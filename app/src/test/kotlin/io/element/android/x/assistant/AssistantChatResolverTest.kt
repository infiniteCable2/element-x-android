/*
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.x.assistant

import com.google.common.truth.Truth.assertThat
import io.element.android.features.lockscreen.api.LockScreenLockState
import io.element.android.features.lockscreen.api.LockScreenService
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.SessionId
import io.element.android.libraries.preferences.api.store.AppPreferencesStore
import io.element.android.libraries.preferences.api.store.AssistantChat
import io.element.android.libraries.sessionstorage.api.SessionData
import io.element.android.libraries.sessionstorage.api.SessionStore
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test

class AssistantChatResolverTest {
    private val chat = AssistantChat(SessionId("@example:example.org"), RoomId("!sample:example.org"))
    private val selectedChat = MutableStateFlow<AssistantChat?>(null)
    private val pinSetupRequired = MutableStateFlow(false)
    private val pinSetup = MutableStateFlow(false)
    private val lockState = MutableStateFlow<LockScreenLockState>(LockScreenLockState.Locked)
    private val preferences = mockk<AppPreferencesStore> {
        every { getAssistantChatFlow() } returns selectedChat
    }
    private val sessionStore = mockk<SessionStore>()
    private val lockScreenService = object : LockScreenService {
        override val lockState = this@AssistantChatResolverTest.lockState
        override fun isSetupRequired() = pinSetupRequired
        override fun isPinSetup() = pinSetup
    }
    private val resolver = AssistantChatResolver(preferences, sessionStore, lockScreenService)

    @Test
    fun `no selection does not look for a session`() = runTest {
        assertThat(resolver.resolve()).isEqualTo(AssistantChatState.NotConfigured)
    }

    @Test
    fun `mandatory setup and PIN lock block access before reading the selection`() = runTest {
        pinSetupRequired.value = true
        assertThat(resolver.resolve()).isEqualTo(AssistantChatState.Locked)

        pinSetupRequired.value = false
        pinSetup.value = true
        assertThat(resolver.resolve()).isEqualTo(AssistantChatState.Locked)
        verify(exactly = 0) { preferences.getAssistantChatFlow() }
    }

    @Test
    fun `selection needs a valid session for the same account`() = runTest {
        selectedChat.value = chat
        coEvery { sessionStore.getSession(chat.sessionId.value) } returns null
        assertThat(resolver.resolve()).isEqualTo(AssistantChatState.Unavailable)

        coEvery { sessionStore.getSession(chat.sessionId.value) } returns session(tokenValid = false)
        assertThat(resolver.resolve()).isEqualTo(AssistantChatState.Unavailable)
    }

    @Test
    fun `resolves the latest selection for a valid session`() = runTest {
        selectedChat.value = chat
        coEvery { sessionStore.getSession(chat.sessionId.value) } returns session(tokenValid = true)
        assertThat(resolver.resolve()).isEqualTo(AssistantChatState.Ready(chat))

        val newChat = chat.copy(roomId = RoomId("!other:example.org"))
        selectedChat.value = newChat
        assertThat(resolver.resolve()).isEqualTo(AssistantChatState.Ready(newChat))
    }

    @Test
    fun `a PIN lock during the session lookup blocks the resolved chat`() = runTest {
        selectedChat.value = chat
        pinSetup.value = true
        lockState.value = LockScreenLockState.Unlocked
        coEvery { sessionStore.getSession(chat.sessionId.value) } answers {
            lockState.value = LockScreenLockState.Locked
            session(tokenValid = true)
        }

        assertThat(resolver.resolve()).isEqualTo(AssistantChatState.Locked)
    }

    private fun session(tokenValid: Boolean) = mockk<SessionData> {
        every { isTokenValid } returns tokenValid
    }
}
