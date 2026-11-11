/*
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.callnative.impl

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The canonical Talk session.activity payload; input, work and output are independent facts. */
data class TalkActivity(val connection: Connection, val input: Input, val work: Work, val output: Output) {
    enum class Connection { PREPARING, READY, CLOSED }
    enum class InputPhase { LISTENING, RECEIVING, CANDIDATE, SPEECH, TRANSCRIBING, UNKNOWN }
    enum class OutputState { IDLE, PREPARING, STREAMING, SUBMITTED }
    enum class OutputPurpose { SPEECH, WAITING, GREETING }

    data class Input(
        val phase: InputPhase,
        val available: Boolean,
        val pending: Long,
        val captureId: String?,
        val utteranceId: String?,
    )

    data class Work(val pending: Long, val queued: Long, val running: Long)
    data class Output(val generation: Long, val state: OutputState, val purpose: OutputPurpose?)
}

data class TalkInputOutcome(val captureId: String, val scope: Scope, val outcome: Outcome, val utteranceId: String?) {
    enum class Scope { CAPTURE, UTTERANCE }
    enum class Outcome { NO_INPUT, REJECTED, TRANSCRIBED, FAILED, CANCELLED, UNKNOWN }
}

/** A projection of the original Talk envelope, not a second sequence or a locally inferred state. */
data class TalkSessionState(
    val sessionId: String,
    val sequence: Long,
    val activity: TalkActivity? = null,
    val inputOutcome: TalkInputOutcome? = null,
    val activitySequence: Long = -1,
    val outcomeSequence: Long = -1,
)

internal data class TalkEvent(
    val id: String,
    val sessionId: String,
    val sequence: Long,
    val isActivity: Boolean,
    val activity: TalkActivity?,
    val inputOutcome: TalkInputOutcome?,
)

/** Consume only the two status event types; captions and arbitrary provider payloads do not belong here. */
internal fun JsonObject.parseTalkEvent(): TalkEvent? {
    val sessionId = identifier("sessionId") ?: return null
    val sequence = nonNegative("seq")?.takeIf { it >= 1 } ?: return null
    val id = identifier("id") ?: return null
    if (identifier("timestamp") == null || keys.any { it !in TALK_EVENT_FIELDS } ||
        text("mode") !in setOf("realtime", "stt-tts", "transcription") ||
        text("transport") !in setOf("webrtc", "provider-websocket", "gateway-relay", "managed-room") ||
        text("brain") !in setOf("agent-consult", "direct-tools", "none") ||
        !validOptionalIdentifiers("provider", "turnId", "captureId", "callId", "itemId", "parentId") ||
        containsKey("final") && (get("final") != JsonPrimitive(true) && get("final") != JsonPrimitive(false))
    ) return null
    val payload = (get("payload") as? JsonObject)?.takeIf { it["version"] == JsonPrimitive(1) }
    return when (text("type")) {
        "session.activity" -> TalkEvent(id, sessionId, sequence, true, payload?.parseActivity(), null)
        "input.outcome" -> TalkEvent(
            id,
            sessionId,
            sequence,
            false,
            null,
            payload?.parseOutcome(identifier("captureId")),
        )
        else -> null
    }
}

internal fun TalkSessionState?.accept(event: TalkEvent): TalkSessionState? {
    if (this != null && sessionId != event.sessionId) return this
    if (event.isActivity && event.sequence <= (this?.activitySequence ?: -1) ||
        !event.isActivity && event.sequence <= (this?.outcomeSequence ?: -1)
    ) return this
    val activity = if (event.isActivity) event.activity else this?.activity
    // A late terminal capture event is information about that capture, never a command to reset input.
    val outcome = (if (event.isActivity) this?.inputOutcome else event.inputOutcome)?.takeIf {
        it.captureId == activity?.input?.captureId &&
            (it.scope == TalkInputOutcome.Scope.CAPTURE || it.utteranceId == activity?.input?.utteranceId)
    }
    return TalkSessionState(
        event.sessionId,
        maxOf(this?.sequence ?: -1, event.sequence),
        activity,
        outcome,
        if (event.isActivity) event.sequence else this?.activitySequence ?: -1,
        if (event.isActivity) this?.outcomeSequence ?: -1 else event.sequence,
    )
}

private fun JsonObject.parseOutcome(captureId: String?): TalkInputOutcome? {
    if (captureId == null || !validOptionalIdentifiers("utteranceId")) return null
    val scope = wireEnum<TalkInputOutcome.Scope>("scope") ?: return null
    val utteranceId = identifier("utteranceId")
    if (scope == TalkInputOutcome.Scope.UTTERANCE && utteranceId == null) return null
    return TalkInputOutcome(captureId, scope, wireEnum("outcome") ?: return null, utteranceId)
}

private fun JsonObject.parseActivity(): TalkActivity? {
    val input = get("input") as? JsonObject ?: return null
    val work = get("work") as? JsonObject ?: return null
    val output = get("output") as? JsonObject ?: return null
    if (!input.validOptionalIdentifiers("captureId", "utteranceId")) return null
    return TalkActivity(
        connection = wireEnum("connection") ?: return null,
        input = TalkActivity.Input(
            phase = input.wireEnum("phase") ?: return null,
            available = (input["available"] as? JsonPrimitive)?.takeUnless { it.isString }?.content?.let {
                when (it) {
                    "true" -> true
                    "false" -> false
                    else -> null
                }
            } ?: return null,
            pending = input.nonNegative("pending") ?: return null,
            captureId = input.identifier("captureId"),
            utteranceId = input.identifier("utteranceId"),
        ),
        work = TalkActivity.Work(
            pending = work.nonNegative("pending") ?: return null,
            queued = work.nonNegative("queued") ?: return null,
            running = work.nonNegative("running") ?: return null,
        ),
        output = TalkActivity.Output(
            generation = output.nonNegative("generation") ?: return null,
            state = output.wireEnum("state") ?: return null,
            purpose = if (output.containsKey("purpose")) output.wireEnum<TalkActivity.OutputPurpose>("purpose") ?: return null else null,
        ),
    )
}

private fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
private fun JsonObject.identifier(key: String): String? = text(key)?.takeIf { it.isNotEmpty() }

private fun JsonObject.validOptionalIdentifiers(vararg keys: String): Boolean = keys.all { !containsKey(it) || identifier(it) != null }

private fun JsonObject.nonNegative(key: String): Long? =
    (get(key) as? JsonPrimitive)?.takeUnless { it.isString }?.content?.toLongOrNull()?.takeIf { it >= 0 }

private inline fun <reified T : Enum<T>> JsonObject.wireEnum(key: String): T? =
    enumValues<T>().firstOrNull { it.name.lowercase().replace('_', '-') == text(key) }

private val TALK_EVENT_FIELDS = setOf(
    "id", "type", "sessionId", "seq", "timestamp", "mode", "transport", "brain", "payload",
    "provider", "turnId", "captureId", "callId", "itemId", "parentId", "final",
)
