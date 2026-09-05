/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.appupdater.api

import kotlinx.coroutines.flow.StateFlow

interface AppUpdater {
    val state: StateFlow<AppUpdateState>

    fun checkInBackground()

    suspend fun checkForUpdate()

    suspend fun downloadUpdate()

    fun installDownloadedUpdate()
}

sealed interface AppUpdateState {
    data object Idle : AppUpdateState
    data object Checking : AppUpdateState
    data object UpToDate : AppUpdateState
    data class Available(val versionName: String) : AppUpdateState
    data class Downloading(val versionName: String) : AppUpdateState
    data class ReadyToInstall(val versionName: String) : AppUpdateState
    data object Failed : AppUpdateState
}
