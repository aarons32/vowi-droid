package com.vowi.vowidroid.core.entitlement

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.vowi.vowidroid.core.ike.EapAka
import com.vowi.vowidroid.core.ike.EapPacket
import com.vowi.vowidroid.core.ike.IkeException
import com.vowi.vowidroid.core.sim.AkaChallenge
import com.vowi.vowidroid.core.sim.AkaMacFailureException
import com.vowi.vowidroid.core.sim.AkaResult
import com.vowi.vowidroid.core.util.concat
import com.vowi.vowidroid.core.util.constantTimeEquals
import com.vowi.vowidroid.core.util.u8
import org.xml.sax.InputSource
import java.io.IOException
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Base64
import javax.xml.parsers.DocumentBuilderFactory

/**
 * GSMA TS.43 service entitlement, as Android's own IMS entitlement client
 * speaks it. The phone signs in to the carrier's entitlement server with
 * EAP-AKA over HTTP (the SIM answers the challenge), asks about the Wi-Fi
 * calling application `ap2004`, and gets back whether the line is entitled,
 * whether the carrier has an emergency (E911) address and terms on file, and
 * the carrier's own web page where the user enters them.
 */
object Ts43 {
    const val APP_VOWIFI = "ap2004"

    const val ENTITLEMENT_DISABLED = 0
    const val ENTITLEMENT_ENABLED = 1
    const val ENTITLEMENT_INCOMPATIBLE = 2
    const val ENTITLEMENT_PROVISIONING = 3

    /** AddrStatus and TC_Status share these values. */
    const val STATUS_NOT_AVAILABLE = 0
    const val STATUS_AVAILABLE = 1
    const val STATUS_NOT_REQUIRED = 2
    const val STATUS_IN_PROGRESS = 3

    const val PROVISIONING_NOT_PROVISIONED = 0
    const val PROVISIONING_PROVISIONED = 1
    const val PROVISIONING_NOT_REQUIRED = 2
    const val PROVISIONING_IN_PROGRESS = 3

    const val ACCEPT_XML = "text/vnd.wap.connectivity-xml"
    const val CONTENT_TYPE_EAP_RELAY = "application/vnd.gsma.eap-relay.v1.0+json"

    /** The name the carrier's web page calls back on: `VoWiFiWebServiceFlow.entitlementChanged()` or `.dismissFlow()`. */
    const val WEB_SHEET_INTERFACE = "VoWiFiWebServiceFlow"

    /** The EAP identity for the server: `0<IMSI>@nai.epc.mnc<MNC>.mcc<MCC>.3gppnetwork.org`, with a 3-digit MNC. */
    fun eapIdentity(imsi: String, mcc: String, mnc: String, realm: String = "nai.epc"): String {
        val paddedMnc = mnc.trim().padStart(3, '0')
        return "0${imsi.trim()}@$realm.mnc$paddedMnc.mcc${mcc.trim()}.3gppnetwork.org"
    }

    /** Parses the server's `wap-provisioningdoc` answer. */
    fun parse(xml: String): VowifiEntitlement {
        // Carriers put raw ampersands in ServiceFlow_UserData; make the document well formed first.
        val repaired = xml.replace("&", "&amp;").replace("&amp;amp;", "&amp;")
        val factory = DocumentBuilderFactory.newInstance()
        runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { factory.isXIncludeAware = false }
        factory.isExpandEntityReferences = false
        val document = try {
            factory.newDocumentBuilder().parse(InputSource(StringReader(repaired)))
        } catch (error: Exception) {
            throw EntitlementException("the entitlement server's answer is not valid XML: ${error.message}", cause = error)
        }
        val nodes = HashMap<String, Map<String, String>>()
        val characteristics = document.getElementsByTagName("characteristic")
        for (index in 0 until characteristics.length) {
            val element = characteristics.item(index) as? org.w3c.dom.Element ?: continue
            if (element.getElementsByTagName("characteristic").length != 0) continue
            val parms = HashMap<String, String>()
            val list = element.getElementsByTagName("parm")
            for (parm in 0 until list.length) {
                val item = list.item(parm) as? org.w3c.dom.Element ?: continue
                parms[item.getAttribute("name")] = item.getAttribute("value")
            }
            val type = element.getAttribute("type")
            val key = if (type == "APPLICATION") parms["AppID"] ?: continue else type
            nodes[key] = parms
        }
        val vowifi = nodes[APP_VOWIFI].orEmpty()
        val token = nodes["TOKEN"].orEmpty()
        val version = nodes["VERS"].orEmpty()
        fun status(name: String, default: Int): Int = vowifi[name]?.trim()?.toIntOrNull() ?: default
        return VowifiEntitlement(
            // Without the application in the answer the carrier does not offer Wi-Fi calling to this device.
            entitlementStatus = status("EntitlementStatus", ENTITLEMENT_INCOMPATIBLE),
            addrStatus = status("AddrStatus", STATUS_NOT_REQUIRED),
            tcStatus = status("TC_Status", STATUS_NOT_REQUIRED),
            provStatus = status("ProvStatus", PROVISIONING_NOT_REQUIRED),
            serviceFlowUrl = vowifi["ServiceFlow_URL"]?.trim().orEmpty(),
            serviceFlowUserData = vowifi["ServiceFlow_UserData"].orEmpty(),
            messageForIncompatible = vowifi["MessageForIncompatible"]?.trim().orEmpty(),
            token = token["token"]?.trim().orEmpty(),
            tokenValiditySeconds = token["validity"]?.trim()?.toLongOrNull() ?: 0,
            version = version["version"]?.trim()?.toIntOrNull() ?: 0,
            versionValiditySeconds = version["validity"]?.trim()?.toLongOrNull() ?: 0,
            raw = xml,
        )
    }
}

/** The carrier's answer about Wi-Fi calling on this line. */
class VowifiEntitlement(
    val entitlementStatus: Int,
    val addrStatus: Int,
    val tcStatus: Int,
    val provStatus: Int,
    /** The carrier's page for the emergency address and terms, or empty. */
    val serviceFlowUrl: String,
    /** Form data to POST to that page, or empty for a plain GET. */
    val serviceFlowUserData: String,
    val messageForIncompatible: String,
    /** A token for later queries without a new EAP-AKA sign-in, or empty. */
    val token: String,
    val tokenValiditySeconds: Long,
    val version: Int,
    val versionValiditySeconds: Long,
    val raw: String,
) {
    /** Entitled, provisioned, and the address and terms are on file or not needed. */
    val entitled: Boolean
        get() = entitlementStatus == Ts43.ENTITLEMENT_ENABLED &&
            (provStatus == Ts43.PROVISIONING_PROVISIONED || provStatus == Ts43.PROVISIONING_NOT_REQUIRED) &&
            (tcStatus == Ts43.STATUS_AVAILABLE || tcStatus == Ts43.STATUS_NOT_REQUIRED) &&
            (addrStatus == Ts43.STATUS_AVAILABLE || addrStatus == Ts43.STATUS_NOT_REQUIRED)

    /** The carrier still needs the address or the terms accepted: time for its web page. */
    val serverDataMissing: Boolean
        get() = entitlementStatus == Ts43.ENTITLEMENT_DISABLED &&
            (tcStatus == Ts43.STATUS_NOT_AVAILABLE || addrStatus == Ts43.STATUS_NOT_AVAILABLE)

    val inProgress: Boolean
        get() = entitlementStatus == Ts43.ENTITLEMENT_PROVISIONING ||
            (entitlementStatus == Ts43.ENTITLEMENT_DISABLED && (tcStatus == Ts43.STATUS_IN_PROGRESS || addrStatus == Ts43.STATUS_IN_PROGRESS)) ||
            (entitlementStatus == Ts43.ENTITLEMENT_DISABLED &&
                (provStatus == Ts43.PROVISIONING_NOT_PROVISIONED || provStatus == Ts43.PROVISIONING_IN_PROGRESS) &&
                (tcStatus == Ts43.STATUS_AVAILABLE || tcStatus == Ts43.STATUS_NOT_REQUIRED) &&
                (addrStatus == Ts43.STATUS_AVAILABLE || addrStatus == Ts43.STATUS_NOT_REQUIRED))

    val incompatible: Boolean get() = entitlementStatus == Ts43.ENTITLEMENT_INCOMPATIBLE

    val addressOnFile: Boolean get() = addrStatus == Ts43.STATUS_AVAILABLE

    override fun toString(): String =
        "entitlement=$entitlementStatus address=$addrStatus terms=$tcStatus provisioning=$provStatus page=${if (serviceFlowUrl.isEmpty()) "none" else "yes"}"
}

class EntitlementException(
    message: String,
    val httpStatus: Int = 0,
    /** From a 503's Retry-After, in seconds, or -1. */
    val retryAfterSeconds: Long = -1,
    cause: Throwable? = null,
) : IOException(message, cause)

/** What one query carries about this device; the server may show some of it on its page. */
data class EntitlementRequest(
    val serverUrl: String,
    /** This device's ID at the server. TS.43 names the IMEI; VowiDroid sends its own stable ID instead, so the phone's IMEI stays out of entitlement traffic. */
    val terminalId: String,
    val terminalVendor: String,
    val terminalModel: String,
    val terminalSoftwareVersion: String,
    /** VowiDroid's version, for the User-Agent. */
    val appVersion: String = "",
    val entitlementVersion: String = "2.0",
    val configurationVersion: Int = 0,
    /** A token from an earlier answer, to skip the EAP-AKA sign-in. */
    val token: String? = null,
)

class HttpCall(val method: String, val url: String, val headers: List<Pair<String, String>>, val body: ByteArray? = null) {
    fun header(name: String): String? = headers.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second
}

class HttpReply(val status: Int, headers: Map<String, List<String>>, val body: ByteArray) {
    val headers: Map<String, List<String>> = headers.mapKeys { it.key.lowercase() }
    fun header(name: String): String? = this.headers[name.lowercase()]?.firstOrNull()
    val contentType: String get() = header("Content-Type").orEmpty()
    val setCookies: List<String> get() = headers["set-cookie"].orEmpty()
    val text: String get() = String(body, Charsets.UTF_8)
}

/** One HTTPS exchange; the app uses [UrlConnectionTransport], tests a scripted one. */
fun interface HttpTransport {
    fun exchange(call: HttpCall): HttpReply
}

/** HttpURLConnection without redirects, as the entitlement library does it. */
class UrlConnectionTransport(
    private val timeoutMs: Int = 30_000,
    private val open: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) : HttpTransport {
    override fun exchange(call: HttpCall): HttpReply {
        val connection = open(URL(call.url))
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.requestMethod = call.method
            for ((name, value) in call.headers) connection.addRequestProperty(name, value)
            val body = call.body
            if (body != null) {
                connection.doOutput = true
                connection.outputStream.use { it.write(body) }
            }
            val status = connection.responseCode
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
            return HttpReply(status, connection.headerFields.filterKeys { it != null }, bytes)
        } finally {
            connection.disconnect()
        }
    }
}

/**
 * The TS.43 client for one line. [eapIdentity] is the NAI the SIM is known
 * by, [imsi] goes with a token, and [aka] has the SIM answer a challenge.
 */
class Ts43Client(
    private val transport: HttpTransport,
    private val eapIdentity: String,
    private val imsi: String,
    private val aka: (AkaChallenge) -> AkaResult,
) {
    /** Asks the server about Wi-Fi calling, signing in with the SIM unless [EntitlementRequest.token] still works. */
    fun queryVowifi(request: EntitlementRequest): VowifiEntitlement {
        val token = request.token?.trim().orEmpty()
        if (token.isNotEmpty()) {
            val reply = transport.exchange(get(request, token, Ts43.ACCEPT_XML))
            // 511: the server wants a full sign-in again.
            if (reply.status == HTTP_TOKEN_EXPIRED) return queryVowifi(request.copy(token = null))
            return entitlement(checked(reply))
        }
        val first = checked(transport.exchange(get(request, null, Ts43.CONTENT_TYPE_EAP_RELAY)))
        var challenge = eapChallenge(first) ?: throw EntitlementException("the entitlement server sent no EAP-AKA challenge")
        val cookies = cookieHeader(first.setCookies)
        var rounds = 0
        while (true) {
            if (++rounds > MAX_ROUNDS) throw EntitlementException("the entitlement server kept challenging the SIM after $MAX_ROUNDS rounds")
            val answer = answer(challenge)
            val accept = if (answer.synchronizationFailure) Ts43.CONTENT_TYPE_EAP_RELAY else "${Ts43.CONTENT_TYPE_EAP_RELAY}, ${Ts43.ACCEPT_XML}"
            val reply = checked(transport.exchange(post(request, answer.packet, cookies, accept)))
            val next = eapChallenge(reply)
            if (next == null) {
                if (answer.synchronizationFailure) throw EntitlementException("the entitlement server sent no new challenge after the SIM's synchronization failure")
                return entitlement(reply)
            }
            challenge = next
        }
    }

    private class Answer(val packet: ByteArray, val synchronizationFailure: Boolean)

    /** Has the SIM answer one EAP-Request/AKA-Challenge; the response carries AT_RES and AT_MAC, or AT_AUTS. */
    private fun answer(encoded: ByteArray): Answer {
        val packet = try {
            EapAka.parsePacket(encoded)
        } catch (error: IkeException) {
            throw EntitlementException("the entitlement server's EAP-AKA challenge is malformed: ${error.message}", cause = error)
        }
        if (packet.code != EapAka.CODE_REQUEST || packet.type != EapAka.TYPE_AKA || packet.data.size < 3 || packet.data.u8(0) != EapAka.SUBTYPE_CHALLENGE) {
            throw EntitlementException("the entitlement server sent something other than an EAP-AKA challenge")
        }
        val attributes = try {
            EapAka.parseAttributes(packet.data.copyOfRange(3, packet.data.size))
        } catch (error: IkeException) {
            throw EntitlementException("the entitlement server's EAP-AKA challenge is malformed: ${error.message}", cause = error)
        }
        val rand = attributes.firstOrNull { it.type == EapAka.AT_RAND }?.raw?.takeIf { it.size == 20 }?.copyOfRange(4, 20)
        val autn = attributes.firstOrNull { it.type == EapAka.AT_AUTN }?.raw?.takeIf { it.size == 20 }?.copyOfRange(4, 20)
        if (rand == null || autn == null) throw EntitlementException("the entitlement server's EAP-AKA challenge has no RAND and AUTN")
        val result = try {
            aka(AkaChallenge(rand, autn))
        } catch (error: AkaMacFailureException) {
            throw EntitlementException("the SIM rejected the entitlement server's challenge (MAC failure)", cause = error)
        } catch (error: EntitlementException) {
            throw error
        } catch (error: Exception) {
            throw EntitlementException("the SIM could not answer the entitlement server: ${error.message}", cause = error)
        }
        if (result.synchronizationFailure) {
            val auts = result.auts ?: ByteArray(0)
            if (auts.size != 14) throw EntitlementException("the SIM reported a synchronization failure without a 14-byte AUTS")
            val data = concat(byteArrayOf(EapAka.SUBTYPE_SYNCHRONIZATION_FAILURE.toByte(), 0, 0), EapAka.marshalAttribute(EapAka.AT_AUTS, auts))
            return Answer(EapAka.marshalPacket(EapPacket(EapAka.CODE_RESPONSE, packet.identifier, EapAka.TYPE_AKA, data)), true)
        }
        if (result.res.size < 4 || result.res.size > 16) throw EntitlementException("the SIM returned a RES of ${result.res.size} bytes")
        val keys = EapAka.deriveKeys(eapIdentity.toByteArray(Charsets.US_ASCII), result.ik, result.ck)
        // The server's MAC proves it holds the same keys; a server that sends none is still answered, as Android does.
        val serverMac = attributes.firstOrNull { it.type == EapAka.AT_MAC }
        if (serverMac != null) {
            if (serverMac.raw.size != 20) throw EntitlementException("the entitlement server's EAP-AKA challenge has a malformed MAC")
            val zeroed = EapAka.marshalPacket(packet)
            val offset = 5 + 3 + serverMac.offset + 4
            for (index in offset until offset + 16) zeroed[index] = 0
            if (!constantTimeEquals(EapAka.mac(keys.kAut, zeroed), serverMac.raw.copyOfRange(4, 20))) {
                throw EntitlementException("the entitlement server's EAP-AKA challenge has an invalid MAC")
            }
        }
        val resValue = concat(byteArrayOf(((result.res.size * 8) ushr 8).toByte(), (result.res.size * 8).toByte()), result.res)
        val data = concat(
            byteArrayOf(EapAka.SUBTYPE_CHALLENGE.toByte(), 0, 0),
            EapAka.marshalAttribute(EapAka.AT_RES, resValue),
            EapAka.marshalAttribute(EapAka.AT_MAC, ByteArray(18)),
        )
        val response = EapAka.marshalPacket(EapPacket(EapAka.CODE_RESPONSE, packet.identifier, EapAka.TYPE_AKA, data))
        // AT_MAC is the last attribute, so the MAC fills the final 16 bytes.
        System.arraycopy(EapAka.mac(keys.kAut, response), 0, response, response.size - 16, 16)
        return Answer(response, false)
    }

    private fun get(request: EntitlementRequest, token: String?, accept: String): HttpCall {
        val parameters = ArrayList<Pair<String, String>>()
        if (token != null) {
            parameters += "IMSI" to imsi
            parameters += "token" to token
        } else {
            parameters += "EAP_ID" to eapIdentity
        }
        parameters += "terminal_id" to request.terminalId
        parameters += "app" to Ts43.APP_VOWIFI
        parameters += "terminal_vendor" to request.terminalVendor.take(MAX_VENDOR)
        parameters += "terminal_model" to request.terminalModel.take(MAX_MODEL)
        parameters += "terminal_sw_version" to request.terminalSoftwareVersion.take(MAX_SOFTWARE_VERSION)
        parameters += "vers" to request.configurationVersion.toString()
        parameters += "entitlement_version" to request.entitlementVersion
        val query = parameters.joinToString("&") { (name, value) -> encode(name) + "=" + encode(value) }
        val url = request.serverUrl + (if ('?' in request.serverUrl) "&" else "?") + query
        return HttpCall("GET", url, listOf("Accept" to accept, "User-Agent" to userAgent(request)))
    }

    /** The EAP response goes back as JSON to the bare server URL; the server's cookies tie it to the challenge. */
    private fun post(request: EntitlementRequest, packet: ByteArray, cookies: String, accept: String): HttpCall {
        val body = JsonObject().apply { addProperty(EAP_RELAY_PACKET, Base64.getEncoder().encodeToString(packet)) }
        val headers = ArrayList<Pair<String, String>>()
        headers += "Accept" to accept
        headers += "Content-Type" to Ts43.CONTENT_TYPE_EAP_RELAY
        if (cookies.isNotEmpty()) headers += "Cookie" to cookies
        headers += "User-Agent" to userAgent(request)
        return HttpCall("POST", request.serverUrl, headers, body.toString().toByteArray(Charsets.UTF_8))
    }

    private fun userAgent(request: EntitlementRequest): String =
        "PRD-TS43 term-${request.terminalVendor.take(MAX_VENDOR)}/${request.terminalModel.take(MAX_MODEL)} " +
            "client-IMS-Entitlement/${request.appVersion} OS-Android/${request.terminalSoftwareVersion.take(MAX_SOFTWARE_VERSION)}"

    private fun checked(reply: HttpReply): HttpReply {
        if (reply.status == HttpURLConnection.HTTP_OK) return reply
        val retryAfter = reply.header("Retry-After")?.let(::retryAfterSeconds) ?: -1
        val detail = if (reply.status == HTTP_RETRY_LATER && retryAfter >= 0) " (retry in $retryAfter s)" else ""
        throw EntitlementException("the entitlement server answered HTTP ${reply.status}$detail", reply.status, retryAfter)
    }

    /** The challenge in a JSON reply, or null when the reply is the entitlement document. */
    private fun eapChallenge(reply: HttpReply): ByteArray? {
        val type = reply.contentType.lowercase()
        return when {
            type.contains("json") -> {
                val value = try {
                    (JsonParser.parseString(reply.text) as? JsonObject)?.get(EAP_RELAY_PACKET)?.takeIf { it.isJsonPrimitive }?.asString
                } catch (error: RuntimeException) {
                    throw EntitlementException("the entitlement server's JSON is malformed", cause = error)
                } ?: return null
                try {
                    Base64.getMimeDecoder().decode(value.trim())
                } catch (error: IllegalArgumentException) {
                    throw EntitlementException("the entitlement server's EAP-AKA challenge is not base64", cause = error)
                }
            }
            type.contains("xml") -> null
            else -> throw EntitlementException("the entitlement server answered with ${reply.contentType.ifEmpty { "no content type" }}")
        }
    }

    private fun entitlement(reply: HttpReply): VowifiEntitlement {
        if (!reply.contentType.lowercase().contains("xml")) {
            throw EntitlementException("the entitlement server answered with ${reply.contentType.ifEmpty { "no content type" }} instead of the entitlement document")
        }
        return Ts43.parse(reply.text)
    }

    private fun cookieHeader(setCookies: List<String>): String =
        setCookies.mapNotNull { it.substringBefore(';').trim().takeIf { cookie -> cookie.contains('=') } }.joinToString("; ")

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun retryAfterSeconds(value: String): Long {
        value.trim().toLongOrNull()?.let { return it }
        return try {
            ChronoUnit.SECONDS.between(Instant.now(), DateTimeFormatter.RFC_1123_DATE_TIME.parse(value.trim(), Instant::from))
        } catch (_: RuntimeException) {
            -1
        }
    }

    private companion object {
        const val EAP_RELAY_PACKET = "eap-relay-packet"
        const val HTTP_TOKEN_EXPIRED = 511
        const val HTTP_RETRY_LATER = 503
        const val MAX_ROUNDS = 4
        const val MAX_VENDOR = 4
        const val MAX_MODEL = 10
        const val MAX_SOFTWARE_VERSION = 20
    }
}
