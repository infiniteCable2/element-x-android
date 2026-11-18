/*
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.androidtoolbox.api

/** Transport-owned absolute deadline and a conservative monotone server-clock upper bound. Never use phone wall time. */
class OpenClawInvokeDeadline(private val expiresAtMs: Long, private val serverUpperBoundMs: () -> Long) {
    init {
        require(expiresAtMs in 1..MAX_SAFE_INTEGER)
    }

    fun remainingMs(): Long = runCatching {
        val now = serverUpperBoundMs()
        check(now in 0..MAX_SAFE_INTEGER)
        (expiresAtMs - now).coerceAtLeast(0)
    }.getOrDefault(0)
}

internal const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L
