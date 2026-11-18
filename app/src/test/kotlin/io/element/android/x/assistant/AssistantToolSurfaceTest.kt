/*
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.x.assistant

import com.google.common.truth.Truth.assertThat
import io.element.android.features.androidtoolbox.api.AndroidToolLauncher
import io.element.android.features.androidtoolbox.api.AndroidToolLauncherFactory
import io.mockk.every
import io.mockk.mockk
import org.junit.Test

class AssistantToolSurfaceTest {
    private val factory = AndroidToolLauncherFactory { visible ->
        mockk<AndroidToolLauncher> { every { isInteractive() } answers { visible() } }
    }

    @Test
    fun `no surface hidden windows and unbound windows cannot launch`() {
        val surface = AssistantToolSurface(factory)
        val owner = Any()
        var visible = false
        assertThat(surface.launcher.isInteractive()).isFalse()
        surface.bind(owner) { visible }
        assertThat(surface.launcher.isInteractive()).isFalse()
        visible = true
        assertThat(surface.launcher.isInteractive()).isTrue()
        visible = false
        assertThat(surface.launcher.isInteractive()).isFalse()
        visible = true
        surface.unbind(owner)
        assertThat(surface.launcher.isInteractive()).isFalse()
    }

    @Test
    fun `invocation lease is independent of window visibility and other invocations`() {
        val surface = AssistantToolSurface(factory)
        var granted = true
        val scoped = surface.forInvocation { granted }
        assertThat(scoped.isInteractive()).isFalse()
        surface.bind(Any()) { true }
        assertThat(scoped.isInteractive()).isTrue()
        granted = false
        assertThat(scoped.isInteractive()).isFalse()
        assertThat(surface.launcher.isInteractive()).isTrue()
    }

    @Test
    fun `destroying an old session cannot revoke the replacement window`() {
        val surface = AssistantToolSurface(factory)
        val oldOwner = Any()
        val newOwner = Any()
        surface.bind(oldOwner) { true }
        surface.bind(newOwner) { true }
        surface.unbind(oldOwner)
        assertThat(surface.launcher.isInteractive()).isTrue()
        surface.unbind(newOwner)
        assertThat(surface.launcher.isInteractive()).isFalse()
    }
}
