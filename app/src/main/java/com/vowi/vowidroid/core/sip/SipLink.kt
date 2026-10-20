package com.vowi.vowidroid.core.sip

import com.vowi.vowidroid.core.net.IpPacket
import com.vowi.vowidroid.core.net.NetStack
import com.vowi.vowidroid.core.net.TcpConnection
import com.vowi.vowidroid.core.net.TcpListener
import com.vowi.vowidroid.core.net.UdpSocket
import com.vowi.vowidroid.core.util.CoreLog
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** Delivers one inbound message with a way to answer on the same path. */
typealias SipReceiver = (message: SipMessage, respond: (ByteArray) -> Unit) -> Unit

/** A SIP connection to the P-CSCF inside the tunnel, over TCP or UDP. */
interface SipLink : AutoCloseable {
    val transport: String
    val localAddress: ByteArray
    val localPort: Int
    val remoteAddress: ByteArray
    val remotePort: Int

    fun send(packet: ByteArray)

    /** Starts the reader; `onClosed` fires once when the link ends. */
    fun start(receiver: SipReceiver, onClosed: (Exception?) -> Unit)

    val localHostPort: String get() = IpPacket.hostPort(localAddress, localPort)
    val remoteHostPort: String get() = IpPacket.hostPort(remoteAddress, remotePort)
}

class TcpSipLink(private val connection: TcpConnection) : SipLink {
    override val transport = "tcp"
    override val localAddress: ByteArray get() = connection.localAddress
    override val localPort: Int get() = connection.localPort
    override val remoteAddress: ByteArray get() = connection.remoteAddress
    override val remotePort: Int get() = connection.remotePort
    private val writeLock = Any()
    private val closed = AtomicBoolean(false)

    override fun send(packet: ByteArray) = synchronized(writeLock) { connection.write(packet) }

    override fun start(receiver: SipReceiver, onClosed: (Exception?) -> Unit) {
        val thread = Thread({
            val stream = connection.inputStream()
            var error: Exception? = null
            try {
                while (!closed.get()) {
                    val message = SipMessage.read(stream) ?: break
                    receiver(message) { reply -> send(reply) }
                }
            } catch (failure: Exception) {
                if (!closed.get()) error = failure
            }
            if (!closed.get()) onClosed(error)
        }, "vowi-sip-tcp")
        thread.isDaemon = true
        thread.start()
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) connection.close()
    }
}

class UdpSipLink(private val socket: UdpSocket, override val remoteAddress: ByteArray, override val remotePort: Int) : SipLink {
    override val transport = "udp"
    override val localAddress: ByteArray get() = socket.localAddress
    override val localPort: Int get() = socket.localPort
    private val closed = AtomicBoolean(false)

    override fun send(packet: ByteArray) = socket.send(packet, remoteAddress, remotePort)

    override fun start(receiver: SipReceiver, onClosed: (Exception?) -> Unit) {
        val thread = Thread({
            var error: Exception? = null
            try {
                while (!closed.get()) {
                    val datagram = socket.receive(1000) ?: continue
                    if (!datagram.sourceAddress.contentEquals(remoteAddress)) continue
                    val message = try {
                        SipMessage.parse(datagram.data)
                    } catch (parseError: SipParseException) {
                        CoreLog.d(TAG, "ignored an unparsable SIP datagram: ${parseError.message}")
                        continue
                    }
                    receiver(message) { reply -> socket.send(reply, datagram.sourceAddress, datagram.sourcePort) }
                }
            } catch (failure: Exception) {
                if (!closed.get()) error = failure
            }
            if (!closed.get()) onClosed(error)
        }, "vowi-sip-udp")
        thread.isDaemon = true
        thread.start()
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) socket.close()
    }

    companion object {
        private const val TAG = "SIP"
    }
}

/**
 * The UE's protected server port: the P-CSCF opens TCP connections to it and
 * sends UDP to it for network-originated requests (MESSAGE, INVITE). Both are
 * reserved before REGISTER so the Security-Client offer is honest.
 */
class ProtectedReceivers(stack: NetStack, localAddress: ByteArray, val port: Int, private val pcscfAddress: ByteArray) : AutoCloseable {
    private val family = if (localAddress.size == 4) 4 else 6
    private val listener: TcpListener = stack.listenTcp(port, family)
    private val socket: UdpSocket = stack.openUdp(port, family)
    private val closed = AtomicBoolean(false)
    private val connections = HashSet<TcpSipLink>()

    fun start(receiver: SipReceiver) {
        Thread({
            while (!closed.get()) {
                val connection = try {
                    listener.accept(1000) ?: continue
                } catch (_: Exception) {
                    break
                }
                if (!connection.remoteAddress.contentEquals(pcscfAddress)) {
                    CoreLog.w(TAG, "refused a protected TCP connection from ${IpPacket.addressString(connection.remoteAddress)}")
                    runCatching { connection.close() }
                    continue
                }
                val link = TcpSipLink(connection)
                synchronized(connections) { connections.add(link) }
                link.start(receiver) { _ ->
                    synchronized(connections) { connections.remove(link) }
                    runCatching { link.close() }
                }
            }
        }, "vowi-sip-accept").apply { isDaemon = true }.start()
        Thread({
            while (!closed.get()) {
                val datagram = try {
                    socket.receive(1000) ?: continue
                } catch (_: Exception) {
                    break
                }
                if (!datagram.sourceAddress.contentEquals(pcscfAddress)) continue
                val message = try {
                    SipMessage.parse(datagram.data)
                } catch (error: SipParseException) {
                    CoreLog.d(TAG, "ignored an unparsable protected datagram: ${error.message}")
                    continue
                }
                receiver(message) { reply -> socket.send(reply, datagram.sourceAddress, datagram.sourcePort) }
            }
        }, "vowi-sip-protected-udp").apply { isDaemon = true }.start()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { listener.close() }
        runCatching { socket.close() }
        val open = synchronized(connections) { connections.toList() }
        open.forEach { runCatching { it.close() } }
    }

    companion object {
        private const val TAG = "SIP"

        /** Reserves a random protected port. */
        fun open(stack: NetStack, localAddress: ByteArray, pcscfAddress: ByteArray, preferredPort: Int, exclude: Int): ProtectedReceivers {
            if (preferredPort != 0) return ProtectedReceivers(stack, localAddress, preferredPort, pcscfAddress)
            var lastError: Exception? = null
            repeat(32) {
                val port = SecurityProposal.randomProtectedPort(exclude) { true }
                try {
                    return ProtectedReceivers(stack, localAddress, port, pcscfAddress)
                } catch (error: IOException) {
                    lastError = error
                }
            }
            throw IOException("no protected local port is available", lastError)
        }
    }
}
