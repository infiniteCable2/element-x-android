/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.appconfig

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LockedHomeserverPolicyTest {
    @Test
    fun `configured homeserver is allowed`() {
        assertThat(LockedHomeserverPolicy.isAllowed(LockedHomeserverPolicy.configuredHomeserverUrl)).isTrue()
    }

    @Test
    fun `port and path do not affect the host lock`() {
        val host = LockedHomeserverPolicy.normalizedHttpsHost(LockedHomeserverPolicy.configuredHomeserverUrl)
        assertThat(LockedHomeserverPolicy.isAllowed("https://$host:8448/matrix/client")).isTrue()
    }

    @Test
    fun `missing scheme is treated as https`() {
        val host = LockedHomeserverPolicy.normalizedHttpsHost(LockedHomeserverPolicy.configuredHomeserverUrl)
        assertThat(LockedHomeserverPolicy.isAllowed(host.orEmpty())).isTrue()
    }

    @Test
    fun `http is rejected`() {
        val host = LockedHomeserverPolicy.normalizedHttpsHost(LockedHomeserverPolicy.configuredHomeserverUrl)
        assertThat(LockedHomeserverPolicy.isAllowed("http://$host")).isFalse()
    }

    @Test
    fun `subdomains and suffix attacks are rejected`() {
        val host = LockedHomeserverPolicy.normalizedHttpsHost(LockedHomeserverPolicy.configuredHomeserverUrl)
        assertThat(LockedHomeserverPolicy.isAllowed("https://sub.$host")).isFalse()
        assertThat(LockedHomeserverPolicy.isAllowed("https://$host.example.org")).isFalse()
    }

    @Test
    fun `userinfo query and fragment are rejected`() {
        val host = LockedHomeserverPolicy.normalizedHttpsHost(LockedHomeserverPolicy.configuredHomeserverUrl)
        assertThat(LockedHomeserverPolicy.isAllowed("https://user@$host")).isFalse()
        assertThat(LockedHomeserverPolicy.isAllowed("https://$host?server=other.example")).isFalse()
        assertThat(LockedHomeserverPolicy.isAllowed("https://$host#other.example")).isFalse()
    }
}
