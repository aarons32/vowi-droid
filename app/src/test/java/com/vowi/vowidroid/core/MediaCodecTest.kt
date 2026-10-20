package com.vowi.vowidroid.core

import com.vowi.vowidroid.core.call.AmrPayload
import com.vowi.vowidroid.core.call.G711
import com.vowi.vowidroid.core.call.G711Codec
import com.vowi.vowidroid.core.call.Sdp
import com.vowi.vowidroid.core.util.toHex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class MediaCodecTest {
    @Test
    fun g711RoundTripsWithinQuantisation() {
        val samples = ShortArray(160) { index -> (10000 * kotlin.math.sin(index / 7.0)).toInt().toShort() }
        for (aLaw in listOf(true, false)) {
            val codec = G711Codec(aLaw)
            val decoded = codec.decode(codec.encode(samples))
            assertEquals(160, decoded.size)
            for (index in samples.indices) assertTrue("${codec.name} sample $index", abs(samples[index] - decoded[index]) < 400)
        }
        // ITU reference points: 0 encodes to 0xff (mu-law) and 0xd5 (A-law).
        assertEquals(0xff, G711.linearToMuLaw(0).toInt() and 0xff)
        assertEquals(0xd5, G711.linearToALaw(0).toInt() and 0xff)
        assertEquals(0, G711.muLawToLinear(0xff.toByte()).toInt())
        assertEquals(8, G711.aLawToLinear(0xd5.toByte()).toInt())
    }

    @Test
    fun amrOctetAlignedPayloadRoundTrips() {
        // One AMR-NB 12.2 kbit/s frame (FT 7, 31 bytes) and one AMR-WB 23.85 frame (FT 8, 60 bytes).
        val nbFrame = ByteArray(32) { if (it == 0) 0x3c else (it * 7).toByte() }
        val payload = AmrPayload.pack(listOf(nbFrame), wideband = false, octetAligned = true)
        assertEquals(1 + 1 + 31, payload.size)
        assertEquals(0xf0, payload[0].toInt() and 0xff) // CMR 15
        assertEquals(0x3c, payload[1].toInt() and 0xff) // F=0 FT=7 Q=1
        val frames = AmrPayload.unpack(payload, wideband = false, octetAligned = true)
        assertEquals(1, frames.size)
        assertArrayEquals(nbFrame, frames[0])

        val wbFrame = ByteArray(61) { if (it == 0) 0x44 else (it * 3).toByte() }
        val wbPayload = AmrPayload.pack(listOf(wbFrame, wbFrame), wideband = true, octetAligned = true)
        assertEquals(1 + 2 + 120, wbPayload.size)
        assertEquals(0xc4, wbPayload[1].toInt() and 0xff) // F=1 FT=8 Q=1
        val wbFrames = AmrPayload.unpack(wbPayload, wideband = true, octetAligned = true)
        assertEquals(2, wbFrames.size)
        assertArrayEquals(wbFrame, wbFrames[1])
    }

    @Test
    fun amrBandwidthEfficientPayloadRoundTrips() {
        // FT 7 carries 244 bits; the payload is 4 + 6 + 244 bits = 254 bits = 32 bytes.
        val nbFrame = ByteArray(32) { if (it == 0) 0x3c else (0xa5 + it).toByte() }
        nbFrame[31] = (nbFrame[31].toInt() and 0xf0).toByte() // 244 bits leave the last nibble unused
        val payload = AmrPayload.pack(listOf(nbFrame), wideband = false, octetAligned = false)
        assertEquals(32, payload.size)
        assertEquals(0xf3, payload[0].toInt() and 0xff) // CMR 1111, F 0, FT 011(1)...
        val frames = AmrPayload.unpack(payload, wideband = false, octetAligned = false)
        assertEquals(1, frames.size)
        assertEquals(nbFrame.toHex(), frames[0].toHex())
        // AMR-WB 6.6 kbit/s (FT 0, 132 bits) also survives.
        val wbFrame = ByteArray(18) { if (it == 0) 0x04 else (it * 11).toByte() }
        wbFrame[17] = (wbFrame[17].toInt() and 0xf0).toByte()
        val wbFrames = AmrPayload.unpack(AmrPayload.pack(listOf(wbFrame), wideband = true, octetAligned = false), wideband = true, octetAligned = false)
        assertEquals(wbFrame.toHex(), wbFrames[0].toHex())
    }

    @Test
    fun sdpParsingFindsTheAudioLine() {
        val body = ("v=0\r\no=- 1 1 IN IP4 10.0.0.9\r\ns=-\r\nc=IN IP4 10.0.0.9\r\nt=0 0\r\n" +
            "m=video 4000 RTP/AVP 96\r\na=rtpmap:96 H264/90000\r\n" +
            "m=audio 30000 RTP/AVP 104 8 100\r\nc=IN IP4 10.0.0.10\r\na=rtpmap:104 AMR-WB/16000\r\na=fmtp:104 mode-set=0,1,2;octet-align=1\r\n" +
            "a=rtpmap:8 PCMA/8000\r\na=rtpmap:100 telephone-event/8000\r\na=ptime:20\r\n").toByteArray()
        val line = Sdp.parse(body)
        assertArrayEquals(byteArrayOf(10, 0, 0, 10), line.address)
        assertEquals(30000, line.port)
        assertEquals(listOf(104, 8, 100), line.formats)
        assertEquals("AMR-WB", Sdp.codecName(line, 104))
        assertEquals("PCMA", Sdp.codecName(line, 8))
        assertEquals("TELEPHONE-EVENT", Sdp.codecName(line, 100))
        assertEquals(16000, line.rates[104])
        assertEquals("mode-set=0,1,2;octet-align=1", line.fmtp[104])
    }
}
