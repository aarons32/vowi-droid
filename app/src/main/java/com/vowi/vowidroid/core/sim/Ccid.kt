package com.vowi.vowidroid.core.sim

import java.io.IOException

/**
 * A user-space USB CCID driver (USB CCID specification 1.1). Android gives
 * apps a USB reader's bulk endpoints through the USB host API; everything
 * above the raw bulk transfers lives here, so it can be tested on any JVM.
 */

/** One claimed CCID interface's bulk pair. [bulkIn] returns at most [maxLength] bytes from one transfer, or nothing on timeout. */
interface CcidTransport {
    fun bulkOut(data: ByteArray, timeoutMs: Int)
    fun bulkIn(maxLength: Int, timeoutMs: Int): ByteArray
}

/** A reader or card failure, in words the user can act on. */
open class SimCardException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** The reader holds no card. */
class NoCardException : SimCardException("no SIM card is in the reader")

/** One APDU's answer: its data and the status word, e.g. 0x9000. */
class ApduResponse(val data: ByteArray, val sw: Int)

/** The fields of the CCID class descriptor (CCID 1.1 table 5.1-1) the driver depends on. */
data class CcidDescriptor(
    val maxSlotIndex: Int,
    val voltageSupport: Int,
    val protocols: Long,
    val features: Long,
    val maxMessageLength: Long,
    val interfaceNumber: Int,
) {
    val exchangeLevel: Long get() = features and EXCHANGE_MASK

    /** TPDU and APDU readers are supported; character-level readers are not. */
    val supported: Boolean get() = exchangeLevel == EXCHANGE_TPDU || exchangeLevel == EXCHANGE_SHORT_APDU || exchangeLevel == EXCHANGE_EXTENDED_APDU

    val messageLimit: Int get() = if (maxMessageLength < HEADER + 5 || maxMessageLength > 65_544) DEFAULT_MESSAGE_LENGTH else maxMessageLength.toInt()

    /** bPowerSelect values to try: ISO/IEC 7816-3 starts a card at its lowest class, so voltages go upward. */
    fun powerSelections(): ByteArray {
        if (features and FEATURE_AUTO_VOLTAGE != 0L || voltageSupport == 0) return byteArrayOf(0x00)
        val selections = ArrayList<Byte>()
        if (voltageSupport and 0x04 != 0) selections += 0x03 // 1.8 V
        if (voltageSupport and 0x02 != 0) selections += 0x02 // 3.0 V
        if (voltageSupport and 0x01 != 0) selections += 0x01 // 5.0 V
        return selections.toByteArray()
    }

    companion object {
        const val HEADER = 10
        const val DEFAULT_MESSAGE_LENGTH = 271
        const val FEATURE_AUTO_PARAMETERS = 0x00000002L
        const val FEATURE_AUTO_VOLTAGE = 0x00000008L
        const val FEATURE_AUTO_PPS_CCID = 0x00000040L
        const val FEATURE_AUTO_PPS_ACTIVE = 0x00000080L
        const val EXCHANGE_MASK = 0x00070000L
        const val EXCHANGE_TPDU = 0x00010000L
        const val EXCHANGE_SHORT_APDU = 0x00020000L
        const val EXCHANGE_EXTENDED_APDU = 0x00040000L

        /**
         * Finds the CCID class descriptor in a device's raw descriptors, as
         * UsbDeviceConnection.getRawDescriptors returns them. Some readers put
         * it after their endpoints, and some use the vendor interface class,
         * so the whole interface block is searched.
         */
        fun parse(raw: ByteArray): CcidDescriptor? {
            var inCcidInterface = false
            var interfaceNumber = 0
            var offset = 0
            while (offset + 2 <= raw.size) {
                val length = raw[offset].toInt() and 0xff
                if (length < 2 || offset + length > raw.size) break
                when (raw[offset + 1].toInt() and 0xff) {
                    0x04 -> if (length >= 9) {
                        val interfaceClass = raw[offset + 5].toInt() and 0xff
                        inCcidInterface = interfaceClass == 0x0b || interfaceClass == 0xff
                        interfaceNumber = raw[offset + 2].toInt() and 0xff
                    }
                    0x21 -> if (inCcidInterface && length >= 0x36) {
                        return CcidDescriptor(
                            maxSlotIndex = raw[offset + 4].toInt() and 0xff,
                            voltageSupport = raw[offset + 5].toInt() and 0xff,
                            protocols = le32(raw, offset + 6),
                            features = le32(raw, offset + 40),
                            maxMessageLength = le32(raw, offset + 44),
                            interfaceNumber = interfaceNumber,
                        )
                    }
                }
                offset += length
            }
            return null
        }

        private fun le32(data: ByteArray, offset: Int): Long =
            (data[offset].toLong() and 0xff) or ((data[offset + 1].toLong() and 0xff) shl 8) or
                ((data[offset + 2].toLong() and 0xff) shl 16) or ((data[offset + 3].toLong() and 0xff) shl 24)
    }
}

/** A smart card that takes APDUs. */
fun interface SmartCard {
    fun transmit(command: ByteArray): ApduResponse
}

/**
 * One CCID reader with one slot. Every exchange is serialized; the card is
 * powered on demand and kept powered, as pcscd does, until [powerOff].
 */
class CcidReader(private val transport: CcidTransport, val descriptor: CcidDescriptor) : SmartCard {
    /** How many times the card was powered on, so a toolkit wrapper knows when it starts afresh. */
    @Volatile
    var powerCycles = 0
        private set

    enum class Slot { ACTIVE, INACTIVE, ABSENT }

    private var sequence = 0
    private var powered = false

    /** The card's answer to reset while it is powered. */
    @get:Synchronized
    var atr: ByteArray? = null
        private set

    init {
        require(descriptor.supported) { "character-level CCID readers are not supported (features %08x)".format(descriptor.features) }
    }

    @Synchronized
    fun slotStatus(): Slot {
        val response = exchange(GET_SLOT_STATUS, ByteArray(3), ByteArray(0), SLOT_STATUS)
        val state = when (response[7].toInt() and 0x03) {
            ICC_ACTIVE -> Slot.ACTIVE
            ICC_INACTIVE -> Slot.INACTIVE
            else -> Slot.ABSENT
        }
        if (state != Slot.ACTIVE) powered = false
        if (state == Slot.ABSENT) atr = null
        return state
    }

    /** Powers the card on if needed and returns its ATR; throws [NoCardException] for an empty slot. */
    @Synchronized
    fun powerOn(): ByteArray {
        val state = slotStatus()
        if (state == Slot.ABSENT) throw NoCardException()
        val current = atr
        if (powered && state == Slot.ACTIVE && current != null) return current
        var lastError: Exception? = null
        for ((index, selection) in descriptor.powerSelections().withIndex()) {
            if (index > 0) runCatching { exchange(POWER_OFF, ByteArray(3), ByteArray(0), SLOT_STATUS) }
            val response = try {
                exchange(POWER_ON, byteArrayOf(selection, 0, 0), ByteArray(0), DATA_BLOCK)
            } catch (error: CcidCommandException) {
                lastError = error
                // A card that stays mute at this voltage may answer at the next one.
                if (error.code == ERROR_ICC_MUTE) continue
                throw error
            }
            val answer = response.copyOfRange(HEADER, response.size)
            if (answer.isEmpty()) {
                lastError = SimCardException("the card returned an empty ATR")
                continue
            }
            atr = answer
            powered = true
            powerCycles++
            try {
                configure(answer)
            } catch (error: Exception) {
                powered = false
                throw error
            }
            return answer
        }
        throw SimCardException("could not power the card on: ${lastError?.message ?: "no usable voltage"}", lastError)
    }

    /** Powers the card down, so it can be taken out safely. */
    @Synchronized
    fun powerOff() {
        powered = false
        exchange(POWER_OFF, ByteArray(3), ByteArray(0), SLOT_STATUS)
    }

    /** Sends one APDU, following 61xx (GET RESPONSE) and 6Cxx (wrong Le), and returns the whole answer. */
    @Synchronized
    override fun transmit(command: ByteArray): ApduResponse = transmit(command, 0)

    private fun transmit(command: ByteArray, depth: Int): ApduResponse {
        if (depth > 8) throw SimCardException("too many APDU continuations")
        val response = transmitRaw(command)
        val sw1 = response.sw shr 8
        val sw2 = response.sw and 0xff
        if (sw1 == 0x6c && command.size >= 5) {
            val retry = command.copyOf()
            retry[retry.size - 1] = sw2.toByte()
            return transmit(retry, depth + 1)
        }
        if (sw1 == 0x61 || sw1 == 0x9f) {
            val more = transmit(byteArrayOf(0x00, 0xc0.toByte(), 0x00, 0x00, sw2.toByte()), depth + 1)
            return ApduResponse(response.data + more.data, more.sw)
        }
        return response
    }

    private fun transmitRaw(command: ByteArray): ApduResponse {
        if (command.isEmpty()) throw SimCardException("empty APDU")
        val block = if (descriptor.exchangeLevel == CcidDescriptor.EXCHANGE_TPDU) apduToT0Tpdu(command) else command
        if (block.size + HEADER > descriptor.messageLimit) throw SimCardException("an APDU of ${command.size} bytes exceeds the reader's message size")
        if (!powered) powerOn()
        val response = exchange(XFR_BLOCK, ByteArray(3), block, DATA_BLOCK)
        val answer = response.copyOfRange(HEADER, response.size)
        if (answer.size < 2) throw SimCardException("the card's answer has no status word")
        val sw = ((answer[answer.size - 2].toInt() and 0xff) shl 8) or (answer[answer.size - 1].toInt() and 0xff)
        return ApduResponse(answer.copyOf(answer.size - 2), sw)
    }

    /** T=0 parameters for TPDU readers that do not take them from the ATR; APDU readers choose their own. */
    private fun configure(answer: ByteArray) {
        if (descriptor.exchangeLevel != CcidDescriptor.EXCHANGE_TPDU || descriptor.features and CcidDescriptor.FEATURE_AUTO_PARAMETERS != 0L) return
        val parsed = Atr.parse(answer)
        if (!parsed.offersT0) throw SimCardException("the card does not offer T=0, which this reader needs")
        val findexDindex = if (descriptor.features and (CcidDescriptor.FEATURE_AUTO_PPS_CCID or CcidDescriptor.FEATURE_AUTO_PPS_ACTIVE) != 0L && parsed.ta1 != 0) parsed.ta1 else 0x11
        val convention = if (parsed.inverse) 0x02 else 0x00
        val waiting = if (parsed.tc2 != 0) parsed.tc2 else 0x0a
        val parameters = byteArrayOf(findexDindex.toByte(), convention.toByte(), parsed.tc1.toByte(), waiting.toByte(), 0x00)
        exchange(SET_PARAMETERS, ByteArray(3), parameters, PARAMETERS)
    }

    /** Sends one command on slot 0 and returns the matching reply, header included; time extensions are waited out. */
    private fun exchange(type: Int, specific: ByteArray, data: ByteArray, expected: Int): ByteArray {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        sequence = (sequence + 1) and 0xff
        val sent = sequence
        val message = ByteArray(HEADER + data.size)
        message[0] = type.toByte()
        message[1] = data.size.toByte()
        message[2] = (data.size shr 8).toByte()
        message[3] = (data.size shr 16).toByte()
        message[4] = (data.size shr 24).toByte()
        message[5] = 0 // slot
        message[6] = sent.toByte()
        specific.copyInto(message, 7, 0, 3)
        data.copyInto(message, HEADER)
        try {
            transport.bulkOut(message, TRANSFER_TIMEOUT_MS)
        } catch (error: IOException) {
            throw SimCardException("could not write to the USB reader: ${error.message}", error)
        }
        while (true) {
            val response = readMessage(deadline)
            if (response[6].toInt() and 0xff != sent) continue // a late reply to a command that timed out earlier
            if (response[0].toInt() and 0xff != expected) {
                throw SimCardException("the USB reader replied %02x to command %02x".format(response[0].toInt() and 0xff, type))
            }
            when ((response[7].toInt() and 0xff) shr 6) {
                0 -> return response
                2 -> continue // the card asked for more time
                else -> throw CcidCommandException(type, response[7].toInt() and 0xff, response[8].toInt() and 0xff)
            }
        }
    }

    private fun readMessage(deadline: Long): ByteArray {
        var message = ByteArray(0)
        while (true) {
            var want = MAX_BULK_READ
            if (message.size >= HEADER) {
                val announced = (message[1].toLong() and 0xff) or ((message[2].toLong() and 0xff) shl 8) or
                    ((message[3].toLong() and 0xff) shl 16) or ((message[4].toLong() and 0xff) shl 24)
                val total = HEADER + announced
                if (total > descriptor.messageLimit + MAX_BULK_READ) throw SimCardException("the USB reader announced an oversized $total-byte reply")
                if (message.size >= total) return message.copyOf(total.toInt())
                want = minOf(total - message.size, MAX_BULK_READ.toLong()).toInt()
            }
            val chunk = try {
                transport.bulkIn(want, TRANSFER_TIMEOUT_MS)
            } catch (error: IOException) {
                throw SimCardException("could not read from the USB reader: ${error.message}", error)
            }
            if (chunk.isEmpty()) {
                if (System.currentTimeMillis() > deadline) throw SimCardException("the USB reader did not answer")
                continue
            }
            message += chunk
        }
    }

    /** A CCID command the reader could not carry out. */
    class CcidCommandException(val operation: Int, val status: Int, val code: Int) : SimCardException(
        when {
            status and 0x03 == ICC_ABSENT -> "no SIM card is in the reader"
            code == ERROR_ICC_MUTE -> "the card did not answer the reader; take it out and put it back"
            else -> "CCID command %02x failed (status %02x, error %02x)".format(operation, status, code)
        },
    ) {
        val noCard: Boolean get() = status and 0x03 == ICC_ABSENT
    }

    companion object {
        private const val HEADER = CcidDescriptor.HEADER
        private const val POWER_ON = 0x62
        private const val POWER_OFF = 0x63
        private const val GET_SLOT_STATUS = 0x65
        private const val SET_PARAMETERS = 0x61
        private const val XFR_BLOCK = 0x6f
        private const val DATA_BLOCK = 0x80
        private const val SLOT_STATUS = 0x81
        private const val PARAMETERS = 0x82
        private const val ICC_ACTIVE = 0
        private const val ICC_INACTIVE = 1
        private const val ICC_ABSENT = 2
        private const val ERROR_ICC_MUTE = 0xfe
        private const val MAX_BULK_READ = 16_384
        private const val TIMEOUT_MS = 30_000L
        private const val TRANSFER_TIMEOUT_MS = 5_000

        /**
         * Maps an ISO 7816-4 short APDU onto a T=0 command TPDU. Case 4 drops
         * Le; the card then answers 61xx and [transmit] issues GET RESPONSE.
         */
        fun apduToT0Tpdu(command: ByteArray): ByteArray {
            if (command.size < 4) throw SimCardException("an APDU is shorter than its header")
            if (command.size == 4) return command + byteArrayOf(0)
            if (command.size == 5) return command.copyOf()
            val lc = command[4].toInt() and 0xff
            if (lc == 0) throw SimCardException("extended APDUs are not supported over T=0")
            return when (command.size) {
                5 + lc -> command.copyOf()
                6 + lc -> command.copyOf(5 + lc)
                else -> throw SimCardException("APDU length ${command.size} does not match Lc $lc")
            }
        }
    }
}

/** The ISO/IEC 7816-3 interface bytes that select T=0 transmission parameters. */
internal class Atr(val inverse: Boolean, val ta1: Int, val tc1: Int, val tc2: Int, val offersT0: Boolean) {
    companion object {
        /** A card that names no protocol offers T=0. */
        fun parse(atr: ByteArray): Atr {
            if (atr.size < 2) return Atr(false, 0, 0, 0, true)
            val inverse = atr[0].toInt() and 0xff == 0x3f
            var ta1 = 0
            var tc1 = 0
            var tc2 = 0
            var index = 1
            var indicator = atr[index].toInt() and 0xff
            index++
            var explicitProtocols = false
            var sawT0 = false
            var level = 1
            while (true) {
                if (indicator and 0x10 != 0 && index < atr.size) {
                    if (level == 1) ta1 = atr[index].toInt() and 0xff
                    index++
                }
                if (indicator and 0x20 != 0) index++
                if (indicator and 0x40 != 0 && index < atr.size) {
                    when (level) {
                        1 -> tc1 = atr[index].toInt() and 0xff
                        2 -> tc2 = atr[index].toInt() and 0xff
                    }
                    index++
                }
                if (indicator and 0x80 == 0 || index >= atr.size) break
                indicator = atr[index].toInt() and 0xff
                index++
                explicitProtocols = true
                if (indicator and 0x0f == 0) sawT0 = true
                level++
            }
            return Atr(inverse, ta1, tc1, tc2, if (explicitProtocols) sawT0 else true)
        }
    }
}
