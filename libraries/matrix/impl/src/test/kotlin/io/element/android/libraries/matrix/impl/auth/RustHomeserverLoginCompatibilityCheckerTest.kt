/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.impl.auth

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.impl.FakeClientBuilderProvider
import io.element.android.libraries.matrix.impl.TEST_HOMESERVER_URL
import io.element.android.libraries.matrix.impl.TestHomeserverConnectionPolicy
import io.element.android.libraries.matrix.impl.fixtures.fakes.FakeFfiClient
import io.element.android.libraries.matrix.impl.fixtures.fakes.FakeFfiClientBuilder
import io.element.android.libraries.matrix.impl.fixtures.fakes.FakeFfiHomeserverLoginDetails
import io.element.android.tests.testutils.testCoroutineDispatchers
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test

class RustHomeserverLoginCompatibilityCheckerTest {
    @Test
    fun `check - is valid if it supports OAuth login`() = runTest {
        val sut = createChecker { FakeFfiHomeserverLoginDetails(supportsOAuthLogin = true) }
        assertThat(sut.check(TEST_HOMESERVER_URL).getOrNull()).isTrue()
    }

    @Test
    fun `check - is valid if it supports password login`() = runTest {
        val sut = createChecker { FakeFfiHomeserverLoginDetails(supportsPasswordLogin = true) }
        assertThat(sut.check(TEST_HOMESERVER_URL).getOrNull()).isTrue()
    }

    @Test
    fun `check - is not valid if it only supports SSO login`() = runTest {
        val sut = createChecker { FakeFfiHomeserverLoginDetails(supportsSsoLogin = true) }
        assertThat(sut.check(TEST_HOMESERVER_URL).getOrNull()).isFalse()
    }

    @Test
    fun `check - is not valid if fetching the data fails`() = runTest {
        val sut = createChecker { error("Unexpected error!") }
        assertThat(sut.check(TEST_HOMESERVER_URL).isFailure).isTrue()
    }

    @Test
    fun `check rejects another homeserver`() = runTest {
        var builderRequested = false
        val sut = RustHomeServerLoginCompatibilityChecker(
            clientBuilderProvider = FakeClientBuilderProvider {
                builderRequested = true
                FakeFfiClientBuilder()
            },
            coroutineDispatchers = testCoroutineDispatchers(),
        ).also { it.homeserverConnectionPolicy = TestHomeserverConnectionPolicy }
        assertThat(sut.check("https://other.example").isFailure).isTrue()
        assertThat(builderRequested).isFalse()
    }

    @Test
    fun `check rejects a resolved homeserver before reading login methods`() = runTest {
        var loginDetailsRequested = false
        val sut = createChecker(resolvedHomeserver = "https://other.example") {
            loginDetailsRequested = true
            FakeFfiHomeserverLoginDetails(supportsOAuthLogin = true)
        }

        assertThat(sut.check(TEST_HOMESERVER_URL).isFailure).isTrue()
        assertThat(loginDetailsRequested).isFalse()
    }

    private fun TestScope.createChecker(
        resolvedHomeserver: String = TEST_HOMESERVER_URL,
        result: () -> FakeFfiHomeserverLoginDetails,
    ) = RustHomeServerLoginCompatibilityChecker(
        clientBuilderProvider = FakeClientBuilderProvider {
            FakeFfiClientBuilder {
                FakeFfiClient(
                    homeserver = resolvedHomeserver,
                    homeserverLoginDetailsResult = result,
                )
            }
        },
        coroutineDispatchers = testCoroutineDispatchers(),
    ).also { it.homeserverConnectionPolicy = TestHomeserverConnectionPolicy }
}
