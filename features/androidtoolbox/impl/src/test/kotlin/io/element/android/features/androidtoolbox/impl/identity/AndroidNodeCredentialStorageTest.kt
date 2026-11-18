/*
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.androidtoolbox.impl.identity

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.cryptography.impl.AESEncryptionDecryptionService
import io.element.android.libraries.cryptography.test.SimpleSecretKeyRepository
import io.element.android.tests.testutils.robolectric.RobolectricTest
import io.element.android.tests.testutils.testCoroutineDispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import java.io.File

class AndroidNodeCredentialStorageTest : RobolectricTest() {
    @Test
    fun `production factory keeps ciphertext in the Android no backup directory`() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val storage = AndroidNodeCredentialStorage(
            context = context,
            scope = backgroundScope,
            keys = SimpleSecretKeyRepository(),
            crypto = AESEncryptionDecryptionService(),
            dispatchers = testCoroutineDispatchers(),
        )
        val plaintext = "synthetic-secret-not-a-real-device-key"
        assertThat(storage.update("test-record") { plaintext }).isEqualTo(plaintext)
        assertThat(storage.read("test-record")).isEqualTo(plaintext)
        val file = File(context.noBackupFilesDir, "openclaw_device_credentials.preferences_pb")
        assertThat(file.exists()).isTrue()
        assertThat(file.readBytes().toString(Charsets.ISO_8859_1)).doesNotContain(plaintext)
        assertThat(File(context.filesDir, "datastore/openclaw_device_credentials.preferences_pb").exists()).isFalse()
    }
}
