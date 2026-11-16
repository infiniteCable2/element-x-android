/*
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.androidtoolbox.impl

import android.app.KeyguardManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import io.element.android.features.androidtoolbox.api.AndroidAppLaunch
import io.element.android.features.androidtoolbox.api.AndroidToolLauncher
import io.element.android.features.androidtoolbox.api.AndroidToolLauncherFactory
import io.element.android.features.androidtoolbox.api.AndroidToolResult
import io.element.android.libraries.di.annotations.ApplicationContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@ContributesBinding(AppScope::class)
class DefaultAndroidToolLauncherFactory(@ApplicationContext private val context: Context) : AndroidToolLauncherFactory {
    override fun create(isVisible: () -> Boolean): AndroidToolLauncher = AndroidActivityLauncher(context, isVisible)
}

internal class AndroidActivityLauncher(private val context: Context, private val isVisible: () -> Boolean) : AndroidToolLauncher {
    override fun isInteractive(): Boolean {
        val keyguard = context.getSystemService(KeyguardManager::class.java) ?: return false
        return isVisible() && !keyguard.isDeviceLocked && !keyguard.isKeyguardLocked
    }

    override fun isAppAvailable(packageName: String): Boolean = try {
        @Suppress("DEPRECATION")
        val info = context.packageManager.getApplicationInfo(packageName, 0)
        info.enabled && (info.flags and ApplicationInfo.FLAG_SUSPENDED) == 0
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    override fun submit(action: AndroidAppLaunch): AndroidToolResult {
        if (!isInteractive()) return AndroidToolResult.Failure(AndroidToolResult.Reason.NOT_AUTHORIZED, "The assistant is not visible or the device is locked.")
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(action.uri)).setPackage(action.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            if (intent.resolveActivity(context.packageManager) == null) {
                return AndroidToolResult.Failure(AndroidToolResult.Reason.NOT_AVAILABLE, "No installed app can handle this action.")
            }
            context.startActivity(intent)
            // startActivity is a handoff, not proof of navigation, playback or completion inside another app.
            AndroidToolResult.Success(buildJsonObject {
                put("status", "submitted")
                put("package", action.packageName)
            })
        } catch (_: ActivityNotFoundException) {
            AndroidToolResult.Failure(AndroidToolResult.Reason.NOT_AVAILABLE, "The target app is unavailable.")
        } catch (_: SecurityException) {
            AndroidToolResult.Failure(AndroidToolResult.Reason.NOT_AUTHORIZED, "Android refused this app launch.")
        }
    }
}
