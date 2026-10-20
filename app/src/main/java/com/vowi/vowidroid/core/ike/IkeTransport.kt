package com.vowi.vowidroid.core.ike

import com.vowi.vowidroid.core.util.CoreLog
import com.vowi.vowidroid.core.util.u16
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

/**
 * The SWu datagram transport to the ePDG.
 *
 * Unlike a textbook IKEv2 initiator, this starts on UDP 4500 with the
 * non-ESP marker (RFC 3948) and keeps **one** socket for the whole session:
 * IKE_SA_INIT, IKE_AUTH, every ESP packet and the keepalives all share one
 * NAT mapping and one 5-tuple. Carrier ePDGs sit behind load balancers that
 * key on that 5-tuple, and changing it between the two halves of the
 * handshake is what made IKE_AUTH time out before.
 *
 * Port 500 with a float to 4500 remains available as a per-address fallback.
 */
class IkeTransport private constructor(
    private val socket: DatagramSocket,
    private var remote: InetSocketAddress,
    private var marked: Boolean,
) : AutoCloseable {
    /** The local endpoint, for NAT detection hashes. */
    val localAddress: InetSocketAddress get() = InetSocketAddress(socket.localAddress, socket.localPort)

    val remoteAddress: InetSocketAddress get() = remote

    /** True when IKE packets carry the non-ESP marker, so ESP shares the socket. */
    val encapsulated: Boolean get() = marked

    /** Moves to UDP 4500 and starts marking IKE packets. A no-op when already there. */
    fun float(port: Int = 4500) {
        if (marked) return // already on the NAT-T port with markers
        remote = InetSocketAddress(remote.address, port)
        socket.disconnect()
        socket.connect(remote)
        marked = true
        CoreLog.d(TAG, "floated to UDP 4500 on the existing socket")
    }

    /**
     * Sends a request (one packet, or its RFC 7383 fragments) and returns the
     * complete response, retransmitting on the standard IKEv2 schedule.
     */
    fun roundTrip(
        packets: List<ByteArray>,
        timeoutMs: Long,
        retransmits: LongArray = RETRANSMIT_MS,
        accept: (IkeHeader) -> Boolean = { true },
    ): List<ByteArray> {
        require(packets.isNotEmpty()) { "outbound packet list is empty" }
        val deadline = System.currentTimeMillis() + timeoutMs
        val buffer = ByteArray(65535)
        var lastError: Exception? = null
        for (interval in retransmits) {
            if (System.currentTimeMillis() >= deadline) break
            for (packet in packets) send(packet, true)
            val attemptDeadline = minOf(System.currentTimeMillis() + interval, deadline)
            val fragments = HashMap<Int, ByteArray>()
            var expected = 0
            while (System.currentTimeMillis() < attemptDeadline) {
                val remaining = (attemptDeadline - System.currentTimeMillis()).toInt()
                if (remaining <= 0) break
                val received = try {
                    receive(buffer, remaining)
                } catch (error: SocketTimeoutException) {
                    lastError = error
                    break
                }
                if (received == null) continue
                val packet = buffer.copyOf(received)
                val header = runCatching { IkeHeader.parse(packet) }.getOrNull() ?: continue
                // Stale packets from an earlier IKE SA, or requests, are not this response.
                if (!accept(header.first)) continue
                if (header.first.nextPayload == Ike.PAYLOAD_ENCRYPTED_FRAGMENT && header.second.size >= 8) {
                    val number = header.second.u16(4)
                    val total = header.second.u16(6)
                    if (total > 1) {
                        if (expected == 0) expected = total
                        fragments[number] = packet
                        if (fragments.size == expected) return (1..expected).mapNotNull { fragments[it] }
                        continue
                    }
                }
                return listOf(packet)
            }
        }
        throw IOException("the ePDG did not answer on UDP ${remote.port}", lastError)
    }

    /** Sends one session packet: an IKE message (marked) or a raw ESP packet. */
    fun send(packet: ByteArray, isIke: Boolean) {
        val wire = if (isIke && marked) MARKER + packet else packet
        socket.send(DatagramPacket(wire, wire.size, remote))
    }

    /** Receives into `buffer`, returning the length, or null for a packet to ignore. */
    private fun receive(buffer: ByteArray, timeoutMs: Int): Int? {
        socket.soTimeout = maxOf(1, timeoutMs)
        val datagram = DatagramPacket(buffer, buffer.size)
        socket.receive(datagram)
        var length = datagram.length
        if (!marked) return length
        if (length < 4) return null
        if (!(buffer[0] == 0.toByte() && buffer[1] == 0.toByte() && buffer[2] == 0.toByte() && buffer[3] == 0.toByte())) {
            // Not an IKE message: an ESP packet arrived during an exchange.
            return null
        }
        System.arraycopy(buffer, 4, buffer, 0, length - 4)
        length -= 4
        return length
    }

    /**
     * Receives any session packet. Returns the length and whether it is an IKE
     * message; ESP packets and NAT keepalives come back as non-IKE.
     */
    fun receiveSession(buffer: ByteArray, timeoutMs: Int): Pair<Int, Boolean>? {
        socket.soTimeout = maxOf(1, timeoutMs)
        val datagram = DatagramPacket(buffer, buffer.size)
        socket.receive(datagram)
        val length = datagram.length
        if (!marked) return length to true
        if (length >= 4 && buffer[0] == 0.toByte() && buffer[1] == 0.toByte() && buffer[2] == 0.toByte() && buffer[3] == 0.toByte()) {
            System.arraycopy(buffer, 4, buffer, 0, length - 4)
            return (length - 4) to true
        }
        return length to false
    }

    /** The RFC 3948 one-byte NAT keepalive. */
    fun sendKeepalive() {
        if (!marked) return
        socket.send(DatagramPacket(KEEPALIVE, KEEPALIVE.size, remote))
    }

    override fun close() {
        runCatching { socket.close() }
    }

    companion object {
        private const val TAG = "IKE"
        private val MARKER = ByteArray(4)
        private val KEEPALIVE = byteArrayOf(0xff.toByte())

        /** 0.5s, 1s, 2s, 4s, as RFC 7296 suggests. */
        val RETRANSMIT_MS = longArrayOf(500, 1000, 2000, 4000)

        /**
         * Opens a socket to one ePDG address.
         *
         * `encapsulated` opens straight onto UDP 4500 with markers, which is
         * the default: one mapping carries the whole session. Otherwise it
         * starts on 500 and [float] moves it later.
         *
         * `bind` is the app's hook for `Network.bindSocket`, so the datagrams
         * leave over Wi-Fi rather than the default route.
         */
        fun open(
            address: InetAddress,
            encapsulated: Boolean = true,
            bind: ((DatagramSocket) -> Unit)? = null,
            remotePort: Int = if (encapsulated) 4500 else 500,
        ): IkeTransport {
            val port = remotePort
            // Binding the local port to 4500 matches a UE on an open network;
            // through NAT any local port works, so a busy port is not fatal.
            val socket = openLocal(if (encapsulated) 4500 else 0)
            try {
                bind?.invoke(socket)
                socket.connect(InetSocketAddress(address, port))
            } catch (error: Exception) {
                socket.close()
                throw error
            }
            return IkeTransport(socket, InetSocketAddress(address, port), encapsulated)
        }

        private fun openLocal(preferredPort: Int): DatagramSocket {
            if (preferredPort != 0) {
                runCatching { return DatagramSocket(preferredPort) }
            }
            return DatagramSocket()
        }
    }
}
