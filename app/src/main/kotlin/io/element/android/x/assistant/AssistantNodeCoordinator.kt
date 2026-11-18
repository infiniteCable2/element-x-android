/*
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.x.assistant

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.element.android.features.androidtoolbox.api.AndroidToolLauncher
import io.element.android.features.androidtoolbox.api.AndroidToolSession
import io.element.android.features.androidtoolbox.api.AndroidToolbox
import io.element.android.features.androidtoolbox.api.AssistantNodePairing
import io.element.android.features.androidtoolbox.api.MatrixNodeCarrier
import io.element.android.features.androidtoolbox.api.OpenClawDeviceAuthStore
import io.element.android.features.androidtoolbox.api.OpenClawDeviceIdentityStore
import io.element.android.features.androidtoolbox.api.OpenClawInvokeDeadline
import io.element.android.features.androidtoolbox.api.OpenClawNodeBinding
import io.element.android.features.androidtoolbox.api.OpenClawNodeBindingStore
import io.element.android.features.androidtoolbox.api.OpenClawNodeConnection
import io.element.android.features.androidtoolbox.api.OpenClawNodeSetup
import io.element.android.features.androidtoolbox.api.OpenClawNodeTools
import io.element.android.libraries.matrix.api.MatrixClientProvider
import io.element.android.libraries.matrix.api.core.DeviceId
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.SessionId
import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.libraries.matrix.api.device.EncryptedDeviceChannelFactory
import io.element.android.libraries.matrix.api.device.EncryptedDeviceMessage
import io.element.android.libraries.matrix.api.device.MatrixDevicePeer
import io.element.android.libraries.matrix.api.encryption.identity.IdentityState
import io.element.android.x.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.seconds

/** One exact verified Matrix peer plus one native node grant. Pairing never grants foreground app-control rights. */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class AssistantNodeCoordinator(
    private val peers: AssistantNodePeerResolver,
    private val clients: MatrixClientProvider,
    private val channels: EncryptedDeviceChannelFactory,
    private val identities: OpenClawDeviceIdentityStore,
    private val credentials: OpenClawDeviceAuthStore,
    private val bindings: OpenClawNodeBindingStore,
    private val toolbox: AndroidToolbox,
    private val surface: AssistantToolSurface,
    private val clock: AssistantNodeClock,
) : AssistantNodePairing {
    private val active = Mutex()

    override suspend fun pair(sessionId: String, roomId: String, peerDeviceId: String, setupCode: String): AssistantNodePairing.Outcome {
        val setup = OpenClawNodeSetup.parse(setupCode).getOrNull() ?: return AssistantNodePairing.Outcome.InvalidSetup
        if (!active.tryLock()) return AssistantNodePairing.Outcome.Failed
        try {
            val selected = peers.resolve(sessionId, roomId, peerDeviceId.trim()) ?: return AssistantNodePairing.Outcome.UnverifiedChat
            runLink(selected.second, setup, surface.launcher, pairingOnly = true, isInvocationCurrent = { true })
            return AssistantNodePairing.Outcome.Paired
        } catch (_: TimeoutCancellationException) {
            return AssistantNodePairing.Outcome.Failed
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return AssistantNodePairing.Outcome.Failed
        } finally {
            active.unlock()
        }
    }

    /** Owned by one assistant call attempt, cancelled when that attempt ends. Hiding its window revokes app-launch rights. */
    suspend fun runAssistant(sessionId: String, roomId: String, isInvocationCurrent: (String, String) -> Boolean) {
        if (!active.tryLock()) return
        try {
            val binding = bindings.load().getOrThrow() ?: return
            if (binding.sessionId != sessionId || binding.roomId != roomId) return
            runLink(binding, null, surface.launcher, pairingOnly = false) { isInvocationCurrent(binding.peerUserId, binding.peerDeviceId) }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // Toolbox failure does not end a healthy voice call or invent a Talk status.
        } finally {
            active.unlock()
        }
    }

    private suspend fun runLink(
        binding: OpenClawNodeBinding,
        setup: OpenClawNodeSetup?,
        launcher: AndroidToolLauncher,
        pairingOnly: Boolean,
        isInvocationCurrent: () -> Boolean,
    ) = coroutineScope {
        suspend fun selected() = peers.resolve(binding.sessionId, binding.roomId, binding.peerDeviceId)?.takeIf { it.second == binding }
        check(selected() != null && isInvocationCurrent())
        val client = clients.getOrRestore(SessionId(binding.sessionId)).getOrThrow()
        val room = client.getJoinedRoom(RoomId(binding.roomId)) ?: error("The assistant room is unavailable.")
        try {
            val peer = MatrixDevicePeer(UserId(binding.peerUserId), DeviceId(binding.peerDeviceId), requireVerified = true)
            val channel = channels.create(client, peer, setOf(MatrixNodeCarrier.EVENT_TYPE))
            suspend fun current() = selected() != null && isInvocationCurrent() && channel.isActive
            val carrier = MatrixNodeCarrier(binding.roomId, clock::elapsedRealtimeMs)
            val incoming = channel.messages.produceIn(this)
            var executing = false
            var invokeDeadline: OpenClawInvokeDeadline? = null
            fun canHandoff() = executing && !pairingOnly && isInvocationCurrent() && channel.isActive && (invokeDeadline?.remainingMs() ?: 0) > 0
            val executionLauncher = surface.forInvocation(::canHandoff)
            // Catalog describes opted-in installed tools. Execution independently needs this live assistant attempt.
            val catalogSession = AndroidToolSession(toolbox, launcher) { selected()?.first?.toolCapabilities.orEmpty() }
            val executeSession = AndroidToolSession(toolbox, executionLauncher) {
                val selection = selected()
                if (canHandoff()) {
                    selection?.first?.toolCapabilities.orEmpty()
                } else {
                    emptySet()
                }
            }
            val connection = OpenClawNodeConnection(
                binding.gatewayId,
                BuildConfig.VERSION_NAME,
                identities,
                credentials,
                tools = { id -> OpenClawNodeTools(id, executeSession, catalogSession) },
                setup = setup,
                isCurrent = ::current,
                send = { frame -> channel.send(MatrixNodeCarrier.EVENT_TYPE, carrier.frame(frame)).getOrThrow() },
                onPaired = { if (setup != null) bindings.save(binding).getOrThrow() },
            )
            suspend fun receiveFrame() {
                val frame = incoming.nextFrame(carrier)
                invokeDeadline = frame.deadline
                try {
                    connection.receive(frame.value, frame.deadline)
                } finally {
                    invokeDeadline = null
                }
            }
            val watchdog = launch {
                while (true) {
                    delay(1.seconds)
                    check(current()) { "The assistant selection or verification changed." }
                }
            }
            val trust = launch {
                room.identityStateChangesFlow.collect { changes ->
                    check(changes.none { it.userId.value == binding.peerUserId && it.identityState != IdentityState.Verified }) {
                        "The peer verification changed."
                    }
                }
            }
            try {
                withTimeout(90.seconds) {
                    channel.awaitReady()
                    check(current())
                    channel.send(MatrixNodeCarrier.EVENT_TYPE, carrier.open()).getOrThrow()
                    while (connection.phase.value != OpenClawNodeConnection.Phase.Ready) {
                        receiveFrame()
                    }
                }
                executing = !pairingOnly
                if (!pairingOnly) {
                    while (true) receiveFrame()
                }
            } finally {
                executing = false
                connection.close()
                incoming.cancel()
                watchdog.cancel()
                trust.cancel()
                withContext(NonCancellable) {
                    runCatching { withTimeout(3.seconds) { carrier.close()?.let { channel.send(MatrixNodeCarrier.EVENT_TYPE, it) } } }
                }
            }
        } finally {
            room.close()
        }
    }

    private suspend fun ReceiveChannel<EncryptedDeviceMessage>.nextFrame(
        carrier: MatrixNodeCarrier,
    ): MatrixNodeCarrier.Incoming.Frame {
        while (true) {
            when (val message = carrier.receive(receive().contentJson)) {
                is MatrixNodeCarrier.Incoming.Frame -> return message
                MatrixNodeCarrier.Incoming.Ignored -> Unit
                else -> error("The verified native node link ended.")
            }
        }
    }
}
