/*
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.androidtoolbox.impl.identity

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import io.element.android.libraries.cryptography.impl.AESEncryptionDecryptionService
import io.element.android.libraries.cryptography.test.SimpleSecretKeyRepository
import io.element.android.tests.testutils.testCoroutineDispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.job
import kotlinx.coroutines.test.TestScope
import java.io.File
import java.io.IOException

internal class CredentialTestEnvironment(scope: TestScope, file: File, val keys: SimpleSecretKeyRepository = SimpleSecretKeyRepository()) {
    private val writer = Job(scope.backgroundScope.coroutineContext.job)
    private val actualStore = PreferenceDataStoreFactory.create(
        scope = CoroutineScope(scope.backgroundScope.coroutineContext + writer),
        produceFile = { file },
    )
    val disk = FailingDataStore(actualStore)
    val storage = AndroidNodeCredentialStorage(disk, keys, AESEncryptionDecryptionService(), scope.testCoroutineDispatchers())
    val identities = DefaultOpenClawDeviceIdentityStore(storage, scope.testCoroutineDispatchers())
    val tokens = DefaultOpenClawDeviceAuthStore(storage, scope.testCoroutineDispatchers())

    suspend fun close() = writer.cancelAndJoin()
}

internal class FailingDataStore(private val delegate: DataStore<Preferences>) : DataStore<Preferences> {
    var failNextWrite = false
    override val data: Flow<Preferences> get() = delegate.data
    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = delegate.updateData { previous ->
        val updated = transform(previous)
        if (failNextWrite) {
            failNextWrite = false
            throw IOException("Injected credential persistence failure")
        }
        updated
    }
}
