/*
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.androidtoolbox.impl

import com.google.common.truth.Truth.assertThat
import io.element.android.features.androidtoolbox.api.AndroidToolResult
import io.element.android.features.androidtoolbox.test.RecordingAndroidToolLauncher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Test
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

class GoogleMapsToolModuleTest {
    private val module = GoogleMapsToolModule()
    private val launcher = RecordingAndroidToolLauncher()

    @Test
    fun `search encodes user content as one parameter in the fixed Maps target`() = runTest {
        val text = "Café & dir_action=navigate #東京"
        module.execute("maps.search", params("""{"query":"$text","placeId":"sample_place"}"""), launcher)
        val action = launcher.submissions.single()
        val uri = URI(action.uri)
        assertThat(action.packageName).isEqualTo("com.google.android.apps.maps")
        assertThat(uri.scheme).isEqualTo("https")
        assertThat(uri.host).isEqualTo("www.google.com")
        assertThat(uri.path).isEqualTo("/maps/search/")
        assertThat(uri.fragment).isNull()
        assertThat(uri.parameters()).containsExactly("api", "1", "query", text, "query_place_id", "sample_place")
    }

    @Test
    fun `directions preview and navigation request differ explicitly`() = runTest {
        module.execute("maps.directions", params("""{"destination":"Sample square","mode":"transit"}"""), launcher)
        assertThat(URI(launcher.submissions.last().uri).parameters()).containsExactly(
            "api",
            "1",
            "destination",
            "Sample square",
            "travelmode",
            "transit",
        )
        module.execute("maps.navigate", params("""{"destination":"Sample square","placeId":"place","mode":"walking"}"""), launcher)
        assertThat(URI(launcher.submissions.last().uri).parameters()).containsExactly(
            "api",
            "1",
            "destination",
            "Sample square",
            "destination_place_id",
            "place",
            "travelmode",
            "walking",
            "dir_action",
            "navigate",
        )
        module.execute("maps.navigate", params("""{"destination":"Sample square"}"""), launcher)
        assertThat(URI(launcher.submissions.last().uri).parameters()["travelmode"]).isEqualTo("driving")
    }

    @Test
    fun `malformed or unsupported parameters never launch an app`() = runTest {
        val invalid = listOf(
            "maps.search" to "{}",
            "maps.search" to """{"query":true}""",
            "maps.search" to """{"query":" "}""",
            "maps.search" to """{"query":"a","mode":"driving"}""",
            "maps.search" to """{"query":"a","package":"other.app"}""",
            "maps.search" to """{"query":"a","placeId":null}""",
            "maps.search" to """{"query":"a\nb"}""",
            "maps.navigate" to """{"destination":"a","mode":"transit"}""",
            "maps.navigate" to """{"destination":"a","mode":1}""",
            "maps.navigate" to """{"destination":"a","mode":"flying"}""",
            "other.command" to """{"destination":"a"}""",
        )
        for ((command, json) in invalid) {
            assertThat(module.execute(command, params(json), launcher)).isInstanceOf(AndroidToolResult.Failure::class.java)
        }
        assertThat(launcher.submissions).isEmpty()
    }

    @Test
    fun `encoded URL length is bounded and failures are passed through`() = runTest {
        val result = module.execute("maps.search", params("""{"query":"${"é".repeat(500)}"}"""), launcher)
        assertThat((result as AndroidToolResult.Failure).reason).isEqualTo(AndroidToolResult.Reason.INVALID_PARAMS)
        assertThat(launcher.submissions).isEmpty()
        launcher.result = AndroidToolResult.Failure(AndroidToolResult.Reason.NOT_AVAILABLE, "Unavailable")
        assertThat(module.execute("maps.search", params("""{"query":"sample"}"""), launcher)).isEqualTo(launcher.result)
    }

    private fun params(json: String) = Json.parseToJsonElement(json) as JsonObject
    private fun URI.parameters() = rawQuery.split('&').associate {
        val (key, value) = it.split('=', limit = 2)
        key to URLDecoder.decode(value, StandardCharsets.UTF_8.name())
    }
}
