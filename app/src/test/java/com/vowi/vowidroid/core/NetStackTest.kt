package com.vowi.vowidroid.core

import com.vowi.vowidroid.core.net.IpPacket
import com.vowi.vowidroid.core.net.NetStack
import com.vowi.vowidroid.core.net.Reassembly
import com.vowi.vowidroid.core.net.TcpConnection
import com.vowi.vowidroid.core.util.putU16
import com.vowi.vowidroid.core.util.u16
import com.vowi.vowidroid.core.util.u8
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Two in-app stacks wired back to back stand in for the UE and the P-CSCF.
 * Packets cross on a single-thread "wire" per direction so delivery is
 * asynchronous, as it is through the tunnel.
 */
class NetStackTest {
    private val ueAddress = byteArrayOf(10, 0, 0, 2)
    private val pcscfAddress = byteArrayOf(10, 0, 0, 1)
    private val toPcscf = Executors.newSingleThreadExecutor()
    private val toUe = Executors.newSingleThreadExecutor()

    /** Drops every n-th packet from the UE while non-zero. */
    @Volatile
    private var dropEvery = 0
    private val sent = AtomicInteger()
    private val captured = ArrayList<ByteArray>()

    private lateinit var pcscf: NetStack
    private val ue: NetStack = NetStack(ueAddress, null) { packet ->
        synchronized(captured) { captured.add(packet) }
        val count = sent.incrementAndGet()
        if (dropEvery != 0 && count % dropEvery == 0) return@NetStack
        toPcscf.execute { pcscf.deliver(packet) }
    }

    init {
        pcscf = NetStack(pcscfAddress, null) { packet -> toUe.execute { ue.deliver(packet) } }
    }

    @After
    fun tearDown() {
        ue.close()
        pcscf.close()
        toPcscf.shutdownNow()
        toUe.shutdownNow()
    }

    @Test
    fun udpDatagramsCrossBothWays() {
        val server = pcscf.openUdp(6000)
        val client = ue.openUdp(5000)
        client.send("hello".toByteArray(), pcscfAddress, 6000)
        val request = server.receive(2000)
        assertNotNull(request)
        assertEquals("hello", String(request!!.data))
        assertEquals(5000, request.sourcePort)
        assertArrayEquals(ueAddress, request.sourceAddress)
        server.send("world".toByteArray(), request.sourceAddress, request.sourcePort)
        assertEquals("world", String(client.receive(2000)!!.data))
        assertNull(client.receive(100))
        // The outbound datagram carried a valid UDP checksum.
        val packet = synchronized(captured) { captured.first() }
        val segment = packet.copyOfRange(20, packet.size)
        assertEquals(0, IpPacket.transportChecksum(ueAddress, pcscfAddress, IpPacket.UDP, segment))
    }

    @Test
    fun tcpConnectsTransfersAndCloses() {
        val listener = pcscf.listenTcp(5060)
        val accepted = CountDownLatch(1)
        var serverSide: TcpConnection? = null
        val serverThread = Thread {
            serverSide = listener.accept(5000)
            accepted.countDown()
        }
        serverThread.start()
        val client = ue.connectTcp(pcscfAddress, 5060)
        assertTrue(accepted.await(5, TimeUnit.SECONDS))
        val server = serverSide!!
        assertTrue(client.established)
        assertTrue(server.established)

        client.write("REGISTER sip:ims SIP/2.0\r\n\r\n".toByteArray())
        assertEquals("REGISTER sip:ims SIP/2.0\r\n\r\n", String(readAll(server, 28)))
        server.write("SIP/2.0 200 OK\r\n\r\n".toByteArray())
        assertEquals("SIP/2.0 200 OK\r\n\r\n", String(readAll(client, 18)))

        // A 20 KB body is segmented at the MSS and arrives in order.
        val large = ByteArray(20_000) { (it % 251).toByte() }
        Thread { client.write(large) }.start()
        assertArrayEquals(large, readAll(server, large.size))

        client.close()
        assertEquals(0, server.read(2000)!!.size) // end of stream
        server.close()
        listener.close()
    }

    @Test
    fun tcpSurvivesPacketLoss() {
        val listener = pcscf.listenTcp(5060)
        val serverRef = arrayOfNulls<TcpConnection>(1)
        val serverThread = Thread { serverRef[0] = listener.accept(5000) }
        serverThread.start()
        val client = ue.connectTcp(pcscfAddress, 5060)
        serverThread.join(5000)
        val server = serverRef[0]!!
        dropEvery = 2
        val body = ByteArray(6000) { (it % 97).toByte() }
        val writer = Thread { client.write(body) }
        writer.start()
        assertArrayEquals(body, readAll(server, body.size, timeoutMs = 15_000))
        dropEvery = 0
        writer.join(5000)
        client.close()
        server.close()
        listener.close()
    }

    @Test
    fun unexpectedSegmentsGetReset() {
        val segment = ByteArray(20)
        segment.putU16(0, 4444)
        segment.putU16(2, 5555)
        segment[12] = 0x50
        segment[13] = 0x10 // ACK, no connection
        val packet = IpPacket.buildIpv4(pcscfAddress, ueAddress, IpPacket.TCP, segment, 1)
        ue.deliver(packet)
        Thread.sleep(100)
        val reply = synchronized(captured) { captured.lastOrNull() }
        assertNotNull(reply)
        assertEquals(IpPacket.TCP, reply!!.u8(9))
        assertEquals(0x14, reply.u8(20 + 13)) // RST|ACK
    }

    @Test
    fun ipv4FragmentsReassembleOutOfOrder() {
        val socket = ue.openUdp(7000)
        val body = ByteArray(3000) { (it % 13).toByte() }
        val segment = ByteArray(8 + body.size)
        segment.putU16(0, 9000)
        segment.putU16(2, 7000)
        segment.putU16(4, segment.size)
        System.arraycopy(body, 0, segment, 8, body.size)
        segment.putU16(6, IpPacket.transportChecksum(pcscfAddress, ueAddress, IpPacket.UDP, segment))
        val fragments = ArrayList<ByteArray>()
        var offset = 0
        while (offset < segment.size) {
            val take = minOf(1200, segment.size - offset)
            val more = offset + take < segment.size
            val packet = IpPacket.buildIpv4(pcscfAddress, ueAddress, IpPacket.UDP, segment.copyOfRange(offset, offset + take), 77, dontFragment = false)
            packet.putU16(6, (if (more) 0x2000 else 0) or (offset / 8))
            packet.putU16(10, 0)
            packet.putU16(10, IpPacket.ipv4Checksum(packet, 20))
            fragments.add(packet)
            offset += take
        }
        assertEquals(3, fragments.size)
        for (fragment in fragments.reversed()) ue.deliver(fragment)
        val datagram = socket.receive(2000)
        assertNotNull(datagram)
        assertArrayEquals(body, datagram!!.data)
        assertEquals(9000, datagram.sourcePort)
    }

    @Test
    fun ipv6FragmentHeaderIsRemoved() {
        val source = ByteArray(16).also { it[0] = 0x20; it[1] = 0x01; it[15] = 1 }
        val destination = ByteArray(16).also { it[0] = 0x20; it[1] = 0x01; it[15] = 2 }
        val payload = "ping".toByteArray()
        val fragmentHeader = ByteArray(8)
        fragmentHeader[0] = IpPacket.UDP.toByte()
        val udp = ByteArray(8 + payload.size)
        udp.putU16(0, 1); udp.putU16(2, 2); udp.putU16(4, udp.size)
        System.arraycopy(payload, 0, udp, 8, payload.size)
        val packet = IpPacket.buildIpv6(source, destination, IpPacket.IPV6_FRAGMENT, fragmentHeader + udp)
        val assembled = Reassembly().accept(packet)
        assertNotNull(assembled)
        assertEquals(IpPacket.UDP, assembled!!.u8(6))
        assertEquals(udp.size, assembled.u16(4))
        assertArrayEquals(udp, assembled.copyOfRange(40, assembled.size))
    }

    @Test
    fun echoRequestsAreAnswered() {
        val echo = ByteArray(16)
        echo[0] = 8
        echo.putU16(4, 0x1234)
        echo.putU16(2, IpPacket.ipv4Checksum(echo, echo.size))
        ue.deliver(IpPacket.buildIpv4(pcscfAddress, ueAddress, IpPacket.ICMP, echo, 5))
        Thread.sleep(100)
        val reply = synchronized(captured) { captured.lastOrNull() }
        assertNotNull(reply)
        assertEquals(IpPacket.ICMP, reply!!.u8(9))
        assertEquals(0, reply.u8(20))
        assertEquals(0x1234, reply.u16(24))
        assertEquals(0, IpPacket.ipv4Checksum(reply.copyOfRange(20, reply.size), reply.size - 20))
    }

    private fun readAll(connection: TcpConnection, length: Int, timeoutMs: Long = 5000): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (out.size() < length && System.currentTimeMillis() < deadline) {
            val chunk = connection.read(200) ?: continue
            if (chunk.isEmpty()) break
            out.write(chunk)
        }
        return out.toByteArray()
    }

    private operator fun ByteArray.plus(other: ByteArray): ByteArray = com.vowi.vowidroid.core.util.concat(this, other)
}
