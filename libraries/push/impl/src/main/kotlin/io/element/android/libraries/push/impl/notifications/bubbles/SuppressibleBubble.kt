/*
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */
package io.element.android.libraries.push.impl.notifications.bubbles

import android.app.Notification
import android.content.Context
import android.os.Build

/** Android 12+ hides this bubble while an Activity displays the notification's matching locus. */
internal fun Notification.withSuppressibleBubble(context: Context): Notification {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return this
    val metadata = bubbleMetadata ?: return this
    val intent = metadata.intent ?: return this
    val icon = metadata.icon ?: return this
    // NotificationCompat does not expose setSuppressableBubble. Keep its other bubble options intact.
    val suppressibleMetadata = Notification.BubbleMetadata.Builder(intent, icon)
        .setAutoExpandBubble(metadata.autoExpandBubble)
        .setSuppressNotification(metadata.isNotificationSuppressed)
        .setDeleteIntent(metadata.deleteIntent)
        .setSuppressableBubble(true)
        .apply {
            if (metadata.desiredHeightResId != 0) {
                setDesiredHeightResId(metadata.desiredHeightResId)
            } else {
                setDesiredHeight(metadata.desiredHeight)
            }
        }
        .build()
    return Notification.Builder.recoverBuilder(context, this)
        .setBubbleMetadata(suppressibleMetadata)
        .build()
}
