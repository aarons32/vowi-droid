package com.vowi.vowidroid.core.ike

import com.vowi.vowidroid.core.carrier.CarrierProfile
import com.vowi.vowidroid.core.sim.AkaChallenge
import com.vowi.vowidroid.core.sim.AkaException
import com.vowi.vowidroid.core.sim.AkaMacFailureException
import com.vowi.vowidroid.core.sim.AkaProvider
import com.vowi.vowidroid.core.sim.SimIdentity
import com.vowi.vowidroid.core.util.concat
import com.vowi.vowidroid.core.util.constantTimeEquals
import com.vowi.vowidroid.core.util.putU16
import com.vowi.vowidroid.core.util.u16
import com.vowi.vowidroid.core.util.u32
import com.vowi.vowidroid.core.util.u8
import java.math.BigInteger

class EapAuthenticationRejectedException(message: String) : IkeException("vowifi: EAP-AKA authentication rejected $message")
class AkaProviderException(cause: Throwable) : IkeException("ike: SIM AKA authentication: ${cause.message}", cause)

class EapPacket(val code: Int, val identifier: Int, val type: Int = 0, val data: ByteArray = ByteArray(0))

class AkaAttribute(val type: Int, val raw: ByteArray, val offset: Int)

class AkaKeys(val kEncr: ByteArray, val kAut: ByteArray, val msk: ByteArray, val emsk: ByteArray)

class EapAction(val response: ByteArray? = null, val success: Boolean = false)

/** EAP-AKA (RFC 4187) peer, with the permanent NAI from the SIM identity. */
object EapAka {
    const val CODE_REQUEST = 1
    const val CODE_RESPONSE = 2
    const val CODE_SUCCESS = 3
    const val CODE_FAILURE = 4

    const val TYPE_IDENTITY = 1
    const val TYPE_AKA = 23

    const val SUBTYPE_CHALLENGE = 1
    const val SUBTYPE_AUTHENTICATION_REJECT = 2
    const val SUBTYPE_SYNCHRONIZATION_FAILURE = 4
    const val SUBTYPE_IDENTITY = 5
    const val SUBTYPE_NOTIFICATION = 12
    const val SUBTYPE_REAUTHENTICATION = 13
    const val SUBTYPE_CLIENT_ERROR = 14

    const val AT_RAND = 1
    const val AT_AUTN = 2
    const val AT_RES = 3
    const val AT_AUTS = 4
    const val AT_PERMANENT_ID_REQ = 10
    const val AT_MAC = 11
    const val AT_NOTIFICATION = 12
    const val AT_ANY_ID_REQ = 13
    const val AT_IDENTITY = 14
    const val AT_FULLAUTH_ID_REQ = 17
    const val AT_CLIENT_ERROR_CODE = 22
    const val AT_RESULT_IND = 135

    fun parsePacket(encoded: ByteArray): EapPacket {
        if (encoded.size < 4) throw IkeException("ike: truncated EAP header")
        val length = encoded.u16(2)
        if (length != encoded.size) throw IkeException("ike: EAP length $length does not match payload length ${encoded.size}")
        val code = encoded.u8(0)
        val identifier = encoded.u8(1)
        return when (code) {
            CODE_REQUEST, CODE_RESPONSE -> {
                if (encoded.size < 5) throw IkeException("ike: typed EAP packet is truncated")
                EapPacket(code, identifier, encoded.u8(4), encoded.copyOfRange(5, encoded.size))
            }
            CODE_SUCCESS, CODE_FAILURE -> {
                if (encoded.size != 4) throw IkeException("ike: EAP success/failure has trailing data")
                EapPacket(code, identifier)
            }
            else -> throw IkeException("ike: unsupported EAP code $code")
        }
    }

    fun marshalPacket(packet: EapPacket): ByteArray {
        var length = 4
        if (packet.code == CODE_REQUEST || packet.code == CODE_RESPONSE) {
            if (packet.type == 0) throw IkeException("ike: typed EAP packet has no type")
            length += 1 + packet.data.size
        } else if (packet.data.isNotEmpty() || packet.type != 0) {
            throw IkeException("ike: EAP success/failure cannot carry type data")
        }
        if (length > 65535) throw IkeException("ike: EAP packet exceeds 65535 bytes")
        val encoded = ByteArray(length)
        encoded[0] = packet.code.toByte()
        encoded[1] = packet.identifier.toByte()
        encoded.putU16(2, length)
        if (length > 4) {
            encoded[4] = packet.type.toByte()
            System.arraycopy(packet.data, 0, encoded, 5, packet.data.size)
        }
        return encoded
    }

    fun parseAttributes(encoded: ByteArray): List<AkaAttribute> {
        val result = ArrayList<AkaAttribute>()
        var offset = 0
        while (offset < encoded.size) {
            if (result.size >= 64 || offset + 2 > encoded.size) throw IkeException("ike: malformed EAP-AKA attribute list")
            val length = encoded.u8(offset + 1) * 4
            if (length < 4 || offset + length > encoded.size) {
                throw IkeException("ike: EAP-AKA attribute ${encoded.u8(offset)} has invalid length $length")
            }
            result.add(AkaAttribute(encoded.u8(offset), encoded.copyOfRange(offset, offset + length), offset))
            offset += length
        }
        return result
    }

    fun oneAttribute(attributes: List<AkaAttribute>, type: Int): AkaAttribute {
        val matches = attributes.filter { it.type == type }
        if (matches.size != 1) throw IkeException("ike: EAP-AKA expected one attribute $type, got ${matches.size}")
        return matches[0]
    }

    fun marshalAttribute(type: Int, value: ByteArray): ByteArray {
        val length = 2 + value.size
        val padded = (length + 3) and 3.inv()
        if (padded / 4 > 255) throw IkeException("ike: EAP-AKA attribute is too long")
        val encoded = ByteArray(padded)
        encoded[0] = type.toByte()
        encoded[1] = (padded / 4).toByte()
        System.arraycopy(value, 0, encoded, 2, value.size)
        return encoded
    }

    fun deriveKeys(identity: ByteArray, ik: ByteArray, ck: ByteArray): AkaKeys {
        if (identity.isEmpty()) throw IkeException("ike: EAP-AKA identity is empty")
        if (ik.size != 16 || ck.size != 16) throw IkeException("ike: EAP-AKA requires 16-byte IK and CK, got ${ik.size} and ${ck.size}")
        val masterKey = IkeCrypto.sha1(concat(identity, ik, ck))
        val stream = fips1862Prf(masterKey, 160)
        return AkaKeys(
            kEncr = stream.copyOfRange(0, 16),
            kAut = stream.copyOfRange(16, 32),
            msk = stream.copyOfRange(32, 96),
            emsk = stream.copyOfRange(96, 160),
        )
    }

    /** The FIPS 186-2 change-notice PRF with the SHA-1 G function (RFC 4187 Annex A). */
    fun fips1862Prf(seed: ByteArray, length: Int): ByteArray {
        var xkey = BigInteger(1, seed)
        val modulus = BigInteger.ONE.shiftLeft(160)
        val result = ByteArray(length)
        var produced = 0
        while (produced < length) {
            val xval = xkey.toFixedLength(20)
            val word = fipsSha1G(xval)
            val take = minOf(20, length - produced)
            System.arraycopy(word, 0, result, produced, take)
            produced += take
            xkey = xkey.add(BigInteger(1, word)).add(BigInteger.ONE).mod(modulus)
        }
        return result
    }

    /** SHA-1's compression function over one zero-filled block, without message padding. */
    fun fipsSha1G(xval: ByteArray): ByteArray {
        val words = IntArray(80)
        val block = ByteArray(64)
        System.arraycopy(xval, 0, block, 0, 20)
        for (index in 0 until 16) words[index] = block.u32(index * 4).toInt()
        for (index in 16 until 80) {
            val value = words[index - 3] xor words[index - 8] xor words[index - 14] xor words[index - 16]
            words[index] = (value shl 1) or (value ushr 31)
        }
        var a = 0x67452301
        var b = 0xEFCDAB89.toInt()
        var c = 0x98BADCFE.toInt()
        var d = 0x10325476
        var e = 0xC3D2E1F0.toInt()
        val initialA = a
        val initialB = b
        val initialC = c
        val initialD = d
        val initialE = e
        for (index in 0 until 80) {
            val function: Int
            val constant: Int
            when {
                index < 20 -> {
                    function = (b and c) or (b.inv() and d)
                    constant = 0x5A827999
                }
                index < 40 -> {
                    function = b xor c xor d
                    constant = 0x6ED9EBA1
                }
                index < 60 -> {
                    function = (b and c) or (b and d) or (c and d)
                    constant = 0x8F1BBCDC.toInt()
                }
                else -> {
                    function = b xor c xor d
                    constant = 0xCA62C1D6.toInt()
                }
            }
            val next = ((a shl 5) or (a ushr 27)) + function + e + constant + words[index]
            e = d
            d = c
            c = (b shl 30) or (b ushr 2)
            b = a
            a = next
        }
        val values = intArrayOf(initialA + a, initialB + b, initialC + c, initialD + d, initialE + e)
        val result = ByteArray(20)
        for ((index, value) in values.withIndex()) {
            result[index * 4] = (value ushr 24).toByte()
            result[index * 4 + 1] = (value ushr 16).toByte()
            result[index * 4 + 2] = (value ushr 8).toByte()
            result[index * 4 + 3] = value.toByte()
        }
        return result
    }

    fun mac(key: ByteArray, packet: ByteArray): ByteArray = IkeCrypto.hmac("HmacSHA1", key, packet).copyOf(16)

    /** The permanent NAI `0<IMSI>@nai.epc.mnc<MNC>.mcc<MCC>.3gppnetwork.org`. */
    fun permanentIdentity(identity: SimIdentity, profile: CarrierProfile): ByteArray {
        val imsi = profile.effectiveSubscriberImsi(identity.imsi.trim())
        if (imsi.length < 5 || imsi.length > 16) throw IkeException("ike: IMSI length is invalid for EAP-AKA")
        if (!imsi.all { it in '0'..'9' }) throw IkeException("ike: IMSI contains a non-digit")
        var mcc = profile.routeMcc.trim()
        var mnc = profile.routeMnc.trim()
        if (mcc.isEmpty() || mnc.isEmpty()) {
            mcc = identity.homeMcc.trim()
            mnc = identity.homeMnc.trim()
        }
        if (mcc.length != 3 || (mnc.length != 2 && mnc.length != 3)) throw IkeException("ike: explicit home MCC/MNC is required for EAP-AKA")
        while (mnc.length < 3) mnc = "0$mnc"
        return "0$imsi@nai.epc.mnc$mnc.mcc$mcc.3gppnetwork.org".toByteArray(Charsets.US_ASCII)
    }

    fun clientErrorResponse(identifier: Int): EapAction {
        val attribute = marshalAttribute(AT_CLIENT_ERROR_CODE, byteArrayOf(0, 0))
        return EapAction(marshalPacket(EapPacket(CODE_RESPONSE, identifier, TYPE_AKA, concat(byteArrayOf(SUBTYPE_CLIENT_ERROR.toByte(), 0, 0), attribute))))
    }

    fun authenticationRejectResponse(identifier: Int): EapAction =
        EapAction(marshalPacket(EapPacket(CODE_RESPONSE, identifier, TYPE_AKA, byteArrayOf(SUBTYPE_AUTHENTICATION_REJECT.toByte(), 0, 0))))

    /** Fills the AT_MAC inside an EAP packet whose MAC field is currently zero. */
    private fun fillMac(packet: ByteArray, attributes: List<AkaAttribute>, kAut: ByteArray): ByteArray {
        val macAttribute = oneAttribute(attributes, AT_MAC)
        val offset = 5 + 3 + macAttribute.offset
        val computed = mac(kAut, packet)
        System.arraycopy(computed, 0, packet, offset + 4, 16)
        return packet
    }

    /** Verifies the AT_MAC of a received request by zeroing it and recomputing. */
    private fun verifyMac(request: EapPacket, macAttribute: AkaAttribute, kAut: ByteArray): Boolean {
        val zeroed = marshalPacket(request)
        val offset = 5 + 3 + macAttribute.offset
        if (offset + 20 > zeroed.size) return false
        for (index in offset + 4 until offset + 20) zeroed[index] = 0
        return constantTimeEquals(mac(kAut, zeroed), macAttribute.raw.copyOfRange(4, 20))
    }

    /** One EAP-AKA peer state machine for one IKE_AUTH exchange. */
    class Client(
        private val simIdentity: SimIdentity,
        profile: CarrierProfile,
        private val provider: AkaProvider,
        /** How the subscriber is named; the default is the permanent NAI in the clear. */
        val names: EapIdentity = EapIdentities.plain(simIdentity, profile),
    ) {
        /** What EAP-Response/Identity and AT_IDENTITY carry. */
        val identity: ByteArray get() = names.eap

        /** The IKE IDi. */
        val idi: ByteArray get() = names.idi

        /** Which identity the server derived the keys from, once the challenge is answered. */
        var keyIdentity: String = ""
            private set
        var keys: AkaKeys? = null
            private set
        var challengeComplete = false
            private set
        private var resultIndication = false
        private var protectedSuccess = false
        private var lastResponseStage = "in the initial IKE_AUTH identity exchange"

        fun handle(encoded: ByteArray): EapAction {
            val packet = parsePacket(encoded)
            when (packet.code) {
                CODE_FAILURE -> {
                    val stage = if (challengeComplete) "after the SIM AKA challenge response" else lastResponseStage
                    throw EapAuthenticationRejectedException(stage)
                }
                CODE_SUCCESS -> {
                    if (!challengeComplete) throw IkeException("ike: EAP success arrived before an authenticated AKA challenge")
                    if (resultIndication && !protectedSuccess) throw IkeException("ike: unprotected EAP success received after AT_RESULT_IND")
                    return EapAction(success = true)
                }
                CODE_REQUEST -> Unit
                else -> throw IkeException("ike: unexpected EAP code ${packet.code} from responder")
            }
            return when (packet.type) {
                TYPE_IDENTITY -> {
                    lastResponseStage = "after EAP-Response/Identity"
                    EapAction(marshalPacket(EapPacket(CODE_RESPONSE, packet.identifier, TYPE_IDENTITY, identity)))
                }
                TYPE_AKA -> handleAkaRequest(packet)
                else -> throw IkeException("ike: responder requested unsupported EAP type ${packet.type}")
            }
        }

        private fun handleAkaRequest(packet: EapPacket): EapAction {
            if (packet.data.size < 3) return clientErrorResponse(packet.identifier)
            val subtype = packet.data.u8(0)
            if (packet.data[1] != 0.toByte() || packet.data[2] != 0.toByte()) return clientErrorResponse(packet.identifier)
            val attributes = try {
                parseAttributes(packet.data.copyOfRange(3, packet.data.size))
            } catch (_: IkeException) {
                return clientErrorResponse(packet.identifier)
            }
            return when (subtype) {
                SUBTYPE_IDENTITY -> try {
                    respondIdentity(packet.identifier, attributes)
                } catch (_: IkeException) {
                    clientErrorResponse(packet.identifier)
                }
                SUBTYPE_CHALLENGE -> try {
                    respondChallenge(packet, attributes)
                } catch (error: IkeException) {
                    if (error is AkaProviderException) throw error
                    clientErrorResponse(packet.identifier)
                }
                SUBTYPE_NOTIFICATION -> respondNotification(packet, attributes)
                else -> clientErrorResponse(packet.identifier)
            }
        }

        private fun respondIdentity(identifier: Int, attributes: List<AkaAttribute>): EapAction {
            var requests = 0
            for (attribute in attributes) {
                when (attribute.type) {
                    AT_PERMANENT_ID_REQ, AT_ANY_ID_REQ, AT_FULLAUTH_ID_REQ -> {
                        if (attribute.raw.size != 4) throw IkeException("ike: malformed EAP-AKA identity request attribute")
                        requests++
                    }
                    else -> if (attribute.type < 128) throw IkeException("ike: unsupported mandatory EAP-AKA identity attribute ${attribute.type}")
                }
            }
            if (requests != 1) throw IkeException("ike: EAP-AKA identity request must contain exactly one request attribute")
            val value = concat(byteArrayOf((identity.size ushr 8).toByte(), identity.size.toByte()), identity)
            val data = concat(byteArrayOf(SUBTYPE_IDENTITY.toByte(), 0, 0), marshalAttribute(AT_IDENTITY, value))
            lastResponseStage = "after EAP-Response/AKA-Identity"
            return EapAction(marshalPacket(EapPacket(CODE_RESPONSE, identifier, TYPE_AKA, data)))
        }

        private fun respondChallenge(request: EapPacket, attributes: List<AkaAttribute>): EapAction {
            for (attribute in attributes) {
                when (attribute.type) {
                    AT_RAND, AT_AUTN, AT_MAC, AT_RESULT_IND -> Unit
                    else -> if (attribute.type < 128) throw IkeException("ike: unknown mandatory EAP-AKA challenge attribute ${attribute.type}")
                }
            }
            val randAttribute = oneAttribute(attributes, AT_RAND)
            val autnAttribute = oneAttribute(attributes, AT_AUTN)
            val macAttribute = oneAttribute(attributes, AT_MAC)
            if (randAttribute.raw.size != 20 || autnAttribute.raw.size != 20 || macAttribute.raw.size != 20) {
                throw IkeException("ike: EAP-AKA RAND, AUTN, or MAC has an invalid length")
            }
            val challenge = AkaChallenge(randAttribute.raw.copyOfRange(4, 20), autnAttribute.raw.copyOfRange(4, 20))
            val result = try {
                provider.authenticate(simIdentity, challenge)
            } catch (_: AkaMacFailureException) {
                return authenticationRejectResponse(request.identifier)
            } catch (error: AkaException) {
                throw AkaProviderException(error)
            } catch (error: RuntimeException) {
                throw AkaProviderException(error)
            }
            if (result.synchronizationFailure) {
                val auts = result.auts ?: ByteArray(0)
                if (auts.size != 14) throw IkeException("ike: SIM reported synchronization failure without a 14-byte AUTS")
                val data = concat(byteArrayOf(SUBTYPE_SYNCHRONIZATION_FAILURE.toByte(), 0, 0), marshalAttribute(AT_AUTS, auts))
                return EapAction(marshalPacket(EapPacket(CODE_RESPONSE, request.identifier, TYPE_AKA, data)))
            }
            if (result.res.size < 4 || result.res.size > 16) throw IkeException("ike: SIM returned invalid RES length ${result.res.size}")
            // With a protected identity the server may derive the keys from what was sent or from what it
            // decrypted; its MAC says which, so each candidate is tried before the exchange is given up.
            var derived: AkaKeys? = null
            for ((label, candidate) in names.keyCandidates) {
                val keysFor = deriveKeys(candidate, result.ik, result.ck)
                if (verifyMac(request, macAttribute, keysFor.kAut)) {
                    derived = keysFor
                    keyIdentity = label
                    break
                }
            }
            if (derived == null) throw IkeException("ike: EAP-AKA server MAC is invalid")

            val resValue = concat(byteArrayOf(((result.res.size * 8) ushr 8).toByte(), (result.res.size * 8).toByte()), result.res)
            var responseData = concat(byteArrayOf(SUBTYPE_CHALLENGE.toByte(), 0, 0), marshalAttribute(AT_RES, resValue))
            var indication = false
            for (attribute in attributes) {
                if (attribute.type == AT_RESULT_IND) {
                    if (attribute.raw.size != 4) throw IkeException("ike: malformed AT_RESULT_IND")
                    responseData = concat(responseData, attribute.raw)
                    indication = true
                }
            }
            responseData = concat(responseData, marshalAttribute(AT_MAC, ByteArray(18)))
            val response = marshalPacket(EapPacket(CODE_RESPONSE, request.identifier, TYPE_AKA, responseData))
            fillMac(response, parseAttributes(responseData.copyOfRange(3, responseData.size)), derived.kAut)
            keys = derived
            challengeComplete = true
            resultIndication = indication
            return EapAction(response)
        }

        private fun respondNotification(request: EapPacket, attributes: List<AkaAttribute>): EapAction {
            val notification = try {
                oneAttribute(attributes, AT_NOTIFICATION)
            } catch (_: IkeException) {
                return clientErrorResponse(request.identifier)
            }
            if (notification.raw.size != 4) throw IkeException("ike: malformed EAP-AKA notification")
            val code = notification.raw.u16(2)
            val currentKeys = keys
            if (code != 32768) {
                var responseData = byteArrayOf(SUBTYPE_NOTIFICATION.toByte(), 0, 0)
                val protected = code and 0x4000 == 0
                if (protected) {
                    if (!challengeComplete || currentKeys == null) return clientErrorResponse(request.identifier)
                    val macAttribute = attributes.singleOrNull { it.type == AT_MAC }
                    if (macAttribute == null || macAttribute.raw.size != 20) return clientErrorResponse(request.identifier)
                    if (!verifyMac(request, macAttribute, currentKeys.kAut)) return clientErrorResponse(request.identifier)
                    responseData = concat(responseData, marshalAttribute(AT_MAC, ByteArray(18)))
                }
                val response = marshalPacket(EapPacket(CODE_RESPONSE, request.identifier, TYPE_AKA, responseData))
                if (protected) fillMac(response, parseAttributes(responseData.copyOfRange(3, responseData.size)), currentKeys!!.kAut)
                return EapAction(response)
            }
            if (!challengeComplete || !resultIndication || currentKeys == null) {
                throw IkeException("ike: unexpected protected EAP-AKA success notification")
            }
            val macAttribute = oneAttribute(attributes, AT_MAC)
            if (macAttribute.raw.size != 20) throw IkeException("ike: malformed notification AT_MAC")
            if (!verifyMac(request, macAttribute, currentKeys.kAut)) throw IkeException("ike: EAP-AKA protected success MAC is invalid")
            val responseData = concat(byteArrayOf(SUBTYPE_NOTIFICATION.toByte(), 0, 0), marshalAttribute(AT_MAC, ByteArray(18)))
            val response = marshalPacket(EapPacket(CODE_RESPONSE, request.identifier, TYPE_AKA, responseData))
            fillMac(response, parseAttributes(responseData.copyOfRange(3, responseData.size)), currentKeys.kAut)
            protectedSuccess = true
            return EapAction(response)
        }
    }
}
