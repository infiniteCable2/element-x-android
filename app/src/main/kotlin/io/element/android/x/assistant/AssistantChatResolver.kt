/*
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.x.assistant

import io.element.android.features.lockscreen.api.LockScreenLockState
import io.element.android.features.lockscreen.api.LockScreenService
import io.element.android.libraries.core.extensions.runCatchingExceptions
import io.element.android.libraries.preferences.api.store.AppPreferencesStore
import io.element.android.libraries.preferences.api.store.AssistantChat
import io.element.android.libraries.sessionstorage.api.SessionStore
import kotlinx.coroutines.flow.first

internal sealed interface AssistantChatState {
    data object NotConfigured : AssistantChatState
    data object Locked : AssistantChatState
    data object Unavailable : AssistantChatState
    data class Ready(val chat: AssistantChat) : AssistantChatState
}

internal class AssistantChatResolver(
    private val preferences: AppPreferencesStore,
    private val sessionStore: SessionStore,
    private val lockScreenService: LockScreenService,
) {
    suspend fun resolve(): AssistantChatState = runCatchingExceptions {
        if (isAppLocked()) return@runCatchingExceptions AssistantChatState.Locked
        val chat = preferences.getAssistantChatFlow().first() ?: return@runCatchingExceptions AssistantChatState.NotConfigured
        val session = sessionStore.getSession(chat.sessionId.value)
        if (isAppLocked()) return@runCatchingExceptions AssistantChatState.Locked
        if (session?.isTokenValid == true) AssistantChatState.Ready(chat) else AssistantChatState.Unavailable
    }.getOrDefault(AssistantChatState.Unavailable)

    private suspend fun isAppLocked(): Boolean = lockScreenService.isSetupRequired().first() ||
        (lockScreenService.isPinSetup().first() && lockScreenService.lockState.value != LockScreenLockState.Unlocked)
}
