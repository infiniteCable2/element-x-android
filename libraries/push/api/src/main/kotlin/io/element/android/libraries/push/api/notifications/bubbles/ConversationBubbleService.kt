/*
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */
package io.element.android.libraries.push.api.notifications.bubbles

import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.SessionId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

data class BubbleRoom(val sessionId: SessionId, val roomId: RoomId) {
    /** Shared by the notification and the visible chat. Length-prefix the account to avoid collisions. */
    val locusId: String get() = "element-room:${sessionId.value.length}:${sessionId.value}${roomId.value}"
}
data class BubblePreview(val room: BubbleRoom, val roomName: String, val latestMessage: String?)

/** A single, locally selected conversation. Android still controls whether its bubble is shown. */
interface ConversationBubbleService {
    val selectedRoom: StateFlow<BubbleRoom?>
    val latestPreview: StateFlow<BubblePreview?>
    val isAvailable: Flow<Boolean>

    fun select(sessionId: SessionId, roomId: RoomId)
    fun clear(sessionId: SessionId, roomId: RoomId)
    fun updatePreview(sessionId: SessionId, roomId: RoomId, roomName: String, latestMessage: String?)
    suspend fun showSelectedBubble(roomName: String, roomAvatarUrl: String?)
    fun restoreAfterMessagesCleared(sessionId: SessionId, roomId: RoomId)
}
