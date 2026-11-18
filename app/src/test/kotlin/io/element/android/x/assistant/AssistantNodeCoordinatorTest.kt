/*
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.x.assistant

import com.google.common.truth.Truth.assertThat
import io.element.android.features.androidtoolbox.api.AndroidAppLaunch
import io.element.android.features.androidtoolbox.api.AndroidToolLauncher
import io.element.android.features.androidtoolbox.api.AndroidToolLauncherFactory
import io.element.android.features.androidtoolbox.api.AndroidToolResult
import io.element.android.features.androidtoolbox.api.AssistantNodePairing
import io.element.android.features.androidtoolbox.api.MatrixNodeCarrier
import io.element.android.features.androidtoolbox.api.OpenClawDeviceAuthClaim
import io.element.android.features.androidtoolbox.api.OpenClawDeviceAuthEntry
import io.element.android.features.androidtoolbox.api.OpenClawDeviceAuthStore
import io.element.android.features.androidtoolbox.api.OpenClawDeviceIdentity
import io.element.android.features.androidtoolbox.api.OpenClawDeviceIdentityStore
import io.element.android.features.androidtoolbox.api.OpenClawDeviceProof
import io.element.android.features.androidtoolbox.api.OpenClawDeviceTokenSlot
import io.element.android.features.androidtoolbox.api.OpenClawNodeBinding
import io.element.android.features.androidtoolbox.api.OpenClawNodeBindingStore
import io.element.android.features.androidtoolbox.impl.DefaultAndroidToolbox
import io.element.android.features.androidtoolbox.impl.GoogleMapsToolModule
import io.element.android.libraries.matrix.api.MatrixClient
import io.element.android.libraries.matrix.api.MatrixClientProvider
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.SessionId
import io.element.android.libraries.matrix.api.device.EncryptedDeviceChannel
import io.element.android.libraries.matrix.api.device.EncryptedDeviceChannelFactory
import io.element.android.libraries.matrix.api.device.EncryptedDeviceMessage
import io.element.android.libraries.matrix.api.device.MatrixDevicePeer
import io.element.android.libraries.matrix.api.room.JoinedRoom
import io.element.android.libraries.preferences.api.store.AssistantChat
import io.element.android.tests.testutils.testCoroutineDispatchers
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Test
import java.util.Base64

class AssistantNodeCoordinatorTest {
    @Test
    fun `settings pairing uses strict targeted channel subscribes first and cannot execute an unsolicited Maps command`() = runTest {
        val fixture = Fixture(this)
        val result = async { fixture.pair() }
        fixture.gateway.catalogPublished.await()
        // The command follows the catalog ack immediately. Setup must close without executing it.
        fixture.gateway.invocation()
        assertThat(result.await()).isEqualTo(AssistantNodePairing.Outcome.Paired)
        assertThat(fixture.bindingSaved).isTrue()
        assertThat(fixture.actions).isEmpty()
        assertThat(fixture.gateway.closed).isTrue()
        assertThat(fixture.gateway.connectAuth!!.keys).containsExactly("bootstrapToken")
        assertThat(fixture.gateway.peer!!.requireVerified).isTrue()
        assertThat(fixture.gateway.peer!!.userId.value).isEqualTo(fixture.binding.peerUserId)
        assertThat(fixture.gateway.peer!!.deviceId.value).isEqualTo(fixture.binding.peerDeviceId)
    }

    @Test
    fun `ready call reconnect traverses carrier native catalog Maps and native result then revokes on call end`() = runTest {
        val fixture = Fixture(this)
        fixture.bindingSaved = true
        fixture.deviceToken = "saved-device-token"
        val running = launch { fixture.coordinator.runAssistant(fixture.binding.sessionId, fixture.binding.roomId) { _, _ -> fixture.current } }
        fixture.gateway.catalogPublished.await()
        runCurrent()
        fixture.gateway.invocation()
        val result = fixture.gateway.result.await()
        assertThat(result["ok"]).isEqualTo(JsonPrimitive(true))
        assertThat(result["payload"]!!.jsonObject["status"]).isEqualTo(JsonPrimitive("submitted"))
        assertThat(fixture.actions.single().packageName).isEqualTo("com.google.android.apps.maps")
        assertThat(fixture.actions.single().uri).contains("Sample%20museum")
        assertThat(fixture.gateway.connectAuth!!.keys).containsExactly("deviceToken")
        fixture.current = false
        fixture.gateway.invocation(id = "after-call")
        running.join()
        assertThat(fixture.actions).hasSize(1)
        assertThat(fixture.gateway.closed).isTrue()
    }

    @Test
    fun `native hello cannot escalate role and sequence gap cannot save pairing`() = runTest {
        for (invalidRole in listOf(true, false)) {
            val fixture = Fixture(this)
            fixture.gateway.operatorHello = invalidRole
            fixture.gateway.skipSequence = !invalidRole
            assertThat(fixture.pair()).isEqualTo(AssistantNodePairing.Outcome.Failed)
            assertThat(fixture.bindingSaved).isFalse()
            assertThat(fixture.actions).isEmpty()
        }
    }

    @Test
    fun `dismissal while waiting for challenge closes subscription and does not send bootstrap`() = runTest {
        val fixture = Fixture(this)
        fixture.gateway.answerOpen = false
        val pairing = launch { fixture.pair() }
        fixture.gateway.opened.await()
        pairing.cancelAndJoin()
        assertThat(fixture.gateway.connectAuth).isNull()
        assertThat(fixture.gateway.detached.isCompleted).isTrue()
        assertThat(fixture.gateway.closed).isTrue()
        assertThat(fixture.bindingSaved).isFalse()
    }

    @Test
    fun `hidden surface and revoked Maps grant prevent side effects on authenticated link`() = runTest {
        for (hide in listOf(true, false)) {
            val fixture = Fixture(this)
            fixture.bindingSaved = true
            fixture.deviceToken = "saved-device-token"
            val running = backgroundScope.launch {
                fixture.coordinator.runAssistant(fixture.binding.sessionId, fixture.binding.roomId) { _, _ -> fixture.current }
            }
            fixture.gateway.catalogPublished.await()
            runCurrent()
            if (hide) fixture.visible = false else fixture.mapsEnabled = false
            fixture.gateway.invocation()
            assertThat(fixture.gateway.result.await()["ok"]).isEqualTo(JsonPrimitive(false))
            assertThat(fixture.actions).isEmpty()
            running.cancelAndJoin()
        }
    }

    private class Fixture(scope: TestScope) {
        val binding = OpenClawNodeBinding("@sample:example.org", "!sample:example.org", "@assistant:example.org", "SAMPLEDEVICE")
        var bindingSaved = false
        var deviceToken: String? = null
        var current = true
        var visible = true
        var mapsEnabled = true
        val actions = mutableListOf<AndroidAppLaunch>()
        val gateway = Gateway()
        private val room = mockk<JoinedRoom>(relaxed = true) {
            every { identityStateChangesFlow } returns emptyFlow()
        }
        private val client = mockk<MatrixClient> {
            coEvery { getJoinedRoom(RoomId(binding.roomId)) } returns room
        }
        private val peers = mockk<AssistantNodePeerResolver> {
            coEvery { resolve(binding.sessionId, binding.roomId, binding.peerDeviceId) } answers {
                if (!current) {
                    null
                } else {
                    AssistantChat(
                    SessionId(binding.sessionId),
                    RoomId(binding.roomId),
                    voiceEnabled = true,
                    toolCapabilities = if (mapsEnabled) setOf("maps") else emptySet(),
                ) to binding
                }
            }
        }
        private val identities = object : OpenClawDeviceIdentityStore {
            override suspend fun loadOrCreate() = Result.success(OpenClawDeviceIdentity("sample-node", "sample-key"))
            override suspend fun signV3(claim: OpenClawDeviceAuthClaim) = Result.success(
                OpenClawDeviceProof("sample-node", "sample-key", "sample-proof", claim.signedAtMs, claim.nonce)
            )
        }
        private val tokens = object : OpenClawDeviceAuthStore {
            override suspend fun loadEntry(slot: OpenClawDeviceTokenSlot) = Result.success(
                deviceToken?.let { OpenClawDeviceAuthEntry(it, "node", emptyList(), 1) }
            )
            override suspend fun saveToken(slot: OpenClawDeviceTokenSlot, token: String, scopes: List<String>, replacesStoredToken: String?): Result<Boolean> {
                check(slot.gatewayId == binding.gatewayId && slot.role == "node" && scopes.isEmpty())
                deviceToken = token
                return Result.success(true)
            }
            override suspend fun clearToken(slot: OpenClawDeviceTokenSlot, onlyIfToken: String?) = Result.success(false)
        }
        private val store = object : OpenClawNodeBindingStore {
            override suspend fun load() = Result.success(binding.takeIf { bindingSaved })
            override suspend fun save(binding: OpenClawNodeBinding): Result<Unit> {
                check(binding == this@Fixture.binding)
                bindingSaved = true
                return Result.success(Unit)
            }
        }
        private val surface = AssistantToolSurface(AndroidToolLauncherFactory { isVisible ->
            object : AndroidToolLauncher {
                override fun isInteractive() = isVisible()
                override fun isAppAvailable(packageName: String) = packageName == "com.google.android.apps.maps"
                override fun submit(action: AndroidAppLaunch): AndroidToolResult {
                    check(isInteractive())
                    actions.add(action)
                    return AndroidToolResult.Success(buildJsonObject { put("status", "submitted") })
                }
            }
        }).apply { bind(this) { visible } }
        val coordinator = AssistantNodeCoordinator(
            peers,
            mockk<MatrixClientProvider> { coEvery { getOrRestore(SessionId(binding.sessionId)) } returns Result.success(client) },
            EncryptedDeviceChannelFactory { _, peer, types ->
                check(types == setOf(MatrixNodeCarrier.EVENT_TYPE))
                gateway.peer = peer
                gateway
            },
            identities,
            tokens,
            store,
            DefaultAndroidToolbox(setOf(GoogleMapsToolModule()), scope.testCoroutineDispatchers()),
            surface,
        )

        suspend fun pair(): AssistantNodePairing.Outcome {
            val code = Base64.getUrlEncoder().withoutPadding().encodeToString(
                """{"url":"wss://example.org","bootstrapToken":"synthetic-bootstrap"}""".toByteArray()
            )
            return coordinator.pair(binding.sessionId, binding.roomId, binding.peerDeviceId, code)
        }
    }

    private class Gateway : EncryptedDeviceChannel {
        var peer: MatrixDevicePeer? = null
        var connectAuth: JsonObject? = null
        var operatorHello = false
        var skipSequence = false
        var answerOpen = true
        var closed = false
        val opened = CompletableDeferred<Unit>()
        val catalogPublished = CompletableDeferred<Unit>()
        val result = CompletableDeferred<JsonObject>()
        val detached = CompletableDeferred<Unit>()
        private val subscribed = CompletableDeferred<Unit>()
        private val input = Channel<EncryptedDeviceMessage>(64)
        private var envelope: JsonObject? = null
        private var seq = 0L
        override val messages: Flow<EncryptedDeviceMessage> = flow {
            subscribed.complete(Unit)
            try {
                for (message in input) emit(message)
            } finally {
                detached.complete(Unit)
            }
        }

        override suspend fun awaitReady() = subscribed.await()

        override suspend fun send(eventType: String, contentJson: String): Result<Unit> {
            check(subscribed.isCompleted && eventType == MatrixNodeCarrier.EVENT_TYPE)
            val value = Json.parseToJsonElement(contentJson) as JsonObject
            when (value["kind"]) {
                JsonPrimitive("open") -> {
                    envelope = value
                    opened.complete(Unit)
                    if (answerOpen) {
                        frame(buildJsonObject {
                        put("type", "event")
                        put("event", "connect.challenge")
                        put("payload", buildJsonObject {
                            put("nonce", "sample-nonce")
                            put("ts", 1234)
                        })
                    })
                    }
                }
                JsonPrimitive("close") -> closed = true
                JsonPrimitive("frame") -> {
                    val native = value["frame"]!!.jsonObject
                    when (native["method"]) {
                        JsonPrimitive("connect") -> {
                            connectAuth = native["params"]!!.jsonObject["auth"]!!.jsonObject
                            if (skipSequence) seq++
                            ack(native, buildJsonObject {
                                put("type", "hello-ok")
                                put("protocol", 4)
                                put("auth", buildJsonObject {
                                    put("role", if (operatorHello) "operator" else "node")
                                    put("scopes", Json.parseToJsonElement("[]"))
                                    put("deviceToken", "synthetic-device-token")
                                })
                            })
                        }
                        JsonPrimitive("node.pluginTools.update") -> {
                            ack(native, JsonObject(emptyMap()))
                            catalogPublished.complete(Unit)
                        }
                        JsonPrimitive("node.invoke.result") -> {
                            result.complete(native["params"]!!.jsonObject)
                            ack(native, JsonObject(emptyMap()))
                        }
                    }
                }
            }
            return Result.success(Unit)
        }

        suspend fun invocation(id: String = "sample-invoke") = frame(buildJsonObject {
            put("type", "event")
            put("event", "node.invoke.request")
            put("payload", buildJsonObject {
                put("id", id)
                put("nodeId", "sample-node")
                put("command", "maps.search")
                put("paramsJSON", """{"query":"Sample museum"}""")
            })
        })

        private suspend fun ack(request: JsonObject, payload: JsonObject) = frame(buildJsonObject {
            put("type", "res")
            put("id", checkNotNull(request["id"]))
            put("ok", true)
            put("payload", payload)
        })

        private suspend fun frame(value: JsonObject) {
            val base = checkNotNull(envelope)
            val content = JsonObject(base + mapOf("kind" to JsonPrimitive("frame"), "seq" to JsonPrimitive(seq++), "frame" to value))
            input.send(EncryptedDeviceMessage(MatrixNodeCarrier.EVENT_TYPE, content.toString()))
        }
    }
}
