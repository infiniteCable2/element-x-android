/*
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.x.assistant

import android.Manifest
import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.service.voice.VoiceInteractionService
import androidx.core.content.ContextCompat
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.element.android.call.api.ElementCallConnection
import io.element.android.call.api.ElementCallController
import io.element.android.call.api.ElementCallData
import io.element.android.call.api.ElementCallSnapshot
import io.element.android.call.api.rtc.MatrixRtcConnectionState
import io.element.android.call.api.rtc.id.RoomId
import io.element.android.features.call.api.CurrentCall
import io.element.android.features.call.api.CurrentCallService
import io.element.android.features.callnative.impl.AssistantVoiceCaption
import io.element.android.features.callnative.impl.AssistantVoicePeer
import io.element.android.features.callnative.impl.ElementCallControllers
import io.element.android.features.lockscreen.api.LockScreenService
import io.element.android.libraries.di.annotations.AppCoroutineScope
import io.element.android.libraries.di.annotations.ApplicationContext
import io.element.android.libraries.matrix.api.MatrixClientProvider
import io.element.android.libraries.matrix.api.room.RoomMembershipState
import io.element.android.libraries.preferences.api.store.AppPreferencesStore
import io.element.android.libraries.preferences.api.store.AssistantChat
import io.element.android.libraries.sessionstorage.api.SessionStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

internal enum class AssistantVoicePhase {
    Connecting, Listening, Speaking, Replying, Muted, Ended, OpenChat, NotConfigured, Locked, Permission, DirectChatRequired, Busy, Failed,
}

internal data class AssistantVoiceState(
    val phase: AssistantVoicePhase = AssistantVoicePhase.Connecting,
    val muted: Boolean = false,
    val level: Float = 0f,
)

/** The call belongs to the app, so hiding or recreating the system overlay leaves it running. */
@SingleIn(AppScope::class)
@Inject
class AssistantVoiceController(
    @ApplicationContext private val context: Context,
    @AppCoroutineScope private val scope: CoroutineScope,
    preferences: AppPreferencesStore,
    sessions: SessionStore,
    lockScreen: LockScreenService,
    private val clients: MatrixClientProvider,
    private val controllers: ElementCallControllers,
    private val currentCall: CurrentCallService,
) {
    private val resolver = AssistantChatResolver(preferences, sessions, lockScreen)
    private val mutableState = MutableStateFlow(AssistantVoiceState())
    internal val state = mutableState.asStateFlow()
    private val mutableCaptions = MutableStateFlow<List<AssistantVoiceCaption>>(emptyList())
    internal val captions = mutableCaptions.asStateFlow()
    private var callJob: Job? = null
    private var controller: ElementCallController? = null

    fun show() {
        if (callJob?.isCompleted == false) return
        update(AssistantVoicePhase.Connecting)
        callJob = scope.launch(Dispatchers.Main.immediate) {
            try {
                start()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                update(AssistantVoicePhase.Failed)
            }
        }
    }

    fun toggleMute() {
        val call = controller ?: return
        val snapshot = call.state.value ?: return
        call.setMicrophoneMuted(!snapshot.isMicrophoneMuted)
    }

    fun hangUp() {
        callJob?.cancel()
        update(AssistantVoicePhase.Ended)
    }

    suspend fun chatToOpen(): AssistantChat? {
        val ready = resolver.resolve() as? AssistantChatState.Ready ?: return null
        return ready.chat.takeIf { canStart() }
    }

    private suspend fun start() {
        if (!canStart()) return update(AssistantVoicePhase.Locked)
        val target = when (val selected = resolver.resolve()) {
            AssistantChatState.NotConfigured -> return update(AssistantVoicePhase.NotConfigured)
            AssistantChatState.Locked -> return update(AssistantVoicePhase.Locked)
            AssistantChatState.Unavailable -> return update(AssistantVoicePhase.Failed)
            is AssistantChatState.Ready -> selected.chat
        }
        if (!target.voiceEnabled) return update(AssistantVoicePhase.OpenChat)
        if (!hasMicrophonePermission()) return update(AssistantVoicePhase.Permission)
        if (hasOtherCall()) return update(AssistantVoicePhase.Busy)

        val peer = withTimeoutOrNull(20.seconds) {
            val room = clients.getOrRestore(target.sessionId).getOrNull()?.getJoinedRoom(target.roomId) ?: return@withTimeoutOrNull null
            room.use {
                val info = it.info()
                if (!info.isDirect || info.joinedMembersCount != 2L) return@use null
                it.getDirectRoomMember()?.takeIf { member -> member.membership == RoomMembershipState.JOIN && member.userId != target.sessionId }
            }
        } ?: return update(AssistantVoicePhase.DirectChatRequired)
        val call = withTimeoutOrNull(15.seconds) { controllers.getOrBuild(target.sessionId) } ?: return update(AssistantVoicePhase.Failed)
        val rechecked = resolver.resolve()
        if (rechecked == AssistantChatState.Locked || !canStart()) return update(AssistantVoicePhase.Locked)
        if (rechecked != AssistantChatState.Ready(target)) return update(AssistantVoicePhase.Failed)
        if (!hasMicrophonePermission()) return update(AssistantVoicePhase.Permission)
        if (hasOtherCall()) return update(AssistantVoicePhase.Busy)

        val roomId = RoomId(target.roomId.value)
        val attempt = controllers.beginAssistantVoice(target.sessionId, target.roomId, peer.userId) ?: return update(AssistantVoicePhase.Failed)
        controller = call
        controllers.setStartingFromAssistant(target.sessionId)
        try {
            call.startCall(ElementCallData(roomId = roomId, isAudioCall = true, isStartingCall = true))
            if (withTimeoutOrNull(8.seconds) { call.state.first { it?.callData?.roomId == roomId } } == null) {
                return update(AssistantVoicePhase.Failed)
            }
            call.setMaximized(false)
            controllers.setStartingFromAssistant(null)
            call.setMicrophonePermissionGranted(true)

            val captionJob = scope.launch(Dispatchers.Main.immediate) {
                attempt.captions.collect { mutableCaptions.value = it }
            }
            var signalled = false
            try {
                combine(call.state, attempt.readyPeers) { snapshot, readyPeers ->
                    when {
                        snapshot == null || snapshot.callData.roomId != roomId -> AssistantVoiceState(AssistantVoicePhase.Ended)
                        snapshot.connection is ElementCallConnection.Ended -> AssistantVoiceState(AssistantVoicePhase.Ended)
                        snapshot.connection is ElementCallConnection.Failed -> AssistantVoiceState(AssistantVoicePhase.Failed)
                        else -> snapshot.toVoiceState(readyPeers)
                    }
                }.first {
                    mutableState.value = it
                    if (!signalled && it.phase in readyPhases) {
                        signalled = true
                        playReadyTone()
                    }
                    it.phase in terminalPhases
                }
            } finally {
                captionJob.cancel()
            }
        } finally {
            attempt.close()
            mutableCaptions.value = emptyList()
            val snapshot = call.state.value
            if (snapshot == null || snapshot.callData.roomId == roomId) {
                call.setMaximized(false)
                call.hangUp()
            }
            controllers.setStartingFromAssistant(null)
            controller = null
        }
    }

    private fun update(phase: AssistantVoicePhase) {
        mutableState.value = AssistantVoiceState(phase)
    }

    private fun canStart(): Boolean {
        val keyguard = context.getSystemService(KeyguardManager::class.java) ?: return false
        return !keyguard.isDeviceLocked && !keyguard.isKeyguardLocked &&
            VoiceInteractionService.isActiveService(context, ComponentName(context, AssistantVoiceService::class.java))
    }

    private fun hasMicrophonePermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun hasOtherCall(): Boolean =
        currentCall.currentCall.value != CurrentCall.None || controllers.withRunningCall() != null

    private fun playReadyTone() {
        scope.launch {
            val tone = runCatching { ToneGenerator(AudioManager.STREAM_VOICE_CALL, 65) }.getOrNull() ?: return@launch
            try {
                tone.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
                delay(180)
            } finally {
                tone.release()
            }
        }
    }
}

private val readyPhases = setOf(AssistantVoicePhase.Listening, AssistantVoicePhase.Speaking, AssistantVoicePhase.Replying, AssistantVoicePhase.Muted)
private val terminalPhases = setOf(AssistantVoicePhase.Ended, AssistantVoicePhase.Failed)
private const val SPEECH_LEVEL = 0.025f

internal fun ElementCallSnapshot.toVoiceState(readyPeers: Set<AssistantVoicePeer>): AssistantVoiceState {
    val peer = roster.ranked.firstOrNull {
        val deviceId = it.deviceId
        it.connection == MatrixRtcConnectionState.CONNECTED && deviceId != null &&
            AssistantVoicePeer(it.userId.value, deviceId, it.id.memberId) in readyPeers
    }
    if (connection !is ElementCallConnection.Connected || peer == null) {
        return AssistantVoiceState(AssistantVoicePhase.Connecting, isMicrophoneMuted)
    }
    val local = if (isMicrophoneMuted) 0f else ownTile?.id?.memberId?.let(audioLevels::get)?.level ?: 0f
    val remote = audioLevels[peer.id.memberId]?.level ?: 0f
    val phase = when {
        local >= SPEECH_LEVEL -> AssistantVoicePhase.Speaking
        remote >= SPEECH_LEVEL -> AssistantVoicePhase.Replying
        isMicrophoneMuted -> AssistantVoicePhase.Muted
        else -> AssistantVoicePhase.Listening
    }
    return AssistantVoiceState(phase, isMicrophoneMuted, maxOf(local, remote).coerceIn(0f, 1f))
}
