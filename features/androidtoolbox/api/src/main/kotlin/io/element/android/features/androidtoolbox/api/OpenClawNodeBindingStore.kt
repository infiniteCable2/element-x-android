/*
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.androidtoolbox.api

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/** Device-local, protected selection. Native node credentials are stored separately. */
data class OpenClawNodeBinding(val sessionId: String, val roomId: String, val peerUserId: String, val peerDeviceId: String) {
    val gatewayId: String get() = JsonArray(listOf(sessionId, roomId, peerUserId, peerDeviceId).map(::JsonPrimitive)).toString()
}

interface OpenClawNodeBindingStore {
    suspend fun load(): Result<OpenClawNodeBinding?>
    suspend fun save(binding: OpenClawNodeBinding): Result<Unit>
}

/** Room settings perform an explicit, ephemeral node-only setup. No secrets belong in retained UI state. */
interface AssistantNodePairing {
    suspend fun pair(sessionId: String, roomId: String, peerDeviceId: String, setupCode: String): Outcome

    enum class Outcome { Paired, InvalidSetup, UnverifiedChat, Failed }
}
