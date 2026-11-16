/*
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.x.assistant

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.element.android.features.androidtoolbox.api.AndroidToolLauncherFactory

/** Main-thread window lease. The native node transport must separately authorise each invocation. */
@SingleIn(AppScope::class)
@Inject
class AssistantToolSurface(factory: AndroidToolLauncherFactory) {
    private var binding: Binding? = null
    val launcher = factory.create { binding?.isVisible?.invoke() == true }

    fun bind(owner: Any, isVisible: () -> Boolean) {
        binding = Binding(owner, isVisible)
    }

    fun unbind(owner: Any) {
        if (binding?.owner === owner) binding = null
    }

    private data class Binding(val owner: Any, val isVisible: () -> Boolean)
}
