/*
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.androidtoolbox.impl

import com.google.common.truth.Truth.assertThat
import io.element.android.features.androidtoolbox.api.AndroidToolCapabilities
import io.element.android.features.androidtoolbox.api.AndroidToolResult
import io.element.android.features.androidtoolbox.test.RecordingAndroidToolLauncher
import io.element.android.tests.testutils.testCoroutineDispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Test

class DefaultAndroidToolboxTest {
    private val launcher = RecordingAndroidToolLauncher()
    private val grants = setOf(AndroidToolCapabilities.MAPS)
    private val query = buildJsonObject { put("query", JsonPrimitive("Sample museum")) }

    @Test
    fun `catalog advertises only granted installed capabilities`() = runTest {
        val toolbox = DefaultAndroidToolbox(setOf(GoogleMapsToolModule()), testCoroutineDispatchers())
        assertThat(toolbox.catalog(launcher, emptySet())).isEmpty()
        assertThat(toolbox.catalog(launcher, grants).map { it.command }).containsExactly("maps.search", "maps.directions", "maps.navigate")
        launcher.availablePackages.clear()
        assertThat(toolbox.catalog(launcher, grants)).isEmpty()
    }

    @Test
    fun `every invocation rechecks grants visibility and installation`() = runTest {
        val toolbox = DefaultAndroidToolbox(setOf(GoogleMapsToolModule()), testCoroutineDispatchers())
        assertThat(toolbox.execute("maps.search", query, launcher, emptySet()).reason()).isEqualTo(AndroidToolResult.Reason.NOT_AUTHORIZED)
        launcher.interactive = false
        assertThat(toolbox.execute("maps.search", query, launcher, grants).reason()).isEqualTo(AndroidToolResult.Reason.NOT_AUTHORIZED)
        launcher.interactive = true
        launcher.availablePackages.clear()
        assertThat(toolbox.execute("maps.search", query, launcher, grants).reason()).isEqualTo(AndroidToolResult.Reason.NOT_AVAILABLE)
        launcher.availablePackages.add("com.google.android.apps.maps")
        assertThat(toolbox.execute("unregistered", query, launcher, grants).reason()).isEqualTo(AndroidToolResult.Reason.UNKNOWN_COMMAND)
        assertThat(launcher.submissions).isEmpty()
        assertThat(toolbox.execute("maps.search", query, launcher, grants)).isInstanceOf(AndroidToolResult.Success::class.java)
        assertThat(launcher.submissions).hasSize(1)
    }

    @Test(expected = IllegalStateException::class)
    fun `conflicting modules fail instead of silently changing the handler`() = runTest {
        DefaultAndroidToolbox(setOf(GoogleMapsToolModule(), GoogleMapsToolModule()), testCoroutineDispatchers())
    }

    private fun AndroidToolResult.reason() = (this as AndroidToolResult.Failure).reason
}
