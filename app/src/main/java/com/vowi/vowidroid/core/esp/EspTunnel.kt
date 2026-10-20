package com.vowi.vowidroid.core.esp

import com.vowi.vowidroid.core.ike.ChildSaConfig
import com.vowi.vowidroid.core.ike.IkeAlgorithms
import com.vowi.vowidroid.core.ike.IkeChild
import com.vowi.vowidroid.core.ike.IkeCrypto
import com.vowi.vowidroid.core.ike.TrafficSelector
import com.vowi.vowidroid.core.net.IpPacket
import com.vowi.vowidroid.core.util.RandomSource
import com.vowi.vowidroid.core.util.Rng
import com.vowi.vowidroid.core.util.constantTimeEquals
import com.vowi.vowidroid.core.util.putU32
import com.vowi.vowidroid.core.util.u16
import com.vowi.vowidroid.core.util.u32
import com.vowi.vowidroid.core.util.u8

open class EspException(message: String) : Exception(message)
class EspAuthenticationException : EspException("esp: authentication failed")
class EspReplayException : EspException("esp: packet is outside the replay window")
class EspPolicyDropException(message: String) : EspException("esp: packet is not eligible for this SA: $message")

const val ESP_HEADER_LENGTH = 8
const val ESP_REPLAY_WINDOW = 64

/** RFC 4303 anti-replay window over 32-bit sequence numbers. */
class ReplayWindow {
    private var highest = 0L
    private var bitmap = 0L

    fun wouldAccept(sequence: Long): Boolean {
        if (sequence == 0L) return false
        if (highest == 0L || sequence > highest) return true
        val difference = highest - sequence
        if (difference >= ESP_REPLAY_WINDOW) return false
        return bitmap and (1L shl difference.toInt()) == 0L
    }

    fun commit(sequence: Long) {
        if (highest == 0L) {
            highest = sequence
            bitmap = 1
            return
        }
        if (sequence > highest) {
            val difference = sequence - highest
            bitmap = if (difference >= ESP_REPLAY_WINDOW) 1 else (bitmap shl difference.toInt()) or 1
            highest = sequence
            return
        }
        bitmap = bitmap or (1L shl (highest - sequence).toInt())
    }
}

class InnerPacketMetadata(
    val source: ByteArray,
    val destination: ByteArray,
    val protocol: Int,
    val sourcePort: Int,
    val destinationPort: Int,
    val nextHeader: Int,
)

/** One direction of a tunnel-mode ESP SA: AES-CBC with HMAC-SHA1-96 or an RFC 4868 HMAC-SHA2 (256-128, 384-192, 512-256). */
class EspDirection(
    val spi: Long,
    private val encryptionKey: ByteArray,
    private val authenticationKey: ByteArray,
    encryption: String,
    integrity: String,
    private val random: RandomSource = Rng::bytes,
) {
    private val macAlgorithm: String
    private val icvLength: Int
    private val lock = Any()
    var sequence = 0L
    private val replay = ReplayWindow()

    init {
        val expectedKeyLength = when (encryption) {
            "aes-cbc-128" -> 16
            "aes-cbc-256" -> 32
            else -> throw EspException("unsupported encryption suite \"$encryption\"")
        }
        if (encryptionKey.size != expectedKeyLength) throw EspException("AES key has length ${encryptionKey.size}, want $expectedKeyLength")
        val suite = IkeAlgorithms.integrityByName(integrity) ?: throw EspException("unsupported integrity suite \"$integrity\"")
        macAlgorithm = suite.jceName
        icvLength = suite.checksumLength
        if (authenticationKey.size != suite.keyLength) {
            throw EspException("authentication key has length ${authenticationKey.size}, want ${suite.keyLength}")
        }
    }

    fun seal(innerPacket: ByteArray, nextHeader: Int): ByteArray = synchronized(lock) {
        if (sequence == 0xffffffffL) throw EspException("esp: sequence number exhausted; rekey is required")
        sequence++
        val blockSize = 16
        val paddingLength = (blockSize - ((innerPacket.size + 2) % blockSize)) % blockSize
        val plaintext = ByteArray(innerPacket.size + paddingLength + 2)
        System.arraycopy(innerPacket, 0, plaintext, 0, innerPacket.size)
        for (index in 0 until paddingLength) plaintext[innerPacket.size + index] = (index + 1).toByte()
        plaintext[plaintext.size - 2] = paddingLength.toByte()
        plaintext[plaintext.size - 1] = nextHeader.toByte()
        val authenticatedLength = ESP_HEADER_LENGTH + blockSize + plaintext.size
        val packet = ByteArray(authenticatedLength + icvLength)
        packet.putU32(0, spi)
        packet.putU32(4, sequence)
        val iv = random(blockSize)
        System.arraycopy(iv, 0, packet, ESP_HEADER_LENGTH, blockSize)
        val ciphertext = IkeCrypto.aesCbc(true, encryptionKey, iv, plaintext)
        System.arraycopy(ciphertext, 0, packet, ESP_HEADER_LENGTH + blockSize, ciphertext.size)
        val icv = authenticationCode(packet, authenticatedLength)
        System.arraycopy(icv, 0, packet, authenticatedLength, icvLength)
        packet
    }

    /** Verifies and decrypts; `validate` may reject the inner packet before the replay window advances. */
    fun open(packet: ByteArray, validate: ((ByteArray, Int) -> Unit)? = null): ByteArray = synchronized(lock) {
        val blockSize = 16
        val minimumLength = ESP_HEADER_LENGTH + blockSize + blockSize + icvLength
        if (packet.size < minimumLength) throw EspException("esp: packet is truncated")
        if (packet.u32(0) != spi) throw EspException("esp: packet has an unexpected SPI")
        val sequence = packet.u32(4)
        if (sequence == 0L || !replay.wouldAccept(sequence)) throw EspReplayException()
        val authenticatedLength = packet.size - icvLength
        val ciphertextLength = authenticatedLength - ESP_HEADER_LENGTH - blockSize
        if (ciphertextLength <= 0 || ciphertextLength % blockSize != 0) throw EspException("esp: ciphertext is not block aligned")
        val expected = authenticationCode(packet, authenticatedLength)
        if (!constantTimeEquals(expected, packet.copyOfRange(authenticatedLength, packet.size))) throw EspAuthenticationException()
        val iv = packet.copyOfRange(ESP_HEADER_LENGTH, ESP_HEADER_LENGTH + blockSize)
        val plaintext = IkeCrypto.aesCbc(false, encryptionKey, iv, packet.copyOfRange(ESP_HEADER_LENGTH + blockSize, authenticatedLength))
        if (plaintext.size < 2) throw EspException("esp: plaintext is truncated")
        val paddingLength = plaintext.u8(plaintext.size - 2)
        if (paddingLength > plaintext.size - 2) throw EspException("esp: padding length is invalid")
        val paddingStart = plaintext.size - 2 - paddingLength
        for (index in 0 until paddingLength) {
            if (plaintext[paddingStart + index] != (index + 1).toByte()) throw EspException("esp: padding bytes are invalid")
        }
        val nextHeader = plaintext.u8(plaintext.size - 1)
        if (nextHeader != 4 && nextHeader != 41) throw EspException("esp: unsupported next-header value $nextHeader")
        val innerPacket = plaintext.copyOf(paddingStart)
        validate?.invoke(innerPacket, nextHeader)
        replay.commit(sequence)
        innerPacket
    }

    private fun authenticationCode(packet: ByteArray, length: Int): ByteArray =
        IkeCrypto.hmac(macAlgorithm, authenticationKey, packet.copyOf(length)).copyOf(icvLength)
}

/** Tunnel-mode ESP for complete inner IPv4/IPv6 packets, with the negotiated traffic selectors enforced. */
class EspTunnel(config: ChildSaConfig, random: RandomSource = Rng::bytes) {
    val outbound: EspDirection
    val inbound: EspDirection
    private val initiatorSelectors: List<TrafficSelector> = config.initiatorSelectors
    private val responderSelectors: List<TrafficSelector> = config.responderSelectors

    init {
        if (config.inboundSpi == 0L || config.outboundSpi == 0L) throw EspException("esp: SPIs must be nonzero")
        if (config.initiatorSelectors.isEmpty() || config.responderSelectors.isEmpty()) throw EspException("esp: traffic selectors are required")
        outbound = EspDirection(config.outboundSpi, config.outboundEncKey, config.outboundAuthKey, config.encryption, config.integrity, random)
        inbound = EspDirection(config.inboundSpi, config.inboundEncKey, config.inboundAuthKey, config.encryption, config.integrity, random)
    }

    fun seal(innerPacket: ByteArray): ByteArray {
        val metadata = try {
            parseInnerPacket(innerPacket)
        } catch (error: EspException) {
            throw EspPolicyDropException(error.message ?: "unparsable inner packet")
        }
        if (!packetAllowed(metadata, initiatorSelectors, responderSelectors)) {
            throw EspPolicyDropException("outbound packet is outside negotiated traffic selectors")
        }
        return outbound.seal(innerPacket, metadata.nextHeader)
    }

    fun open(packet: ByteArray): ByteArray = inbound.open(packet) { innerPacket, nextHeader ->
        val metadata = parseInnerPacket(innerPacket)
        if (metadata.nextHeader != nextHeader) throw EspException("esp: trailer does not match the inner IP version")
        if (!packetAllowed(metadata, responderSelectors, initiatorSelectors)) {
            throw EspPolicyDropException("inbound packet is outside negotiated traffic selectors (protocol ${metadata.protocol}, ${IpPacket.addressString(metadata.source)}:${metadata.sourcePort} -> ${IpPacket.addressString(metadata.destination)}:${metadata.destinationPort})")
        }
    }

    companion object {
        fun parseInnerPacket(packet: ByteArray): InnerPacketMetadata {
            if (packet.isEmpty()) throw EspException("esp: inner IP packet is empty")
            return when (packet.u8(0) ushr 4) {
                4 -> parseInnerIpv4(packet)
                6 -> parseInnerIpv6(packet)
                else -> throw EspException("esp: inner packet is not IPv4 or IPv6")
            }
        }

        private fun parseInnerIpv4(packet: ByteArray): InnerPacketMetadata {
            if (packet.size < 20) throw EspException("esp: inner IPv4 packet is truncated")
            val headerLength = (packet.u8(0) and 0x0f) * 4
            if (headerLength < 20 || headerLength > packet.size) throw EspException("esp: inner IPv4 header length is invalid")
            val totalLength = packet.u16(2)
            if (totalLength != packet.size || totalLength < headerLength) throw EspException("esp: inner IPv4 total length is invalid")
            val protocol = packet.u8(9)
            val fragmentOffset = packet.u16(6) and 0x1fff
            var sourcePort = 0
            var destinationPort = 0
            if (fragmentOffset == 0 && (protocol == 6 || protocol == 17) && packet.size >= headerLength + 4) {
                sourcePort = packet.u16(headerLength)
                destinationPort = packet.u16(headerLength + 2)
            }
            return InnerPacketMetadata(packet.copyOfRange(12, 16), packet.copyOfRange(16, 20), protocol, sourcePort, destinationPort, 4)
        }

        private fun parseInnerIpv6(fullPacket: ByteArray): InnerPacketMetadata {
            if (fullPacket.size < 40) throw EspException("esp: inner IPv6 packet is truncated")
            val declaredLength = fullPacket.u16(4) + 40
            if (declaredLength > fullPacket.size) throw EspException("esp: inner IPv6 payload length is invalid")
            val packet = fullPacket.copyOf(declaredLength)
            var protocol = packet.u8(6)
            var offset = 40
            var firstFragment = true
            while (true) {
                when (protocol) {
                    0, 43, 60 -> {
                        if (offset + 2 > packet.size) throw EspException("esp: inner IPv6 extension header is truncated")
                        val length = (packet.u8(offset + 1) + 1) * 8
                        if (offset + length > packet.size) throw EspException("esp: inner IPv6 extension header length is invalid")
                        protocol = packet.u8(offset)
                        offset += length
                    }
                    44 -> {
                        if (offset + 8 > packet.size) throw EspException("esp: inner IPv6 fragment header is truncated")
                        firstFragment = packet.u16(offset + 2) and 0xfff8 == 0
                        protocol = packet.u8(offset)
                        offset += 8
                    }
                    51 -> {
                        if (offset + 2 > packet.size) throw EspException("esp: inner IPv6 AH header is truncated")
                        val length = (packet.u8(offset + 1) + 2) * 4
                        if (offset + length > packet.size) throw EspException("esp: inner IPv6 AH header length is invalid")
                        protocol = packet.u8(offset)
                        offset += length
                    }
                    else -> {
                        var sourcePort = 0
                        var destinationPort = 0
                        if (firstFragment && (protocol == 6 || protocol == 17) && packet.size >= offset + 4) {
                            sourcePort = packet.u16(offset)
                            destinationPort = packet.u16(offset + 2)
                        }
                        return InnerPacketMetadata(packet.copyOfRange(8, 24), packet.copyOfRange(24, 40), protocol, sourcePort, destinationPort, 41)
                    }
                }
            }
        }

        fun packetAllowed(metadata: InnerPacketMetadata, sourceSelectors: List<TrafficSelector>, destinationSelectors: List<TrafficSelector>): Boolean =
            endpointAllowed(metadata.source, metadata.protocol, metadata.sourcePort, sourceSelectors) &&
                endpointAllowed(metadata.destination, metadata.protocol, metadata.destinationPort, destinationSelectors)

        private fun endpointAllowed(ip: ByteArray, protocol: Int, port: Int, selectors: List<TrafficSelector>): Boolean =
            selectors.any { selector ->
                (selector.ipProtocol == 0 || selector.ipProtocol == protocol) &&
                    port >= selector.startPort && port <= selector.endPort &&
                    IkeChild.ipWithinRange(ip, selector.startIp, selector.endIp)
            }
    }
}
