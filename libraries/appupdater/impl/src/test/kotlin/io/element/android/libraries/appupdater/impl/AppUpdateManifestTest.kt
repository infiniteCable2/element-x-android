/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.appupdater.impl

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class AppUpdateManifestTest {
    @Test
    fun `resolve returns null for an older or equal version`() {
        assertThat(aManifest(versionCode = 42).resolve(42, A_MANIFEST_URL)).isNull()
        assertThat(aManifest(versionCode = 41).resolve(42, A_MANIFEST_URL)).isNull()
    }

    @Test
    fun `resolve accepts a newer APK in the manifest directory`() {
        val result = aManifest(versionCode = 43).resolve(42, A_MANIFEST_URL)

        assertThat(result).isEqualTo(
            ResolvedAppUpdate(
                versionCode = 43,
                versionName = "4.3",
                apkUrl = "https://example.org/release/infinitecable2-matrix.apk",
                sha256 = A_SHA_256,
            )
        )
    }

    @Test
    fun `resolve accepts an APK beside the latest GitHub release manifest`() {
        val manifestUrl = "https://github.com/infiniteCable2/element-x-android/releases/latest/download/update.json"

        val result = aManifest(versionCode = 43).resolve(42, manifestUrl)

        assertThat(result?.apkUrl).isEqualTo(
            "https://github.com/infiniteCable2/element-x-android/releases/latest/download/infinitecable2-matrix.apk"
        )
    }

    @Test
    fun `resolve rejects paths and invalid checksums`() {
        assertThrows(IllegalArgumentException::class.java) {
            aManifest(apk = "../outside.apk").resolve(42, A_MANIFEST_URL)
        }
        assertThrows(IllegalArgumentException::class.java) {
            aManifest(sha256 = "not-a-checksum").resolve(42, A_MANIFEST_URL)
        }
    }
}

private const val A_MANIFEST_URL = "https://example.org/release/update.json"
private const val A_SHA_256 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

private fun aManifest(
    versionCode: Long = 43,
    apk: String = "infinitecable2-matrix.apk",
    sha256: String = A_SHA_256,
) = AppUpdateManifest(
    versionCode = versionCode,
    versionName = "4.3",
    apk = apk,
    sha256 = sha256,
)
