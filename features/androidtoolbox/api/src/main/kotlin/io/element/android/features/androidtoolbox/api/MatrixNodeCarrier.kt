/*
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.androidtoolbox.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.util.UUID

/** Ordered, bounded envelope for native Gateway frames. The caller supplies an authenticated device channel. */
class MatrixNodeCarrier(
    private val roomId: String,
    private val elapsedRealtimeMs: () -> Long,
    private val linkId: String = UUID.randomUUID().toString(),
) {
    private var opened = false
    private var closed = false
    private var sent = 0L
    private var received = 0L
    private var openedAtMs: Long? = null
    private var serverChallengeAtMs: Long? = null

    init {
        require(roomId.isNotBlank() && UUID.fromString(linkId).toString() == linkId)
    }

    @Synchronized
    fun open(): String {
        check(!opened && !closed)
        openedAtMs = elapsedRealtimeMs().also { require(it >= 0) }
        opened = true
        return encode("open")
    }

    @Synchronized
    fun frame(frame: JsonObject): String {
        check(opened && !closed && sent < Long.MAX_VALUE)
        return encode("frame", frame).also { sent++ }
    }

    @Synchronized
    fun close(): String? {
        if (!opened || closed) return null
        closed = true
        return encode("close")
    }

    @Synchronized
    fun receive(content: String): Incoming {
        if (!opened || closed) return Incoming.Ignored
        val envelope = try {
            require(content.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
            Json.parseToJsonElement(content) as? JsonObject ?: error("Invalid carrier.")
        } catch (_: Exception) {
            return fail()
        }
        if (envelope.text("room_id") != roomId || envelope.text("link_id") != linkId) return Incoming.Ignored
        if (envelope["version"] != JsonPrimitive(1)) return fail()
        return when (envelope.text("kind")) {
            "close" -> if (envelope.keys == BASE_KEYS) {
                closed = true
                Incoming.Closed
            } else {
                fail()
            }
            "frame" -> {
                val seq = (envelope["seq"] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
                val frame = envelope["frame"] as? JsonObject
                val invoke = frame != null && frame.text("type") == "event" && frame.text("event") == "node.invoke.request"
                val keys = if (invoke) FRAME_KEYS + "expires_at_ms" else FRAME_KEYS
                when {
                    envelope.keys != keys || seq == null || seq < 0 || frame == null -> fail()
                    seq < received -> Incoming.Ignored
                    seq != received || received == Long.MAX_VALUE -> fail()
                    else -> {
                        if (serverChallengeAtMs == null) {
                            if (seq != 0L || frame.text("type") != "event" || frame.text("event") != "connect.challenge") return fail()
                            val timestamp = ((frame["payload"] as? JsonObject)?.get("ts") as? JsonPrimitive)
                                ?.takeUnless { it.isString }?.longOrNull?.takeIf { it in 0..MAX_SAFE_INTEGER } ?: return fail()
                            serverChallengeAtMs = timestamp
                        }
                        val deadline = if (invoke) {
                            val expires = (envelope["expires_at_ms"] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
                                ?.takeIf { it in 1..MAX_SAFE_INTEGER } ?: return fail()
                            OpenClawInvokeDeadline(expires, ::serverUpperBoundMs).also { if (it.remainingMs() == 0L) return fail() }
                        } else {
                            null
                        }
                        received++
                        Incoming.Frame(frame, deadline)
                    }
                }
            }
            else -> fail()
        }
    }

    private fun serverUpperBoundMs(): Long {
        val elapsed = elapsedRealtimeMs() - checkNotNull(openedAtMs)
        check(elapsed >= 0)
        val server = checkNotNull(serverChallengeAtMs)
        check(elapsed <= MAX_SAFE_INTEGER - server)
        // Counting the entire open/challenge round trip avoids awarding delayed frames extra time.
        return server + elapsed
    }

    private fun fail(): Incoming {
        closed = true
        return Incoming.Invalid
    }

    private fun encode(kind: String, frame: JsonObject? = null): String = buildJsonObject {
        put("version", 1)
        put("room_id", roomId)
        put("link_id", linkId)
        put("kind", kind)
        if (frame != null) {
            put("seq", sent)
            put("frame", frame)
        }
    }.toString().also { require(it.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) }

    sealed interface Incoming {
        class Frame(val value: JsonObject, val deadline: OpenClawInvokeDeadline?) : Incoming
        data object Ignored : Incoming
        data object Closed : Incoming
        data object Invalid : Incoming
    }

    companion object {
        const val EVENT_TYPE = "io.openclaw.node"
        const val MAX_BYTES = 12 * 1024
        private val BASE_KEYS = setOf("version", "room_id", "link_id", "kind")
        private val FRAME_KEYS = BASE_KEYS + setOf("seq", "frame")
    }
}

internal fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
