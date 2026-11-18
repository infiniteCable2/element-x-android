/*
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.androidtoolbox.impl.identity

import com.google.common.truth.Truth.assertThat
import io.element.android.features.androidtoolbox.api.AndroidToolSession
import io.element.android.features.androidtoolbox.api.OpenClawNodeConnection
import io.element.android.features.androidtoolbox.api.OpenClawNodeSetup
import io.element.android.features.androidtoolbox.api.OpenClawNodeTools
import io.element.android.features.androidtoolbox.impl.DefaultAndroidToolbox
import io.element.android.features.androidtoolbox.impl.GoogleMapsToolModule
import io.element.android.features.androidtoolbox.test.RecordingAndroidToolLauncher
import io.element.android.tests.testutils.testCoroutineDispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Base64

class NativeNodeCredentialRoundTripTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `native connect proofs use actual protected identity and durable grant across bootstrap and process reopen`() = runTest {
        val vectors = checkNotNull(javaClass.getResourceAsStream("/native-device-auth/v3-vectors.json"))
            .bufferedReader(Charsets.UTF_8).use { Json.parseToJsonElement(it.readText()).jsonObject["vectors"]!!.jsonArray }
        val file = temporary.newFolder().resolve("credentials.preferences_pb")
        val initial = CredentialTestEnvironment(this, file)
        val cases = listOf("node-bootstrap-connect", "node-device-token-connect")
        val seed = vectors.first().jsonObject["seedHex"]!!.jsonPrimitive.content.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        initial.storage.update(IDENTITY_ENTRY) {
            buildJsonObject {
                put("version", 1)
                put("seed", Base64.getEncoder().encodeToString(seed))
            }.toString()
        }
        var environment = initial
        for ((index, name) in cases.withIndex()) {
            if (index > 0) environment = CredentialTestEnvironment(this, file, initial.keys)
            val vector = vectors.single { it.jsonObject["name"]!!.jsonPrimitive.content == name }.jsonObject
            val expected = vector["expected"]!!.jsonObject
            val sent = mutableListOf<JsonObject>()
            val tools = AndroidToolSession(
                DefaultAndroidToolbox(setOf(GoogleMapsToolModule()), testCoroutineDispatchers()),
                RecordingAndroidToolLauncher(),
            ) { setOf("maps") }
            val code = Base64.getUrlEncoder().withoutPadding().encodeToString(
                """{"url":"wss://example.org","bootstrapToken":"synthetic-bootstrap","expiresAtMs":1700000060000}""".toByteArray()
            )
            val connection = OpenClawNodeConnection(
                "synthetic-gateway",
                "test",
                environment.identities,
                environment.tokens,
                tools = { OpenClawNodeTools(it, tools) },
                setup = if (index == 0) OpenClawNodeSetup.parse(code).getOrThrow() else null,
                isCurrent = { true },
                send = { sent.add(it) },
                onPaired = {},
            )
            connection.receive(Json.parseToJsonElement(
                """{"type":"event","event":"connect.challenge","payload":{"nonce":"nonce-abc","ts":1700000000000}}"""
            ).jsonObject)
            val connect = sent.single()["params"]!!.jsonObject
            val proof = connect["device"]!!.jsonObject
            assertThat(connect["client"]!!.jsonObject["id"]).isEqualTo(vector["claim"]!!.jsonObject["clientId"])
            assertThat(connect["caps"]).isNull()
            assertThat(proof["id"]).isEqualTo(expected["deviceId"])
            assertThat(proof["publicKey"]).isEqualTo(expected["publicKey"])
            assertThat(proof["signature"]).isEqualTo(expected["signature"])
            val authKey = if (index == 0) "bootstrapToken" else "deviceToken"
            assertThat(connect["auth"]!!.jsonObject.keys).containsExactly(authKey)
            assertThat(connect["auth"]!!.jsonObject[authKey]).isEqualTo(vector["claim"]!!.jsonObject["token"])
            connection.receive(buildJsonObject {
                put("type", "res")
                put("id", checkNotNull(sent.single()["id"]))
                put("ok", true)
                put("payload", Json.parseToJsonElement(
                    """{"type":"hello-ok","protocol":4,"auth":{"role":"node","scopes":[],"deviceToken":"synthetic-device-token"}}"""
                ))
            })
            if (index == 0) {
                assertThat(sent).hasSize(1)
            } else {
                assertThat(sent.last()["method"]).isEqualTo(JsonPrimitive("node.pluginTools.update"))
                connection.receive(buildJsonObject {
                    put("type", "res")
                    put("id", checkNotNull(sent.last()["id"]))
                    put("ok", true)
                    put("payload", JsonObject(emptyMap()))
                })
            }
            assertThat(connection.phase.value).isEqualTo(OpenClawNodeConnection.Phase.Ready)
            connection.close()
            environment.close()
        }
        assertThat(file.readBytes().toString(Charsets.UTF_8)).doesNotContain("synthetic-device-token")
        assertThat(file.readBytes().toString(Charsets.UTF_8)).doesNotContain(Base64.getEncoder().encodeToString(seed))
    }
}
