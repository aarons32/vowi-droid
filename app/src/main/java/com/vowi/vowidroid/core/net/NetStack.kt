package com.vowi.vowidroid.core.net

import com.vowi.vowidroid.core.util.ByteWriter
import com.vowi.vowidroid.core.util.CoreLog
import com.vowi.vowidroid.core.util.putU16
import com.vowi.vowidroid.core.util.u16
import com.vowi.vowidroid.core.util.u8
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * VowiDroid's own IP stack for the inside of the ePDG tunnel. It needs no VPN
 * interface and no root: inner packets are handed to it from the ESP
 * dataplane, and SIP and RTP open sockets on it directly.
 *
 * It carries what IMS needs: UDP, a compact TCP for SIP, inbound fragment
 * reassembly and ICMP/ICMPv6 echo. Everything else is dropped.
 */
class NetStack(
    val localIpv4: ByteArray?,
    val localIpv6: ByteArray?,
    /** The inner MTU; carrier bundles may set their own (`IPSecMTU`). */
    val mtu: Int = MTU,
    /** Sends one complete inner IP packet into the tunnel. */
    private val transmit: (ByteArray) -> Unit,
) {
    private val reassembly = Reassembly()
    private val identification = AtomicInteger(1)
    private val ephemeral = AtomicInteger(40000)
    private val lock = Any()
    private val udpSockets = HashMap<Int, UdpSocket>()
    private val tcpConnections = HashMap<Long, TcpConnection>()
    private val tcpListeners = HashMap<Int, TcpListener>()

    /** Applied to every inner packet in both directions, for ipsec-3gpp. */
    @Volatile
    var filter: PacketFilter? = null

    interface PacketFilter {
        /** Returns the packet to send, or throws to drop it. */
        fun outbound(packet: ByteArray): ByteArray

        /** Returns the packet to deliver, or null with a reason to drop it. */
        fun inbound(packet: ByteArray): Pair<ByteArray?, String>
    }

    /** Logs every inner packet at debug level; for diagnosing a registration that stalls. */
    @Volatile
    var trace: Boolean = false

    private fun describe(parsed: IpPacket.Transport, size: Int): String =
        "proto=${parsed.protocol} ${IpPacket.addressString(parsed.source)}:${parsed.sourcePort} -> ${IpPacket.addressString(parsed.destination)}:${parsed.destinationPort} len=$size"

    fun localAddressFor(remote: ByteArray): ByteArray =
        (if (remote.size == 4) localIpv4 else localIpv6) ?: throw IOException("the tunnel has no address in that family")

    fun nextEphemeralPort(): Int {
        synchronized(lock) {
            repeat(4096) {
                val port = ephemeral.getAndUpdate { if (it >= 60000) 40000 else it + 1 }
                if (!udpSockets.containsKey(port) && !tcpListeners.containsKey(port) &&
                    tcpConnections.values.none { connection -> connection.localPort == port }
                ) {
                    return port
                }
            }
        }
        throw IOException("no free local port in the tunnel")
    }

    // --- Sockets -----------------------------------------------------------

    fun openUdp(port: Int = 0, family: Int = 4): UdpSocket {
        val address = (if (family == 4) localIpv4 else localIpv6) ?: throw IOException("the tunnel has no address in that family")
        val local = if (port != 0) port else nextEphemeralPort()
        val socket = UdpSocket(this, address, local)
        synchronized(lock) {
            if (udpSockets.containsKey(local)) throw IOException("local UDP port $local is already open")
            udpSockets[local] = socket
        }
        return socket
    }

    fun connectTcp(remoteAddress: ByteArray, remotePort: Int, localPort: Int = 0, timeoutMs: Long = 20_000): TcpConnection {
        val local = localAddressFor(remoteAddress)
        val port = if (localPort != 0) localPort else nextEphemeralPort()
        val connection = TcpConnection(this, local, port, remoteAddress, remotePort)
        synchronized(lock) {
            val key = flowKey(local, port, remoteAddress, remotePort)
            if (tcpConnections.containsKey(key)) throw IOException("that TCP flow is already open")
            tcpConnections[key] = connection
        }
        try {
            connection.connect(timeoutMs)
        } catch (error: Exception) {
            connection.close()
            throw error
        }
        return connection
    }

    fun listenTcp(port: Int, family: Int = 4): TcpListener {
        val address = (if (family == 4) localIpv4 else localIpv6) ?: throw IOException("the tunnel has no address in that family")
        val listener = TcpListener(this, address, port)
        synchronized(lock) {
            if (tcpListeners.containsKey(port)) throw IOException("local TCP port $port is already listening")
            tcpListeners[port] = listener
        }
        return listener
    }

    internal fun release(socket: UdpSocket) {
        synchronized(lock) { udpSockets.remove(socket.localPort, socket) }
    }

    internal fun release(connection: TcpConnection) {
        synchronized(lock) {
            tcpConnections.remove(flowKey(connection.localAddress, connection.localPort, connection.remoteAddress, connection.remotePort), connection)
        }
    }

    internal fun release(listener: TcpListener) {
        synchronized(lock) { tcpListeners.remove(listener.localPort, listener) }
    }

    internal fun register(connection: TcpConnection) {
        synchronized(lock) {
            tcpConnections[flowKey(connection.localAddress, connection.localPort, connection.remoteAddress, connection.remotePort)] = connection
        }
    }

    // --- Transmit ----------------------------------------------------------

    internal fun sendUdp(localAddress: ByteArray, localPort: Int, remoteAddress: ByteArray, remotePort: Int, data: ByteArray) {
        val segment = ByteArray(8 + data.size)
        segment.putU16(0, localPort)
        segment.putU16(2, remotePort)
        segment.putU16(4, segment.size)
        System.arraycopy(data, 0, segment, 8, data.size)
        var checksum = IpPacket.transportChecksum(localAddress, remoteAddress, IpPacket.UDP, segment)
        // A zero UDP checksum means "not computed" in IPv4 and is illegal in IPv6.
        if (checksum == 0) checksum = 0xffff
        segment.putU16(6, checksum)
        sendSegment(localAddress, remoteAddress, IpPacket.UDP, segment)
    }

    internal fun sendSegment(localAddress: ByteArray, remoteAddress: ByteArray, protocol: Int, segment: ByteArray) {
        val packet = if (localAddress.size == 4) {
            IpPacket.buildIpv4(localAddress, remoteAddress, protocol, segment, identification.getAndIncrement())
        } else {
            IpPacket.buildIpv6(localAddress, remoteAddress, protocol, segment)
        }
        val filtered = try {
            filter?.outbound(packet) ?: packet
        } catch (error: Exception) {
            CoreLog.d(TAG, "dropped an outbound packet: ${error.message}")
            return
        }
        if (trace) IpPacket.parseTransport(packet)?.let { CoreLog.d(TAG, "out " + describe(it, packet.size)) }
        transmit(filtered)
    }

    // --- Receive -----------------------------------------------------------

    /** Hands one inner packet from the dataplane to the stack. */
    fun deliver(raw: ByteArray) {
        val assembled = reassembly.accept(raw) ?: return
        val (packet, reason) = filter?.inbound(assembled) ?: (assembled to "")
        if (packet == null) {
            CoreLog.d(TAG, "dropped an inbound packet: $reason")
            return
        }
        val parsed = IpPacket.parseTransport(packet) ?: run {
            if (trace) CoreLog.d(TAG, "in: unparsable packet of ${packet.size} bytes")
            return
        }
        if (trace) CoreLog.d(TAG, "in " + describe(parsed, packet.size))
        val payload = packet.copyOfRange(parsed.headerLength, packet.size)
        when (parsed.protocol) {
            IpPacket.UDP -> deliverUdp(parsed, payload)
            IpPacket.TCP -> deliverTcp(parsed, payload)
            IpPacket.ICMP, IpPacket.ICMPV6 -> deliverEcho(parsed, payload)
        }
    }

    private fun deliverUdp(parsed: IpPacket.Transport, payload: ByteArray) {
        if (payload.size < 8) return
        val length = payload.u16(4)
        if (length < 8 || length > payload.size) return
        val socket = synchronized(lock) { udpSockets[parsed.destinationPort] } ?: return
        socket.deliver(Datagram(payload.copyOfRange(8, length), parsed.source, parsed.sourcePort))
    }

    private fun deliverTcp(parsed: IpPacket.Transport, segment: ByteArray) {
        if (segment.size < 20) return
        val key = flowKey(parsed.destination, parsed.destinationPort, parsed.source, parsed.sourcePort)
        val connection = synchronized(lock) { tcpConnections[key] }
        if (connection != null) {
            connection.accept(segment)
            return
        }
        val flags = segment.u8(13)
        val listener = synchronized(lock) { tcpListeners[parsed.destinationPort] }
        if (listener != null && flags and Tcp.SYN != 0 && flags and Tcp.ACK == 0) {
            listener.accept(parsed, segment)
            return
        }
        if (flags and Tcp.RST == 0) sendTcpReset(parsed, segment)
    }

    /** Answers an unexpected segment with RST, as a host stack would. */
    private fun sendTcpReset(parsed: IpPacket.Transport, segment: ByteArray) {
        val sequence = segment.u16(4).toLong() shl 16 or segment.u16(6).toLong()
        val dataOffset = (segment.u8(12) ushr 4) * 4
        val payloadLength = maxOf(0, segment.size - dataOffset)
        val flags = segment.u8(13)
        var acknowledgement = sequence + payloadLength
        if (flags and Tcp.SYN != 0) acknowledgement += 1
        if (flags and Tcp.FIN != 0) acknowledgement += 1
        val reset = Tcp.build(
            sourcePort = parsed.destinationPort,
            destinationPort = parsed.sourcePort,
            sequence = if (flags and Tcp.ACK != 0) segment.u16(8).toLong() shl 16 or segment.u16(10).toLong() else 0,
            acknowledgement = acknowledgement,
            flags = Tcp.RST or Tcp.ACK,
            window = 0,
            payload = ByteArray(0),
            source = parsed.destination,
            destination = parsed.source,
        )
        sendSegment(parsed.destination, parsed.source, IpPacket.TCP, reset)
    }

    /** Replies to echo requests so the ePDG's reachability checks succeed. */
    private fun deliverEcho(parsed: IpPacket.Transport, payload: ByteArray) {
        if (payload.size < 8) return
        val ipv4 = parsed.source.size == 4
        val requestType = if (ipv4) 8 else 128
        val replyType = if (ipv4) 0 else 129
        if (payload.u8(0) != requestType) return
        val reply = payload.copyOf()
        reply[0] = replyType.toByte()
        reply.putU16(2, 0)
        val checksum = if (ipv4) {
            IpPacket.ipv4Checksum(reply, reply.size)
        } else {
            IpPacket.transportChecksum(parsed.destination, parsed.source, IpPacket.ICMPV6, reply)
        }
        reply.putU16(2, checksum)
        sendSegment(parsed.destination, parsed.source, if (ipv4) IpPacket.ICMP else IpPacket.ICMPV6, reply)
    }

    fun close() {
        val (sockets, connections, listeners) = synchronized(lock) {
            Triple(udpSockets.values.toList(), tcpConnections.values.toList(), tcpListeners.values.toList())
        }
        sockets.forEach { runCatching { it.close() } }
        connections.forEach { runCatching { it.close() } }
        listeners.forEach { runCatching { it.close() } }
    }

    companion object {
        private const val TAG = "NetStack"

        /**
         * Inner packets stay at the IPv6 minimum MTU, leaving room for
         * ipsec-3gpp transport ESP plus the tunnel's ESP-in-UDP framing under a
         * 1500-byte path, so TCP segments never need outer fragmentation.
         */
        const val MTU = 1280

        fun flowKey(localAddress: ByteArray, localPort: Int, remoteAddress: ByteArray, remotePort: Int): Long {
            var hash = 1125899906842597L
            for (byte in localAddress) hash = hash * 31 + byte
            for (byte in remoteAddress) hash = hash * 31 + byte
            hash = hash * 31 + localPort
            hash = hash * 31 + remotePort
            return hash
        }
    }
}

/** TCP header constants and a header builder. */
object Tcp {
    const val FIN = 0x01
    const val SYN = 0x02
    const val RST = 0x04
    const val PSH = 0x08
    const val ACK = 0x10

    fun build(
        sourcePort: Int,
        destinationPort: Int,
        sequence: Long,
        acknowledgement: Long,
        flags: Int,
        window: Int,
        payload: ByteArray,
        source: ByteArray,
        destination: ByteArray,
        options: ByteArray = ByteArray(0),
    ): ByteArray {
        require(options.size % 4 == 0) { "TCP options must be padded to four bytes" }
        val headerLength = 20 + options.size
        val out = ByteWriter(headerLength + payload.size)
        out.u16(sourcePort).u16(destinationPort)
        out.u32(sequence and 0xffffffffL)
        out.u32(acknowledgement and 0xffffffffL)
        out.byte((headerLength / 4) shl 4).byte(flags)
        out.u16(window).u16(0).u16(0)
        out.bytes(options).bytes(payload)
        val segment = out.toByteArray()
        segment.putU16(16, IpPacket.transportChecksum(source, destination, IpPacket.TCP, segment))
        return segment
    }

    fun maximumSegmentSizeOption(value: Int): ByteArray = byteArrayOf(2, 4, (value ushr 8).toByte(), value.toByte(), 1, 1, 0, 0)
}
