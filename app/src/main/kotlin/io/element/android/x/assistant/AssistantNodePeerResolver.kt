/*
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.x.assistant

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.element.android.features.androidtoolbox.api.OpenClawNodeBinding
import io.element.android.features.lockscreen.api.LockScreenService
import io.element.android.libraries.matrix.api.MatrixClientProvider
import io.element.android.libraries.matrix.api.encryption.identity.IdentityState
import io.element.android.libraries.matrix.api.room.RoomMembershipState
import io.element.android.libraries.preferences.api.store.AppPreferencesStore
import io.element.android.libraries.preferences.api.store.AssistantChat
import io.element.android.libraries.sessionstorage.api.SessionStore

/** Personal user verification and a two-member encrypted DM are required independently of native node pairing. */
@SingleIn(AppScope::class)
@Inject
class AssistantNodePeerResolver(
    preferences: AppPreferencesStore,
    sessions: SessionStore,
    lockScreen: LockScreenService,
    private val clients: MatrixClientProvider,
) {
    private val resolver = AssistantChatResolver(preferences, sessions, lockScreen)

    suspend fun resolve(sessionId: String, roomId: String, deviceId: String): Pair<AssistantChat, OpenClawNodeBinding>? {
        if (deviceId.isBlank() || deviceId == "*" || deviceId.length > 255 || deviceId.any { it.isWhitespace() || it.isISOControl() }) return null
        val target = (resolver.resolve() as? AssistantChatState.Ready)?.chat ?: return null
        if (!target.voiceEnabled || target.sessionId.value != sessionId || target.roomId.value != roomId) return null
        val client = clients.getOrRestore(target.sessionId).getOrNull() ?: return null
        val room = client.getJoinedRoom(target.roomId) ?: return null
        val peer = room.use {
            val info = it.info()
            if (!info.isDirect || info.isEncrypted != true || info.joinedMembersCount != 2L) return@use null
            it.getDirectRoomMember()?.takeIf { member -> member.userId != target.sessionId && member.membership == RoomMembershipState.JOIN }
        } ?: return null
        if (client.encryptionService.getUserIdentity(peer.userId, fallbackToServer = true).getOrNull() != IdentityState.Verified) return null
        val selected = (resolver.resolve() as? AssistantChatState.Ready)?.chat ?: return null
        if (!selected.hasSameTargetAs(target) || !selected.voiceEnabled) return null
        return selected to OpenClawNodeBinding(sessionId, roomId, peer.userId.value, deviceId)
    }
}
