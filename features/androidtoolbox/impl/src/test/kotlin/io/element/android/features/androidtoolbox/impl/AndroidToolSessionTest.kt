/*
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.androidtoolbox.impl

import com.google.common.truth.Truth.assertThat
import io.element.android.features.androidtoolbox.api.AndroidAppLaunch
import io.element.android.features.androidtoolbox.api.AndroidToolDefinition
import io.element.android.features.androidtoolbox.api.AndroidToolLauncher
import io.element.android.features.androidtoolbox.api.AndroidToolResult
import io.element.android.features.androidtoolbox.api.AndroidToolSession
import io.element.android.features.androidtoolbox.api.AndroidToolbox
import io.element.android.features.androidtoolbox.test.RecordingAndroidToolLauncher
import io.element.android.tests.testutils.testCoroutineDispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test

class AndroidToolSessionTest {
    private val params = JsonObject(mapOf("query" to JsonPrimitive("Sample park")))

    @Test
    fun `concurrent duplicate operations submit once and changed requests are rejected`() = runTest {
        val launcher = RecordingAndroidToolLauncher()
        val toolbox = DefaultAndroidToolbox(setOf(GoogleMapsToolModule()), testCoroutineDispatchers())
        val session = AndroidToolSession(toolbox, launcher) { setOf("maps") }
        val results = List(4) { async { session.execute("operation", "maps.search", params) } }.awaitAll()
        assertThat(results.distinct()).hasSize(1)
        assertThat(results.first()).isInstanceOf(AndroidToolResult.Success::class.java)
        assertThat(launcher.submissions).hasSize(1)
        assertThat(session.execute("operation", "maps.navigate", params)).isInstanceOf(AndroidToolResult.Failure::class.java)
        assertThat(launcher.submissions).hasSize(1)
    }

    @Test
    fun `new operations see revoked grants and fail closed on hidden surfaces`() = runTest {
        val launcher = RecordingAndroidToolLauncher()
        val toolbox = DefaultAndroidToolbox(setOf(GoogleMapsToolModule()), testCoroutineDispatchers())
        var grants = setOf("maps")
        val session = AndroidToolSession(toolbox, launcher) { grants }
        assertThat(session.catalog()).hasSize(3)
        session.execute("first", "maps.search", params)
        grants = emptySet()
        assertThat(session.catalog()).isEmpty()
        assertThat(session.execute("revoked", "maps.search", params)).isInstanceOf(AndroidToolResult.Failure::class.java)
        grants = setOf("maps")
        launcher.interactive = false
        assertThat(session.execute("hidden", "maps.search", params)).isInstanceOf(AndroidToolResult.Failure::class.java)
        assertThat(launcher.submissions).hasSize(1)
    }

    @Test
    fun `operation limit retains old reservations instead of permitting replay`() = runTest {
        val launcher = RecordingAndroidToolLauncher()
        val toolbox = DefaultAndroidToolbox(setOf(GoogleMapsToolModule()), testCoroutineDispatchers())
        val session = AndroidToolSession(toolbox, launcher) { setOf("maps") }
        repeat(128) { session.execute(it.toString(), "maps.search", params) }
        assertThat(session.execute("overflow", "maps.search", params)).isInstanceOf(AndroidToolResult.Failure::class.java)
        session.execute("0", "maps.search", params)
        assertThat(launcher.submissions).hasSize(128)
    }

    @Test
    fun `cancellation after a handoff cannot cause a second submission`() = runTest {
        val launcher = RecordingAndroidToolLauncher()
        val handedOff = CompletableDeferred<Unit>()
        val toolbox = object : AndroidToolbox {
            override fun catalog(launcher: AndroidToolLauncher, grants: Set<String>): List<AndroidToolDefinition> = emptyList()
            override suspend fun execute(
                command: String, params: JsonObject, launcher: AndroidToolLauncher, grants: suspend () -> Set<String>,
            ): AndroidToolResult {
                launcher.submit(AndroidAppLaunch("com.google.android.apps.maps", "https://www.google.com/maps/"))
                handedOff.complete(Unit)
                awaitCancellation()
            }
        }
        val session = AndroidToolSession(toolbox, launcher) { setOf("maps") }
        val pending = async { session.execute("operation", "maps.search", params) }
        handedOff.await()
        pending.cancelAndJoin()
        assertThat(session.execute("operation", "maps.search", params)).isInstanceOf(AndroidToolResult.Failure::class.java)
        assertThat(launcher.submissions).hasSize(1)
    }
}
