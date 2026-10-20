package com.vowi.vowidroid.core.ike

import com.vowi.vowidroid.core.util.concat
import com.vowi.vowidroid.core.util.constantTimeEquals
import com.vowi.vowidroid.core.util.u8
import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.security.KeyStore
import java.security.PublicKey
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

enum class ResponderAuthStatus(val wire: String) {
    UNKNOWN("unknown"), VERIFIED("verified"), MISSING("missing"), INVALID("invalid")
}

class ResponderAuthRequiredException : IkeException("ike: verified IKE responder AUTH is required")

/** IKE_AUTH signed-octets and AUTH payload handling (RFC 7296 2.15, EAP shared-key MIC). */
object IkeAuth {
    const val METHOD_RSA_SIGNATURE = 1
    const val METHOD_SHARED_KEY_MIC = 2
    const val METHOD_ECDSA_SHA256_P256 = 9
    const val METHOD_ECDSA_SHA384_P384 = 10
    const val METHOD_ECDSA_SHA512_P521 = 11
    const val METHOD_DIGITAL_SIGNATURE = 14

    private val KEY_PAD = "Key Pad for IKEv2".toByteArray(Charsets.US_ASCII)

    fun responderSignedOctets(initialResponse: ByteArray, initiatorNonce: ByteArray, suite: IkeSuite, skpr: ByteArray, idr: Payload): ByteArray =
        concat(initialResponse, initiatorNonce, IkeCrypto.prf(suite, skpr, idr.body))

    fun initiatorSignedOctets(initialRequest: ByteArray, responderNonce: ByteArray, suite: IkeSuite, skpi: ByteArray, idi: Payload): ByteArray =
        concat(initialRequest, responderNonce, IkeCrypto.prf(suite, skpi, idi.body))

    fun makeEapInitiatorAuth(msk: ByteArray, initialRequest: ByteArray, responderNonce: ByteArray, suite: IkeSuite, skpi: ByteArray, idi: Payload): Payload {
        val signed = initiatorSignedOctets(initialRequest, responderNonce, suite, skpi, idi)
        val paddedKey = IkeCrypto.prf(suite, msk, KEY_PAD)
        val authValue = IkeCrypto.prf(suite, paddedKey, signed)
        val body = ByteArray(4 + authValue.size)
        body[0] = METHOD_SHARED_KEY_MIC.toByte()
        System.arraycopy(authValue, 0, body, 4, authValue.size)
        return Payload(Ike.PAYLOAD_AUTH, body)
    }

    fun verifyEapResponderAuth(auth: Payload, msk: ByteArray, initialResponse: ByteArray, initiatorNonce: ByteArray, suite: IkeSuite, skpr: ByteArray, idr: Payload) {
        if (auth.body.size < 4 || auth.body.u8(0) != METHOD_SHARED_KEY_MIC) {
            throw IkeException("ike: final responder AUTH does not use the EAP shared-key MIC")
        }
        val signed = responderSignedOctets(initialResponse, initiatorNonce, suite, skpr, idr)
        val paddedKey = IkeCrypto.prf(suite, msk, KEY_PAD)
        val expected = IkeCrypto.prf(suite, paddedKey, signed)
        val actual = auth.body.copyOfRange(4, auth.body.size)
        if (!constantTimeEquals(actual, expected)) throw IkeException("ike: final responder AUTH is invalid")
    }

    class InitialAuth(val status: ResponderAuthStatus, val idr: Payload?)

    /**
     * Validates the responder's AUTH in the first IKE_AUTH response when it is
     * present (certificate based); EAP-only ePDGs defer it to the final MSK AUTH.
     */
    fun validateInitialResponderAuth(
        payloads: List<Payload>,
        initialResponse: ByteArray,
        initiatorNonce: ByteArray,
        suite: IkeSuite,
        skpr: ByteArray,
        expectedIdr: String,
        serverName: String,
        allowMissing: Boolean,
        /** The carrier's own CA (DER) when its ePDG certificate is not signed by a public one. */
        carrierCa: ByteArray? = null,
    ): InitialAuth {
        val idrs = IkeWire.payloadsOfType(payloads, Ike.PAYLOAD_IDR)
        val auths = IkeWire.payloadsOfType(payloads, Ike.PAYLOAD_AUTH)
        if (auths.isEmpty()) {
            if (idrs.size > 1) throw IkeException("ike: duplicate responder identity payload")
            if (allowMissing) return InitialAuth(ResponderAuthStatus.MISSING, idrs.firstOrNull())
            throw ResponderAuthRequiredException()
        }
        if (auths.size != 1 || idrs.size != 1) throw IkeException("ike: responder AUTH requires exactly one IDr and AUTH payload")
        val idr = idrs[0]
        val auth = auths[0]
        if (idr.body.size < 4 || auth.body.size < 5) throw IkeException("ike: responder IDr or AUTH payload is truncated")
        validateFqdnIdr(idr, expectedIdr, "initial ePDG")
        val certificates = parseResponderCertificates(payloads)
        if (certificates.isEmpty()) throw IkeException("ike: responder AUTH has no certificate")
        verifyResponderCertificate(certificates, serverName, carrierCa)
        val signed = responderSignedOctets(initialResponse, initiatorNonce, suite, skpr, idr)
        try {
            verifyDigitalAuth(certificates[0].publicKey, auth.body.u8(0), auth.body.copyOfRange(4, auth.body.size), signed)
        } catch (error: Exception) {
            throw IkeException("ike: invalid responder AUTH: ${error.message}", error)
        }
        return InitialAuth(ResponderAuthStatus.VERIFIED, idr)
    }

    fun validateFqdnIdr(idr: Payload, expectedIdr: String, label: String) {
        if (idr.body.size < 4) throw IkeException("ike: responder identity is truncated")
        val identityType = idr.body.u8(0)
        val identity = String(idr.body, 4, idr.body.size - 4, Charsets.ISO_8859_1).trim()
        if (identityType != Ike.ID_FQDN) throw IkeException("ike: $label IDr must use ID_FQDN, got type $identityType")
        if (identity.isEmpty()) throw IkeException("ike: $label IDr is empty")
        if (expectedIdr.isNotEmpty() && !identity.trimEnd('.').equals(expectedIdr.trimEnd('.'), ignoreCase = true)) {
            throw IkeException("ike: $label IDr \"$identity\" does not match \"$expectedIdr\"")
        }
    }

    fun parseResponderCertificates(payloads: List<Payload>): List<X509Certificate> {
        val factory = CertificateFactory.getInstance("X.509")
        return IkeWire.payloadsOfType(payloads, Ike.PAYLOAD_CERT).map { item ->
            if (item.body.size < 2) throw IkeException("ike: responder certificate payload is truncated")
            if (item.body.u8(0) != 4) throw IkeException("ike: unsupported responder certificate encoding ${item.body.u8(0)}")
            try {
                factory.generateCertificate(ByteArrayInputStream(item.body, 1, item.body.size - 1)) as X509Certificate
            } catch (error: Exception) {
                throw IkeException("ike: parse responder certificate: ${error.message}", error)
            }
        }
    }

    /** The chain must lead to the carrier's CA when the profile has one, else to a CA the system trusts. */
    private fun verifyResponderCertificate(certificates: List<X509Certificate>, serverName: String, carrierCa: ByteArray?) {
        val chain = certificates.toTypedArray()
        val authType = certificates[0].publicKey.algorithm
        var failure: Exception? = null
        var trusted = false
        if (carrierCa != null) {
            try {
                trustManager(carrierCa).checkServerTrusted(chain, authType)
                trusted = true
            } catch (error: Exception) {
                failure = error
            }
        }
        if (!trusted) {
            try {
                trustManager(null).checkServerTrusted(chain, authType)
                trusted = true
            } catch (error: Exception) {
                if (failure == null) failure = error
            }
        }
        if (!trusted) throw IkeException("ike: verify responder certificate: ${failure?.message}", failure)
        if (!hostnameMatches(certificates[0], serverName.trimEnd('.'))) {
            throw IkeException("ike: responder certificate does not match $serverName")
        }
    }

    /** A trust manager over the carrier's CA alone, or the system's when [carrierCa] is null. */
    private fun trustManager(carrierCa: ByteArray?): X509TrustManager {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        if (carrierCa == null) {
            factory.init(null as KeyStore?)
        } else {
            val anchor = try {
                CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(carrierCa)) as X509Certificate
            } catch (error: Exception) {
                throw IkeException("ike: the carrier's CA certificate is invalid: ${error.message}", error)
            }
            val store = KeyStore.getInstance(KeyStore.getDefaultType())
            store.load(null, null)
            store.setCertificateEntry("carrier-ca", anchor)
            factory.init(store)
        }
        return factory.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull() ?: throw IkeException("ike: no X.509 trust manager")
    }

    fun hostnameMatches(certificate: X509Certificate, host: String): Boolean {
        val wanted = host.lowercase()
        val names = ArrayList<String>()
        certificate.subjectAlternativeNames?.forEach { entry ->
            if (entry.size >= 2 && entry[0] == 2 && entry[1] is String) names.add((entry[1] as String).lowercase())
        }
        if (names.isEmpty()) {
            val cn = Regex("CN=([^,]+)").find(certificate.subjectX500Principal.name)?.groupValues?.get(1)
            if (cn != null) names.add(cn.trim().lowercase())
        }
        return names.any { pattern ->
            if (pattern.startsWith("*.")) {
                val suffix = pattern.substring(1)
                wanted.endsWith(suffix) && wanted.indexOf('.') == wanted.length - suffix.length
            } else {
                pattern == wanted
            }
        }
    }

    fun verifyDigitalAuth(publicKey: PublicKey, method: Int, signature: ByteArray, signed: ByteArray) {
        when (method) {
            METHOD_RSA_SIGNATURE -> verify("SHA1withRSA", publicKey, signature, signed)
            METHOD_ECDSA_SHA256_P256 -> verify("SHA256withECDSA", publicKey, rawEcdsaToDer(signature), signed)
            METHOD_ECDSA_SHA384_P384 -> verify("SHA384withECDSA", publicKey, rawEcdsaToDer(signature), signed)
            METHOD_ECDSA_SHA512_P521 -> verify("SHA512withECDSA", publicKey, rawEcdsaToDer(signature), signed)
            METHOD_DIGITAL_SIGNATURE -> verifyGenericSignature(publicKey, signature, signed)
            else -> throw IkeException("unsupported responder AUTH method $method")
        }
    }

    private fun verify(algorithm: String, publicKey: PublicKey, signature: ByteArray, signed: ByteArray) {
        val verifier = Signature.getInstance(algorithm)
        verifier.initVerify(publicKey)
        verifier.update(signed)
        if (!verifier.verify(signature)) throw IkeException("$algorithm signature verification failed")
    }

    /** RFC 7427: the signature value is prefixed with a DER AlgorithmIdentifier. */
    private fun verifyGenericSignature(publicKey: PublicKey, encoded: ByteArray, signed: ByteArray) {
        if (encoded.size < 2 || encoded.u8(0) != 0x30) throw IkeException("generic digital signature has an invalid AlgorithmIdentifier")
        val (length, lengthBytes) = derLength(encoded, 1)
        val algorithmEnd = 1 + lengthBytes + length
        if (algorithmEnd > encoded.size) throw IkeException("generic digital signature has an invalid AlgorithmIdentifier")
        val identifier = encoded.copyOfRange(0, algorithmEnd)
        val signature = encoded.copyOfRange(algorithmEnd, encoded.size)
        if (signature.isEmpty()) throw IkeException("generic digital signature is empty")
        val oidStart = 1 + lengthBytes
        if (oidStart + 2 > identifier.size || identifier.u8(oidStart) != 0x06) throw IkeException("generic digital signature has no OID")
        val oidLength = identifier.u8(oidStart + 1)
        val oid = identifier.copyOfRange(oidStart + 2, oidStart + 2 + oidLength)
        val algorithm = SIGNATURE_OIDS.entries.firstOrNull { it.key.contentEquals(oid) }?.value
            ?: throw IkeException("unsupported generic signature algorithm")
        verify(algorithm, publicKey, signature, signed)
    }

    private fun derLength(encoded: ByteArray, offset: Int): Pair<Int, Int> {
        val first = encoded.u8(offset)
        if (first < 0x80) return first to 1
        val count = first and 0x7f
        if (count == 0 || count > 2 || offset + 1 + count > encoded.size) throw IkeException("invalid DER length")
        var length = 0
        for (index in 0 until count) length = (length shl 8) or encoded.u8(offset + 1 + index)
        return length to 1 + count
    }

    private val SIGNATURE_OIDS: Map<ByteArray, String> = mapOf(
        byteArrayOf(0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x01, 0x0B) to "SHA256withRSA",
        byteArrayOf(0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x01, 0x0C) to "SHA384withRSA",
        byteArrayOf(0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x01, 0x0D) to "SHA512withRSA",
        byteArrayOf(0x2A, 0x86.toByte(), 0x48, 0xCE.toByte(), 0x3D, 0x04, 0x03, 0x02) to "SHA256withECDSA",
        byteArrayOf(0x2A, 0x86.toByte(), 0x48, 0xCE.toByte(), 0x3D, 0x04, 0x03, 0x03) to "SHA384withECDSA",
        byteArrayOf(0x2A, 0x86.toByte(), 0x48, 0xCE.toByte(), 0x3D, 0x04, 0x03, 0x04) to "SHA512withECDSA",
    )

    /** Converts an IKEv2 raw r||s ECDSA signature into the DER form Java verifies. */
    fun rawEcdsaToDer(signature: ByteArray): ByteArray {
        if (signature.isEmpty() || signature.size % 2 != 0) throw IkeException("ECDSA signature has an odd length")
        val half = signature.size / 2
        val r = derInteger(BigInteger(1, signature.copyOfRange(0, half)))
        val s = derInteger(BigInteger(1, signature.copyOfRange(half, signature.size)))
        val content = concat(r, s)
        return concat(byteArrayOf(0x30), derLengthBytes(content.size), content)
    }

    private fun derInteger(value: BigInteger): ByteArray {
        val magnitude = value.toByteArray() // includes a sign byte when the top bit is set
        return concat(byteArrayOf(0x02), derLengthBytes(magnitude.size), magnitude)
    }

    private fun derLengthBytes(length: Int): ByteArray = when {
        length < 0x80 -> byteArrayOf(length.toByte())
        length < 0x100 -> byteArrayOf(0x81.toByte(), length.toByte())
        else -> byteArrayOf(0x82.toByte(), (length ushr 8).toByte(), length.toByte())
    }
}
