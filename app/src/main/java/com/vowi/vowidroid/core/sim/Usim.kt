package com.vowi.vowidroid.core.sim

import com.vowi.vowidroid.core.util.hexToBytes
import com.vowi.vowidroid.core.util.toHex

/** The card wants its PIN before it shares its files. [tries] is how many attempts remain, or -1. */
class SimPinRequiredException(val tries: Int) : SimCardException(
    if (tries >= 0) "the SIM is locked with a PIN ($tries attempts left); turn the SIM PIN off in a phone first"
    else "the SIM is locked with a PIN; turn the SIM PIN off in a phone first",
)

/** The card has no USIM application, e.g. an old 2G SIM. */
class UsimUnavailableException : SimCardException("the card has no USIM application")

/** The card in a slot or reader is no longer the one VowiDroid was using. */
class CardChangedException : SimCardException("a different SIM card is in its place")

/** The card's access rules (ARA-M) do not list VowiDroid, so Android's OMAPI refused a channel to it. */
class CardAccessDeniedException : SimCardException("the card's access rules do not list VowiDroid")

/**
 * A card reached over a channel that Android opened on the USIM already, as
 * OMAPI does. SELECT by DF name is not allowed on such a channel, so the USIM
 * is re-entered through its reserved file ID 7FFF (TS 102 221 §8.4.1).
 */
interface UsimChannel : SmartCard

/**
 * What VowiDroid reads from a USIM over APDUs (3GPP TS 102 221, TS 31.102):
 * the ICCID, the identity files and AUTHENTICATE in the 3G context.
 */
object Usim {
    private const val USIM_AID_PREFIX = "A0000000871002"

    /** EF_ICCID under the MF. */
    fun readIccid(card: SmartCard): String {
        selectMf(card)
        selectFile(card, 0x2f, 0xe2, "EF_ICCID")
        val value = decodeSwappedBcd(readBinary(card, 10), dropFirstNibble = false)
        if (value.length !in 18..22) throw SimCardException("the card returned an invalid ICCID")
        return value
    }

    /** Finds the USIM in EF_DIR and selects it; returns its AID. A [UsimChannel] is already on the USIM and is re-entered instead. */
    fun selectUsim(card: SmartCard): ByteArray {
        if (card is UsimChannel) {
            selectAdf(card)
            return USIM_AID_PREFIX.hexToBytes()
        }
        selectMf(card)
        selectFile(card, 0x2f, 0x00, "EF_DIR")
        var aid: ByteArray? = null
        for (record in 1..32) {
            val response = card.transmit(byteArrayOf(0x00, 0xb2.toByte(), record.toByte(), 0x04, 0x00))
            if (response.sw == 0x6a83 || response.sw == 0x9402) break
            if (response.sw != 0x9000) continue
            val found = findTlv(response.data, 0x4f) ?: continue
            if (found.toHex().uppercase().startsWith(USIM_AID_PREFIX)) {
                aid = found
                break
            }
        }
        val usim = aid ?: throw UsimUnavailableException()
        selectApplication(card, usim)
        return usim
    }

    /**
     * How many PIN attempts the card has left, asked only once a read has
     * failed for want of the PIN. A bare VERIFY is not a test of whether the
     * PIN is on: cards with the PIN off still answer it with a retry count.
     */
    private fun pinTries(card: SmartCard): Int {
        val response = runCatching { card.transmit(byteArrayOf(0x00, 0x20, 0x00, 0x01, 0x00)) }.getOrNull() ?: return -1
        return if (response.sw and 0xfff0 == 0x63c0) response.sw and 0x0f else if (response.sw == 0x6983) 0 else -1
    }

    /** The card's identity for the protocol stack: ICCID, IMSI, home network, SPN, GID1 and the SMS centre. */
    fun readIdentity(card: SmartCard): SimIdentity {
        val iccid = readIccid(card)
        val aid = selectUsim(card)
        // EF_IMSI needs the PIN when it is on; that read, not a probe, tells whether the card is locked.
        val imsi = try {
            selectFile(card, 0x6f, 0x07, "EF_IMSI")
            decodeImsi(readBinary(card, 9))
        } catch (error: SimPinRequiredException) {
            throw SimPinRequiredException(pinTries(card))
        }
        val mncLength = runCatching {
            selectApplication(card, aid)
            selectFile(card, 0x6f, 0xad, "EF_AD")
            val data = readBinary(card, 4)
            (data[3].toInt() and 0x0f).takeIf { data.size >= 4 && (it == 2 || it == 3) }
        }.getOrNull() ?: guessMncLength(imsi.substring(0, 3))
        val spn = runCatching { selectApplication(card, aid); readSpn(card) }.getOrDefault("")
        val smsc = runCatching { selectApplication(card, aid); readSmsc(card) }.getOrDefault("")
        val gid1 = runCatching { selectApplication(card, aid); readGid1(card) }.getOrDefault("")
        return SimIdentity(
            iccid = iccid,
            imsi = imsi,
            homeMcc = imsi.substring(0, 3),
            homeMnc = imsi.substring(3, 3 + mncLength),
            spn = spn,
            gid1 = gid1,
            smsc = smsc,
        )
    }

    /**
     * AUTHENTICATE in the 3G context (TS 31.102 §7.1.2.1). With [expectedIccid],
     * the card is checked first, so a swapped card never answers for a line.
     */
    fun authenticate(card: SmartCard, challenge: AkaChallenge, expectedIccid: String? = null): AkaResult {
        if (expectedIccid != null && !readIccid(card).equals(expectedIccid.trimEnd('f', 'F'), ignoreCase = true)) throw CardChangedException()
        selectUsim(card)
        val command = byteArrayOf(0x00, 0x88.toByte(), 0x00, 0x81.toByte(), 0x22, 0x10) + challenge.rand + byteArrayOf(0x10) + challenge.autn + byteArrayOf(0x00)
        val response = card.transmit(command)
        return when (response.sw) {
            0x9000 -> AkaResult.fromUiccResponse(response.data)
            0x9862 -> throw AkaMacFailureException()
            0x6982 -> throw AkaException("the SIM is locked with a PIN")
            else -> throw AkaException("the SIM's authentication failed with status %04X".format(response.sw))
        }
    }

    // --- Files ------------------------------------------------------------------

    private fun selectMf(card: SmartCard) {
        // No FCP wanted (P2=0C): some readers would surface the template's leading 62 xx as a warning.
        require(card.transmit(byteArrayOf(0x00, 0xa4.toByte(), 0x00, 0x0c, 0x02, 0x3f, 0x00)), "select MF")
    }

    private fun selectFile(card: SmartCard, high: Int, low: Int, name: String): ByteArray {
        val response = card.transmit(byteArrayOf(0x00, 0xa4.toByte(), 0x00, 0x04, 0x02, high.toByte(), low.toByte(), 0x00))
        require(response, "select $name")
        return response.data
    }

    private fun selectApplication(card: SmartCard, aid: ByteArray) {
        if (card is UsimChannel) return selectAdf(card)
        require(card.transmit(byteArrayOf(0x00, 0xa4.toByte(), 0x04, 0x04, aid.size.toByte()) + aid + byteArrayOf(0x00)), "select the USIM")
    }

    /** Re-enters the application the channel was opened on: 7FFF names the current ADF on every UICC. */
    private fun selectAdf(card: SmartCard) {
        require(card.transmit(byteArrayOf(0x00, 0xa4.toByte(), 0x00, 0x04, 0x02, 0x7f, 0xff.toByte(), 0x00)), "select the USIM")
    }

    private fun readBinary(card: SmartCard, length: Int): ByteArray {
        val response = card.transmit(byteArrayOf(0x00, 0xb0.toByte(), 0x00, 0x00, (if (length >= 256) 0 else length).toByte()))
        require(response, "read binary")
        return response.data
    }

    private fun require(response: ApduResponse, operation: String) {
        when (response.sw) {
            0x9000 -> Unit
            0x6982, 0x9804 -> throw SimPinRequiredException(-1)
            else -> throw SimCardException("$operation failed with status %04X".format(response.sw))
        }
    }

    /** EF_SPN: a display-condition byte and up to 16 bytes of name. */
    private fun readSpn(card: SmartCard): String {
        selectFile(card, 0x6f, 0x46, "EF_SPN")
        val data = readBinary(card, 17)
        if (data.size < 2) return ""
        val name = data.copyOfRange(1, data.size).takeWhile { it != 0xff.toByte() && it != 0.toByte() }.toByteArray()
        return String(name, Charsets.UTF_8).trim()
    }

    /** The TS-Service-Centre address in EF_SMSP's first record (TS 31.102 §4.2.27). */
    private fun readSmsc(card: SmartCard): String {
        selectFile(card, 0x6f, 0x42, "EF_SMSP")
        val response = card.transmit(byteArrayOf(0x00, 0xb2.toByte(), 0x01, 0x04, 0x00))
        val data = response.data
        if (response.sw != 0x9000 || data.size < 15) return ""
        val address = data.copyOfRange(data.size - 15, data.size - 3)
        val length = address[0].toInt() and 0xff
        if (length < 2 || length > address.size - 1) return ""
        val digits = decodeSwappedBcd(address.copyOfRange(2, 1 + length), dropFirstNibble = false)
        if (digits.isEmpty() || !digits.all(Char::isDigit)) return ""
        return if (address[1].toInt() and 0x70 == 0x10) "+$digits" else digits
    }

    /** EF_GID1, whose size varies by card, so it comes from the FCP. */
    private fun readGid1(card: SmartCard): String {
        val fcp = selectFile(card, 0x6f, 0x3e, "EF_GID1")
        val encoded = findTlv(fcp, 0x80) ?: return ""
        if (encoded.isEmpty() || encoded.size > 4) return ""
        val size = encoded.fold(0) { total, octet -> (total shl 8) or (octet.toInt() and 0xff) }
        if (size <= 0) return ""
        val data = readBinary(card, minOf(size, 256))
        return data.dropLastWhile { it == 0xff.toByte() }.toByteArray().toHex().uppercase()
    }

    // --- Encodings ----------------------------------------------------------------

    /** Digits two per byte, low nibble first; F ends the value. */
    fun decodeSwappedBcd(value: ByteArray, dropFirstNibble: Boolean): String {
        val result = StringBuilder()
        var skip = dropFirstNibble
        for (octet in value) {
            for (nibble in intArrayOf(octet.toInt() and 0x0f, (octet.toInt() shr 4) and 0x0f)) {
                if (skip) {
                    skip = false
                    continue
                }
                if (nibble == 0x0f) return result.toString()
                if (nibble > 9) return ""
                result.append('0' + nibble)
            }
        }
        return result.toString()
    }

    /** EF_IMSI (TS 31.102 §4.2.2): a length byte, then the digits after a parity nibble. */
    fun decodeImsi(data: ByteArray): String {
        if (data.size < 2) throw SimCardException("EF_IMSI is too short")
        val length = data[0].toInt() and 0xff
        if (length <= 0 || length > data.size - 1) throw SimCardException("EF_IMSI has an invalid length")
        val value = decodeSwappedBcd(data.copyOfRange(1, 1 + length), dropFirstNibble = true)
        if (value.length !in 10..18 || !value.all(Char::isDigit)) throw SimCardException("the card returned an invalid IMSI")
        return value
    }

    /** The first BER-TLV with [wanted] as its tag, searching constructed tags too. */
    fun findTlv(data: ByteArray, wanted: Int): ByteArray? {
        var offset = 0
        while (data.size - offset >= 2) {
            val tag = data[offset].toInt() and 0xff
            offset++
            val first = data[offset].toInt() and 0xff
            var length: Int
            var consumed: Int
            if (first and 0x80 == 0) {
                length = first
                consumed = 1
            } else {
                val count = first and 0x7f
                if (count < 1 || count > 2 || data.size - offset < 1 + count) return null
                length = 0
                for (index in 1..count) length = (length shl 8) or (data[offset + index].toInt() and 0xff)
                consumed = 1 + count
            }
            if (offset + consumed + length > data.size) return null
            val value = data.copyOfRange(offset + consumed, offset + consumed + length)
            if (tag == wanted) return value
            if (tag and 0x20 != 0) findTlv(value, wanted)?.let { return it }
            offset += consumed + length
        }
        return null
    }

    /** When EF_AD is missing: three-digit MNCs are the rule in North America, two elsewhere. */
    private fun guessMncLength(mcc: String): Int = if (mcc == "302" || mcc in (310..316).map(Int::toString) || mcc == "334") 3 else 2
}
