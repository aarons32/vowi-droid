package com.vowi.vowidroid.core.sim

import com.vowi.vowidroid.core.util.hexToBytes
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reading a USIM and authenticating with it, against a virtual UICC. */
class UsimTest {
    /** Just enough of the UICC file system (TS 102 221, TS 31.102) for VowiDroid's reads, and AUTHENTICATE. */
    private class VirtualUicc(
        iccid: String,
        imsi: String,
        plmn: String,
        spn: String = "",
        smsc: String = "",
        gid1: String = "",
        var pinTries: Int? = null,
        var hasUsim: Boolean = true,
    ) : SmartCard {
        private class File(val binary: ByteArray? = null, val records: List<ByteArray>? = null)

        private val master = HashMap<Int, File>()
        private val usim = HashMap<Int, File>()
        private var inUsim = false
        /** An application was selected on this channel, so 7FFF leads back to it even from under the MF. */
        private var appSelected = false
        private var selected: File? = null
        val calls = ArrayList<ByteArray>()
        var authenticate: (ByteArray) -> ApduResponse = { ApduResponse(ByteArray(0), 0x9862) }

        init {
            master[0x2fe2] = File(binary = bcd(iccid, 10))
            master[0x2f00] = File(records = listOf(directoryRecord()))
            usim[0x6f07] = File(binary = encodeImsi(imsi))
            usim[0x6fad] = File(binary = byteArrayOf(0, 0, 0, (plmn.length - 3).toByte()))
            if (spn.isNotEmpty()) usim[0x6f46] = File(binary = ByteArray(17) { 0xff.toByte() }.also { it[0] = 0; spn.toByteArray().copyInto(it, 1) })
            if (smsc.isNotEmpty()) usim[0x6f42] = File(records = listOf(smspRecord(smsc)))
            if (gid1.isNotEmpty()) usim[0x6f3e] = File(binary = gid1.hexToBytes())
        }

        override fun transmit(command: ByteArray): ApduResponse {
            calls += command
            val data = if (command.size > 5) command.copyOfRange(5, 5 + (command[4].toInt() and 0xff)) else ByteArray(0)
            return when (command[1].toInt() and 0xff) {
                0xa4 -> select(command[2].toInt(), data)
                0xb0 -> selected?.binary?.let { binary ->
                    // Under the USIM, files need the PIN while it is on and not verified; EF_ICCID under the MF never does.
                    if (inUsim && pinTries != null) return ApduResponse(ByteArray(0), 0x6982)
                    val want = if (command.size == 5 && command[4].toInt() != 0) minOf(binary.size, command[4].toInt() and 0xff) else binary.size
                    ApduResponse(binary.copyOf(want), 0x9000)
                } ?: ApduResponse(ByteArray(0), 0x6981)
                0xb2 -> selected?.records?.getOrNull((command[2].toInt() and 0xff) - 1)?.let { ApduResponse(it, 0x9000) } ?: ApduResponse(ByteArray(0), 0x6a83)
                0x20 -> pinTries?.let { ApduResponse(ByteArray(0), 0x63c0 or it) } ?: ApduResponse(ByteArray(0), 0x9000)
                0x88 -> if (inUsim && command[3].toInt() and 0xff == 0x81) authenticate(data) else ApduResponse(ByteArray(0), 0x6985)
                else -> ApduResponse(ByteArray(0), 0x6d00)
            }
        }

        private fun select(p1: Int, data: ByteArray): ApduResponse {
            if (p1 == 0x04) {
                if (!hasUsim || !data.toList().take(7).toByteArray().contentEquals(USIM_AID.copyOf(7))) return ApduResponse(ByteArray(0), 0x6a82)
                inUsim = true
                appSelected = true
                selected = null
                return ApduResponse(ByteArray(0), 0x9000)
            }
            val id = ((data[0].toInt() and 0xff) shl 8) or (data[1].toInt() and 0xff)
            if (id == 0x3f00) {
                inUsim = false
                selected = null
                return ApduResponse(ByteArray(0), 0x9000)
            }
            if (id == 0x7fff) {
                // The channel's application by its reserved file ID; only meaningful once one was selected.
                if (!hasUsim || !appSelected) return ApduResponse(ByteArray(0), 0x6a82)
                inUsim = true
                selected = null
                return ApduResponse(ByteArray(0), 0x9000)
            }
            val file = (if (inUsim) usim else master)[id] ?: return ApduResponse(ByteArray(0), 0x6a82)
            selected = file
            val size = file.binary?.size
            val inner = byteArrayOf(0x83.toByte(), 0x02, (id shr 8).toByte(), id.toByte()) +
                (if (size != null) byteArrayOf(0x80.toByte(), 0x02, (size shr 8).toByte(), size.toByte()) else ByteArray(0))
            return ApduResponse(byteArrayOf(0x62, inner.size.toByte()) + inner, 0x9000)
        }

        private fun directoryRecord(): ByteArray {
            val aid = if (hasUsim) USIM_AID else "A0000000090001FF".hexToBytes()
            val inner = byteArrayOf(0x4f, aid.size.toByte()) + aid + byteArrayOf(0x50, 4) + "USIM".toByteArray()
            return byteArrayOf(0x61, inner.size.toByte()) + inner
        }

        companion object {
            val USIM_AID = "A0000000871002FF33FF018900000100".hexToBytes()

            fun bcd(digits: String, size: Int): ByteArray {
                val out = ByteArray(size) { 0xff.toByte() }
                digits.forEachIndexed { index, digit ->
                    val nibble = digit - '0'
                    out[index / 2] = if (index % 2 == 0) (0xf0 or nibble).toByte() else ((out[index / 2].toInt() and 0x0f) or (nibble shl 4)).toByte()
                }
                return out
            }

            fun encodeImsi(imsi: String): ByteArray {
                val parity = if (imsi.length % 2 == 1) 9 else 1
                val nibbles = listOf(parity) + imsi.map { it - '0' }
                val packed = ByteArray(8) { 0xff.toByte() }
                nibbles.forEachIndexed { index, nibble ->
                    packed[index / 2] = if (index % 2 == 0) (0xf0 or nibble).toByte() else ((packed[index / 2].toInt() and 0x0f) or (nibble shl 4)).toByte()
                }
                return byteArrayOf(((nibbles.size + 1) / 2).toByte()) + packed
            }

            fun smspRecord(smsc: String): ByteArray {
                val digits = smsc.removePrefix("+")
                val address = byteArrayOf((1 + (digits.length + 1) / 2).toByte(), if (smsc.startsWith("+")) 0x91.toByte() else 0x81.toByte()) + bcd(digits, 10)
                return byteArrayOf(0xfd.toByte()) + ByteArray(12) { 0xff.toByte() } + address + byteArrayOf(0x00, 0x00, 0xff.toByte())
            }
        }
    }

    @Test
    fun theIdentityComesFromTheCardsFiles() {
        val card = VirtualUicc("8901260012345678901", "310260123456789", "310260", spn = "T-Mobile", smsc = "+12063130004", gid1 = "54ff")
        val identity = Usim.readIdentity(card)
        assertEquals("8901260012345678901", identity.iccid)
        assertEquals("310260123456789", identity.imsi)
        assertEquals("310", identity.homeMcc)
        assertEquals("260", identity.homeMnc)
        assertEquals("T-Mobile", identity.spn)
        assertEquals("+12063130004", identity.smsc)
        assertEquals("54", identity.gid1)
        // SELECT MF asks for no FCP (P2 = 0C).
        assertArrayEquals(byteArrayOf(0x00, 0xa4.toByte(), 0x00, 0x0c, 0x02, 0x3f, 0x00), card.calls.first())
    }

    @Test
    fun aTwoDigitNetworkCodeComesFromEfAd() {
        val identity = Usim.readIdentity(VirtualUicc("8949226000123456789", "262011234567890", "26201"))
        assertEquals("262", identity.homeMcc)
        assertEquals("01", identity.homeMnc)
        assertEquals("", identity.spn)
        assertEquals("", identity.smsc)
    }

    @Test
    fun aPinLockedCardIsReportedWithItsTriesLeft() {
        val locked = VirtualUicc("8901260012345678901", "310260123456789", "310260", pinTries = 3)
        val error = assertThrows(SimPinRequiredException::class.java) { Usim.readIdentity(locked) }
        assertEquals(3, error.tries)
        // Its ICCID still reads, so the card is still known.
        assertEquals("8901260012345678901", Usim.readIccid(locked))
    }

    @Test
    fun aCardWithThePinOffIsNotTakenForLockedWhenItAnswersTheBareVerify() {
        // Many cards answer a bare VERIFY with a retry count whether or not the PIN is on; only a refused read means locked.
        val card = object : SmartCard {
            val inner = VirtualUicc("8901260012345678901", "310260123456789", "310260", spn = "T-Mobile")
            override fun transmit(command: ByteArray): ApduResponse =
                if (command[1].toInt() and 0xff == 0x20) ApduResponse(ByteArray(0), 0x63c3) else inner.transmit(command)
        }
        assertEquals("310260123456789", Usim.readIdentity(card).imsi)
        assertTrue("no bare VERIFY is sent to a card that reads fine", card.inner.calls.none { it[1].toInt() and 0xff == 0x20 })
    }

    @Test
    fun aChannelOpenedOnTheUsimIsReEnteredByFileIdNotByName() {
        // Android's OMAPI opens the channel on the USIM itself and refuses SELECT by DF name on it.
        val inner = VirtualUicc("8901260012345678901", "310260123456789", "310260", spn = "T-Mobile")
        inner.transmit(byteArrayOf(0x00, 0xa4.toByte(), 0x04, 0x04, 7) + VirtualUicc.USIM_AID.copyOf(7) + byteArrayOf(0x00))
        val opened = inner.calls.size
        val channel = object : UsimChannel {
            override fun transmit(command: ByteArray): ApduResponse {
                if (command[1].toInt() and 0xff == 0xa4 && command[2].toInt() == 0x04) throw SecurityException("SELECT by DF name command is not allowed")
                return inner.transmit(command)
            }
        }
        val identity = Usim.readIdentity(channel)
        assertEquals("8901260012345678901", identity.iccid)
        assertEquals("310260123456789", identity.imsi)
        assertEquals("T-Mobile", identity.spn)
        inner.authenticate = { ApduResponse(byteArrayOf(0xdb.toByte(), 8) + ByteArray(8) { 1 } + byteArrayOf(16) + ByteArray(16) { 2 } + byteArrayOf(16) + ByteArray(16) { 3 }, 0x9000) }
        val result = Usim.authenticate(channel, AkaChallenge(ByteArray(16), ByteArray(16)), expectedIccid = "8901260012345678901")
        assertArrayEquals(ByteArray(16) { 2 }, result.ck)
        // Everything after the channel was opened went by file ID; the USIM was re-entered through 7FFF.
        val later = inner.calls.drop(opened)
        assertTrue(later.none { it[1].toInt() and 0xff == 0xa4 && it[2].toInt() == 0x04 })
        assertTrue(later.any { it[1].toInt() and 0xff == 0xa4 && it.size >= 7 && it[5].toInt() == 0x7f && it[6].toInt() and 0xff == 0xff })
    }

    @Test
    fun aCardWithoutUsimIsReported() {
        assertThrows(UsimUnavailableException::class.java) { Usim.readIdentity(VirtualUicc("8901260012345678901", "310260123456789", "310260", hasUsim = false)) }
    }

    @Test
    fun authenticateSendsRandAndAutnAndChecksTheCard() {
        val card = VirtualUicc("8901260012345678901", "310260123456789", "310260")
        var seen = ByteArray(0)
        val res = ByteArray(8) { 1 }
        val ck = ByteArray(16) { 2 }
        val ik = ByteArray(16) { 3 }
        card.authenticate = { data ->
            seen = data
            ApduResponse(byteArrayOf(0xdb.toByte(), 8) + res + byteArrayOf(16) + ck + byteArrayOf(16) + ik, 0x9000)
        }
        val challenge = AkaChallenge(ByteArray(16) { 0xa1.toByte() }, ByteArray(16) { 0xa2.toByte() })
        val result = Usim.authenticate(card, challenge, expectedIccid = "8901260012345678901")
        assertArrayEquals(res, result.res)
        assertArrayEquals(ck, result.ck)
        assertArrayEquals(ik, result.ik)
        assertArrayEquals(byteArrayOf(16) + challenge.rand + byteArrayOf(16) + challenge.autn, seen)

        // Another card in the reader never answers for this line.
        assertThrows(CardChangedException::class.java) { Usim.authenticate(card, challenge, expectedIccid = "8901260099999999999") }
        // A rejected network MAC is told apart from other failures.
        card.authenticate = { ApduResponse(ByteArray(0), 0x9862) }
        assertThrows(com.vowi.vowidroid.core.sim.AkaMacFailureException::class.java) { Usim.authenticate(card, challenge) }
        // A resynchronisation answer carries AUTS.
        card.authenticate = { ApduResponse(byteArrayOf(0xdc.toByte(), 14) + ByteArray(14) { 5 }, 0x9000) }
        assertTrue(Usim.authenticate(card, challenge).synchronizationFailure)
    }

    @Test
    fun identifiersDecode() {
        assertEquals("890123456", Usim.decodeSwappedBcd(byteArrayOf(0x98.toByte(), 0x10, 0x32, 0x54, 0xf6.toByte()), dropFirstNibble = false))
        assertEquals("123456789012345", Usim.decodeImsi(byteArrayOf(0x08, 0x19, 0x32, 0x54, 0x76, 0x98.toByte(), 0x10, 0x32, 0x54)))
        assertThrows(SimCardException::class.java) { Usim.decodeImsi(byteArrayOf(0x09, 0x19)) }
    }
}
