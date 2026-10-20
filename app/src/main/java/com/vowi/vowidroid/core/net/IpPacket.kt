package com.vowi.vowidroid.core.net

import com.vowi.vowidroid.core.util.putU16
import com.vowi.vowidroid.core.util.putU32
import com.vowi.vowidroid.core.util.u16
import com.vowi.vowidroid.core.util.u8
import java.net.InetAddress

/** IPv4/IPv6 packet helpers for the user-space stack. Addresses are raw 4 or 16 byte arrays. */
object IpPacket {
    const val ICMP = 1
    const val TCP = 6
    const val UDP = 17
    const val ESP = 50
    const val ICMPV6 = 58
    const val IPV6_FRAGMENT = 44

    /** A packet whose transport header directly follows the IP header (no fragments, no IPv6 extensions). */
    class Transport(
        val headerLength: Int,
        val protocol: Int,
        val source: ByteArray,
        val destination: ByteArray,
        val sourcePort: Int,
        val destinationPort: Int,
    )

    fun version(packet: ByteArray): Int = if (packet.isEmpty()) 0 else packet.u8(0) ushr 4

    fun parseTransport(packet: ByteArray): Transport? {
        when (version(packet)) {
            4 -> {
                if (packet.size < 20) return null
                val headerLength = (packet.u8(0) and 0x0f) * 4
                if (headerLength < 20 || headerLength > packet.size || packet.u16(2) != packet.size) return null
                if (packet.u16(6) and 0x3fff != 0) return null
                val protocol = packet.u8(9)
                val (sourcePort, destinationPort) = ports(packet, headerLength, protocol)
                return Transport(headerLength, protocol, packet.copyOfRange(12, 16), packet.copyOfRange(16, 20), sourcePort, destinationPort)
            }
            6 -> {
                if (packet.size < 40 || packet.u16(4) + 40 != packet.size) return null
                val protocol = packet.u8(6)
                val (sourcePort, destinationPort) = ports(packet, 40, protocol)
                return Transport(40, protocol, packet.copyOfRange(8, 24), packet.copyOfRange(24, 40), sourcePort, destinationPort)
            }
            else -> return null
        }
    }

    private fun ports(packet: ByteArray, offset: Int, protocol: Int): Pair<Int, Int> =
        if ((protocol == TCP || protocol == UDP) && packet.size >= offset + 4) packet.u16(offset) to packet.u16(offset + 2) else 0 to 0

    /** Replaces the payload behind an unmodified IP header, fixing protocol, length and the IPv4 checksum. */
    fun rebuild(header: ByteArray, protocol: Int, payload: ByteArray): ByteArray {
        val packet = ByteArray(header.size + payload.size)
        System.arraycopy(header, 0, packet, 0, header.size)
        System.arraycopy(payload, 0, packet, header.size, payload.size)
        when (version(packet)) {
            4 -> {
                require(packet.size <= 65535) { "rebuilt IPv4 packet is too large" }
                packet[9] = protocol.toByte()
                packet.putU16(2, packet.size)
                packet[10] = 0
                packet[11] = 0
                packet.putU16(10, ipv4Checksum(packet, header.size))
            }
            6 -> {
                require(payload.size <= 65535) { "rebuilt IPv6 payload is too large" }
                packet[6] = protocol.toByte()
                packet.putU16(4, payload.size)
            }
            else -> throw IllegalArgumentException("rebuilt packet is not IPv4 or IPv6")
        }
        return packet
    }

    fun ipv4Checksum(header: ByteArray, length: Int): Int {
        var sum = 0L
        var index = 0
        while (index + 1 < length) {
            sum += header.u16(index)
            index += 2
        }
        while (sum ushr 16 != 0L) sum = (sum and 0xffff) + (sum ushr 16)
        return sum.inv().toInt() and 0xffff
    }

    /** The one's-complement checksum over a TCP/UDP/ICMPv6 segment and its pseudo header. */
    fun transportChecksum(source: ByteArray, destination: ByteArray, protocol: Int, segment: ByteArray, offset: Int = 0, length: Int = segment.size - offset): Int {
        var sum = 0L
        fun addWords(data: ByteArray, from: Int, count: Int) {
            var index = from
            val end = from + count
            while (index + 1 < end) {
                sum += ((data[index].toInt() and 0xff) shl 8) or (data[index + 1].toInt() and 0xff)
                index += 2
            }
            if (index < end) sum += (data[index].toInt() and 0xff) shl 8
        }
        addWords(source, 0, source.size)
        addWords(destination, 0, destination.size)
        if (source.size == 4) {
            sum += protocol
            sum += length
        } else {
            sum += (length.toLong() ushr 16) and 0xffff
            sum += length.toLong() and 0xffff
            sum += protocol
        }
        addWords(segment, offset, length)
        while (sum ushr 16 != 0L) sum = (sum and 0xffff) + (sum ushr 16)
        return sum.inv().toInt() and 0xffff
    }

    fun buildIpv4(source: ByteArray, destination: ByteArray, protocol: Int, payload: ByteArray, identification: Int, dontFragment: Boolean = true, tos: Int = 0): ByteArray {
        val packet = ByteArray(20 + payload.size)
        packet[0] = 0x45
        packet[1] = tos.toByte()
        packet.putU16(2, packet.size)
        packet.putU16(4, identification and 0xffff)
        packet.putU16(6, if (dontFragment) 0x4000 else 0)
        packet[8] = 64
        packet[9] = protocol.toByte()
        System.arraycopy(source, 0, packet, 12, 4)
        System.arraycopy(destination, 0, packet, 16, 4)
        packet.putU16(10, ipv4Checksum(packet, 20))
        System.arraycopy(payload, 0, packet, 20, payload.size)
        return packet
    }

    fun buildIpv6(source: ByteArray, destination: ByteArray, nextHeader: Int, payload: ByteArray, trafficClass: Int = 0): ByteArray {
        val packet = ByteArray(40 + payload.size)
        packet.putU32(0, (6L shl 28) or ((trafficClass.toLong() and 0xff) shl 20))
        packet.putU16(4, payload.size)
        packet[6] = nextHeader.toByte()
        packet[7] = 64
        System.arraycopy(source, 0, packet, 8, 16)
        System.arraycopy(destination, 0, packet, 24, 16)
        System.arraycopy(payload, 0, packet, 40, payload.size)
        return packet
    }

    fun headerLengthFor(address: ByteArray): Int = if (address.size == 4) 20 else 40

    fun addressString(address: ByteArray): String = InetAddress.getByAddress(address).hostAddress ?: "?"

    /** The address as a SIP host: IPv6 in brackets. */
    fun host(address: ByteArray): String = if (address.size == 16) "[${addressString(address)}]" else addressString(address)

    fun hostPort(address: ByteArray, port: Int): String = "${host(address)}:$port"
}
