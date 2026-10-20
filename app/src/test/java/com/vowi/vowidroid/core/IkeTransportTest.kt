package com.vowi.vowidroid.core

import com.vowi.vowidroid.core.ike.Ike
import com.vowi.vowidroid.core.ike.IkeHeader
import com.vowi.vowidroid.core.ike.IkeTransport
import com.vowi.vowidroid.core.ike.IkeWire
import com.vowi.vowidroid.core.ike.Payload
import com.vowi.vowidroid.core.util.concat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/** A fake ePDG on loopback exercises the marker handling, fragment collection and filtering. */
class IkeTransportTest {
    private val spii = ByteArray(8) { 1 }

    private fun request(messageId: Long = 0) =
        IkeHeader(spii, ByteArray(8), nextPayload = Ike.PAYLOAD_NONCE, exchange = Ike.EXCHANGE_SA_INIT, flags = Ike.FLAG_INITIATOR, messageId = messageId)
            .marshal(IkeWire.marshalPayloadChain(listOf(Payload(Ike.PAYLOAD_NONCE, ByteArray(32)))).second)

    /** Runs a responder that answers each marked request with `replies` built from the request. */
    private fun withResponder(replies: (ByteArray) -> List<ByteArray>, block: (Int) -> Unit) {
        val socket = DatagramSocket(0, InetAddress.getLoopbackAddress())
        val thread = Thread {
            val buffer = ByteArray(65535)
            try {
                while (!socket.isClosed) {
                    val datagram = DatagramPacket(buffer, buffer.size)
                    socket.receive(datagram)
                    val data = buffer.copyOf(datagram.length)
                    if (data.size < 4 || data[0] != 0.toByte() || data[1] != 0.toByte() || data[2] != 0.toByte() || data[3] != 0.toByte()) continue
                    for (reply in replies(data.copyOfRange(4, data.size))) {
                        val wire = concat(ByteArray(4), reply)
                        socket.send(DatagramPacket(wire, wire.size, datagram.socketAddress))
                    }
                }
            } catch (_: Exception) {
            }
        }
        thread.isDaemon = true
        thread.start()
        try {
            block(socket.localPort)
        } finally {
            socket.close()
        }
    }

    private fun responseFor(request: ByteArray, nextPayload: Int = Ike.PAYLOAD_NONCE, body: ByteArray = ByteArray(0)): ByteArray {
        val (header, _) = IkeHeader.parse(request)
        return header.copy(responderSpi = ByteArray(8) { 2 }, flags = Ike.FLAG_RESPONSE, nextPayload = nextPayload).marshal(body)
    }

    @Test
    fun roundTripOnPort4500UsesMarkersAndSkipsForeignPackets() {
        withResponder({ request ->
            val stale = IkeHeader(ByteArray(8) { 9 }, ByteArray(8) { 9 }, exchange = Ike.EXCHANGE_SA_INIT, flags = Ike.FLAG_RESPONSE, messageId = 0).marshal(ByteArray(0))
            listOf(stale, responseFor(request, body = IkeWire.marshalPayloadChain(listOf(Payload(Ike.PAYLOAD_NONCE, ByteArray(16) { 7 }))).second))
        }) { port ->
            val transport = IkeTransport.open(InetAddress.getLoopbackAddress(), encapsulated = true, remotePort = port)
            transport.use {
                assertTrue(it.encapsulated)
                val replies = it.roundTrip(listOf(request()), 3000, longArrayOf(500, 1000)) { header -> header.initiatorSpi.contentEquals(spii) }
                assertEquals(1, replies.size)
                val (header, body) = IkeHeader.parse(replies[0])
                assertTrue(header.isResponse)
                assertEquals(2.toByte(), header.responderSpi[0])
                assertEquals(20, body.size)
            }
        }
    }

    @Test
    fun fragmentsAreCollectedInAnyOrder() {
        withResponder({ request ->
            val (header, _) = IkeHeader.parse(request)
            fun fragment(number: Int, total: Int): ByteArray {
                val body = ByteArray(8 + 32)
                body[0] = 0
                body[2] = 0
                body[3] = body.size.toByte()
                body[4] = 0
                body[5] = number.toByte()
                body[6] = 0
                body[7] = total.toByte()
                return header.copy(responderSpi = ByteArray(8) { 2 }, flags = Ike.FLAG_RESPONSE, nextPayload = Ike.PAYLOAD_ENCRYPTED_FRAGMENT).marshal(body)
            }
            listOf(fragment(3, 3), fragment(1, 3), fragment(2, 3))
        }) { port ->
            IkeTransport.open(InetAddress.getLoopbackAddress(), encapsulated = true, remotePort = port).use { transport ->
                val replies = transport.roundTrip(listOf(request()), 3000, longArrayOf(1000))
                assertEquals(3, replies.size)
                for ((index, reply) in replies.withIndex()) {
                    val (_, body) = IkeHeader.parse(reply)
                    assertEquals(index + 1, body[5].toInt())
                }
            }
        }
    }

    @Test
    fun timeoutIsReportedAfterRetransmits() {
        withResponder({ emptyList() }) { port ->
            IkeTransport.open(InetAddress.getLoopbackAddress(), encapsulated = true, remotePort = port).use { transport ->
                val started = System.currentTimeMillis()
                val error = assertThrows(IOException::class.java) { transport.roundTrip(listOf(request()), 900, longArrayOf(200, 300, 400)) }
                assertTrue(error.message!!.contains("did not answer"))
                assertTrue(System.currentTimeMillis() - started >= 800)
            }
        }
    }

    @Test
    fun port500StartFloatsOnTheSameSocket() {
        withResponder({ request -> listOf(responseFor(request)) }) { port ->
            // The responder only understands marked packets, so a plain start must not reach it.
            IkeTransport.open(InetAddress.getLoopbackAddress(), encapsulated = false, remotePort = port).use { transport ->
                assertTrue(!transport.encapsulated)
                val local = transport.localAddress.port
                transport.float(port)
                assertTrue(transport.encapsulated)
                assertEquals(local, transport.localAddress.port)
                val replies = transport.roundTrip(listOf(request()), 2000, longArrayOf(500))
                assertEquals(1, replies.size)
            }
        }
    }
}
