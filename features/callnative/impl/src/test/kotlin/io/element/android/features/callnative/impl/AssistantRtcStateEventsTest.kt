/*
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.callnative.impl

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import org.matrix.rtc.FfiEventEncryption
import org.matrix.rtc.FfiEventIn
import org.matrix.rtc.FfiToDeviceMessageIn
import java.util.UUID

class AssistantRtcStateEventsTest {
    private val events = AssistantRtcStateEvents(CALLER) { NOW }

    @Test
    fun `only the assistant join and renewals carry a request ID`() {
        assertThat(outgoing()).isEqualTo(JOIN)
        val attempt = checkNotNull(events.begin(ROOM, PEER))
        assertThat(outgoing(room = OTHER_ROOM)).isEqualTo(JOIN)
        assertThat(outgoing(content = "{}")).isEqualTo("{}")
        val join = Json.parseToJsonElement(outgoing()).jsonObject
        val marker = join.getValue(NAMESPACE).jsonObject
        val request = marker.getValue("request_id").jsonPrimitive.content
        assertThat(UUID.fromString(request).version()).isEqualTo(4)
        assertThat(marker["captions"]).isEqualTo(JsonPrimitive(true))
        assertThat(marker["talk_events"]).isEqualTo(JsonPrimitive(true))
        assertThat(marker["existing"]?.jsonPrimitive?.content).isEqualTo("preserved")
        assertThat(JsonObject(join - NAMESPACE)).isEqualTo(JsonObject(Json.parseToJsonElement(JOIN).jsonObject - NAMESPACE))
        assertThat(requestId()).isEqualTo(request)
        attempt.close()
        assertThat(outgoing()).isEqualTo(JOIN)
    }

    @Test
    fun `only a current matching ready reply from the expected peer is exposed to the roster`() {
        val attempt = checkNotNull(events.begin(ROOM, PEER))
        val id = requestId()
        fun deliver(event: FfiEventIn, room: String = ROOM) = events.received(room, MEMBER_TYPE, listOf(event))

        deliver(reply(id), room = OTHER_ROOM)
        assertThat(attempt.readyPeers.value).isEmpty()
        listOf(
            reply(id, state = "preparing"),
            reply(id, sender = "@someone:example.org"),
            reply(id, callerDevice = "OTHER"),
            reply(id, callerMember = "old-membership"),
            reply(id, callId = "other-slot"),
            reply(id, origin = 1uL),
        ).forEach {
            deliver(it)
            assertThat(attempt.readyPeers.value).isEmpty()
        }

        deliver(reply(id))
        assertThat(attempt.readyPeers.value).containsExactly(AssistantVoicePeer(PEER, PEER_DEVICE, "$PEER:$PEER_DEVICE"))
        events.received(ROOM, MEMBER_TYPE, emptyList())
        assertThat(attempt.readyPeers.value).isEmpty()
    }

    @Test
    fun `a new invocation ignores the previous ready even on the same device`() {
        val previous = checkNotNull(events.begin(ROOM, PEER))
        val oldId = requestId()
        previous.close()
        val attempt = checkNotNull(events.begin(ROOM, PEER))
        val currentId = requestId()
        assertThat(currentId).isNotEqualTo(oldId)
        events.received(ROOM, MEMBER_TYPE, listOf(reply(oldId)))
        assertThat(attempt.readyPeers.value).isEmpty()
        events.received(ROOM, MEMBER_TYPE, listOf(reply(currentId)))
        assertThat(attempt.readyPeers.value).isNotEmpty()
    }

    @Test
    fun `encrypted captions can arrive before ready and appear once in spoken order`() {
        val attempt = checkNotNull(events.begin(ROOM, PEER))
        val id = requestId()
        val answer = caption(id, 2, "assistant", "A simple soup.")
        events.receivedToDevice(answer)
        assertThat(attempt.captions.value).isEmpty()

        events.received(ROOM, MEMBER_TYPE, listOf(reply(id, offersCaptions = true)))
        events.receivedToDevice(caption(id, 1, "user", "What should I cook?"))
        events.receivedToDevice(answer)
        assertThat(attempt.captions.value).containsExactly(
            AssistantVoiceCaption(1, AssistantVoiceCaption.Role.User, "What should I cook?"),
            AssistantVoiceCaption(2, AssistantVoiceCaption.Role.Assistant, "A simple soup."),
        ).inOrder()
        attempt.close()
        assertThat(attempt.captions.value).isEmpty()
    }

    @Test
    fun `captions are scoped to the current room request and encrypted answering device`() {
        val attempt = checkNotNull(events.begin(ROOM, PEER))
        val id = requestId()
        events.received(ROOM, MEMBER_TYPE, listOf(reply(id, offersCaptions = true)))
        val text = "Not a caption for this call"
        val valid = caption(id, 1, "assistant", text)
        listOf(
            valid.copy(sender = "@someone:example.org"),
            valid.copy(encryption = valid.encryption.copy(senderDeviceId = "OTHERDEVICE")),
            valid.copy(encryption = valid.encryption.copy(senderConfirmed = false)),
            valid.copy(encryption = FfiEventEncryption(encrypted = false, senderDeviceId = null, senderConfirmed = null)),
            caption("prior-request", 1, "assistant", text),
            caption(id, 1, "assistant", text, room = OTHER_ROOM),
        ).forEach(events::receivedToDevice)
        assertThat(attempt.captions.value).isEmpty()
    }

    @Test
    fun `audio ready alone does not enable captions and a withdrawn offer clears them`() {
        val attempt = checkNotNull(events.begin(ROOM, PEER))
        val id = requestId()
        events.received(ROOM, MEMBER_TYPE, listOf(reply(id)))
        events.receivedToDevice(caption(id, 1, "assistant", "Hello"))
        assertThat(attempt.readyPeers.value).isNotEmpty()
        assertThat(attempt.captions.value).isEmpty()

        events.received(ROOM, MEMBER_TYPE, listOf(reply(id, offersCaptions = true)))
        assertThat(attempt.captions.value).isEmpty()
        events.receivedToDevice(caption(id, 2, "assistant", "Text offered"))
        assertThat(attempt.captions.value).hasSize(1)
        events.received(ROOM, MEMBER_TYPE, listOf(reply(id, offersCaptions = false)))
        assertThat(attempt.readyPeers.value).isNotEmpty()
        assertThat(attempt.captions.value).isEmpty()
    }

    @Test
    fun `talk activity waits for the exact ready device session and explicit offer`() {
        val attempt = checkNotNull(events.begin(ROOM, PEER))
        val id = requestId()
        events.receivedToDevice(talk(id, talkActivityEvent(2)))
        assertThat(attempt.talkStates.value).isEmpty()
        events.received(ROOM, MEMBER_TYPE, listOf(reply(id, offersTalk = "talk-session")))
        assertThat(attempt.talkStates.value.values.single().activity?.input?.phase).isEqualTo(TalkActivity.InputPhase.SPEECH)
        events.received(ROOM, MEMBER_TYPE, listOf(reply(id)))
        assertThat(attempt.talkStates.value).isEmpty()
        events.received(ROOM, MEMBER_TYPE, listOf(reply(id, offersTalk = "talk-session")))
        assertThat(attempt.talkStates.value.values.single().activity).isNull()
        events.receivedToDevice(talk(id, talkActivityEvent(2)))
        assertThat(attempt.talkStates.value.values.single().activity).isNull()
        events.receivedToDevice(talk(id, talkActivityEvent(100)))
        assertThat(attempt.talkStates.value.values.single().sequence).isEqualTo(100)
        assertThat(attempt.talkStates.value.values.single().activity).isNotNull()
    }

    @Test
    fun `talk rejects unauthenticated devices other sessions and old invocation envelopes`() {
        val attempt = checkNotNull(events.begin(ROOM, PEER))
        val id = requestId()
        events.received(ROOM, MEMBER_TYPE, listOf(reply(id, offersTalk = "talk-session")))
        val valid = talk(id, talkActivityEvent(1))
        val anotherSession = JsonObject(talkActivityEvent(1) + ("sessionId" to JsonPrimitive("other-session")))
        listOf(
            valid.copy(sender = "@other:example.org"),
            valid.copy(encryption = valid.encryption.copy(senderDeviceId = "OTHER")),
            valid.copy(encryption = valid.encryption.copy(senderDeviceId = null)),
            valid.copy(encryption = valid.encryption.copy(senderConfirmed = false)),
            valid.copy(encryption = valid.encryption.copy(encrypted = false)),
            talk("old-request", talkActivityEvent(1)),
            talk(id, talkActivityEvent(1), room = OTHER_ROOM),
            talk(id, anotherSession),
        ).forEach(events::receivedToDevice)
        assertThat(attempt.talkStates.value.values.single().activity).isNull()
        events.receivedToDevice(valid)
        assertThat(attempt.talkStates.value.values.single().activity).isNotNull()
        attempt.close()
        assertThat(attempt.talkStates.value).isEmpty()
        val next = checkNotNull(events.begin(ROOM, PEER))
        val nextId = requestId()
        events.received(ROOM, MEMBER_TYPE, listOf(reply(nextId, offersTalk = "talk-session")))
        events.receivedToDevice(valid)
        assertThat(next.talkStates.value.values.single().activity).isNull()
    }

    @Test
    fun `talk coalesces pre ready activity and deduplicates IDs independently of sequence gaps`() {
        val attempt = checkNotNull(events.begin(ROOM, PEER))
        val id = requestId()
        for (seq in 1L..200L) events.receivedToDevice(talk(id, talkActivityEvent(seq)))
        events.received(ROOM, MEMBER_TYPE, listOf(reply(id, offersTalk = "talk-session")))
        assertThat(attempt.talkStates.value.values.single().sequence).isEqualTo(200)
        val duplicateId = JsonObject(talkActivityEvent(200) + ("seq" to JsonPrimitive(300)))
        events.receivedToDevice(talk(id, duplicateId))
        assertThat(attempt.talkStates.value.values.single().sequence).isEqualTo(200)
        val invalidPayload = JsonObject(talkActivityEvent(400) + ("payload" to JsonPrimitive("unsupported")))
        events.receivedToDevice(talk(id, invalidPayload))
        assertThat(attempt.talkStates.value.values.single().activity).isNull()
    }

    private fun requestId() = Json.parseToJsonElement(outgoing()).jsonObject.getValue(NAMESPACE)
        .jsonObject.getValue("request_id").jsonPrimitive.content

    private fun outgoing(room: String = ROOM, content: String = JOIN): String =
        events.outgoing(room, MEMBER_TYPE, "_${CALLER}_${CALLER_DEVICE}_m.call", CALLER_DEVICE, content)

    private fun caption(request: String, sequence: Long, role: String, text: String, room: String = ROOM) = FfiToDeviceMessageIn(
        sender = PEER,
        eventType = "$NAMESPACE.caption",
        contentJson = """{"version":1,"room_id":"$room","request_id":"$request","seq":$sequence,"role":"$role","text":"$text"}""",
        encryption = FfiEventEncryption(encrypted = true, senderDeviceId = PEER_DEVICE, senderConfirmed = true),
    )

    private fun talk(request: String, event: JsonObject, room: String = ROOM) = FfiToDeviceMessageIn(
        sender = PEER,
        eventType = "$NAMESPACE.talk",
        contentJson = """{"version":1,"room_id":"$room","request_id":"$request","event":$event}""",
        encryption = FfiEventEncryption(encrypted = true, senderDeviceId = PEER_DEVICE, senderConfirmed = true),
    )

    private fun reply(
        request: String,
        state: String = "ready",
        sender: String = PEER,
        callerDevice: String = CALLER_DEVICE,
        callerMember: String = "$CALLER:$CALLER_DEVICE",
        callId: String = "",
        origin: ULong = NOW,
        offersCaptions: Boolean? = null,
        offersTalk: String? = null,
    ) = FfiEventIn(
        eventId = "\$ready",
        sender = sender,
        eventType = MEMBER_TYPE,
        stateKey = "_${sender}_${PEER_DEVICE}_m.call",
        originServerTs = origin,
        contentJson = """
            {"application":"m.call","scope":"m.room","call_id":"$callId","device_id":"$PEER_DEVICE","expires":60000,
             "$NAMESPACE":{"version":1,"purpose":"assistant-voice","request_id":"$request","state":"$state",
             ${offersCaptions?.let { """"captions":$it,""" }.orEmpty()}
             ${offersTalk?.let { """"talk_events":true,"talk_session_id":"$it",""" }.orEmpty()}
             "caller":{"user_id":"$CALLER","device_id":"$callerDevice","member_id":"$callerMember"}}}
        """.trimIndent(),
        encryption = FfiEventEncryption(encrypted = false, senderDeviceId = null, senderConfirmed = null),
    )
}

private const val NAMESPACE = "io.openclaw.conversation"
private const val MEMBER_TYPE = "org.matrix.msc3401.call.member"
private const val ROOM = "!assistant:example.org"
private const val OTHER_ROOM = "!other:example.org"
private const val CALLER = "@caller:example.org"
private const val CALLER_DEVICE = "CALLERDEVICE"
private const val PEER = "@assistant:example.org"
private const val PEER_DEVICE = "PEERDEVICE"
private const val NOW = 100_000uL
private const val JOIN =
    """{"application":"m.call","scope":"m.room","call_id":"","device_id":"CALLERDEVICE","io.openclaw.conversation":{"existing":"preserved"}}"""
