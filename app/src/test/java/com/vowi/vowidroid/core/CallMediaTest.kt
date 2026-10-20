package com.vowi.vowidroid.core

import com.vowi.vowidroid.core.call.AmrPayload
import com.vowi.vowidroid.core.call.G711Factory
import com.vowi.vowidroid.core.call.MediaDirection
import com.vowi.vowidroid.core.call.MediaDirection.INACTIVE
import com.vowi.vowidroid.core.call.MediaDirection.RECVONLY
import com.vowi.vowidroid.core.call.MediaDirection.SENDONLY
import com.vowi.vowidroid.core.call.MediaDirection.SENDRECV
import com.vowi.vowidroid.core.call.RtpMedia
import com.vowi.vowidroid.core.call.Sdp
import com.vowi.vowidroid.core.net.NetStack
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** SDP offer/answer for hold (RFC 3264, TS 24.610) and the hold keepalive payloads. */
class CallMediaTest {
    private val address = byteArrayOf(10, 0, 0, 2)
    private val stack = NetStack(address, null) { }
    private val media = RtpMedia(stack, address, G711Factory)

    @After
    fun tearDown() {
        media.close()
        stack.close()
    }

    private fun direction(sdp: ByteArray): MediaDirection = Sdp.parse(sdp).direction

    @Test
    fun parsesDirectionsAtSessionAndMediaLevel() {
        assertEquals(SENDRECV, direction(FakePcscf.sdp("sendrecv")))
        assertEquals(INACTIVE, direction(FakePcscf.sdp("inactive")))
        val sessionLevel = "v=0\r\nc=IN IP4 10.0.0.1\r\na=sendonly\r\nm=audio 4000 RTP/AVP 0\r\n".toByteArray()
        assertEquals(SENDONLY, direction(sessionLevel))
        val mediaWins = "v=0\r\nc=IN IP4 10.0.0.1\r\na=sendonly\r\nm=audio 4000 RTP/AVP 0\r\na=recvonly\r\n".toByteArray()
        assertEquals(RECVONLY, direction(mediaWins))
        // RFC 2543 hold: 0.0.0.0 wants nothing sent to it.
        assertEquals(SENDONLY, direction(FakePcscf.sdp("sendrecv", address = "0.0.0.0")))
        assertEquals(SENDONLY.reversed, RECVONLY)
        assertEquals(INACTIVE, SENDONLY and RECVONLY)
    }

    @Test
    fun answersFollowRfc3264() {
        val live = mapOf("sendrecv" to SENDRECV, "sendonly" to RECVONLY, "recvonly" to SENDONLY, "inactive" to INACTIVE)
        for ((offered, expected) in live) {
            media.applyOffer(FakePcscf.sdp(offered))
            assertEquals(offered, expected, direction(media.answerSdp()))
            assertEquals(offered, expected, media.direction)
        }
        // While we hold, we never take media in.
        media.localHold = true
        val held = mapOf("sendrecv" to SENDONLY, "sendonly" to INACTIVE, "recvonly" to SENDONLY, "inactive" to INACTIVE)
        for ((offered, expected) in held) {
            media.applyOffer(FakePcscf.sdp(offered))
            assertEquals("held, offered $offered", expected, direction(media.answerSdp()))
        }
    }

    @Test
    fun offersFollowTs24610() {
        media.applyOffer(FakePcscf.sdp("sendrecv"))
        assertEquals(SENDRECV, direction(media.reofferSdp()))
        media.localHold = true
        assertEquals(SENDONLY, direction(media.reofferSdp()))
        // The far end holds us as well: inactive.
        media.applyOffer(FakePcscf.sdp("sendonly"))
        assertEquals(INACTIVE, direction(media.reofferSdp()))
        // We resume while still held: recvonly.
        media.localHold = false
        assertEquals(RECVONLY, direction(media.reofferSdp()))
        // An answer narrows our offer: we offered recvonly, the far end answers sendonly.
        media.applyAnswer(FakePcscf.sdp("sendonly"))
        assertEquals(RECVONLY, media.direction)
    }

    @Test
    fun oldStyleHoldKeepsTheAddressAndCodec() {
        media.applyOffer(FakePcscf.sdp("sendrecv"))
        media.applyOffer(FakePcscf.sdp("sendrecv", address = "0.0.0.0"))
        assertEquals(RECVONLY, media.direction)
        assertEquals(RECVONLY, direction(media.answerSdp()))
        assertEquals("PCMU", media.codecName)
        assertTrue(media.ready)
    }

    @Test
    fun everySdpKeepsOneSessionAndRaisesTheVersion() {
        val first = origin(media.offerSdp())
        media.applyAnswer(FakePcscf.sdp("sendrecv"))
        val second = origin(media.reofferSdp())
        val third = origin(media.answerSdp())
        assertEquals(first.first, second.first)
        assertEquals(first.first, third.first)
        assertEquals(first.second + 1, second.second)
        assertEquals(second.second + 1, third.second)
    }

    @Test
    fun reoffersKeepTheRunningCodec() {
        media.applyOffer(FakePcscf.sdp("sendrecv", formats = "8 0 101"))
        assertEquals("PCMA", media.codecName)
        // A later offer that puts PCMU first still keeps PCMA running.
        media.applyOffer(FakePcscf.sdp("sendonly", formats = "0 8 101"))
        assertEquals("PCMA", media.codecName)
        assertTrue(String(media.answerSdp()).contains("m=audio ${media.localPort} RTP/AVP 8 101\r\n"))
        assertTrue(String(media.reofferSdp()).contains("m=audio ${media.localPort} RTP/AVP 8 101\r\n"))
        // A fresh offer lists every codec.
        assertTrue(String(media.offerSdp()).contains("m=audio ${media.localPort} RTP/AVP 8 0 100\r\n"))
    }

    @Test
    fun amrNoDataPayloads() {
        val octetAligned = AmrPayload.noData(wideband = false, octetAligned = true)
        assertArrayEquals(byteArrayOf(0xF0.toByte(), 0x7C), octetAligned)
        assertArrayEquals(byteArrayOf(0xF7.toByte(), 0xC0.toByte()), AmrPayload.noData(wideband = true, octetAligned = false))
        val frames = AmrPayload.unpack(octetAligned, wideband = false, octetAligned = true)
        assertEquals(1, frames.size)
        assertEquals(15, (frames[0][0].toInt() ushr 3) and 0x0f)
    }

    private fun origin(sdp: ByteArray): Pair<Long, Long> {
        val fields = String(sdp, Charsets.ISO_8859_1).lines().first { it.startsWith("o=") }.split(' ')
        return fields[1].toLong() to fields[2].toLong()
    }
}
