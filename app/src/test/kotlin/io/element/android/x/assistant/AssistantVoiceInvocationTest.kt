/*
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.x.assistant

import android.service.voice.VoiceInteractionSession
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AssistantVoiceInvocationTest {
    @Test
    fun `system gesture and physical button can start a call`() {
        assertThat(isTrustedAssistantInvocation(VoiceInteractionSession.SHOW_SOURCE_ASSIST_GESTURE)).isTrue()
        assertThat(isTrustedAssistantInvocation(VoiceInteractionSession.SHOW_SOURCE_PUSH_TO_TALK)).isTrue()
    }

    @Test
    fun `app requests and unknown origins cannot start a call`() {
        assertThat(isTrustedAssistantInvocation(0)).isFalse()
        assertThat(isTrustedAssistantInvocation(VoiceInteractionSession.SHOW_SOURCE_ACTIVITY)).isFalse()
        assertThat(
            isTrustedAssistantInvocation(VoiceInteractionSession.SHOW_SOURCE_ASSIST_GESTURE or VoiceInteractionSession.SHOW_SOURCE_APPLICATION)
        ).isFalse()
    }
}
