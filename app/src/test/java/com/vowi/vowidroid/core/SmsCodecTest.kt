package com.vowi.vowidroid.core

import com.vowi.vowidroid.core.sms.SmsDirection
import com.vowi.vowidroid.core.sms.SmsEncoding
import com.vowi.vowidroid.core.sms.SmsOverIms
import com.vowi.vowidroid.core.sms.SmsPdu
import com.vowi.vowidroid.core.util.hexToBytes
import com.vowi.vowidroid.core.util.toHex
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import com.vowi.vowidroid.core.sms.RpCauses
import org.junit.Test
import java.io.InputStreamReader

/** The SMS TPDU and RP codecs against known-answer vectors (`sms_vectors.json`). */
class SmsCodecTest {
    private val vectors: JsonObject by lazy {
        val stream = SmsCodecTest::class.java.classLoader!!.getResourceAsStream("sms_vectors.json")!!
        InputStreamReader(stream, Charsets.UTF_8).use { JsonParser.parseReader(it).asJsonObject }
    }

    @Test
    fun submitTpdusMatchVectors() {
        for (element in vectors.getAsJsonArray("submit")) {
            val vector = element.asJsonObject
            val parts = SmsPdu.prepareSubmit(vector.get("to").asString, vector.get("text").asString)
            val expected = vector.getAsJsonArray("parts").map { it.asJsonObject }
            assertEquals(vector.get("text").asString, expected.size, parts.size)
            for ((index, part) in parts.withIndex()) {
                val want = expected[index]
                assertEquals(want.get("encoding").asString, part.encoding.wire)
                assertEquals(want.get("part").asInt, part.part)
                assertEquals(want.get("total").asInt, part.total)
                val wanted = want.get("tpdu").asString.hexToBytes()
                val got = part.tpdu.copyOf()
                if (parts.size > 1) {
                    // The concatenation reference is random on both sides.
                    val digits = got[2].toInt() and 0xff
                    val referenceOffset = (digits + 1) / 2 + 10
                    wanted[referenceOffset] = 0
                    got[referenceOffset] = 0
                }
                assertEquals("part ${index + 1} of ${vector.get("to").asString}", wanted.toHex(), got.toHex())
            }
        }
    }

    @Test
    fun deliverDecodingMatchesVectors() {
        for (element in vectors.getAsJsonArray("deliver")) {
            val vector = element.asJsonObject
            val tpdu = vector.get("tpdu").asString
            val message = SmsPdu.decodeDeliver(tpdu.hexToBytes())
            val expectedError = vector.get("error").asString
            if (expectedError.isNotEmpty()) {
                assertTrue(tpdu, message.decodeError.isNotEmpty())
                continue
            }
            assertEquals(tpdu, "", message.decodeError)
            val direction = when (vector.get("direction").asString) {
                "received" -> SmsDirection.RECEIVED
                "status_report" -> SmsDirection.STATUS_REPORT
                "submitted" -> SmsDirection.SUBMITTED
                else -> SmsDirection.UNKNOWN
            }
            assertEquals(tpdu, direction, message.direction)
            assertEquals(tpdu, vector.get("from").asString, message.from)
            assertEquals(tpdu, vector.get("to").asString, message.to)
            assertEquals(tpdu, vector.get("text").asString, message.text)
            if (direction != SmsDirection.STATUS_REPORT) assertEquals(tpdu, vector.get("encoding").asString, message.encoding.wire)
            if (vector.has("scts")) assertEquals(tpdu, vector.get("scts").asLong, message.serviceCenterTimestamp)
            if (vector.has("concat")) {
                val concat = vector.getAsJsonObject("concat")
                assertNotNull(message.concat)
                assertEquals(concat.get("reference").asInt, message.concat!!.reference)
                assertEquals(concat.get("total").asInt, message.concat!!.total)
                assertEquals(concat.get("sequence").asInt, message.concat!!.sequence)
            } else {
                assertNull(message.concat)
            }
            if (vector.has("reference")) assertEquals(vector.get("reference").asInt, message.messageReference)
            if (vector.has("status")) {
                assertEquals(vector.get("status").asInt, message.statusCode)
                assertEquals(vector.get("deliveryStatus").asString, message.deliveryStatus)
            }
        }
    }

    @Test
    fun gsm7PackingRoundTrips() {
        val text = "Hello, world! {[]} ~€ Ñandù"
        val septets = SmsPdu.encodeGsm7(text)!!
        val packed = SmsPdu.packSeptets(septets, 0)
        assertArrayEquals(septets, SmsPdu.unpackSeptets(packed, septets.size, 0))
        assertEquals(text, SmsPdu.decodeGsm7(septets))
        assertNull(SmsPdu.encodeGsm7("你好"))
        val withHeader = SmsPdu.packSeptets(septets, 7)
        assertArrayEquals(septets, SmsPdu.unpackSeptets(withHeader, septets.size, 7))
    }

    @Test
    fun rpLayerRoundTrips() {
        val tpdu = "0405912143F500004210203040500005C82293F904".hexToBytes()
        val rpdu = SmsOverIms.buildRpData(7, "+12063130004", tpdu)
        assertEquals(0, rpdu[0].toInt())
        assertEquals(7, rpdu[1].toInt())
        // Mobile-originated RP-DATA is type 0; only the network's type 1 carries a TPDU for us.
        val parsed = SmsOverIms.parseRpdu(rpdu)
        assertEquals(0, parsed.messageType)
        assertEquals(7, parsed.reference)
        // RP-DATA from the network: originator address present, destination empty.
        val inbound = "01 05 04 91 21 43 f5 00 ${tpdu.size.toString(16).padStart(2, '0')} ${tpdu.toHex()}".replace(" ", "").hexToBytes()
        val fromNetwork = SmsOverIms.parseRpdu(inbound)
        assertEquals(1, fromNetwork.messageType)
        assertArrayEquals(tpdu, fromNetwork.tpdu)
        assertArrayEquals(byteArrayOf(0x02, 5), SmsOverIms.buildRpAck(5))
        assertArrayEquals(byteArrayOf(0x04, 5, 0x01, 95), SmsOverIms.buildRpError(5, 95))
        assertEquals("912143658709", SmsOverIms.encodeRpAddress("+1234567890").toHex())
        assertEquals("8121436587f9", SmsOverIms.encodeRpAddress("123456789").toHex())
        assertEquals("+12345", SmsOverIms.normalizeE164(" +1 (23) 45 "))
    }

    @Test
    fun multipartBodiesYieldTheSmsPart() {
        val body = ("--b1\r\nContent-Type: text/plain\r\n\r\nignored\r\n--b1\r\nContent-Type: application/vnd.3gpp.sms\r\n" +
            "Content-Transfer-Encoding: base64\r\n\r\nAQIDBA==\r\n--b1--\r\n").toByteArray()
        val parts = SmsOverIms.multipart(body, "b1")
        assertEquals(2, parts.size)
        assertEquals("application/vnd.3gpp.sms", parts[1].first["content-type"])
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), SmsOverIms.decodeTransfer(parts[1].second, "base64"))
        assertTrue(SmsOverIms.isSmsContentType("multipart/mixed; boundary=\"b1\""))
        assertTrue(SmsOverIms.isSmsContentType("application/vnd.3gpp.sms"))
        assertTrue(!SmsOverIms.isSmsContentType("application/vnd.3gpp.ussd"))
    }

    @Test
    fun measuresTextsAsTheyWillBeSplit() {
        val short = SmsPdu.measure("Hello")
        assertEquals(SmsEncoding.GSM7, short.encoding)
        assertEquals(1, short.parts)
        assertEquals(155, short.remaining)
        // Extension characters such as € take two septets.
        assertEquals(158, SmsPdu.measure("€").remaining)
        val full = SmsPdu.measure("a".repeat(160))
        assertEquals(1, full.parts)
        assertEquals(0, full.remaining)
        val long = SmsPdu.measure("a".repeat(161))
        assertEquals(2, long.parts)
        assertEquals(153 * 2 - 161, long.remaining)
        // One character outside GSM-7 makes the whole text UCS-2: 70 per SMS, 67 per part.
        val unicode = SmsPdu.measure("Hi 你好")
        assertEquals(SmsEncoding.UCS2, unicode.encoding)
        assertEquals(70 - 5, unicode.remaining)
        assertEquals(2, SmsPdu.measure("你".repeat(71)).parts)
        assertEquals(0, SmsPdu.measure("").parts)
        // The measure agrees with how a text is really split.
        val text = "Long text " + "x".repeat(300)
        assertEquals(SmsPdu.prepareSubmit("+15551234567", text).size, SmsPdu.measure(text).parts)
    }

    @Test
    fun theCentresAnswerToASubmittedTextIsRead() {
        // RP-ERROR (TS 24.011 7.3.4): type 5, the reference, then the RP-Cause with its extension bit set.
        val refused = SmsOverIms.parseRpdu(byteArrayOf(0x05, 7, 0x01, (0x80 or 38).toByte()))
        assertEquals(SmsOverIms.RP_ERROR, refused.messageType)
        assertEquals(7, refused.reference)
        assertEquals(38, refused.cause)
        assertEquals("network out of order", RpCauses.name(38))
        assertEquals("cause 5", RpCauses.name(5))
        // RP-ACK (7.3.3) carries only the reference; an RP-ERROR without a cause reads as none.
        val acknowledged = SmsOverIms.parseRpdu(byteArrayOf(0x03, 9))
        assertEquals(SmsOverIms.RP_ACK, acknowledged.messageType)
        assertEquals(9, acknowledged.reference)
        assertNull(acknowledged.cause)
        assertNull(SmsOverIms.parseRpdu(byteArrayOf(0x05, 7)).cause)
    }
}
