/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.impl

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.impl.fixtures.fakes.FakeFfiClient
import io.element.android.libraries.matrix.impl.fixtures.fakes.FakeFfiClientBuilder
import io.element.android.libraries.matrix.impl.paths.SessionPathsFactory
import io.element.android.tests.testutils.testCoroutineDispatchers
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.File

class RustTemporaryMatrixClientFactoryTest {
    @Test
    fun `create returns a TemporaryMatrixClient`() = runTest {
        val sut = createRustTemporaryMatrixClientFactory()
        val result = sut.create(TEST_HOMESERVER_URL)
        assertThat(result.isSuccess).isTrue()
    }

    @Test
    fun `create rejects another homeserver before building a client`() = runTest {
        var builderRequested = false
        val sut = createRustTemporaryMatrixClientFactory(
            rustMatrixClientFactory = createRustMatrixClientFactory(
                clientBuilderProvider = FakeClientBuilderProvider {
                    builderRequested = true
                    FakeFfiClientBuilder()
                },
            ),
        )
        val result = sut.create("https://other.example")
        assertThat(result.isFailure).isTrue()
        assertThat(builderRequested).isFalse()
    }

    @Test
    fun `create rejects and closes a client resolved to another homeserver`() = runTest {
        var clientClosed = false
        val sut = createRustTemporaryMatrixClientFactory(
            rustMatrixClientFactory = createRustMatrixClientFactory(
                clientBuilderProvider = FakeClientBuilderProvider {
                    FakeFfiClientBuilder {
                        FakeFfiClient(
                            homeserver = "https://other.example",
                            closeResult = { clientClosed = true },
                        )
                    }
                },
            ),
        )

        assertThat(sut.create(TEST_HOMESERVER_URL).isFailure).isTrue()
        assertThat(clientClosed).isTrue()
    }

    @Test
    fun `create can fail gracefully`() = runTest {
        val sut = createRustTemporaryMatrixClientFactory(
            rustMatrixClientFactory = createRustMatrixClientFactory(
                clientBuilderProvider = FakeClientBuilderProvider(
                    provideResult = { throw IllegalStateException("Failed to create client builder") }
                )
            )
        )
        val result = sut.create(TEST_HOMESERVER_URL)
        assertThat(result.isFailure).isTrue()
    }

    private fun TestScope.createRustTemporaryMatrixClientFactory(
        sessionPathsFactory: SessionPathsFactory = SessionPathsFactory(File("/base"), File("/cache")),
        rustMatrixClientFactory: RustMatrixClientFactory = createRustMatrixClientFactory(),
    ): RustTemporaryMatrixClientFactory {
        return RustTemporaryMatrixClientFactory(
            sessionPathsFactory = sessionPathsFactory,
            rustMatrixClientFactory = rustMatrixClientFactory,
            coroutineDispatchers = testCoroutineDispatchers(),
        ).also { it.homeserverConnectionPolicy = TestHomeserverConnectionPolicy }
    }
}
