/*
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.x.assistant

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import io.element.android.compound.theme.ElementTheme
import io.element.android.libraries.architecture.bindings
import io.element.android.x.di.AppBindings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class AssistantVoiceService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        setDisabledShowContext(VoiceInteractionSession.SHOW_WITH_ASSIST or VoiceInteractionSession.SHOW_WITH_SCREENSHOT)
    }
}

/** Android requires this metadata component. MatrixRTC owns the microphone for assistant calls. */
class AssistantVoiceRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent, listener: Callback) {
        listener.error(SpeechRecognizer.ERROR_CLIENT)
    }

    override fun onStopListening(listener: Callback) = Unit

    override fun onCancel(listener: Callback) = Unit
}

@ContributesTo(AppScope::class)
internal interface AssistantVoiceBindings {
    fun assistantVoiceController(): AssistantVoiceController
}

class AssistantVoiceSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession {
        val controller = bindings<AssistantVoiceBindings>().assistantVoiceController()
        return AssistantVoiceSession(this, controller)
    }
}

private class AssistantVoiceSession(
    private val service: Context,
    private val controller: AssistantVoiceController,
) : VoiceInteractionSession(service) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val owner = SessionViewOwner()

    override fun onCreateContentView(): View = ComposeView(service).apply {
        setViewTreeLifecycleOwner(owner)
        setViewTreeSavedStateRegistryOwner(owner)
        setContent {
            val state by controller.state.collectAsState()
            ElementTheme(applySystemBarsUpdate = false) {
                AssistantVoiceBar(
                    state = state,
                    onMute = controller::toggleMute,
                    onHangUp = {
                        controller.hangUp()
                        hide()
                    },
                    onHide = ::hide,
                    onOpenChat = ::openChat,
                )
            }
        }
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        if (!isTrustedAssistantInvocation(showFlags)) {
            hide()
            return
        }
        window?.window?.apply {
            setGravity(Gravity.BOTTOM)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        controller.show()
    }

    override fun onDestroy() {
        scope.cancel()
        owner.lifecycle.currentState = Lifecycle.State.DESTROYED
        super.onDestroy()
    }

    private fun openChat() {
        scope.launch {
            val chat = controller.chatToOpen() ?: return@launch
            val intent = service.bindings<AppBindings>().intentProvider()
                .getViewRoomIntent(chat.sessionId, chat.roomId, threadId = null, eventId = null)
            service.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            hide()
        }
    }
}

internal fun isTrustedAssistantInvocation(showFlags: Int): Boolean {
    val trusted = VoiceInteractionSession.SHOW_SOURCE_ASSIST_GESTURE or VoiceInteractionSession.SHOW_SOURCE_PUSH_TO_TALK
    val appInitiated = VoiceInteractionSession.SHOW_SOURCE_ACTIVITY or VoiceInteractionSession.SHOW_SOURCE_APPLICATION
    return (showFlags and trusted) != 0 && (showFlags and appInitiated) == 0
}

private class SessionViewOwner : SavedStateRegistryOwner {
    override val lifecycle = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)
    override val savedStateRegistry get() = savedState.savedStateRegistry

    init {
        savedState.performAttach()
        savedState.performRestore(null)
        lifecycle.currentState = Lifecycle.State.RESUMED
    }
}
