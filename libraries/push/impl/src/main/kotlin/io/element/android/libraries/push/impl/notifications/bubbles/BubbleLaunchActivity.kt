/*
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */
package io.element.android.libraries.push.impl.notifications.bubbles

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import io.element.android.libraries.architecture.bindings
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.SessionId
import io.element.android.libraries.push.api.notifications.bubbles.BubbleRoom
import io.element.android.libraries.push.api.notifications.bubbles.ConversationBubbleService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@ContributesTo(AppScope::class)
interface BubbleActivityBindings {
    fun conversationBubbleService(): ConversationBubbleService
}

/** A bubble tap opens its room in the normal, full-screen Element task. */
class BubbleLaunchActivity : ComponentActivity() {
    private var navigationStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openSelectedChat()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openSelectedChat()
    }

    private fun openSelectedChat() {
        if (navigationStarted) return
        navigationStarted = true
        val room = intent.bubbleRoom()
        val openRoomIntent = intent.openRoomIntent()
        if (room == null || openRoomIntent == null) {
            finish()
            return
        }
        lifecycleScope.launch {
            val bubbleService = bindings<BubbleActivityBindings>().conversationBubbleService()
            if (bubbleService.selectedRoom.value == room && bubbleService.isAvailable.first()) {
                startActivity(openRoomIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            finish()
        }
    }

    private fun Intent.bubbleRoom(): BubbleRoom? {
        val sessionId = getStringExtra(EXTRA_SESSION_ID) ?: return null
        val roomId = getStringExtra(EXTRA_ROOM_ID) ?: return null
        return BubbleRoom(SessionId(sessionId), RoomId(roomId))
    }

    private fun Intent.openRoomIntent(): Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(EXTRA_OPEN_ROOM_INTENT, Intent::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(EXTRA_OPEN_ROOM_INTENT)
    }

    companion object {
        const val EXTRA_SESSION_ID = "bubble_session_id"
        const val EXTRA_ROOM_ID = "bubble_room_id"
        const val EXTRA_OPEN_ROOM_INTENT = "bubble_open_room_intent"
    }
}
