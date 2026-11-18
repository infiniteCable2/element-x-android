/*
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.androidtoolbox.api

/** Gateway ID must come from the authenticated connection owner, not from tool arguments. */
data class OpenClawDeviceTokenSlot(val gatewayId: String, val deviceId: String, val role: String)

/** Secret-bearing class deliberately has no generated toString/copy/component methods. */
class OpenClawDeviceAuthEntry(val token: String, val role: String, val scopes: List<String>, val updatedAtMs: Long)

interface OpenClawDeviceAuthStore {
    suspend fun loadEntry(slot: OpenClawDeviceTokenSlot): Result<OpenClawDeviceAuthEntry?>

    /** False when a fresher token won. Success is returned only after durable protected storage. */
    suspend fun saveToken(
        slot: OpenClawDeviceTokenSlot,
        token: String,
        scopes: List<String> = emptyList(),
        replacesStoredToken: String? = null,
    ): Result<Boolean>

    /** Conditional retirement cannot delete a newer grant. */
    suspend fun clearToken(slot: OpenClawDeviceTokenSlot, onlyIfToken: String? = null): Result<Boolean>
}
