package com.vowi.vowidroid.core.net

import com.vowi.vowidroid.core.util.putU16
import com.vowi.vowidroid.core.util.u16
import com.vowi.vowidroid.core.util.u32
import com.vowi.vowidroid.core.util.u8

/**
 * Reassembles inbound IPv4 and IPv6 fragments and normalises IPv6 packets so
 * the transport header directly follows the fixed header. Returns null while a
 * datagram is incomplete or when a packet is malformed.
 */
class Reassembly(private val clock: () -> Long = System::currentTimeMillis) {
    private class Pending(val created: Long, var header: ByteArray) {
        val parts = java.util.TreeMap<Int, ByteArray>()
        var total = -1
        var bytes = 0
        var nextHeader = 0
    }

    private val pending = LinkedHashMap<String, Pending>()

    @Synchronized
    fun accept(packet: ByteArray): ByteArray? {
        expire()
        return when (IpPacket.version(packet)) {
            4 -> ipv4(packet)
            6 -> ipv6(packet)
            else -> null
        }
    }

    private fun expire() {
        val now = clock()
        val iterator = pending.values.iterator()
        while (iterator.hasNext()) {
            if (now - iterator.next().created > TIMEOUT_MS) iterator.remove()
        }
    }

    private fun ipv4(packet: ByteArray): ByteArray? {
        if (packet.size < 20) return null
        val headerLength = (packet.u8(0) and 0x0f) * 4
        if (headerLength < 20 || headerLength > packet.size) return null
        val totalLength = packet.u16(2)
        if (totalLength < headerLength || totalLength > packet.size) return null
        val datagram = if (totalLength == packet.size) packet else packet.copyOf(totalLength)
        val flags = datagram.u16(6)
        val moreFragments = flags and 0x2000 != 0
        val offset = (flags and 0x1fff) * 8
        if (!moreFragments && offset == 0) return datagram
        val key = "4:" + datagram.copyOfRange(12, 20).contentToString() + ":" + datagram.u16(4) + ":" + datagram.u8(9)
        val data = datagram.copyOfRange(headerLength, datagram.size)
        val entry = entryFor(key, datagram.copyOf(headerLength)) ?: return null
        if (offset == 0) entry.header = datagram.copyOf(headerLength)
        if (!add(key, entry, offset, data, moreFragments)) return null
        val payload = complete(entry) ?: return null
        pending.remove(key)
        val header = entry.header
        val result = ByteArray(header.size + payload.size)
        System.arraycopy(header, 0, result, 0, header.size)
        System.arraycopy(payload, 0, result, header.size, payload.size)
        if (result.size > 65535) return null
        result.putU16(2, result.size)
        result.putU16(6, 0)
        result.putU16(10, 0)
        result.putU16(10, IpPacket.ipv4Checksum(result, header.size))
        return result
    }

    private fun ipv6(packet: ByteArray): ByteArray? {
        if (packet.size < 40) return null
        val declared = packet.u16(4) + 40
        if (declared > packet.size) return null
        val datagram = if (declared == packet.size) packet else packet.copyOf(declared)
        var nextHeader = datagram.u8(6)
        var offset = 40
        while (true) {
            when (nextHeader) {
                0, 43, 60 -> {
                    if (offset + 2 > datagram.size) return null
                    val length = (datagram.u8(offset + 1) + 1) * 8
                    if (offset + length > datagram.size) return null
                    nextHeader = datagram.u8(offset)
                    offset += length
                }
                IpPacket.IPV6_FRAGMENT -> {
                    if (offset + 8 > datagram.size) return null
                    val fragmentNext = datagram.u8(offset)
                    val field = datagram.u16(offset + 2)
                    val fragmentOffset = (field ushr 3) * 8
                    val moreFragments = field and 1 != 0
                    val identification = datagram.u32(offset + 4)
                    val data = datagram.copyOfRange(offset + 8, datagram.size)
                    if (!moreFragments && fragmentOffset == 0) return plainIpv6(datagram, fragmentNext, data)
                    val key = "6:" + datagram.copyOfRange(8, 40).contentToString() + ":" + identification
                    val entry = entryFor(key, datagram.copyOf(40)) ?: return null
                    if (fragmentOffset == 0) entry.nextHeader = fragmentNext
                    if (!add(key, entry, fragmentOffset, data, moreFragments)) return null
                    val payload = complete(entry) ?: return null
                    pending.remove(key)
                    return plainIpv6(entry.header, entry.nextHeader, payload)
                }
                else -> {
                    if (offset == 40) return datagram
                    return plainIpv6(datagram, nextHeader, datagram.copyOfRange(offset, datagram.size))
                }
            }
        }
    }

    private fun plainIpv6(header: ByteArray, nextHeader: Int, payload: ByteArray): ByteArray? {
        if (payload.size > 65535) return null
        val result = ByteArray(40 + payload.size)
        System.arraycopy(header, 0, result, 0, 40)
        result.putU16(4, payload.size)
        result[6] = nextHeader.toByte()
        System.arraycopy(payload, 0, result, 40, payload.size)
        return result
    }

    private fun entryFor(key: String, header: ByteArray): Pending? {
        pending[key]?.let { return it }
        if (pending.size >= MAX_DATAGRAMS) pending.remove(pending.keys.first())
        return Pending(clock(), header).also { pending[key] = it }
    }

    private fun add(key: String, entry: Pending, offset: Int, data: ByteArray, moreFragments: Boolean): Boolean {
        if (moreFragments && data.size % 8 != 0) {
            pending.remove(key)
            return false
        }
        val end = offset + data.size
        if (end > 65535 || entry.bytes + data.size > 65535) {
            pending.remove(key)
            return false
        }
        if (!moreFragments) entry.total = end
        if (entry.parts.put(offset, data) == null) entry.bytes += data.size
        return true
    }

    /** Joins the parts when they cover 0 until the final fragment's end without gaps. */
    private fun complete(entry: Pending): ByteArray? {
        if (entry.total < 0) return null
        var covered = 0
        for ((offset, data) in entry.parts) {
            if (offset > covered) return null
            covered = maxOf(covered, offset + data.size)
        }
        if (covered < entry.total) return null
        val result = ByteArray(entry.total)
        for ((offset, data) in entry.parts) {
            val length = minOf(data.size, entry.total - offset)
            if (length > 0) System.arraycopy(data, 0, result, offset, length)
        }
        return result
    }

    companion object {
        const val TIMEOUT_MS = 30_000L
        const val MAX_DATAGRAMS = 64
    }
}
