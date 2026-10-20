package com.vowi.vowidroid.core.net

import com.vowi.vowidroid.core.util.CoreLog
import com.vowi.vowidroid.core.util.u16
import com.vowi.vowidroid.core.util.u32
import com.vowi.vowidroid.core.util.u8
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import java.util.TreeMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * A TCP connection inside the tunnel's stack. It carries SIP: one stream in
 * each direction, a few kilobytes per message, on a low-loss path. The
 * implementation is deliberately small: cumulative acknowledgements, one
 * retransmission timer, a fixed receive window and no congestion window
 * beyond a single outstanding flight.
 */
class TcpConnection internal constructor(
    private val stack: NetStack,
    val localAddress: ByteArray,
    val localPort: Int,
    val remoteAddress: ByteArray,
    val remotePort: Int,
) : AutoCloseable {
    private enum class State { CLOSED, SYN_SENT, SYN_RECEIVED, ESTABLISHED, FIN_WAIT, CLOSE_WAIT, LAST_ACK, TIME_WAIT }

    private val lock = Object()
    private var state = State.CLOSED
    private var sendNext = 0L
    private var sendUnacknowledged = 0L
    private var receiveNext = 0L
    private var peerWindow = DEFAULT_WINDOW
    private var peerMss = DEFAULT_MSS
    private var failure: IOException? = null

    private val sendBuffer = ArrayDeque<Byte>()
    private val outOfOrder = TreeMap<Long, ByteArray>()
    private val received = LinkedBlockingQueue<ByteArray>(RECEIVE_QUEUE)
    private var pendingBytes = 0
    private var lastTransmit = 0L
    private var retransmits = 0
    private var finReceived = false
    private var finSent = false

    val established: Boolean get() = synchronized(lock) { state == State.ESTABLISHED || state == State.CLOSE_WAIT }

    // --- Client and server handshakes --------------------------------------

    internal fun connect(timeoutMs: Long) {
        synchronized(lock) {
            sendNext = initialSequence()
            sendUnacknowledged = sendNext
            state = State.SYN_SENT
            transmitFlags(Tcp.SYN, Tcp.maximumSegmentSizeOption(stack.mtu - 40 - 20))
            val deadline = System.currentTimeMillis() + timeoutMs
            while (state == State.SYN_SENT && failure == null) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) {
                    state = State.CLOSED
                    throw IOException("the P-CSCF did not answer the TCP handshake")
                }
                lock.wait(minOf(remaining, RETRANSMIT_MS))
                maybeRetransmitLocked()
            }
            failure?.let { throw it }
            if (state != State.ESTABLISHED) throw IOException("the TCP connection was refused")
        }
    }

    /** Completes the passive side of a handshake for a listener. */
    internal fun acceptSyn(segment: ByteArray) {
        synchronized(lock) {
            receiveNext = sequenceOf(segment) + 1
            peerWindow = segment.u16(14)
            peerMss = mssOption(segment) ?: DEFAULT_MSS
            sendNext = initialSequence()
            sendUnacknowledged = sendNext
            state = State.SYN_RECEIVED
            transmitFlags(Tcp.SYN or Tcp.ACK, Tcp.maximumSegmentSizeOption(stack.mtu - 40 - 20))
        }
    }

    internal fun awaitEstablished(timeoutMs: Long): Boolean {
        synchronized(lock) {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (state == State.SYN_RECEIVED && failure == null) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) return false
                lock.wait(minOf(remaining, RETRANSMIT_MS))
                maybeRetransmitLocked()
            }
            return state == State.ESTABLISHED || state == State.CLOSE_WAIT
        }
    }

    // --- Inbound segments --------------------------------------------------

    internal fun accept(segment: ByteArray) {
        synchronized(lock) {
            val flags = segment.u8(13)
            val dataOffset = (segment.u8(12) ushr 4) * 4
            if (dataOffset < 20 || dataOffset > segment.size) return
            val sequence = sequenceOf(segment)
            val acknowledgement = acknowledgementOf(segment)
            val payload = segment.copyOfRange(dataOffset, segment.size)
            if (stack.trace) CoreLog.d(TAG, "tcp :$localPort<-:$remotePort flags=${flagNames(flags)} seq=$sequence ack=$acknowledgement len=${payload.size} state=$state una=$sendUnacknowledged nxt=$sendNext rcv=$receiveNext")

            if (flags and Tcp.RST != 0) {
                fail(IOException("the P-CSCF reset the TCP connection"))
                return
            }
            peerWindow = segment.u16(14)

            when (state) {
                State.SYN_SENT -> {
                    if (flags and Tcp.SYN == 0 || flags and Tcp.ACK == 0) return
                    if (acknowledgement != sendNext + 1) return
                    sendNext += 1
                    sendUnacknowledged = sendNext
                    receiveNext = sequence + 1
                    peerMss = mssOption(segment) ?: DEFAULT_MSS
                    state = State.ESTABLISHED
                    retransmits = 0
                    transmitFlags(Tcp.ACK)
                    lock.notifyAll()
                    return
                }
                State.SYN_RECEIVED -> {
                    if (flags and Tcp.ACK == 0 || acknowledgement != sendNext + 1) return
                    sendNext += 1
                    sendUnacknowledged = sendNext
                    state = State.ESTABLISHED
                    retransmits = 0
                    lock.notifyAll()
                }
                State.CLOSED, State.TIME_WAIT -> return
                else -> Unit
            }

            if (flags and Tcp.ACK != 0) acknowledge(acknowledgement)

            if (payload.isNotEmpty()) {
                takePayload(sequence, payload)
                transmitFlags(Tcp.ACK)
            }

            if (flags and Tcp.FIN != 0 && sequence <= receiveNext) {
                if (!finReceived) {
                    finReceived = true
                    receiveNext = maxOf(receiveNext, sequence) + 1
                    received.offer(END_OF_STREAM)
                    transmitFlags(Tcp.ACK)
                    state = when (state) {
                        State.FIN_WAIT -> State.TIME_WAIT
                        State.ESTABLISHED -> State.CLOSE_WAIT
                        else -> state
                    }
                }
            }
            if (state == State.FIN_WAIT && finSent && sendUnacknowledged >= sendNext && finReceived) state = State.TIME_WAIT
            if (state == State.LAST_ACK && sendUnacknowledged >= sendNext) state = State.CLOSED
            lock.notifyAll()
        }
    }

    private fun acknowledge(acknowledgement: Long) {
        if (acknowledgement <= sendUnacknowledged) return
        val acknowledged = (acknowledgement - sendUnacknowledged).toInt()
        repeat(minOf(acknowledged, sendBuffer.size)) { sendBuffer.removeFirst() }
        sendUnacknowledged = acknowledgement
        pendingBytes = sendBuffer.size
        retransmits = 0
        lastTransmit = System.currentTimeMillis()
        transmitPendingLocked()
    }

    private fun takePayload(sequence: Long, payload: ByteArray) {
        if (sequence + payload.size <= receiveNext) return
        if (sequence > receiveNext) {
            if (outOfOrder.size < OUT_OF_ORDER_LIMIT) outOfOrder[sequence] = payload
            return
        }
        var data = payload
        if (sequence < receiveNext) data = payload.copyOfRange((receiveNext - sequence).toInt(), payload.size)
        deliver(data)
        receiveNext += data.size
        while (true) {
            val entry = outOfOrder.entries.firstOrNull { it.key <= receiveNext && it.key + it.value.size > receiveNext } ?: break
            outOfOrder.remove(entry.key)
            val tail = entry.value.copyOfRange((receiveNext - entry.key).toInt(), entry.value.size)
            deliver(tail)
            receiveNext += tail.size
        }
        outOfOrder.keys.filter { it + (outOfOrder[it]?.size ?: 0) <= receiveNext }.forEach { outOfOrder.remove(it) }
    }

    private fun deliver(data: ByteArray) {
        if (data.isEmpty()) return
        if (!received.offer(data)) fail(IOException("the TCP receive queue overflowed"))
    }

    // --- Outbound data -----------------------------------------------------

    fun write(data: ByteArray) {
        synchronized(lock) {
            failure?.let { throw it }
            if (state != State.ESTABLISHED && state != State.CLOSE_WAIT) throw IOException("the TCP connection is not established")
            for (byte in data) sendBuffer.addLast(byte)
            pendingBytes = sendBuffer.size
            transmitPendingLocked()
            val deadline = System.currentTimeMillis() + SEND_TIMEOUT_MS
            while (sendUnacknowledged < sendNext && failure == null) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) {
                    fail(IOException("the P-CSCF did not acknowledge the SIP request"))
                    break
                }
                lock.wait(minOf(remaining, RETRANSMIT_MS))
                maybeRetransmitLocked()
            }
            failure?.let { throw it }
        }
    }

    /** Waits up to `timeoutMs` for stream bytes; returns null on timeout, empty at end of stream. */
    fun read(timeoutMs: Long): ByteArray? {
        synchronized(lock) { failure?.let { throw it } }
        val data = received.poll(timeoutMs, TimeUnit.MILLISECONDS)
        synchronized(lock) { failure?.let { throw it } }
        if (data === END_OF_STREAM) {
            received.offer(END_OF_STREAM)
            return ByteArray(0)
        }
        return data
    }

    private fun transmitPendingLocked() {
        val inFlight = (sendNext - sendUnacknowledged).toInt()
        if (inFlight > 0) return
        if (sendBuffer.isEmpty()) return
        val window = minOf(peerWindow, peerMss, sendBuffer.size)
        if (window <= 0) return
        val chunk = ByteArray(window)
        var index = 0
        for (byte in sendBuffer) {
            if (index == window) break
            chunk[index++] = byte
        }
        transmit(Tcp.ACK or Tcp.PSH, chunk, sendNext)
        sendNext += chunk.size
        lastTransmit = System.currentTimeMillis()
    }

    private fun maybeRetransmitLocked() {
        if (sendUnacknowledged >= sendNext && state != State.SYN_SENT && state != State.SYN_RECEIVED) return
        val elapsed = System.currentTimeMillis() - lastTransmit
        val timeout = RETRANSMIT_MS shl minOf(retransmits, 4)
        if (elapsed < timeout) return
        if (retransmits >= MAX_RETRANSMITS) {
            fail(IOException("the P-CSCF stopped answering the TCP connection"))
            return
        }
        retransmits++
        lastTransmit = System.currentTimeMillis()
        when (state) {
            State.SYN_SENT -> transmit(Tcp.SYN, ByteArray(0), sendUnacknowledged, Tcp.maximumSegmentSizeOption(stack.mtu - 40 - 20))
            State.SYN_RECEIVED -> transmit(Tcp.SYN or Tcp.ACK, ByteArray(0), sendUnacknowledged, Tcp.maximumSegmentSizeOption(stack.mtu - 40 - 20))
            else -> {
                val outstanding = (sendNext - sendUnacknowledged).toInt()
                if (outstanding <= 0) return
                if (finSent && outstanding == 1 && sendBuffer.isEmpty()) {
                    transmit(Tcp.ACK or Tcp.FIN, ByteArray(0), sendUnacknowledged)
                    return
                }
                val chunk = ByteArray(minOf(outstanding, sendBuffer.size))
                var index = 0
                for (byte in sendBuffer) {
                    if (index == chunk.size) break
                    chunk[index++] = byte
                }
                if (chunk.isNotEmpty()) transmit(Tcp.ACK or Tcp.PSH, chunk, sendUnacknowledged)
            }
        }
    }

    private fun transmitFlags(flags: Int, options: ByteArray = ByteArray(0)) {
        transmit(flags, ByteArray(0), sendNext, options)
        if (flags and (Tcp.SYN or Tcp.FIN) != 0) lastTransmit = System.currentTimeMillis()
    }

    private fun transmit(flags: Int, payload: ByteArray, sequence: Long, options: ByteArray = ByteArray(0)) {
        if (stack.trace) CoreLog.d(TAG, "tcp :$localPort->:$remotePort flags=${flagNames(flags)} seq=$sequence ack=$receiveNext len=${payload.size} state=$state")
        val segment = Tcp.build(
            sourcePort = localPort,
            destinationPort = remotePort,
            sequence = sequence,
            acknowledgement = receiveNext,
            flags = flags,
            window = DEFAULT_WINDOW,
            payload = payload,
            source = localAddress,
            destination = remoteAddress,
            options = options,
        )
        stack.sendSegment(localAddress, remoteAddress, IpPacket.TCP, segment)
    }

    private fun fail(error: IOException) {
        if (failure == null) {
            failure = error
            CoreLog.d(TAG, "TCP flow to port $remotePort failed: ${error.message}")
        }
        state = State.CLOSED
        received.offer(END_OF_STREAM)
        lock.notifyAll()
    }

    override fun close() {
        synchronized(lock) {
            if (state == State.ESTABLISHED || state == State.CLOSE_WAIT) {
                finSent = true
                transmit(Tcp.ACK or Tcp.FIN, ByteArray(0), sendNext)
                sendNext += 1
                lastTransmit = System.currentTimeMillis()
                state = if (state == State.CLOSE_WAIT) State.LAST_ACK else State.FIN_WAIT
                val deadline = System.currentTimeMillis() + CLOSE_TIMEOUT_MS
                while (state == State.FIN_WAIT || state == State.LAST_ACK) {
                    val remaining = deadline - System.currentTimeMillis()
                    if (remaining <= 0) break
                    lock.wait(minOf(remaining, 200L))
                }
            }
            state = State.CLOSED
            received.offer(END_OF_STREAM)
            lock.notifyAll()
        }
        stack.release(this)
    }

    /** Stream views, so SIP parsing can use ordinary readers. */
    fun inputStream(): InputStream = object : InputStream() {
        private var current: ByteArray = ByteArray(0)
        private var offset = 0

        override fun read(): Int {
            val buffer = ByteArray(1)
            return if (read(buffer, 0, 1) == 1) buffer[0].toInt() and 0xff else -1
        }

        override fun read(destination: ByteArray, destinationOffset: Int, length: Int): Int {
            while (offset == current.size) {
                val next = read(READ_TIMEOUT_MS) ?: continue
                if (next.isEmpty()) return -1
                current = next
                offset = 0
            }
            val count = minOf(length, current.size - offset)
            System.arraycopy(current, offset, destination, destinationOffset, count)
            offset += count
            return count
        }

        override fun available(): Int = current.size - offset
    }

    fun outputStream(): OutputStream = object : OutputStream() {
        override fun write(value: Int) = write(byteArrayOf(value.toByte()))

        override fun write(source: ByteArray, sourceOffset: Int, length: Int) {
            this@TcpConnection.write(source.copyOfRange(sourceOffset, sourceOffset + length))
        }
    }

    private fun sequenceOf(segment: ByteArray) = segment.u32(4)
    private fun acknowledgementOf(segment: ByteArray) = segment.u32(8)

    private fun mssOption(segment: ByteArray): Int? {
        val dataOffset = (segment.u8(12) ushr 4) * 4
        var index = 20
        while (index < dataOffset && index + 1 < segment.size) {
            when (segment.u8(index)) {
                0 -> return null
                1 -> index++
                2 -> {
                    if (index + 4 > segment.size) return null
                    return segment.u16(index + 2).coerceAtMost(stack.mtu - 40 - 20)
                }
                else -> {
                    val length = segment.u8(index + 1)
                    if (length < 2) return null
                    index += length
                }
            }
        }
        return null
    }

    private fun initialSequence(): Long = (SecureRandom().nextLong() and 0x7fffffffL)

    private fun flagNames(flags: Int): String {
        val names = ArrayList<String>()
        if (flags and Tcp.SYN != 0) names.add("SYN")
        if (flags and Tcp.ACK != 0) names.add("ACK")
        if (flags and Tcp.PSH != 0) names.add("PSH")
        if (flags and Tcp.FIN != 0) names.add("FIN")
        if (flags and Tcp.RST != 0) names.add("RST")
        return names.joinToString("|")
    }

    companion object {
        private const val TAG = "NetStack"
        private const val DEFAULT_WINDOW = 32768
        private const val DEFAULT_MSS = 536
        private const val RETRANSMIT_MS = 500L
        private const val MAX_RETRANSMITS = 7
        private const val SEND_TIMEOUT_MS = 20_000L
        private const val CLOSE_TIMEOUT_MS = 2_000L
        private const val READ_TIMEOUT_MS = 1_000L
        private const val RECEIVE_QUEUE = 512
        private const val OUT_OF_ORDER_LIMIT = 32
        private val END_OF_STREAM = ByteArray(0)
    }
}

/** Accepts P-CSCF-initiated TCP connections on the UE's protected server port. */
class TcpListener internal constructor(
    private val stack: NetStack,
    val localAddress: ByteArray,
    val localPort: Int,
) : AutoCloseable {
    private val pending = LinkedBlockingQueue<TcpConnection>(8)

    @Volatile
    private var closed = false

    internal fun accept(parsed: IpPacket.Transport, segment: ByteArray) {
        if (closed) return
        val connection = TcpConnection(stack, parsed.destination, parsed.destinationPort, parsed.source, parsed.sourcePort)
        stack.register(connection)
        connection.acceptSyn(segment)
        if (!pending.offer(connection)) {
            runCatching { connection.close() }
        }
    }

    /** Waits up to `timeoutMs` for an established inbound connection. */
    fun accept(timeoutMs: Long): TcpConnection? {
        val connection = pending.poll(timeoutMs, TimeUnit.MILLISECONDS) ?: return null
        if (!connection.awaitEstablished(HANDSHAKE_TIMEOUT_MS)) {
            runCatching { connection.close() }
            return null
        }
        return connection
    }

    override fun close() {
        closed = true
        while (true) {
            val connection = pending.poll() ?: break
            runCatching { connection.close() }
        }
        stack.release(this)
    }

    companion object {
        private const val HANDSHAKE_TIMEOUT_MS = 5_000L
    }
}
