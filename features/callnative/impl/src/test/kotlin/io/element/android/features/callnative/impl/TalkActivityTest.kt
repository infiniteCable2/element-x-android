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
import org.junit.Test

class TalkActivityTest {
    @Test
    fun `activity retains simultaneous input work and output without a guessed single phase`() {
        val event = checkNotNull(talkActivityEvent(1, output = "streaming").parseTalkEvent())
        val activity = checkNotNull(event.activity)
        assertThat(activity.input.phase).isEqualTo(TalkActivity.InputPhase.SPEECH)
        assertThat(activity.input.available).isTrue()
        assertThat(activity.work).isEqualTo(TalkActivity.Work(2, 1, 1))
        assertThat(activity.output.state).isEqualTo(TalkActivity.OutputState.STREAMING)
        assertThat(activity.output.purpose).isEqualTo(TalkActivity.OutputPurpose.SPEECH)
    }

    @Test
    fun `submitted remains submitted and is not a local playback completion`() {
        assertThat(talkActivityEvent(1).parseTalkEvent()?.activity?.output?.state).isEqualTo(TalkActivity.OutputState.SUBMITTED)
    }

    @Test
    fun `malformed fields and non status events are rejected`() {
        val valid = talkActivityEvent(1)
        listOf(
            JsonObject(valid + ("seq" to JsonPrimitive(-1))),
            JsonObject(valid + ("seq" to JsonPrimitive("1"))),
            JsonObject(valid - "sessionId"),
            JsonObject(valid + ("type" to JsonPrimitive("output.text"))),
        ).forEach { assertThat(it.parseTalkEvent()).isNull() }
        assertThat(talkActivityEvent(1, output = "finished").parseTalkEvent()?.activity).isNull()
        assertThat(JsonObject(valid + ("payload" to JsonPrimitive("not an object"))).parseTalkEvent()?.activity).isNull()
    }

    @Test
    fun `duplicates stale events and another session cannot replace current activity`() {
        val first = checkNotNull(talkActivityEvent(2).parseTalkEvent())
        val state = checkNotNull(null.accept(first))
        assertThat(state.accept(first)).isEqualTo(state)
        assertThat(state.accept(checkNotNull(talkActivityEvent(1, output = "idle").parseTalkEvent()))).isEqualTo(state)
        val another = talkActivityEvent(3).let { JsonObject(it + ("sessionId" to JsonPrimitive("other-session"))) }
        assertThat(state.accept(checkNotNull(another.parseTalkEvent()))).isEqualTo(state)
    }

    @Test
    fun `an older capture outcome cannot reset the newer input or its work`() {
        val state = checkNotNull(null.accept(checkNotNull(talkActivityEvent(2, capture = "new-capture").parseTalkEvent())))
        val updated = checkNotNull(state.accept(checkNotNull(outcomeEvent(3, "old-capture").parseTalkEvent())))
        assertThat(updated.activity).isEqualTo(state.activity)
        assertThat(updated.inputOutcome).isNull()
        val current = checkNotNull(updated.accept(checkNotNull(outcomeEvent(4, "new-capture").parseTalkEvent())))
        assertThat(current.inputOutcome?.outcome).isEqualTo(TalkInputOutcome.Outcome.NO_INPUT)
        assertThat(current.activity?.work?.running).isEqualTo(1)
        val next = checkNotNull(current.accept(checkNotNull(talkActivityEvent(5, capture = "next-capture").parseTalkEvent())))
        assertThat(next.inputOutcome).isNull()
    }

    private fun outcomeEvent(seq: Long, capture: String): JsonObject = Json.parseToJsonElement(
        """{"id":"event-$seq","type":"input.outcome","sessionId":"talk-session","seq":$seq,"timestamp":1000,
           "mode":"realtime","transport":"matrix","brain":"agent","captureId":"$capture",
           "payload":{"version":1,"scope":"capture","outcome":"no-input"}}""",
    ).jsonObject
}

internal fun talkActivityEvent(seq: Long, capture: String = "capture-1", output: String = "submitted"): JsonObject = Json.parseToJsonElement(
    """{"id":"event-$seq","type":"session.activity","sessionId":"talk-session","seq":$seq,"timestamp":1000,
       "mode":"realtime","transport":"matrix","brain":"agent","payload":{"version":1,"connection":"ready",
       "input":{"phase":"speech","available":true,"captureId":"$capture","pending":0},
       "work":{"pending":2,"queued":1,"running":1},"output":{"generation":1,"state":"$output","purpose":"speech"}}}""",
).jsonObject
