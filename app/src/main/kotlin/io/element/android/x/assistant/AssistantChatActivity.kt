/*
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.x.assistant

import android.app.KeyguardManager
import android.app.role.RoleManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.lifecycle.lifecycleScope
import io.element.android.compound.theme.ElementTheme
import io.element.android.libraries.architecture.bindings
import io.element.android.libraries.designsystem.components.dialogs.AlertDialog
import io.element.android.libraries.designsystem.components.dialogs.ConfirmationDialog
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.preview.PreviewsDayNight
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.SessionId
import io.element.android.libraries.preferences.api.store.AssistantChat
import io.element.android.libraries.push.impl.intent.IntentProvider
import io.element.android.x.R
import io.element.android.x.di.AppBindings
import kotlinx.coroutines.launch

/** Opens only the locally selected chat, after a separate user action in the system-invoked dialog. */
class AssistantChatActivity : ComponentActivity() {
    private var state by mutableStateOf<AssistantChatState?>(null)
    private var opening = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!canHandleAssist()) {
            finish()
            return
        }

        // Drop the system's assist extras without reading the foreground app's context.
        setIntent(Intent(Intent.ACTION_ASSIST))
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val bindings = bindings<AppBindings>()
        val resolver = AssistantChatResolver(
            preferences = bindings.preferencesStore(),
            sessionStore = bindings.sessionStore(),
            lockScreenService = bindings.lockScreenService(),
        )
        setContent {
            ElementTheme(applySystemBarsUpdate = false) {
                state?.let { current ->
                    AssistantChatDialog(
                        state = current,
                        onOpenChat = {
                            if (current is AssistantChatState.Ready) {
                                openChat(current.chat, resolver, bindings.intentProvider())
                            }
                        },
                        onDismiss = ::finish,
                    )
                }
            }
        }
        lifecycleScope.launch {
            val resolved = resolver.resolve()
            if (canHandleAssist()) state = resolved else finish()
        }
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) finish()
    }

    private fun openChat(chat: AssistantChat, resolver: AssistantChatResolver, intentProvider: IntentProvider) {
        if (opening) return
        opening = true
        lifecycleScope.launch {
            try {
                if (!canHandleAssist()) return@launch
                // Do not open a different target if the user's selection changed while this dialog was visible.
                if (resolver.resolve() != AssistantChatState.Ready(chat) || !canHandleAssist() || isFinishing) return@launch
                startActivity(
                    intentProvider.getViewRoomIntent(chat.sessionId, chat.roomId, threadId = null, eventId = null)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            } finally {
                finish()
            }
        }
    }

    private fun canHandleAssist(): Boolean {
        if (intent.action != Intent.ACTION_ASSIST || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        if (getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_ASSISTANT) != true) return false
        val keyguard = getSystemService(KeyguardManager::class.java) ?: return false
        return !keyguard.isDeviceLocked && !keyguard.isKeyguardLocked
    }
}

@Composable
private fun AssistantChatDialog(
    state: AssistantChatState,
    onOpenChat: () -> Unit,
    onDismiss: () -> Unit,
) {
    val title = stringResource(R.string.screen_assistant_chat_title)
    if (state is AssistantChatState.Ready) {
        ConfirmationDialog(
            title = title,
            content = stringResource(R.string.screen_assistant_chat_ready),
            submitText = stringResource(R.string.screen_assistant_chat_open),
            onSubmitClick = onOpenChat,
            onDismiss = onDismiss,
        )
    } else {
        val message = when (state) {
            AssistantChatState.NotConfigured -> R.string.screen_assistant_chat_not_configured
            AssistantChatState.Locked -> R.string.screen_assistant_chat_locked
            else -> R.string.screen_assistant_chat_unavailable
        }
        AlertDialog(title = title, content = stringResource(message), onDismiss = onDismiss)
    }
}

internal class AssistantChatStatePreviews : PreviewParameterProvider<AssistantChatState> {
    override val values = sequenceOf(
        AssistantChatState.Ready(
            AssistantChat(
                SessionId("@example:example.org"),
                RoomId("!sample:example.org"),
            ),
        ),
        AssistantChatState.NotConfigured,
        AssistantChatState.Locked,
        AssistantChatState.Unavailable,
    )
}

@PreviewsDayNight
@Composable
private fun AssistantChatDialogPreview(@PreviewParameter(AssistantChatStatePreviews::class) state: AssistantChatState) = ElementPreview {
    AssistantChatDialog(state, onOpenChat = {}, onDismiss = {})
}
