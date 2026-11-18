/*
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.androidtoolbox.impl

import com.google.common.truth.Truth.assertThat
import io.element.android.features.androidtoolbox.api.AndroidToolSession
import io.element.android.features.androidtoolbox.api.OpenClawInvokeDeadline
import io.element.android.features.androidtoolbox.api.OpenClawNodeTools
import io.element.android.features.androidtoolbox.test.RecordingAndroidToolLauncher
import io.element.android.tests.testutils.testCoroutineDispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Test

class OpenClawNodeToolsTest {
    @Test
    fun `native catalog and invoke produce a real Maps handoff result`() = runTest {
        val launcher = RecordingAndroidToolLauncher()
        val session = AndroidToolSession(DefaultAndroidToolbox(setOf(GoogleMapsToolModule()), testCoroutineDispatchers()), launcher) { setOf("maps") }
        val node = OpenClawNodeTools("sample-node", session)
        val catalog = node.catalog()["tools"]!!.jsonArray
        assertThat(catalog).hasSize(3)
        val descriptor = catalog.first().jsonObject
        assertThat(descriptor.keys).containsExactly("pluginId", "name", "command", "description", "parameters")
        assertThat(descriptor["pluginId"]).isEqualTo(JsonPrimitive("android-toolbox"))

        val request = request()
        val result = checkNotNull(node.invoke(request))
        assertThat(result.keys).containsExactly("id", "nodeId", "ok", "payload")
        assertThat(result["id"]).isEqualTo(request["id"])
        assertThat(result["nodeId"]).isEqualTo(request["nodeId"])
        assertThat(result["ok"]).isEqualTo(JsonPrimitive(true))
        assertThat(result["payload"]?.jsonObject?.get("status")).isEqualTo(JsonPrimitive("submitted"))
        assertThat(node.invoke(request)).isEqualTo(result)
        assertThat(launcher.submissions).hasSize(1)
    }

    @Test
    fun `native idempotency key prevents relaunch under a new invocation ID`() = runTest {
        val launcher = RecordingAndroidToolLauncher()
        val session = AndroidToolSession(DefaultAndroidToolbox(setOf(GoogleMapsToolModule()), testCoroutineDispatchers()), launcher) { setOf("maps") }
        val node = OpenClawNodeTools("sample-node", session)
        node.invoke(request())
        val retry = checkNotNull(node.invoke(request("retry")))
        assertThat(retry["id"]).isEqualTo(JsonPrimitive("retry"))
        assertThat(retry["ok"]).isEqualTo(JsonPrimitive(true))
        val changed = request() + ("command" to JsonPrimitive("maps.navigate"))
        assertThat(node.invoke(JsonObject(changed))?.get("ok")).isEqualTo(JsonPrimitive(false))
        assertThat(launcher.submissions).hasSize(1)
    }

    @Test
    fun `wrong node invalid fields and expired deadline never launch`() = runTest {
        val launcher = RecordingAndroidToolLauncher()
        val session = AndroidToolSession(DefaultAndroidToolbox(setOf(GoogleMapsToolModule()), testCoroutineDispatchers()), launcher) { setOf("maps") }
        val node = OpenClawNodeTools("sample-node", session)
        val invalid = listOf(
            request() + ("nodeId" to JsonPrimitive("other-node")),
            request() + ("paramsJSON" to JsonPrimitive("[]")),
            request() + ("paramsJSON" to JsonPrimitive("broken")),
            request() + ("paramsJSON" to JsonPrimitive(" ".repeat(16_385))),
            request() + ("timeoutMs" to JsonPrimitive(-1)),
            request() + ("timeoutMs" to JsonPrimitive("1000")),
            request() + ("sessionKey" to JsonPrimitive(false)),
            request() + ("approved" to JsonPrimitive(true)),
        )
        invalid.forEach { assertThat(node.invoke(JsonObject(it))?.get("ok")).isEqualTo(JsonPrimitive(false)) }
        assertThat(node.invoke(JsonObject(emptyMap()))).isNull()
        val expired = request() + ("timeoutMs" to JsonPrimitive(0))
        assertThat(node.invoke(JsonObject(expired), OpenClawInvokeDeadline(1) { 1 })?.get("error")?.jsonObject?.get("code"))
            .isEqualTo(JsonPrimitive("TIMEOUT"))
        assertThat(launcher.submissions).isEmpty()
    }

    @Test
    fun `native zero timeout remains unbounded under a finite transport ceiling and null params parse normally`() = runTest {
        val launcher = RecordingAndroidToolLauncher()
        val session = AndroidToolSession(DefaultAndroidToolbox(setOf(GoogleMapsToolModule()), testCoroutineDispatchers()), launcher) { setOf("maps") }
        val node = OpenClawNodeTools("sample-node", session)
        val noNativeTimeout = JsonObject(request() + ("timeoutMs" to JsonPrimitive(0)))
        assertThat(node.invoke(noNativeTimeout, OpenClawInvokeDeadline(100) { 1 })?.get("ok")).isEqualTo(JsonPrimitive(true))
        val nullParams = JsonObject(request("null-params") + ("paramsJSON" to JsonNull) + ("idempotencyKey" to JsonPrimitive("null-op")))
        val error = node.invoke(nullParams)?.get("error")?.jsonObject
        assertThat(error?.get("code")).isEqualTo(JsonPrimitive("INVALID_PARAMS"))
        assertThat(launcher.submissions).hasSize(1)
    }

    private fun request(id: String = "invocation") = buildJsonObject {
        put("id", id)
        put("nodeId", "sample-node")
        put("command", "maps.search")
        put("paramsJSON", """{"query":"Sample museum"}""")
        put("idempotencyKey", "operation")
        put("sessionKey", "agent:example:assistant")
    }
}
