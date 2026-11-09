/*
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.preferences.api.store

import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.SessionId

/** A device-local assistant target; it is independent of the chat bubble. */
data class AssistantChat(val sessionId: SessionId, val roomId: RoomId, val voiceEnabled: Boolean = false) {
    fun hasSameTargetAs(other: AssistantChat): Boolean = sessionId == other.sessionId && roomId == other.roomId
}
