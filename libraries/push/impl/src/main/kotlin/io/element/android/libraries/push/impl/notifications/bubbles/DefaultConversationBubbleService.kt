/*
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */
package io.element.android.libraries.push.impl.notifications.bubbles

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.graphics.drawable.IconCompat
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.SingleIn
import io.element.android.features.lockscreen.api.LockScreenService
import io.element.android.libraries.di.annotations.ApplicationContext
import io.element.android.libraries.di.annotations.AppCoroutineScope
import io.element.android.libraries.designsystem.components.avatar.AvatarData
import io.element.android.libraries.designsystem.components.avatar.AvatarSize
import io.element.android.libraries.designsystem.utils.CommonDrawables
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.SessionId
import io.element.android.libraries.matrix.api.MatrixClientProvider
import io.element.android.libraries.matrixmedia.api.ImageLoaderHolder
import io.element.android.libraries.push.api.notifications.NotificationBitmapLoader
import io.element.android.libraries.push.api.notifications.NotificationIdProvider
import io.element.android.libraries.push.api.notifications.bubbles.BubbleRoom
import io.element.android.libraries.push.api.notifications.bubbles.BubblePreview
import io.element.android.libraries.push.api.notifications.bubbles.ConversationBubbleService
import io.element.android.libraries.sessionstorage.api.observer.SessionListener
import io.element.android.libraries.sessionstorage.api.observer.SessionObserver
import io.element.android.libraries.push.impl.R
import io.element.android.libraries.push.impl.notifications.NotificationDisplayer
import io.element.android.libraries.push.impl.notifications.channels.NotificationChannels
import io.element.android.libraries.push.impl.notifications.factories.NotificationCreator
import io.element.android.libraries.push.impl.notifications.factories.PendingIntentFactory
import io.element.android.libraries.push.impl.notifications.shortcut.createShortcutId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class DefaultConversationBubbleService(
    @ApplicationContext private val context: Context,
    private val lockScreenService: LockScreenService,
    sessionObserver: SessionObserver,
    private val notificationDisplayer: NotificationDisplayer,
    private val notificationChannels: NotificationChannels,
    private val pendingIntentFactory: PendingIntentFactory,
    private val bitmapLoader: NotificationBitmapLoader,
    private val matrixClientProvider: MatrixClientProvider,
    private val imageLoaderHolder: ImageLoaderHolder,
    @AppCoroutineScope private val coroutineScope: CoroutineScope,
) : ConversationBubbleService {
    private val preferences = context.getSharedPreferences("conversation_bubble", Context.MODE_PRIVATE)
    private val selection = MutableStateFlow(readSelection())
    private val preview = MutableStateFlow<BubblePreview?>(null)

    override val selectedRoom = selection
    override val latestPreview = preview
    override val isAvailable = lockScreenService.isPinSetup().map { pinIsSetup -> !pinIsSetup }

    init {
        sessionObserver.addListener(object : SessionListener {
            override suspend fun onSessionDeleted(userId: String, wasLastSession: Boolean) {
                selection.value?.takeIf { it.sessionId.value == userId }?.let { clear(it.sessionId, it.roomId) }
            }
        })
        lockScreenService.isPinSetup().onEach { pinIsSetup ->
            selection.value?.let { selected ->
                if (pinIsSetup) {
                    clear(selected.sessionId, selected.roomId)
                } else {
                    val name = preferences.getString(KEY_ROOM_NAME, null)
                    if (name != null) publishBubble(selected, name, preferences.getString(KEY_AVATAR_URL, null))
                }
            }
        }.launchIn(coroutineScope)
    }

    override fun select(sessionId: SessionId, roomId: RoomId) {
        selection.value?.takeIf { it != BubbleRoom(sessionId, roomId) }?.let(::cancelBubbleNotification)
        preferences.edit()
            .putString(KEY_SESSION_ID, sessionId.value)
            .putString(KEY_ROOM_ID, roomId.value)
            .apply()
        selection.value = BubbleRoom(sessionId, roomId)
        preview.value = null
    }

    override fun clear(sessionId: SessionId, roomId: RoomId) {
        if (selection.value != BubbleRoom(sessionId, roomId)) return
        cancelBubbleNotification(BubbleRoom(sessionId, roomId))
        preferences.edit().clear().apply()
        selection.value = null
        preview.value = null
    }

    override fun updatePreview(sessionId: SessionId, roomId: RoomId, roomName: String, latestMessage: String?) {
        val room = BubbleRoom(sessionId, roomId)
        if (selection.value == room) preview.value = BubblePreview(room, roomName, latestMessage)
    }

    override suspend fun showSelectedBubble(roomName: String, roomAvatarUrl: String?) {
        val room = selection.value ?: return
        preferences.edit().putString(KEY_ROOM_NAME, roomName).putString(KEY_AVATAR_URL, roomAvatarUrl).apply()
        if (lockScreenService.isPinSetup().first()) return
        publishBubble(room, roomName, roomAvatarUrl)
    }

    override fun restoreAfterMessagesCleared(sessionId: SessionId, roomId: RoomId) {
        val room = BubbleRoom(sessionId, roomId)
        if (selection.value != room) return
        preview.value = null
        val name = preferences.getString(KEY_ROOM_NAME, null) ?: return
        val avatarUrl = preferences.getString(KEY_AVATAR_URL, null)
        coroutineScope.launch {
            if (!lockScreenService.isPinSetup().first() && selection.value == room) {
                publishBubble(room, name, avatarUrl)
            }
        }
    }

    private suspend fun publishBubble(room: BubbleRoom, roomName: String, avatarUrl: String?) {
        val client = matrixClientProvider.getOrRestore(room.sessionId).getOrNull() ?: return
        val imageLoader = imageLoaderHolder.get(client)
        val bitmap = bitmapLoader.getRoomBitmap(
            avatarData = AvatarData(room.roomId.value, roomName, avatarUrl, AvatarSize.RoomDetailsHeader),
            imageLoader = imageLoader,
        )
        val icon = bitmap?.let(IconCompat::createWithBitmap)
            ?: IconCompat.createWithResource(context, CommonDrawables.ic_notification)
        val openChat = pendingIntentFactory.createOpenRoomPendingIntent(room.sessionId, room.roomId, eventId = null)
        val bubble = NotificationCompat.BubbleMetadata.Builder(
            pendingIntentFactory.createBubblePendingIntent(room.sessionId, room.roomId),
            icon,
        ).setDesiredHeight(600).setSuppressNotification(true).build()
        val notification = NotificationCompat.Builder(context, notificationChannels.getChannelIdForMessage(room.sessionId, noisy = false))
            .setSmallIcon(CommonDrawables.ic_notification)
            .setContentTitle(roomName)
            .setContentText(context.getString(R.string.bubble_open_chat))
            .setStyle(
                NotificationCompat.MessagingStyle(
                    Person.Builder().setName(client.userProfile.value.displayName ?: context.getString(R.string.bubble_self)).build()
                ).setConversationTitle(roomName)
            )
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setShortcutId(createShortcutId(room.sessionId, room.roomId))
            .addPerson(Person.Builder().setName(roomName).setKey(room.roomId.value).build())
            .setGroup(room.sessionId.value)
            .setContentIntent(openChat)
            .setBubbleMetadata(bubble)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()
        notificationDisplayer.showNotification(
            tag = NotificationCreator.messageTag(room.roomId, threadId = null),
            id = NotificationIdProvider.getRoomMessagesNotificationId(room.sessionId),
            notification = notification,
        )
    }

    private fun cancelBubbleNotification(room: BubbleRoom) {
        notificationDisplayer.cancelNotification(
            NotificationCreator.messageTag(room.roomId, threadId = null),
            NotificationIdProvider.getRoomMessagesNotificationId(room.sessionId),
        )
    }

    private fun readSelection(): BubbleRoom? {
        val sessionId = preferences.getString(KEY_SESSION_ID, null) ?: return null
        val roomId = preferences.getString(KEY_ROOM_ID, null) ?: return null
        return BubbleRoom(SessionId(sessionId), RoomId(roomId))
    }

    private companion object {
        const val KEY_SESSION_ID = "session_id"
        const val KEY_ROOM_ID = "room_id"
        const val KEY_ROOM_NAME = "room_name"
        const val KEY_AVATAR_URL = "avatar_url"
    }
}
