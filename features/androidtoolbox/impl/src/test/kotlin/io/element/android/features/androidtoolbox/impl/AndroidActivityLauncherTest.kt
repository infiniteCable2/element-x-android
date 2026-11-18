/*
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.androidtoolbox.impl

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.PowerManager
import com.google.common.truth.Truth.assertThat
import io.element.android.features.androidtoolbox.api.AndroidAppLaunch
import io.element.android.features.androidtoolbox.api.AndroidToolResult
import io.element.android.tests.testutils.robolectric.RobolectricTest
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
import org.junit.Test

class AndroidActivityLauncherTest : RobolectricTest() {
    private val keyguard = mockk<KeyguardManager> {
        every { isDeviceLocked } returns false
        every { isKeyguardLocked } returns false
    }
    private val manager = mockk<PackageManager>()
    private val power = mockk<PowerManager> { every { isInteractive } returns true }
    private val context = mockk<Context> {
        every { getSystemService(KeyguardManager::class.java) } returns keyguard
        every { getSystemService(PowerManager::class.java) } returns power
        every { packageManager } returns manager
        every { startActivity(any()) } just runs
    }
    private val action = AndroidAppLaunch("com.google.android.apps.maps", "https://www.google.com/maps/search/?api=1&query=Sample")
    private var visible = true
    private val launcher = AndroidActivityLauncher(context) { visible }

    @Test
    fun `handoff uses only the fixed target action data and task flag`() {
        every { manager.resolveActivity(any<Intent>(), any<Int>()) } returns ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                packageName = action.packageName
                name = "MapsActivity"
                applicationInfo = ApplicationInfo().apply { packageName = action.packageName }
            }
        }
        val result = launcher.submit(action)
        val intent = slot<Intent>()
        verify(exactly = 1) { context.startActivity(capture(intent)) }
        assertThat(intent.captured.action).isEqualTo(Intent.ACTION_VIEW)
        assertThat(intent.captured.`package`).isEqualTo(action.packageName)
        assertThat(intent.captured.dataString).isEqualTo(action.uri)
        assertThat(intent.captured.flags).isEqualTo(Intent.FLAG_ACTIVITY_NEW_TASK)
        assertThat((result as AndroidToolResult.Success).payload["status"].toString()).isEqualTo("\"submitted\"")
    }

    @Test
    fun `revocation during Android activity resolution prevents the actual handoff`() {
        every { manager.resolveActivity(any<Intent>(), any<Int>()) } answers {
            visible = false
            ResolveInfo().apply {
                activityInfo = ActivityInfo().apply {
                    packageName = action.packageName
                    name = "MapsActivity"
                    applicationInfo = ApplicationInfo().apply { packageName = action.packageName }
                }
            }
        }
        assertThat((launcher.submit(action) as AndroidToolResult.Failure).reason).isEqualTo(AndroidToolResult.Reason.NOT_AUTHORIZED)
        verify(exactly = 0) { context.startActivity(any()) }
    }

    @Test
    fun `visibility and both lock states prevent submission`() {
        visible = false
        assertThat(launcher.submit(action)).isInstanceOf(AndroidToolResult.Failure::class.java)
        visible = true
        every { keyguard.isDeviceLocked } returns true
        assertThat(launcher.submit(action)).isInstanceOf(AndroidToolResult.Failure::class.java)
        every { keyguard.isDeviceLocked } returns false
        every { keyguard.isKeyguardLocked } returns true
        assertThat(launcher.submit(action)).isInstanceOf(AndroidToolResult.Failure::class.java)
        every { keyguard.isKeyguardLocked } returns false
        every { power.isInteractive } returns false
        assertThat(launcher.submit(action)).isInstanceOf(AndroidToolResult.Failure::class.java)
        verify(exactly = 0) { context.startActivity(any()) }
    }

    @Test
    fun `missing activity and system refusal return failures`() {
        every { manager.resolveActivity(any<Intent>(), any<Int>()) } returns null
        assertThat((launcher.submit(action) as AndroidToolResult.Failure).reason).isEqualTo(AndroidToolResult.Reason.NOT_AVAILABLE)
        every { manager.resolveActivity(any<Intent>(), any<Int>()) } returns ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                packageName = action.packageName
                name = "MapsActivity"
                applicationInfo = ApplicationInfo().apply { packageName = action.packageName }
            }
        }
        every { context.startActivity(any()) } throws SecurityException("private details")
        val denied = launcher.submit(action) as AndroidToolResult.Failure
        assertThat(denied.reason).isEqualTo(AndroidToolResult.Reason.NOT_AUTHORIZED)
        assertThat(denied.message).doesNotContain("private details")
    }

    @Suppress("DEPRECATION")
    @Test
    fun `disabled suspended and missing apps are unavailable`() {
        val info = ApplicationInfo().apply { enabled = true }
        every { manager.getApplicationInfo(action.packageName, 0) } returns info
        assertThat(launcher.isAppAvailable(action.packageName)).isTrue()
        info.enabled = false
        assertThat(launcher.isAppAvailable(action.packageName)).isFalse()
        info.enabled = true
        info.flags = ApplicationInfo.FLAG_SUSPENDED
        assertThat(launcher.isAppAvailable(action.packageName)).isFalse()
        every { manager.getApplicationInfo(action.packageName, 0) } throws PackageManager.NameNotFoundException()
        assertThat(launcher.isAppAvailable(action.packageName)).isFalse()
    }
}
