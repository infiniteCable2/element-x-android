/*
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.androidtoolbox.impl.identity

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.common.truth.Truth.assertThat
import io.element.android.features.androidtoolbox.api.OpenClawDeviceTokenSlot
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class OpenClawDeviceAuthStoreTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private val slot = OpenClawDeviceTokenSlot("gateway-a", "device-a", "node")

    @Test
    fun `gateway device and role isolate durable encrypted grants`() = runTest {
        val file = File(temporaryFolder.root, "credentials.preferences_pb")
        val first = CredentialTestEnvironment(this, file)
        val slots = listOf(slot, slot.copy(gatewayId = "gateway-b"), slot.copy(deviceId = "device-b"), slot.copy(role = "operator"))
        slots.forEachIndexed { index, key ->
            assertThat(first.tokens.saveToken(key, " token-$index ", listOf("write", "read", "read", " ")).getOrThrow()).isTrue()
        }
        val initial = first.tokens.loadEntry(slot.copy(deviceId = " Device-A ", role = " Node ")).getOrThrow()!!
        assertThat(initial.token).isEqualTo("token-0")
        assertThat(initial.scopes).containsExactly("read", "write").inOrder()
        assertThat(initial.role).isEqualTo("node")
        assertThat(initial.updatedAtMs).isGreaterThan(0)
        assertThat(initial.toString()).doesNotContain(initial.token)
        val diskBytes = file.readBytes().toString(Charsets.ISO_8859_1)
        assertThat(diskBytes).doesNotContain("token-0")
        assertThat(diskBytes).doesNotContain("gateway-a")
        first.close()
        val reopened = CredentialTestEnvironment(this, file, first.keys)
        slots.forEachIndexed { index, key -> assertThat(reopened.tokens.loadEntry(key).getOrThrow()?.token).isEqualTo("token-$index") }
        reopened.close()
    }

    @Test
    fun `only one racing replacement wins and stale retirement cannot delete it`() = runTest {
        val environment = CredentialTestEnvironment(this, File(temporaryFolder.root, "credentials.preferences_pb"))
        environment.tokens.saveToken(slot, "old").getOrThrow()
        val results = listOf("replacement-a", "replacement-b").map { candidate ->
            async { environment.tokens.saveToken(slot, candidate, replacesStoredToken = " old ").getOrThrow() }
        }.awaitAll()
        assertThat(results.count { it }).isEqualTo(1)
        val current = environment.tokens.loadEntry(slot).getOrThrow()!!.token
        assertThat(environment.tokens.clearToken(slot, onlyIfToken = "old").getOrThrow()).isFalse()
        assertThat(environment.tokens.loadEntry(slot).getOrThrow()?.token).isEqualTo(current)
        assertThat(environment.tokens.clearToken(slot, onlyIfToken = current).getOrThrow()).isTrue()
        assertThat(environment.tokens.loadEntry(slot).getOrThrow()).isNull()
        assertThat(environment.tokens.saveToken(slot, "stale", replacesStoredToken = current).getOrThrow()).isFalse()
        assertThat(environment.tokens.loadEntry(slot).getOrThrow()).isNull()
        environment.close()
    }

    @Test
    fun `failed persistence preserves the complete old grant`() = runTest {
        val environment = CredentialTestEnvironment(this, File(temporaryFolder.root, "credentials.preferences_pb"))
        environment.tokens.saveToken(slot, "old", listOf("read")).getOrThrow()
        val previous = environment.disk.data.first().asMap()
        environment.disk.failNextWrite = true
        assertThat(environment.tokens.saveToken(slot, "new", listOf("write"), replacesStoredToken = "old").isFailure).isTrue()
        assertThat(environment.disk.data.first().asMap()).isEqualTo(previous)
        assertThat(environment.tokens.loadEntry(slot).getOrThrow()?.token).isEqualTo("old")
        assertThat(environment.tokens.loadEntry(slot).getOrThrow()?.scopes).containsExactly("read")
        environment.disk.failNextWrite = true
        assertThat(environment.tokens.clearToken(slot).isFailure).isTrue()
        assertThat(environment.tokens.loadEntry(slot).getOrThrow()?.token).isEqualTo("old")
        environment.close()
    }

    @Test
    fun `swapping ciphertext between role slots fails authentication`() = runTest {
        val environment = CredentialTestEnvironment(this, File(temporaryFolder.root, "credentials.preferences_pb"))
        environment.tokens.saveToken(slot, "first-token").getOrThrow()
        val second = slot.copy(role = "operator")
        environment.tokens.saveToken(second, "second-token").getOrThrow()
        val records = environment.disk.data.first().asMap().entries.toList()
        assertThat(records).hasSize(2)
        environment.disk.edit { preferences ->
            preferences[stringPreferencesKey(records[0].key.name)] = records[1].value as String
            preferences[stringPreferencesKey(records[1].key.name)] = records[0].value as String
        }
        assertThat(environment.tokens.loadEntry(slot).isFailure).isTrue()
        assertThat(environment.tokens.loadEntry(second).isFailure).isTrue()
        environment.close()
    }
}
