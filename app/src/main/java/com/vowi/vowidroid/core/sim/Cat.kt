package com.vowi.vowidroid.core.sim

import com.vowi.vowidroid.core.util.CoreLog

/**
 * What a card asks of its terminal through proactive commands (TS 102 223)
 * while VowiDroid is driving it. Outside an SMS-PP data download nothing is
 * served: [NONE] turns every request down as beyond the terminal's abilities.
 */
interface ProactiveHandler {
    /**
     * Sends [tpdu], an SMS-SUBMIT the card prepared, through [serviceCentre]
     * (type-of-address octet and BCD digits; empty for the line's own centre).
     * True once the network accepted it.
     */
    fun sendSms(serviceCentre: ByteArray, tpdu: ByteArray): Boolean

    /** The card changed its files; [qualifier] as in TS 102 223 §8.6, 4 meaning the card wants a reset. */
    fun refresh(qualifier: Int)

    object NONE : ProactiveHandler {
        override fun sendSms(serviceCentre: ByteArray, tpdu: ByteArray): Boolean = false
        override fun refresh(qualifier: Int) = Unit
    }
}

/** The card's answer to an SMS-PP data download (TS 31.111 §7.1.1): taken, refused with [data], or busy for now. */
class DownloadResult(val accepted: Boolean, val busy: Boolean, val data: ByteArray) {
    companion object {
        fun accepted(data: ByteArray = ByteArray(0)) = DownloadResult(true, false, data)
        fun refused(data: ByteArray) = DownloadResult(false, false, data)
        val BUSY = DownloadResult(false, true, ByteArray(0))
    }
}

/**
 * A card in a reader with its toolkit served the way a phone's modem serves
 * it: TERMINAL PROFILE once after each power-on, and whenever a status word
 * says a proactive command is pending (91 xx), the command is fetched,
 * answered and the original response handed back as plain success. Every
 * other command passes through unchanged, so the USIM code never sees the
 * toolkit.
 */
class ToolkitCard(private val card: SmartCard, private val powerCycle: () -> Int) : SmartCard {
    /** Who serves the card's requests right now; the data download sets it for its duration. */
    @Volatile
    var handler: ProactiveHandler = ProactiveHandler.NONE

    /** False once the card refused the terminal profile, so ENVELOPE is not even tried. */
    @Volatile
    var toolkit = true
        private set

    private var profiledCycle = -1

    override fun transmit(command: ByteArray): ApduResponse {
        ensureProfile()
        return settle(card.transmit(command))
    }

    private fun ensureProfile() {
        val cycle = powerCycle()
        if (cycle == profiledCycle) return
        profiledCycle = cycle
        val response = card.transmit(Cat.TERMINAL_PROFILE)
        toolkit = response.sw == 0x9000 || Cat.proactivePending(response.sw)
        if (!toolkit) {
            CoreLog.i(TAG, "the card took no terminal profile (status %04X); its toolkit stays unserved".format(response.sw))
            return
        }
        settle(response)
    }

    /** Serves pending proactive commands until the card's status word is plain. */
    private fun settle(response: ApduResponse): ApduResponse {
        var current = response
        var rounds = 0
        while (Cat.proactivePending(current.sw)) {
            if (++rounds > MAX_ROUNDS) throw SimCardException("the card keeps raising proactive commands")
            val fetched = card.transmit(Cat.fetch(current.sw and 0xff))
            if (fetched.sw != 0x9000 || fetched.data.isEmpty()) {
                CoreLog.w(TAG, "FETCH failed with status %04X".format(fetched.sw))
                break
            }
            current = card.transmit(Cat.respond(fetched.data, handler))
        }
        return if (Cat.proactivePending(response.sw)) ApduResponse(response.data, 0x9000) else response
    }

    companion object {
        private const val TAG = "SIM"
        private const val MAX_ROUNDS = 16
    }
}

/** The Card Application Toolkit commands VowiDroid issues (TS 102 223, TS 31.111). */
object Cat {
    /**
     * What this terminal offers (TS 102 223 §5.2): profile download, SMS-PP
     * data download with 9E answers, command results, REFRESH, MORE TIME and
     * SEND SHORT MESSAGE. Nothing that needs a screen, keys or polling.
     */
    val TERMINAL_PROFILE: ByteArray = byteArrayOf(
        0x80.toByte(), 0x10, 0x00, 0x00, 0x09,
        0x13, 0x01, 0x88.toByte(), 0x02, 0x00, 0x00, 0x00, 0x00, 0x00,
    )

    const val CMD_REFRESH = 0x01
    const val CMD_MORE_TIME = 0x02
    const val CMD_SEND_SMS = 0x13

    const val RESULT_OK = 0x00
    const val RESULT_BEYOND_CAPABILITIES = 0x30
    const val RESULT_NOT_UNDERSTOOD = 0x32
    const val RESULT_SMS_ERROR = 0x34

    private const val TAG_COMMAND_DETAILS = 0x01
    private const val TAG_DEVICE_IDENTITIES = 0x02
    private const val TAG_RESULT = 0x03
    private const val TAG_ADDRESS = 0x06
    private const val TAG_SMS_TPDU = 0x0b
    private const val TAG_PROACTIVE = 0xd0
    private const val TAG_SMS_PP_DOWNLOAD = 0xd1
    private const val DEVICE_TERMINAL = 0x82
    private const val DEVICE_NETWORK = 0x83
    private const val DEVICE_UICC = 0x81

    /** 91 xx: the command went fine and the card has a proactive command of xx bytes waiting. */
    fun proactivePending(sw: Int): Boolean = sw shr 8 == 0x91

    fun fetch(length: Int): ByteArray = byteArrayOf(0x80.toByte(), 0x12, 0x00, 0x00, length.toByte())

    /** ENVELOPE (SMS-PP DOWNLOAD): the centre's address and the SMS-DELIVER TPDU, from the network to the card (TS 31.111 §7.1.1). */
    fun smsDownloadEnvelope(serviceCentre: ByteArray, tpdu: ByteArray): ByteArray {
        require(tpdu.isNotEmpty()) { "an SMS-PP download needs a TPDU" }
        var body = tlv(TAG_DEVICE_IDENTITIES or 0x80, byteArrayOf(DEVICE_NETWORK.toByte(), DEVICE_UICC.toByte()))
        if (serviceCentre.isNotEmpty()) body += tlv(TAG_ADDRESS, serviceCentre)
        body += tlv(TAG_SMS_TPDU or 0x80, tpdu)
        val data = tlv(TAG_SMS_PP_DOWNLOAD, body)
        require(data.size <= 255) { "the SMS-PP download does not fit one ENVELOPE" }
        return byteArrayOf(0x80.toByte(), 0xc2.toByte(), 0x00, 0x00, data.size.toByte()) + data
    }

    /**
     * Hands an SMS-PP data download to [card] and reads its verdict: plain
     * success (any response packet comes back as data, a reader having
     * fetched a 9F answer already), 9E with an error packet, or 93 00 busy.
     */
    fun smsDownload(card: SmartCard, serviceCentre: ByteArray, tpdu: ByteArray): DownloadResult {
        val response = card.transmit(smsDownloadEnvelope(serviceCentre, tpdu))
        return when {
            response.sw == 0x9000 -> DownloadResult.accepted(response.data)
            response.sw shr 8 == 0x9e -> {
                val more = card.transmit(byteArrayOf(0x00, 0xc0.toByte(), 0x00, 0x00, (response.sw and 0xff).toByte()))
                DownloadResult.refused(more.data)
            }
            response.sw == 0x9300 -> DownloadResult.BUSY
            else -> throw SimCardException("ENVELOPE failed with status %04X".format(response.sw))
        }
    }

    /**
     * Answers one fetched proactive command: a text to send goes to
     * [handler], a refresh is noted, MORE TIME is granted, and anything else
     * is turned down as beyond the terminal's abilities. Returns the
     * TERMINAL RESPONSE to send.
     */
    fun respond(proactive: ByteArray, handler: ProactiveHandler): ByteArray {
        val command = parseProactive(proactive)
        val details = command.details
        val type = details?.getOrNull(1)?.toInt()?.and(0xff) ?: -1
        val qualifier = details?.getOrNull(2)?.toInt()?.and(0xff) ?: 0
        var result = byteArrayOf(RESULT_OK.toByte())
        when {
            details == null || details.size < 3 -> result = byteArrayOf(RESULT_NOT_UNDERSTOOD.toByte())
            type == CMD_MORE_TIME -> Unit
            type == CMD_REFRESH -> {
                CoreLog.i(TAG, "the card asks for a refresh (mode $qualifier)")
                handler.refresh(qualifier)
            }
            type == CMD_SEND_SMS -> result = sendSms(command, qualifier, handler)
            else -> {
                CoreLog.i(TAG, "proactive command %02X turned down: beyond this terminal".format(type))
                result = byteArrayOf(RESULT_BEYOND_CAPABILITIES.toByte())
            }
        }
        val body = tlv(TAG_COMMAND_DETAILS or 0x80, details ?: byteArrayOf(0, type.toByte(), qualifier.toByte())) +
            tlv(TAG_DEVICE_IDENTITIES or 0x80, byteArrayOf(DEVICE_TERMINAL.toByte(), DEVICE_UICC.toByte())) +
            tlv(TAG_RESULT or 0x80, result)
        return byteArrayOf(0x80.toByte(), 0x14, 0x00, 0x00, body.size.toByte()) + body
    }

    private fun sendSms(command: Proactive, qualifier: Int, handler: ProactiveHandler): ByteArray {
        if (handler === ProactiveHandler.NONE) return byteArrayOf(RESULT_BEYOND_CAPABILITIES.toByte())
        val tpdu = command.smsTpdu ?: return byteArrayOf(RESULT_NOT_UNDERSTOOD.toByte())
        val packed = if (qualifier and 0x01 != 0) packSubmit(tpdu) else tpdu
        if (packed == null) return byteArrayOf(RESULT_BEYOND_CAPABILITIES.toByte())
        CoreLog.i(TAG, "the card asks for a text to be sent (${packed.size}-byte TPDU)")
        return if (handler.sendSms(command.address ?: ByteArray(0), packed)) byteArrayOf(RESULT_OK.toByte()) else byteArrayOf(RESULT_SMS_ERROR.toByte(), 41)
    }

    /**
     * Packs the user data of an SMS-SUBMIT the card left as one septet per
     * octet (TS 102 223 §6.4.10, "packing required"); null when the data has a
     * header or an alphabet this does not cover, which the card is told.
     */
    fun packSubmit(tpdu: ByteArray): ByteArray? {
        if (tpdu.size < 4 || tpdu[0].toInt() and 0x03 != 0x01) return null
        val firstOctet = tpdu[0].toInt() and 0xff
        if (firstOctet and 0x40 != 0) return null
        var index = 2
        val digits = tpdu[index].toInt() and 0xff
        index += 2 + (digits + 1) / 2
        if (index + 2 > tpdu.size) return null
        val dcs = tpdu[index + 1].toInt() and 0xff
        index += 2
        index += when (firstOctet shr 3 and 0x03) {
            0 -> 0
            2 -> 1
            else -> 7
        }
        if (index >= tpdu.size) return null
        val sevenBit = (dcs and 0xc0 == 0 && dcs and 0x0c == 0) || (dcs and 0xf0 == 0xf0 && dcs and 0x04 == 0)
        if (!sevenBit) return tpdu
        val length = tpdu[index].toInt() and 0xff
        val septets = tpdu.copyOfRange(index + 1, tpdu.size)
        if (septets.size != length) return null
        val packed = com.vowi.vowidroid.core.sms.SmsPdu.packSeptets(septets, 0)
        return tpdu.copyOfRange(0, index + 1) + packed
    }

    private class Proactive(val details: ByteArray?, val address: ByteArray?, val smsTpdu: ByteArray?)

    private fun parseProactive(data: ByteArray): Proactive {
        var details: ByteArray? = null
        var address: ByteArray? = null
        var tpdu: ByteArray? = null
        val inner = if (data.isNotEmpty() && data[0].toInt() and 0xff == TAG_PROACTIVE) unwrap(data) else data
        var index = 0
        while (inner.size - index >= 2) {
            val tag = inner[index].toInt() and 0x7f
            index++
            var length = inner[index].toInt() and 0xff
            index++
            if (length == 0x81 && index < inner.size) {
                length = inner[index].toInt() and 0xff
                index++
            }
            if (index + length > inner.size) break
            val value = inner.copyOfRange(index, index + length)
            index += length
            when (tag) {
                TAG_COMMAND_DETAILS -> details = value
                TAG_ADDRESS -> address = value
                TAG_SMS_TPDU -> tpdu = value
            }
        }
        return Proactive(details, address, tpdu)
    }

    private fun unwrap(data: ByteArray): ByteArray {
        if (data.size < 2) return ByteArray(0)
        var index = 1
        var length = data[index].toInt() and 0xff
        index++
        if (length == 0x81 && index < data.size) {
            length = data[index].toInt() and 0xff
            index++
        }
        return data.copyOfRange(index, minOf(data.size, index + length))
    }

    private fun tlv(tag: Int, value: ByteArray): ByteArray {
        val length = if (value.size < 128) byteArrayOf(value.size.toByte()) else byteArrayOf(0x81.toByte(), value.size.toByte())
        return byteArrayOf(tag.toByte()) + length + value
    }

    private const val TAG = "SIM"
}
