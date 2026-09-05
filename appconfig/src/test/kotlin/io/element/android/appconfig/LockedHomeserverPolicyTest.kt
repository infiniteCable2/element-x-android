/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.appconfig

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.security.MessageDigest

class LockedHomeserverPolicyTest {
    private val expectedDigest = MessageDigest.getInstance("SHA-256")
        .digest(ALLOWED_HOST.toByteArray(Charsets.UTF_8))

    @Test
    fun `configured homeserver is allowed`() {
        assertThat(isAllowed("https://$ALLOWED_HOST")).isTrue()
    }

    @Test
    fun `port and path do not affect the host lock`() {
        assertThat(isAllowed("https://$ALLOWED_HOST:8448/matrix/client")).isTrue()
    }

    @Test
    fun `missing scheme is treated as https`() {
        assertThat(isAllowed(ALLOWED_HOST)).isTrue()
    }

    @Test
    fun `http is rejected`() {
        assertThat(isAllowed("http://$ALLOWED_HOST")).isFalse()
    }

    @Test
    fun `subdomains and suffix attacks are rejected`() {
        assertThat(isAllowed("https://sub.$ALLOWED_HOST")).isFalse()
        assertThat(isAllowed("https://$ALLOWED_HOST.example.org")).isFalse()
    }

    @Test
    fun `userinfo query and fragment are rejected`() {
        assertThat(isAllowed("https://user@$ALLOWED_HOST")).isFalse()
        assertThat(isAllowed("https://$ALLOWED_HOST?server=other.example")).isFalse()
        assertThat(isAllowed("https://$ALLOWED_HOST#other.example")).isFalse()
    }

    private fun isAllowed(value: String): Boolean = LockedHomeserverPolicy.isAllowed(value, expectedDigest)

    companion object {
        private const val ALLOWED_HOST = "allowed.example"
    }
}
