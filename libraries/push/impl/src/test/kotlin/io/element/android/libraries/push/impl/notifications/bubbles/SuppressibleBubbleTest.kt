/*
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */
package io.element.android.libraries.push.impl.notifications.bubbles

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.LocusIdCompat
import androidx.core.graphics.drawable.IconCompat
import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.designsystem.utils.CommonDrawables
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.SessionId
import io.element.android.libraries.matrix.test.A_ROOM_ID
import io.element.android.libraries.matrix.test.A_ROOM_ID_2
import io.element.android.libraries.matrix.test.A_SESSION_ID
import io.element.android.libraries.matrix.test.A_SESSION_ID_2
import io.element.android.libraries.push.api.notifications.bubbles.BubbleRoom
import io.element.android.tests.testutils.robolectric.RobolectricTest
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

class SuppressibleBubbleTest : RobolectricTest() {
    @Test
    fun `locus identifies both the account and room without ambiguous boundaries`() {
        val room = BubbleRoom(A_SESSION_ID, A_ROOM_ID)
        assertThat(room.locusId).isEqualTo(BubbleRoom(A_SESSION_ID, A_ROOM_ID).locusId)
        assertThat(room.locusId).isNotEqualTo(BubbleRoom(A_SESSION_ID_2, A_ROOM_ID).locusId)
        assertThat(room.locusId).isNotEqualTo(BubbleRoom(A_SESSION_ID, A_ROOM_ID_2).locusId)
        // Accepted legacy IDs can contain delimiters, so concatenation alone is ambiguous.
        val first = BubbleRoom(SessionId("@a:example.org!b:example.org"), RoomId("!c:example.org"))
        val second = BubbleRoom(SessionId("@a:example.org"), RoomId("!b:example.org!c:example.org"))
        assertThat(first.sessionId.value + first.roomId.value).isEqualTo(second.sessionId.value + second.roomId.value)
        assertThat(first.locusId).isNotEqualTo(second.locusId)
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.S])
    fun `Android 12 bubble is suppressible and keeps its notification and launch options`() {
        val context = RuntimeEnvironment.getApplication()
        val original = notificationWithBubble()
        val result = original.withSuppressibleBubble(context)
        val metadata = requireNotNull(result.bubbleMetadata)
        assertThat(metadata.isBubbleSuppressable).isTrue()
        assertThat(metadata.isNotificationSuppressed).isTrue()
        assertThat(metadata.autoExpandBubble).isFalse()
        assertThat(metadata.desiredHeight).isEqualTo(600)
        assertThat(metadata.intent).isEqualTo(original.bubbleMetadata?.intent)
        assertThat(metadata.deleteIntent).isEqualTo(original.bubbleMetadata?.deleteIntent)
        assertThat(result.locusId?.id).isEqualTo(BubbleRoom(A_SESSION_ID, A_ROOM_ID).locusId)
        assertThat(result.contentIntent).isEqualTo(original.contentIntent)
        assertThat(result.channelId).isEqualTo(original.channelId)
        assertThat(result.flags).isEqualTo(original.flags)
        assertThat(result.extras.getCharSequence(Notification.EXTRA_TITLE)).isEqualTo("Room")
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.R])
    fun `Android 11 keeps the original bubble unchanged`() {
        val original = notificationWithBubble()
        assertThat(original.withSuppressibleBubble(RuntimeEnvironment.getApplication())).isSameInstanceAs(original)
    }

    @Test
    fun `regular notifications remain unchanged`() {
        val context = RuntimeEnvironment.getApplication()
        val original = NotificationCompat.Builder(context, "messages").setSmallIcon(CommonDrawables.ic_notification).build()
        assertThat(original.withSuppressibleBubble(context)).isSameInstanceAs(original)
    }

    private fun notificationWithBubble(): Notification {
        val context = RuntimeEnvironment.getApplication()
        val openChat = PendingIntent.getActivity(context, 0, Intent(context, BubbleLaunchActivity::class.java), PendingIntent.FLAG_MUTABLE)
        val deleteIntent = PendingIntent.getBroadcast(context, 1, Intent("dismiss-bubble"), PendingIntent.FLAG_IMMUTABLE)
        val metadata = NotificationCompat.BubbleMetadata.Builder(
            openChat,
            IconCompat.createWithResource(context, CommonDrawables.ic_notification),
        )
            .setDesiredHeight(600)
            .setAutoExpandBubble(false)
            .setSuppressNotification(true)
            .setDeleteIntent(deleteIntent)
            .build()
        return NotificationCompat.Builder(context, "messages")
            .setSmallIcon(CommonDrawables.ic_notification)
            .setContentTitle("Room")
            .setContentIntent(openChat)
            .setLocusId(LocusIdCompat(BubbleRoom(A_SESSION_ID, A_ROOM_ID).locusId))
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setBubbleMetadata(metadata)
            .build()
    }
}
