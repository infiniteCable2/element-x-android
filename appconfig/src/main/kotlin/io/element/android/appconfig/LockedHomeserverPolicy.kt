/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.appconfig

import java.net.IDN
import java.net.URI
import java.security.MessageDigest
import java.util.Locale

interface HomeserverConnectionPolicy {
    fun isAllowed(urlOrServerName: String): Boolean

    fun requireAllowed(urlOrServerName: String) {
        require(isAllowed(urlOrServerName)) { "Homeserver is not allowed by this application" }
    }
}

/**
 * Restricts every Matrix client to the configured homeserver host.
 *
 * Only the SHA-256 digest of the normalized hostname is present in production code. Ports and path
 * prefixes are intentionally not part of the digest, allowing the user to enter the complete URL
 * without the app bundling or displaying it.
 */
object LockedHomeserverPolicy : HomeserverConnectionPolicy {
    private val expectedHostDigest = decodeHex(
        "67f909705f666f52ce638ba32affe2d275e11f8ef6f18db7fa1c6d6961b29d20"
    )

    override fun isAllowed(urlOrServerName: String): Boolean {
        return isAllowed(urlOrServerName, expectedHostDigest)
    }

    internal fun isAllowed(urlOrServerName: String, expectedDigest: ByteArray): Boolean {
        val normalizedHost = normalizedHttpsHost(urlOrServerName) ?: return false
        val actualDigest = MessageDigest.getInstance("SHA-256")
            .digest(normalizedHost.toByteArray(Charsets.UTF_8))
        return MessageDigest.isEqual(expectedDigest, actualDigest)
    }

    internal fun normalizedHttpsHost(urlOrServerName: String): String? {
        val candidate = urlOrServerName.trim()
        if (candidate.isEmpty()) return null

        val uri = runCatching {
            URI(if (candidate.contains("://")) candidate else "https://$candidate")
        }.getOrNull() ?: return null

        if (!uri.scheme.equals("https", ignoreCase = true)) return null
        if (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) return null

        val host = uri.host?.removeSuffix(".")?.takeIf(String::isNotEmpty) ?: return null
        return runCatching {
            IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES).lowercase(Locale.ROOT)
        }.getOrNull()
    }

    private fun decodeHex(value: String): ByteArray {
        require(value.length % 2 == 0)
        return ByteArray(value.length / 2) { index ->
            value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }
}
