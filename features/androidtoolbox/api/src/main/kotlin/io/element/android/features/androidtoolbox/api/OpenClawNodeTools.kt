/*
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.androidtoolbox.api

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlin.time.TimeSource

/**
 * OpenClaw's native node.pluginTools.update / node.invoke.request / node.invoke.result shapes.
 * The transport must authenticate and bind [nodeId] and this session before calling this adapter.
 * This is not an authentication mechanism or a Matrix command transport.
 */
class OpenClawNodeTools(private val nodeId: String, private val session: AndroidToolSession) {
    private val mutex = Mutex()
    private val requests = mutableMapOf<String, Pair<JsonObject, JsonObject>>()

    init {
        require(nodeId.isNotBlank())
    }

    /** Params for the native node.pluginTools.update method; replaces the previous catalog. */
    suspend fun catalog(): JsonObject = buildJsonObject {
        put("tools", buildJsonArray {
            session.catalog().forEach { tool ->
                add(buildJsonObject {
                    put("pluginId", "android-toolbox")
                    put("name", tool.command.replace('.', '_'))
                    put("command", tool.command)
                    put("description", tool.description)
                    put("parameters", tool.parameters)
                })
            }
        })
    }

    /** Payload of node.invoke.request in, params for node.invoke.result out. */
    suspend fun invoke(request: JsonObject): JsonObject? {
        val received = TimeSource.Monotonic.markNow()
        return mutex.withLock {
            val id = request.text("id") ?: return@withLock null
            requests[id]?.let { (original, response) ->
                return@withLock if (original == request) response else result(id, failure("INVALID_REQUEST", "This invocation ID was already used."))
            }
            if (requests.size >= MAX_REQUESTS) return@withLock result(id, failure("UNAVAILABLE", "The invocation limit was reached."))
            val command = request.text("command")
            val key = if ("idempotencyKey" in request) request.text("idempotencyKey") else id
            val timeout = (request["timeoutMs"] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
            val params = try {
                if ("paramsJSON" !in request) JsonObject(emptyMap()) else {
                    request.text("paramsJSON")?.takeIf { it.length <= MAX_PARAMS }?.let(Json::parseToJsonElement) as? JsonObject
                }
            } catch (_: SerializationException) {
                null
            }
            val invalid = request.keys.any { it !in REQUEST_KEYS } || request.text("nodeId") != nodeId || command == null || key == null ||
                params == null || ("timeoutMs" in request && (timeout == null || timeout < 0)) ||
                ("sessionKey" in request && request.text("sessionKey") == null)
            if (invalid) return@withLock result(id, failure("INVALID_REQUEST", "Invalid native node invocation."))
            // Reserve before dispatch: a cancelled handoff must not become an automatic retry.
            requests[id] = request to result(id, failure("UNAVAILABLE", "The operation was interrupted; its outcome is unknown."))
            try {
                val execution = if (timeout == null) session.execute(checkNotNull(key), checkNotNull(command), checkNotNull(params)) else {
                    val remaining = (timeout - received.elapsedNow().inWholeMilliseconds).coerceAtLeast(0)
                    withTimeoutOrNull(remaining) { session.execute(checkNotNull(key), checkNotNull(command), checkNotNull(params)) }
                }
                val response = when (execution) {
                    is AndroidToolResult.Success -> buildJsonObject {
                        put("ok", true)
                        put("payload", execution.payload)
                    }
                    is AndroidToolResult.Failure -> failure(execution.reason.name, execution.message)
                    null -> failure("TIMEOUT", "The invocation deadline expired; do not assume the app action completed.")
                }
                result(id, response).also { requests[id] = request to it }
            } catch (error: CancellationException) {
                throw error
            }
        }
    }

    private fun result(id: String, content: JsonObject) = buildJsonObject {
        put("id", id)
        put("nodeId", nodeId)
        content.forEach { (key, value) -> put(key, value) }
    }

    private fun failure(code: String, message: String) = buildJsonObject {
        put("ok", false)
        put("error", buildJsonObject {
            put("code", code)
            put("message", message)
        })
    }

    private fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }

    private companion object {
        const val MAX_REQUESTS = 128
        const val MAX_PARAMS = 16_384
        val REQUEST_KEYS = setOf("id", "nodeId", "command", "paramsJSON", "timeoutMs", "idempotencyKey", "sessionKey")
    }
}
