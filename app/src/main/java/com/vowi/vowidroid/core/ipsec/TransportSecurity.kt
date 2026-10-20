package com.vowi.vowidroid.core.ipsec

import com.vowi.vowidroid.core.esp.ESP_HEADER_LENGTH
import com.vowi.vowidroid.core.esp.ReplayWindow
import com.vowi.vowidroid.core.ike.IkeCrypto
import com.vowi.vowidroid.core.net.IpPacket
import com.vowi.vowidroid.core.util.RandomSource
import com.vowi.vowidroid.core.util.Rng
import com.vowi.vowidroid.core.util.constantTimeEquals
import com.vowi.vowidroid.core.util.putU32
import com.vowi.vowidroid.core.util.u32
import com.vowi.vowidroid.core.util.u8
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The negotiated ipsec-3gpp security association set (3GPP TS 33.203): two
 * outbound SAs keyed by the P-CSCF's SPIs, two inbound SAs keyed by ours.
 * Keys must be discarded with the set.
 */
class IpsecSaConfig(
    val localIp: ByteArray,
    val remoteIp: ByteArray,
    /** hmac-md5-96 or hmac-sha-1-96. */
    val integrityAlgorithm: String,
    /** null, des-ede3-cbc or aes-cbc. */
    val encryptionAlgorithm: String,
    val ueClientSpi: Long,
    val ueServerSpi: Long,
    val pcscfClientSpi: Long,
    val pcscfServerSpi: Long,
    val ueClientPort: Int,
    val ueServerPort: Int,
    val pcscfClientPort: Int,
    val pcscfServerPort: Int,
    val encryptionKey: ByteArray,
    val integrityKey: ByteArray,
)

/**
 * Transport-mode ESP for SIP between the UE and the P-CSCF, applied to inner
 * packets of the user-space tunnel. Packets on unprotected flows pass through
 * unchanged; plaintext on a protected inbound flow is dropped, as an XFRM
 * "level required" policy would.
 */
class TransportSecurity(private val random: RandomSource = Rng::bytes) {
    private val lock = Any()
    private val sets = LinkedHashSet<SaSet>()

    internal class SaSet(
        val local: ByteArray,
        val remote: ByteArray,
        val ueClientPort: Int,
        val ueServerPort: Int,
        val pcscfClientPort: Int,
        val pcscfServerPort: Int,
        val toServer: Direction,
        val toClient: Direction,
        val fromServer: Direction,
        val fromClient: Direction,
    )

    /** Removes the SA set; its flows then pass in plaintext, like a deleted policy. */
    inner class Handle internal constructor(private val set: SaSet) : AutoCloseable {
        override fun close() {
            synchronized(lock) { sets.remove(set) }
        }
    }

    fun install(config: IpsecSaConfig): Handle {
        if (config.localIp.size != config.remoteIp.size || (config.localIp.size != 4 && config.localIp.size != 16)) {
            throw IllegalArgumentException("ipsec-3gpp endpoints must be addresses of one IP family")
        }
        for (port in listOf(config.ueClientPort, config.ueServerPort, config.pcscfClientPort, config.pcscfServerPort)) {
            require(port in 1..65535) { "ipsec-3gpp ports must be between 1 and 65535" }
        }
        val spis = listOf(config.pcscfServerSpi, config.pcscfClientSpi, config.ueClientSpi, config.ueServerSpi)
        require(spis.all { it >= 256 }) { "ipsec-3gpp SPIs must be at least 256" }
        require(config.ueClientSpi != config.ueServerSpi) { "ipsec-3gpp inbound SPIs must differ" }
        val directions = spis.map { Direction(it, config, random) }
        val set = SaSet(
            local = config.localIp.copyOf(),
            remote = config.remoteIp.copyOf(),
            ueClientPort = config.ueClientPort,
            ueServerPort = config.ueServerPort,
            pcscfClientPort = config.pcscfClientPort,
            pcscfServerPort = config.pcscfServerPort,
            toServer = directions[0],
            toClient = directions[1],
            fromServer = directions[2],
            fromClient = directions[3],
        )
        synchronized(lock) {
            for (existing in sets) {
                for (spi in listOf(existing.fromServer.spi, existing.fromClient.spi)) {
                    if (spi == set.fromServer.spi || spi == set.fromClient.spi) {
                        throw IllegalStateException("ipsec-3gpp inbound SPI ${"%08x".format(spi)} is already installed")
                    }
                }
            }
            sets.add(set)
        }
        return Handle(set)
    }

    /** Applies outbound transport ESP to packets on a protected flow; returns other packets unchanged. */
    fun protect(packet: ByteArray): ByteArray {
        val parsed = IpPacket.parseTransport(packet) ?: return packet
        if (parsed.protocol != IpPacket.TCP && parsed.protocol != IpPacket.UDP) return packet
        val set = synchronized(lock) {
            sets.firstOrNull { it.local.contentEquals(parsed.source) && it.remote.contentEquals(parsed.destination) &&
                ((parsed.sourcePort == it.ueClientPort && parsed.destinationPort == it.pcscfServerPort) ||
                    (parsed.sourcePort == it.ueServerPort && parsed.destinationPort == it.pcscfClientPort && parsed.protocol == IpPacket.TCP))
            }
        } ?: return packet
        return if (parsed.sourcePort == set.ueClientPort && parsed.destinationPort == set.pcscfServerPort) {
            set.toServer.seal(packet, parsed)
        } else {
            set.toClient.seal(packet, parsed)
        }
    }

    /** Removes inbound transport ESP; returns null with a reason when the packet must be dropped. */
    fun unprotect(packet: ByteArray): Pair<ByteArray?, String> {
        val parsed = IpPacket.parseTransport(packet) ?: return packet to ""
        val candidates = synchronized(lock) { sets.toList() }
        for (set in candidates) {
            if (!set.remote.contentEquals(parsed.source) || !set.local.contentEquals(parsed.destination)) continue
            if (parsed.protocol == IpPacket.ESP) {
                if (packet.size < parsed.headerLength + ESP_HEADER_LENGTH) return null to "truncated ipsec-3gpp ESP"
                val spi = packet.u32(parsed.headerLength)
                val (direction, fromServer) = when (spi) {
                    set.fromServer.spi -> set.fromServer to true
                    set.fromClient.spi -> set.fromClient to false
                    else -> continue
                }
                val cleartext = direction.open(packet, parsed) ?: return null to "ipsec-3gpp ESP failed authentication, replay or padding checks"
                val inner = IpPacket.parseTransport(cleartext)
                if (inner == null || (inner.protocol != IpPacket.TCP && inner.protocol != IpPacket.UDP)) {
                    return null to "ipsec-3gpp ESP carried no TCP or UDP segment"
                }
                if (fromServer && (inner.sourcePort != set.pcscfServerPort || inner.destinationPort != set.ueClientPort)) {
                    return null to "ipsec-3gpp P-CSCF-server SA carried another flow"
                }
                if (!fromServer && inner.destinationPort != set.ueServerPort) {
                    return null to "ipsec-3gpp P-CSCF-client SA carried another flow"
                }
                return cleartext to ""
            }
            if (parsed.protocol == IpPacket.TCP && parsed.sourcePort == set.pcscfServerPort && parsed.destinationPort == set.ueClientPort) {
                return null to "plaintext TCP on the protected P-CSCF-server to UE-client flow"
            }
            if ((parsed.protocol == IpPacket.TCP || parsed.protocol == IpPacket.UDP) && parsed.destinationPort == set.ueServerPort) {
                return null to "plaintext on the protected UE-server port"
            }
        }
        if (parsed.protocol == IpPacket.ESP) return null to "ipsec-3gpp ESP matches no installed SA"
        return packet to ""
    }

    internal class Direction(val spi: Long, config: IpsecSaConfig, private val random: RandomSource) {
        private val cipherName: String?
        private val keySpec: SecretKeySpec?
        private val ivLength: Int
        private val alignment: Int
        private val macAlgorithm: String
        private val authKey: ByteArray = config.integrityKey.copyOf()
        private val lock = Any()
        private var sequence = 0L
        private val replay = ReplayWindow()

        init {
            when (config.encryptionAlgorithm.trim().lowercase()) {
                "null" -> {
                    require(config.encryptionKey.isEmpty()) { "ipsec-3gpp null encryption must not carry a key" }
                    cipherName = null
                    keySpec = null
                    ivLength = 0
                    alignment = 4
                }
                "aes-cbc", "" -> {
                    require(config.encryptionKey.size == 16) { "ipsec-3gpp AES-CBC key has length ${config.encryptionKey.size}, want 16" }
                    cipherName = "AES/CBC/NoPadding"
                    keySpec = SecretKeySpec(config.encryptionKey, "AES")
                    ivLength = 16
                    alignment = 16
                }
                "des-ede3-cbc" -> {
                    require(config.encryptionKey.size == 24) { "ipsec-3gpp 3DES key has length ${config.encryptionKey.size}, want 24" }
                    cipherName = "DESede/CBC/NoPadding"
                    keySpec = SecretKeySpec(config.encryptionKey, "DESede")
                    ivLength = 8
                    alignment = 8
                }
                else -> throw IllegalArgumentException("unsupported ipsec-3gpp encryption ${config.encryptionAlgorithm}")
            }
            macAlgorithm = when (config.integrityAlgorithm.trim().lowercase()) {
                "hmac-md5-96" -> {
                    require(authKey.size == 16) { "ipsec-3gpp HMAC-MD5 key has length ${authKey.size}, want 16" }
                    "HmacMD5"
                }
                "hmac-sha-1-96", "" -> {
                    require(authKey.size == 20) { "ipsec-3gpp HMAC-SHA1 key has length ${authKey.size}, want 20" }
                    "HmacSHA1"
                }
                else -> throw IllegalArgumentException("unsupported ipsec-3gpp integrity ${config.integrityAlgorithm}")
            }
        }

        private fun crypt(encrypt: Boolean, iv: ByteArray, data: ByteArray): ByteArray {
            val cipher = Cipher.getInstance(cipherName!!)
            cipher.init(if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE, keySpec, IvParameterSpec(iv))
            return cipher.doFinal(data)
        }

        fun seal(packet: ByteArray, parsed: IpPacket.Transport): ByteArray = synchronized(lock) {
            if (sequence == 0xffffffffL) throw IllegalStateException("ipsec-3gpp sequence number exhausted")
            sequence++
            val segment = packet.copyOfRange(parsed.headerLength, packet.size)
            val paddingLength = (alignment - ((segment.size + 2) % alignment)) % alignment
            val plaintext = ByteArray(segment.size + paddingLength + 2)
            System.arraycopy(segment, 0, plaintext, 0, segment.size)
            for (index in 0 until paddingLength) plaintext[segment.size + index] = (index + 1).toByte()
            plaintext[plaintext.size - 2] = paddingLength.toByte()
            plaintext[plaintext.size - 1] = parsed.protocol.toByte()
            val authenticated = ESP_HEADER_LENGTH + ivLength + plaintext.size
            val esp = ByteArray(authenticated + ICV_LENGTH)
            esp.putU32(0, spi)
            esp.putU32(4, sequence)
            if (cipherName != null) {
                val iv = random(ivLength)
                System.arraycopy(iv, 0, esp, ESP_HEADER_LENGTH, ivLength)
                val ciphertext = crypt(true, iv, plaintext)
                System.arraycopy(ciphertext, 0, esp, ESP_HEADER_LENGTH + ivLength, ciphertext.size)
            } else {
                System.arraycopy(plaintext, 0, esp, ESP_HEADER_LENGTH, plaintext.size)
            }
            System.arraycopy(icv(esp, authenticated), 0, esp, authenticated, ICV_LENGTH)
            IpPacket.rebuild(packet.copyOf(parsed.headerLength), IpPacket.ESP, esp)
        }

        fun open(packet: ByteArray, parsed: IpPacket.Transport): ByteArray? = synchronized(lock) {
            val esp = packet.copyOfRange(parsed.headerLength, packet.size)
            if (esp.size < ESP_HEADER_LENGTH + ivLength + alignment + ICV_LENGTH) return null
            val sequence = esp.u32(4)
            if (!replay.wouldAccept(sequence)) return null
            val authenticated = esp.size - ICV_LENGTH
            if (!constantTimeEquals(icv(esp, authenticated), esp.copyOfRange(authenticated, esp.size))) return null
            val body = esp.copyOfRange(ESP_HEADER_LENGTH + ivLength, authenticated)
            if (body.isEmpty() || body.size % alignment != 0) return null
            val plaintext = if (cipherName != null) {
                crypt(false, esp.copyOfRange(ESP_HEADER_LENGTH, ESP_HEADER_LENGTH + ivLength), body)
            } else {
                body
            }
            if (plaintext.size < 2) return null
            val paddingLength = plaintext.u8(plaintext.size - 2)
            if (paddingLength > plaintext.size - 2) return null
            val nextHeader = plaintext.u8(plaintext.size - 1)
            val segment = plaintext.copyOf(plaintext.size - 2 - paddingLength)
            val rebuilt = try {
                IpPacket.rebuild(packet.copyOf(parsed.headerLength), nextHeader, segment)
            } catch (_: IllegalArgumentException) {
                return null
            }
            replay.commit(sequence)
            rebuilt
        }

        private fun icv(data: ByteArray, length: Int): ByteArray =
            IkeCrypto.hmac(macAlgorithm, authKey, data.copyOf(length)).copyOf(ICV_LENGTH)
    }

    companion object {
        const val ICV_LENGTH = 12
    }
}
