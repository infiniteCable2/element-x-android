/*
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */
package io.element.android.libraries.push.impl.notifications.bubbles

import android.app.Notification
import android.os.Build
import androidx.core.app.NotificationCompat
import com.google.common.truth.Truth.assertThat
import io.element.android.features.lockscreen.test.FakeLockScreenService
import io.element.android.libraries.matrix.test.A_ROOM_ID
import io.element.android.libraries.matrix.test.A_ROOM_ID_2
import io.element.android.libraries.matrix.test.A_SESSION_ID
import io.element.android.libraries.matrix.test.FakeMatrixClientProvider
import io.element.android.libraries.matrix.test.core.aBuildMeta
import io.element.android.libraries.matrixmedia.test.FakeImageLoaderHolder
import io.element.android.libraries.push.api.notifications.bubbles.BubbleRoom
import io.element.android.libraries.push.impl.notifications.NotificationActionIds
import io.element.android.libraries.push.impl.notifications.channels.FakeNotificationChannels
import io.element.android.libraries.push.impl.notifications.factories.FakeIntentProvider
import io.element.android.libraries.push.impl.notifications.factories.PendingIntentFactory
import io.element.android.libraries.push.impl.notifications.fake.FakeNotificationDisplayer
import io.element.android.libraries.push.test.notifications.push.FakeNotificationBitmapLoader
import io.element.android.libraries.sessionstorage.test.observer.FakeSessionObserver
import io.element.android.services.toolbox.test.systemclock.FakeSystemClock
import io.element.android.tests.testutils.lambda.lambdaRecorder
import io.element.android.tests.testutils.robolectric.RobolectricTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class DefaultConversationBubbleServiceTest : RobolectricTest() {
    @Test
    fun `read bubble stays visible without a shade notification or fake unread message`() = runTest {
        val notifications = mutableListOf<Notification>()
        val service = createService(notifications)
        runCurrent()
        service.select(A_SESSION_ID, A_ROOM_ID)
        service.showSelectedBubble("Room title", null)

        val notification = notifications.single()
        assertThat(notification.bubbleMetadata?.isBubbleSuppressable).isFalse()
        assertReadBubble(notification)
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.R])
    fun `read bubble also suppresses the shade notification on Android 11`() = runTest {
        val notifications = mutableListOf<Notification>()
        val service = createService(notifications)
        runCurrent()
        service.select(A_SESSION_ID, A_ROOM_ID)
        service.showSelectedBubble("Room title", null)

        assertReadBubble(notifications.single())
    }

    @Test
    fun `clearing messages restores the read bubble without losing selection`() = runTest {
        val notifications = mutableListOf<Notification>()
        val service = createService(notifications)
        runCurrent()
        service.select(A_SESSION_ID, A_ROOM_ID)
        service.showSelectedBubble("Room title", null)
        service.updatePreview(A_SESSION_ID, A_ROOM_ID, "Room title", "Unread preview")
        notifications.clear()

        service.restoreAfterMessagesCleared(A_SESSION_ID, A_ROOM_ID)
        runCurrent()

        assertReadBubble(notifications.single())
        assertThat(notifications.single().bubbleMetadata?.isBubbleSuppressable).isFalse()
        assertThat(service.selectedRoom.value).isEqualTo(BubbleRoom(A_SESSION_ID, A_ROOM_ID))
        assertThat(service.latestPreview.value).isNull()
    }

    @Test
    fun `clearing messages in another room does not recreate the selected bubble`() = runTest {
        val notifications = mutableListOf<Notification>()
        val service = createService(notifications)
        runCurrent()
        service.select(A_SESSION_ID, A_ROOM_ID)
        service.showSelectedBubble("Room title", null)
        notifications.clear()

        service.restoreAfterMessagesCleared(A_SESSION_ID, A_ROOM_ID_2)
        runCurrent()

        assertThat(notifications).isEmpty()
        assertThat(service.selectedRoom.value).isEqualTo(BubbleRoom(A_SESSION_ID, A_ROOM_ID))
    }

    @Test
    fun `app pin prevents publishing the persistent bubble`() = runTest {
        val notifications = mutableListOf<Notification>()
        val lockScreenService = FakeLockScreenService().apply { setIsPinSetup(true) }
        val service = createService(notifications, lockScreenService)
        runCurrent()
        service.select(A_SESSION_ID, A_ROOM_ID)

        service.showSelectedBubble("Room title", null)
        service.restoreAfterMessagesCleared(A_SESSION_ID, A_ROOM_ID)
        runCurrent()

        assertThat(notifications).isEmpty()
    }

    private fun assertReadBubble(notification: Notification) {
        assertThat(notification.bubbleMetadata).isNotNull()
        assertThat(notification.bubbleMetadata?.isNotificationSuppressed).isTrue()
        assertThat(notification.bubbleMetadata?.autoExpandBubble).isFalse()
        assertThat(notification.locusId).isNull()
        val style = checkNotNull(NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification))
        assertThat(style.messages).isEmpty()
        assertThat(notification.flags and Notification.FLAG_ONLY_ALERT_ONCE).isNotEqualTo(0)
    }

    private fun TestScope.createService(
        notifications: MutableList<Notification>,
        lockScreenService: FakeLockScreenService = FakeLockScreenService(),
    ): DefaultConversationBubbleService {
        val context = RuntimeEnvironment.getApplication()
        return DefaultConversationBubbleService(
            context = context,
            lockScreenService = lockScreenService,
            sessionObserver = FakeSessionObserver(),
            notificationDisplayer = FakeNotificationDisplayer(
                showNotificationResult = lambdaRecorder { _, _, notification ->
                    notifications.add(notification)
                    true
                },
            ),
            notificationChannels = FakeNotificationChannels(channelIdForMessage = { _, _ -> "bubble-test" }),
            pendingIntentFactory = PendingIntentFactory(context, FakeIntentProvider(), FakeSystemClock(), NotificationActionIds(aBuildMeta())),
            bitmapLoader = FakeNotificationBitmapLoader(),
            matrixClientProvider = FakeMatrixClientProvider(),
            imageLoaderHolder = FakeImageLoaderHolder(),
            coroutineScope = backgroundScope,
        )
    }
}
