/*
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.androidtoolbox.impl.identity

import com.google.crypto.tink.subtle.Ed25519Sign
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import io.element.android.features.androidtoolbox.api.OpenClawDeviceAuthClaim
import io.element.android.features.androidtoolbox.api.OpenClawDeviceIdentity
import io.element.android.features.androidtoolbox.api.OpenClawDeviceIdentityStore
import io.element.android.features.androidtoolbox.api.OpenClawDeviceProof
import io.element.android.libraries.core.coroutine.CoroutineDispatchers
import io.element.android.libraries.core.extensions.runCatchingExceptions
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.MessageDigest
import java.util.Base64

@ContributesBinding(AppScope::class)
class DefaultOpenClawDeviceIdentityStore(
    private val storage: AndroidNodeCredentialStorage,
    private val dispatchers: CoroutineDispatchers,
) : OpenClawDeviceIdentityStore {
    override suspend fun loadOrCreate(): Result<OpenClawDeviceIdentity> = withContext(dispatchers.io) {
        runCatchingExceptions { withKeyPair { pair -> pair.publicIdentity() } }
    }

    override suspend fun signV3(claim: OpenClawDeviceAuthClaim): Result<OpenClawDeviceProof> = withContext(dispatchers.io) {
        runCatchingExceptions {
            withKeyPair { pair ->
                val identity = pair.publicIdentity()
                val seed = pair.privateKey
                try {
                    val signature = Ed25519Sign(seed).sign(claim.payloadV3(identity.deviceId).toByteArray(Charsets.UTF_8))
                    OpenClawDeviceProof(identity.deviceId, identity.publicKey, signature.base64Url(), claim.signedAtMs, claim.nonce)
                } finally {
                    seed.fill(0)
                }
            }
        }
    }

    private suspend fun <T> withKeyPair(block: (Ed25519Sign.KeyPair) -> T): T {
        val record = checkNotNull(storage.update(IDENTITY_ENTRY) { existing ->
            existing ?: run {
                val seed = Ed25519Sign.KeyPair.newKeyPair().privateKey
                try {
                    buildJsonObject {
                        put("version", 1)
                        put("seed", Base64.getEncoder().encodeToString(seed))
                    }.toString()
                } finally {
                    seed.fill(0)
                }
            }
        })
        val seed = try {
            val objectValue = Json.parseToJsonElement(record) as JsonObject
            check(objectValue["version"] == JsonPrimitive(1))
            val encoded = (objectValue["seed"] as JsonPrimitive).takeIf { it.isString }?.content ?: error("Missing seed")
            Base64.getDecoder().decode(encoded).also { check(it.size == 32) }
        } catch (_: Exception) {
            // Parsing diagnostics can contain the private seed. Return only a fixed error.
            error("The stored OpenClaw identity is invalid; it was not replaced.")
        }
        try {
            return block(Ed25519Sign.KeyPair.newKeyPairFromSeed(seed))
        } finally {
            seed.fill(0)
        }
    }
}

internal const val IDENTITY_ENTRY = "identity.v1"
internal fun ByteArray.base64Url(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(this)
private fun Ed25519Sign.KeyPair.publicIdentity(): OpenClawDeviceIdentity {
    val public = publicKey
    val id = MessageDigest.getInstance("SHA-256").digest(public).joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
    return OpenClawDeviceIdentity(id, public.base64Url())
}
