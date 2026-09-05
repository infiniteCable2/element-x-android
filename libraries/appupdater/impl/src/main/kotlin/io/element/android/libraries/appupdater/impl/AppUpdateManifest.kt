/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.appupdater.impl

import kotlinx.serialization.Serializable
import java.net.URI

@Serializable
internal data class AppUpdateManifest(
    val versionCode: Long,
    val versionName: String,
    val apk: String,
    val sha256: String,
)

internal data class ResolvedAppUpdate(
    val versionCode: Long,
    val versionName: String,
    val apkUrl: String,
    val sha256: String,
)

internal fun AppUpdateManifest.resolve(
    currentVersionCode: Long,
    manifestUrl: String,
): ResolvedAppUpdate? {
    if (versionCode <= currentVersionCode) return null

    require(versionName.isNotBlank()) { "Update version name is missing" }
    require(APK_FILE_NAME.matches(apk)) { "Update APK must be a file in the release directory" }
    require(SHA_256.matches(sha256)) { "Update checksum is invalid" }

    val manifestUri = URI(manifestUrl)
    require(manifestUri.scheme.equals("https", ignoreCase = true) && manifestUri.host != null) {
        "Update manifest must use HTTPS"
    }
    val apkUri = manifestUri.resolve(apk)
    require(apkUri.scheme.equals("https", ignoreCase = true)) { "Update APK must use HTTPS" }
    require(apkUri.host.equals(manifestUri.host, ignoreCase = true)) { "Update APK must use the manifest host" }
    require(apkUri.path.substringBeforeLast('/') == manifestUri.path.substringBeforeLast('/')) {
        "Update APK must be in the release directory"
    }

    return ResolvedAppUpdate(
        versionCode = versionCode,
        versionName = versionName,
        apkUrl = apkUri.toString(),
        sha256 = sha256.lowercase(),
    )
}

private val APK_FILE_NAME = Regex("[A-Za-z0-9._-]+\\.apk")
private val SHA_256 = Regex("[A-Fa-f0-9]{64}")
