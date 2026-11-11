/*
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.x.assistant

import io.element.android.call.api.ElementCallConnection
import io.element.android.call.api.ElementCallController
import io.element.android.call.api.ElementCallData
import io.element.android.call.api.ElementCallFailure
import io.element.android.call.api.ElementCallSnapshot
import io.element.android.call.api.audio.CallAudioDevice
import io.element.android.call.api.audio.CallAudioDeviceType
import io.element.android.call.api.rtc.id.RoomId
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AssistantAudioRouteTest {
    private val roomId = RoomId("!assistant:example.org")
    private val earpiece = CallAudioDevice(1, CallAudioDeviceType.EARPIECE, null)
    private val speaker = CallAudioDevice(2, CallAudioDeviceType.SPEAKER, null)
    private val snapshot = ElementCallSnapshot(
        callData = ElementCallData(roomId = roomId, isAudioCall = true, isStartingCall = true),
        connection = ElementCallConnection.ConnectingMedia,
        audioDevices = persistentListOf(earpiece, speaker),
        selectedAudioDevice = earpiece,
    )
    private val state = MutableStateFlow<ElementCallSnapshot?>(null)
    private val controller = mockk<ElementCallController> {
        every { state } returns this@AssistantAudioRouteTest.state
        every { selectAudioDevice(any()) } answers {
            this@AssistantAudioRouteTest.state.value = this@AssistantAudioRouteTest.state.value?.copy(selectedAudioDevice = firstArg())
        }
    }

    @Test
    fun `waits for the audio route then selects speaker once`() = runTest {
        backgroundScope.launch { controller.followAssistantAudioRoute(roomId) }
        state.value = snapshot.copy(audioDevices = persistentListOf(), selectedAudioDevice = null)
        runCurrent()
        verify(exactly = 0) { controller.selectAudioDevice(any()) }

        state.value = snapshot
        runCurrent()
        state.value = state.value?.copy(isMicrophoneMuted = true)
        runCurrent()
        verify(exactly = 1) { controller.selectAudioDevice(speaker) }
    }

    @Test
    fun `keeps external audio routes and returns to speaker when disconnected`() = runTest {
        backgroundScope.launch { controller.followAssistantAudioRoute(roomId) }
        val externalTypes = CallAudioDeviceType.entries.filterNot { it.isBuiltIn }
        for (type in externalTypes) {
            val headset = CallAudioDevice(3, type, "Example headset")
            state.value = snapshot.copy(
                audioDevices = persistentListOf(headset, earpiece, speaker),
                selectedAudioDevice = headset,
            )
            runCurrent()
        }
        verify(exactly = 0) { controller.selectAudioDevice(any()) }

        state.value = snapshot
        runCurrent()
        verify(exactly = 1) { controller.selectAudioDevice(speaker) }
    }

    @Test
    fun `does not reroute another call or a call with no speaker`() = runTest {
        backgroundScope.launch { controller.followAssistantAudioRoute(roomId) }
        state.value = snapshot.copy(callData = snapshot.callData.copy(roomId = RoomId("!other:example.org")))
        runCurrent()
        state.value = snapshot.copy(audioDevices = persistentListOf(earpiece))
        runCurrent()
        verify(exactly = 0) { controller.selectAudioDevice(any()) }
    }

    @Test
    fun `stops routing when assistant observation ends`() = runTest {
        val job = backgroundScope.launch { controller.followAssistantAudioRoute(roomId) }
        runCurrent()
        job.cancel()
        state.value = snapshot
        runCurrent()
        verify(exactly = 0) { controller.selectAudioDevice(any()) }
    }

    @Test
    fun `does not reroute ended or failed calls`() = runTest {
        backgroundScope.launch { controller.followAssistantAudioRoute(roomId) }
        for (connection in listOf(
            ElementCallConnection.Ended,
            ElementCallConnection.Failed(ElementCallFailure.JoinTimedOut),
            ElementCallConnection.RingNotSent(null),
        )) {
            state.value = snapshot.copy(connection = connection)
            runCurrent()
        }
        verify(exactly = 0) { controller.selectAudioDevice(any()) }
    }
}
