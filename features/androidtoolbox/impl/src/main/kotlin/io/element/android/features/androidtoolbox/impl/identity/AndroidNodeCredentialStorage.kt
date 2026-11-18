/*
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.androidtoolbox.impl.identity

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.element.android.libraries.core.coroutine.CoroutineDispatchers
import io.element.android.libraries.cryptography.api.EncryptionDecryptionService
import io.element.android.libraries.cryptography.api.SecretKeyRepository
import io.element.android.libraries.di.annotations.AppCoroutineScope
import io.element.android.libraries.di.annotations.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Base64
import javax.crypto.SecretKey

/** One DataStore owner. No corruption replacement or plaintext fallback for credentials. */
@SingleIn(AppScope::class)
class AndroidNodeCredentialStorage internal constructor(
    private val store: DataStore<Preferences>,
    private val keys: SecretKeyRepository,
    private val crypto: EncryptionDecryptionService,
    private val dispatchers: CoroutineDispatchers,
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        @AppCoroutineScope scope: CoroutineScope,
        keys: SecretKeyRepository,
        crypto: EncryptionDecryptionService,
        dispatchers: CoroutineDispatchers,
    ) : this(
        // Backups cannot restore a software signing key without its non-exportable wrapping key.
        PreferenceDataStoreFactory.create(scope = CoroutineScope(scope.coroutineContext + dispatchers.io), produceFile = {
            File(context.noBackupFilesDir, "openclaw_device_credentials.preferences_pb")
        }),
        keys,
        crypto,
        dispatchers,
    )

    internal suspend fun read(entry: String): String? = withContext(dispatchers.io) {
        val stored = store.data.first()[stringPreferencesKey(entry)] ?: return@withContext null
        decrypt(entry, stored, masterKey(requireExisting = true))
    }

    /** DataStore publishes only after a successful disk write. Token CAS and identity creation share this owner. */
    internal suspend fun update(entry: String, transform: (String?) -> String?): String? = withContext(dispatchers.io) {
        var result: String? = null
        store.edit { preferences ->
            val key = masterKey(requireExisting = preferences.asMap().isNotEmpty())
            val field = stringPreferencesKey(entry)
            val previous = preferences[field]?.let { decrypt(entry, it, key) }
            result = transform(previous)
            if (result != previous) {
                if (result == null) preferences.remove(field) else preferences[field] = encrypt(entry, checkNotNull(result), key)
            }
        }
        result
    }

    private suspend fun masterKey(requireExisting: Boolean): SecretKey {
        check(!requireExisting || keys.hasKey(KEY_ALIAS).first()) { "The Android credential key is unavailable." }
        return keys.getOrCreateKey(KEY_ALIAS, requiresUserAuthentication = false)
    }

    private fun encrypt(entry: String, plaintext: String, key: SecretKey): String {
        val cipher = crypto.createEncryptionCipher(key)
        cipher.updateAAD(entry.toByteArray(Charsets.UTF_8))
        val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        check(cipher.iv.size == IV_SIZE)
        return Base64.getEncoder().encodeToString(byteArrayOf(VERSION) + cipher.iv + encrypted)
    }

    private fun decrypt(entry: String, stored: String, key: SecretKey): String {
        val bytes = Base64.getDecoder().decode(stored)
        require(bytes.size >= 1 + IV_SIZE + 16 && bytes[0] == VERSION) { "Invalid protected credential record." }
        val cipher = crypto.createDecryptionCipher(key, bytes.copyOfRange(1, 1 + IV_SIZE))
        cipher.updateAAD(entry.toByteArray(Charsets.UTF_8))
        return cipher.doFinal(bytes.copyOfRange(1 + IV_SIZE, bytes.size)).toString(Charsets.UTF_8)
    }

    private companion object {
        const val KEY_ALIAS = "elementx_openclaw_device_credentials_v1"
        const val IV_SIZE = 12
        const val VERSION: Byte = 1
    }
}
