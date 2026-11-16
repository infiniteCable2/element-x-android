/*
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.androidtoolbox.impl

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import io.element.android.features.androidtoolbox.api.AndroidToolLauncher
import io.element.android.features.androidtoolbox.api.AndroidToolModule
import io.element.android.features.androidtoolbox.api.AndroidToolResult
import io.element.android.features.androidtoolbox.api.AndroidToolbox
import io.element.android.libraries.core.coroutine.CoroutineDispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

@ContributesBinding(AppScope::class)
class DefaultAndroidToolbox(
    modules: Set<AndroidToolModule>,
    private val dispatchers: CoroutineDispatchers,
) : AndroidToolbox {
    private val handlers = buildMap {
        for (module in modules) {
            for (definition in module.definitions) {
                check(put(definition.command, module to definition) == null) { "Duplicate Android toolbox command" }
            }
        }
    }

    override fun catalog(launcher: AndroidToolLauncher, grants: Set<String>) = handlers.values
        .filter { (module, _) -> module.capability in grants && module.isAvailable(launcher) }
        .map { (_, definition) -> definition }
        .sortedBy { it.command }

    override suspend fun execute(command: String, params: JsonObject, launcher: AndroidToolLauncher, grants: suspend () -> Set<String>): AndroidToolResult =
        withContext(dispatchers.main) {
            val (module, definition) = handlers[command]
                ?: return@withContext AndroidToolResult.Failure(AndroidToolResult.Reason.UNKNOWN_COMMAND, "Unknown Android command.")
            if (module.capability !in grants() || (definition.requiresForeground && !launcher.isInteractive())) {
                return@withContext AndroidToolResult.Failure(AndroidToolResult.Reason.NOT_AUTHORIZED, "An enabled, visible and unlocked assistant is required.")
            }
            try {
                if (!module.isAvailable(launcher)) {
                    AndroidToolResult.Failure(AndroidToolResult.Reason.NOT_AVAILABLE, "The required Android app is unavailable.")
                } else {
                    module.execute(command, params, launcher)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                AndroidToolResult.Failure(AndroidToolResult.Reason.EXECUTION_FAILED, "Android could not execute this command.")
            }
        }
}
