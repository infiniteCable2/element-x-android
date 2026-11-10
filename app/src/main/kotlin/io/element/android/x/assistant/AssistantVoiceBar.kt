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
        AssistantVoicePhase.Connecting, AssistantVoicePhase.Listening, AssistantVoicePhase.Speaking,
        AssistantVoicePhase.Replying, AssistantVoicePhase.Muted,
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
    val colour = when (state.phase) {
        AssistantVoicePhase.Speaking -> ElementTheme.colors.textPrimary
        AssistantVoicePhase.Replying -> ElementTheme.colors.bgActionPrimaryRest
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
    AssistantVoicePhase.Listening -> R.string.screen_assistant_voice_listening
    AssistantVoicePhase.Speaking -> R.string.screen_assistant_voice_speaking
    AssistantVoicePhase.Replying -> R.string.screen_assistant_voice_replying
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
        state = AssistantVoiceState(AssistantVoicePhase.Replying),
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
