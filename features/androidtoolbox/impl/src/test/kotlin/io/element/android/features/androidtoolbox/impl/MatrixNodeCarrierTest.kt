/*
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.androidtoolbox.impl

import com.google.common.truth.Truth.assertThat
import io.element.android.features.androidtoolbox.api.MatrixNodeCarrier
import io.element.android.features.androidtoolbox.api.OpenClawNodeSetup
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.Base64

class MatrixNodeCarrierTest {
    private val room = "!example:example.org"
    private val link = "00000000-0000-4000-8000-000000000001"
    private val frame = Json.parseToJsonElement("""{"type":"event","event":"connect.challenge","payload":{"nonce":"sample","ts":42}}""") as JsonObject

    @Test
    fun `open once native frames unchanged independent sequence and close`() {
        val carrier = MatrixNodeCarrier(room, link)
        assertThrows(IllegalStateException::class.java) { carrier.frame(frame) }
        assertThat(Json.parseToJsonElement(carrier.open())).isEqualTo(Json.parseToJsonElement(envelope("open")))
        assertThrows(IllegalStateException::class.java) { carrier.open() }
        assertThat(carrier.frame(frame)).isEqualTo(envelope("frame", 0))
        assertThat((carrier.receive(envelope("frame", 0)) as MatrixNodeCarrier.Incoming.Frame).value).isEqualTo(frame)
        assertThat(carrier.receive(envelope("frame", 0))).isEqualTo(MatrixNodeCarrier.Incoming.Ignored)
        assertThat(carrier.frame(frame)).isEqualTo(envelope("frame", 1))
        assertThat(carrier.close()).isEqualTo(envelope("close"))
        assertThat(carrier.close()).isNull()
        assertThat(carrier.receive(envelope("frame", 1))).isEqualTo(MatrixNodeCarrier.Incoming.Ignored)
    }

    @Test
    fun `wrong link is ignored but sequence gap ends this link`() {
        val carrier = MatrixNodeCarrier(room, link)
        carrier.open()
        assertThat(carrier.receive(envelope("frame", 0).replace(link, "00000000-0000-4000-8000-000000000002")))
            .isEqualTo(MatrixNodeCarrier.Incoming.Ignored)
        assertThat(carrier.receive(envelope("frame", 1))).isEqualTo(MatrixNodeCarrier.Incoming.Invalid)
        assertThat(carrier.receive(envelope("frame", 0))).isEqualTo(MatrixNodeCarrier.Incoming.Ignored)
    }

    @Test
    fun `unknown fields negative string and fractional sequence fail closed`() {
        val invalid = listOf(
            envelope("frame", -1),
            envelope("frame", 0).replace("\"seq\":0", "\"seq\":\"0\""),
            envelope("frame", 0).replace("\"seq\":0", "\"seq\":0.5"),
            envelope("open"),
            envelope("close").dropLast(1) + ",\"extra\":true}",
            "[]",
            "invalid",
        )
        invalid.forEach {
            val carrier = MatrixNodeCarrier(room, link)
            carrier.open()
            assertThat(carrier.receive(it)).isEqualTo(MatrixNodeCarrier.Incoming.Invalid)
        }
    }

    @Test
    fun `limit counts UTF8 bytes and an oversize outgoing frame does not consume sequence`() {
        val carrier = MatrixNodeCarrier(room, link)
        carrier.open()
        val large = JsonObject(mapOf("text" to JsonPrimitive("語".repeat(5_000))))
        assertThrows(IllegalArgumentException::class.java) { carrier.frame(large) }
        assertThat(carrier.frame(frame)).isEqualTo(envelope("frame", 0))
        assertThat(carrier.receive(envelope("frame", 0).replace("sample", "語".repeat(5_000))))
            .isEqualTo(MatrixNodeCarrier.Incoming.Invalid)
    }

    @Test
    fun `setup accepts native bootstrap only and never reports submitted secret in errors`() {
        fun code(json: String) = "oc-pair://" + Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray())
        val secret = "synthetic-bootstrap-secret"
        val valid = code("""{"url":"wss://example.org","bootstrapToken":"$secret"}""")
        assertThat(OpenClawNodeSetup.parse(valid).getOrThrow().bootstrapToken).isEqualTo(secret)
        listOf(
            code("""{"url":"wss://example.org","token":"$secret"}"""),
            code("""{"url":"wss://example.org","bootstrapToken":"$secret","password":"other"}"""),
            code("""{"bootstrapToken":"$secret"}"""),
            secret,
        ).forEach {
            val result = OpenClawNodeSetup.parse(it)
            assertThat(result.isFailure).isTrue()
            assertThat(result.exceptionOrNull().toString()).doesNotContain(secret)
        }
    }

    private fun envelope(kind: String, seq: Long? = null): String =
        """{"version":1,"room_id":"$room","link_id":"$link","kind":"$kind"""" +
            (seq?.let { ",\"seq\":$it,\"frame\":$frame" } ?: "") + "}"
}
