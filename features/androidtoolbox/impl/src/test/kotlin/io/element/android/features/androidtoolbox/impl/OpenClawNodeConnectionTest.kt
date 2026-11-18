/*
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.androidtoolbox.impl

import com.google.common.truth.Truth.assertThat
import io.element.android.features.androidtoolbox.api.AndroidToolSession
import io.element.android.features.androidtoolbox.api.OpenClawDeviceAuthClaim
import io.element.android.features.androidtoolbox.api.OpenClawDeviceAuthEntry
import io.element.android.features.androidtoolbox.api.OpenClawDeviceAuthStore
import io.element.android.features.androidtoolbox.api.OpenClawDeviceIdentity
import io.element.android.features.androidtoolbox.api.OpenClawDeviceIdentityStore
import io.element.android.features.androidtoolbox.api.OpenClawDeviceProof
import io.element.android.features.androidtoolbox.api.OpenClawDeviceTokenSlot
import io.element.android.features.androidtoolbox.api.OpenClawInvokeDeadline
import io.element.android.features.androidtoolbox.api.OpenClawNodeConnection
import io.element.android.features.androidtoolbox.api.OpenClawNodeSetup
import io.element.android.features.androidtoolbox.api.OpenClawNodeTools
import io.element.android.features.androidtoolbox.test.RecordingAndroidToolLauncher
import io.element.android.tests.testutils.testCoroutineDispatchers
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Test
import java.util.Base64

class OpenClawNodeConnectionTest {
    @Test
    fun `native bootstrap challenge hello catalog invocation and result are a single authenticated flow`() = runTest {
        val fixture = Fixture(this)
        val node = fixture.node(setup = true)
        assertThat(fixture.sent).isEmpty()
        assertThat(fixture.store.reads).isEqualTo(0)
        node.receive(challenge())
        val connect = fixture.sent.single().params()
        assertThat(connect["role"]).isEqualTo(JsonPrimitive("node"))
        assertThat(connect["scopes"].toString()).isEqualTo("[]")
        assertThat(connect["minProtocol"]).isEqualTo(JsonPrimitive(4))
        assertThat(connect["auth"]?.jsonObject?.keys).containsExactly("bootstrapToken")
        assertThat(fixture.claim!!.token).isEqualTo("synthetic-bootstrap")
        assertThat(fixture.claim!!.signedAtMs).isEqualTo(1234)
        assertThat(fixture.claim!!.nonce).isEqualTo("challenge-nonce")
        assertThat(fixture.paired).isFalse()
        node.receive(fixture.response(hello()))
        assertThat(fixture.store.entry!!.token).isEqualTo("synthetic-device-token")
        assertThat(node.phase.value).isEqualTo(OpenClawNodeConnection.Phase.PublishingTools)
        assertThat(fixture.sent.last()["method"]).isEqualTo(JsonPrimitive("node.pluginTools.update"))
        assertThat(fixture.paired).isFalse()
        node.receive(fixture.response(json("{}")))
        assertThat(fixture.paired).isTrue()
        assertThat(node.phase.value).isEqualTo(OpenClawNodeConnection.Phase.Ready)
        node.receive(
            json(
                """{"type":"event","event":"node.invoke.request","payload":{"id":"invoke-1","nodeId":"sample-node","command":"maps.search","paramsJSON":"{\"query\":\"Sample museum\"}"}}"""
            ),
            OpenClawInvokeDeadline(10_000) { 1234 },
        )
        assertThat(fixture.launcher.submissions).hasSize(1)
        assertThat(fixture.sent.last()["method"]).isEqualTo(JsonPrimitive("node.invoke.result"))
        assertThat(fixture.sent.last().params()["id"]).isEqualTo(JsonPrimitive("invoke-1"))
        assertThat(fixture.sent.last().params()["ok"]).isEqualTo(JsonPrimitive(true))
    }

    @Test
    fun `reconnect uses deviceToken only and signs the exact same token`() = runTest {
        val fixture = Fixture(this)
        fixture.store.entry = OpenClawDeviceAuthEntry("saved-device-token", "node", emptyList(), 1)
        fixture.node(setup = false).receive(challenge())
        val auth = fixture.sent.single().params()["auth"]!!.jsonObject
        assertThat(auth.keys).containsExactly("deviceToken")
        assertThat(auth["deviceToken"]).isEqualTo(JsonPrimitive("saved-device-token"))
        assertThat(fixture.claim!!.token).isEqualTo("saved-device-token")
    }

    @Test
    fun `explicit setup cannot silently fall back to stored grant and no grant no reconnect`() = runTest {
        val fixture = Fixture(this)
        fixture.store.entry = OpenClawDeviceAuthEntry("old-grant", "node", emptyList(), 1)
        val node = fixture.node(setup = true)
        node.receive(challenge())
        assertThat(fixture.claim!!.token).isEqualTo("synthetic-bootstrap")
        assertThat(runCatching { node.receive(fixture.response(json("{}"), ok = false)) }.isFailure).isTrue()
        assertThat(fixture.sent).hasSize(1)
        assertThat(fixture.store.entry!!.token).isEqualTo("old-grant")
        val empty = Fixture(this)
        assertThat(runCatching { empty.node(setup = false).receive(challenge()) }.isFailure).isTrue()
        assertThat(empty.sent).isEmpty()
    }

    @Test
    fun `operator role scopes wrong protocol missing token failed durable write and failed catalog cannot pair`() = runTest {
        val invalid = listOf(
            hello().toString().replace("\"node\"", "\"operator\""),
            hello().toString().replace("\"scopes\":[]", "\"scopes\":[\"operator.admin\"]"),
            hello().toString().replace("\"protocol\":4", "\"protocol\":3"),
            """{"type":"hello-ok","protocol":4,"auth":{"role":"node","scopes":[]}}""",
        )
        invalid.forEach {
            val fixture = Fixture(this)
            val node = fixture.node(true)
            node.receive(challenge())
            assertThat(runCatching { node.receive(fixture.response(json(it))) }.isFailure).isTrue()
            assertThat(fixture.store.entry).isNull()
            assertThat(fixture.paired).isFalse()
        }
        val fixture = Fixture(this)
        fixture.store.failWrite = true
        val node = fixture.node(true)
        node.receive(challenge())
        assertThat(runCatching { node.receive(fixture.response(hello())) }.isFailure).isTrue()
        assertThat(fixture.paired).isFalse()
        assertThat(fixture.sent).hasSize(1)
        val rejectedCatalog = Fixture(this)
        val other = rejectedCatalog.node(true)
        other.receive(challenge())
        other.receive(rejectedCatalog.response(hello()))
        assertThat(runCatching { other.receive(rejectedCatalog.response(json("{}"), ok = false)) }.isFailure).isTrue()
        assertThat(rejectedCatalog.paired).isFalse()
    }

    @Test
    fun `binding invalidation while signing cannot send credentials and invocation before ready cannot launch`() = runTest {
        val fixture = Fixture(this)
        fixture.afterSign = { fixture.current = false }
        assertThat(runCatching { fixture.node(true).receive(challenge()) }.isFailure).isTrue()
        assertThat(fixture.sent).isEmpty()
        val other = Fixture(this)
        val node = other.node(true)
        val invocation = json("""{"type":"event","event":"node.invoke.request","payload":{}}""")
        assertThat(runCatching { node.receive(invocation) }.isFailure).isTrue()
        assertThat(other.launcher.submissions).isEmpty()
        node.close()
        assertThat(runCatching { node.receive(challenge()) }.isFailure).isTrue()
        assertThat(other.store.reads).isEqualTo(0)
    }

    private class Fixture(private val scope: TestScope) {
        val sent = mutableListOf<JsonObject>()
        val store = AuthStore()
        val launcher = RecordingAndroidToolLauncher()
        var paired = false
        var current = true
        var claim: OpenClawDeviceAuthClaim? = null
        var afterSign: () -> Unit = {}
        private val identities = object : OpenClawDeviceIdentityStore {
            override suspend fun loadOrCreate() = Result.success(OpenClawDeviceIdentity("sample-node", "sample-public-key"))
            override suspend fun signV3(claim: OpenClawDeviceAuthClaim): Result<OpenClawDeviceProof> {
                this@Fixture.claim = claim
                afterSign()
                return Result.success(OpenClawDeviceProof("sample-node", "sample-public-key", "sample-signature", claim.signedAtMs, claim.nonce))
            }
        }

        fun node(setup: Boolean): OpenClawNodeConnection {
            val code = Base64.getUrlEncoder().withoutPadding().encodeToString(
                """{"url":"wss://example.org","bootstrapToken":"synthetic-bootstrap"}""".toByteArray()
            )
            return OpenClawNodeConnection("sample-gateway", "test", identities, store, { id ->
                OpenClawNodeTools(id, AndroidToolSession(DefaultAndroidToolbox(setOf(GoogleMapsToolModule()), scope.testCoroutineDispatchers()), launcher) {
                    if (current) setOf("maps") else emptySet()
                })
            }, if (setup) OpenClawNodeSetup.parse(code).getOrThrow() else null, { current }, { sent.add(it) }, { paired = true })
        }

        fun response(payload: JsonObject, ok: Boolean = true) = buildJsonObject {
            put("type", "res")
            put("id", checkNotNull(sent.last()["id"]))
            put("ok", ok)
            put("payload", payload)
        }
    }

    private class AuthStore : OpenClawDeviceAuthStore {
        var entry: OpenClawDeviceAuthEntry? = null
        var reads = 0
        var failWrite = false
        override suspend fun loadEntry(slot: OpenClawDeviceTokenSlot): Result<OpenClawDeviceAuthEntry?> {
            reads++
            return Result.success(entry)
        }

        override suspend fun saveToken(slot: OpenClawDeviceTokenSlot, token: String, scopes: List<String>, replacesStoredToken: String?): Result<Boolean> {
            if (failWrite) return Result.failure(IllegalStateException("Test write failure."))
            if (entry?.token != replacesStoredToken) return Result.success(false)
            entry = OpenClawDeviceAuthEntry(token, slot.role, scopes, 1)
            return Result.success(true)
        }

        override suspend fun clearToken(slot: OpenClawDeviceTokenSlot, onlyIfToken: String?) = Result.success(false)
    }

    companion object {
        private fun json(value: String) = Json.parseToJsonElement(value) as JsonObject
        private fun challenge() = json("""{"type":"event","event":"connect.challenge","payload":{"nonce":"challenge-nonce","ts":1234}}""")
        private fun hello() = json("""{"type":"hello-ok","protocol":4,"auth":{"role":"node","scopes":[],"deviceToken":"synthetic-device-token"}}""")
        private fun JsonObject.params() = get("params")!!.jsonObject
    }
}
