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
        val carrier = carrier()
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
        val carrier = carrier()
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
            val carrier = carrier()
            carrier.open()
            assertThat(carrier.receive(it)).isEqualTo(MatrixNodeCarrier.Incoming.Invalid)
        }
    }

    @Test
    fun `limit counts UTF8 bytes and an oversize outgoing frame does not consume sequence`() {
        val carrier = carrier()
        carrier.open()
        val large = JsonObject(mapOf("text" to JsonPrimitive("語".repeat(5_000))))
        assertThrows(IllegalArgumentException::class.java) { carrier.frame(large) }
        assertThat(carrier.frame(frame)).isEqualTo(envelope("frame", 0))
        assertThat(carrier.receive(envelope("frame", 0).replace("sample", "語".repeat(5_000))))
            .isEqualTo(MatrixNodeCarrier.Incoming.Invalid)
    }

    @Test
    fun `invoke deadline uses confirmed server time plus complete monotone elapsed time including delivery delay`() {
        var elapsed = 1_000L
        val carrier = carrier { elapsed }
        carrier.open()
        elapsed += 200
        carrier.receive(envelope("frame", 0))
        val incoming = carrier.receive(invokeEnvelope(1, "1000")) as MatrixNodeCarrier.Incoming.Frame
        assertThat(incoming.deadline!!.remainingMs()).isEqualTo(758)
        elapsed += 800
        assertThat(incoming.deadline!!.remainingMs()).isEqualTo(0)
        assertThat(carrier.receive(invokeEnvelope(2, "1000"))).isEqualTo(MatrixNodeCarrier.Incoming.Invalid)
    }

    @Test
    fun `missing string fractional unsafe and elapsed deadlines cannot reach the node adapter`() {
        for (expires in listOf(null, "\"1000\"", "1000.5", "9007199254740992", "42", "0", "-1")) {
            val carrier = carrier()
            carrier.open()
            carrier.receive(envelope("frame", 0))
            assertThat(carrier.receive(invokeEnvelope(1, expires))).isEqualTo(MatrixNodeCarrier.Incoming.Invalid)
        }
        val carrier = carrier()
        carrier.open()
        assertThat(carrier.receive(envelope("frame", 0).dropLast(1) + ",\"expires_at_ms\":1000}"))
            .isEqualTo(MatrixNodeCarrier.Incoming.Invalid)
    }

    @Test
    fun `replay cannot refresh original deadline and clock rollback grants no time`() {
        var elapsed = 100L
        val carrier = carrier { elapsed }
        carrier.open()
        carrier.receive(envelope("frame", 0))
        val event = invokeEnvelope(1, "1000")
        val accepted = carrier.receive(event) as MatrixNodeCarrier.Incoming.Frame
        elapsed += 100
        assertThat(carrier.receive(event)).isEqualTo(MatrixNodeCarrier.Incoming.Ignored)
        assertThat(accepted.deadline!!.remainingMs()).isEqualTo(858)
        elapsed = 0
        assertThat(accepted.deadline!!.remainingMs()).isEqualTo(0)
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

    private fun carrier(clock: () -> Long = { 0L }) = MatrixNodeCarrier(room, clock, link)

    private fun invokeEnvelope(seq: Long, expires: String?): String {
        val native = """{"type":"event","event":"node.invoke.request","payload":{"id":"sample-invoke"}}"""
        return envelope("frame", seq).replace(frame.toString(), native).dropLast(1) +
            (expires?.let { ",\"expires_at_ms\":$it" } ?: "") + "}"
    }

    private fun envelope(kind: String, seq: Long? = null): String =
        """{"version":1,"room_id":"$room","link_id":"$link","kind":"$kind"""" +
            (seq?.let { ",\"seq\":$it,\"frame\":$frame" } ?: "") + "}"
}
