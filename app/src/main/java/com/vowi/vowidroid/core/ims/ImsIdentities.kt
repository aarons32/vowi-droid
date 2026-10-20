package com.vowi.vowidroid.core.ims

import com.vowi.vowidroid.core.carrier.CarrierProfile
import com.vowi.vowidroid.core.carrier.CarrierProfiles
import com.vowi.vowidroid.core.sim.SimIdentity
import com.vowi.vowidroid.core.sip.SipMessage
import com.vowi.vowidroid.core.util.CoreLog
import com.vowi.vowidroid.core.util.hexToBytes
import java.net.URLDecoder
import java.security.MessageDigest

/** The IMS private and public identities derived from the SIM (3GPP TS 23.003). */
class ImsIdentities(
    val domain: String,
    val privateIdentity: String,
    val publicIdentity: String,
    val user: String,
    /** True when the public identity was generated for REGISTER only. */
    val temporaryPublic: Boolean,
) {
    companion object {
        fun derive(identity: SimIdentity, profile: CarrierProfile): ImsIdentities {
            val imsi = profile.effectiveSubscriberImsi(identity.imsi)
            require(SimIdentity.isDigits(imsi, 5, 16)) { "SIM IMSI is unavailable or invalid" }
            var mcc = profile.routeMcc.trim()
            var mnc = profile.routeMnc.trim()
            if (mcc.isEmpty() || mnc.isEmpty()) {
                mcc = identity.homeMcc.trim()
                mnc = identity.homeMnc.trim()
            }
            require(SimIdentity.isDigits(mcc, 3, 3) && SimIdentity.isDigits(mnc, 2, 3)) { "home PLMN is unavailable or invalid" }
            mnc = mnc.padStart(3, '0')
            var domain = "ims.mnc$mnc.mcc$mcc.3gppnetwork.org"
            var privateDomain = domain
            var publicDomain = domain
            if (profile.imsIdentityProfile == CarrierProfiles.IMS_ATT) {
                // AT&T provisions the IMPI and IMPU in its ISIM domains.
                domain = "one.att.net"
                privateDomain = "private.att.net"
                publicDomain = "one.att.net"
            }
            return ImsIdentities(domain, "$imsi@$privateDomain", "sip:$imsi@$publicDomain", imsi, temporaryPublic = true)
        }
    }
}

/** Helpers for identities, P-CSCF addresses and headers shared by registration, SMS and calls. */
object ImsSupport {
    private const val TAG = "IMS"
    const val CONTACT_ICSI_REF = "urn%3Aurn-7%3A3gpp-service.ims.icsi.mmtel,urn%3Aurn-7%3A3gpp-service.ims.icsi.sms"
    const val DEFAULT_PANI_NODE = "ffffffffffff"
    const val DEFAULT_USER_AGENT = "vowidroid/1"

    /** A UUIDv5 under the URL namespace, stable per device so registrations survive restarts. */
    fun stableInstanceUuid(identity: SimIdentity, deviceId: String): String {
        val imei = identity.imei.trim()
        val name = when {
            SimIdentity.isDigits(imei, 15, 15) -> "urn:vowi:sip-instance:imei:$imei"
            deviceId.isNotBlank() -> "urn:vowi:sip-instance:device-id:${deviceId.trim()}"
            SimIdentity.isDigits(identity.imsi, 5, 16) -> "urn:vowi:sip-instance:imsi:${identity.imsi}"
            else -> throw IllegalArgumentException("a stable SIP instance requires an IMEI, device ID or IMSI")
        }
        val namespace = "6ba7b8119dad11d180b400c04fd430c8".hexToBytes()
        val digest = MessageDigest.getInstance("SHA-1")
        digest.update(namespace)
        digest.update(name.toByteArray(Charsets.UTF_8))
        val uuid = digest.digest().copyOf(16)
        uuid[6] = ((uuid[6].toInt() and 0x0f) or 0x50).toByte()
        uuid[8] = ((uuid[8].toInt() and 0x3f) or 0x80).toByte()
        val hex = uuid.joinToString("") { "%02x".format(it) }
        return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}"
    }

    /** The GSMA IMEI instance URI when the modem identity is known, else the UUID. */
    fun sipInstanceId(identity: SimIdentity, fallbackUuid: String): String {
        val imei = identity.imei.trim()
        return if (SimIdentity.isDigits(imei, 15, 15)) "urn:gsma:imei:$imei-0" else "urn:uuid:${fallbackUuid.trim()}"
    }

    /** P-Access-Network-Info for a Wi-Fi UE; it never claims to be network-provided. */
    fun accessNetworkInfo(identity: SimIdentity, profile: CarrierProfile): String {
        if (profile.paniEnabled == false) return ""
        profile.registerOptions.pAccessNetworkInfo?.let { configured ->
            return appendCountry(ueProvided(configured), identity, profile)
        }
        var node = profile.paniNode.trim().lowercase()
        val decoded = runCatching { node.hexToBytes() }.getOrNull()
        if (decoded == null || decoded.size != 6) node = DEFAULT_PANI_NODE
        return appendCountry("IEEE-802.11;i-wlan-node-id=$node", identity, profile)
    }

    private fun appendCountry(value: String, identity: SimIdentity, profile: CarrierProfile): String {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return trimmed
        val parts = trimmed.split(";").toMutableList()
        if (parts.any { it.trim().lowercase().startsWith("country=") }) return trimmed
        var country = profile.paniCountry.trim().uppercase()
        if (country == "AUTO") {
            val mcc = identity.homeMcc.trim().ifEmpty { profile.routeMcc.trim() }
            country = CarrierProfiles.countryCodeForMcc(mcc)
            if (country.isEmpty()) {
                CoreLog.e(TAG, "the PANI country code could not be derived for profile ${profile.id}")
                return trimmed
            }
        }
        if (country.isEmpty()) return trimmed
        parts.add(1, "country=$country")
        return parts.joinToString(";")
    }

    /** RFC 7315 reserves the network-provided marker for a trusted proxy. */
    private fun ueProvided(value: String): String =
        value.trim().split(";").map { it.trim() }.filter { it.isNotEmpty() && !it.equals("network-provided", ignoreCase = true) }.joinToString(";")

    fun userAgent(profile: CarrierProfile, configured: String): String {
        val value = configured.trim()
        if (value.isNotEmpty() && value != DEFAULT_USER_AGENT) return value
        if (profile.imsUserAgent.isNotBlank()) return profile.imsUserAgent.trim()
        if (profile.imsRegisterProfile == CarrierProfiles.IMS_ATT) return "SimAdmin VoWiFi"
        return value.ifEmpty { DEFAULT_USER_AGENT }
    }

    class PcscfEndpoint(val host: String, val port: Int, val transportHint: String)

    /** Parses a P-CSCF value from the ePDG (an address, optionally with a port and transport). */
    fun parsePcscf(raw: String, defaultPort: Int): PcscfEndpoint {
        var value = raw.trim().trim('<', '>')
        val lower = value.lowercase()
        if (lower.startsWith("sip:")) value = value.substring(4) else if (lower.startsWith("sips:")) value = value.substring(5)
        var transport = ""
        val separator = value.indexOfFirst { it == ';' || it == '?' }
        if (separator >= 0) {
            val parameters = value.substring(separator + 1)
            value = value.substring(0, separator)
            for (parameter in parameters.split(';', '&')) {
                val equals = parameter.indexOf('=')
                if (equals > 0 && parameter.substring(0, equals).trim().equals("transport", ignoreCase = true)) {
                    transport = parameter.substring(equals + 1).trim().lowercase()
                }
            }
        }
        val at = value.lastIndexOf('@')
        if (at >= 0) value = value.substring(at + 1)
        value = value.trim()
        require(value.isNotEmpty() && value.none { it == '\r' || it == '\n' || it == '/' }) { "invalid P-CSCF address" }
        var host = value
        var port = defaultPort
        if (value.startsWith("[")) {
            val close = value.indexOf(']')
            require(close > 0) { "invalid P-CSCF host" }
            host = value.substring(1, close)
            val rest = value.substring(close + 1)
            if (rest.startsWith(":")) port = rest.substring(1).toIntOrNull()?.takeIf { it in 1..65535 } ?: throw IllegalArgumentException("invalid P-CSCF port")
        } else if (value.count { it == ':' } == 1) {
            val colon = value.indexOf(':')
            host = value.substring(0, colon)
            port = value.substring(colon + 1).toIntOrNull()?.takeIf { it in 1..65535 } ?: throw IllegalArgumentException("invalid P-CSCF port")
        }
        host = host.trim().trim('[', ']')
        require(host.isNotEmpty() && host.none { it == ' ' || it == '\t' || it == '<' || it == '>' || it == '"' }) { "invalid P-CSCF host" }
        require(transport.isEmpty() || transport == "udp" || transport == "tcp") { "unsupported P-CSCF transport \"$transport\"" }
        return PcscfEndpoint(host, port, transport)
    }

    // --- Public identity selection for MESSAGE (3GPP TS 24.229) ------------

    /** The network default identity for originating SMS; the REGISTER-only identity is not allowed. */
    fun originatingSmsPublicIdentity(temporary: String, associated: List<String>): Pair<String, String> {
        val values = SipMessage.splitHeaderValues(associated)
        if (values.isNotEmpty()) {
            val uri = publicIdentityUri(values[0])
            val key = publicIdentityKey(uri)
            if (key.isNotEmpty() && temporaryPublicIdentityKey(uri) != temporaryPublicIdentityKey(temporary)) return uri to "associated_default"
        }
        throw IllegalStateException("originating SMS requires a network default public identity distinct from the temporary registration identity")
    }

    fun temporaryPublicIdentityKey(value: String): String {
        val uri = publicIdentityUri(value)
        val normalized = StringBuilder()
        var index = 0
        val unreserved = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_.!~*'()"
        while (index < uri.length) {
            if (uri[index] == '%' && index + 2 < uri.length) {
                val decoded = runCatching { URLDecoder.decode(uri.substring(index, index + 3), "UTF-8") }.getOrNull()
                if (decoded != null && decoded.length == 1 && unreserved.contains(decoded[0])) {
                    normalized.append(decoded[0])
                    index += 3
                    continue
                }
            }
            normalized.append(uri[index])
            index++
        }
        return publicIdentityKey(normalized.toString().substringBefore(';'))
    }

    /** Picks an identity from the registration's associated URIs for a request. */
    fun messagePublicIdentity(current: String, preferred: String, associated: List<String>): Pair<String, String> {
        val preferredKey = publicIdentityKey(publicIdentityUri(preferred))
        val currentKey = publicIdentityKey(publicIdentityUri(current))
        var defaultUri = ""
        var currentUri = ""
        for (value in SipMessage.splitHeaderValues(associated)) {
            val uri = publicIdentityUri(value)
            val key = publicIdentityKey(uri)
            if (key.isEmpty()) continue
            if (defaultUri.isEmpty()) defaultUri = uri
            if (preferredKey.isNotEmpty() && key == preferredKey) return uri to "called_party"
            if (key == currentKey) currentUri = uri
        }
        if (currentUri.isNotEmpty()) return currentUri to "associated_current"
        if (defaultUri.isNotEmpty()) return defaultUri to "associated_default"
        return current to "configured_fallback"
    }

    fun publicIdentityUri(value: String): String {
        if (value.contains('\r') || value.contains('\n')) return ""
        val values = SipMessage.splitHeaderValues(listOf(value))
        if (values.size != 1) return ""
        var uri = values[0]
        val start = uri.indexOf('<')
        if (start >= 0) {
            val end = uri.indexOf('>', start + 1)
            if (end < 0) return ""
            uri = uri.substring(start + 1, end)
        }
        uri = uri.trim()
        return if (uri.any { it == '<' || it == '>' || it == '"' || it == ' ' || it == '\t' }) "" else uri
    }

    /** A conservative comparison key: scheme and host fold case, the user part does not. */
    fun publicIdentityKey(uri: String): String {
        val colon = uri.indexOf(':')
        if (colon < 0 || colon == uri.length - 1) return ""
        val scheme = uri.substring(0, colon).lowercase()
        val rest = uri.substring(colon + 1)
        return when (scheme) {
            "tel" -> "$scheme:$rest"
            "sip", "sips" -> {
                val at = rest.lastIndexOf('@')
                if (at <= 0 || at == rest.length - 1) return ""
                val userinfo = rest.substring(0, at + 1)
                var host = rest.substring(at + 1)
                var tail = ""
                val end = host.indexOfFirst { it == ';' || it == '?' }
                if (end >= 0) {
                    tail = host.substring(end)
                    host = host.substring(0, end)
                }
                if (host.isEmpty()) "" else "$scheme:$userinfo${host.lowercase()}$tail"
            }
            else -> ""
        }
    }

    // --- The subscriber's number from P-Associated-URI -----------------------

    /** Only identities the registrar associated count; a bare SIP user may be an IMSI. */
    fun extractAssociatedMsisdn(pAssociatedUri: List<String>): Pair<String, String>? {
        for (uri in pAssociatedUri) {
            normalizeAssociatedIdentity(uri, explicit = false)?.let { return it to "ims_p_associated_uri" }
        }
        return null
    }

    fun normalizeAssociatedIdentity(raw: String, explicit: Boolean): String? {
        var value = raw.trim().trim('<', '>', '"', '\'')
        if (value.isEmpty()) return null
        val lower = value.lowercase()
        var typed = false
        when {
            lower.startsWith("tel:") -> {
                typed = true
                value = value.substring(4)
            }
            lower.startsWith("sip:") || lower.startsWith("sips:") -> {
                typed = true
                value = value.substring(value.indexOf(':') + 1)
                val at = value.indexOf('@')
                if (at >= 0) value = value.substring(0, at)
                if (!value.trim().startsWith("+")) return null
            }
            else -> {
                val at = value.indexOf('@')
                if (at >= 0) value = value.substring(0, at) else if (!explicit) return null
            }
        }
        val semicolon = value.indexOf(';')
        if (semicolon >= 0) {
            if (!typed) return null
            value = value.substring(0, semicolon)
        }
        val question = value.indexOf('?')
        if (question >= 0) {
            if (!typed) return null
            value = value.substring(0, question)
        }
        value = runCatching { URLDecoder.decode(value.replace("+", "%2B"), "UTF-8") }.getOrDefault(value)
        val normalized = StringBuilder()
        for (character in value.trim()) {
            when {
                character == '+' && normalized.isEmpty() -> normalized.append(character)
                character in '0'..'9' -> normalized.append(character)
                character.isWhitespace() || character == '-' || character == '(' || character == ')' -> Unit
                else -> return null
            }
        }
        val number = normalized.toString()
        if (!number.startsWith("+")) return null
        return if (SimIdentity.isDigits(number.substring(1), 5, 15)) number else null
    }
}
