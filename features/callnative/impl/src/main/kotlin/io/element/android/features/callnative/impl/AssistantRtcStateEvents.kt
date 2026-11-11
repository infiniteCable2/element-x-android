/*
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.callnative.impl

import io.element.android.features.callnative.api.MatrixRtcApplicationEvents
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.matrix.rtc.FfiEventIn
import org.matrix.rtc.FfiToDeviceMessageIn
import java.util.UUID

/** Matches the same peer identity as the active native call roster. */
data class AssistantVoicePeer(val userId: String, val deviceId: String, val memberId: String)

data class AssistantVoicePresentation(
    val readyPeers: Set<AssistantVoicePeer> = emptySet(),
    val talkStates: Map<AssistantVoicePeer, TalkSessionState> = emptyMap(),
)

interface AssistantVoiceAttempt : AutoCloseable {
    val readyPeers: StateFlow<Set<AssistantVoicePeer>>
    val captions: StateFlow<List<AssistantVoiceCaption>>
    val talkStates: StateFlow<Map<AssistantVoicePeer, TalkSessionState>>
    val presentation: StateFlow<AssistantVoicePresentation>
}

data class AssistantVoiceCaption(val sequence: Long, val role: Role, val text: String) {
    enum class Role { User, Assistant }
}

/**
 * Adds the Assistant Voice presentation marker to the native default-room membership.
 * Element Call currently uses the pre-sticky Room State format; keep everything else in its
 * existing join/renew/leave flow. The marker grants no permission to use the remote agent.
 */
internal class AssistantRtcStateEvents(
    private val userId: String,
    private val nowMs: () -> ULong = { System.currentTimeMillis().toULong() },
) : MatrixRtcApplicationEvents {
    override val toDeviceEventTypes = setOf(CAPTION_TYPE, TALK_TYPE)
    private var current: Attempt? = null

    @Synchronized
    fun begin(roomId: String, peerId: String): AssistantVoiceAttempt? {
        if (current != null) return null
        return Attempt(roomId, peerId).also { current = it }
    }

    @Synchronized
    override fun outgoing(roomId: String, eventType: String, stateKey: String, deviceId: String, contentJson: String): String {
        val attempt = current?.takeIf { it.roomId == roomId } ?: return contentJson
        if (!eventType.isCallMemberType() || !isDefaultStateKey(stateKey, userId, deviceId)) return contentJson
        val content = parseObject(contentJson) ?: return contentJson
        // A leave is an empty object. Only decorate our actual default-slot join and renewals.
        if (!content.isDefaultCall() || content.string("device_id") != deviceId) return contentJson

        val caller = AssistantVoicePeer(userId, deviceId, content.memberId(userId, deviceId))
        if (attempt.caller != caller) {
            attempt.caller = caller
            attempt.mutableReadyPeers.value = emptySet()
            attempt.captionDevices = emptySet()
            attempt.clearCaptions()
            attempt.talk.clear()
            attempt.publishPresentation()
        }
        val marker = buildJsonObject {
            (content[NAMESPACE] as? JsonObject)?.forEach { (key, value) -> put(key, value) }
            put("version", VERSION)
            put("purpose", PURPOSE)
            put("request_id", attempt.requestId)
            put("captions", true)
            put("talk_events", true)
        }
        return JsonObject(content + (NAMESPACE to marker)).toString()
    }

    @Synchronized
    override fun received(roomId: String, eventType: String, events: List<FfiEventIn>) {
        val attempt = current?.takeIf { it.roomId == roomId && eventType.isCallMemberType() } ?: return
        val caller = attempt.caller ?: return
        val now = nowMs()
        // The SDK supplies the complete current state set, so a leave replaces readiness.
        val ready = events.mapNotNull { event ->
            if (event.sender != attempt.peerId) return@mapNotNull null
            val content = parseObject(event.contentJson) ?: return@mapNotNull null
            val deviceId = content.string("device_id")?.takeIf(String::isNotEmpty) ?: return@mapNotNull null
            if (!isDefaultStateKey(event.stateKey, event.sender, deviceId) || !content.isDefaultCall()) return@mapNotNull null
            val created = content.unsigned("created_ts")?.coerceAtMost(event.originServerTs) ?: event.originServerTs
            val expires = content.unsigned("expires") ?: DEFAULT_EXPIRES_MS
            if (now >= created && now - created >= expires) return@mapNotNull null

            val marker = content[NAMESPACE] as? JsonObject ?: return@mapNotNull null
            if (marker["version"] != JsonPrimitive(VERSION) ||
                marker.string("purpose") != PURPOSE ||
                marker.string("request_id") != attempt.requestId ||
                marker.string("state") != "ready"
            ) return@mapNotNull null
            val replyTo = marker["caller"] as? JsonObject ?: return@mapNotNull null
            if (replyTo.string("user_id") != caller.userId ||
                replyTo.string("device_id") != caller.deviceId ||
                replyTo.string("member_id") != caller.memberId
            ) return@mapNotNull null
            val talkSession = marker.string("talk_session_id")?.takeIf { it.isNotEmpty() }
                ?.takeIf { marker["talk_events"] == JsonPrimitive(true) }
            Triple(
                AssistantVoicePeer(event.sender, deviceId, content.memberId(event.sender, deviceId)),
                marker["captions"] == JsonPrimitive(true),
                talkSession,
            )
        }
        val captionDevices = ready.filter { (_, offersCaptions, _) -> offersCaptions }.mapTo(mutableSetOf()) { (peer, _, _) -> peer.deviceId }
        if (!captionDevices.containsAll(attempt.captionDevices)) attempt.clearCaptions()
        attempt.captionDevices = captionDevices
        attempt.talk.updateOffers(
            ready.mapNotNull { (peer, _, session) -> session?.let { peer to it } }.toMap(),
            ready.mapTo(mutableSetOf()) { (peer, _, _) -> peer.deviceId },
        )
        attempt.mutableReadyPeers.value = ready.mapTo(mutableSetOf()) { (peer, _, _) -> peer }
        attempt.publishPresentation()
        attempt.publishCaptions()
    }

    @Synchronized
    override fun receivedToDevice(event: FfiToDeviceMessageIn) {
        val attempt = current?.takeIf { it.caller != null && it.peerId == event.sender } ?: return
        if (event.eventType !in toDeviceEventTypes || !event.encryption.encrypted || event.encryption.senderConfirmed != true) return
        val deviceId = event.encryption.senderDeviceId ?: return
        if (event.contentJson.length > MAX_CAPTION_JSON) return
        val payload = parseObject(event.contentJson) ?: return
        if (payload["version"] != JsonPrimitive(VERSION) ||
            payload.string("room_id") != attempt.roomId || payload.string("request_id") != attempt.requestId
        ) return
        if (event.eventType == TALK_TYPE) {
            val talk = (payload["event"] as? JsonObject)?.parseTalkEvent() ?: return
            attempt.talk.receive(deviceId, talk)
            attempt.publishPresentation()
            return
        }
        if (attempt.readyPeers.value.any { it.deviceId == deviceId } && deviceId !in attempt.captionDevices) return
        val sequence = payload.unsigned("seq")?.takeIf { it in 1uL..Long.MAX_VALUE.toULong() }?.toLong() ?: return
        val role = when (payload.string("role")) {
            "user" -> AssistantVoiceCaption.Role.User
            "assistant" -> AssistantVoiceCaption.Role.Assistant
            else -> return
        }
        val caption = payload.string("text")?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_CAPTION_CHARS } ?: return
        attempt.addCaption(deviceId, AssistantVoiceCaption(sequence, role, caption))
    }

    @Synchronized
    private fun end(attempt: Attempt) {
        if (current === attempt) current = null
        attempt.mutableReadyPeers.value = emptySet()
        attempt.clearCaptions()
        attempt.talk.clear()
        attempt.publishPresentation()
    }

    private inner class Attempt(val roomId: String, val peerId: String) : AssistantVoiceAttempt {
        val requestId: String = UUID.randomUUID().toString()
        var caller: AssistantVoicePeer? = null
        val mutableReadyPeers = MutableStateFlow<Set<AssistantVoicePeer>>(emptySet())
        override val readyPeers = mutableReadyPeers.asStateFlow()
        val talk = AssistantTalkEvents()
        override val talkStates = talk.states
        private val mutablePresentation = MutableStateFlow(AssistantVoicePresentation())
        override val presentation = mutablePresentation.asStateFlow()

        fun publishPresentation() {
            mutablePresentation.value = AssistantVoicePresentation(readyPeers.value, talkStates.value)
        }
        var captionDevices: Set<String> = emptySet()
        private val mutableCaptions = MutableStateFlow<List<AssistantVoiceCaption>>(emptyList())
        override val captions = mutableCaptions.asStateFlow()
        // The membership update and the first Olm message can reach sync in either order.
        private val pending = ArrayDeque<Pair<String, AssistantVoiceCaption>>()

        fun addCaption(deviceId: String, caption: AssistantVoiceCaption) {
            if (pending.size == MAX_CAPTIONS) pending.removeFirst()
            pending.addLast(deviceId to caption)
            publishCaptions()
        }

        fun publishCaptions() {
            val existing = mutableCaptions.value
            val bySequence = existing.associateByTo(sortedMapOf()) { it.sequence }
            pending.removeAll { (deviceId, caption) ->
                if (deviceId !in captionDevices) return@removeAll readyPeers.value.any { it.deviceId == deviceId }
                bySequence.putIfAbsent(caption.sequence, caption)
                true
            }
            mutableCaptions.value = bySequence.values.toList().takeLast(MAX_CAPTIONS)
        }

        fun clearCaptions() {
            pending.clear()
            mutableCaptions.value = emptyList()
        }

        override fun close() = end(this)
    }
}

private const val NAMESPACE = "io.openclaw.conversation"
private const val VERSION = 1
private const val PURPOSE = "assistant-voice"
private const val DEFAULT_EXPIRES_MS = 14_400_000uL
private const val CAPTION_TYPE = "$NAMESPACE.caption"
private const val TALK_TYPE = "$NAMESPACE.talk"
private const val MAX_CAPTIONS = 128
private const val MAX_CAPTION_CHARS = 2_048
private const val MAX_CAPTION_JSON = 16_384

private fun String.isCallMemberType(): Boolean = this == "org.matrix.msc3401.call.member" || this == "m.call.member"

private fun isDefaultStateKey(stateKey: String?, userId: String, deviceId: String): Boolean =
    stateKey?.removePrefix("_") == "${userId}_${deviceId}_m.call"

private fun JsonObject.isDefaultCall(): Boolean =
    string("application") == "m.call" && string("call_id") == "" && string("scope") == "m.room"

private fun JsonObject.memberId(userId: String, deviceId: String): String =
    string("membershipID")?.takeIf(String::isNotEmpty) ?: "$userId:$deviceId"

private fun JsonObject.string(key: String): String? =
    (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.unsigned(key: String): ULong? =
    (get(key) as? JsonPrimitive)?.takeUnless { it.isString }?.content?.toULongOrNull()

private fun parseObject(contentJson: String): JsonObject? = try {
    Json.parseToJsonElement(contentJson) as? JsonObject
} catch (_: SerializationException) {
    null
}
