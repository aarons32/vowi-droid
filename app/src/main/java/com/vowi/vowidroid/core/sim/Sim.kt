package com.vowi.vowidroid.core.sim

import com.vowi.vowidroid.core.util.u8

/**
 * What the protocol stack needs to know about a SIM. Never copied wholesale
 * into UI state, so IMSI/ICCID stay out of screens and logs.
 */
data class SimIdentity(
    val iccid: String,
    val imsi: String,
    val homeMcc: String,
    val homeMnc: String,
    val imei: String = "",
    val homeCountryCode: String = "",
    val spn: String = "",
    val gid1: String = "",
    val gid2: String = "",
    /** An explicitly configured ePDG hostname, or empty for carrier/3GPP derivation. */
    val epdg: String = "",
    /** The SMS service centre, needed to send texts. */
    val smsc: String = "",
) {
    val homePlmn: String get() = homeMcc + homeMnc

    fun validate() {
        require(iccid.isNotBlank()) { "SIM identity has no ICCID" }
        require(isDigits(homeMcc, 3, 3)) { "home MCC must contain three digits" }
        require(isDigits(homeMnc, 2, 3)) { "home MNC must contain two or three digits" }
    }

    companion object {
        fun isDigits(value: String, minimum: Int, maximum: Int): Boolean {
            val trimmed = value.trim()
            return trimmed.length in minimum..maximum && trimmed.all { it in '0'..'9' }
        }

        /** Splits a 5 or 6 digit PLMN into MCC and MNC. */
        fun splitPlmn(plmn: String): Pair<String, String>? {
            val clean = plmn.trim()
            if (clean.length != 5 && clean.length != 6 || !clean.all { it.isDigit() }) return null
            return clean.substring(0, 3) to clean.substring(3)
        }
    }
}

/** The UMTS AKA input carried by EAP-AKA and by IMS AKAv1-MD5. */
class AkaChallenge(val rand: ByteArray, val autn: ByteArray) {
    init {
        require(rand.size == 16 && autn.size == 16) { "AKA RAND and AUTN must be 16 bytes" }
    }
}

/**
 * Either a successful authentication vector (RES/CK/IK) or the AUTS for a
 * synchronisation failure. Never put these values in logs or errors.
 */
class AkaResult(
    val res: ByteArray = ByteArray(0),
    val ck: ByteArray = ByteArray(0),
    val ik: ByteArray = ByteArray(0),
    val auts: ByteArray? = null,
) {
    val synchronizationFailure: Boolean get() = auts != null

    companion object {
        /**
         * Decodes the USIM AUTHENTICATE response in the 3G context (3GPP TS
         * 31.102 7.1.2.1), which `TelephonyManager.getIccAuthentication` returns
         * unchanged: `DB L RES L CK L IK [L Kc]` or `DC L AUTS`.
         */
        fun fromUiccResponse(response: ByteArray): AkaResult {
            if (response.isEmpty()) throw AkaException("the SIM returned an empty AUTHENTICATE response")
            var offset = 1
            fun next(): ByteArray {
                if (offset >= response.size) throw AkaException("truncated AUTHENTICATE response")
                val length = response.u8(offset)
                offset += 1
                if (offset + length > response.size) throw AkaException("truncated AUTHENTICATE response")
                val value = response.copyOfRange(offset, offset + length)
                offset += length
                return value
            }
            return when (response.u8(0)) {
                0xDB -> {
                    val res = next()
                    val ck = next()
                    val ik = next()
                    if (res.size !in 4..16 || ck.size != 16 || ik.size != 16) {
                        throw AkaException("AUTHENTICATE response has invalid RES/CK/IK lengths")
                    }
                    AkaResult(res, ck, ik)
                }
                0xDC -> {
                    val auts = next()
                    if (auts.size != 14) throw AkaException("AUTHENTICATE synchronisation failure without a 14-byte AUTS")
                    AkaResult(auts = auts)
                }
                else -> throw AkaException("unexpected AUTHENTICATE response tag ${response.u8(0)}")
            }
        }
    }
}

/** Runs AKA on the real SIM. Implementations block; call them off the main thread. */
interface AkaProvider {
    @Throws(AkaException::class)
    fun authenticate(identity: SimIdentity, challenge: AkaChallenge): AkaResult
}

open class AkaException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The USIM rejected the network's challenge (MAC failure, SW 9862). */
class AkaMacFailureException : AkaException("the SIM rejected the network authentication (MAC failure)")
