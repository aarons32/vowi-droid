package com.vowi.vowidroid.core.ike

import com.vowi.vowidroid.core.util.ByteWriter
import com.vowi.vowidroid.core.util.concat
import com.vowi.vowidroid.core.util.putU16
import com.vowi.vowidroid.core.util.u16
import com.vowi.vowidroid.core.util.u8
import java.net.InetAddress

/** The ESP suite the responder selected for the CHILD_SA. */
data class EspSuite(val encryptionId: Int, val encryptionBits: Int, val integrityId: Int, val esn: Int) {
    val encryptionKeyLength: Int
        get() {
            if (encryptionId != Ike.ENCRYPTION_AES_CBC || (encryptionBits != 128 && encryptionBits != 256)) {
                throw IkeUnsupportedSuiteException("ESP encryption id=$encryptionId bits=$encryptionBits")
            }
            return encryptionBits / 8
        }

    val integrityKeyLength: Int
        get() = IkeAlgorithms.integrities.firstOrNull { it.id == integrityId }?.keyLength
            ?: throw IkeUnsupportedSuiteException("ESP integrity id=$integrityId")

    val encryptionName: String get() = IkeAlgorithms.encryptionName(encryptionBits)

    val integrityName: String get() = IkeAlgorithms.integrityName(integrityId)
}

/** One IKEv2 traffic selector; addresses are raw 4 or 16 byte values. */
class TrafficSelector(val ipProtocol: Int, val startPort: Int, val endPort: Int, val startIp: ByteArray, val endIp: ByteArray) {
    override fun toString(): String =
        "${InetAddress.getByAddress(startIp).hostAddress}-${InetAddress.getByAddress(endIp).hostAddress} proto=$ipProtocol ports=$startPort-$endPort"
}

/** The CP(CFG_REPLY) the ePDG returned. Addresses are raw bytes. */
class NetworkConfiguration(
    val localIpv4: ByteArray?,
    val localIpv6: ByteArray?,
    val ipv6Prefix: Int,
    val dns: List<ByteArray>,
    val pcscf: List<ByteArray>,
)

/** Everything a dataplane needs to run one CHILD_SA. Keys must be discarded with it. */
class ChildSaConfig(
    val innerLocalIpv4: ByteArray?,
    val innerLocalIpv6: ByteArray?,
    val innerIpv6Prefix: Int,
    val pcscf: List<ByteArray>,
    val dns: List<ByteArray>,
    val inboundSpi: Long,
    val outboundSpi: Long,
    val encryption: String,
    val integrity: String,
    val inboundEncKey: ByteArray,
    val inboundAuthKey: ByteArray,
    val outboundEncKey: ByteArray,
    val outboundAuthKey: ByteArray,
    val initiatorSelectors: List<TrafficSelector>,
    val responderSelectors: List<TrafficSelector>,
)

class ChildSaKeys(val outboundEncryption: ByteArray, val outboundIntegrity: ByteArray, val inboundEncryption: ByteArray, val inboundIntegrity: ByteArray)

object IkeChild {
    const val CONFIG_REQUEST = 1
    const val CONFIG_REPLY = 2

    const val CONFIG_INTERNAL_IP4_ADDRESS = 1
    const val CONFIG_INTERNAL_IP4_DNS = 3
    const val CONFIG_APPLICATION_VERSION = 7
    const val CONFIG_INTERNAL_IP6_ADDRESS = 8
    const val CONFIG_INTERNAL_IP6_DNS = 10
    const val CONFIG_P_CSCF_IP4_ADDRESS = 20
    const val CONFIG_P_CSCF_IP6_ADDRESS = 21

    // Pre-standard P-CSCF attributes in the private range, still used by many ePDGs (AT&T and T-Mobile US
    // among them, per their phone settings); Apple's default settings ask for all of these too.
    const val CONFIG_P_CSCF_IP4_ADDRESS_PRIVATE = 16384
    const val CONFIG_P_CSCF_IP6_ADDRESS_PRIVATE = 16386
    const val CONFIG_P_CSCF_IP4_ADDRESS_PRIVATE_ALT = 16389
    const val CONFIG_P_CSCF_IP6_ADDRESS_PRIVATE_ALT = 16390

    val PCSCF_IP4_ATTRIBUTES = intArrayOf(CONFIG_P_CSCF_IP4_ADDRESS, CONFIG_P_CSCF_IP4_ADDRESS_PRIVATE, CONFIG_P_CSCF_IP4_ADDRESS_PRIVATE_ALT)
    val PCSCF_IP6_ATTRIBUTES = intArrayOf(CONFIG_P_CSCF_IP6_ADDRESS, CONFIG_P_CSCF_IP6_ADDRESS_PRIVATE, CONFIG_P_CSCF_IP6_ADDRESS_PRIVATE_ALT)

    const val TS_IPV4_ADDR_RANGE = 7
    const val TS_IPV6_ADDR_RANGE = 8

    fun parseEspSuite(item: Proposal): EspSuite {
        if (item.protocol != Ike.PROTOCOL_ESP || item.spi.size != 4) throw IkeUnsupportedSuiteException("invalid ESP proposal protocol or SPI")
        var encryptionId = 0
        var encryptionBits = 0
        var integrityId = 0
        var esn = 0
        val seen = HashSet<Int>()
        for (candidate in item.transforms) {
            if (!seen.add(candidate.type)) throw IkeUnsupportedSuiteException("duplicate ESP transform type ${candidate.type}")
            when (candidate.type) {
                Ike.TRANSFORM_ENCRYPTION -> {
                    encryptionId = candidate.id
                    encryptionBits = candidate.keyLength
                }
                Ike.TRANSFORM_INTEGRITY -> integrityId = candidate.id
                Ike.TRANSFORM_ESN -> esn = candidate.id
                else -> throw IkeUnsupportedSuiteException("unsupported ESP transform type ${candidate.type}")
            }
        }
        val suite = EspSuite(encryptionId, encryptionBits, integrityId, esn)
        suite.encryptionKeyLength
        suite.integrityKeyLength
        if (esn != 0) throw IkeUnsupportedSuiteException("ESP extended sequence numbers are unsupported")
        return suite
    }

    private fun anySelector(ipv6: Boolean): ByteArray {
        return if (ipv6) {
            ByteArray(40).also {
                it[0] = TS_IPV6_ADDR_RANGE.toByte()
                it.putU16(2, 40)
                it.putU16(6, 65535)
                for (index in 24 until 40) it[index] = 0xff.toByte()
            }
        } else {
            ByteArray(16).also {
                it[0] = TS_IPV4_ADDR_RANGE.toByte()
                it.putU16(2, 16)
                it.putU16(6, 65535)
                for (index in 12 until 16) it[index] = 0xff.toByte()
            }
        }
    }

    /** TSi/TSr covering all IPv4 and IPv6 traffic, as the ePDG narrows them. */
    fun dualStackTrafficSelectors(type: Int): Payload =
        Payload(type, concat(byteArrayOf(2, 0, 0, 0), anySelector(false), anySelector(true)))

    fun marshalTrafficSelectors(type: Int, selectors: List<TrafficSelector>): Payload {
        val out = ByteWriter().byte(selectors.size).byte(0).u16(0)
        for (selector in selectors) {
            val ipv6 = selector.startIp.size == 16
            out.byte(if (ipv6) TS_IPV6_ADDR_RANGE else TS_IPV4_ADDR_RANGE)
            out.byte(selector.ipProtocol)
            out.u16(if (ipv6) 40 else 16)
            out.u16(selector.startPort)
            out.u16(selector.endPort)
            out.bytes(selector.startIp)
            out.bytes(selector.endIp)
        }
        return Payload(type, out.toByteArray())
    }

    fun parseTrafficSelectors(item: Payload): List<TrafficSelector> {
        if (item.body.size < 4) throw IkeException("ike: traffic selector payload is truncated")
        val count = item.body.u8(0)
        var offset = 4
        val result = ArrayList<TrafficSelector>(count)
        repeat(count) {
            if (offset + 8 > item.body.size) throw IkeException("ike: traffic selector is truncated")
            val length = item.body.u16(offset + 2)
            if (length < 16 || offset + length > item.body.size) throw IkeException("ike: traffic selector has an invalid length")
            val protocol = item.body.u8(offset + 1)
            val startPort = item.body.u16(offset + 4)
            val endPort = item.body.u16(offset + 6)
            val selector = when (item.body.u8(offset)) {
                TS_IPV4_ADDR_RANGE -> {
                    if (length != 16) throw IkeException("ike: IPv4 traffic selector has an invalid length")
                    TrafficSelector(protocol, startPort, endPort, item.body.copyOfRange(offset + 8, offset + 12), item.body.copyOfRange(offset + 12, offset + 16))
                }
                TS_IPV6_ADDR_RANGE -> {
                    if (length != 40) throw IkeException("ike: IPv6 traffic selector has an invalid length")
                    TrafficSelector(protocol, startPort, endPort, item.body.copyOfRange(offset + 8, offset + 24), item.body.copyOfRange(offset + 24, offset + 40))
                }
                else -> throw IkeException("ike: unsupported traffic selector type ${item.body.u8(offset)}")
            }
            result.add(selector)
            offset += length
        }
        if (offset != item.body.size) throw IkeException("ike: traffic selector payload has trailing bytes")
        return result
    }

    fun configurationRequest(): Payload {
        val attributes = intArrayOf(
            CONFIG_INTERNAL_IP4_ADDRESS,
            CONFIG_INTERNAL_IP6_ADDRESS,
            CONFIG_INTERNAL_IP4_DNS,
            CONFIG_INTERNAL_IP6_DNS,
            CONFIG_P_CSCF_IP4_ADDRESS,
            CONFIG_P_CSCF_IP6_ADDRESS,
            CONFIG_P_CSCF_IP4_ADDRESS_PRIVATE,
            CONFIG_P_CSCF_IP6_ADDRESS_PRIVATE,
            CONFIG_P_CSCF_IP4_ADDRESS_PRIVATE_ALT,
            CONFIG_P_CSCF_IP6_ADDRESS_PRIVATE_ALT,
            // Android's IKE library always appends APPLICATION_VERSION to the
            // initial configuration request, even when the value is empty.
            CONFIG_APPLICATION_VERSION,
        )
        val out = ByteWriter().byte(CONFIG_REQUEST).byte(0).u16(0)
        for (attribute in attributes) out.u16(attribute).u16(0)
        return Payload(Ike.PAYLOAD_CP, out.toByteArray())
    }

    fun parseConfiguration(item: Payload): NetworkConfiguration {
        if (item.type != Ike.PAYLOAD_CP || item.body.size < 4 || item.body.u8(0) != CONFIG_REPLY) {
            throw IkeException("ike: missing or invalid configuration reply")
        }
        var localIpv4: ByteArray? = null
        var localIpv6: ByteArray? = null
        var ipv6Prefix = 0
        val dns = ArrayList<ByteArray>()
        val pcscf = ArrayList<ByteArray>()
        var offset = 4
        while (offset < item.body.size) {
            if (offset + 4 > item.body.size) throw IkeException("ike: truncated configuration attribute")
            val kind = item.body.u16(offset) and 0x7fff
            val length = item.body.u16(offset + 2)
            offset += 4
            if (offset + length > item.body.size) throw IkeException("ike: invalid configuration attribute length")
            val value = item.body.copyOfRange(offset, offset + length)
            when (kind) {
                CONFIG_INTERNAL_IP4_ADDRESS -> if (length == 4) localIpv4 = value
                CONFIG_INTERNAL_IP6_ADDRESS -> {
                    if (length != 17) throw IkeException("ike: INTERNAL_IP6_ADDRESS must contain 16 address bytes and one prefix byte")
                    if (value.u8(16) > 128) throw IkeException("ike: INTERNAL_IP6_ADDRESS prefix exceeds 128")
                    localIpv6 = value.copyOf(16)
                    ipv6Prefix = value.u8(16)
                }
                CONFIG_INTERNAL_IP4_DNS -> if (length == 4) dns.add(value)
                CONFIG_INTERNAL_IP6_DNS -> if (length == 16) dns.add(value)
                in PCSCF_IP4_ATTRIBUTES -> if (length == 4) pcscf.add(value)
                in PCSCF_IP6_ATTRIBUTES -> if (length == 16) pcscf.add(value)
            }
            offset += length
        }
        return NetworkConfiguration(localIpv4, localIpv6, ipv6Prefix, dns, pcscf)
    }

    /** RFC 7296 2.17: KEYMAT = prf+(SK_d, Ni | Nr) (no PFS), initiator-to-responder first. */
    fun deriveChildSaKeys(ikeSuite: IkeSuite, childSuite: EspSuite, skd: ByteArray, initiatorNonce: ByteArray, responderNonce: ByteArray, sharedSecret: ByteArray? = null): ChildSaKeys {
        val encryptionLength = childSuite.encryptionKeyLength
        val integrityLength = childSuite.integrityKeyLength
        val seed = if (sharedSecret != null) concat(sharedSecret, initiatorNonce, responderNonce) else concat(initiatorNonce, responderNonce)
        val stream = IkeCrypto.prfPlus(ikeSuite, skd, seed, 2 * (encryptionLength + integrityLength))
        var offset = 0
        fun take(length: Int): ByteArray {
            val value = stream.copyOfRange(offset, offset + length)
            offset += length
            return value
        }
        return ChildSaKeys(take(encryptionLength), take(integrityLength), take(encryptionLength), take(integrityLength))
    }

    /** Keeps only P-CSCF addresses in a family we were assigned an address for. */
    fun pcscfForAssignedFamilies(addresses: List<ByteArray>, hasIpv4: Boolean, hasIpv6: Boolean): List<ByteArray> {
        val result = ArrayList<ByteArray>()
        for (address in addresses) {
            if (result.any { it.contentEquals(address) }) continue
            if ((address.size == 4 && hasIpv4) || (address.size == 16 && hasIpv6)) result.add(address)
        }
        return result
    }

    fun ipAllowedBySelectors(ip: ByteArray, selectors: List<TrafficSelector>): Boolean =
        selectors.any { ipWithinRange(ip, it.startIp, it.endIp) }

    fun ipWithinRange(ip: ByteArray, start: ByteArray, end: ByteArray): Boolean {
        if (ip.size != start.size || ip.size != end.size) return false
        return compareUnsigned(ip, start) >= 0 && compareUnsigned(ip, end) <= 0
    }

    fun compareUnsigned(first: ByteArray, second: ByteArray): Int {
        for (index in first.indices) {
            val difference = (first[index].toInt() and 0xff) - (second[index].toInt() and 0xff)
            if (difference != 0) return difference
        }
        return 0
    }
}
