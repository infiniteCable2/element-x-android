/*
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.androidtoolbox.impl.identity

import com.google.common.truth.Truth.assertThat
import io.element.android.features.androidtoolbox.api.OpenClawNodeBinding
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OpenClawNodeBindingStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `binding persists protected alongside credentials and failed replacement preserves last selection`() = runTest {
        val file = temporary.newFolder().resolve("credentials.preferences_pb")
        val environment = CredentialTestEnvironment(this, file)
        val store = DefaultOpenClawNodeBindingStore(environment.storage)
        val binding = OpenClawNodeBinding("@sample:example.org", "!sample:example.org", "@assistant:example.org", "SAMPLEDEVICE")
        assertThat(store.load().getOrThrow()).isNull()
        store.save(binding).getOrThrow()
        assertThat(file.readBytes().toString(Charsets.UTF_8)).doesNotContain(binding.peerUserId)
        environment.disk.failNextWrite = true
        assertThat(store.save(binding.copy(peerDeviceId = "OTHERDEVICE")).isFailure).isTrue()
        assertThat(store.load().getOrThrow()).isEqualTo(binding)
        assertThat(store.save(binding.copy(peerDeviceId = "*")).isFailure).isTrue()
        environment.close()
        val reopened = CredentialTestEnvironment(this, file, environment.keys)
        assertThat(DefaultOpenClawNodeBindingStore(reopened.storage).load().getOrThrow()).isEqualTo(binding)
        reopened.close()
    }
}
