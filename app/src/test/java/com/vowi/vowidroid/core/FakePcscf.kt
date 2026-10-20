package com.vowi.vowidroid.core

import com.vowi.vowidroid.core.call.Sdp
import com.vowi.vowidroid.core.carrier.CarrierProfiles
import com.vowi.vowidroid.core.ims.ImsConfig
import com.vowi.vowidroid.core.ims.ImsNetwork
import com.vowi.vowidroid.core.ims.ImsSession
import com.vowi.vowidroid.core.ipsec.IpsecSaConfig
import com.vowi.vowidroid.core.ipsec.TransportSecurity
import com.vowi.vowidroid.core.net.NetStack
import com.vowi.vowidroid.core.net.TcpConnection
import com.vowi.vowidroid.core.net.TcpListener
import com.vowi.vowidroid.core.sim.AkaChallenge
import com.vowi.vowidroid.core.sim.AkaProvider
import com.vowi.vowidroid.core.sim.AkaResult
import com.vowi.vowidroid.core.sim.SimIdentity
import com.vowi.vowidroid.core.sip.Digest
import com.vowi.vowidroid.core.sip.SecurityAgreements
import com.vowi.vowidroid.core.sip.SipBuilder
import com.vowi.vowidroid.core.sip.SipMessage
import com.vowi.vowidroid.core.util.concat
import com.vowi.vowidroid.core.util.hexToBytes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * A minimal P-CSCF on its own in-app stack. It challenges REGISTER with
 * AKAv1-MD5 and an ipsec-3gpp offer, accepts the protected re-registration,
 * takes texts, answers the UE's call requests, and sends requests to the
 * UE's protected server port.
 */
internal class FakePcscf(
    private val address: ByteArray,
    private val ueAddress: ByteArray,
    private val ck: ByteArray,
    private val ik: ByteArray,
    private val res: ByteArray,
    transmit: (ByteArray) -> Unit,
) : AutoCloseable {
    val stack = NetStack(address, null, transmit = transmit)
    private val security = TransportSecurity()
    val registers = AtomicInteger()
    @Volatile var sawSecurityVerify = false
    @Volatile var secondRegisterProtected = false
    val sawRpAck = CountDownLatch(1)
    val sawDeregister = CountDownLatch(1)
    val submitted = LinkedBlockingQueue<ByteArray>()

    /** Requests the UE sent on its own connection (INVITE, ACK, BYE, UPDATE, PRACK, SUBSCRIBE, REFER, INFO). */
    val fromUe = LinkedBlockingQueue<SipMessage>()

    /** The UE's responses to requests sent with [sendToUe]. */
    val responsesFromUe = LinkedBlockingQueue<SipMessage>()

    /** How the network answers an INVITE from the UE: a status and an SDP body, or null for none. */
    @Volatile
    var inviteAnswer: (SipMessage) -> Pair<Int, ByteArray?> = { request -> 200 to mirrorSdp(request.body) }

    /** A reliable provisional response (RFC 3262) sent before [inviteAnswer]: a 1xx status and SDP, or null for none. */
    @Volatile
    var inviteProvisional: (SipMessage) -> Pair<Int, ByteArray?>? = { null }

    /** How the network answers SUBSCRIBE and REFER from the UE. */
    @Volatile
    var subscribeAnswer: (SipMessage) -> Int = { 200 }

    @Volatile
    var referAnswer: (SipMessage) -> Int = { 202 }

    private val rand = ByteArray(16) { 0xa1.toByte() }
    private val autn = ByteArray(16) { 0xa2.toByte() }
    private var ueServerPort = 0
    private var protectedServerPort = 0
    private var protectedClientPort = 0
    private var networkLink: TcpConnection? = null
    private val threads = ArrayList<Thread>()
    @Volatile private var closed = false

    init {
        stack.filter = object : NetStack.PacketFilter {
            override fun outbound(packet: ByteArray): ByteArray = security.protect(packet)
            override fun inbound(packet: ByteArray): Pair<ByteArray?, String> = security.unprotect(packet)
        }
    }

    fun start() {
        serve(stack.listenTcp(5060))
    }

    private fun serve(listener: TcpListener) {
        val thread = Thread {
            while (!closed) {
                val connection = try {
                    listener.accept(1000) ?: continue
                } catch (_: Exception) {
                    break
                }
                val worker = Thread {
                    val stream = connection.inputStream()
                    try {
                        while (!closed) {
                            val message = SipMessage.read(stream) ?: break
                            handle(message, connection.localPort) { reply -> connection.write(reply) }
                        }
                    } catch (_: Exception) {
                    }
                }
                worker.isDaemon = true
                worker.start()
                synchronized(threads) { threads.add(worker) }
            }
        }
        thread.isDaemon = true
        thread.start()
        synchronized(threads) { threads.add(thread) }
    }

    private fun handle(request: SipMessage, localPort: Int, respond: (ByteArray) -> Unit) {
        when (request.method) {
            "REGISTER" -> register(request, localPort, respond)
            "MESSAGE" -> {
                val body = request.body
                if (body.isNotEmpty() && body[0].toInt() and 0x07 == 2) sawRpAck.countDown() else submitted.offer(body)
                respond(reply(request, 202, "Accepted"))
            }
            "INVITE" -> {
                fromUe.offer(request)
                val contact = listOf("Contact" to "<sip:+15550001111@10.0.0.1:$protectedServerPort;transport=tcp>")
                inviteProvisional(request)?.let { (status, body) ->
                    respond(reply(request, status, ImsSession.REASONS[status] ?: "Unknown", contact + listOf("Require" to "100rel", "RSeq" to "1"), body))
                }
                val (status, body) = inviteAnswer(request)
                respond(reply(request, status, ImsSession.REASONS[status] ?: "Unknown", if (status in 200..299) contact else emptyList(), body))
            }
            "ACK" -> fromUe.offer(request)
            "BYE", "UPDATE", "PRACK", "CANCEL", "INFO" -> {
                fromUe.offer(request)
                respond(reply(request, 200, "OK"))
            }
            "SUBSCRIBE" -> {
                fromUe.offer(request)
                val status = subscribeAnswer(request)
                val headers = if (status in 200..299) {
                    listOf("Expires" to request.value("Expires").ifEmpty { "3600" }, "Contact" to "<sip:10.0.0.1:$protectedServerPort;transport=tcp>")
                } else {
                    emptyList()
                }
                respond(reply(request, status, ImsSession.REASONS[status] ?: "Unknown", headers))
            }
            "REFER" -> {
                fromUe.offer(request)
                val status = referAnswer(request)
                respond(reply(request, status, if (status == 202) "Accepted" else ImsSession.REASONS[status] ?: "Unknown"))
            }
            else -> respond(reply(request, 405, "Method Not Allowed"))
        }
    }

    private fun reply(request: SipMessage, status: Int, reason: String, extra: List<Pair<String, String>> = emptyList(), body: ByteArray? = null): ByteArray {
        val builder = SipBuilder.response(status, reason)
        for (via in request.values("Via")) builder.header("Via", via)
        var to = request.value("To")
        if (!to.contains(";tag=")) to += ";tag=pcscf"
        builder.header("From", request.value("From")).header("To", to).header("Call-ID", request.callId).header("CSeq", request.value("CSeq"))
        for ((name, value) in extra) builder.header(name, value)
        if (body != null) builder.body("application/sdp", body)
        return builder.build()
    }

    private fun register(request: SipMessage, localPort: Int, respond: (ByteArray) -> Unit) {
        val count = registers.incrementAndGet()
        val expires = request.value("Expires").trim().toIntOrNull() ?: 3600
        if (expires == 0) {
            sawDeregister.countDown()
            respond(reply(request, 200, "OK"))
            return
        }
        val authorization = request.value("Authorization")
        if (count == 1 || !authorization.contains("response=\"") || authorization.contains("response=\"\"")) {
            // Challenge: the nonce carries RAND || AUTN, and the ipsec-3gpp offer is answered.
            val client = request.value("Security-Client")
            assertTrue(client.contains("ipsec-3gpp"))
            val offer = SecurityAgreements.parseMechanism(SipMessage.splitHeaderValues(listOf(client)).first { it.contains("alg=hmac-sha-1-96") && it.contains("ealg=aes-cbc") })
            ueServerPort = offer.portServer
            protectedClientPort = 6100
            protectedServerPort = 6101
            val (encryptionKey, integrityKey) = SecurityAgreements.expandKeys(ck, ik, "aes-cbc", "hmac-sha-1-96")
            // The mirrored SA set: this side's "client" port is the UE's server port.
            security.install(
                IpsecSaConfig(
                    localIp = address, remoteIp = ueAddress, integrityAlgorithm = "hmac-sha-1-96", encryptionAlgorithm = "aes-cbc",
                    // Inbound here is what the UE sends with our SPIs; outbound uses the UE's.
                    ueClientSpi = 20482, ueServerSpi = 20481, pcscfClientSpi = offer.spiServer, pcscfServerSpi = offer.spiClient,
                    ueClientPort = protectedServerPort, ueServerPort = protectedClientPort, pcscfClientPort = offer.portServer, pcscfServerPort = offer.portClient,
                    encryptionKey = encryptionKey, integrityKey = integrityKey,
                ),
            )
            serve(stack.listenTcp(protectedServerPort))
            val nonce = Base64.getEncoder().encodeToString(concat(rand, autn, ByteArray(4)))
            respond(
                reply(
                    request, 401, "Unauthorized",
                    listOf(
                        "WWW-Authenticate" to "Digest realm=\"ims.mnc260.mcc310.3gppnetwork.org\", nonce=\"$nonce\", algorithm=AKAv1-MD5, qop=\"auth\"",
                        "Security-Server" to "ipsec-3gpp;q=0.1;alg=hmac-sha-1-96;prot=esp;mod=trans;ealg=aes-cbc;spi-c=20481;spi-s=20482;port-c=$protectedClientPort;port-s=$protectedServerPort",
                    ),
                ),
            )
            return
        }
        secondRegisterProtected = localPort == protectedServerPort
        sawSecurityVerify = request.value("Security-Verify").contains("ipsec-3gpp")
        val directives = Digest.parseDirectives(authorization.substringAfter("Digest "))
        val expected = Digest.response(directives["username"]!!, directives["realm"]!!, res, "REGISTER", directives["uri"]!!, directives["nonce"]!!, directives["nc"]!!, directives["cnonce"]!!, "auth")
        assertEquals("digest response", expected, directives["response"])
        val contact = request.value("Contact")
        respond(
            reply(
                request, 200, "OK",
                listOf(
                    "Contact" to "$contact;expires=600",
                    "P-Associated-URI" to "<sip:+15551234567@ims.mnc260.mcc310.3gppnetwork.org>, <tel:+15551234567>",
                    "Service-Route" to "<sip:10.0.0.1:$protectedServerPort;transport=tcp;lr>",
                    "Expires" to "600",
                ),
            ),
        )
    }

    /** Sends a request from the protected client port to the UE's protected server port, on one connection. */
    fun sendToUe(request: ByteArray) {
        val connection = synchronized(this) {
            networkLink ?: stack.connectTcp(ueAddress, ueServerPort, protectedClientPort, 5_000).also { connection ->
                networkLink = connection
                val worker = Thread {
                    val stream = connection.inputStream()
                    runCatching {
                        while (!closed) {
                            val message = SipMessage.read(stream) ?: break
                            if (message.isResponse) responsesFromUe.offer(message)
                        }
                    }
                }
                worker.isDaemon = true
                worker.start()
            }
        }
        connection.write(request)
    }

    /** Sends RP-DATA in a MESSAGE to the UE. */
    fun deliverText(tpdu: ByteArray) {
        val rpdu = concat(byteArrayOf(0x01, 0x09, 0x04, 0x91.toByte(), 0x21, 0x43, 0xf5.toByte(), 0x00, tpdu.size.toByte()), tpdu)
        val request = SipBuilder.request("MESSAGE", "sip:310260123456789@ims.mnc260.mcc310.3gppnetwork.org")
            .header("Via", "SIP/2.0/TCP 10.0.0.1:$protectedClientPort;branch=z9hG4bKnet")
            .header("From", "<sip:+12345@ims.mnc260.mcc310.3gppnetwork.org>;tag=n1")
            .header("To", "<sip:310260123456789@ims.mnc260.mcc310.3gppnetwork.org>")
            .header("Call-ID", "net-call-1")
            .header("CSeq", "1 MESSAGE")
            .header("P-Asserted-Identity", "<sip:+12063130004@ims.mnc260.mcc310.3gppnetwork.org>")
            .body("application/vnd.3gpp.sms", rpdu)
            .build()
        sendToUe(request)
    }

    override fun close() {
        closed = true
        stack.close()
    }

    companion object {
        /** A request from the network to the UE inside a dialog, e.g. a NOTIFY or a BYE. */
        fun request(
            method: String,
            callId: String,
            from: String,
            to: String,
            cseq: Int,
            extra: List<Pair<String, String>> = emptyList(),
            contentType: String? = null,
            body: ByteArray? = null,
        ): ByteArray {
            val builder = SipBuilder.request(method, "sip:+15551234567@10.0.0.2")
                .header("Via", "SIP/2.0/TCP 10.0.0.1:6100;branch=z9hG4bK-$method-${callId.hashCode()}-$cseq")
                .header("Max-Forwards", "70")
                .header("From", from)
                .header("To", to)
                .header("Call-ID", callId)
                .header("CSeq", "$cseq $method")
                .header("Contact", "<sip:10.0.0.1:6100;transport=tcp>")
            for ((name, value) in extra) builder.header(name, value)
            if (contentType != null && body != null) builder.body(contentType, body)
            return builder.build()
        }

        /** The far end's audio: PCMU on 10.0.0.1:40000 with DTMF, in the given direction. */
        fun sdp(direction: String, address: String = "10.0.0.1", version: Int = 1, formats: String = "0 101"): ByteArray = listOf(
            "v=0",
            "o=net 7 $version IN IP4 $address",
            "s=-",
            "c=IN IP4 $address",
            "t=0 0",
            "m=audio 40000 RTP/AVP $formats",
            "a=rtpmap:0 PCMU/8000",
            "a=rtpmap:8 PCMA/8000",
            "a=rtpmap:101 telephone-event/8000",
            "a=fmtp:101 0-15",
            "a=ptime:20",
            "a=$direction",
            "",
        ).joinToString("\r\n").toByteArray(Charsets.ISO_8859_1)

        /** An answer that takes the offer's direction from the other side (RFC 3264 §6.1). */
        fun mirrorSdp(offer: ByteArray): ByteArray = sdp(Sdp.parse(offer).direction.reversed.attribute)
    }
}

/** A UE stack and a fake P-CSCF wired back to back, and an IMS session for the UE. */
internal class ImsTestBed : AutoCloseable {
    val identity = SimIdentity(iccid = "8901260123456789012", imsi = "310260123456789", homeMcc = "310", homeMnc = "260", gid1 = "DD", smsc = "+12063130004")
    private val ueAddress = byteArrayOf(10, 0, 0, 2)
    private val pcscfAddress = byteArrayOf(10, 0, 0, 1)
    private val ck = ByteArray(16) { 0x11 }
    private val ik = ByteArray(16) { 0x22 }
    private val res = "0102030405060708".hexToBytes()
    private val toPcscf = Executors.newSingleThreadExecutor()
    private val toUe = Executors.newSingleThreadExecutor()
    val pcscf: FakePcscf
    private val ueStack: NetStack
    val session: ImsSession

    private class UeNetwork(override val stack: NetStack, override val security: TransportSecurity, override val pcscf: List<String>) : ImsNetwork

    init {
        TestSupport.installCarrierProfiles()
        val security = TransportSecurity()
        lateinit var stack: NetStack
        pcscf = FakePcscf(pcscfAddress, ueAddress, ck, ik, res) { packet -> toUe.execute { stack.deliver(packet) } }
        stack = NetStack(ueAddress, null) { packet -> toPcscf.execute { pcscf.stack.deliver(packet) } }
        stack.filter = object : NetStack.PacketFilter {
            override fun outbound(packet: ByteArray): ByteArray = security.protect(packet)
            override fun inbound(packet: ByteArray): Pair<ByteArray?, String> = security.unprotect(packet)
        }
        ueStack = stack
        val sim = object : AkaProvider {
            override fun authenticate(identity: SimIdentity, challenge: AkaChallenge): AkaResult = AkaResult(res, ck, ik)
        }
        session = ImsSession(
            UeNetwork(stack, security, listOf("10.0.0.1")), identity, CarrierProfiles.resolve(identity), sim,
            ImsConfig(transport = "tcp", autoTransportFallback = false, transactionTimeoutMs = 5_000, deviceId = "phone-sim-2"),
        )
    }

    /** Registers over ipsec-3gpp; [listener] gets the network's requests. */
    fun register(listener: ImsSession.Listener) {
        session.listener = listener
        pcscf.start()
        session.connect()
    }

    override fun close() {
        runCatching { session.close() }
        runCatching { ueStack.close() }
        runCatching { pcscf.close() }
        toPcscf.shutdownNow()
        toUe.shutdownNow()
    }
}
