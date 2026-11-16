/*
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.androidtoolbox.test

import io.element.android.features.androidtoolbox.api.AndroidAppLaunch
import io.element.android.features.androidtoolbox.api.AndroidToolLauncher
import io.element.android.features.androidtoolbox.api.AndroidToolResult
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class RecordingAndroidToolLauncher : AndroidToolLauncher {
    var interactive = true
    val availablePackages = mutableSetOf("com.google.android.apps.maps")
    val submissions = mutableListOf<AndroidAppLaunch>()
    var result: AndroidToolResult = AndroidToolResult.Success(buildJsonObject { put("status", "submitted") })
    override fun isInteractive() = interactive
    override fun isAppAvailable(packageName: String) = packageName in availablePackages
    override fun submit(action: AndroidAppLaunch): AndroidToolResult {
        submissions += action
        return result
    }
}
