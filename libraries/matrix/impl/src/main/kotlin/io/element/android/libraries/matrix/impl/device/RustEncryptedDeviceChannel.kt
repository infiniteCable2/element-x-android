/*
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.impl.device

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import io.element.android.libraries.core.coroutine.CoroutineDispatchers
import io.element.android.libraries.core.extensions.runCatchingExceptions
import io.element.android.libraries.matrix.api.MatrixClient
import io.element.android.libraries.matrix.api.device.EncryptedDeviceChannel
import io.element.android.libraries.matrix.api.device.EncryptedDeviceChannelFactory
import io.element.android.libraries.matrix.api.device.EncryptedDeviceMessage
import io.element.android.libraries.matrix.api.device.MatrixDevicePeer
import io.element.android.libraries.matrix.impl.RustMatrixClient
import io.element.android.libraries.matrix.impl.util.cancelAndDestroy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.ShieldState
import org.matrix.rustcomponents.sdk.ToDeviceMessage
import org.matrix.rustcomponents.sdk.ToDeviceMessageListener
import java.util.concurrent.atomic.AtomicBoolean

@ContributesBinding(AppScope::class)
class RustEncryptedDeviceChannelFactory(private val dispatchers: CoroutineDispatchers) : EncryptedDeviceChannelFactory {
    override fun create(client: MatrixClient, peer: MatrixDevicePeer, eventTypes: Set<String>): EncryptedDeviceChannel = RustEncryptedDeviceChannel(
        client = (client as RustMatrixClient).innerClient,
        sessionScope = client.sessionCoroutineScope,
        dispatchers = dispatchers,
        peer = peer,
        eventTypes = eventTypes,
    )
}

internal class RustEncryptedDeviceChannel(
    private val client: Client,
    private val sessionScope: CoroutineScope,
    private val dispatchers: CoroutineDispatchers,
    private val peer: MatrixDevicePeer,
    eventTypes: Set<String>,
) : EncryptedDeviceChannel {
    private val types = eventTypes.toSet()
    private val subscriptionReady = CompletableDeferred<Unit>()
    private val available = AtomicBoolean(true)
    override val isActive: Boolean get() = available.get() && sessionScope.isActive

    init {
        require(peer.deviceId.value.isNotBlank() && peer.deviceId.value != "*")
        require(types.isNotEmpty() && types.all { it.isNotBlank() && !it.startsWith("m.") })
    }

    override val messages = callbackFlow {
        check(this@RustEncryptedDeviceChannel.isActive) { "The Matrix device channel is closed." }
        val lifetime = sessionScope.coroutineContext.job.invokeOnCompletion {
            available.set(false)
            close()
        }
        val subscription = try {
            client.subscribeToCustomToDeviceMessages(types.toList(), object : ToDeviceMessageListener {
                override fun onMessage(message: ToDeviceMessage) {
                    if (!sessionScope.isActive) {
                        available.set(false)
                        close()
                        return
                    }
                    val info = message.encryptionInfo
                    if (peer.requireVerified && message.eventType in types && info?.senderId == peer.userId.value &&
                        info.senderDeviceId == peer.deviceId.value && info.shieldState != ShieldState.None
                    ) {
                        available.set(false)
                        close(IllegalStateException("The selected device is no longer verified."))
                        return
                    }
                    val trusted = message.forPeer(peer, types) ?: return
                    if (trySend(trusted).isFailure) {
                        available.set(false)
                        close(IllegalStateException("The encrypted device message queue overflowed."))
                    }
                }
            })
        } catch (error: Exception) {
            available.set(false)
            subscriptionReady.completeExceptionally(error)
            lifetime.dispose()
            throw error
        }
        subscriptionReady.complete(Unit)
        awaitClose {
            available.set(false)
            lifetime.dispose()
            subscription.cancelAndDestroy()
        }
    }.buffer(capacity = 64).flowOn(dispatchers.io).onEach {
        check(isActive) { "The Matrix device channel was revoked." }
    }

    override suspend fun awaitReady() = subscriptionReady.await()

    override suspend fun send(eventType: String, contentJson: String): Result<Unit> = withContext(dispatchers.io) {
        runCatchingExceptions {
            check(isActive) { "The Matrix device channel is closed." }
            require(eventType in types && contentJson.length <= MAX_DEVICE_MESSAGE_CHARS)
            val failures = client.sendEncryptedToDeviceMessage(eventType, mapOf(peer.userId.value to listOf(peer.deviceId.value)), contentJson).failures
            check(failures.isEmpty()) { "The encrypted message could not be submitted to the selected device." }
        }
    }
}

internal fun ToDeviceMessage.forPeer(peer: MatrixDevicePeer, eventTypes: Set<String>): EncryptedDeviceMessage? {
    val encryption = encryptionInfo ?: return null
    if (encryption.senderId != peer.userId.value || encryption.senderDeviceId != peer.deviceId.value || encryption.shieldState is ShieldState.Red) return null
    if (peer.requireVerified && encryption.shieldState != ShieldState.None) return null
    if (eventType !in eventTypes || content.length > MAX_DEVICE_MESSAGE_CHARS) return null
    return EncryptedDeviceMessage(eventType, content)
}

private const val MAX_DEVICE_MESSAGE_CHARS = 65_536
