/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.roomdetails.impl

sealed interface RoomDetailsEvent {
    data class LeaveRoom(val needsConfirmation: Boolean) : RoomDetailsEvent
    data object MuteNotification : RoomDetailsEvent
    data object UnmuteNotification : RoomDetailsEvent
    data class CopyToClipboard(val text: String) : RoomDetailsEvent
    data object CopyRoomId : RoomDetailsEvent
    data class SetFavorite(val isFavorite: Boolean) : RoomDetailsEvent
    data class SetBubbleEnabled(val enabled: Boolean) : RoomDetailsEvent
    data class SetAssistantChatEnabled(val enabled: Boolean) : RoomDetailsEvent
    data class SetAssistantVoiceEnabled(val enabled: Boolean) : RoomDetailsEvent
    data class SetAssistantMapsEnabled(val enabled: Boolean) : RoomDetailsEvent
    // Secret-bearing event deliberately has no generated toString/copy/component methods.
    class PairAssistantNode(val deviceId: String, val setupCode: String) : RoomDetailsEvent
    data object CancelAssistantNodePairing : RoomDetailsEvent
    data object MarkAsRead : RoomDetailsEvent
    data object MarkAsUnread : RoomDetailsEvent
}
