/*
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.x.assistant

import com.google.common.truth.Truth.assertThat
import io.element.android.features.lockscreen.api.LockScreenLockState
import io.element.android.features.lockscreen.api.LockScreenService
import io.element.android.libraries.matrix.api.MatrixClient
import io.element.android.libraries.matrix.api.MatrixClientProvider
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.SessionId
import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.libraries.matrix.api.encryption.EncryptionService
import io.element.android.libraries.matrix.api.encryption.identity.IdentityState
import io.element.android.libraries.matrix.api.room.JoinedRoom
import io.element.android.libraries.matrix.api.room.RoomMember
import io.element.android.libraries.matrix.api.room.RoomMembershipState
import io.element.android.libraries.preferences.api.store.AppPreferencesStore
import io.element.android.libraries.preferences.api.store.AssistantChat
import io.element.android.libraries.sessionstorage.api.SessionData
import io.element.android.libraries.sessionstorage.api.SessionStore
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test

class AssistantNodePeerResolverTest {
    private val chat = AssistantChat(SessionId("@sample:example.org"), RoomId("!sample:example.org"), voiceEnabled = true)
    private val selected = MutableStateFlow<AssistantChat?>(chat)
    private val peerId = UserId("@assistant:example.org")
    private var verified: IdentityState? = IdentityState.Verified
    private var encrypted: Boolean? = true
    private var direct = true
    private var count = 2L
    private var memberId = peerId
    private var membership = RoomMembershipState.JOIN
    private var afterIdentity: () -> Unit = {}
    private val lockState = MutableStateFlow<LockScreenLockState>(LockScreenLockState.Unlocked)
    private val mandatorySetup = MutableStateFlow(false)
    private val client = mockk<MatrixClient>()
    private val room = mockk<JoinedRoom>(relaxed = true)
    private val encryption = mockk<EncryptionService>()
    private val sessions = mockk<SessionStore> {
        coEvery { getSession(chat.sessionId.value) } returns mockk<SessionData> { every { isTokenValid } returns true }
    }
    private val resolver = AssistantNodePeerResolver(
        mockk<AppPreferencesStore> { every { getAssistantChatFlow() } returns selected },
        sessions,
        object : LockScreenService {
            override val lockState = this@AssistantNodePeerResolverTest.lockState
            override fun isPinSetup() = MutableStateFlow(true)
            override fun isSetupRequired() = mandatorySetup
        },
        mockk<MatrixClientProvider> { coEvery { getOrRestore(chat.sessionId) } returns Result.success(client) },
    )

    init {
        coEvery { client.getJoinedRoom(chat.roomId) } returns room
        every { client.encryptionService } returns encryption
        every { room.info() } answers {
            mockk {
                every { isDirect } returns direct
                every { isEncrypted } returns encrypted
                every { joinedMembersCount } returns count
            }
        }
        coEvery { room.getDirectRoomMember() } answers {
            RoomMember(
                userId = memberId,
                displayName = null,
                avatarUrl = null,
                membership = membership,
                isNameAmbiguous = false,
                powerLevel = 0,
                isIgnored = false,
                role = RoomMember.Role.User,
                membershipChangeReason = null,
                isServiceMember = false,
                displayedStatus = null,
            )
        }
        // MockK's generic any<UserId>() constructs a random invalid value-class ID while recording the signature.
        coEvery { encryption.getUserIdentity(peerId, fallbackToServer = true) } answers {
            afterIdentity()
            Result.success(verified)
        }
    }

    private suspend fun resolve(device: String = "SAMPLEDEVICE") = resolver.resolve(chat.sessionId.value, chat.roomId.value, device)

    @Test
    fun `only personally verified direct encrypted joined peer can be selected`() = runTest {
        assertThat(resolve()!!.second.peerUserId).isEqualTo(peerId.value)
        assertThat(resolve()!!.second.peerDeviceId).isEqualTo("SAMPLEDEVICE")
        for (state in listOf(null, IdentityState.Pinned, IdentityState.PinViolation, IdentityState.VerificationViolation)) {
            verified = state
            assertThat(resolve()).isNull()
        }
    }

    @Test
    fun `groups unknown encryption absent peer self and departed peer are rejected`() = runTest {
        direct = false
        assertThat(resolve()).isNull()
        direct = true
        count = 3
        assertThat(resolve()).isNull()
        count = 2
        encrypted = null
        assertThat(resolve()).isNull()
        encrypted = false
        assertThat(resolve()).isNull()
        encrypted = true
        membership = RoomMembershipState.LEAVE
        assertThat(resolve()).isNull()
        membership = RoomMembershipState.JOIN
        memberId = chat.sessionId
        assertThat(resolve()).isNull()
        coEvery { room.getDirectRoomMember() } returns null
        assertThat(resolve()).isNull()
    }

    @Test
    fun `wildcard and invalid device IDs and disabled or different target cannot bind`() = runTest {
        for (device in listOf("*", "", " ", "two devices", "a\nb", "a".repeat(256))) assertThat(resolve(device)).isNull()
        selected.value = chat.copy(voiceEnabled = false)
        assertThat(resolve()).isNull()
        selected.value = chat.copy(roomId = RoomId("!other:example.org"))
        assertThat(resolve()).isNull()
        selected.value = null
        assertThat(resolve()).isNull()
    }

    @Test
    fun `membership changing during verification cannot authorise the old DM shape`() = runTest {
        afterIdentity = { count = 3 }
        assertThat(resolve()).isNull()
        count = 2
        afterIdentity = { memberId = UserId("@replacement:example.org") }
        assertThat(resolve()).isNull()
    }

    @Test
    fun `PIN lock and target change during identity lookup cannot authorise a stale selection`() = runTest {
        afterIdentity = { lockState.value = LockScreenLockState.Locked }
        assertThat(resolve()).isNull()
        lockState.value = LockScreenLockState.Unlocked
        afterIdentity = { selected.value = null }
        assertThat(resolve()).isNull()
        selected.value = chat
        afterIdentity = {}
        mandatorySetup.value = true
        assertThat(resolve()).isNull()
    }
}
