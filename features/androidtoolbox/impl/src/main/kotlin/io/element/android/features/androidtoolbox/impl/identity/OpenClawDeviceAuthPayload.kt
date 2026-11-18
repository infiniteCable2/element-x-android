/*
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.androidtoolbox.impl.identity

import io.element.android.features.androidtoolbox.api.OpenClawDeviceAuthClaim

/** Wire layout owned by OpenClaw's DeviceAuthPayload.buildV3 and gateway-client/src/device-auth.ts. */
internal fun OpenClawDeviceAuthClaim.payloadV3(deviceId: String): String = listOf(
    "v3",
    deviceId,
    clientId,
    clientMode,
    role,
    scopes.joinToString(","),
    signedAtMs.toString(),
    token.orEmpty(),
    nonce,
    platform.normalizedMetadata(),
    deviceFamily.normalizedMetadata(),
).joinToString("|")

/** Preserve scope order, token bytes and non-ASCII case; only the two native metadata fields are normalised. */
private fun String?.normalizedMetadata(): String = orEmpty().trim().map { character ->
    if (character in 'A'..'Z') (character.code + 32).toChar() else character
}.joinToString("")
