package com.vowi.vowidroid.core

import com.vowi.vowidroid.core.carrier.CarrierProfiles
import com.vowi.vowidroid.core.esp.EspTunnel
import com.vowi.vowidroid.core.ike.ChildSaConfig
import com.vowi.vowidroid.core.ike.DhExchange
import com.vowi.vowidroid.core.ike.EapAka
import com.vowi.vowidroid.core.ike.EapPacket
import com.vowi.vowidroid.core.ike.Ike
import com.vowi.vowidroid.core.ike.IkeAuth
import com.vowi.vowidroid.core.ike.IkeChild
import com.vowi.vowidroid.core.ike.IkeCrypto
import com.vowi.vowidroid.core.ike.IkeHandshake
import com.vowi.vowidroid.core.ike.IkeHeader
import com.vowi.vowidroid.core.ike.IkeSa
import com.vowi.vowidroid.core.ike.IkeSession
import com.vowi.vowidroid.core.ike.IkeTransport
import com.vowi.vowidroid.core.ike.IkeWire
import com.vowi.vowidroid.core.ike.NoProposalChosenException
import com.vowi.vowidroid.core.ike.OfferKind
import com.vowi.vowidroid.core.ike.Payload
import com.vowi.vowidroid.core.ike.Proposal
import com.vowi.vowidroid.core.ike.ProposalSpec
import com.vowi.vowidroid.core.ike.ResponderAuthStatus
import com.vowi.vowidroid.core.ike.TrafficSelector
import com.vowi.vowidroid.core.ike.Transform
import com.vowi.vowidroid.core.ike.Tunnel
import com.vowi.vowidroid.core.ike.TunnelParams
import com.vowi.vowidroid.core.net.IpPacket
import com.vowi.vowidroid.core.sim.AkaChallenge
import com.vowi.vowidroid.core.sim.AkaProvider
import com.vowi.vowidroid.core.sim.AkaResult
import com.vowi.vowidroid.core.sim.SimIdentity
import com.vowi.vowidroid.core.util.Rng
import com.vowi.vowidroid.core.util.concat
import com.vowi.vowidroid.core.util.putU16
import com.vowi.vowidroid.core.util.u16
import com.vowi.vowidroid.core.util.u32
import com.vowi.vowidroid.core.util.u8
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import com.vowi.vowidroid.core.carrier.CarrierProfile
import com.vowi.vowidroid.core.carrier.EpdgCertificate
import com.vowi.vowidroid.core.carrier.IdentityPrivacy
import com.vowi.vowidroid.core.ike.IkeException
import com.vowi.vowidroid.core.ike.ResponderAuthRequiredException
import java.net.DatagramPacket
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.MGF1ParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A fake ePDG on loopback, built from the same primitives, takes the
 * initiator through IKE_SA_INIT, IKE_AUTH with EAP-AKA, the CHILD_SA and
 * then session traffic. It proves the message flow end to end without a
 * carrier. Given a required suite it behaves like a carrier that accepts
 * exactly its iPhone bundle's proposal and nothing else.
 */
class TunnelBringUpTest {
    private val ck = ByteArray(16) { 0x92.toByte() }
    private val ik = ByteArray(16) { 0x93.toByte() }
    private val res = ByteArray(8) { 0x91.toByte() }
    private val identity = SimIdentity(iccid = "8944100000000000000", imsi = "234150123456789", homeMcc = "234", homeMnc = "15")
    private lateinit var epdg: FakeEpdg

    private val sim = object : AkaProvider {
        override fun authenticate(identity: SimIdentity, challenge: AkaChallenge): AkaResult = AkaResult(res, ck, ik)
    }

    @Before
    fun setUp() {
        TestSupport.installCarrierProfiles()
        epdg = FakeEpdg(ck, ik)
        epdg.start()
    }

    @After
    fun tearDown() = epdg.stop()

    /** The resolved profile without the bundle's exact proposal, so today's offers are what goes out. */
    private fun profileWithTodaysOffers() = CarrierProfiles.resolve(identity).copy(ikeExactProposal = null, espExactProposal = null)

    @Test
    fun handshakeSessionAndDataplane() {
        val params = TunnelParams(identity, profileWithTodaysOffers(), "epdg.test", sim, timeoutMs = 5_000)
        val transport = IkeTransport.open(InetAddress.getLoopbackAddress(), encapsulated = true, remotePort = epdg.port)
        val result = IkeHandshake.run(transport, params)
        assertEquals(ResponderAuthStatus.VERIFIED, result.evidence.responderAuth)
        assertEquals("10.0.0.2", result.evidence.localIpv4)
        assertEquals(listOf("10.0.0.1"), result.evidence.pcscf)
        assertEquals("aes-cbc-128", result.evidence.espEncryption)
        assertEquals("modern", result.evidence.ikeOffer)
        assertTrue(result.evidence.encapsulated)
        assertEquals(epdg.childSpi.u32(0), result.child.outboundSpi)
        exerciseSession(result)
    }

    private val sha512Modp3072 = listOf(
        Transform(Ike.TRANSFORM_ENCRYPTION, Ike.ENCRYPTION_AES_CBC, 256),
        Transform(Ike.TRANSFORM_PRF, Ike.PRF_HMAC_SHA512),
        Transform(Ike.TRANSFORM_INTEGRITY, Ike.INTEGRITY_HMAC_SHA512_256),
        Transform(Ike.TRANSFORM_DH, Ike.DH_MODP3072),
    )
    private val sha512Esp = listOf(
        Transform(Ike.TRANSFORM_ENCRYPTION, Ike.ENCRYPTION_AES_CBC, 256),
        Transform(Ike.TRANSFORM_INTEGRITY, Ike.INTEGRITY_HMAC_SHA512_256),
        Transform(Ike.TRANSFORM_ESN, 0),
    )

    /** An ePDG that takes only its bundle's AES-256/SHA2-512/DH 15 proposal: today's offers fail, the exact one registers. */
    @Test
    fun exactSha512Modp3072ProposalIsTheOnlyOneAccepted() {
        epdg.stop()
        epdg = FakeEpdg(ck, ik, requiredIke = sha512Modp3072, requiredEsp = sha512Esp)
        epdg.start()
        val exact = profileWithTodaysOffers().copy(
            ikeExactProposal = ProposalSpec(256, Ike.INTEGRITY_HMAC_SHA512_256, Ike.PRF_HMAC_SHA512, Ike.DH_MODP3072, 86400),
            espExactProposal = ProposalSpec(256, Ike.INTEGRITY_HMAC_SHA512_256, lifetimeSeconds = 86400),
        )
        val params = TunnelParams(identity, exact, "epdg.test", sim, timeoutMs = 5_000)
        assertEquals(listOf(OfferKind.EXACT, OfferKind.MODERN), IkeHandshake.offerChain(params))
        val transport = IkeTransport.open(InetAddress.getLoopbackAddress(), encapsulated = true, remotePort = epdg.port)
        // Neither of today's offers carries the suite, so the ePDG answers NO_PROPOSAL_CHOSEN.
        assertThrows(NoProposalChosenException::class.java) { IkeHandshake.run(transport, params, OfferKind.MODERN) }
        assertThrows(NoProposalChosenException::class.java) { IkeHandshake.run(transport, params, OfferKind.LEGACY) }
        // The bundle's exact proposal goes through on the same socket, as the connector retries it.
        val result = IkeHandshake.run(transport, params)
        assertEquals("exact", result.evidence.ikeOffer)
        assertEquals("aes-cbc-256", result.evidence.ikeEncryption)
        assertEquals("hmac-sha2-512-256", result.evidence.ikeIntegrity)
        assertEquals("modp3072", result.evidence.ikeDhGroup)
        assertEquals("aes-cbc-256", result.evidence.espEncryption)
        assertEquals("hmac-sha2-512-256", result.evidence.espIntegrity)
        assertEquals(ResponderAuthStatus.VERIFIED, result.evidence.responderAuth)
        exerciseSession(result)
    }

    /** An ePDG that rejects the bundle's exact proposal: the chain falls back to today's offer. */
    @Test
    fun rejectedExactProposalFallsBackToTodaysOffer() {
        epdg.stop()
        val modernOnly = listOf(
            Transform(Ike.TRANSFORM_ENCRYPTION, Ike.ENCRYPTION_AES_CBC, 128),
            Transform(Ike.TRANSFORM_PRF, Ike.PRF_HMAC_SHA256),
            Transform(Ike.TRANSFORM_INTEGRITY, Ike.INTEGRITY_HMAC_SHA256_128),
            Transform(Ike.TRANSFORM_DH, Ike.DH_MODP2048),
        )
        epdg = FakeEpdg(ck, ik, requiredIke = modernOnly)
        epdg.start()
        val exact = profileWithTodaysOffers().copy(ikeExactProposal = ProposalSpec(256, Ike.INTEGRITY_HMAC_SHA512_256, Ike.PRF_HMAC_SHA512, Ike.DH_MODP3072))
        val params = TunnelParams(identity, exact, "epdg.test", sim, timeoutMs = 5_000)
        val transport = IkeTransport.open(InetAddress.getLoopbackAddress(), encapsulated = true, remotePort = epdg.port)
        val offers = IkeHandshake.offerChain(params)
        val error = assertThrows(NoProposalChosenException::class.java) { IkeHandshake.run(transport, params, offers[0]) }
        assertTrue(IkeHandshake.retryableWithAnotherOffer(error))
        val result = IkeHandshake.run(transport, params, offers[1])
        assertEquals("modern", result.evidence.ikeOffer)
        assertEquals("aes-cbc-128", result.evidence.ikeEncryption)
        assertEquals("modp2048", result.evidence.ikeDhGroup)
        exerciseSession(result)
    }

    private fun restart(fake: FakeEpdg) {
        epdg.stop()
        epdg = fake
        epdg.start()
    }

    private fun rsaKey(resource: String): PrivateKey = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(TestSupport.resource(resource)))

    private fun privateProfile() = profileWithTodaysOffers().copy(
        privacy = IdentityPrivacy(anonymousIdi = true, encryptedEap = true, certificateDer = TestSupport.resource("test_imsi_cert.der"), keyIdentifier = "CertificateSerialNumber=12345"),
    )

    private fun handshake(profile: CarrierProfile): com.vowi.vowidroid.core.ike.HandshakeResult {
        val transport = IkeTransport.open(InetAddress.getLoopbackAddress(), encapsulated = true, remotePort = epdg.port)
        return IkeHandshake.run(transport, TunnelParams(identity, profile, "epdg.test", sim, timeoutMs = 5_000))
    }

    /** An anonymous IKE identity, the IMSI encrypted in EAP, and a server that keys from the decrypted permanent NAI. */
    @Test
    fun encryptedIdentityWorksWhenTheServerKeysFromThePermanentOne() {
        restart(FakeEpdg(ck, ik, identityKey = rsaKey("test_imsi_key.pkcs8")))
        val result = handshake(privateProfile())
        assertEquals("anonymous+encrypted", result.evidence.identity)
        assertEquals("0" + identity.imsi, epdg.decryptedUsername)
        exerciseSession(result)
    }

    /** The same, with a server that keys from the encrypted identity as sent: the client finds that out from the MAC. */
    @Test
    fun encryptedIdentityWorksWhenTheServerKeysFromWhatWasSent() {
        restart(FakeEpdg(ck, ik, identityKey = rsaKey("test_imsi_key.pkcs8"), serverKeyIdentity = "sent"))
        val result = handshake(privateProfile())
        assertEquals("anonymous+encrypted (keys: encrypted)", result.evidence.identity)
        exerciseSession(result)
    }

    /** The carrier's own CA validates the ePDG's certificate, which no public CA signed. */
    @Test
    fun theCarrierCaValidatesTheEpdgCertificate() {
        restart(FakeEpdg(ck, ik, certificateDer = TestSupport.resource("test_epdg_cert.der"), certificateKey = rsaKey("test_epdg_key.pkcs8")))
        val profile = profileWithTodaysOffers().copy(epdgCertificate = EpdgCertificate(required = true, hostname = "epdg.test", caDer = TestSupport.resource("test_epdg_ca.der")))
        val result = handshake(profile)
        assertEquals(ResponderAuthStatus.VERIFIED, result.evidence.responderAuth)
        exerciseSession(result)
    }

    @Test
    fun theEpdgMustShowACertificateWhenTheCarrierRequiresOne() {
        val profile = profileWithTodaysOffers().copy(epdgCertificate = EpdgCertificate(required = true, hostname = "epdg.test", caDer = TestSupport.resource("test_epdg_ca.der")))
        assertThrows(ResponderAuthRequiredException::class.java) { handshake(profile) }
    }

    @Test
    fun aCertificateForAnotherNameIsRefused() {
        restart(FakeEpdg(ck, ik, certificateDer = TestSupport.resource("test_epdg_cert.der"), certificateKey = rsaKey("test_epdg_key.pkcs8")))
        val profile = profileWithTodaysOffers().copy(epdgCertificate = EpdgCertificate(required = true, hostname = "other.test", caDer = TestSupport.resource("test_epdg_ca.der")))
        val error = assertThrows(IkeException::class.java) { handshake(profile) }
        assertTrue(error.message!!, error.message!!.contains("does not match"))
    }

    /** DPD both ways, a UDP echo through ESP, keepalives and a clean delete. */
    private fun exerciseSession(result: com.vowi.vowidroid.core.ike.HandshakeResult) {
        val failures = ArrayList<Exception>()
        val session = IkeSession(result, keepaliveMs = 500, dpdIdleMs = 60_000)
        val tunnel = Tunnel(session) { failures.add(it) }
        try {
            // Our own dead-peer detection is answered.
            assertEquals(0, session.request(Ike.EXCHANGE_INFORMATIONAL, emptyList(), 3_000).size)
            // A UDP datagram crosses the ESP tunnel and comes back.
            val socket = tunnel.stack.openUdp(5000)
            socket.send("ping".toByteArray(), byteArrayOf(10, 0, 0, 1), 6000)
            val reply = socket.receive(3_000)
            assertNotNull(reply)
            assertEquals("ping", String(reply!!.data))
            assertEquals(6000, reply.sourcePort)
            assertTrue(epdg.sawKeepalive.await(3, TimeUnit.SECONDS))
            // The ePDG's dead-peer detection is answered by the reader.
            assertTrue(epdg.probe(3_000))
        } finally {
            tunnel.close()
        }
        assertTrue(epdg.sawDelete.await(3, TimeUnit.SECONDS))
        assertTrue(failures.isEmpty())
    }

    /** The responder side of SWu, enough for one initiator; `requiredIke`/`requiredEsp` make it accept only that suite. */
    private class FakeEpdg(
        private val ck: ByteArray,
        private val ik: ByteArray,
        private val requiredIke: List<Transform>? = null,
        private val requiredEsp: List<Transform>? = null,
        /** Decrypts an encrypted EAP identity; the keys derive from the permanent NAI, or from what was "sent". */
        private val identityKey: PrivateKey? = null,
        private val serverKeyIdentity: String = "permanent",
        /** Makes the ePDG prove itself with this certificate (DER) and key in the first IKE_AUTH answer. */
        private val certificateDer: ByteArray? = null,
        private val certificateKey: PrivateKey? = null,
    ) {
        private val socket = DatagramSocket(0, InetAddress.getLoopbackAddress())
        val port: Int get() = socket.localPort
        val sawDelete = CountDownLatch(1)
        val sawKeepalive = CountDownLatch(1)
        val childSpi = Rng.nonZero(4)
        private val marker = ByteArray(4)
        private var peer: java.net.SocketAddress? = null
        private var sa: IkeSa? = null
        private var initRequest = ByteArray(0)
        private var initResponse = ByteArray(0)
        private var initiatorNonce = ByteArray(0)
        private var responderNonce = ByteArray(0)
        private var msk = ByteArray(0)
        private var idr = Payload(Ike.PAYLOAD_IDR, concat(byteArrayOf(Ike.ID_FQDN.toByte(), 0, 0, 0), "epdg.test".toByteArray()))
        private var esp: EspTunnel? = null
        private val fragments = HashMap<Long, MutableList<ByteArray>>()
        private var probeId = 0L
        private val probeAnswered = CountDownLatch(1)
        private val thread = Thread(::run).apply { isDaemon = true }

        fun start() = thread.start()
        fun stop() = socket.close()

        /** Sends an empty INFORMATIONAL and waits for the initiator's reply. */
        fun probe(timeoutMs: Long): Boolean {
            val current = sa ?: return false
            probeId = current.nextRequestId++
            send(current.encrypt(current.header(Ike.EXCHANGE_INFORMATIONAL, probeId, response = false), emptyList()))
            return probeAnswered.await(timeoutMs, TimeUnit.MILLISECONDS)
        }

        private fun send(packets: List<ByteArray>) {
            for (packet in packets) {
                val wire = concat(marker, packet)
                socket.send(DatagramPacket(wire, wire.size, peer))
            }
        }

        private fun run() {
            val buffer = ByteArray(65535)
            try {
                while (!socket.isClosed) {
                    val datagram = DatagramPacket(buffer, buffer.size)
                    socket.receive(datagram)
                    peer = datagram.socketAddress
                    val data = buffer.copyOf(datagram.length)
                    if (data.size == 1 && data[0] == 0xff.toByte()) {
                        sawKeepalive.countDown()
                        continue
                    }
                    try {
                        if (data.size >= 4 && data.copyOf(4).contentEquals(marker)) handleIke(data.copyOfRange(4, data.size)) else handleEsp(data)
                    } catch (error: Throwable) {
                        System.err.println("fake ePDG: " + error)
                        error.printStackTrace()
                    }
                }
            } catch (_: Exception) {
            }
        }

        private fun handleIke(packet: ByteArray) {
            val (header, body) = IkeHeader.parse(packet)
            when (header.exchange) {
                Ike.EXCHANGE_SA_INIT -> saInit(packet, header, body)
                else -> {
                    val list = fragments.getOrPut(header.messageId) { ArrayList() }
                    list.add(packet)
                    if (header.nextPayload == Ike.PAYLOAD_ENCRYPTED_FRAGMENT && list.size < body.u16(6)) return
                    fragments.remove(header.messageId)
                    val current = sa!!
                    val (requestHeader, payloads) = current.decrypt(list)
                    if (requestHeader.isResponse) {
                        if (requestHeader.messageId == probeId) probeAnswered.countDown()
                        return
                    }
                    val response = when (requestHeader.exchange) {
                        Ike.EXCHANGE_AUTH -> auth(requestHeader.messageId, payloads)
                        Ike.EXCHANGE_INFORMATIONAL -> {
                            if (payloads.any { it.type == Ike.PAYLOAD_DELETE }) sawDelete.countDown()
                            emptyList()
                        }
                        else -> listOf(IkeWire.makeNotify(Ike.NOTIFY_INVALID_SYNTAX))
                    }
                    send(current.encrypt(current.header(requestHeader.exchange, requestHeader.messageId, response = true), response))
                }
            }
        }

        private fun saInit(packet: ByteArray, header: IkeHeader, body: ByteArray) {
            initRequest = packet
            val payloads = IkeWire.parsePayloadChain(header.nextPayload, body)
            val offered = IkeWire.parseProposals(IkeWire.onePayload(payloads, Ike.PAYLOAD_SA).body)[0]
            val chosen = requiredIke
                ?: listOf(Ike.TRANSFORM_ENCRYPTION, Ike.TRANSFORM_PRF, Ike.TRANSFORM_INTEGRITY, Ike.TRANSFORM_DH).map { type -> offered.transforms.first { it.type == type } }
            if (chosen.any { wanted -> offered.transforms.none { it == wanted } }) {
                val (first, notifyBody) = IkeWire.marshalPayloadChain(listOf(IkeWire.makeNotify(Ike.NOTIFY_NO_PROPOSAL_CHOSEN)))
                send(listOf(IkeHeader(header.initiatorSpi, IkeHeader.ZERO_SPI, first, exchange = Ike.EXCHANGE_SA_INIT, flags = Ike.FLAG_RESPONSE, messageId = 0).marshal(notifyBody)))
                return
            }
            val suite = IkeWire.parseIkeSuite(Proposal(1, Ike.PROTOCOL_IKE, ByteArray(0), chosen))
            val ke = IkeWire.onePayload(payloads, Ike.PAYLOAD_KE)
            val dh = DhExchange(suite.dhId)
            val shared = dh.shared(ke.body.copyOfRange(4, ke.body.size))
            initiatorNonce = IkeWire.onePayload(payloads, Ike.PAYLOAD_NONCE).body
            responderNonce = Rng.bytes(32)
            val responderSpi = Rng.nonZero(8)
            val keys = IkeCrypto.deriveIkeKeys(suite, shared, initiatorNonce, responderNonce, header.initiatorSpi, responderSpi)
            sa = IkeSa(header.initiatorSpi, responderSpi, suite, keys, originalInitiator = false, fragmentation = true)
            val responsePayloads = listOf(
                Payload(Ike.PAYLOAD_SA, IkeWire.marshalProposals(listOf(Proposal(1, Ike.PROTOCOL_IKE, ByteArray(0), chosen)))),
                Payload(Ike.PAYLOAD_KE, concat(byteArrayOf((suite.dhId ushr 8).toByte(), suite.dhId.toByte(), 0, 0), dh.public)),
                Payload(Ike.PAYLOAD_NONCE, responderNonce),
                IkeWire.makeNotify(Ike.NOTIFY_NAT_DETECTION_SOURCE_IP, Rng.bytes(20)),
                IkeWire.makeNotify(Ike.NOTIFY_NAT_DETECTION_DESTINATION_IP, Rng.bytes(20)),
                IkeWire.makeNotify(Ike.NOTIFY_IKEV2_FRAGMENTATION_SUPPORTED),
            )
            val (first, responseBody) = IkeWire.marshalPayloadChain(responsePayloads)
            initResponse = IkeHeader(header.initiatorSpi, responderSpi, first, exchange = Ike.EXCHANGE_SA_INIT, flags = Ike.FLAG_RESPONSE, messageId = 0).marshal(responseBody)
            send(listOf(initResponse))
        }

        private var stage = 0
        private var realm = ""
        var decryptedUsername = ""
            private set

        /** The challenge whose MAC and keys derive from [keyIdentity]. */
        private fun challengeFor(keyIdentity: ByteArray): Payload {
            val akaKeys = EapAka.deriveKeys(keyIdentity, ik, ck)
            msk = akaKeys.msk
            val rand = EapAka.marshalAttribute(EapAka.AT_RAND, concat(ByteArray(2), ByteArray(16) { 0xa1.toByte() }))
            val autn = EapAka.marshalAttribute(EapAka.AT_AUTN, concat(ByteArray(2), ByteArray(16) { 0xa2.toByte() }))
            val mac = EapAka.marshalAttribute(EapAka.AT_MAC, ByteArray(18))
            val data = concat(byteArrayOf(EapAka.SUBTYPE_CHALLENGE.toByte(), 0, 0), rand, autn, mac)
            val request = EapAka.marshalPacket(EapPacket(EapAka.CODE_REQUEST, 21, EapAka.TYPE_AKA, data))
            val macOffset = 5 + 3 + EapAka.oneAttribute(EapAka.parseAttributes(data.copyOfRange(3, data.size)), EapAka.AT_MAC).offset
            System.arraycopy(EapAka.mac(akaKeys.kAut, request), 0, request, macOffset + 4, 16)
            return Payload(Ike.PAYLOAD_EAP, request)
        }

        /** The identity the keys derive from: what was sent, or the permanent NAI decrypted from it. */
        private fun keyIdentity(sent: ByteArray): ByteArray {
            if (sent.isEmpty() || sent[0] != 0.toByte()) return sent
            val key = identityKey ?: throw IllegalStateException("an encrypted identity arrived but the fake has no key")
            val text = String(sent, 1, sent.size - 1, Charsets.US_ASCII)
            val cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")
            cipher.init(Cipher.DECRYPT_MODE, key, OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT))
            decryptedUsername = String(cipher.doFinal(Base64.getDecoder().decode(text.substringBefore(','))), Charsets.US_ASCII)
            return if (serverKeyIdentity == "sent") sent else "$decryptedUsername@$realm".toByteArray(Charsets.US_ASCII)
        }

        private fun auth(messageId: Long, payloads: List<Payload>): List<Payload> {
            val current = sa!!
            return when (stage) {
                0 -> {
                    val idi = IkeWire.onePayload(payloads, Ike.PAYLOAD_IDI)
                    val offeredProposal = IkeWire.parseProposals(IkeWire.onePayload(payloads, Ike.PAYLOAD_SA).body)[0]
                    initiatorChildSpi = offeredProposal.spi.u32(0)
                    offeredChild = offeredProposal.transforms
                    IkeWire.onePayload(payloads, Ike.PAYLOAD_CP)
                    val nai = idi.body.copyOfRange(4, idi.body.size)
                    val naiText = String(nai, Charsets.US_ASCII)
                    val response = ArrayList<Payload>()
                    response += idr
                    val der = certificateDer
                    if (der != null) {
                        // A certificate-authenticated ePDG signs the first answer, as the carriers in section 4 of the study do.
                        response += Payload(Ike.PAYLOAD_CERT, concat(byteArrayOf(4), der))
                        val signed = IkeAuth.responderSignedOctets(initResponse, initiatorNonce, current.suite, current.keys.skpr, idr)
                        val signature = Signature.getInstance("SHA1withRSA").apply { initSign(certificateKey); update(signed) }.sign()
                        response += Payload(Ike.PAYLOAD_AUTH, concat(byteArrayOf(IkeAuth.METHOD_RSA_SIGNATURE.toByte(), 0, 0, 0), signature))
                    }
                    if (naiText.startsWith("anonymous@")) {
                        // The IKE identity names nobody: ask EAP for the real one.
                        realm = naiText.substringAfter('@')
                        stage = 1
                        val data = concat(byteArrayOf(EapAka.SUBTYPE_IDENTITY.toByte(), 0, 0), EapAka.marshalAttribute(EapAka.AT_PERMANENT_ID_REQ, ByteArray(2)))
                        response += Payload(Ike.PAYLOAD_EAP, EapAka.marshalPacket(EapPacket(EapAka.CODE_REQUEST, 20, EapAka.TYPE_AKA, data)))
                    } else {
                        stage = 2
                        response += challengeFor(nai)
                    }
                    response
                }
                1 -> {
                    val identityResponse = EapAka.parsePacket(IkeWire.onePayload(payloads, Ike.PAYLOAD_EAP).body)
                    assertEquals(EapAka.SUBTYPE_IDENTITY, identityResponse.data.u8(0))
                    val attribute = EapAka.oneAttribute(EapAka.parseAttributes(identityResponse.data.copyOfRange(3, identityResponse.data.size)), EapAka.AT_IDENTITY)
                    val sent = attribute.raw.copyOfRange(4, 4 + attribute.raw.u16(2))
                    stage = 2
                    listOf(challengeFor(keyIdentity(sent)))
                }
                2 -> {
                    val response = EapAka.parsePacket(IkeWire.onePayload(payloads, Ike.PAYLOAD_EAP).body)
                    assertEquals(EapAka.SUBTYPE_CHALLENGE, response.data.u8(0))
                    val attributes = EapAka.parseAttributes(response.data.copyOfRange(3, response.data.size))
                    val resAttribute = EapAka.oneAttribute(attributes, EapAka.AT_RES)
                    assertEquals(64, resAttribute.raw.u16(2))
                    stage = 3
                    listOf(Payload(Ike.PAYLOAD_EAP, EapAka.marshalPacket(EapPacket(EapAka.CODE_SUCCESS, 22))))
                }
                else -> {
                    val auth = IkeWire.onePayload(payloads, Ike.PAYLOAD_AUTH)
                    assertEquals(IkeAuth.METHOD_SHARED_KEY_MIC, auth.body.u8(0))
                    val padded = IkeCrypto.prf(current.suite, msk, "Key Pad for IKEv2".toByteArray())
                    val signed = IkeAuth.responderSignedOctets(initResponse, initiatorNonce, current.suite, current.keys.skpr, idr)
                    val responderAuth = concat(byteArrayOf(IkeAuth.METHOD_SHARED_KEY_MIC.toByte(), 0, 0, 0), IkeCrypto.prf(current.suite, padded, signed))
                    val childTransforms = requiredEsp ?: listOf(
                        Transform(Ike.TRANSFORM_ENCRYPTION, Ike.ENCRYPTION_AES_CBC, 128),
                        Transform(Ike.TRANSFORM_INTEGRITY, Ike.INTEGRITY_HMAC_SHA1_96),
                        Transform(Ike.TRANSFORM_ESN, 0),
                    )
                    if (childTransforms.any { wanted -> offeredChild.none { it == wanted } }) {
                        return listOf(IkeWire.makeNotify(Ike.NOTIFY_NO_PROPOSAL_CHOSEN))
                    }
                    val childSuite = IkeChild.parseEspSuite(Proposal(1, Ike.PROTOCOL_ESP, childSpi, childTransforms))
                    val keys = IkeChild.deriveChildSaKeys(current.suite, childSuite, current.keys.skd, initiatorNonce, responderNonce)
                    val ue = byteArrayOf(10, 0, 0, 2)
                    val pcscf = byteArrayOf(10, 0, 0, 1)
                    val any = TrafficSelector(0, 0, 65535, ByteArray(4), ByteArray(4) { 0xff.toByte() })
                    val ueOnly = TrafficSelector(0, 0, 65535, ue, ue)
                    esp = EspTunnel(
                        ChildSaConfig(
                            innerLocalIpv4 = pcscf, innerLocalIpv6 = null, innerIpv6Prefix = 0, pcscf = emptyList(), dns = emptyList(),
                            inboundSpi = childSpi.u32(0), outboundSpi = initiatorChildSpi,
                            encryption = childSuite.encryptionName, integrity = childSuite.integrityName,
                            inboundEncKey = keys.outboundEncryption, inboundAuthKey = keys.outboundIntegrity,
                            outboundEncKey = keys.inboundEncryption, outboundAuthKey = keys.inboundIntegrity,
                            initiatorSelectors = listOf(any), responderSelectors = listOf(ueOnly),
                        ),
                    )
                    val cp = concat(
                        byteArrayOf(IkeChild.CONFIG_REPLY.toByte(), 0, 0, 0),
                        attribute(IkeChild.CONFIG_INTERNAL_IP4_ADDRESS, ue),
                        attribute(IkeChild.CONFIG_P_CSCF_IP4_ADDRESS, pcscf),
                        attribute(IkeChild.CONFIG_INTERNAL_IP4_DNS, byteArrayOf(8, 8, 8, 8)),
                    )
                    listOf(
                        Payload(Ike.PAYLOAD_AUTH, responderAuth),
                        Payload(Ike.PAYLOAD_SA, IkeWire.marshalProposals(listOf(Proposal(1, Ike.PROTOCOL_ESP, childSpi, childTransforms)))),
                        IkeChild.marshalTrafficSelectors(Ike.PAYLOAD_TSI, listOf(ueOnly)),
                        IkeChild.marshalTrafficSelectors(Ike.PAYLOAD_TSR, listOf(any)),
                        Payload(Ike.PAYLOAD_CP, cp),
                    )
                }
            }
        }

        private var initiatorChildSpi = 0L
        private var offeredChild: List<Transform> = emptyList()

        private fun attribute(type: Int, value: ByteArray): ByteArray {
            val out = ByteArray(4 + value.size)
            out.putU16(0, type)
            out.putU16(2, value.size)
            System.arraycopy(value, 0, out, 4, value.size)
            return out
        }

        /** Decrypts an inbound ESP packet and echoes its UDP payload back. */
        private fun handleEsp(packet: ByteArray) {
            val tunnel = esp ?: return
            val inner = tunnel.open(packet)
            val parsed = IpPacket.parseTransport(inner) ?: return
            if (parsed.protocol != IpPacket.UDP) return
            val segment = inner.copyOfRange(parsed.headerLength, inner.size)
            val reply = ByteArray(segment.size)
            System.arraycopy(segment, 0, reply, 0, segment.size)
            reply.putU16(0, parsed.destinationPort)
            reply.putU16(2, parsed.sourcePort)
            reply.putU16(6, 0)
            reply.putU16(6, IpPacket.transportChecksum(parsed.destination, parsed.source, IpPacket.UDP, reply).let { if (it == 0) 0xffff else it })
            val ip = IpPacket.buildIpv4(parsed.destination, parsed.source, IpPacket.UDP, reply, 1)
            val sealed = tunnel.seal(ip)
            socket.send(DatagramPacket(sealed, sealed.size, peer))
        }
    }
}
