/*
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */
package io.element.android.x

import io.element.android.libraries.push.api.notifications.bubbles.BubbleRoom
import io.element.android.services.appnavstate.api.NavigationState
import io.element.android.services.appnavstate.api.currentRoomId
import io.element.android.services.appnavstate.api.currentSessionId

/** Use an explicit non-chat context so leaving a room updates the visible task without waiting for Home. */
internal fun NavigationState.conversationLocusId(): String {
    val sessionId = currentSessionId()
    val roomId = currentRoomId()
    return if (sessionId != null && roomId != null) {
        BubbleRoom(sessionId, roomId).locusId
    } else {
        "element-overview"
    }
}
