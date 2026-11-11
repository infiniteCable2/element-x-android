/*
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.x.assistant

import com.google.common.truth.Truth.assertThat
import io.element.android.call.api.ElementCallConnection
import io.element.android.call.api.ElementCallData
import io.element.android.call.api.ElementCallSnapshot
import io.element.android.call.api.rtc.MatrixRtcAudioLevel
import io.element.android.call.api.rtc.MatrixRtcConnectionState
import io.element.android.call.api.rtc.MatrixRtcFrameEncryptionState
import io.element.android.call.api.rtc.MatrixRtcTile
import io.element.android.call.api.rtc.MatrixRtcTileId
import io.element.android.call.api.rtc.MatrixRtcTileKind
import io.element.android.call.api.rtc.MatrixRtcTileRef
import io.element.android.call.api.rtc.MatrixRtcTileRoster
import io.element.android.call.api.rtc.MatrixRtcUnableToDecrypt
import io.element.android.call.api.rtc.id.RoomId
import io.element.android.call.api.rtc.id.UserId
import io.element.android.features.callnative.impl.AssistantVoicePeer
import io.element.android.features.callnative.impl.TalkActivity
import io.element.android.features.callnative.impl.TalkSessionState
import kotlinx.collections.immutable.persistentMapOf
import org.junit.Test

class AssistantVoiceStateTest {
    private val own = tile("own", "@caller:example.org", "LOCAL")
    private val peer = tile("peer", "@assistant:example.org", "REMOTE")
    private val ready = setOf(AssistantVoicePeer(peer.userId.value, "REMOTE", peer.id.memberId))
    private val snapshot = ElementCallSnapshot(
        callData = ElementCallData(RoomId("!assistant:example.org"), isAudioCall = true, isStartingCall = true),
        connection = ElementCallConnection.Connected,
        ownTile = own,
        roster = roster(peer),
        audioLevels = persistentMapOf("own" to MatrixRtcAudioLevel(1f, 500), "peer" to MatrixRtcAudioLevel(1f, 500)),
    )
    private val activity = TalkActivity(
        TalkActivity.Connection.READY,
        TalkActivity.Input(TalkActivity.InputPhase.TRANSCRIBING, true, 1, "capture", null),
        TalkActivity.Work(2, 1, 1),
        TalkActivity.Output(1, TalkActivity.OutputState.SUBMITTED, TalkActivity.OutputPurpose.SPEECH),
    )

    @Test
    fun `loud audio without status capability is only connected`() {
        val state = snapshot.toVoiceState(ready)
        assertThat(state.phase).isEqualTo(AssistantVoicePhase.Connected)
        assertThat(state.activity).isNull()
        assertThat(state.level).isEqualTo(1f)
    }

    @Test
    fun `readiness needs our media connection and the correlated decryptable peer`() {
        listOf(
            snapshot.copy(ownTile = null),
            snapshot.copy(ownTile = own.copy(connection = MatrixRtcConnectionState.CONNECTING)),
            snapshot.copy(roster = roster(peer.copy(unableToDecrypt = MatrixRtcUnableToDecrypt.BAD_KEY))),
            snapshot.copy(frameEncryption = persistentMapOf("own" to MatrixRtcFrameEncryptionState.ENCRYPTION_FAILED)),
            snapshot.copy(frameEncryption = persistentMapOf("peer" to MatrixRtcFrameEncryptionState.MISSING_KEY)),
        ).forEach { assertThat(it.toVoiceState(ready).phase).isEqualTo(AssistantVoicePhase.Connecting) }
        assertThat(snapshot.toVoiceState(emptySet()).phase).isEqualTo(AssistantVoicePhase.Connecting)
    }

    @Test
    fun `canonical dimensions are preserved despite loud local and remote audio`() {
        val state = snapshot.toVoiceState(ready, talks(activity))
        assertThat(state.phase).isEqualTo(AssistantVoicePhase.Connected)
        assertThat(state.activity).isEqualTo(activity)
        val muted = snapshot.copy(isMicrophoneMuted = true).toVoiceState(ready, talks(activity))
        assertThat(muted.phase).isEqualTo(AssistantVoicePhase.Muted)
        assertThat(muted.activity?.work?.running).isEqualTo(1)
        assertThat(muted.activity?.output?.state).isEqualTo(TalkActivity.OutputState.SUBMITTED)
    }

    @Test
    fun `preparing closed and missing activity retain their actual meaning without a timer`() {
        assertThat(snapshot.toVoiceState(ready, talks(activity.copy(connection = TalkActivity.Connection.PREPARING))).phase)
            .isEqualTo(AssistantVoicePhase.Connecting)
        assertThat(snapshot.toVoiceState(ready, talks(activity.copy(connection = TalkActivity.Connection.CLOSED))).phase)
            .isEqualTo(AssistantVoicePhase.Ended)
        val missing = snapshot.toVoiceState(ready, mapOf(ready.single() to TalkSessionState("session", -1)))
        assertThat(missing.phase).isEqualTo(AssistantVoicePhase.Connected)
        assertThat(missing.activity).isNull()
    }

    @Test
    fun `another device cannot supply our peers status`() {
        val other = AssistantVoicePeer(peer.userId.value, "OTHER", peer.id.memberId)
        assertThat(snapshot.toVoiceState(ready, mapOf(other to TalkSessionState("session", 1, activity))).activity).isNull()
    }

    private fun talks(value: TalkActivity) = mapOf(ready.single() to TalkSessionState("session", 1, value))

    private fun roster(value: MatrixRtcTile) = MatrixRtcTileRoster(
        listOf(MatrixRtcTileRef(value.id, value.userId, false)),
        mapOf(value.id to value),
    )

    private fun tile(member: String, user: String, device: String) = MatrixRtcTile(
        id = MatrixRtcTileId(member, MatrixRtcTileKind.PERSON),
        userId = UserId(user),
        deviceId = device,
        isHero = false,
        hasVideo = false,
        isMicrophoneMuted = false,
        isSpeaking = false,
        handRaisedAtMs = null,
        handRaisedPosition = null,
        connection = MatrixRtcConnectionState.CONNECTED,
        unableToDecrypt = null,
    )
}
