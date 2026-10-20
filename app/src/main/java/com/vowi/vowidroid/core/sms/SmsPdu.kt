package com.vowi.vowidroid.core.sms

import com.vowi.vowidroid.core.util.Rng
import com.vowi.vowidroid.core.util.toHex
import com.vowi.vowidroid.core.util.u8
import java.nio.charset.Charset
import java.util.Calendar
import java.util.TimeZone

class SmsException(message: String) : Exception("sms: $message")

enum class SmsEncoding(val wire: String) {
    GSM7("gsm7_pdu"), UCS2("ucs2_pdu"), UTF8("utf8_pdu"), GB18030("gb18030_pdu"), LATIN1("latin1_pdu"), BINARY("8bit_pdu"), UNKNOWN("unknown")
}

enum class SmsDirection { RECEIVED, SUBMITTED, STATUS_REPORT, UNKNOWN }

class SmsConcat(val reference: Int, val total: Int, val sequence: Int)

/** A decoded TPDU (3GPP TS 23.040). Timestamps are epoch milliseconds. */
class SmsMessage(
    val direction: SmsDirection,
    val from: String = "",
    val to: String = "",
    val text: String = "",
    val encoding: SmsEncoding = SmsEncoding.UNKNOWN,
    val serviceCenterTimestamp: Long? = null,
    val dischargeTimestamp: Long? = null,
    val messageReference: Int? = null,
    val statusCode: Int? = null,
    val deliveryStatus: String = "",
    val concat: SmsConcat? = null,
    val protocolId: Int = 0,
    val dataCodingScheme: Int = 0,
    val rawUserData: String = "",
    val simDataDownload: Boolean = false,
    val decodeError: String = "",
)

/** One SMS-SUBMIT TPDU ready for RP-DATA. */
class SmsSubmitTpdu(val to: String, val encoding: SmsEncoding, val tpdu: ByteArray, val part: Int, val total: Int, val concatReference: Int?)

/** The SMS TPDU codec: SMS-SUBMIT encoding with concatenation, SMS-DELIVER and STATUS-REPORT decoding. */
object SmsPdu {
    private val GSM7_ALPHABET = charArrayOf(
        '@', '£', '$', '¥', 'è', 'é', 'ù', 'ì', 'ò', 'Ç', '\n', 'Ø', 'ø', '\r', 'Å', 'å',
        'Δ', '_', 'Φ', 'Γ', 'Λ', 'Ω', 'Π', 'Ψ', 'Σ', 'Θ', 'Ξ', '\u001b', 'Æ', 'æ', 'ß', 'É',
        ' ', '!', '"', '#', '¤', '%', '&', '\'', '(', ')', '*', '+', ',', '-', '.', '/',
        '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', ':', ';', '<', '=', '>', '?',
        '¡', 'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I', 'J', 'K', 'L', 'M', 'N', 'O',
        'P', 'Q', 'R', 'S', 'T', 'U', 'V', 'W', 'X', 'Y', 'Z', 'Ä', 'Ö', 'Ñ', 'Ü', '§',
        '¿', 'a', 'b', 'c', 'd', 'e', 'f', 'g', 'h', 'i', 'j', 'k', 'l', 'm', 'n', 'o',
        'p', 'q', 'r', 's', 't', 'u', 'v', 'w', 'x', 'y', 'z', 'ä', 'ö', 'ñ', 'ü', 'à',
    )
    private val GSM7_EXTENSION = mapOf(
        0x0a to '\u000c', 0x14 to '^', 0x28 to '{', 0x29 to '}', 0x2f to '\\', 0x3c to '[', 0x3d to '~', 0x3e to ']', 0x40 to '|', 0x65 to '€',
    )
    private val GSM7_ENCODER: Map<Char, ByteArray> = HashMap<Char, ByteArray>().also { map ->
        for ((code, character) in GSM7_ALPHABET.withIndex()) if (code != 0x1b) map[character] = byteArrayOf(code.toByte())
        for ((code, character) in GSM7_EXTENSION) map[character] = byteArrayOf(0x1b, code.toByte())
    }

    // --- Encoding -------------------------------------------------------------

    fun normalizeRecipient(value: String): String {
        val result = StringBuilder()
        for (character in value.trim()) {
            when {
                character in '0'..'9' -> result.append(character)
                character == '+' && result.isEmpty() -> result.append(character)
                character == ' ' || character == '-' || character == '(' || character == ')' -> Unit
                else -> throw SmsException("invalid recipient")
            }
        }
        val normalized = result.toString()
        val digits = normalized.removePrefix("+")
        if (digits.isEmpty() || digits.length > 20) throw SmsException("invalid recipient")
        return normalized
    }

    fun encodeGsm7(text: String): ByteArray? {
        val out = ArrayList<Byte>(text.length)
        for (character in text) {
            val encoded = GSM7_ENCODER[character] ?: return null
            for (byte in encoded) out.add(byte)
        }
        return out.toByteArray()
    }

    /** Builds SMS-SUBMIT TPDUs with TP-SRR set, splitting long texts (RFC-style UDH concatenation). */
    fun prepareSubmit(recipient: String, text: String): List<SmsSubmitTpdu> {
        val to = normalizeRecipient(recipient)
        if (text.isEmpty()) throw SmsException("message is empty")
        if (text.contains('\u0000')) throw SmsException("message contains NUL")
        val septets = encodeGsm7(text)
        if (septets != null) {
            if (septets.size <= 160) return listOf(SmsSubmitTpdu(to, SmsEncoding.GSM7, submitTpdu(to, septets, null, null), 1, 1, null))
            val segments = splitGsm7(septets, 153)
            if (segments.size > 255) throw SmsException("message needs ${segments.size} parts; the maximum is 255")
            val reference = Rng.bytes(1).u8(0)
            return segments.mapIndexed { index, segment ->
                SmsSubmitTpdu(to, SmsEncoding.GSM7, submitTpdu(to, segment, null, concatHeader(reference, segments.size, index + 1)), index + 1, segments.size, reference)
            }
        }
        val units = text.toCharArray()
        if (units.size <= 70) return listOf(SmsSubmitTpdu(to, SmsEncoding.UCS2, submitTpdu(to, null, ucs2(units), null), 1, 1, null))
        val segments = splitUcs2(units, 67)
        if (segments.size > 255) throw SmsException("message needs ${segments.size} parts; the maximum is 255")
        val reference = Rng.bytes(1).u8(0)
        return segments.mapIndexed { index, segment ->
            SmsSubmitTpdu(to, SmsEncoding.UCS2, submitTpdu(to, null, ucs2(segment), concatHeader(reference, segments.size, index + 1)), index + 1, segments.size, reference)
        }
    }

    /** A voicemail-waiting indication carried by an SMS: [count] when known, [discard] when the SMS itself is not for the inbox. */
    class VoicemailIndication(val active: Boolean, val count: Int?, val discard: Boolean)

    /**
     * Finds a voicemail indication in an SMS-DELIVER: a TS 23.038 "message
     * waiting" coding group, or a TS 23.040 §9.2.3.24.2 special SMS message
     * indication in the user data header. Null for an ordinary text.
     */
    fun voicemailIndication(tpdu: ByteArray): VoicemailIndication? = runCatching {
        if (tpdu.isEmpty() || tpdu.u8(0) and 0x03 != 0) return null
        val first = tpdu.u8(0)
        var index = 1
        val digits = tpdu.u8(index)
        index += 2 + (digits + 1) / 2 // length, type of number, the address
        index += 1 // protocol identifier
        val dcs = tpdu.u8(index++)
        index += 7 // service centre time stamp
        index += 1 // user data length
        var result: VoicemailIndication? = null
        // Groups 1100 (discard), 1101 (store, GSM 7-bit), 1110 (store, UCS2); bit 3 is the indication, bits 1-0 = 00 voicemail.
        val group = dcs and 0xf0
        if ((group == 0xc0 || group == 0xd0 || group == 0xe0) && dcs and 0x03 == 0) {
            result = VoicemailIndication(active = dcs and 0x08 != 0, count = null, discard = group == 0xc0)
        }
        if (first and 0x40 != 0 && index < tpdu.size) {
            val end = index + 1 + tpdu.u8(index)
            var element = index + 1
            while (element + 1 < end && end <= tpdu.size) {
                val iei = tpdu.u8(element)
                val length = tpdu.u8(element + 1)
                // Octet 1: bit 7 store, bits 1-0 basic type (00 voice); octet 2: the number of messages waiting.
                if (iei == 0x01 && length >= 2 && element + 3 < tpdu.size && tpdu.u8(element + 2) and 0x03 == 0) {
                    val count = tpdu.u8(element + 3)
                    result = VoicemailIndication(active = count > 0, count = count, discard = tpdu.u8(element + 2) and 0x80 == 0)
                }
                element += 2 + length
            }
        }
        result
    }.getOrNull()

    /** How a text will go out (TS 23.040): its encoding, the number of SMS, and the room left in the last one. */
    class Length(val encoding: SmsEncoding, val parts: Int, val remaining: Int)

    /** Measures [text] exactly as [prepareSubmit] would split it. */
    fun measure(text: String): Length {
        if (text.isEmpty()) return Length(SmsEncoding.GSM7, 0, 160)
        val septets = encodeGsm7(text)
        if (septets != null) {
            if (septets.size <= 160) return Length(SmsEncoding.GSM7, 1, 160 - septets.size)
            val segments = splitGsm7(septets, 153)
            return Length(SmsEncoding.GSM7, segments.size, 153 - segments.last().size)
        }
        val units = text.toCharArray()
        if (units.size <= 70) return Length(SmsEncoding.UCS2, 1, 70 - units.size)
        val segments = splitUcs2(units, 67)
        return Length(SmsEncoding.UCS2, segments.size, 67 - segments.last().size)
    }

    private fun splitGsm7(septets: ByteArray, maximum: Int): List<ByteArray> {
        val result = ArrayList<ByteArray>()
        var offset = 0
        while (offset < septets.size) {
            var count = minOf(maximum, septets.size - offset)
            if (offset + count < septets.size && septets[offset + count - 1] == 0x1b.toByte()) count--
            result.add(septets.copyOfRange(offset, offset + count))
            offset += count
        }
        return result
    }

    private fun splitUcs2(units: CharArray, maximum: Int): List<CharArray> {
        val result = ArrayList<CharArray>()
        var offset = 0
        while (offset < units.size) {
            var count = minOf(maximum, units.size - offset)
            if (offset + count < units.size && units[offset + count - 1].isHighSurrogate() && units[offset + count].isLowSurrogate()) count--
            result.add(units.copyOfRange(offset, offset + count))
            offset += count
        }
        return result
    }

    private fun ucs2(units: CharArray): ByteArray {
        val out = ByteArray(units.size * 2)
        for ((index, unit) in units.withIndex()) {
            out[index * 2] = (unit.code ushr 8).toByte()
            out[index * 2 + 1] = unit.code.toByte()
        }
        return out
    }

    private fun concatHeader(reference: Int, total: Int, sequence: Int) = byteArrayOf(0x05, 0x00, 0x03, reference.toByte(), total.toByte(), sequence.toByte())

    private fun submitTpdu(recipient: String, gsm7: ByteArray?, ucs2: ByteArray?, header: ByteArray?): ByteArray {
        val digits = recipient.removePrefix("+")
        val address = semiOctets(digits)
        val toa = if (recipient.startsWith("+")) 0x91 else 0x81
        var firstOctet = 0x21 // SMS-SUBMIT with TP-SRR
        if (header != null) firstOctet = firstOctet or 0x40
        val out = ArrayList<Byte>()
        out.add(firstOctet.toByte())
        out.add(0) // TP-MR, set by the sender
        out.add(digits.length.toByte())
        out.add(toa.toByte())
        for (byte in address) out.add(byte)
        out.add(0) // TP-PID
        when {
            gsm7 != null -> {
                val headerSeptets = if (header != null) (header.size * 8 + 6) / 7 else 0
                val userDataLength = headerSeptets + gsm7.size
                if (userDataLength > 160) throw SmsException("message is too long")
                val packed = packSeptets(gsm7, headerSeptets * 7)
                if (header != null) System.arraycopy(header, 0, packed, 0, header.size)
                out.add(0)
                out.add(userDataLength.toByte())
                for (byte in packed) out.add(byte)
            }
            ucs2 != null -> {
                val userDataLength = (header?.size ?: 0) + ucs2.size
                if (userDataLength > 140) throw SmsException("message is too long")
                out.add(0x08)
                out.add(userDataLength.toByte())
                if (header != null) for (byte in header) out.add(byte)
                for (byte in ucs2) out.add(byte)
            }
            else -> throw SmsException("no user data")
        }
        return out.toByteArray()
    }

    private fun semiOctets(digits: String): ByteArray {
        if (digits.isEmpty()) throw SmsException("invalid recipient")
        val result = ByteArray((digits.length + 1) / 2)
        var index = 0
        while (index < digits.length) {
            val low = digits[index]
            if (low !in '0'..'9') throw SmsException("invalid recipient")
            var high = 0x0f
            if (index + 1 < digits.length) {
                val character = digits[index + 1]
                if (character !in '0'..'9') throw SmsException("invalid recipient")
                high = character - '0'
            }
            result[index / 2] = ((high shl 4) or (low - '0')).toByte()
            index += 2
        }
        return result
    }

    fun packSeptets(septets: ByteArray, startBit: Int): ByteArray {
        if (septets.isEmpty()) return ByteArray((startBit + 7) / 8)
        val bitLength = startBit + septets.size * 7
        val result = ByteArray((bitLength + 7) / 8)
        for ((index, septet) in septets.withIndex()) {
            val bit = startBit + index * 7
            val byteIndex = bit / 8
            val shift = bit % 8
            val value = septet.toInt() and 0x7f
            result[byteIndex] = (result[byteIndex].toInt() or (value shl shift)).toByte()
            if (shift > 1 && byteIndex + 1 < result.size) {
                result[byteIndex + 1] = (result[byteIndex + 1].toInt() or (value ushr (8 - shift))).toByte()
            }
        }
        return result
    }

    fun unpackSeptets(data: ByteArray, count: Int, startBit: Int): ByteArray {
        if (count < 0 || startBit < 0 || startBit + count * 7 > data.size * 8) throw SmsException("GSM-7 user data is truncated")
        val result = ByteArray(count)
        for (index in 0 until count) {
            val bit = startBit + index * 7
            val byteIndex = bit / 8
            val shift = bit % 8
            var value = data.u8(byteIndex) ushr shift
            if (shift > 1 && byteIndex + 1 < data.size) value = value or (data.u8(byteIndex + 1) shl (8 - shift))
            result[index] = (value and 0x7f).toByte()
        }
        return result
    }

    fun decodeGsm7(septets: ByteArray): String {
        val result = StringBuilder()
        var index = 0
        while (index < septets.size) {
            val code = septets.u8(index)
            if (code != 0x1b) {
                if (code >= GSM7_ALPHABET.size) throw SmsException("invalid GSM-7 code")
                result.append(GSM7_ALPHABET[code])
                index++
                continue
            }
            index++
            if (index >= septets.size) throw SmsException("trailing GSM-7 escape")
            val character = GSM7_EXTENSION[septets.u8(index)] ?: throw SmsException("unknown GSM-7 extension 0x%02X".format(septets.u8(index)))
            result.append(character)
            index++
        }
        return result.toString()
    }

    // --- Decoding -------------------------------------------------------------

    private class Cursor(val data: ByteArray) {
        var index = 0
        fun byte(): Int {
            if (index >= data.size) throw SmsException("TPDU is truncated")
            return data.u8(index++)
        }

        fun bytes(count: Int): ByteArray {
            if (count < 0 || index + count > data.size) throw SmsException("TPDU is truncated")
            val value = data.copyOfRange(index, index + count)
            index += count
            return value
        }

        fun rest(): ByteArray = data.copyOfRange(index, data.size)
    }

    /** Decodes a TPDU from RP-DATA; errors leave a partial message with `decodeError` set. */
    fun decodeDeliver(tpdu: ByteArray): SmsMessage {
        if (tpdu.isEmpty()) return SmsMessage(SmsDirection.UNKNOWN, decodeError = "TPDU is empty")
        val cursor = Cursor(tpdu)
        return try {
            val firstOctet = cursor.byte()
            when (firstOctet and 0x03) {
                0 -> deliver(cursor, firstOctet)
                1 -> submit(cursor, firstOctet)
                2 -> statusReport(cursor)
                else -> SmsMessage(SmsDirection.UNKNOWN, decodeError = "unsupported TPDU message type")
            }
        } catch (error: SmsException) {
            SmsMessage(when (tpdu.u8(0) and 0x03) { 0 -> SmsDirection.RECEIVED; 2 -> SmsDirection.STATUS_REPORT; else -> SmsDirection.UNKNOWN }, decodeError = error.message ?: "decode failed")
        }
    }

    private fun deliver(cursor: Cursor, firstOctet: Int): SmsMessage {
        val from = readAddress(cursor)
        val pid = cursor.byte()
        val dcs = cursor.byte()
        val timestamp = decodeTimestamp(cursor.bytes(7))
        val udl = cursor.byte()
        val base = SmsMessage(SmsDirection.RECEIVED, from = from, serviceCenterTimestamp = timestamp, protocolId = pid, dataCodingScheme = dcs, simDataDownload = isSimDataDownload(pid, dcs))
        return userData(cursor.rest(), firstOctet, dcs, udl, base)
    }

    private fun submit(cursor: Cursor, firstOctet: Int): SmsMessage {
        val reference = cursor.byte()
        val to = readAddress(cursor)
        val pid = cursor.byte()
        val dcs = cursor.byte()
        when ((firstOctet ushr 3) and 0x03) {
            2 -> cursor.byte()
            1, 3 -> cursor.bytes(7)
        }
        val udl = cursor.byte()
        val base = SmsMessage(SmsDirection.SUBMITTED, to = to, messageReference = reference, protocolId = pid, dataCodingScheme = dcs)
        return userData(cursor.rest(), firstOctet, dcs, udl, base)
    }

    private fun statusReport(cursor: Cursor): SmsMessage {
        val reference = cursor.byte()
        val to = readAddress(cursor)
        val scts = decodeTimestamp(cursor.bytes(7))
        val discharge = decodeTimestamp(cursor.bytes(7))
        val status = cursor.byte()
        return SmsMessage(SmsDirection.STATUS_REPORT, to = to, messageReference = reference, statusCode = status, deliveryStatus = deliveryStatus(status), serviceCenterTimestamp = scts, dischargeTimestamp = discharge)
    }

    fun deliveryStatus(status: Int): String = when {
        status <= 0x1f -> "delivered"
        status <= 0x3f -> "temporary_error"
        status <= 0x5f -> "permanent_error"
        status <= 0x7f -> "temporary_error_no_retry"
        else -> "reserved"
    }

    /** TS 23.040: the SIM data download PID with a class-2 coding scheme targets the UICC, not the inbox. */
    fun isSimDataDownload(pid: Int, dcs: Int): Boolean {
        if (pid != 0x7f) return false
        if (dcs and 0xf0 == 0xf0) return dcs and 0x03 == 0x02
        return dcs and 0xc0 == 0 && dcs and 0x10 != 0 && dcs and 0x03 == 0x02
    }

    private fun readAddress(cursor: Cursor): String {
        val length = cursor.byte()
        val toa = cursor.byte()
        if (toa and 0x70 == 0x50) {
            // TS 23.040 9.1.2.5: the length counts semi-octets for alphanumeric addresses.
            var byteCount = (length + 1) / 2
            var septetCount = length * 4 / 7
            if (byteCount == 0 || cursor.index + byteCount > cursor.data.size || !hasZeroPadding(cursor.data.copyOfRange(cursor.index, cursor.index + byteCount), septetCount)) {
                byteCount = (length * 7 + 7) / 8
                septetCount = length
            }
            val value = cursor.bytes(byteCount)
            return decodeGsm7(unpackSeptets(value, septetCount, 0))
        }
        return numericAddress(cursor.bytes((length + 1) / 2), length, toa)
    }

    private fun hasZeroPadding(data: ByteArray, septetCount: Int): Boolean {
        if (septetCount <= 0 || septetCount * 7 > data.size * 8) return false
        for (bit in septetCount * 7 until data.size * 8) if (data.u8(bit / 8) and (1 shl (bit % 8)) != 0) return false
        return true
    }

    private fun numericAddress(value: ByteArray, digits: Int, toa: Int): String {
        val result = StringBuilder()
        if (toa and 0x70 == 0x10) result.append('+')
        var written = 0
        for (octet in value) {
            for (digit in intArrayOf(octet.toInt() and 0x0f, (octet.toInt() and 0xff) ushr 4)) {
                if (written >= digits) return result.toString()
                if (digit <= 9) {
                    result.append('0' + digit)
                    written++
                }
            }
        }
        return result.toString()
    }

    private enum class Alphabet { GSM7, BINARY, UCS2, UNKNOWN }

    /** The full TS 23.038 coding-group rules, including the message-waiting groups. */
    private fun alphabet(dcs: Int): Alphabet = when {
        dcs and 0x80 == 0 -> if (dcs and 0x20 != 0) Alphabet.UNKNOWN else when ((dcs ushr 2) and 0x03) { 0 -> Alphabet.GSM7; 1 -> Alphabet.BINARY; 2 -> Alphabet.UCS2; else -> Alphabet.UNKNOWN }
        dcs and 0xe0 == 0xc0 -> Alphabet.GSM7
        dcs and 0xf0 == 0xe0 -> Alphabet.UCS2
        dcs and 0xf0 == 0xf0 -> if (dcs and 0x04 != 0) Alphabet.BINARY else Alphabet.GSM7
        else -> Alphabet.UNKNOWN
    }

    private fun userData(data: ByteArray, firstOctet: Int, dcs: Int, udl: Int, base: SmsMessage): SmsMessage {
        val kind = alphabet(dcs)
        val expected = if (kind == Alphabet.GSM7) (udl * 7 + 7) / 8 else udl
        if (expected > data.size) throw SmsException("user data is truncated")
        val payload = data.copyOf(expected)
        var headerBytes = 0
        var concat: SmsConcat? = null
        var header = ByteArray(0)
        if (firstOctet and 0x40 != 0) {
            if (payload.isEmpty()) throw SmsException("UDHI set but no user data header")
            headerBytes = payload.u8(0) + 1
            if (headerBytes > payload.size) throw SmsException("user data header is truncated")
            header = payload.copyOfRange(1, headerBytes)
            concat = parseConcat(header)
        }
        val raw = payload.toHex().uppercase()
        return when (kind) {
            Alphabet.GSM7 -> {
                val headerSeptets = if (headerBytes > 0) (headerBytes * 8 + 6) / 7 else 0
                val septets = unpackSeptets(payload, udl - headerSeptets, headerSeptets * 7)
                base.with(text = decodeGsm7(septets), encoding = SmsEncoding.GSM7, concat = concat, raw = raw)
            }
            Alphabet.UCS2 -> {
                val body = payload.copyOfRange(headerBytes, payload.size)
                val text = decodeUtf16(body)
                if (text != null) return base.with(text = text, encoding = SmsEncoding.UCS2, concat = concat, raw = raw)
                val fallback = decodeTextBytes(body, header) ?: throw SmsException("UCS2 SMS has invalid UTF-16 data")
                base.with(text = fallback.first, encoding = fallback.second, concat = concat, raw = raw)
            }
            else -> {
                val body = payload.copyOfRange(headerBytes, payload.size)
                val detected = decodeTextBytes(body, header)
                if (detected != null) return base.with(text = detected.first, encoding = detected.second, concat = concat, raw = raw)
                base.with(text = body.toHex().uppercase(), encoding = SmsEncoding.BINARY, concat = concat, raw = raw)
            }
        }
    }

    private fun SmsMessage.with(text: String, encoding: SmsEncoding, concat: SmsConcat?, raw: String) = SmsMessage(
        direction, from, to, text, encoding, serviceCenterTimestamp, dischargeTimestamp, messageReference, statusCode, deliveryStatus,
        concat, protocolId, dataCodingScheme, raw, simDataDownload, decodeError,
    )

    private fun decodeUtf16(payload: ByteArray): String? {
        if (payload.isEmpty()) return ""
        if (payload.size % 2 != 0) return null
        var data = payload
        val littleEndian = payload.size >= 2 && payload.u8(0) == 0xff && payload.u8(1) == 0xfe
        if ((payload.u8(0) == 0xfe && payload.u8(1) == 0xff) || littleEndian) data = payload.copyOfRange(2, payload.size)
        val text = String(data, if (littleEndian) Charsets.UTF_16LE else Charsets.UTF_16BE)
        return if (!text.contains('�') && readable(text)) text else null
    }

    private fun decodeTextBytes(payload: ByteArray, header: ByteArray): Pair<String, SmsEncoding>? {
        if (hasPortAddressing(header) || payload.isEmpty()) return null
        if (payload.size >= 2 && ((payload.u8(0) == 0xfe && payload.u8(1) == 0xff) || (payload.u8(0) == 0xff && payload.u8(1) == 0xfe))) {
            decodeUtf16(payload)?.let { return it to SmsEncoding.UCS2 }
        }
        val utf8 = runCatching { Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(payload)).toString() }.getOrNull()
        if (utf8 != null && readable(utf8)) return utf8 to SmsEncoding.UTF8
        if (payload.any { it.toInt() and 0x80 != 0 }) {
            val gb = runCatching { String(payload, Charset.forName("GB18030")) }.getOrNull()
            if (gb != null && gb.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN } && readable(gb)) return gb to SmsEncoding.GB18030
        }
        latin1(payload)?.let { return it to SmsEncoding.LATIN1 }
        return null
    }

    private fun latin1(payload: ByteArray): String? {
        var ascii = 0
        val characters = StringBuilder()
        for (byte in payload) {
            val value = byte.toInt() and 0xff
            when {
                value == '\n'.code || value == '\r'.code || value == '\t'.code || value in 0x20..0x7e -> ascii++
                value >= 0xa0 -> Unit
                else -> return null
            }
            characters.append(value.toChar())
        }
        if (ascii == 0 || ascii * 2 < payload.size) return null
        val text = characters.toString()
        return if (readable(text)) text else null
    }

    private fun readable(text: String): Boolean {
        if (text.isEmpty()) return true
        var printable = 0
        var total = 0
        var index = 0
        while (index < text.length) {
            val code = text.codePointAt(index)
            index += Character.charCount(code)
            total++
            val type = Character.getType(code)
            val isPrintable = type != Character.CONTROL.toInt() && type != Character.UNASSIGNED.toInt() && type != Character.SURROGATE.toInt() &&
                type != Character.FORMAT.toInt() && type != Character.PRIVATE_USE.toInt()
            if (isPrintable || code == '\n'.code || code == '\r'.code || code == '\t'.code) printable++
        }
        return printable * 100 >= total * 90
    }

    private fun hasPortAddressing(header: ByteArray): Boolean {
        var index = 0
        while (index + 1 < header.size) {
            val id = header.u8(index)
            val length = header.u8(index + 1)
            index += 2
            if (index + length > header.size) return true
            if ((id == 0x04 && length == 2) || (id == 0x05 && length == 4)) return true
            index += length
        }
        return false
    }

    fun parseConcat(header: ByteArray): SmsConcat? {
        var index = 0
        while (index + 1 < header.size) {
            val id = header.u8(index)
            val length = header.u8(index + 1)
            index += 2
            if (index + length > header.size) return null
            when {
                id == 0x00 && length == 3 -> return SmsConcat(header.u8(index), header.u8(index + 1), header.u8(index + 2))
                id == 0x08 && length == 4 -> return SmsConcat((header.u8(index) shl 8) or header.u8(index + 1), header.u8(index + 2), header.u8(index + 3))
            }
            index += length
        }
        return null
    }

    /** A TS 23.040 seven-octet timestamp as epoch milliseconds, or null when invalid. */
    fun decodeTimestamp(value: ByteArray): Long? {
        if (value.size != 7) return null
        fun component(octet: Int): Int? {
            val low = octet and 0x0f
            val high = octet ushr 4
            return if (low > 9 || high > 9) null else low * 10 + high
        }
        val parts = IntArray(6)
        for (index in 0 until 6) parts[index] = component(value.u8(index)) ?: return null
        var zone = value.u8(6)
        val negative = zone and 0x08 != 0
        zone = zone and 0x08.inv()
        val quarters = component(zone) ?: return null
        if (quarters > 56) return null
        var offsetSeconds = quarters * 15 * 60
        if (negative) offsetSeconds = -offsetSeconds
        val year = if (parts[0] >= 90) 1900 + parts[0] else 2000 + parts[0]
        if (parts[1] !in 1..12 || parts[2] !in 1..31 || parts[3] > 23 || parts[4] > 59 || parts[5] > 60) return null
        val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        calendar.clear()
        calendar.set(year, parts[1] - 1, parts[2], parts[3], parts[4], minOf(parts[5], 59))
        return calendar.timeInMillis - offsetSeconds * 1000L
    }
}
