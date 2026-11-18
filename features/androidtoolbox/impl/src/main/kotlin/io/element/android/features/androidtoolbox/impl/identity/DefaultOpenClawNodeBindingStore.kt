/*
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.androidtoolbox.impl.identity

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import io.element.android.features.androidtoolbox.api.OpenClawNodeBinding
import io.element.android.features.androidtoolbox.api.OpenClawNodeBindingStore
import io.element.android.libraries.core.extensions.runCatchingExceptions
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@ContributesBinding(AppScope::class)
class DefaultOpenClawNodeBindingStore(private val storage: AndroidNodeCredentialStorage) : OpenClawNodeBindingStore {
    override suspend fun load(): Result<OpenClawNodeBinding?> = runCatchingExceptions {
        val saved = storage.read(RECORD) ?: return@runCatchingExceptions null
        try {
            val record = Json.parseToJsonElement(saved) as JsonObject
            check(record["version"] == JsonPrimitive(1))
            fun text(key: String) = (record[key] as JsonPrimitive).takeIf { it.isString && it.content.isNotBlank() }!!.content
            OpenClawNodeBinding(text("sessionId"), text("roomId"), text("peerUserId"), text("peerDeviceId"))
        } catch (_: Exception) {
            error("The protected node selection is invalid.")
        }
    }

    override suspend fun save(binding: OpenClawNodeBinding): Result<Unit> = runCatchingExceptions {
        require(listOf(binding.sessionId, binding.roomId, binding.peerUserId, binding.peerDeviceId).all { it.isNotBlank() })
        require(binding.peerDeviceId != "*")
        storage.update(RECORD) {
            buildJsonObject {
                put("version", 1)
                put("sessionId", binding.sessionId)
                put("roomId", binding.roomId)
                put("peerUserId", binding.peerUserId)
                put("peerDeviceId", binding.peerDeviceId)
            }.toString()
        }
        Unit
    }

    private companion object {
        const val RECORD = "node.binding.v1"
    }
}
