package com.vowi.vowidroid.core.net

import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** One received UDP datagram. */
class Datagram(val data: ByteArray, val sourceAddress: ByteArray, val sourcePort: Int)

/** A UDP endpoint inside the tunnel's stack, bound to one local port. */
class UdpSocket internal constructor(
    private val stack: NetStack,
    val localAddress: ByteArray,
    val localPort: Int,
) : AutoCloseable {
    private val queue = LinkedBlockingQueue<Datagram>(QUEUE_CAPACITY)

    @Volatile
    var closed = false
        private set

    fun send(data: ByteArray, remoteAddress: ByteArray, remotePort: Int) {
        if (closed) throw IOException("UDP socket is closed")
        stack.sendUdp(localAddress, localPort, remoteAddress, remotePort, data)
    }

    /** Waits up to `timeoutMs` for a datagram; returns null on timeout. */
    fun receive(timeoutMs: Long): Datagram? {
        if (closed) throw IOException("UDP socket is closed")
        val datagram = queue.poll(timeoutMs, TimeUnit.MILLISECONDS)
        if (datagram === CLOSED) throw IOException("UDP socket is closed")
        return datagram
    }

    internal fun deliver(datagram: Datagram) {
        if (closed) return
        if (!queue.offer(datagram)) {
            // Keep the newest audio/SIP datagrams when a reader falls behind.
            queue.poll()
            queue.offer(datagram)
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        stack.release(this)
        queue.clear()
        queue.offer(CLOSED)
    }

    companion object {
        private const val QUEUE_CAPACITY = 512
        private val CLOSED = Datagram(ByteArray(0), ByteArray(0), 0)
    }
}
