/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.impl

import io.element.android.appconfig.HomeserverConnectionPolicy
import java.net.URI

internal const val TEST_HOMESERVER_URL = "https://allowed.example"

internal object TestHomeserverConnectionPolicy : HomeserverConnectionPolicy {
    override fun isAllowed(urlOrServerName: String): Boolean {
        val candidate = if (urlOrServerName.contains("://")) urlOrServerName else "https://$urlOrServerName"
        return runCatching { URI(candidate).host == "allowed.example" }.getOrDefault(false)
    }
}
