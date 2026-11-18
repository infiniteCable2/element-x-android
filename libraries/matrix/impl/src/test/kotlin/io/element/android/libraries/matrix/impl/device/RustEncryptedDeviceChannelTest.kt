/*
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.impl.device

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.api.core.DeviceId
import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.libraries.matrix.api.device.MatrixDevicePeer
import io.element.android.libraries.matrix.api.device.EncryptedDeviceMessage
import io.element.android.libraries.matrix.impl.rtc.A_SENDER_DEVICE
import io.element.android.libraries.matrix.impl.rtc.anEncryptionInfo
import io.element.android.tests.testutils.testCoroutineDispatchers
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.job
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.ShieldState
import org.matrix.rustcomponents.sdk.TaskHandle
import org.matrix.rustcomponents.sdk.ToDeviceMessage
import org.matrix.rustcomponents.sdk.ToDeviceMessageListener
import uniffi.matrix_sdk_ui.TimelineEventShieldStateCode

class RustEncryptedDeviceChannelTest {
    private val peer = MatrixDevicePeer(UserId("@sample:example.org"), DeviceId(A_SENDER_DEVICE))
    private val types = setOf("org.example.device.frame")
    private val message = ToDeviceMessage(
        eventType = types.single(),
        senderId = "@claimed:example.org",
        content = "{}",
        encryptionInfo = anEncryptionInfo(peer.userId.value, ShieldState.None),
    )

    @Test
    fun `only the attested pinned device and allowed event types are delivered`() {
        assertThat(message.forPeer(peer, types)?.contentJson).isEqualTo("{}")
        assertThat(message.copy(encryptionInfo = null).forPeer(peer, types)).isNull()
        assertThat(message.forPeer(peer.copy(deviceId = DeviceId("OTHER")), types)).isNull()
        assertThat(message.forPeer(peer.copy(userId = UserId("@other:example.org")), types)).isNull()
        assertThat(message.copy(eventType = "org.example.other").forPeer(peer, types)).isNull()
        assertThat(message.copy(content = "a".repeat(65_537)).forPeer(peer, types)).isNull()
        val red = anEncryptionInfo(peer.userId.value, ShieldState.Red(TimelineEventShieldStateCode.MISMATCHED_SENDER))
        assertThat(message.copy(encryptionInfo = red).forPeer(peer, types)).isNull()
        assertThat(message.copy(encryptionInfo = message.encryptionInfo!!.copy(senderDeviceId = null)).forPeer(peer, types)).isNull()
    }

    @Test
    fun `strict node peer requires full SDK verification and trust loss closes collection`() = runTest {
        val strict = peer.copy(requireVerified = true)
        val grey = message.copy(encryptionInfo = anEncryptionInfo(peer.userId.value, ShieldState.Grey(TimelineEventShieldStateCode.UNSIGNED_DEVICE)))
        assertThat(message.forPeer(strict, types)).isNotNull()
        assertThat(grey.forPeer(strict, types)).isNull()
        val sdk = mockk<Client>()
        val task = mockk<TaskHandle>(relaxed = true)
        val subscribed = CompletableDeferred<ToDeviceMessageListener>()
        coEvery { sdk.subscribeToCustomToDeviceMessages(any(), any()) } answers {
            subscribed.complete(secondArg())
            task
        }
        val channel = RustEncryptedDeviceChannel(sdk, backgroundScope, testCoroutineDispatchers(), strict, types)
        val delivered = mutableListOf<EncryptedDeviceMessage>()
        val collection = async { runCatching { channel.messages.toList(delivered) } }
        val listener = subscribed.await()
        listener.onMessage(message)
        listener.onMessage(grey)
        assertThat(channel.isActive).isFalse()
        assertThat(collection.await().exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
        assertThat(delivered).isEmpty()
        assertThat(channel.send(types.single(), "{}").isFailure).isTrue()
        coVerify(exactly = 1) { task.cancel() }
        coVerify(exactly = 1) { task.destroy() }
    }

    @Test
    fun `sending is encrypted targeted and checks delivery failures`() = runTest {
        val sdk = mockk<Client>()
        coEvery { sdk.sendEncryptedToDeviceMessage(any(), any(), any()) } returns mockk {
            every { failures } returns emptyMap()
        }
        val channel = RustEncryptedDeviceChannel(sdk, backgroundScope, testCoroutineDispatchers(), peer, types)
        assertThat(channel.send(types.single(), "{}").isSuccess).isTrue()
        coVerify(exactly = 1) { sdk.sendEncryptedToDeviceMessage(types.single(), mapOf(peer.userId.value to listOf(peer.deviceId.value)), "{}") }
        assertThat(channel.send("org.example.other", "{}").isFailure).isTrue()
        assertThat(channel.send(types.single(), "a".repeat(65_537)).isFailure).isTrue()
        coEvery { sdk.sendEncryptedToDeviceMessage(any(), any(), any()) } returns mockk {
            every { failures } returns mapOf(peer.userId.value to listOf(peer.deviceId.value))
        }
        assertThat(channel.send(types.single(), "{}").isFailure).isTrue()
    }

    @Test
    fun `collector detaches and destroyed session rejects further sending`() = runTest {
        val sdk = mockk<Client>()
        val task = mockk<TaskHandle>(relaxed = true)
        val subscribed = CompletableDeferred<ToDeviceMessageListener>()
        coEvery { sdk.subscribeToCustomToDeviceMessages(any(), any()) } answers {
            subscribed.complete(secondArg())
            task
        }
        val lifetime = CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext.job))
        val channel = RustEncryptedDeviceChannel(sdk, lifetime, testCoroutineDispatchers(), peer, types)
        val first = async { channel.messages.first() }
        subscribed.await().onMessage(message)
        assertThat(first.await().contentJson).isEqualTo("{}")
        coVerify { task.cancel() }
        coVerify { task.destroy() }
        lifetime.cancel()
        assertThat(channel.send(types.single(), "{}").isFailure).isTrue()
        coVerify(exactly = 0) { sdk.sendEncryptedToDeviceMessage(any(), any(), any()) }
    }

    @Test
    fun `receive overflow fails the channel and releases its SDK subscription`() = runTest {
        val sdk = mockk<Client>()
        val task = mockk<TaskHandle>(relaxed = true)
        val subscribed = CompletableDeferred<ToDeviceMessageListener>()
        coEvery { sdk.subscribeToCustomToDeviceMessages(any(), any()) } answers {
            subscribed.complete(secondArg())
            task
        }
        val channel = RustEncryptedDeviceChannel(sdk, backgroundScope, testCoroutineDispatchers(), peer, types)
        val collection = async { runCatching { channel.messages.toList() } }
        val listener = subscribed.await()
        repeat(256) { listener.onMessage(message) }
        assertThat(collection.await().exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
        coVerify(exactly = 1) { task.cancel() }
        coVerify(exactly = 1) { task.destroy() }
    }
}
