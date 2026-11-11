/*
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.x.assistant

import io.element.android.call.api.ElementCallConnection
import io.element.android.call.api.ElementCallSnapshot
import io.element.android.call.api.rtc.MatrixRtcConnectionState
import io.element.android.call.api.rtc.MatrixRtcFrameEncryptionState
import io.element.android.call.api.rtc.MatrixRtcTileKind
import io.element.android.features.callnative.impl.AssistantVoicePeer
import io.element.android.features.callnative.impl.TalkActivity
import io.element.android.features.callnative.impl.TalkInputOutcome
import io.element.android.features.callnative.impl.TalkSessionState

internal enum class AssistantVoicePhase {
    Connecting,
    Connected,
    Muted,
    Ended,
    OpenChat,
    NotConfigured,
    Locked,
    Permission,
    DirectChatRequired,
    Busy,
    Failed,
}

internal data class AssistantVoiceState(
    val phase: AssistantVoicePhase = AssistantVoicePhase.Connecting,
    val muted: Boolean = false,
    val level: Float = 0f,
    val activity: TalkActivity? = null,
    val inputOutcome: TalkInputOutcome? = null,
)

internal fun ElementCallSnapshot.toVoiceState(
    readyPeers: Set<AssistantVoicePeer>,
    talkStates: Map<AssistantVoicePeer, TalkSessionState> = emptyMap(),
): AssistantVoiceState {
    val peer = roster.ranked.firstOrNull {
        val deviceId = it.deviceId
        it.id.kind == MatrixRtcTileKind.PERSON && it.connection == MatrixRtcConnectionState.CONNECTED &&
            it.unableToDecrypt == null && deviceId != null &&
            AssistantVoicePeer(it.userId.value, deviceId, it.id.memberId) in readyPeers
    }
    val own = ownTile
    if (connection !is ElementCallConnection.Connected || own?.connection != MatrixRtcConnectionState.CONNECTED || peer == null ||
        listOfNotNull(frameEncryption[own.id.memberId], frameEncryption[peer.id.memberId]).any { it != MatrixRtcFrameEncryptionState.OK }
    ) return AssistantVoiceState(AssistantVoicePhase.Connecting, isMicrophoneMuted)

    val talk = talkStates[AssistantVoicePeer(peer.userId.value, checkNotNull(peer.deviceId), peer.id.memberId)]
    val activity = talk?.activity
    val phase = when {
        activity?.connection == TalkActivity.Connection.CLOSED -> AssistantVoicePhase.Ended
        activity?.connection == TalkActivity.Connection.PREPARING -> AssistantVoicePhase.Connecting
        isMicrophoneMuted -> AssistantVoicePhase.Muted
        else -> AssistantVoicePhase.Connected
    }
    val local = if (isMicrophoneMuted) 0f else audioLevels[own.id.memberId]?.level ?: 0f
    val remote = audioLevels[peer.id.memberId]?.level ?: 0f
    // Levels animate the device UI. They cannot establish input, model work or output phases.
    return AssistantVoiceState(phase, isMicrophoneMuted, maxOf(local, remote).coerceIn(0f, 1f), activity, talk?.inputOutcome)
}
