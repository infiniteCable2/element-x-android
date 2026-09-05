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

/**
 * Restricts every Matrix client to the configured homeserver host.
 *
 * Only the SHA-256 digest of the normalized hostname is kept in source control. The complete URL is
 * supplied at build time through `ELEMENT_X_LOCKED_HOMESERVER_URL` or `lockedHomeserverUrl` in the
 * ignored local.properties file. Ports and path prefixes are intentionally not part of the digest.
 */
object LockedHomeserverPolicy {
    private const val OBFUSCATION_KEY = 0x5A
    private val expectedHostDigest = decodeHex(
        "67f909705f666f52ce638ba32affe2d275e11f8ef6f18db7fa1c6d6961b29d20"
    )

    val configuredHomeserverUrl: String
        get() = BuildConfig.LOCKED_HOMESERVER_URL.trim().ifEmpty(::decodeFallbackUrl)

    fun requireConfiguredHomeserverUrl(): String {
        return configuredHomeserverUrl.also(::requireAllowed)
    }

    fun isAllowed(urlOrServerName: String): Boolean {
        val normalizedHost = normalizedHttpsHost(urlOrServerName) ?: return false
        val actualDigest = MessageDigest.getInstance("SHA-256")
            .digest(normalizedHost.toByteArray(Charsets.UTF_8))
        return MessageDigest.isEqual(expectedHostDigest, actualDigest)
    }

    fun requireAllowed(urlOrServerName: String) {
        require(isAllowed(urlOrServerName)) { "Homeserver is not allowed by this application" }
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

    /** A build-time URL is preferred; this fallback only keeps the address out of plain-text source. */
    private fun decodeFallbackUrl(): String {
        val encoded = intArrayOf(
            50,
            46,
            46,
            42,
            41,
            96,
            117,
            117,
            63,
            52,
            48,
            53,
            35,
            46,
            50,
            63,
            61,
            59,
            55,
            63,
            116,
            62,
            62,
            52,
            41,
            41,
            116,
            62,
            63,
        )
        return encoded.map { value -> (value xor OBFUSCATION_KEY).toByte() }.toByteArray().toString(Charsets.UTF_8)
    }
}
