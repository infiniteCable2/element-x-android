/*
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.androidtoolbox.api

/** Public native identity. This is independent of Matrix user/device IDs and RTC membership. */
data class OpenClawDeviceIdentity(val deviceId: String, val publicKey: String)

/** Native V3 connect inputs. Tokens and complete signed payloads must never be logged. */
class OpenClawDeviceAuthClaim(
    val clientId: String,
    val clientMode: String,
    val role: String,
    val scopes: List<String>,
    val signedAtMs: Long,
    val token: String?,
    val nonce: String,
    val platform: String?,
    val deviceFamily: String?,
)

/** Fields of the native connect.device proof; the enclosing transport is deliberately not defined here. */
data class OpenClawDeviceProof(val id: String, val publicKey: String, val signature: String, val signedAt: Long, val nonce: String)

interface OpenClawDeviceIdentityStore {
    /** Create once and return only after durable protected storage, or fail without replacing an unreadable identity. */
    suspend fun loadOrCreate(): Result<OpenClawDeviceIdentity>

    /** Signing proves key possession, not pairing, permission or current authority to execute a tool. */
    suspend fun signV3(claim: OpenClawDeviceAuthClaim): Result<OpenClawDeviceProof>
}
