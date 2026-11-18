/*
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.roomdetails.impl

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import io.element.android.compound.theme.ElementTheme
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.preview.PreviewsDayNight
import io.element.android.libraries.designsystem.theme.components.Button
import io.element.android.libraries.designsystem.theme.components.Surface
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.designsystem.theme.components.TextButton
import io.element.android.libraries.designsystem.theme.components.TextField
import io.element.android.libraries.ui.strings.CommonStrings

enum class AssistantNodePairingState { Idle, Working, Paired, InvalidSetup, UnverifiedChat, Failed }

@Composable
internal fun AssistantNodePairingDialog(state: AssistantNodePairingState, eventSink: (RoomDetailsEvent) -> Unit, onDismiss: () -> Unit) {
    DisposableEffect(Unit) {
        onDispose { eventSink(RoomDetailsEvent.CancelAssistantNodePairing) }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn)) {
        PairingContent(state, onSubmit = { device, code -> eventSink(RoomDetailsEvent.PairAssistantNode(device, code)) }, onDismiss)
    }
}

@Composable
private fun PairingContent(state: AssistantNodePairingState, onSubmit: (String, String) -> Unit, onDismiss: () -> Unit) {
    // Deliberately not rememberSaveable: bootstrap credentials must never enter a saved instance or a screenshot.
    var device by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    val working = state == AssistantNodePairingState.Working
    val paired = state == AssistantNodePairingState.Paired
    Surface(shape = RoundedCornerShape(24.dp), color = ElementTheme.colors.bgCanvasDefault) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.screen_room_details_node_title), style = ElementTheme.typography.fontHeadingSmMedium)
            Text(stringResource(R.string.screen_room_details_node_description))
            if (!paired) {
                TextField(
                    value = device,
                    onValueChange = { if (it.length <= 255) device = it },
                    label = stringResource(R.string.screen_room_details_node_device),
                    singleLine = true,
                    enabled = !working,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextField(
                    value = code,
                    onValueChange = { if (it.length <= 16_384) code = it },
                    label = stringResource(R.string.screen_room_details_node_code),
                    singleLine = true,
                    enabled = !working,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            val status = when (state) {
                AssistantNodePairingState.Idle -> null
                AssistantNodePairingState.Working -> R.string.screen_room_details_node_working
                AssistantNodePairingState.Paired -> R.string.screen_room_details_node_paired
                AssistantNodePairingState.InvalidSetup -> R.string.screen_room_details_node_invalid
                AssistantNodePairingState.UnverifiedChat -> R.string.screen_room_details_node_unverified
                AssistantNodePairingState.Failed -> R.string.screen_room_details_node_failed
            }
            if (status != null) Text(stringResource(status))
            if (!paired) {
                Button(
                    text = stringResource(R.string.screen_room_details_node_pair),
                    enabled = !working && device.isNotBlank() && code.isNotBlank(),
                    showProgress = working,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        val submitted = code
                        code = ""
                        onSubmit(device.trim(), submitted)
                    },
                )
            }
            TextButton(
                text = stringResource(if (paired) CommonStrings.action_done else CommonStrings.action_cancel),
                onClick = {
                    code = ""
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private class PairingPreviewStates : PreviewParameterProvider<AssistantNodePairingState> {
    override val values = AssistantNodePairingState.entries.asSequence()
}

@PreviewsDayNight
@Composable
private fun PairingPreview(@PreviewParameter(PairingPreviewStates::class) state: AssistantNodePairingState) = ElementPreview {
    PairingContent(state, onSubmit = { _, _ -> }, onDismiss = {})
}
