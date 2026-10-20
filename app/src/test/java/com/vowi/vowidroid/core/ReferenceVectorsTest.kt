package com.vowi.vowidroid.core

import com.vowi.vowidroid.core.TestSupport.hex
import com.vowi.vowidroid.core.TestSupport.text
import com.vowi.vowidroid.core.carrier.CarrierProfiles
import com.vowi.vowidroid.core.esp.EspDirection
import com.vowi.vowidroid.core.ike.EapAka
import com.vowi.vowidroid.core.ike.Ike
import com.vowi.vowidroid.core.ike.IkeAuth
import com.vowi.vowidroid.core.ike.IkeChild
import com.vowi.vowidroid.core.ike.IkeCrypto
import com.vowi.vowidroid.core.ike.IkeHeader
import com.vowi.vowidroid.core.ike.IkeOffers
import com.vowi.vowidroid.core.ike.IkeSuite
import com.vowi.vowidroid.core.ike.IkeWire
import com.vowi.vowidroid.core.ike.Payload
import com.vowi.vowidroid.core.ipsec.IpsecSaConfig
import com.vowi.vowidroid.core.ipsec.TransportSecurity
import com.vowi.vowidroid.core.sim.AkaChallenge
import com.vowi.vowidroid.core.sim.AkaProvider
import com.vowi.vowidroid.core.sim.AkaResult
import com.vowi.vowidroid.core.sim.SimIdentity
import com.vowi.vowidroid.core.util.toHex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress

/**
 * Byte-for-byte comparison with known-answer vectors (`reference_vectors.json`)
 * taken from an implementation that registered on T-Mobile.
 */
class ReferenceVectorsTest {
    private val vectors get() = TestSupport.vectors

    private fun suite(name: String): IkeSuite = when (name) {
        "sha1" -> IkeSuite(Ike.ENCRYPTION_AES_CBC, 128, Ike.PRF_HMAC_SHA1, Ike.INTEGRITY_HMAC_SHA1_96, Ike.DH_MODP1024)
        "sha256" -> IkeSuite(Ike.ENCRYPTION_AES_CBC, 256, Ike.PRF_HMAC_SHA256, Ike.INTEGRITY_HMAC_SHA256_128, Ike.DH_MODP2048)
        else -> error("unknown suite $name")
    }

    @Before
    fun setUp() = TestSupport.installCarrierProfiles()

    @Test
    fun fips1862PrfMatchesVectorsAndRfc4186() {
        val vector = vectors.getAsJsonObject("fips1862")
        val output = EapAka.fips1862Prf(vector.hex("seed"), 160)
        assertEquals(vector.text("output"), output.toHex())
        // RFC 4186 section 11.1: K_encr for MK e576d5ca...
        assertEquals("536e5ebc4465582aa6a8ec9986ebb620", output.copyOfRange(0, 16).toHex())
    }

    @Test
    fun akaKeyDerivationMatchesVectors() {
        val vector = vectors.getAsJsonObject("akaKeys")
        val keys = EapAka.deriveKeys(vector.text("identity").toByteArray(Charsets.US_ASCII), vector.hex("ik"), vector.hex("ck"))
        assertEquals(vector.text("kEncr"), keys.kEncr.toHex())
        assertEquals(vector.text("kAut"), keys.kAut.toHex())
        assertEquals(vector.text("msk"), keys.msk.toHex())
        assertEquals(vector.text("emsk"), keys.emsk.toHex())
    }

    @Test
    fun prfPlusMatchesVectors() {
        for (element in vectors.getAsJsonArray("prfPlus")) {
            val vector = element.asJsonObject
            val output = IkeCrypto.prfPlus(suite(vector.text("suite")), vector.hex("key"), vector.hex("seed"), vector.get("length").asInt)
            assertEquals(vector.text("suite"), vector.text("output"), output.toHex())
        }
    }

    @Test
    fun ikeKeyDerivationMatchesVectors() {
        for (element in vectors.getAsJsonArray("ikeKeys")) {
            val vector = element.asJsonObject
            val keys = IkeCrypto.deriveIkeKeys(suite(vector.text("suite")), vector.hex("shared"), vector.hex("ni"), vector.hex("nr"), vector.hex("spii"), vector.hex("spir"))
            assertEquals(vector.text("skd"), keys.skd.toHex())
            assertEquals(vector.text("skai"), keys.skai.toHex())
            assertEquals(vector.text("skar"), keys.skar.toHex())
            assertEquals(vector.text("skei"), keys.skei.toHex())
            assertEquals(vector.text("sker"), keys.sker.toHex())
            assertEquals(vector.text("skpi"), keys.skpi.toHex())
            assertEquals(vector.text("skpr"), keys.skpr.toHex())
        }
    }

    private fun testHeader() = IkeHeader(
        initiatorSpi = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8),
        responderSpi = byteArrayOf(8, 7, 6, 5, 4, 3, 2, 1),
        exchange = Ike.EXCHANGE_AUTH,
        flags = Ike.FLAG_INITIATOR,
        messageId = 7,
    )

    @Test
    fun encryptedPayloadMatchesVectorsAndDecrypts() {
        val vector = vectors.getAsJsonObject("encrypted")
        val inner = listOf(
            Payload(Ike.PAYLOAD_IDI, byteArrayOf(3, 0, 0, 0, 'u'.code.toByte(), '@'.code.toByte(), 'r'.code.toByte())),
            Payload(Ike.PAYLOAD_EAP, byteArrayOf(1, 9, 0, 5, 1)),
            IkeWire.makeNotify(Ike.NOTIFY_INITIAL_CONTACT),
        )
        val suite = suite(vector.text("suite"))
        val packet = IkeCrypto.encryptPayloads(testHeader(), inner, suite, vector.hex("encKey"), vector.hex("intKey"), TestSupport.PatternRandom())
        assertEquals(vector.text("packet"), packet.toHex())
        val (header, payloads) = IkeCrypto.decryptPayloads(packet, suite, vector.hex("encKey"), vector.hex("intKey"))
        assertEquals(7L, header.messageId)
        assertEquals(3, payloads.size)
        assertArrayEquals(inner[0].body, payloads[0].body)
        assertEquals(Ike.NOTIFY_INITIAL_CONTACT, IkeWire.parseNotify(payloads[2]).type)
    }

    @Test
    fun fragmentationMatchesVectorsAndReassembles() {
        val vector = vectors.getAsJsonObject("fragmented")
        val body = TestSupport.repeat(vector.get("bodyByte").asInt, vector.get("bodyLength").asInt)
        val inner = listOf(Payload(Ike.PAYLOAD_EAP, body), IkeWire.makeNotify(Ike.NOTIFY_INITIAL_CONTACT))
        val suite = suite(vector.text("suite"))
        val packets = IkeCrypto.encryptPayloadsFragmented(testHeader(), inner, suite, vector.hex("encKey"), vector.hex("intKey"), 0, TestSupport.PatternRandom())
        val expected = vector.getAsJsonArray("packets").map { it.asString }
        assertEquals(expected.size, packets.size)
        for ((index, packet) in packets.withIndex()) assertEquals("fragment ${index + 1}", expected[index], packet.toHex())
        val (header, payloads) = IkeCrypto.decryptAny(packets.reversed(), suite, vector.hex("encKey"), vector.hex("intKey"))
        assertEquals(Ike.EXCHANGE_AUTH, header.exchange)
        assertEquals(2, payloads.size)
        assertArrayEquals(body, payloads[0].body)
    }

    @Test
    fun natDetectionHashMatchesVectors() {
        val vector = vectors.getAsJsonObject("natHash")
        val hash = IkeCrypto.natDetectionHash(vector.hex("spii"), vector.hex("spir"), InetAddress.getByName(vector.text("ip")), vector.get("port").asInt)
        assertEquals(vector.text("hash"), hash.toHex())
    }

    @Test
    fun espSealMatchesVectorsAndOpens() {
        for (element in vectors.getAsJsonArray("esp")) {
            val vector = element.asJsonObject
            val sealer = EspDirection(vector.get("spi").asLong, vector.hex("encKey"), vector.hex("authKey"), vector.text("encryption"), vector.text("integrity"), TestSupport.PatternRandom())
            val packet = sealer.seal(vector.hex("inner"), 4)
            assertEquals(vector.text("encryption"), vector.text("packet"), packet.toHex())
            val opener = EspDirection(vector.get("spi").asLong, vector.hex("encKey"), vector.hex("authKey"), vector.text("encryption"), vector.text("integrity"))
            assertArrayEquals(vector.hex("inner"), opener.open(packet))
        }
    }

    @Test
    fun ipsec3gppMatchesVectors() {
        for (element in vectors.getAsJsonArray("transport")) {
            val vector = element.asJsonObject
            val security = TransportSecurity(TestSupport.PatternRandom())
            security.install(
                IpsecSaConfig(
                    localIp = byteArrayOf(10, 0, 0, 2),
                    remoteIp = byteArrayOf(10, 0, 0, 1),
                    integrityAlgorithm = vector.text("integrity"),
                    encryptionAlgorithm = vector.text("encryption"),
                    ueClientSpi = 0x1000, ueServerSpi = 0x1001, pcscfClientSpi = 0x2000, pcscfServerSpi = 0x2001,
                    ueClientPort = 5062, ueServerPort = 5063, pcscfClientPort = 5061, pcscfServerPort = 5060,
                    encryptionKey = vector.hex("encKey"),
                    integrityKey = vector.hex("intKey"),
                ),
            )
            val protected = security.protect(vector.hex("outbound"))
            assertEquals(vector.text("encryption"), vector.text("outboundProtected"), protected.toHex())
            val (cleartext, reason) = security.unprotect(vector.hex("inboundProtected"))
            assertEquals("", reason)
            assertArrayEquals(vector.hex("inbound"), cleartext)
            // Plaintext on the protected inbound flow is refused.
            val (dropped, dropReason) = security.unprotect(vector.hex("inbound"))
            assertEquals(null, dropped)
            assertTrue(dropReason.contains("plaintext"))
        }
    }

    @Test
    fun proposalsAndFixedPayloadsMatchVectors() {
        val vector = vectors.getAsJsonObject("proposals")
        assertEquals(vector.text("ikeModern"), IkeWire.marshalProposals(listOf(IkeOffers.ikeOffer(Ike.DH_MODP2048, false))).toHex())
        assertEquals(vector.text("ikeLegacy"), IkeWire.marshalProposals(listOf(IkeOffers.ikeOffer(Ike.DH_MODP1024, true))).toHex())
        val spi = byteArrayOf(0xde.toByte(), 0xad.toByte(), 0xbe.toByte(), 0xef.toByte())
        assertEquals(vector.text("espModern"), IkeWire.marshalProposals(listOf(IkeOffers.espOffer(spi, false))).toHex())
        assertEquals(vector.text("espLegacy"), IkeWire.marshalProposals(listOf(IkeOffers.espOffer(spi, true))).toHex())
        // The vector asked for P-CSCF addresses under the standard IDs only; the private IDs 16384, 16386, 16389
        // and 16390 now follow them, ahead of APPLICATION_VERSION, which stays last as in Android's library.
        val configRequest = vector.text("configRequest")
        val withPrivatePcscf = configRequest.dropLast(8) + "40000000" + "40020000" + "40050000" + "40060000" + configRequest.takeLast(8)
        assertEquals(withPrivatePcscf, IkeChild.configurationRequest().body.toHex())
        assertEquals(vector.text("dualTS"), IkeChild.dualStackTrafficSelectors(Ike.PAYLOAD_TSI).body.toHex())
        assertEquals(vector.text("deviceIdentity"), IkeOffers.deviceIdentityNotify("123456789012345").body.toHex())
        // The proposals parse back to what was offered.
        val parsed = IkeWire.parseProposals(vector.hex("ikeModern"))
        assertEquals(1, parsed.size)
        assertEquals(7, parsed[0].transforms.size)
        assertEquals(Ike.PRF_HMAC_SHA256, parsed[0].transforms[2].id)
    }

    private class FixedAka(private val result: AkaResult) : AkaProvider {
        var calls = 0
        var lastChallenge: AkaChallenge? = null
        override fun authenticate(identity: SimIdentity, challenge: AkaChallenge): AkaResult {
            calls++
            lastChallenge = challenge
            return result
        }
    }

    @Test
    fun eapAkaExchangeMatchesVectors() {
        val vector = vectors.getAsJsonObject("eap")
        val identity = SimIdentity(iccid = "8944100000000000000", imsi = vector.text("imsi"), homeMcc = vector.text("mcc"), homeMnc = vector.text("mnc"))
        val provider = FixedAka(AkaResult(vector.hex("res"), vector.hex("ck"), vector.hex("ik")))
        val client = EapAka.Client(identity, CarrierProfiles.resolve(identity), provider)
        assertEquals(vector.text("nai"), String(client.identity, Charsets.US_ASCII))
        val identityAction = client.handle(vector.hex("identityRequest"))
        assertEquals(vector.text("identityResponse"), identityAction.response!!.toHex())
        val akaIdentityAction = client.handle(vector.hex("akaIdentityRequest"))
        assertEquals(vector.text("akaIdentityResponse"), akaIdentityAction.response!!.toHex())
        val challengeAction = client.handle(vector.hex("challengeRequest"))
        assertEquals(vector.text("challengeResponse"), challengeAction.response!!.toHex())
        assertEquals(1, provider.calls)
        assertArrayEquals(TestSupport.repeat(0xa1, 16), provider.lastChallenge!!.rand)
        assertEquals(vector.text("msk"), client.keys!!.msk.toHex())
        val success = client.handle(EapAka.marshalPacket(com.vowi.vowidroid.core.ike.EapPacket(EapAka.CODE_SUCCESS, 22)))
        assertTrue(success.success)
    }

    @Test
    fun eapInitiatorAuthMatchesVectors() {
        val vector = vectors.getAsJsonObject("eapAuth")
        val eap = vectors.getAsJsonObject("eap")
        val idi = Payload(Ike.PAYLOAD_IDI, vector.hex("idi"))
        val auth = IkeAuth.makeEapInitiatorAuth(eap.hex("msk"), vector.hex("initRequest"), vector.hex("nr"), suite(vector.text("suite")), vector.hex("skpi"), idi)
        assertEquals(vector.text("auth"), auth.body.toHex())
    }

    @Test
    fun carrierProfilesMatchVectors() {
        assertTrue("profiles loaded", CarrierProfiles.count > 600)
        for (element in vectors.getAsJsonArray("profiles")) {
            val vector = element.asJsonObject
            val identity = SimIdentity(iccid = vector.text("iccid"), imsi = vector.text("imsi"), homeMcc = vector.text("mcc"), homeMnc = vector.text("mnc"), gid1 = vector.text("gid1"))
            val profile = CarrierProfiles.resolve(identity)
            val label = "${identity.homeMcc}/${identity.homeMnc}/${identity.gid1}"
            assertEquals(label, vector.text("id"), profile.id)
            assertEquals(label, vector.text("source"), profile.matchSource)
            assertEquals(label, vector.text("epdg"), CarrierProfiles.deriveEpdg(identity))
            assertEquals(label, vector.text("proposal"), profile.ikeProposal)
            assertEquals(label, vector.get("eapOnly").asBoolean, profile.advertiseEapOnly)
            assertEquals(label, vector.text("ipsecEncryption"), profile.imsIpsecEncryption)
            assertEquals(label, vector.text("routeMcc"), profile.routeMcc)
            assertEquals(label, vector.text("routeMnc"), profile.routeMnc)
            assertEquals(label, vector.text("registerProfile"), profile.imsRegisterProfile)
            assertEquals(label, vector.text("contactFormat"), profile.registerOptions.contactFormat)
            assertEquals(label, vector.text("visited"), profile.registerOptions.pVisitedNetworkId)
            assertEquals(label, vector.text("supported"), profile.registerOptions.supportedHeader ?: "")
            assertEquals(label, vector.get("expiry").asInt, profile.registerOptions.expirySeconds)
            assertEquals(label, vector.text("effectiveImsi"), profile.effectiveSubscriberImsi(identity.imsi))
            val vectorCodecs = vector.getAsJsonArray("codecs").map { it.asString }
            // The vectors default to PCMA/PCMU; the profile here leaves that choice to the media layer.
            if (vectorCodecs != listOf("PCMA", "PCMU")) assertEquals(label, vectorCodecs, profile.imsVoiceCodecs)
        }
    }
}
