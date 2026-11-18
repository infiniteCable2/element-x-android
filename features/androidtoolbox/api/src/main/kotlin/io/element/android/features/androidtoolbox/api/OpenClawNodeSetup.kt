/*
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.androidtoolbox.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.util.Base64

/** Ephemeral native node-only bootstrap credential. Never put this object in saved UI state or logs. */
class OpenClawNodeSetup private constructor(val bootstrapToken: String) {
    companion object {
        fun parse(code: String): Result<OpenClawNodeSetup> = runCatching {
            require(code.length <= 16_384)
            val trimmed = code.trim()
            val encoded = if (trimmed.startsWith("oc-pair://", ignoreCase = true)) trimmed.substring(10) else trimmed
            val bytes = Base64.getUrlDecoder().decode(encoded)
            val value = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as? JsonObject ?: error("Invalid setup code.")
            require(value.keys.all { it in setOf("url", "bootstrapToken") } && value.text("url") != null)
            // The URL is native setup metadata. Matrix remains the only transport; never open it.
            OpenClawNodeSetup(checkNotNull(value.text("bootstrapToken")))
        }.recoverCatching { throw IllegalArgumentException("A native node-only setup code is required.") }
    }
}
