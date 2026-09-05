/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.appupdater.test

import io.element.android.libraries.appupdater.api.AppUpdateState
import io.element.android.libraries.appupdater.api.AppUpdater
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class FakeAppUpdater(
    initialState: AppUpdateState = AppUpdateState.UpToDate,
) : AppUpdater {
    private val mutableState = MutableStateFlow(initialState)

    override val state: StateFlow<AppUpdateState> = mutableState.asStateFlow()

    var backgroundCheckCount = 0
        private set
    var checkCount = 0
        private set
    var downloadCount = 0
        private set
    var installCount = 0
        private set

    fun emit(value: AppUpdateState) {
        mutableState.value = value
    }

    override fun checkInBackground() {
        backgroundCheckCount++
    }

    override suspend fun checkForUpdate() {
        checkCount++
    }

    override suspend fun downloadUpdate() {
        downloadCount++
    }

    override fun installDownloadedUpdate() {
        installCount++
    }
}
