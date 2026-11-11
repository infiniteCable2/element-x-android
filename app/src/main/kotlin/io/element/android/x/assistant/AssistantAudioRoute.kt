/*
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.x.assistant

import io.element.android.call.api.ElementCallConnection
import io.element.android.call.api.ElementCallController
import io.element.android.call.api.audio.CallAudioDeviceType
import io.element.android.call.api.rtc.id.RoomId
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Use speakerphone for the assistant, including after a headset is disconnected. */
internal suspend fun ElementCallController.followAssistantAudioRoute(roomId: RoomId) {
    state.map { snapshot ->
        snapshot?.takeIf {
            it.callData.roomId == roomId &&
                it.connection !is ElementCallConnection.Ended &&
                it.connection !is ElementCallConnection.Failed &&
                it.connection !is ElementCallConnection.RingNotSent &&
                it.selectedAudioDevice?.type == CallAudioDeviceType.EARPIECE
        }?.audioDevices?.firstOrNull { it.type == CallAudioDeviceType.SPEAKER }
    }.distinctUntilChanged().collect { speaker ->
        // Leave connected headsets and vehicle hands-free routes to the native audio controller.
        if (speaker != null) selectAudioDevice(speaker)
    }
}
