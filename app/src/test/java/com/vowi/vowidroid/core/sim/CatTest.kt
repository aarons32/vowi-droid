package com.vowi.vowidroid.core.sim

import com.vowi.vowidroid.core.util.hexToBytes
import com.vowi.vowidroid.core.util.toHex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The card toolkit as a reader-driven card sees it: profile, data download, and the card's own requests. */
class CatTest {
    /** A card answering from a script, one step per command; after the script, plain success. */
    private class ScriptedCard : SmartCard {
        val calls = ArrayList<ByteArray>()
        val script = ArrayDeque<(ByteArray) -> ApduResponse>()
        var powerCycles = 1

        override fun transmit(command: ByteArray): ApduResponse {
            calls += command
            val step = script.removeFirstOrNull() ?: return ok()
            return step(command)
        }
    }

    private companion object {
        fun ok(data: ByteArray = ByteArray(0)) = ApduResponse(data, 0x9000)
        val CENTRE = "91887766554433".hexToBytes()
        val SUBMIT = "01000a91214365870900000568656c6c6f".hexToBytes()
        fun proactive(body: String): ByteArray = ("d0" + "%02x".format(body.length / 2) + body).hexToBytes()
    }

    @Test
    fun theTerminalProfileGoesOutOnceAfterEachPowerOn() {
        val card = ScriptedCard()
        val toolkit = ToolkitCard(card) { card.powerCycles }
        toolkit.transmit(byteArrayOf(0x00, 0xa4.toByte(), 0x00, 0x0c, 0x02, 0x3f, 0x00))
        toolkit.transmit(byteArrayOf(0x00, 0xb0.toByte(), 0x00, 0x00, 0x0a))
        assertArrayEquals(Cat.TERMINAL_PROFILE, card.calls[0])
        assertEquals(3, card.calls.size)
        card.powerCycles++
        toolkit.transmit(byteArrayOf(0x00, 0xb0.toByte(), 0x00, 0x00, 0x0a))
        assertArrayEquals(Cat.TERMINAL_PROFILE, card.calls[3])
        assertTrue(toolkit.toolkit)
    }

    @Test
    fun aCardThatRefusesTheProfileIsLeftAlone() {
        val card = ScriptedCard()
        card.script.add { ApduResponse(ByteArray(0), 0x6d00) }
        val toolkit = ToolkitCard(card) { 1 }
        val response = toolkit.transmit(byteArrayOf(0x00, 0xb0.toByte(), 0x00, 0x00, 0x0a))
        assertEquals(0x9000, response.sw)
        assertFalse(toolkit.toolkit)
    }

    @Test
    fun aDataDownloadIsEnvelopedAndTheCardsAnswerComesBack() {
        val card = ScriptedCard()
        card.script.add { ok() }
        card.script.add { ApduResponse("0102".hexToBytes(), 0x9000) }
        val toolkit = ToolkitCard(card) { 1 }
        val tpdu = "0411223344".hexToBytes()
        val result = Cat.smsDownload(toolkit, CENTRE, tpdu)
        assertTrue(result.accepted)
        assertArrayEquals("0102".hexToBytes(), result.data)
        val envelope = card.calls[1]
        assertEquals("80c20000", envelope.copyOf(4).toHex())
        val body = "82028381" + "06" + "%02x".format(CENTRE.size) + CENTRE.toHex() + "8b" + "%02x".format(tpdu.size) + tpdu.toHex()
        assertEquals("d1" + "%02x".format(body.length / 2) + body, envelope.copyOfRange(5, envelope.size).toHex())
    }

    @Test
    fun theCardsErrorAndBusyAnswersAreTold() {
        val card = ScriptedCard()
        card.script.add { ok() }
        card.script.add { ApduResponse(ByteArray(0), 0x9e02) }
        card.script.add { command ->
            assertEquals("00c0000002", command.toHex())
            ApduResponse("0900".hexToBytes(), 0x9000)
        }
        card.script.add { ApduResponse(ByteArray(0), 0x9300) }
        val toolkit = ToolkitCard(card) { 1 }
        val refused = Cat.smsDownload(toolkit, CENTRE, "0411".hexToBytes())
        assertFalse(refused.accepted)
        assertFalse(refused.busy)
        assertArrayEquals("0900".hexToBytes(), refused.data)
        assertTrue(Cat.smsDownload(toolkit, CENTRE, "0411".hexToBytes()).busy)
    }

    @Test
    fun aPendingRequestToSendATextIsServedAndAnswered() {
        val command = proactive("810301130082028183" + "06" + "%02x".format(CENTRE.size) + CENTRE.toHex() + "8b" + "%02x".format(SUBMIT.size) + SUBMIT.toHex())
        val card = ScriptedCard()
        card.script.add { ok() }
        card.script.add { ApduResponse(ByteArray(0), 0x9100 or command.size) }
        card.script.add { fetch ->
            assertEquals("80120000" + "%02x".format(command.size), fetch.toHex())
            ok(command)
        }
        card.script.add { response ->
            assertEquals("801400000c" + "810301130082028281830100", response.toHex())
            ok()
        }
        var sent: Pair<ByteArray, ByteArray>? = null
        val toolkit = ToolkitCard(card) { 1 }
        toolkit.handler = object : ProactiveHandler {
            override fun sendSms(serviceCentre: ByteArray, tpdu: ByteArray): Boolean {
                sent = serviceCentre to tpdu
                return true
            }

            override fun refresh(qualifier: Int) = Unit
        }
        val result = Cat.smsDownload(toolkit, ByteArray(0), "0411".hexToBytes())
        assertTrue(result.accepted)
        assertArrayEquals(CENTRE, sent!!.first)
        assertArrayEquals(SUBMIT, sent!!.second)
        assertEquals(4, card.calls.size)
    }

    @Test
    fun whatTheTerminalCannotDoIsTurnedDownAndARefreshIsPassedOn() {
        var refreshed = -1
        val handler = object : ProactiveHandler {
            override fun sendSms(serviceCentre: ByteArray, tpdu: ByteArray): Boolean = true
            override fun refresh(qualifier: Int) {
                refreshed = qualifier
            }
        }
        val menu = Cat.respond(proactive("81030125008202818200"), handler)
        assertEquals("801400000c" + "810301250082028281830130", menu.toHex())
        val refresh = Cat.respond(proactive("8103010104820281820c"), handler)
        assertEquals("801400000c" + "810301010482028281830100", refresh.toHex())
        assertEquals(4, refreshed)
        // With nobody to send for, a text request is beyond the terminal too.
        val text = Cat.respond(proactive("810301130082028183" + "8b02" + "0100"), ProactiveHandler.NONE)
        assertEquals("801400000c" + "810301130082028281830130", text.toHex())
    }

    @Test
    fun sevenBitTextsTheCardLeavesUnpackedArePacked() {
        val packed = Cat.packSubmit(SUBMIT)!!
        assertEquals("01000a912143658709000005e8329bfd06", packed.toHex())
        // Eight-bit data goes out as it is; a header the terminal cannot pack is refused.
        val eightBit = "01000a912143658709" + "00" + "f6" + "02" + "0102"
        assertEquals(eightBit, Cat.packSubmit(eightBit.hexToBytes())!!.toHex())
        assertNull(Cat.packSubmit("41000a91214365870900000568656c6c6f".hexToBytes()))
    }
}
