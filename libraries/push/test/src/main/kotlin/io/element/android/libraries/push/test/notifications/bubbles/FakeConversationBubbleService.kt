/*
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */
package io.element.android.libraries.push.test.notifications.bubbles

import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.SessionId
import io.element.android.libraries.push.api.notifications.bubbles.BubbleRoom
import io.element.android.libraries.push.api.notifications.bubbles.BubblePreview
import io.element.android.libraries.push.api.notifications.bubbles.ConversationBubbleService
import kotlinx.coroutines.flow.MutableStateFlow

class FakeConversationBubbleService : ConversationBubbleService {
    override val selectedRoom = MutableStateFlow<BubbleRoom?>(null)
    override val latestPreview = MutableStateFlow<BubblePreview?>(null)
    override val isAvailable = MutableStateFlow(true)

    override fun select(sessionId: SessionId, roomId: RoomId) {
        selectedRoom.value = BubbleRoom(sessionId, roomId)
        latestPreview.value = null
    }

    override fun clear(sessionId: SessionId, roomId: RoomId) {
        if (selectedRoom.value == BubbleRoom(sessionId, roomId)) selectedRoom.value = null
        if (selectedRoom.value == null) latestPreview.value = null
    }

    override fun updatePreview(sessionId: SessionId, roomId: RoomId, roomName: String, latestMessage: String?) {
        val room = BubbleRoom(sessionId, roomId)
        if (selectedRoom.value == room) latestPreview.value = BubblePreview(room, roomName, latestMessage)
    }

    override suspend fun showSelectedBubble(roomName: String, roomAvatarUrl: String?) = Unit
    override fun restoreAfterMessagesCleared(sessionId: SessionId, roomId: RoomId) = Unit
}
