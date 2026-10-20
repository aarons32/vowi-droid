package com.vowi.vowidroid.core.ims

import com.vowi.vowidroid.core.net.IpPacket
import com.vowi.vowidroid.core.sip.SipMessage
import com.vowi.vowidroid.core.util.CoreLog
import com.vowi.vowidroid.core.util.Rng
import com.vowi.vowidroid.core.util.toHex
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** The network's answer to a USSD code; [final] when it ended the session. */
class UssdReply(val text: String, val final: Boolean)

class UssdException(message: String) : ImsException(message)

/**
 * USSD over IMS (3GPP TS 24.390). An INVITE to the
 * dial string carries the code as XML; the network answers in an INFO when it
 * wants more from the user, or in a BYE when it is done. This is how MMI
 * codes such as call forwarding (`**21*number#`) reach an IMS network.
 */
class UssdRuntime(private val session: ImsSession) {
    private class Dialog(val callId: String, var to: String, var target: String, var routes: List<String>) {
        val turns = LinkedBlockingQueue<Turn>()
    }

    private class Turn(val method: String, val text: String, val errorCode: Int?)

    @Volatile
    private var dialog: Dialog? = null

    /** Sends [code] and waits for the network's first answer. */
    fun send(code: String): UssdReply {
        if (dialog != null) cancel()
        val dial = code.trim()
        if (dial.isEmpty()) throw UssdException("the code is empty")
        val domain = session.identities.domain
        // TS 24.390 Table A.1-1: the dial string as the user part, user=dialstring.
        val uri = "sip:${dial.replace("#", "%23")};phone-context=$domain@$domain;user=dialstring"
        val callId = Rng.bytes(18).toHex() + "@" + session.localHost
        val cseq = session.nextCseq()
        val branch = Rng.bytes(12).toHex()
        val boundary = Rng.bytes(16).toHex()
        val preferred = session.originatingIdentities().second
        val request = session.newRequest("INVITE", uri, "<$uri>", callId, cseq, branch)
            .header("Contact", session.mmtelContact())
            .header("P-Preferred-Identity", "<$preferred>")
            .header("P-Preferred-Service", MMTEL_SERVICE_URN)
            .header("Accept-Contact", "*;+g.3gpp.icsi-ref=\"$MMTEL_FEATURE_TAG\"")
            .header("Allow", "INVITE, ACK, CANCEL, BYE, PRACK, UPDATE, INFO")
            .header("Supported", "100rel, timer")
            .header("Accept", "application/sdp, $XML_TYPE, multipart/mixed")
            .header("Recv-Info", INFO_PACKAGE)
            .body("multipart/mixed; boundary=$boundary", multipart(boundary, sdp(), xml(dial)))
            .build()
        val current = Dialog(callId, "<$uri>", uri, session.evidence.serviceRoute)
        dialog = current
        val response = try {
            session.transaction(request, "$callId:$cseq:INVITE", 20_000)
        } catch (error: Exception) {
            dialog = null
            throw UssdException("the network did not answer: ${error.message}")
        }
        if (response.statusCode !in 200..299) {
            runCatching { failureAck(current, response, branch, cseq, uri) }
            dialog = null
            throw UssdException("the network refused the code (SIP ${response.statusCode})")
        }
        current.to = response.value("To")
        SipMessage.firstUri(response.value("Contact")).takeIf { it.isNotEmpty() }?.let { current.target = it }
        routeSetOf(response)?.let { current.routes = it }
        runCatching { session.send(session.newRequest("ACK", current.target, current.to, callId, cseq, routes = current.routes).build()) }
        return await(current)
    }

    /** Answers the network's question, e.g. a menu choice. */
    fun reply(input: String): UssdReply {
        val current = dialog ?: throw UssdException("the USSD session has ended")
        val cseq = session.nextCseq()
        val request = session.newRequest("INFO", current.target, current.to, current.callId, cseq, routes = current.routes)
            .header("Info-Package", INFO_PACKAGE)
            .header("Content-Disposition", "info-package")
            .body(XML_TYPE, xml(input.trim()))
            .build()
        val response = session.transaction(request, "${current.callId}:$cseq:INFO")
        if (response.statusCode !in 200..299) throw UssdException("the network refused the reply (SIP ${response.statusCode})")
        return await(current)
    }

    /** Ends the session, if one is open; a send or reply still waiting returns at once. */
    fun cancel() {
        val current = dialog ?: return
        dialog = null
        current.turns.offer(Turn(CANCELLED, "", null))
        val cseq = session.nextCseq()
        runCatching {
            session.transaction(session.newRequest("BYE", current.target, current.to, current.callId, cseq, routes = current.routes).build(), "${current.callId}:$cseq:BYE", 5_000)
        }
    }

    private fun await(current: Dialog): UssdReply {
        val turn = current.turns.poll(TURN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (turn == null) {
            cancel()
            throw UssdException("the network did not reply")
        }
        if (turn.method == CANCELLED) throw UssdException("the session was cancelled")
        if (turn.method == "CANCEL") throw UssdException("the network ended the session")
        if (turn.errorCode != null && turn.errorCode != 0) {
            if (dialog === current && turn.method != "BYE") cancel()
            throw UssdException("the network returned USSD error ${turn.errorCode}")
        }
        return UssdReply(turn.text, final = turn.method == "BYE")
    }

    /** INFO, BYE and CANCEL for the session in progress. False for anything else. */
    fun handle(request: SipMessage, respond: (ByteArray) -> Unit): Boolean {
        val current = dialog?.takeIf { it.callId == request.callId }
        when (request.method) {
            "INFO" -> {
                val isUssd = current != null || request.value("Info-Package").trim() == INFO_PACKAGE ||
                    request.value("Content-Type").contains("ussd", ignoreCase = true)
                if (!isUssd) return false
                // TS 24.390 §5.1.2.1: answer the INFO with a 200 without a body.
                respond(session.buildResponse(request, 200))
                val (text, error) = parse(request.body, request.value("Content-Type"))
                current?.turns?.offer(Turn("INFO", text, error))
                return true
            }
            "BYE", "CANCEL" -> {
                if (current == null) return false
                respond(session.buildResponse(request, 200))
                val (text, error) = parse(request.body, request.value("Content-Type"))
                dialog = null
                current.turns.offer(Turn(request.method, text, error))
                return true
            }
            else -> return false
        }
    }

    private fun failureAck(current: Dialog, response: SipMessage, branch: String, cseq: Long, uri: String) {
        val to = response.value("To").ifEmpty { current.to }
        session.send(session.newRequest("ACK", uri, to, current.callId, cseq, branch, routes = session.evidence.serviceRoute).build())
    }

    /** An SDP with no media stream, as TS 24.390 sessions carry. */
    private fun sdp(): ByteArray {
        val address = session.localIp
        val family = if (address.size == 4) "IP4" else "IP6"
        val host = IpPacket.addressString(address)
        val now = System.currentTimeMillis() / 1000
        return listOf(
            "v=0", "o=- $now $now IN $family $host", "s=-", "c=IN $family $host", "t=0 0",
            "m=audio 0 RTP/AVP 97 96", "a=rtpmap:97 AMR", "a=fmtp:97 mode-set=0,2,5,7; maxframes=2", "a=rtpmap:96 telephone-event", "",
        ).joinToString("\r\n").toByteArray(Charsets.ISO_8859_1)
    }

    companion object {
        private const val TAG = "USSD"
        const val XML_TYPE = "application/vnd.3gpp.ussd+xml"
        const val INFO_PACKAGE = "g.3gpp.ussd"
        private const val TURN_TIMEOUT_SECONDS = 15L

        /** Our own cancel, as a turn, so a waiting send or reply returns. */
        private const val CANCELLED = "cancelled"

        /** The TS 24.390 §5.1.3 document. */
        fun xml(text: String): ByteArray = (
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<ussd-data>\n    <language>en</language>\n" +
                "    <ussd-string>${escape(text)}</ussd-string>\n</ussd-data>\n"
            ).toByteArray(Charsets.UTF_8)

        fun multipart(boundary: String, sdp: ByteArray, xml: ByteArray): ByteArray {
            val head = "--$boundary\r\nContent-Type: application/sdp\r\n\r\n"
            val middle = "\r\n--$boundary\r\nContent-Type: $XML_TYPE\r\nContent-Disposition: render; handling=optional\r\n\r\n"
            val tail = "\r\n--$boundary--\r\n"
            return head.toByteArray() + sdp + middle.toByteArray() + xml + tail.toByteArray()
        }

        /** The text and error code in a USSD body: XML, possibly inside multipart/mixed, or plain text. */
        fun parse(body: ByteArray, contentType: String): Pair<String, Int?> {
            if (body.isEmpty()) return "" to null
            val text = String(body, Charsets.UTF_8)
            val boundary = Regex("boundary=\"?([^\";]+)\"?", RegexOption.IGNORE_CASE).find(contentType)?.groupValues?.get(1)
            if (contentType.lowercase().startsWith("multipart/") && boundary != null) {
                for (part in text.split("--$boundary")) {
                    val headers = part.substringBefore("\r\n\r\n", "")
                    if (headers.contains("ussd", ignoreCase = true)) return parseXml(part.substringAfter("\r\n\r\n").trimEnd('-', '\r', '\n'))
                }
            }
            if (text.contains("<ussd-data")) return parseXml(text)
            return text.trim() to null
        }

        private fun parseXml(xml: String): Pair<String, Int?> {
            val string = Regex("<ussd-string>(.*?)</ussd-string>", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1)
            val error = Regex("<error-code>\\s*(\\d+)\\s*</error-code>").find(xml)?.groupValues?.get(1)?.toIntOrNull()
            return unescape(string?.trim().orEmpty()) to error
        }

        private fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

        private fun unescape(text: String) = text.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")

        private operator fun ByteArray.plus(other: ByteArray): ByteArray = com.vowi.vowidroid.core.util.concat(this, other)
    }
}
