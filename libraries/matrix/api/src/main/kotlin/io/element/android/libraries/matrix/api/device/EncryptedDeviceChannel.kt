/*
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.api.device

import io.element.android.libraries.matrix.api.MatrixClient
import io.element.android.libraries.matrix.api.core.DeviceId
import io.element.android.libraries.matrix.api.core.UserId
import kotlinx.coroutines.flow.Flow

/** Exact remote device, selected by a separately authenticated application binding. */
data class MatrixDevicePeer(val userId: UserId, val deviceId: DeviceId, val requireVerified: Boolean = false)

data class EncryptedDeviceMessage(val eventType: String, val contentJson: String)

/**
 * E2EE application messages over the existing Matrix client, outside the room timeline.
 * Only decrypted messages from the bound device with an SDK-confirmed sender are delivered.
 * This channel is a transport primitive, not an application pairing or authorisation grant.
 */
interface EncryptedDeviceChannel {
    /** Collection ends on logout or transport failure. There is no replay of past messages. */
    val messages: Flow<EncryptedDeviceMessage>

    /** Wait for the receiving SDK subscription before sending an open that may be answered immediately. */
    suspend fun awaitReady()

    /** SDK submission, not an acknowledgement that the peer processed the message. */
    suspend fun send(eventType: String, contentJson: String): Result<Unit>
}

fun interface EncryptedDeviceChannelFactory {
    /** Event types and the pinned peer belong to trusted application code, never to model output. */
    fun create(client: MatrixClient, peer: MatrixDevicePeer, eventTypes: Set<String>): EncryptedDeviceChannel
}
