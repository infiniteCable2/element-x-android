/*
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.x.assistant

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.dp
import io.element.android.compound.theme.ElementTheme
import io.element.android.compound.tokens.generated.CompoundIcons
import io.element.android.features.callnative.impl.AssistantVoiceCaption
import io.element.android.features.callnative.impl.TalkActivity
import io.element.android.features.callnative.impl.TalkInputOutcome
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.preview.PreviewsDayNight
import io.element.android.libraries.designsystem.theme.components.Icon
import io.element.android.libraries.designsystem.theme.components.IconButton
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.designsystem.theme.components.TextButton
import io.element.android.x.R

@Composable
internal fun AssistantVoiceBar(
    state: AssistantVoiceState,
    captions: List<AssistantVoiceCaption>,
    onMute: () -> Unit,
    onHangUp: () -> Unit,
    onHide: () -> Unit,
    onOpenChat: () -> Unit,
) {
    val phase = state.phase
    val callActive = phase in setOf(
        AssistantVoicePhase.Connecting,
        AssistantVoicePhase.Connected,
        AssistantVoicePhase.Muted,
    )
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = ElementTheme.colors.bgCanvasDefault,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        shadowElevation = 12.dp,
    ) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 20.dp, vertical = 8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (callActive) VoiceLevel(state)
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.screen_assistant_chat_title),
                        style = ElementTheme.typography.fontBodyMdMedium,
                    )
                    Text(
                        text = stringResource(phase.label()),
                        color = ElementTheme.colors.textSecondary,
                        style = ElementTheme.typography.fontBodySmRegular,
                    )
                }
                IconButton(onClick = onHide) {
                    Icon(CompoundIcons.ChevronDown(), stringResource(R.string.screen_assistant_voice_hide))
                }
            }
            if (callActive) {
                state.activity?.let { AssistantActivity(it, state.inputOutcome) }
                if (captions.isNotEmpty()) AssistantTranscript(captions)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(
                        text = stringResource(if (state.muted) R.string.screen_assistant_voice_unmute else R.string.screen_assistant_voice_mute),
                        onClick = onMute,
                        enabled = phase != AssistantVoicePhase.Connecting,
                    )
                    TextButton(text = stringResource(R.string.screen_assistant_voice_hang_up), onClick = onHangUp, destructive = true)
                }
            } else if (phase == AssistantVoicePhase.OpenChat) {
                TextButton(text = stringResource(R.string.screen_assistant_chat_open), onClick = onOpenChat)
            }
        }
    }
}

@Composable
private fun AssistantActivity(activity: TalkActivity, outcome: TalkInputOutcome?) {
    Column(Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val inputLabel = if (!activity.input.available) R.string.screen_assistant_input_unavailable else when (activity.input.phase) {
            TalkActivity.InputPhase.LISTENING -> R.string.screen_assistant_voice_listening
            TalkActivity.InputPhase.RECEIVING -> R.string.screen_assistant_input_receiving
            TalkActivity.InputPhase.CANDIDATE -> R.string.screen_assistant_input_candidate
            TalkActivity.InputPhase.SPEECH -> R.string.screen_assistant_input_speech
            TalkActivity.InputPhase.TRANSCRIBING -> R.string.screen_assistant_input_transcribing
            TalkActivity.InputPhase.UNKNOWN -> R.string.screen_assistant_input_unknown
        }
        Text(stringResource(inputLabel), style = ElementTheme.typography.fontBodySmRegular)
        val workLabel = when {
            activity.work.running > 0 -> R.string.screen_assistant_work_running
            activity.work.queued > 0 -> R.string.screen_assistant_work_queued
            activity.work.pending > 0 -> R.string.screen_assistant_work_pending
            else -> R.string.screen_assistant_work_idle
        }
        Text(stringResource(workLabel), style = ElementTheme.typography.fontBodySmRegular)
        val outputLabel = when (activity.output.state) {
            TalkActivity.OutputState.IDLE -> R.string.screen_assistant_output_idle
            TalkActivity.OutputState.PREPARING -> R.string.screen_assistant_output_preparing
            TalkActivity.OutputState.STREAMING -> R.string.screen_assistant_output_streaming
            TalkActivity.OutputState.SUBMITTED -> R.string.screen_assistant_output_submitted
        }
        Text(stringResource(outputLabel), style = ElementTheme.typography.fontBodySmRegular)
        outcome?.let {
            val label = when (it.outcome) {
                TalkInputOutcome.Outcome.NO_INPUT -> R.string.screen_assistant_outcome_no_input
                TalkInputOutcome.Outcome.REJECTED -> R.string.screen_assistant_outcome_rejected
                TalkInputOutcome.Outcome.TRANSCRIBED -> R.string.screen_assistant_outcome_transcribed
                TalkInputOutcome.Outcome.FAILED -> R.string.screen_assistant_outcome_failed
                TalkInputOutcome.Outcome.CANCELLED -> R.string.screen_assistant_outcome_cancelled
                TalkInputOutcome.Outcome.UNKNOWN -> R.string.screen_assistant_input_unknown
            }
            Text(stringResource(label), style = ElementTheme.typography.fontBodySmRegular, color = ElementTheme.colors.textSecondary)
        }
    }
}

@Composable
private fun AssistantTranscript(captions: List<AssistantVoiceCaption>) {
    val scroll = rememberScrollState()
    LaunchedEffect(scroll) {
        var previousMax = Int.MAX_VALUE
        var following = true
        snapshotFlow { Triple(scroll.value, scroll.maxValue, scroll.isScrollInProgress) }.collect { (position, max, scrolling) ->
            if (max == Int.MAX_VALUE) return@collect
            if (max != previousMax && following && !scrolling) {
                scroll.scrollTo(max)
                following = scroll.value == scroll.maxValue
            } else {
                following = position == max
            }
            previousMax = max
        }
    }
    Column(
        modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp).verticalScroll(scroll).padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        captions.forEachIndexed { index, caption ->
            val fromUser = caption.role == AssistantVoiceCaption.Role.User
            if (captions.getOrNull(index - 1)?.role != caption.role) {
                Text(
                    text = stringResource(if (fromUser) R.string.screen_assistant_voice_you else R.string.screen_assistant_chat_title),
                    color = ElementTheme.colors.textSecondary,
                    style = ElementTheme.typography.fontBodySmMedium,
                )
            }
            Text(
                text = caption.text,
                color = if (fromUser) ElementTheme.colors.textSecondary else ElementTheme.colors.textPrimary,
                style = ElementTheme.typography.fontBodyMdRegular,
            )
        }
    }
}

@Composable
private fun VoiceLevel(state: AssistantVoiceState) {
    val level by animateFloatAsState(state.level, label = "Voice level")
    val colour = when {
        state.activity?.output?.state == TalkActivity.OutputState.STREAMING -> ElementTheme.colors.bgActionPrimaryRest
        state.activity?.input?.phase == TalkActivity.InputPhase.SPEECH -> ElementTheme.colors.textPrimary
        else -> ElementTheme.colors.textSecondary
    }
    Canvas(Modifier.size(width = 36.dp, height = 28.dp)) {
        val heights = listOf(0.4f, 0.7f, 1f, 0.7f, 0.4f)
        heights.forEachIndexed { index, factor ->
            val x = size.width * (index + 0.5f) / heights.size
            val halfHeight = size.height * factor * (0.13f + level * 0.33f)
            drawLine(
                color = colour,
                start = Offset(x, center.y - halfHeight),
                end = Offset(x, center.y + halfHeight),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }
    }
}

private fun AssistantVoicePhase.label() = when (this) {
    AssistantVoicePhase.Connecting -> R.string.screen_assistant_voice_connecting
    AssistantVoicePhase.Connected -> R.string.screen_assistant_voice_connected
    AssistantVoicePhase.Muted -> R.string.screen_assistant_voice_muted
    AssistantVoicePhase.Ended -> R.string.screen_assistant_voice_ended
    AssistantVoicePhase.OpenChat -> R.string.screen_assistant_chat_ready
    AssistantVoicePhase.NotConfigured -> R.string.screen_assistant_chat_not_configured
    AssistantVoicePhase.Locked -> R.string.screen_assistant_chat_locked
    AssistantVoicePhase.Permission -> R.string.screen_assistant_voice_permission
    AssistantVoicePhase.DirectChatRequired -> R.string.screen_assistant_voice_direct
    AssistantVoicePhase.Busy -> R.string.screen_assistant_voice_busy
    AssistantVoicePhase.Failed -> R.string.screen_assistant_voice_failed
}

internal class AssistantVoicePreviewStates : PreviewParameterProvider<AssistantVoiceState> {
    override val values = AssistantVoicePhase.entries.asSequence().map { phase ->
        AssistantVoiceState(phase, muted = phase == AssistantVoicePhase.Muted, level = 0.65f)
    }
}

@PreviewsDayNight
@Composable
private fun AssistantVoicePreview(@PreviewParameter(AssistantVoicePreviewStates::class) state: AssistantVoiceState) = ElementPreview {
    AssistantVoiceBar(state, captions = emptyList(), onMute = {}, onHangUp = {}, onHide = {}, onOpenChat = {})
}

@PreviewsDayNight
@Composable
private fun AssistantVoiceTranscriptPreview() = ElementPreview {
    AssistantVoiceBar(
        state = AssistantVoiceState(AssistantVoicePhase.Connected),
        captions = listOf(
            AssistantVoiceCaption(1, AssistantVoiceCaption.Role.User, "What can I make with carrots and potatoes?"),
            AssistantVoiceCaption(2, AssistantVoiceCaption.Role.Assistant, "You could make a simple soup with both."),
        ),
        onMute = {},
        onHangUp = {},
        onHide = {},
        onOpenChat = {},
    )
}

internal class AssistantActivityPreviewStates : PreviewParameterProvider<AssistantVoiceState> {
    private val activity = TalkActivity(
        connection = TalkActivity.Connection.READY,
        input = TalkActivity.Input(TalkActivity.InputPhase.LISTENING, true, 0, "capture", null),
        work = TalkActivity.Work(2, 1, 1),
        output = TalkActivity.Output(1, TalkActivity.OutputState.IDLE, null),
    )
    override val values = sequence {
        for (phase in TalkActivity.InputPhase.entries) yield(activity.copy(input = activity.input.copy(phase = phase)))
        for (state in TalkActivity.OutputState.entries) yield(activity.copy(output = activity.output.copy(state = state)))
        yield(activity.copy(input = activity.input.copy(available = false)))
    }.map { AssistantVoiceState(AssistantVoicePhase.Connected, activity = it, level = 0.65f) } +
        TalkInputOutcome.Outcome.entries.asSequence().map {
            AssistantVoiceState(
                AssistantVoicePhase.Connected,
                activity = activity,
                inputOutcome = TalkInputOutcome("capture", TalkInputOutcome.Scope.CAPTURE, it, null),
            )
        }
}

@PreviewsDayNight
@Composable
private fun AssistantActivityPreview(@PreviewParameter(AssistantActivityPreviewStates::class) state: AssistantVoiceState) = ElementPreview {
    AssistantVoiceBar(state, captions = emptyList(), onMute = {}, onHangUp = {}, onHide = {}, onOpenChat = {})
}
