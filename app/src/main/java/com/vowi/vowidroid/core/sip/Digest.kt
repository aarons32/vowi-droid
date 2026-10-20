package com.vowi.vowidroid.core.sip

import com.vowi.vowidroid.core.sim.AkaChallenge
import com.vowi.vowidroid.core.util.Rng
import com.vowi.vowidroid.core.util.toHex
import java.security.MessageDigest
import java.util.Base64

class DigestChallenge(
    val realm: String,
    val nonce: String,
    val opaque: String,
    val algorithm: String,
    val qop: String,
    val stale: Boolean,
    val proxy: Boolean,
)

/** HTTP Digest AKAv1-MD5 (RFC 3310) as IMS registrars use it (3GPP TS 33.203). */
object Digest {
    fun parseChallenge(value: String, proxy: Boolean): DigestChallenge {
        val trimmed = value.trim()
        val space = trimmed.indexOf(' ')
        if (space < 0 || !trimmed.substring(0, space).equals("Digest", ignoreCase = true)) {
            throw SipParseException("unsupported SIP authentication scheme")
        }
        val directives = parseDirectives(trimmed.substring(space + 1))
        val realm = directives["realm"].orEmpty()
        val nonce = directives["nonce"].orEmpty()
        if (realm.isEmpty() || nonce.isEmpty()) throw SipParseException("incomplete SIP digest challenge")
        val algorithm = directives["algorithm"].orEmpty()
        // An omitted algorithm means plain MD5 with a password, which this UE has none of.
        if (algorithm.isEmpty()) throw SipParseException("digest challenge omitted the AKA algorithm")
        if (!algorithm.equals("AKAv1-MD5", ignoreCase = true)) throw SipParseException("unsupported digest algorithm \"$algorithm\"")
        var qop = ""
        directives["qop"]?.takeIf { it.isNotEmpty() }?.let { offered ->
            if (offered.split(',').any { it.trim().equals("auth", ignoreCase = true) }) qop = "auth"
            if (qop.isEmpty()) throw SipParseException("digest challenge does not offer qop=auth")
        }
        return DigestChallenge(realm, nonce, directives["opaque"].orEmpty(), algorithm, qop, directives["stale"].equals("true", ignoreCase = true), proxy)
    }

    fun parseDirectives(value: String): Map<String, String> {
        val directives = HashMap<String, String>()
        var index = 0
        while (index < value.length) {
            while (index < value.length && (value[index] == ' ' || value[index] == '\t' || value[index] == ',')) index++
            if (index == value.length) break
            val keyStart = index
            while (index < value.length && value[index] != '=' && value[index] != ',') index++
            if (index == value.length || value[index] != '=') throw SipParseException("malformed digest directive")
            val key = value.substring(keyStart, index).trim().lowercase()
            index++
            while (index < value.length && (value[index] == ' ' || value[index] == '\t')) index++
            val directive = StringBuilder()
            if (index < value.length && value[index] == '"') {
                index++
                var closed = false
                while (index < value.length && !closed) {
                    when (value[index]) {
                        '\\' -> {
                            index++
                            if (index == value.length) throw SipParseException("malformed quoted digest directive")
                            directive.append(value[index])
                            index++
                        }
                        '"' -> {
                            index++
                            closed = true
                        }
                        else -> {
                            directive.append(value[index])
                            index++
                        }
                    }
                }
                if (!closed) throw SipParseException("unterminated quoted digest directive")
            } else {
                val start = index
                while (index < value.length && value[index] != ',') index++
                directive.append(value.substring(start, index).trim())
            }
            if (key.isEmpty()) throw SipParseException("empty digest directive name")
            directives[key] = directive.toString()
            while (index < value.length && value[index] != ',') {
                if (value[index] != ' ' && value[index] != '\t') throw SipParseException("malformed digest directive separator")
                index++
            }
        }
        return directives
    }

    /** The AKA challenge inside a digest nonce: RAND || AUTN in the first 32 octets. */
    fun challengeFromNonce(nonce: String): AkaChallenge {
        val trimmed = nonce.trim()
        var decoded: ByteArray? = null
        for (decoder in listOf(Base64.getDecoder(), Base64.getUrlDecoder(), Base64.getMimeDecoder())) {
            decoded = runCatching { decoder.decode(trimmed) }.getOrNull()
            if (decoded != null) break
        }
        if (decoded == null || decoded.size < 32) throw SipParseException("invalid AKA nonce")
        return AkaChallenge(decoded.copyOfRange(0, 16), decoded.copyOfRange(16, 32))
    }

    class Credentials(
        val username: String,
        /** The short-lived USIM RES value, used in place of a password. */
        val akaResponse: ByteArray,
        val auts: String,
        val uri: String,
        val method: String,
        val cnonce: String = Rng.bytes(16).toHex(),
        val nc: Long = 1,
    )

    fun authorization(challenge: DigestChallenge, credentials: Credentials): String {
        val nc = "%08x".format(credentials.nc)
        val response = response(credentials.username, challenge.realm, credentials.akaResponse, credentials.method, credentials.uri, challenge.nonce, nc, credentials.cnonce, challenge.qop)
        val parts = mutableListOf(
            "username=\"${quote(credentials.username)}\"",
            "realm=\"${quote(challenge.realm)}\"",
            "nonce=\"${quote(challenge.nonce)}\"",
            "uri=\"${quote(credentials.uri)}\"",
            "response=\"$response\"",
            "algorithm=AKAv1-MD5",
        )
        if (challenge.opaque.isNotEmpty()) parts.add("opaque=\"${quote(challenge.opaque)}\"")
        if (challenge.qop.isNotEmpty()) {
            parts.add("qop=${challenge.qop}")
            parts.add("nc=$nc")
            parts.add("cnonce=\"${quote(credentials.cnonce)}\"")
        }
        if (credentials.auts.isNotEmpty()) parts.add("auts=\"${quote(credentials.auts)}\"")
        return "Digest " + parts.joinToString(", ")
    }

    /** The identity-only Authorization sent before any challenge (3GPP TS 24.229 5.1.1.2). */
    fun emptyAuthorization(privateIdentity: String, domain: String): String = "Digest " + listOf(
        "username=\"${quote(privateIdentity)}\"",
        "realm=\"${quote(domain)}\"",
        "nonce=\"\"",
        "uri=\"${quote("sip:$domain")}\"",
        "response=\"\"",
        "algorithm=AKAv1-MD5",
        "integrity-protected=no",
    ).joinToString(", ")

    fun response(username: String, realm: String, akaResponse: ByteArray, method: String, uri: String, nonce: String, nc: String, cnonce: String, qop: String): String {
        val ha1Digest = MessageDigest.getInstance("MD5")
        ha1Digest.update("$username:$realm:".toByteArray(Charsets.ISO_8859_1))
        ha1Digest.update(akaResponse)
        val ha1 = ha1Digest.digest().toHex()
        val ha2 = md5Hex("$method:$uri")
        return if (qop.isEmpty()) md5Hex("$ha1:$nonce:$ha2") else md5Hex("$ha1:$nonce:$nc:$cnonce:$qop:$ha2")
    }

    fun md5Hex(value: String): String = MessageDigest.getInstance("MD5").digest(value.toByteArray(Charsets.ISO_8859_1)).toHex()

    fun quote(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")
}
