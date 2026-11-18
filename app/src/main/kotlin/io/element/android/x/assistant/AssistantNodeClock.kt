/*
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.x.assistant

import android.os.SystemClock
import dev.zacsweers.metro.Inject

/** Includes deep sleep, so turning the screen off cannot extend a remote invocation deadline. */
@Inject
class AssistantNodeClock {
    fun elapsedRealtimeMs(): Long = SystemClock.elapsedRealtime()
}
