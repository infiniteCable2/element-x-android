/*
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.androidtoolbox.impl.identity

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.common.truth.Truth.assertThat
import com.google.crypto.tink.subtle.Ed25519Verify
import io.element.android.features.androidtoolbox.api.OpenClawDeviceAuthClaim
import io.element.android.tests.testutils.testCoroutineDispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.GeneralSecurityException
import java.util.Base64

class OpenClawDeviceIdentityStoreTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun `persisted identity signs native Node vectors with byte identical UTF8 and Ed25519 output`() = runTest {
        val fixture = checkNotNull(javaClass.getResourceAsStream("/native-device-auth/v3-vectors.json"))
            .bufferedReader(Charsets.UTF_8).use { Json.parseToJsonElement(it.readText()).jsonObject }
        for ((index, vector) in fixture["vectors"]!!.jsonArray.withIndex()) {
            val item = vector.jsonObject
            val expected = item["expected"]!!.jsonObject
            val claim = item["claim"]!!.jsonObject.claim()
            val seed = item["seedHex"]!!.jsonPrimitive.content.hexBytes()
            val environment = CredentialTestEnvironment(this, File(temporaryFolder.root, "vector-$index.preferences_pb"))
            environment.storage.update(IDENTITY_ENTRY) {
                buildJsonObject {
                    put("version", 1)
                    put("seed", Base64.getEncoder().encodeToString(seed))
                }.toString()
            }
            val identity = environment.identities.loadOrCreate().getOrThrow()
            assertThat(identity.deviceId).isEqualTo(expected["deviceId"]!!.jsonPrimitive.content)
            assertThat(identity.publicKey).isEqualTo(expected["publicKey"]!!.jsonPrimitive.content)
            val payload = claim.payloadV3(identity.deviceId)
            assertThat(payload).isEqualTo(expected["payload"]!!.jsonPrimitive.content)
            assertThat(payload.toByteArray(Charsets.UTF_8)).isEqualTo(expected["payloadUtf8Hex"]!!.jsonPrimitive.content.hexBytes())
            val proof = environment.identities.signV3(claim).getOrThrow()
            assertThat(proof.signature).isEqualTo(expected["signature"]!!.jsonPrimitive.content)
            assertThat(Base64.getUrlDecoder().decode(proof.signature)).hasLength(64)
            val verifier = Ed25519Verify(Base64.getUrlDecoder().decode(identity.publicKey))
            verifier.verify(Base64.getUrlDecoder().decode(proof.signature), payload.toByteArray(Charsets.UTF_8))
            assertThrows(GeneralSecurityException::class.java) {
                verifier.verify(Base64.getUrlDecoder().decode(proof.signature), (payload + "changed").toByteArray(Charsets.UTF_8))
            }
            val persisted = environment.disk.data.first().asMap().values.joinToString()
            assertThat(persisted).doesNotContain(Base64.getEncoder().encodeToString(seed))
            assertThat(persisted).doesNotContain(identity.publicKey)
            environment.close()
        }
    }

    @Test
    fun `concurrent creation and a real DataStore reopen retain one identity`() = runTest {
        val file = File(temporaryFolder.root, "credentials.preferences_pb")
        val first = CredentialTestEnvironment(this, file)
        val identities = List(5) { async { first.identities.loadOrCreate().getOrThrow() } }.awaitAll()
        assertThat(identities.distinct()).hasSize(1)
        first.close()
        val reopened = CredentialTestEnvironment(this, file, first.keys)
        assertThat(reopened.identities.loadOrCreate().getOrThrow()).isEqualTo(identities.first())
        reopened.close()
    }

    @Test
    fun `failed persistence never returns an uncommitted identity`() = runTest {
        val environment = CredentialTestEnvironment(this, File(temporaryFolder.root, "credentials.preferences_pb"))
        environment.disk.failNextWrite = true
        assertThat(environment.identities.loadOrCreate().isFailure).isTrue()
        assertThat(environment.disk.data.first().asMap()).isEmpty()
        val durable = environment.identities.loadOrCreate().getOrThrow()
        val secondReader = DefaultOpenClawDeviceIdentityStore(environment.storage, testCoroutineDispatchers())
        assertThat(secondReader.loadOrCreate().getOrThrow()).isEqualTo(durable)
        environment.close()
    }

    @Test
    fun `a lost wrapping key fails without replacing the native identity`() = runTest {
        val environment = CredentialTestEnvironment(this, File(temporaryFolder.root, "credentials.preferences_pb"))
        environment.identities.loadOrCreate().getOrThrow()
        val original = environment.disk.data.first().asMap()
        environment.keys.deleteKey("elementx_openclaw_device_credentials_v1")
        assertThat(environment.identities.loadOrCreate().isFailure).isTrue()
        assertThat(environment.disk.data.first().asMap()).isEqualTo(original)
        environment.close()
    }

    @Test
    fun `corruption is reported without secret diagnostics or replacement`() = runTest {
        val environment = CredentialTestEnvironment(this, File(temporaryFolder.root, "credentials.preferences_pb"))
        environment.identities.loadOrCreate().getOrThrow()
        environment.storage.update(IDENTITY_ENTRY) { """{"version":1,"seed":"private-rejected-example"}""" }
        val corrupted = environment.disk.data.first().asMap()
        val failure = environment.identities.loadOrCreate().exceptionOrNull()
        assertThat(failure).isNotNull()
        assertThat(failure?.message).doesNotContain("private-rejected-example")
        assertThat(environment.disk.data.first().asMap()).isEqualTo(corrupted)
        environment.disk.edit { it[stringPreferencesKey(IDENTITY_ENTRY)] = "broken encrypted record" }
        assertThat(environment.identities.loadOrCreate().isFailure).isTrue()
        assertThat(environment.disk.data.first()[stringPreferencesKey(IDENTITY_ENTRY)]).isEqualTo("broken encrypted record")
        environment.close()
    }

    @Test
    fun `a corrupt DataStore file is never reset to a new identity`() = runTest {
        val file = File(temporaryFolder.root, "credentials.preferences_pb")
        val first = CredentialTestEnvironment(this, file)
        first.identities.loadOrCreate().getOrThrow()
        first.close()
        val corruptBytes = byteArrayOf(0x0f, 0x0f, 0x0f)
        file.writeBytes(corruptBytes)
        val reopened = CredentialTestEnvironment(this, file, first.keys)
        assertThat(reopened.identities.loadOrCreate().isFailure).isTrue()
        assertThat(file.readBytes()).isEqualTo(corruptBytes)
        reopened.close()
    }

    private fun JsonObject.claim() = OpenClawDeviceAuthClaim(
        clientId = getValue("clientId").jsonPrimitive.content,
        clientMode = getValue("clientMode").jsonPrimitive.content,
        role = getValue("role").jsonPrimitive.content,
        scopes = getValue("scopes").jsonArray.map { it.jsonPrimitive.content },
        signedAtMs = getValue("signedAtMs").jsonPrimitive.long,
        token = (get("token") as? JsonPrimitive)?.takeIf { it.isString }?.content,
        nonce = getValue("nonce").jsonPrimitive.content,
        platform = (get("platform") as? JsonPrimitive)?.takeIf { it.isString }?.content,
        deviceFamily = (get("deviceFamily") as? JsonPrimitive)?.takeIf { it.isString }?.content,
    )
    private fun String.hexBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
