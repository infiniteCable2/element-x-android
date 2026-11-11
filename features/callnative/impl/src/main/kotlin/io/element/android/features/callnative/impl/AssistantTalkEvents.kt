/*
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.callnative.impl

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Called under AssistantRtcStateEvents' lock, after the authenticated envelope has been checked. */
internal class AssistantTalkEvents {
    private val mutableStates = MutableStateFlow<Map<AssistantVoicePeer, TalkSessionState>>(emptyMap())
    val states = mutableStates.asStateFlow()
    private var offers = emptyMap<AssistantVoicePeer, String>()
    private var readyDevices = emptySet<String>()
    private val pending = ArrayDeque<Pair<String, TalkEvent>>()
    private val history = linkedMapOf<Pair<String, String>, TalkSessionState>()
    private val seen = linkedSetOf<Triple<String, String, String>>()

    fun updateOffers(newOffers: Map<AssistantVoicePeer, String>, devices: Set<String>) {
        offers = newOffers.entries.take(32).associate { it.toPair() }
        readyDevices = devices
        mutableStates.value = offers.mapValues { (peer, session) ->
            mutableStates.value[peer]?.takeIf { it.sessionId == session } ?: history[peer.deviceId to session] ?: TalkSessionState(session, -1)
        }
        val waiting = pending.toList()
        pending.clear()
        waiting.sortedBy { it.second.sequence }.forEach { (device, event) -> receive(device, event) }
    }

    fun receive(device: String, event: TalkEvent) {
        val peer = offers.keys.firstOrNull { it.deviceId == device && offers[it] == event.sessionId }
        if (peer == null) {
            if (device in readyDevices) return
            // Keep only the newest pre-ready activity snapshot, plus bounded distinct outcomes.
            if (event.isActivity) {
                if (pending.any { (sender, previous) ->
                    sender == device && previous.sessionId == event.sessionId && previous.isActivity && previous.sequence >= event.sequence
                }) return
                pending.removeAll { (sender, previous) -> sender == device && previous.sessionId == event.sessionId && previous.isActivity }
            }
            if (pending.size == 64) pending.removeFirst()
            pending.addLast(device to event)
            return
        }
        val identity = Triple(device, event.sessionId, event.id)
        if (!seen.add(identity)) return
        if (seen.size > 256) seen.remove(seen.first())
        val state = mutableStates.value[peer].accept(event) ?: return
        mutableStates.value = mutableStates.value + (peer to state)
        // Only watermarks survive withdrawal. Reoffering a session cannot resurrect its old UI.
        history[device to event.sessionId] = state.copy(activity = null, inputOutcome = null)
        if (history.size > 64) history.remove(history.keys.first())
    }

    fun clear() {
        offers = emptyMap()
        readyDevices = emptySet()
        pending.clear()
        mutableStates.value = emptyMap()
    }
}
