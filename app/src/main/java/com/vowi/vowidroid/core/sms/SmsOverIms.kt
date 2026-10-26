package com.vowi.vowidroid.core.sms

import com.vowi.vowidroid.core.ims.ImsException
import com.vowi.vowidroid.core.ims.ImsSession
import com.vowi.vowidroid.core.sim.DownloadResult
import com.vowi.vowidroid.core.ims.ImsSupport
import com.vowi.vowidroid.core.sip.SipBuilder
import com.vowi.vowidroid.core.sip.SipMessage
import com.vowi.vowidroid.core.util.CoreLog
import com.vowi.vowidroid.core.util.Rng
import com.vowi.vowidroid.core.util.toHex
import com.vowi.vowidroid.core.util.u8
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger

class ReceivedSms(
    val messageId: String,
    val from: String,
    val text: String,
    val timestamp: Long,
    val serviceCenterTimestamp: Long?,
    val encoding: SmsEncoding,
    val concat: SmsConcat?,
    val rpReference: Int,
    val callId: String,
    val rawTpdu: String,
    val decodeError: String,
)

class ReceivedSmsStatus(
    val to: String,
    val messageReference: Int,
    val statusCode: Int,
    val deliveryStatus: String,
    val serviceCenterTimestamp: Long?,
    val dischargeTimestamp: Long?,
    val timestamp: Long,
)

class SmsSubmitPart(val part: Int, val total: Int, val reference: Int, val sipCode: Int, val accepted: Boolean, val submittedAt: Long)

class SmsSubmitResult(
    val to: String,
    val encoding: SmsEncoding,
    val submittedAt: Long,
    val partsTotal: Int,
    val concatReference: Int?,
    val parts: List<SmsSubmitPart>,
    val status: String,
) {
    val accepted: Boolean get() = parts.size == partsTotal && parts.all { it.accepted }
}

class SmsNotReadyException(message: String) : ImsException(message)
class SmsRejectedException(val sipCode: Int) : ImsException("SMS MESSAGE was rejected: SIP $sipCode")

/**
 * SMS over IMS (3GPP TS 24.341): mobile-terminated RP-DATA arrives in SIP
 * MESSAGE and is acknowledged with RP-ACK; mobile-originated texts go to the
 * service centre as RP-DATA in SIP MESSAGE.
 */
class SmsOverIms(private val session: ImsSession, private val smsCenter: () -> String) {
    interface Listener {
        /** Returns false when the text could not be stored; the network then gets RP-ERROR. */
        fun onSms(sms: ReceivedSms): Boolean
        fun onStatus(status: ReceivedSmsStatus): Boolean

        /**
         * The service centre's answer to a submitted part, by its RP message
         * reference: RP-ACK ([cause] null) or RP-ERROR with its RP-Cause.
         */
        fun onSubmitResult(reference: Int, cause: Int?) = Unit
    }

    @Volatile
    var listener: Listener? = null

    /** The card behind the line, for messages addressed to it; null from a phone slot, where the modem owns the card. */
    fun interface UiccDataDownload {
        fun deliver(serviceCentre: ByteArray, tpdu: ByteArray, sendSms: (ByteArray, ByteArray) -> Boolean): DownloadResult?
    }

    @Volatile
    var uicc: UiccDataDownload? = null

    /** Called when a message for the card had to be left with the network. */
    @Volatile
    var onSimDataUndeliverable: (() -> Unit)? = null
    private val rpReference = AtomicInteger(0)
    private val sendLock = Any()

    /** Handles an inbound MESSAGE; returns false when it is not an SMS. */
    fun handle(request: SipMessage, respond: (ByteArray) -> Unit): Boolean {
        if (request.method != "MESSAGE") return false
        val contentType = request.value("Content-Type").trim()
        if (!isSmsContentType(contentType)) return false
        respond(session.buildResponse(request, 200))
        CoreLog.i(TAG, "inbound SMS MESSAGE accepted (${request.body.size} bytes)")
        process(request)
        return true
    }

    private fun process(request: SipMessage) {
        val payload = try {
            extractPayload(request)
        } catch (error: Exception) {
            CoreLog.w(TAG, "inbound SMS body could not be read: ${error.message}")
            deliveryReport(request, buildRpError(0, 95))
            return
        }
        val rpdu = try {
            parseRpdu(payload)
        } catch (error: SmsException) {
            CoreLog.w(TAG, "inbound RPDU is invalid: ${error.message}")
            deliveryReport(request, buildRpError(if (payload.size > 1) payload.u8(1) else 0, 95))
            return
        }
        when (rpdu.messageType) {
            RP_ACK -> {
                CoreLog.i(TAG, "RP-ACK for submitted reference ${rpdu.reference}")
                listener?.onSubmitResult(rpdu.reference, null)
                return
            }
            RP_ERROR -> {
                // The centre refused the text after the IMS core had accepted the MESSAGE: the only failure word for it.
                val cause = rpdu.cause ?: 0
                CoreLog.w(TAG, "RP-ERROR for submitted reference ${rpdu.reference}: cause $cause, ${RpCauses.name(cause)}")
                listener?.onSubmitResult(rpdu.reference, cause)
                return
            }
            RP_DATA -> Unit
            else -> {
                CoreLog.i(TAG, "inbound RP message type ${rpdu.messageType} ignored")
                return
            }
        }
        val message = SmsPdu.decodeDeliver(rpdu.tpdu)
        val now = System.currentTimeMillis()
        val callId = request.callId
        if (message.decodeError.isNotEmpty()) CoreLog.w(TAG, "inbound TPDU decode problem: ${message.decodeError}")
        if (message.simDataDownload) {
            // SMS-PP data download is for the card, served off this thread: the card may ask for a text to be
            // sent in return, and that needs the connection's dispatcher free.
            Thread({ dataDownload(request, rpdu, message) }, "vowi-sms-pp").apply { isDaemon = true }.start()
            return
        }
        when {
            message.direction == SmsDirection.STATUS_REPORT -> {
                if ((message.messageReference == null || message.statusCode == null) && message.decodeError.isEmpty()) {
                    deliveryReport(request, buildRpError(rpdu.reference, 95))
                    return
                }
                val status = ReceivedSmsStatus(
                    to = message.to,
                    messageReference = message.messageReference ?: 0,
                    statusCode = message.statusCode ?: 0,
                    deliveryStatus = message.deliveryStatus,
                    serviceCenterTimestamp = message.serviceCenterTimestamp,
                    dischargeTimestamp = message.dischargeTimestamp,
                    timestamp = now,
                )
                if (listener?.onStatus(status) == false) {
                    deliveryReport(request, buildRpError(rpdu.reference, 22))
                    return
                }
                deliveryReport(request, buildRpAck(rpdu.reference))
            }
            message.direction == SmsDirection.RECEIVED || message.decodeError.isNotEmpty() -> {
                val received = ReceivedSms(
                    messageId = "ims:$callId:${rpdu.reference}",
                    from = message.from,
                    text = message.text,
                    timestamp = now,
                    serviceCenterTimestamp = message.serviceCenterTimestamp,
                    encoding = message.encoding,
                    concat = message.concat,
                    rpReference = rpdu.reference,
                    callId = callId,
                    rawTpdu = rpdu.tpdu.toHex().uppercase(),
                    decodeError = message.decodeError,
                )
                if (listener?.onSms(received) == false) {
                    deliveryReport(request, buildRpError(rpdu.reference, 22))
                    return
                }
                CoreLog.i(TAG, "inbound SMS processed (${message.encoding.wire}${if (message.concat != null) ", part ${message.concat.sequence}/${message.concat.total}" else ""})")
                deliveryReport(request, buildRpAck(rpdu.reference))
            }
            else -> {
                CoreLog.w(TAG, "inbound SMS has unexpected TPDU direction ${message.direction}")
                deliveryReport(request, buildRpError(rpdu.reference, 95))
            }
        }
    }

    /**
     * Hands a message meant for the card to it, as a phone's modem would, and
     * reports the card's answer to the network. Without a way to the card the
     * message is refused as a temporary failure, so the network keeps it and
     * may deliver it through the phone's own modem.
     */
    private fun dataDownload(request: SipMessage, rpdu: RpMessage, message: SmsMessage) {
        val result = try {
            uicc?.deliver(rpdu.originatingAddress, rpdu.tpdu) { centre, tpdu -> submitTpdu(centre, tpdu) }
        } catch (error: Exception) {
            CoreLog.w(TAG, "SIM data download failed at the card: ${error.message}")
            deliveryReport(request, buildRpError(rpdu.reference, RP_CAUSE_TEMPORARY_FAILURE))
            return
        }
        when {
            result == null -> {
                CoreLog.i(TAG, "inbound SIM data download left with the network: the card cannot be reached from here")
                onSimDataUndeliverable?.invoke()
                deliveryReport(request, buildRpError(rpdu.reference, RP_CAUSE_TEMPORARY_FAILURE))
            }
            result.busy -> {
                CoreLog.i(TAG, "inbound SIM data download: the card's toolkit is busy, so the network will retry")
                deliveryReport(request, buildRpError(rpdu.reference, RP_CAUSE_TEMPORARY_FAILURE))
            }
            result.accepted -> {
                CoreLog.i(TAG, "inbound SIM data download taken by the card" + if (result.data.isNotEmpty()) ", which answers ${result.data.size} bytes" else "")
                val report = if (result.data.isNotEmpty()) buildDeliverReport(null, message.protocolId, message.dataCodingScheme, result.data) else null
                deliveryReport(request, buildRpAck(rpdu.reference, report))
            }
            else -> {
                CoreLog.w(TAG, "inbound SIM data download refused by the card (${result.data.size} bytes of error data)")
                val report = buildDeliverReport(TP_FCS_SIM_DATA_DOWNLOAD_ERROR, message.protocolId, message.dataCodingScheme, result.data)
                deliveryReport(request, buildRpError(rpdu.reference, RP_CAUSE_TRANSFER_REJECTED, report))
            }
        }
    }

    /**
     * Sends one SMS-SUBMIT the card prepared, through [serviceCentre] (a
     * type-of-address octet and BCD digits, or empty for the line's own
     * centre); true once the IMS core accepted it.
     */
    fun submitTpdu(serviceCentre: ByteArray, tpdu: ByteArray): Boolean = synchronized(sendLock) {
        if (!session.smsReady || tpdu.size < 2) return false
        val address = serviceCentre.takeIf { it.size >= 2 } ?: runCatching { encodeRpAddress(smsCenter().trim()) }.getOrNull() ?: return false
        val reference = rpReference.getAndIncrement() and 0xff
        val submit = tpdu.copyOf()
        submit[1] = reference.toByte()
        val response = try {
            sendMessage("tel:" + decodeRpAddress(address), buildRpData(reference, address, submit), "", SMS_CONTENT_TYPE, "smsip", "")
        } catch (error: Exception) {
            CoreLog.w(TAG, "the card's text could not be sent: ${error.message}")
            return false
        }
        val accepted = response.statusCode in 200..299
        CoreLog.i(TAG, "the card's text was " + if (accepted) "accepted" else "refused with SIP ${response.statusCode}")
        accepted
    }

    private fun deliveryReport(request: SipMessage, report: ByteArray) {
        var target = SipMessage.firstUri(request.value("P-Asserted-Identity"))
        if (target.isEmpty()) target = SipMessage.firstUri(request.value("From"))
        if (target.isEmpty()) {
            CoreLog.w(TAG, "inbound SMS omitted a delivery-report target")
            return
        }
        try {
            // TS 24.341 5.3.2.4: the report goes from a public identity of the receiver.
            val response = sendMessage(target, report, request.callId, SMS_CONTENT_TYPE, "smsip", request.value("P-Called-Party-ID"))
            if (response.statusCode !in 200..299) CoreLog.w(TAG, "SMS delivery report returned SIP ${response.statusCode}")
        } catch (error: Exception) {
            CoreLog.w(TAG, "SMS delivery report failed: ${error.message}")
        }
    }

    /** Sends a text; each part is one MESSAGE to the service centre. */
    fun send(recipient: String, text: String): SmsSubmitResult = synchronized(sendLock) {
        if (!session.smsReady) throw SmsNotReadyException("SMS over IMS is not ready")
        val smsc = smsCenter().trim()
        if (smsc.isEmpty()) throw SmsNotReadyException("the SMS service centre address is unavailable")
        val parts = SmsPdu.prepareSubmit(recipient, text)
        val submittedAt = System.currentTimeMillis()
        val target = "tel:" + normalizeE164(smsc)
        if (session.identities.temporaryPublic) ImsSupport.originatingSmsPublicIdentity(session.identities.publicIdentity, session.evidence.pAssociatedUri)
        val results = ArrayList<SmsSubmitPart>()
        for (part in parts) {
            val reference = rpReference.getAndIncrement() and 0xff
            // The same value for TP-MR and RP-Message-Reference maps status reports back.
            part.tpdu[1] = reference.toByte()
            val rpdu = buildRpData(reference, smsc, part.tpdu)
            val response = try {
                sendMessage(target, rpdu, "", SMS_CONTENT_TYPE, "smsip", "")
            } catch (error: Exception) {
                CoreLog.w(TAG, "outbound SMS part ${part.part} failed: ${error.message}")
                throw error
            }
            val accepted = response.statusCode in 200..299
            results.add(SmsSubmitPart(part.part, part.total, reference, response.statusCode, accepted, System.currentTimeMillis()))
            if (!accepted) {
                CoreLog.w(TAG, "outbound SMS part ${part.part} was rejected with SIP ${response.statusCode}")
                throw SmsRejectedException(response.statusCode)
            }
        }
        CoreLog.i(TAG, "outbound SMS accepted (${parts.size} part(s), ${parts[0].encoding.wire})")
        SmsSubmitResult(parts[0].to, parts[0].encoding, submittedAt, parts.size, parts[0].concatReference, results, "accepted_by_ims")
    }

    /** One MESSAGE transaction on the main connection. */
    fun sendMessage(target: String, body: ByteArray, inReplyTo: String, contentType: String, acceptContactTag: String, preferredIdentity: String): SipMessage {
        val callId = Rng.bytes(18).toHex() + "@" + session.localHost
        val cseq = session.nextCseq()
        val identity = if (session.identities.temporaryPublic && contentType == SMS_CONTENT_TYPE && inReplyTo.isEmpty()) {
            ImsSupport.originatingSmsPublicIdentity(session.identities.publicIdentity, session.evidence.pAssociatedUri).first
        } else {
            ImsSupport.messagePublicIdentity(session.identities.publicIdentity, preferredIdentity, session.evidence.pAssociatedUri).first
        }
        val builder = SipBuilder.request("MESSAGE", target)
            .header("Via", session.viaHeader())
            .header("Max-Forwards", "70")
        for ((name, value) in session.securityHeaders()) builder.header(name, value)
        for ((name, value) in session.routeHeaders()) builder.header(name, value)
        builder.header("From", "<$identity>;tag=${session.fromTag}")
            .header("To", "<$target>")
            .header("Call-ID", callId)
            .header("CSeq", "$cseq MESSAGE")
            .header("P-Preferred-Identity", "<$identity>")
        if (session.accessNetworkInfo.isNotEmpty()) builder.header("P-Access-Network-Info", session.accessNetworkInfo)
        if (acceptContactTag.isNotEmpty()) builder.header("Accept-Contact", "*;+g.3gpp.$acceptContactTag")
        builder.header("Request-Disposition", "no-fork").header("Allow", "MESSAGE").header("User-Agent", session.userAgent)
        if (inReplyTo.isNotEmpty()) builder.header("In-Reply-To", inReplyTo)
        builder.header("Content-Transfer-Encoding", "binary")
        builder.body(contentType, body)
        return session.transaction(builder.build(), "$callId:$cseq:MESSAGE")
    }

    companion object {
        private const val TAG = "SMS"
        const val SMS_CONTENT_TYPE = "application/vnd.3gpp.sms"

        fun isSmsContentType(value: String): Boolean {
            val mediaType = value.substringBefore(';').trim().lowercase()
            if (mediaType == SMS_CONTENT_TYPE) return true
            return mediaType == "multipart/mixed" && SipMessage.parameter(value, "boundary")?.isNotBlank() == true
        }

        /** The RP-DATA payload from a MESSAGE body, which may be multipart or base64. */
        fun extractPayload(request: SipMessage): ByteArray {
            val contentType = request.value("Content-Type").trim()
            val mediaType = contentType.substringBefore(';').trim().lowercase()
            if (mediaType == SMS_CONTENT_TYPE) return decodeTransfer(request.body, request.value("Content-Transfer-Encoding"))
            require(mediaType == "multipart/mixed") { "unsupported SMS Content-Type \"$mediaType\"" }
            val boundary = SipMessage.parameter(contentType, "boundary")?.trim().orEmpty()
            require(boundary.isNotEmpty()) { "multipart SMS has no boundary" }
            for (part in multipart(request.body, boundary)) {
                val partType = part.first["content-type"]?.substringBefore(';')?.trim()?.lowercase() ?: continue
                if (partType != SMS_CONTENT_TYPE) continue
                return decodeTransfer(part.second, part.first["content-transfer-encoding"].orEmpty())
            }
            throw IllegalArgumentException("multipart MESSAGE omitted the SMS payload")
        }

        /** Splits a multipart body into (headers, body) parts. */
        fun multipart(body: ByteArray, boundary: String): List<Pair<Map<String, String>, ByteArray>> {
            val text = String(body, Charsets.ISO_8859_1)
            val delimiter = "--$boundary"
            val result = ArrayList<Pair<Map<String, String>, ByteArray>>()
            var index = text.indexOf(delimiter)
            while (index >= 0) {
                var start = index + delimiter.length
                if (text.startsWith("--", start)) break
                if (text.startsWith("\r\n", start)) start += 2 else if (text.startsWith("\n", start)) start += 1
                val end = text.indexOf(delimiter, start)
                if (end < 0) break
                var section = text.substring(start, end)
                if (section.endsWith("\r\n")) section = section.dropLast(2) else if (section.endsWith("\n")) section = section.dropLast(1)
                val headerEnd = section.indexOf("\r\n\r\n").let { if (it >= 0) it to 4 else section.indexOf("\n\n") to 2 }
                val headers = HashMap<String, String>()
                val content: String
                if (headerEnd.first >= 0) {
                    for (line in section.substring(0, headerEnd.first).split("\r\n", "\n")) {
                        val colon = line.indexOf(':')
                        if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
                    }
                    content = section.substring(headerEnd.first + headerEnd.second)
                } else {
                    content = section
                }
                result.add(headers to content.toByteArray(Charsets.ISO_8859_1))
                index = end
            }
            return result
        }

        fun decodeTransfer(body: ByteArray, encoding: String): ByteArray = when (encoding.trim().lowercase()) {
            "", "binary", "8bit", "7bit" -> body.copyOf()
            "base64" -> Base64.getMimeDecoder().decode(String(body, Charsets.ISO_8859_1).trim())
            "quoted-printable" -> quotedPrintable(body)
            else -> throw IllegalArgumentException("unsupported SMS Content-Transfer-Encoding \"$encoding\"")
        }

        private fun quotedPrintable(body: ByteArray): ByteArray {
            val out = ArrayList<Byte>()
            var index = 0
            while (index < body.size) {
                val byte = body.u8(index)
                if (byte == '='.code && index + 2 < body.size) {
                    val hex = String(body, index + 1, 2, Charsets.ISO_8859_1)
                    if (hex == "\r\n") {
                        index += 3
                        continue
                    }
                    hex.toIntOrNull(16)?.let {
                        out.add(it.toByte())
                        index += 3
                        return@let
                    } ?: run {
                        out.add(byte.toByte())
                        index++
                    }
                    continue
                }
                if (byte == '='.code && index + 1 < body.size && body[index + 1] == '\n'.code.toByte()) {
                    index += 2
                    continue
                }
                out.add(byte.toByte())
                index++
            }
            return out.toByteArray()
        }

        /** RP message types from the network (TS 24.011 8.2.2): RP-DATA, RP-ACK and RP-ERROR. */
        const val RP_DATA = 1
        const val RP_ACK = 3
        const val RP_ERROR = 5

        /** An RP message; [originatingAddress] is the centre's address of an RP-DATA (type-of-address octet and BCD digits), for the card. */
        class RpMessage(val messageType: Int, val reference: Int, val tpdu: ByteArray, val cause: Int? = null, val originatingAddress: ByteArray = ByteArray(0))

        /** Parses an RP-DATA (3GPP TS 24.011 7.3.1); an RP-ERROR carries its cause, other RP types only their reference. */
        fun parseRpdu(data: ByteArray): RpMessage {
            if (data.size < 2) throw SmsException("RPDU is truncated")
            val messageType = data.u8(0) and 0x07
            val reference = data.u8(1)
            if (messageType == RP_ERROR) {
                // RP-Cause (8.2.5.4): a length, then the cause value in the low seven bits of the next octet.
                val cause = if (data.size >= 4 && data.u8(2) >= 1) data.u8(3) and 0x7f else null
                return RpMessage(messageType, reference, ByteArray(0), cause)
            }
            if (messageType != RP_DATA) return RpMessage(messageType, reference, ByteArray(0))
            var index = 2
            var originating = ByteArray(0)
            for (position in 0 until 2) {
                if (index >= data.size) throw SmsException("RP-DATA address is truncated")
                val length = data.u8(index)
                index++
                if (length > data.size - index) throw SmsException("RP-DATA address length is invalid")
                if (position == 0) originating = data.copyOfRange(index, index + length)
                index += length
            }
            if (index >= data.size) throw SmsException("RP-DATA omitted user data")
            val length = data.u8(index)
            index++
            if (length == 0 || length > data.size - index) throw SmsException("RP-DATA user-data length is invalid")
            return RpMessage(messageType, reference, data.copyOfRange(index, index + length), originatingAddress = originating)
        }

        fun buildRpData(reference: Int, smsc: String, tpdu: ByteArray): ByteArray = buildRpData(reference, encodeRpAddress(smsc), tpdu)

        /** RP-DATA (TS 24.011 7.3.1.2) to the centre at [address], a type-of-address octet and BCD digits. */
        fun buildRpData(reference: Int, address: ByteArray, tpdu: ByteArray): ByteArray {
            if (address.size < 2 || address.size > 12) throw SmsException("the SMS service centre address is invalid")
            if (tpdu.isEmpty() || tpdu.size > 232) throw SmsException("SMS TPDU length is invalid")
            val out = ArrayList<Byte>()
            out.add(0x00)
            out.add(reference.toByte())
            out.add(0x00)
            out.add(address.size.toByte())
            for (byte in address) out.add(byte)
            out.add(tpdu.size.toByte())
            for (byte in tpdu) out.add(byte)
            return out.toByteArray()
        }

        /** RP-ACK (TS 24.011 7.3.3), with an SMS-DELIVER-REPORT as RP-User-Data when the card answered something. */
        fun buildRpAck(reference: Int, report: ByteArray? = null): ByteArray = byteArrayOf(0x02, reference.toByte()) + userData(report)

        fun buildRpError(reference: Int, cause: Int, report: ByteArray? = null): ByteArray =
            byteArrayOf(0x04, reference.toByte(), 0x01, (cause and 0x7f).toByte()) + userData(report)

        private fun userData(report: ByteArray?): ByteArray =
            if (report == null || report.isEmpty()) ByteArray(0) else byteArrayOf(0x41, report.size.toByte()) + report

        /**
         * An SMS-DELIVER-REPORT (TS 23.040 9.2.2.1a) carrying the card's answer
         * to a data download, with the message's protocol identifier and
         * coding scheme; [failureCause] is the TP-FCS of the RP-ERROR form.
         */
        fun buildDeliverReport(failureCause: Int?, protocolId: Int, dataCodingScheme: Int, data: ByteArray): ByteArray {
            if (data.size > 140) throw SmsException("the card's answer is too long for a delivery report")
            val head = if (failureCause == null) byteArrayOf(0x00) else byteArrayOf(0x00, failureCause.toByte())
            return head + byteArrayOf(0x07, protocolId.toByte(), dataCodingScheme.toByte(), data.size.toByte()) + data
        }

        /** The digits of an RP address, with a + when it is international. */
        fun decodeRpAddress(address: ByteArray): String {
            if (address.size < 2) throw SmsException("the RP address is too short")
            val digits = StringBuilder()
            for (octet in address.copyOfRange(1, address.size)) {
                val low = octet.toInt() and 0x0f
                val high = (octet.toInt() shr 4) and 0x0f
                if (low <= 9) digits.append('0' + low)
                if (high <= 9) digits.append('0' + high)
            }
            return (if (address[0].toInt() and 0x70 == 0x10) "+" else "") + digits
        }

        /** RP causes (TS 24.011 8.2.5.4) and the TP failure cause (TS 23.040 9.2.3.22) used for the card. */
        const val RP_CAUSE_TRANSFER_REJECTED = 21
        const val RP_CAUSE_TEMPORARY_FAILURE = 41
        const val TP_FCS_SIM_DATA_DOWNLOAD_ERROR = 0xd5

        fun encodeRpAddress(value: String): ByteArray {
            val normalized = normalizeE164(value)
            val digits = normalized.removePrefix("+")
            if (digits.length < 3 || digits.length > 20 || !digits.all { it in '0'..'9' }) throw SmsException("the SMS service centre address is invalid")
            val toa = if (normalized.startsWith("+")) 0x91 else 0x81
            val encoded = ByteArray((digits.length + 1) / 2)
            var index = 0
            while (index < digits.length) {
                val low = digits[index] - '0'
                val high = if (index + 1 < digits.length) digits[index + 1] - '0' else 0x0f
                encoded[index / 2] = ((high shl 4) or low).toByte()
                index += 2
            }
            return byteArrayOf(toa.toByte()) + encoded
        }

        fun normalizeE164(value: String): String {
            val result = StringBuilder()
            for ((index, character) in value.trim().withIndex()) {
                if (character in '0'..'9' || (index == 0 && character == '+')) result.append(character)
            }
            return result.toString()
        }
    }
}

private operator fun ByteArray.plus(other: ByteArray): ByteArray = com.vowi.vowidroid.core.util.concat(this, other)
