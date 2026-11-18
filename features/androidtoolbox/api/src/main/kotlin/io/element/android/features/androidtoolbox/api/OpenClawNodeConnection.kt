/*
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.androidtoolbox.api

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.util.UUID

/**
 * Native protocol v4 node client. The owner serialises incoming frames and cancels this instance when its verified link ends.
 * Neither a Matrix envelope nor a talk-state event grants native device authority.
 */
class OpenClawNodeConnection(
    private val gatewayId: String,
    private val version: String,
    private val identities: OpenClawDeviceIdentityStore,
    private val credentials: OpenClawDeviceAuthStore,
    private val tools: (String) -> OpenClawNodeTools,
    setup: OpenClawNodeSetup?,
    private val isCurrent: suspend () -> Boolean,
    private val send: suspend (JsonObject) -> Unit,
    private val onPaired: suspend () -> Unit,
) {
    enum class Phase { AwaitingChallenge, Authenticating, PublishingTools, Ready, Closed }

    private val mutablePhase = MutableStateFlow(Phase.AwaitingChallenge)
    val phase = mutablePhase.asStateFlow()
    private var bootstrap = setup?.bootstrapToken
    private val initialSetup = setup != null
    private var identity: OpenClawDeviceIdentity? = null
    private var nodeTools: OpenClawNodeTools? = null
    private var previousToken: String? = null
    private val pending = mutableMapOf<String, String>()

    suspend fun receive(frame: JsonObject, deadline: OpenClawInvokeDeadline? = null) {
        checkCurrent()
        when (frame.text("type")) {
            "event" -> when (frame.text("event")) {
                "connect.challenge" -> connect(frame["payload"] as? JsonObject ?: fail())
                "node.invoke.request" -> {
                    check(!initialSetup && mutablePhase.value == Phase.Ready) { "The node is not ready to invoke tools." }
                    check(deadline != null && deadline.remainingMs() > 0) { "The original invocation deadline is unavailable or expired." }
                    val result = checkNotNull(nodeTools).invoke(frame["payload"] as? JsonObject ?: fail(), deadline) ?: fail()
                    checkCurrent()
                    request("node.invoke.result", result)
                }
                else -> Unit // Native tick and other unsolicited events carry no tool authority.
            }
            "res" -> response(frame)
            else -> fail()
        }
    }

    fun close() {
        bootstrap = null
        previousToken = null
        pending.clear()
        nodeTools = null
        mutablePhase.value = Phase.Closed
    }

    private suspend fun connect(challenge: JsonObject) {
        check(mutablePhase.value == Phase.AwaitingChallenge) { "Unexpected native challenge." }
        mutablePhase.value = Phase.Authenticating
        val nonce = challenge.text("nonce")?.trim() ?: fail()
        val timestamp = (challenge["ts"] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull?.takeIf { it >= 0 } ?: fail()
        val local = identities.loadOrCreate().getOrThrow()
        identity = local
        val saved = credentials.loadEntry(slot()).getOrThrow()
        check(saved == null || (saved.role == "node" && saved.scopes.isEmpty())) { "Invalid node credential." }
        previousToken = saved?.token
        val token = if (initialSetup) checkNotNull(bootstrap) else saved?.token ?: fail()
        val proof = identities.signV3(OpenClawDeviceAuthClaim(
            clientId = CLIENT_ID,
            clientMode = "node",
            role = "node",
            scopes = emptyList(),
            signedAtMs = timestamp,
            token = token,
            nonce = nonce,
            platform = "android",
            deviceFamily = "phone",
        )).getOrThrow()
        check(proof.id == local.deviceId && proof.publicKey == local.publicKey) { "The node identity changed." }
        val adapter = tools(local.deviceId)
        nodeTools = adapter
        val catalog = adapter.catalog()
        val commands = (catalog["tools"] as JsonArray).map { (it as JsonObject).text("command") ?: fail() }
        checkCurrent()
        request("connect", buildJsonObject {
            put("minProtocol", 4)
            put("maxProtocol", 4)
            put("client", buildJsonObject {
                put("id", CLIENT_ID)
                put("displayName", "Element")
                put("version", version)
                put("platform", "android")
                put("deviceFamily", "phone")
                put("mode", "node")
            })
            put("role", "node")
            put("scopes", JsonArray(emptyList()))
            put("commands", JsonArray(commands.map(::JsonPrimitive)))
            put("auth", buildJsonObject { put(if (initialSetup) "bootstrapToken" else "deviceToken", token) })
            put("device", buildJsonObject {
                put("id", proof.id)
                put("publicKey", proof.publicKey)
                put("signature", proof.signature)
                put("signedAt", proof.signedAt)
                put("nonce", proof.nonce)
            })
        })
        bootstrap = null
    }

    private suspend fun response(frame: JsonObject) {
        val id = frame.text("id") ?: fail()
        val method = pending.remove(id) ?: return
        val ok = (frame["ok"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
        check(ok == true) { "The native node request was rejected." }
        when (method) {
            "connect" -> {
                check(mutablePhase.value == Phase.Authenticating)
                val hello = frame["payload"] as? JsonObject ?: fail()
                check(hello.text("type") == "hello-ok" && hello["protocol"] == JsonPrimitive(4)) { "Invalid native hello." }
                val auth = hello["auth"] as? JsonObject ?: fail()
                check(auth.text("role") == "node" && auth["scopes"] == JsonArray(emptyList())) { "Only node authority is permitted." }
                val token = auth.text("deviceToken") ?: fail()
                // The returned token is not adopted in memory until its protected durable write succeeds.
                check(credentials.saveToken(slot(), token, replacesStoredToken = previousToken).getOrThrow()) { "The node grant changed." }
                previousToken = null
                checkCurrent()
                if (initialSetup) {
                    // The unpinned bootstrap link only grants a credential. Its RPC surface is empty.
                    onPaired()
                    checkCurrent()
                    mutablePhase.value = Phase.Ready
                } else {
                    mutablePhase.value = Phase.PublishingTools
                    request("node.pluginTools.update", checkNotNull(nodeTools).catalog())
                }
            }
            "node.pluginTools.update" -> {
                check(mutablePhase.value == Phase.PublishingTools)
                mutablePhase.value = Phase.Ready
            }
        }
    }

    private suspend fun request(method: String, params: JsonObject) {
        checkCurrent()
        check(pending.size < 16) { "Too many outstanding node requests." }
        val id = UUID.randomUUID().toString()
        pending[id] = method
        send(buildJsonObject {
            put("type", "req")
            put("id", id)
            put("method", method)
            put("params", params)
        })
    }

    private suspend fun checkCurrent() {
        check(mutablePhase.value != Phase.Closed && isCurrent()) { "The verified node binding ended." }
    }

    private fun slot() = OpenClawDeviceTokenSlot(gatewayId, checkNotNull(identity).deviceId, "node")

    private fun fail(): Nothing = throw IllegalArgumentException("Invalid native node frame.")

    private companion object {
        const val CLIENT_ID = "node-host"
    }
}
