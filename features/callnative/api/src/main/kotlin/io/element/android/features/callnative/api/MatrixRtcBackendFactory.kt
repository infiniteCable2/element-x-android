/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.callnative.api

import io.element.android.libraries.matrix.api.MatrixClient
import org.matrix.rtc.FfiEventIn
import org.matrix.rtc.FfiToDeviceMessageIn
import org.matrix.rtc.MatrixBackend

/**
 * Makes the native call's view of Matrix for a session.
 *
 * The RTC core's backend is built over a raw Rust SDK client, and only `libraries/matrix/impl` is
 * compiled against the SDK. This is the seam that keeps it that way: the implementation lives there,
 * everything else takes a [MatrixClient] and asks.
 *
 * A factory rather than a binding for the backend itself, because the call is started from app scope
 * - a call can be placed for an account that is not the one on screen - and the session it belongs to
 * is known only as an id until a client is fetched for it.
 */
fun interface MatrixRtcBackendFactory {
    fun create(client: MatrixClient, applicationEvents: MatrixRtcApplicationEvents): MatrixBackend
}

/** Application data alongside the native RTC stack, independent of its media and key handling. */
interface MatrixRtcApplicationEvents {
    val toDeviceEventTypes: Set<String>
    fun outgoing(roomId: String, eventType: String, stateKey: String, deviceId: String, contentJson: String): String
    fun received(roomId: String, eventType: String, events: List<FfiEventIn>)
    fun receivedToDevice(event: FfiToDeviceMessageIn)
}
