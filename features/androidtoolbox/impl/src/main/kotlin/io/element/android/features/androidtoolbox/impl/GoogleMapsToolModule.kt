/*
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.androidtoolbox.impl

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import io.element.android.features.androidtoolbox.api.AndroidAppLaunch
import io.element.android.features.androidtoolbox.api.AndroidToolCapabilities
import io.element.android.features.androidtoolbox.api.AndroidToolDefinition
import io.element.android.features.androidtoolbox.api.AndroidToolLauncher
import io.element.android.features.androidtoolbox.api.AndroidToolModule
import io.element.android.features.androidtoolbox.api.AndroidToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

@ContributesIntoSet(AppScope::class)
class GoogleMapsToolModule : AndroidToolModule {
    override val capability = AndroidToolCapabilities.MAPS
    override val definitions = listOf(
        AndroidToolDefinition(
            "maps.search",
            "Open Google Maps search results on this phone. Search results are displayed, not returned to the assistant.",
            parameters("query", emptyList()),
        ),
        AndroidToolDefinition(
            "maps.directions",
            "Show a route preview in Google Maps to the requested destination.",
            parameters("destination", listOf("driving", "walking", "bicycling", "transit")),
        ),
        AndroidToolDefinition(
            "maps.navigate",
            "Request Google Maps navigation to an unambiguous destination confirmed by the user. Use placeId when known. " +
                "Maps may show a preview if location or navigation is unavailable; submission is not navigation confirmation.",
            parameters("destination", listOf("driving", "walking", "bicycling")),
        ),
    )

    override fun isAvailable(launcher: AndroidToolLauncher): Boolean = launcher.isAppAvailable(MAPS_PACKAGE)

    override suspend fun execute(command: String, params: JsonObject, launcher: AndroidToolLauncher): AndroidToolResult {
        val search = command == "maps.search"
        if (command !in definitions.map { it.command }) return invalid("Unsupported Maps command.")
        val field = if (search) "query" else "destination"
        val allowed = if (search) setOf("query", "placeId") else setOf("destination", "placeId", "mode")
        if (params.keys.any { it !in allowed }) return invalid("Unexpected Maps parameters.")
        val value = params.text(field) ?: return invalid("A non-empty $field is required.")
        val placeId = params.text("placeId")
        if ("placeId" in params && placeId == null) return invalid("placeId must be a non-empty string.")
        val mode = if ("mode" in params) params.text("mode") else "driving"
        val modes = if (command == "maps.directions") setOf("driving", "walking", "bicycling", "transit") else setOf("driving", "walking", "bicycling")
        if (!search && mode !in modes) return invalid("Unsupported Maps travel mode.")
        val query = linkedMapOf("api" to "1", field to value)
        if (placeId != null) query[if (search) "query_place_id" else "destination_place_id"] = placeId
        if (!search) query["travelmode"] = checkNotNull(mode)
        if (command == "maps.navigate") query["dir_action"] = "navigate"
        val path = if (search) "search" else "dir"
        val uri = "https://www.google.com/maps/$path/?" + query.entries.joinToString("&") { (key, text) -> "$key=${encode(text)}" }
        if (uri.length > 2_048) return invalid("The Maps request exceeds the supported URL length.")
        return launcher.submit(AndroidAppLaunch(MAPS_PACKAGE, uri))
    }
}

private const val MAPS_PACKAGE = "com.google.android.apps.maps"
private fun invalid(message: String) = AndroidToolResult.Failure(AndroidToolResult.Reason.INVALID_PARAMS, message)
private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")
private fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
    ?.takeIf { it.isNotEmpty() && it.length <= 2_048 && it.none { char -> char.code < 32 || char.code == 127 } }

private fun parameters(field: String, modes: List<String>) = buildJsonObject {
    put("type", "object")
    put("additionalProperties", false)
    put("required", buildJsonArray { add(JsonPrimitive(field)) })
    put("properties", buildJsonObject {
        put(field, buildJsonObject {
            put("type", "string")
            put("minLength", 1)
            put("maxLength", 2_048)
        })
        put("placeId", buildJsonObject {
            put("type", "string")
            put("minLength", 1)
            put("maxLength", 2_048)
        })
        if (modes.isNotEmpty()) {
            put("mode", buildJsonObject {
                put("type", "string")
                put("enum", buildJsonArray { modes.forEach { add(JsonPrimitive(it)) } })
                put("default", "driving")
            })
        }
    })
}
