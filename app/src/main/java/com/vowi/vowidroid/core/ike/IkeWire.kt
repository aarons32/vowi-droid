package com.vowi.vowidroid.core.ike

import com.vowi.vowidroid.core.util.ByteWriter
import com.vowi.vowidroid.core.util.putU16
import com.vowi.vowidroid.core.util.putU32
import com.vowi.vowidroid.core.util.u16
import com.vowi.vowidroid.core.util.u32
import com.vowi.vowidroid.core.util.u8

/** IKEv2 constants (RFC 7296) used by this stack. */
object Ike {
    const val HEADER_LENGTH = 28
    const val MAJOR_VERSION = 2

    const val EXCHANGE_SA_INIT = 34
    const val EXCHANGE_AUTH = 35
    const val EXCHANGE_CREATE_CHILD_SA = 36
    const val EXCHANGE_INFORMATIONAL = 37

    const val FLAG_INITIATOR = 0x08
    const val FLAG_VERSION = 0x10
    const val FLAG_RESPONSE = 0x20

    const val PAYLOAD_NONE = 0
    const val PAYLOAD_SA = 33
    const val PAYLOAD_KE = 34
    const val PAYLOAD_IDI = 35
    const val PAYLOAD_IDR = 36
    const val PAYLOAD_CERT = 37
    const val PAYLOAD_CERTREQ = 38
    const val PAYLOAD_AUTH = 39
    const val PAYLOAD_NONCE = 40
    const val PAYLOAD_NOTIFY = 41
    const val PAYLOAD_DELETE = 42
    const val PAYLOAD_VENDOR = 43
    const val PAYLOAD_TSI = 44
    const val PAYLOAD_TSR = 45
    const val PAYLOAD_ENCRYPTED = 46
    const val PAYLOAD_CP = 47
    const val PAYLOAD_EAP = 48
    const val PAYLOAD_ENCRYPTED_FRAGMENT = 53

    const val PROTOCOL_IKE = 1
    const val PROTOCOL_AH = 2
    const val PROTOCOL_ESP = 3

    const val TRANSFORM_ENCRYPTION = 1
    const val TRANSFORM_PRF = 2
    const val TRANSFORM_INTEGRITY = 3
    const val TRANSFORM_DH = 4
    const val TRANSFORM_ESN = 5

    const val ENCRYPTION_AES_CBC = 12

    const val PRF_HMAC_SHA1 = 2
    const val PRF_HMAC_SHA256 = 5
    const val PRF_HMAC_SHA384 = 6
    const val PRF_HMAC_SHA512 = 7

    const val INTEGRITY_HMAC_SHA1_96 = 2
    const val INTEGRITY_HMAC_SHA256_128 = 12
    const val INTEGRITY_HMAC_SHA384_192 = 13
    const val INTEGRITY_HMAC_SHA512_256 = 14

    const val DH_MODP1024 = 2
    const val DH_MODP1536 = 5
    const val DH_MODP2048 = 14
    const val DH_MODP3072 = 15
    const val DH_MODP4096 = 16
    const val DH_MODP8192 = 18

    const val TRANSFORM_ATTRIBUTE_KEY_LENGTH = 14

    const val ID_FQDN = 2
    const val ID_RFC822_ADDR = 3

    // Error notifications (< 16384).
    const val NOTIFY_UNSUPPORTED_CRITICAL_PAYLOAD = 1
    const val NOTIFY_INVALID_SYNTAX = 7
    const val NOTIFY_INVALID_MESSAGE_ID = 9
    const val NOTIFY_NO_PROPOSAL_CHOSEN = 14
    const val NOTIFY_INVALID_KE_PAYLOAD = 17
    const val NOTIFY_AUTHENTICATION_FAILED = 24
    const val NOTIFY_NO_ADDITIONAL_SAS = 35
    const val NOTIFY_CHILD_SA_NOT_FOUND = 44
    const val NOTIFY_TEMPORARY_FAILURE = 43

    // Status notifications.
    const val NOTIFY_INITIAL_CONTACT = 16384
    const val NOTIFY_NAT_DETECTION_SOURCE_IP = 16388
    const val NOTIFY_NAT_DETECTION_DESTINATION_IP = 16389
    const val NOTIFY_COOKIE = 16390
    const val NOTIFY_USE_TRANSPORT_MODE = 16391
    const val NOTIFY_REKEY_SA = 16393
    const val NOTIFY_MOBIKE_SUPPORTED = 16396
    const val NOTIFY_EAP_ONLY_AUTHENTICATION = 16417
    const val NOTIFY_IKEV2_FRAGMENTATION_SUPPORTED = 16430
    const val NOTIFY_DEVICE_IDENTITY = 41101

    const val DEFAULT_FRAGMENT_SIZE = 1100
}

open class IkeException(message: String, cause: Throwable? = null) : Exception(message, cause)
class IkeMalformedException(message: String) : IkeException("ike: malformed packet: $message")
class IkeUnexpectedPacketException(message: String) : IkeException("ike: unexpected packet: $message")
class IkeUnsupportedSuiteException(message: String) : IkeException("ike: unsupported negotiated suite: $message")
class IkeIntegrityException : IkeException("ike: encrypted payload integrity mismatch")

/** A fixed IKE header. SPIs are 8 bytes; message IDs are unsigned 32-bit values. */
data class IkeHeader(
    val initiatorSpi: ByteArray,
    val responderSpi: ByteArray,
    val nextPayload: Int = Ike.PAYLOAD_NONE,
    val version: Int = Ike.MAJOR_VERSION shl 4,
    val exchange: Int,
    val flags: Int,
    val messageId: Long,
    val length: Long = 0,
) {
    val isResponse: Boolean get() = flags and Ike.FLAG_RESPONSE != 0
    val fromInitiator: Boolean get() = flags and Ike.FLAG_INITIATOR != 0

    fun marshal(body: ByteArray): ByteArray {
        val packet = ByteArray(Ike.HEADER_LENGTH + body.size)
        System.arraycopy(initiatorSpi, 0, packet, 0, 8)
        System.arraycopy(responderSpi, 0, packet, 8, 8)
        packet[16] = nextPayload.toByte()
        packet[17] = (if (version == 0) Ike.MAJOR_VERSION shl 4 else version).toByte()
        packet[18] = exchange.toByte()
        packet[19] = flags.toByte()
        packet.putU32(20, messageId)
        packet.putU32(24, packet.size.toLong())
        System.arraycopy(body, 0, packet, Ike.HEADER_LENGTH, body.size)
        return packet
    }

    companion object {
        val ZERO_SPI = ByteArray(8)

        /** Parses a complete datagram into its header and body. */
        fun parse(packet: ByteArray): Pair<IkeHeader, ByteArray> {
            if (packet.size < Ike.HEADER_LENGTH) throw IkeMalformedException("header is truncated")
            val header = IkeHeader(
                initiatorSpi = packet.copyOfRange(0, 8),
                responderSpi = packet.copyOfRange(8, 16),
                nextPayload = packet.u8(16),
                version = packet.u8(17),
                exchange = packet.u8(18),
                flags = packet.u8(19),
                messageId = packet.u32(20),
                length = packet.u32(24),
            )
            if (header.version ushr 4 != Ike.MAJOR_VERSION) {
                throw IkeMalformedException("unsupported IKE major version ${header.version ushr 4}")
            }
            if (header.length < Ike.HEADER_LENGTH || header.length != packet.size.toLong()) {
                throw IkeMalformedException("encoded length ${header.length} does not match datagram length ${packet.size}")
            }
            return header to packet.copyOfRange(Ike.HEADER_LENGTH, packet.size)
        }
    }
}

class Payload(val type: Int, val body: ByteArray, val critical: Boolean = false)

data class Transform(val type: Int, val id: Int, val keyLength: Int = 0)

class Proposal(val number: Int, val protocol: Int, val spi: ByteArray, val transforms: List<Transform>)

class Notify(val protocol: Int, val spi: ByteArray, val type: Int, val data: ByteArray)

/** The IKE SA suite the responder selected. */
data class IkeSuite(
    val encryptionId: Int,
    val encryptionBits: Int,
    val prfId: Int,
    val integrityId: Int,
    val dhId: Int,
)

object IkeWire {
    fun marshalPayloadChain(payloads: List<Payload>): Pair<Int, ByteArray> {
        if (payloads.isEmpty()) return Ike.PAYLOAD_NONE to ByteArray(0)
        val out = ByteWriter()
        for ((index, item) in payloads.withIndex()) {
            if (item.type == Ike.PAYLOAD_NONE || item.type == Ike.PAYLOAD_ENCRYPTED) {
                throw IkeException("ike: invalid ordinary payload type ${item.type}")
            }
            val next = if (index + 1 < payloads.size) payloads[index + 1].type else Ike.PAYLOAD_NONE
            val length = 4 + item.body.size
            if (length > 65535) throw IkeException("ike: payload exceeds 65535 bytes")
            out.byte(next).byte(if (item.critical) 0x80 else 0).u16(length).bytes(item.body)
        }
        return payloads[0].type to out.toByteArray()
    }

    fun parsePayloadChain(first: Int, encoded: ByteArray): List<Payload> {
        val result = ArrayList<Payload>()
        var next = first
        var offset = 0
        while (next != Ike.PAYLOAD_NONE) {
            if (result.size >= 64) throw IkeMalformedException("too many chained payloads")
            if (offset + 4 > encoded.size) throw IkeMalformedException("payload header is truncated")
            val following = encoded.u8(offset)
            val flags = encoded.u8(offset + 1)
            val length = encoded.u16(offset + 2)
            if (length < 4 || offset + length > encoded.size) {
                throw IkeMalformedException("payload type $next has invalid length $length")
            }
            result.add(Payload(next, encoded.copyOfRange(offset + 4, offset + length), flags and 0x80 != 0))
            offset += length
            next = following
        }
        if (offset != encoded.size) throw IkeMalformedException("${encoded.size - offset} trailing payload bytes")
        return result
    }

    fun payloadsOfType(payloads: List<Payload>, type: Int): List<Payload> = payloads.filter { it.type == type }

    fun onePayload(payloads: List<Payload>, type: Int): Payload {
        val matches = payloadsOfType(payloads, type)
        if (matches.size != 1) throw IkeUnexpectedPacketException("expected one payload type $type, got ${matches.size}")
        return matches[0]
    }

    fun marshalProposals(proposals: List<Proposal>): ByteArray {
        val out = ByteWriter()
        for ((proposalIndex, item) in proposals.withIndex()) {
            if (item.spi.size > 255 || item.transforms.size > 255) throw IkeException("ike: proposal has too many bytes or transforms")
            val transforms = ByteWriter()
            for ((transformIndex, candidate) in item.transforms.withIndex()) {
                val attributes = if (candidate.keyLength > 0) {
                    ByteArray(4).also {
                        it.putU16(0, 0x8000 or Ike.TRANSFORM_ATTRIBUTE_KEY_LENGTH)
                        it.putU16(2, candidate.keyLength)
                    }
                } else {
                    ByteArray(0)
                }
                val length = 8 + attributes.size
                transforms.byte(if (transformIndex + 1 < item.transforms.size) 3 else 0)
                transforms.byte(0)
                transforms.u16(length)
                transforms.byte(candidate.type)
                transforms.byte(0)
                transforms.u16(candidate.id)
                transforms.bytes(attributes)
            }
            val encodedTransforms = transforms.toByteArray()
            val length = 8 + item.spi.size + encodedTransforms.size
            out.byte(if (proposalIndex + 1 < proposals.size) 2 else 0)
            out.byte(0)
            out.u16(length)
            out.byte(item.number)
            out.byte(item.protocol)
            out.byte(item.spi.size)
            out.byte(item.transforms.size)
            out.bytes(item.spi)
            out.bytes(encodedTransforms)
        }
        return out.toByteArray()
    }

    fun parseProposals(encoded: ByteArray): List<Proposal> {
        val result = ArrayList<Proposal>()
        var offset = 0
        while (true) {
            if (offset == encoded.size) break
            if (result.size >= 16 || offset + 8 > encoded.size) throw IkeMalformedException("invalid SA proposal header")
            val last = encoded.u8(offset)
            val length = encoded.u16(offset + 2)
            val spiSize = encoded.u8(offset + 6)
            val transformCount = encoded.u8(offset + 7)
            if (length < 8 + spiSize || offset + length > encoded.size) throw IkeMalformedException("invalid SA proposal length")
            val number = encoded.u8(offset + 4)
            val protocol = encoded.u8(offset + 5)
            val spi = encoded.copyOfRange(offset + 8, offset + 8 + spiSize)
            val transforms = ArrayList<Transform>()
            var transformOffset = offset + 8 + spiSize
            val proposalEnd = offset + length
            while (transformOffset < proposalEnd) {
                if (transforms.size >= 32 || transformOffset + 8 > proposalEnd) throw IkeMalformedException("invalid transform header")
                val transformLength = encoded.u16(transformOffset + 2)
                if (transformLength < 8 || transformOffset + transformLength > proposalEnd) {
                    throw IkeMalformedException("invalid transform length")
                }
                val transformEnd = transformOffset + transformLength
                if (transformEnd < proposalEnd && encoded.u8(transformOffset) != 3) {
                    throw IkeMalformedException("non-final transform has invalid chaining marker")
                }
                if (transformEnd == proposalEnd && encoded.u8(transformOffset) != 0) {
                    throw IkeMalformedException("final transform has invalid chaining marker")
                }
                var keyLength = 0
                var attributeOffset = transformOffset + 8
                while (attributeOffset < transformEnd) {
                    if (attributeOffset + 4 > transformEnd) throw IkeMalformedException("truncated transform attribute")
                    val attributeType = encoded.u16(attributeOffset)
                    if (attributeType and 0x8000 != 0) {
                        if (attributeType and 0x7fff == Ike.TRANSFORM_ATTRIBUTE_KEY_LENGTH) {
                            keyLength = encoded.u16(attributeOffset + 2)
                        }
                        attributeOffset += 4
                        continue
                    }
                    val attributeLength = encoded.u16(attributeOffset + 2)
                    if (attributeOffset + 4 + attributeLength > transformEnd) throw IkeMalformedException("invalid transform TLV attribute")
                    if (attributeType == Ike.TRANSFORM_ATTRIBUTE_KEY_LENGTH && attributeLength == 2) {
                        keyLength = encoded.u16(attributeOffset + 4)
                    }
                    attributeOffset += 4 + attributeLength
                }
                transforms.add(Transform(encoded.u8(transformOffset + 4), encoded.u16(transformOffset + 6), keyLength))
                transformOffset += transformLength
            }
            if (transformOffset != proposalEnd || transforms.size != transformCount) {
                throw IkeMalformedException("transform count mismatch")
            }
            result.add(Proposal(number, protocol, spi, transforms))
            offset = proposalEnd
            if (last == 0) {
                if (offset != encoded.size) throw IkeMalformedException("bytes follow last proposal")
                break
            }
            if (last != 2) throw IkeMalformedException("invalid proposal chaining marker $last")
        }
        if (result.isEmpty()) throw IkeMalformedException("empty SA payload")
        return result
    }

    fun parseIkeSuite(item: Proposal): IkeSuite {
        if (item.protocol != Ike.PROTOCOL_IKE || item.spi.isNotEmpty()) {
            throw IkeUnsupportedSuiteException("responder selected a non-IKE proposal")
        }
        var encryptionId = 0
        var encryptionBits = 0
        var prfId = 0
        var integrityId = 0
        var dhId = 0
        val seen = HashSet<Int>()
        for (candidate in item.transforms) {
            if (!seen.add(candidate.type)) throw IkeUnsupportedSuiteException("duplicate transform type ${candidate.type}")
            when (candidate.type) {
                Ike.TRANSFORM_ENCRYPTION -> {
                    encryptionId = candidate.id
                    encryptionBits = candidate.keyLength
                }
                Ike.TRANSFORM_PRF -> prfId = candidate.id
                Ike.TRANSFORM_INTEGRITY -> integrityId = candidate.id
                Ike.TRANSFORM_DH -> dhId = candidate.id
                else -> throw IkeUnsupportedSuiteException("IKE transform type ${candidate.type}")
            }
        }
        if (!IkeAlgorithms.supportsEncryption(encryptionId, encryptionBits)) {
            throw IkeUnsupportedSuiteException("encryption id=$encryptionId bits=$encryptionBits")
        }
        if (!IkeAlgorithms.supportsPrf(prfId)) throw IkeUnsupportedSuiteException("PRF id=$prfId")
        if (!IkeAlgorithms.supportsIntegrity(integrityId)) throw IkeUnsupportedSuiteException("integrity id=$integrityId")
        if (!IkeAlgorithms.supportsDh(dhId)) throw IkeUnsupportedSuiteException("DH id=$dhId")
        return IkeSuite(encryptionId, encryptionBits, prfId, integrityId, dhId)
    }

    fun makeNotify(type: Int, data: ByteArray = ByteArray(0), protocol: Int = 0, spi: ByteArray = ByteArray(0)): Payload {
        val body = ByteArray(4 + spi.size + data.size)
        body[0] = protocol.toByte()
        body[1] = spi.size.toByte()
        body.putU16(2, type)
        System.arraycopy(spi, 0, body, 4, spi.size)
        System.arraycopy(data, 0, body, 4 + spi.size, data.size)
        return Payload(Ike.PAYLOAD_NOTIFY, body)
    }

    fun parseNotify(item: Payload): Notify {
        if (item.type != Ike.PAYLOAD_NOTIFY || item.body.size < 4) throw IkeMalformedException("invalid notify payload")
        val spiSize = item.body.u8(1)
        if (4 + spiSize > item.body.size) throw IkeMalformedException("truncated notify SPI")
        return Notify(
            protocol = item.body.u8(0),
            spi = item.body.copyOfRange(4, 4 + spiSize),
            type = item.body.u16(2),
            data = item.body.copyOfRange(4 + spiSize, item.body.size),
        )
    }

    fun notifies(payloads: List<Payload>): List<Notify> = payloadsOfType(payloads, Ike.PAYLOAD_NOTIFY).map(::parseNotify)

    fun hasNotify(payloads: List<Payload>, type: Int): Boolean =
        payloadsOfType(payloads, Ike.PAYLOAD_NOTIFY).any { runCatching { parseNotify(it).type == type }.getOrDefault(false) }

    /** A Delete payload for one protocol; IKE deletes carry no SPIs. */
    fun makeDelete(protocol: Int, spis: List<ByteArray> = emptyList()): Payload {
        val spiSize = if (protocol == Ike.PROTOCOL_IKE) 0 else 4
        val out = ByteWriter().byte(protocol).byte(spiSize).u16(spis.size)
        for (spi in spis) out.bytes(spi)
        return Payload(Ike.PAYLOAD_DELETE, out.toByteArray())
    }

    class Delete(val protocol: Int, val spis: List<ByteArray>)

    fun parseDelete(item: Payload): Delete {
        if (item.type != Ike.PAYLOAD_DELETE || item.body.size < 4) throw IkeMalformedException("invalid delete payload")
        val protocol = item.body.u8(0)
        val spiSize = item.body.u8(1)
        val count = item.body.u16(2)
        if (4 + spiSize * count != item.body.size) throw IkeMalformedException("delete payload length mismatch")
        val spis = (0 until count).map { item.body.copyOfRange(4 + it * spiSize, 4 + (it + 1) * spiSize) }
        return Delete(protocol, spis)
    }
}
