/*
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.androidtoolbox.api

import kotlinx.serialization.json.JsonObject

/** Local Android adapters. The caller owns authenticated device/session binding and invocation IDs. */
interface AndroidToolbox {
    fun catalog(launcher: AndroidToolLauncher, grants: Set<String>): List<AndroidToolDefinition>
    suspend fun execute(command: String, params: JsonObject, launcher: AndroidToolLauncher, grants: Set<String>): AndroidToolResult
}

data class AndroidToolDefinition(
    val command: String,
    val description: String,
    val parameters: JsonObject,
    val requiresForeground: Boolean = true,
)

/** Each module contributes commands to the same dispatcher and permission boundary. */
interface AndroidToolModule {
    val capability: String
    val definitions: List<AndroidToolDefinition>
    fun isAvailable(launcher: AndroidToolLauncher): Boolean
    suspend fun execute(command: String, params: JsonObject, launcher: AndroidToolLauncher): AndroidToolResult
}

data class AndroidAppLaunch(val packageName: String, val uri: String)

/** A launch surface belongs to a visible, user-initiated assistant session, not to remote JSON. */
interface AndroidToolLauncher {
    fun isInteractive(): Boolean
    fun isAppAvailable(packageName: String): Boolean
    fun submit(action: AndroidAppLaunch): AndroidToolResult
}

fun interface AndroidToolLauncherFactory {
    fun create(isVisible: () -> Boolean): AndroidToolLauncher
}

sealed interface AndroidToolResult {
    data class Success(val payload: JsonObject) : AndroidToolResult
    data class Failure(val reason: Reason, val message: String) : AndroidToolResult

    enum class Reason {
        UNKNOWN_COMMAND,
        INVALID_PARAMS,
        NOT_AVAILABLE,
        NOT_AUTHORIZED,
        EXECUTION_FAILED,
    }
}

object AndroidToolCapabilities {
    const val MAPS = "maps"
}
