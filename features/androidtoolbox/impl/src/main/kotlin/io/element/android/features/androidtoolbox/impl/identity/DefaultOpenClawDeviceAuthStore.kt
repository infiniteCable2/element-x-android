/*
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.androidtoolbox.impl.identity

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import io.element.android.features.androidtoolbox.api.OpenClawDeviceAuthEntry
import io.element.android.features.androidtoolbox.api.OpenClawDeviceAuthStore
import io.element.android.features.androidtoolbox.api.OpenClawDeviceTokenSlot
import io.element.android.libraries.core.coroutine.CoroutineDispatchers
import io.element.android.libraries.core.extensions.runCatchingExceptions
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.security.MessageDigest

@ContributesBinding(AppScope::class)
class DefaultOpenClawDeviceAuthStore(
    private val storage: AndroidNodeCredentialStorage,
    private val dispatchers: CoroutineDispatchers,
) : OpenClawDeviceAuthStore {
    override suspend fun loadEntry(slot: OpenClawDeviceTokenSlot): Result<OpenClawDeviceAuthEntry?> = withContext(dispatchers.io) {
        runCatchingExceptions {
            val normalized = slot.normalized()
            storage.read(normalized.recordKey())?.parseEntry(normalized.role)
        }
    }

    override suspend fun saveToken(
        slot: OpenClawDeviceTokenSlot,
        token: String,
        scopes: List<String>,
        replacesStoredToken: String?,
    ): Result<Boolean> = withContext(dispatchers.io) {
        runCatchingExceptions {
            val normalized = slot.normalized()
            require(token.isNotBlank()) { "An empty device token cannot be stored." }
            var saved = false
            storage.update(normalized.recordKey()) { previous ->
                if (replacesStoredToken != null && previous?.parseEntry(normalized.role)?.token != replacesStoredToken.trim()) {
                    previous
                } else {
                    saved = true
                    buildJsonObject {
                        put("version", 1)
                        put("token", token.trim())
                        put("scopes", JsonArray(scopes.map(String::trim).filter(String::isNotEmpty).distinct().sorted().map(::JsonPrimitive)))
                        put("updatedAtMs", System.currentTimeMillis())
                    }.toString()
                }
            }
            saved
        }
    }

    override suspend fun clearToken(slot: OpenClawDeviceTokenSlot, onlyIfToken: String?): Result<Boolean> = withContext(dispatchers.io) {
        runCatchingExceptions {
            val normalized = slot.normalized()
            var removed = false
            storage.update(normalized.recordKey()) { previous ->
                if (onlyIfToken != null && previous?.parseEntry(normalized.role)?.token != onlyIfToken.trim()) {
                    previous
                } else {
                    removed = previous != null
                    null
                }
            }
            removed
        }
    }
}

private fun OpenClawDeviceTokenSlot.normalized(): OpenClawDeviceTokenSlot = OpenClawDeviceTokenSlot(
    gatewayId = gatewayId.trim(),
    deviceId = deviceId.trim().lowercase(),
    role = role.trim().lowercase(),
).also { require(it.gatewayId.isNotEmpty() && it.deviceId.isNotEmpty() && it.role.isNotEmpty()) }

/** Framed hashing avoids ambiguous dotted slots and keeps gateway identities out of plaintext preferences. */
private fun OpenClawDeviceTokenSlot.recordKey(): String {
    val framed = JsonArray(listOf(gatewayId, deviceId, role).map(::JsonPrimitive)).toString().toByteArray(Charsets.UTF_8)
    return "token." + MessageDigest.getInstance("SHA-256").digest(framed).base64Url()
}

private fun String.parseEntry(role: String): OpenClawDeviceAuthEntry = try {
    val record = Json.parseToJsonElement(this) as JsonObject
    check(record["version"] == JsonPrimitive(1))
    val token = (record["token"] as JsonPrimitive).takeIf { it.isString && it.content.isNotBlank() }?.content ?: error("Missing token")
    val scopes = (record["scopes"] as JsonArray).map { value ->
        (value as JsonPrimitive).takeIf { it.isString }?.content ?: error("Invalid scope")
    }
    val updated = (record["updatedAtMs"] as JsonPrimitive).takeUnless { it.isString }?.longOrNull ?: error("Missing timestamp")
    OpenClawDeviceAuthEntry(token, role, scopes, updated)
} catch (_: Exception) {
    error("The protected device token is invalid; it was not replaced.")
}
