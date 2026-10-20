package com.vowi.vowidroid.core

import com.vowi.vowidroid.core.carrier.CarrierProfile
import com.vowi.vowidroid.core.carrier.CarrierProfiles
import com.vowi.vowidroid.core.esp.EspAuthenticationException
import com.vowi.vowidroid.core.esp.EspDirection
import com.vowi.vowidroid.core.esp.EspException
import com.vowi.vowidroid.core.ike.DhExchange
import com.vowi.vowidroid.core.ike.Ike
import com.vowi.vowidroid.core.ike.IkeAlgorithms
import com.vowi.vowidroid.core.ike.IkeChild
import com.vowi.vowidroid.core.ike.IkeCrypto
import com.vowi.vowidroid.core.ike.IkeHandshake
import com.vowi.vowidroid.core.ike.IkeHeader
import com.vowi.vowidroid.core.ike.IkeIntegrityException
import com.vowi.vowidroid.core.ike.IkeOffers
import com.vowi.vowidroid.core.ike.IkeSuite
import com.vowi.vowidroid.core.ike.IkeUnsupportedSuiteException
import com.vowi.vowidroid.core.ike.IkeWire
import com.vowi.vowidroid.core.ike.OfferKind
import com.vowi.vowidroid.core.ike.Payload
import com.vowi.vowidroid.core.ike.Proposal
import com.vowi.vowidroid.core.ike.ProposalSpec
import com.vowi.vowidroid.core.ike.Transform
import com.vowi.vowidroid.core.ike.TunnelParams
import com.vowi.vowidroid.core.ike.checksumLength
import com.vowi.vowidroid.core.ike.integrityKeyLength
import com.vowi.vowidroid.core.ike.prfLength
import com.vowi.vowidroid.core.sim.AkaChallenge
import com.vowi.vowidroid.core.sim.AkaProvider
import com.vowi.vowidroid.core.sim.AkaResult
import com.vowi.vowidroid.core.sim.SimIdentity
import com.vowi.vowidroid.core.util.hexToBytes
import com.vowi.vowidroid.core.util.toHex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.security.MessageDigest

/**
 * The algorithms the iPhone carrier bundles ask for beyond SHA-1/SHA-256 and
 * DH 2/14: HMAC-SHA2-384/512 as PRF and integrity (RFC 4868) and the MODP
 * groups 5, 15, 16 and 18 (RFC 3526), plus the exact-proposal offers.
 */
class IkeAlgorithmsTest {
    private val sha384Suite = IkeSuite(Ike.ENCRYPTION_AES_CBC, 256, Ike.PRF_HMAC_SHA384, Ike.INTEGRITY_HMAC_SHA384_192, Ike.DH_MODP4096)
    private val sha512Suite = IkeSuite(Ike.ENCRYPTION_AES_CBC, 256, Ike.PRF_HMAC_SHA512, Ike.INTEGRITY_HMAC_SHA512_256, Ike.DH_MODP3072)

    @Test
    fun hmacSha2VectorsFromRfc4231() {
        // Test cases 1 and 2 of RFC 4231.
        val key1 = ByteArray(20) { 0x0b }
        val data1 = "Hi There".toByteArray()
        val key2 = "Jefe".toByteArray()
        val data2 = "what do ya want for nothing?".toByteArray()
        assertEquals(
            "afd03944d84895626b0825f4ab46907f15f9dadbe4101ec682aa034c7cebc59cfaea9ea9076ede7f4af152e8b2fa9cb6",
            IkeCrypto.prf(sha384Suite, key1, data1).toHex(),
        )
        assertEquals(
            "af45d2e376484031617f78d2b58a6b1b9c7ef464f5a01b47e42ec3736322445e8e2240ca5e69e2c78b3239ecfab21649",
            IkeCrypto.prf(sha384Suite, key2, data2).toHex(),
        )
        assertEquals(
            "87aa7cdea5ef619d4ff0b4241a1d6cb02379f4e2ce4ec2787ad0b30545e17cdedaa833b7d6b8a702038b274eaea3f4e4be9d914eeb61f1702e696c203a126854",
            IkeCrypto.prf(sha512Suite, key1, data1).toHex(),
        )
        assertEquals(
            "164b7a7bfcf819e2e395fbe73b56e0a387bd64222e831fd610270cd7ea2505549758bf75c05a994a6d034f65f8f0e6fdcaeab1a34d4a6b4b636e070a38bce737",
            IkeCrypto.prf(sha512Suite, key2, data2).toHex(),
        )
    }

    @Test
    fun prfPlusWithSha2VariantsMatchesReference() {
        // RFC 7296 2.13 prf+ over HMAC-SHA-512 and -384, computed with an independent implementation.
        val seed = "030a11181f262d343b424950575e656c737a81888f969da4abb2b9c0c7ced5dce3eaf1f8ff060d14".hexToBytes()
        val key512 = ByteArray(64) { (it + 1).toByte() }
        assertEquals(
            "cc6ec622608e213b038a88effd3c15a310c0203f1c6e56347ab27386618300185f13e89513c0f3aa68cd1534015be7ca3e73b1ad12948f78be73fd49d3002aab" +
                "b0f2173102998168bd98551902a109620320c65c32c8c340e790ccc8a24830f5ab3a2efd1ba91d56ddf99c3f410e240c68ed9730072c0088ed4097aa827ff693e" +
                "58510e94ccc09702d24b0ef59cd81c1cea355673561",
            IkeCrypto.prfPlus(sha512Suite, key512, seed, 150).toHex(),
        )
        val key384 = ByteArray(48) { (it + 1).toByte() }
        assertEquals(
            "98168465ad9b95b685e885f9313becfb5ca731238ec0df4edd0b0d4a43654ca6adb8955fb546d76a82bcc6f3642ff580b6b9df353caafa1c63bf3191254e9b0c" +
                "13bf7e97e0ce04dd6e9159df10f28e2b118dee5570bd13887bcdc995d60b2d03c99ff44d",
            IkeCrypto.prfPlus(sha384Suite, key384, seed, 100).toHex(),
        )
    }

    @Test
    fun suiteLengthsFollowRfc4868() {
        assertEquals(48, sha384Suite.prfLength)
        assertEquals(48, sha384Suite.integrityKeyLength)
        assertEquals(24, sha384Suite.checksumLength)
        assertEquals(64, sha512Suite.prfLength)
        assertEquals(64, sha512Suite.integrityKeyLength)
        assertEquals(32, sha512Suite.checksumLength)
        assertEquals("hmac-sha2-384", IkeAlgorithms.prfName(Ike.PRF_HMAC_SHA384))
        assertEquals("hmac-sha2-512-256", IkeAlgorithms.integrityName(Ike.INTEGRITY_HMAC_SHA512_256))
        assertEquals("modp8192", IkeAlgorithms.dhName(Ike.DH_MODP8192))
        assertEquals(Ike.PRF_HMAC_SHA512, IkeAlgorithms.prfId("hmac-sha2-512"))
        assertEquals(Ike.INTEGRITY_HMAC_SHA384_192, IkeAlgorithms.integrityId("HMAC-SHA2-384-192"))
        assertEquals(0, IkeAlgorithms.prfId("aes128-xcbc"))
        // The IKE key schedule grows with the PRF and integrity lengths.
        val keys = IkeCrypto.deriveIkeKeys(sha512Suite, ByteArray(384) { 1 }, ByteArray(32) { 2 }, ByteArray(32) { 3 }, ByteArray(8) { 4 }, ByteArray(8) { 5 })
        assertEquals(64, keys.skd.size)
        assertEquals(64, keys.skai.size)
        assertEquals(32, keys.skei.size)
        assertEquals(64, keys.skpr.size)
    }

    @Test
    fun modpPrimesAreTheRfc3526Groups() {
        // SHA-256 of each prime's big-endian bytes, from the primes derived with the RFC 3526 formula.
        val expected = mapOf(
            Ike.DH_MODP1024 to Triple(1024, "3f35a3f5f6c4376a744acad409bb22f8d897f949d2311d885adaa890981b67a0", true),
            Ike.DH_MODP1536 to Triple(1536, "64fcc83ec403930bf18393dbc883ccaa1fbb08ac876f77f7aa99748ca945019b", true),
            Ike.DH_MODP2048 to Triple(2048, "d66436f79bbd6b2e38c0ffbd079be904d2641415e2e67140e09448be9a60890e", true),
            Ike.DH_MODP3072 to Triple(3072, "48cf8b092fbce4359d9871abf74f98e25b6163379eaa15cd9087e800c6d1c55c", true),
            Ike.DH_MODP4096 to Triple(4096, "4ee95187682bcb230ad26a95205f6920e84708f6251b3894329b09ec23919e33", false),
            Ike.DH_MODP8192 to Triple(8192, "39ab4feab950a3128fb71accb9fc3965d857012e081998a85996e3ea8b3c3bcf", false),
        )
        assertEquals(expected.keys, DhExchange.MODP_PRIMES.keys)
        assertEquals(expected.keys, IkeAlgorithms.dhGroups.map { it.id }.toSet())
        for ((group, want) in expected) {
            val (bits, digest, checkPrimality) = want
            val bytes = DhExchange.MODP_PRIMES.getValue(group).hexToBytes()
            assertEquals("group $group length", bits / 8, bytes.size)
            assertEquals("group $group digest", digest, MessageDigest.getInstance("SHA-256").digest(bytes).toHex())
            // Every RFC 3526 prime starts and ends with 64 one bits.
            assertTrue(bytes.take(8).all { it == 0xff.toByte() } && bytes.takeLast(8).all { it == 0xff.toByte() })
            assertEquals(bits, IkeAlgorithms.dhGroups.first { it.id == group }.bits)
            if (checkPrimality) assertTrue("group $group is prime", BigInteger(1, bytes).isProbablePrime(8))
        }
    }

    @Test
    fun dhAgreementOnTheLargerGroups() {
        for ((group, length) in listOf(Ike.DH_MODP1536 to 192, Ike.DH_MODP3072 to 384, Ike.DH_MODP8192 to 1024)) {
            val first = DhExchange(group) { ByteArray(it) { 0x42 } }
            val second = DhExchange(group) { ByteArray(it) { 0x24 } }
            assertEquals("group $group public length", length, first.public.size)
            val shared = first.shared(second.public)
            assertEquals(length, shared.size)
            assertArrayEquals(shared, second.shared(first.public))
        }
        assertThrows(IkeUnsupportedSuiteException::class.java) { DhExchange(19) }
    }

    private fun header() = IkeHeader(
        initiatorSpi = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8),
        responderSpi = byteArrayOf(8, 7, 6, 5, 4, 3, 2, 1),
        exchange = Ike.EXCHANGE_AUTH,
        flags = Ike.FLAG_INITIATOR,
        messageId = 3,
    )

    @Test
    fun encryptedPayloadsWithSha2VariantsRoundTripAndDetectTampering() {
        val inner = listOf(Payload(Ike.PAYLOAD_EAP, ByteArray(1500) { 0x5a }), IkeWire.makeNotify(Ike.NOTIFY_INITIAL_CONTACT))
        for (suite in listOf(sha384Suite, sha512Suite)) {
            val encryptionKey = ByteArray(32) { 7 }
            val integrityKey = ByteArray(suite.integrityKeyLength) { 9 }
            val packet = IkeCrypto.encryptPayloads(header(), inner, suite, encryptionKey, integrityKey, TestSupport.PatternRandom())
            val (_, decrypted) = IkeCrypto.decryptPayloads(packet, suite, encryptionKey, integrityKey)
            assertEquals(2, decrypted.size)
            assertArrayEquals(inner[0].body, decrypted[0].body)
            val tampered = packet.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
            assertThrows(IkeIntegrityException::class.java) { IkeCrypto.decryptPayloads(tampered, suite, encryptionKey, integrityKey) }
            // The checksum occupies the last 24 or 32 bytes, so the SK length reflects it.
            val skLength = (packet[Ike.HEADER_LENGTH + 2].toInt() and 0xff shl 8) or (packet[Ike.HEADER_LENGTH + 3].toInt() and 0xff)
            assertEquals(packet.size - Ike.HEADER_LENGTH, skLength)
            val fragments = IkeCrypto.encryptPayloadsFragmented(header(), inner, suite, encryptionKey, integrityKey, 600, TestSupport.PatternRandom())
            assertTrue(fragments.size > 1)
            val (reassembledHeader, reassembled) = IkeCrypto.decryptAny(fragments.reversed(), suite, encryptionKey, integrityKey)
            assertEquals(3L, reassembledHeader.messageId)
            assertArrayEquals(inner[0].body, reassembled[0].body)
        }
    }

    @Test
    fun espWithSha2VariantsSealsOpensAndRejectsTampering() {
        val inner = "45000020000100004011000a0a0000020a00000113881389000c0000deadbeef".hexToBytes()
        for ((integrity, keyLength, icvLength) in listOf(Triple("hmac-sha2-384-192", 48, 24), Triple("hmac-sha2-512-256", 64, 32))) {
            val encryptionKey = ByteArray(32) { 3 }
            val authKey = ByteArray(keyLength) { 4 }
            val sealer = EspDirection(0x1234, encryptionKey, authKey, "aes-cbc-256", integrity, TestSupport.PatternRandom())
            val packet = sealer.seal(inner, 4)
            // SPI, sequence, IV, 48 bytes of padded plaintext, then the ICV.
            assertEquals(8 + 16 + 48 + icvLength, packet.size)
            val opener = EspDirection(0x1234, encryptionKey, authKey, "aes-cbc-256", integrity)
            assertArrayEquals(inner, opener.open(packet))
            val tampered = packet.copyOf().also { it[20] = (it[20].toInt() xor 0x80).toByte() }
            assertThrows(EspAuthenticationException::class.java) { EspDirection(0x1234, encryptionKey, authKey, "aes-cbc-256", integrity).open(tampered) }
            assertThrows(EspException::class.java) { EspDirection(0x1234, encryptionKey, ByteArray(32), "aes-cbc-256", integrity) }
        }
        assertThrows(EspException::class.java) { EspDirection(0x1234, ByteArray(32), ByteArray(64), "aes-cbc-256", "hmac-sha2-512") }
    }

    @Test
    fun espSuiteAcceptsSha2VariantsAndSizesTheChildKeys() {
        val spi = byteArrayOf(1, 2, 3, 4)
        val suite = IkeChild.parseEspSuite(
            Proposal(1, Ike.PROTOCOL_ESP, spi, listOf(Transform(Ike.TRANSFORM_ENCRYPTION, Ike.ENCRYPTION_AES_CBC, 256), Transform(Ike.TRANSFORM_INTEGRITY, Ike.INTEGRITY_HMAC_SHA384_192), Transform(Ike.TRANSFORM_ESN, 0))),
        )
        assertEquals(48, suite.integrityKeyLength)
        assertEquals("hmac-sha2-384-192", suite.integrityName)
        val keys = IkeChild.deriveChildSaKeys(sha512Suite, suite, ByteArray(64) { 1 }, ByteArray(32) { 2 }, ByteArray(32) { 3 })
        assertEquals(32, keys.outboundEncryption.size)
        assertEquals(48, keys.outboundIntegrity.size)
        assertEquals(48, keys.inboundIntegrity.size)
        assertThrows(IkeUnsupportedSuiteException::class.java) {
            IkeChild.parseEspSuite(Proposal(1, Ike.PROTOCOL_ESP, spi, listOf(Transform(Ike.TRANSFORM_ENCRYPTION, Ike.ENCRYPTION_AES_CBC, 256), Transform(Ike.TRANSFORM_INTEGRITY, 5), Transform(Ike.TRANSFORM_ESN, 0))))
        }
    }

    @Test
    fun ikeSuiteParsingAcceptsTheNewIdsAndRejectsOthers() {
        fun proposal(prf: Int, integrity: Int, dh: Int) = Proposal(
            1, Ike.PROTOCOL_IKE, ByteArray(0),
            listOf(Transform(Ike.TRANSFORM_ENCRYPTION, Ike.ENCRYPTION_AES_CBC, 256), Transform(Ike.TRANSFORM_PRF, prf), Transform(Ike.TRANSFORM_INTEGRITY, integrity), Transform(Ike.TRANSFORM_DH, dh)),
        )
        val suite = IkeWire.parseIkeSuite(proposal(Ike.PRF_HMAC_SHA512, Ike.INTEGRITY_HMAC_SHA512_256, Ike.DH_MODP3072))
        assertEquals(sha512Suite, suite)
        IkeWire.parseIkeSuite(proposal(Ike.PRF_HMAC_SHA384, Ike.INTEGRITY_HMAC_SHA384_192, Ike.DH_MODP8192))
        IkeWire.parseIkeSuite(proposal(Ike.PRF_HMAC_SHA256, Ike.INTEGRITY_HMAC_SHA256_128, Ike.DH_MODP1536))
        assertThrows(IkeUnsupportedSuiteException::class.java) { IkeWire.parseIkeSuite(proposal(4, Ike.INTEGRITY_HMAC_SHA512_256, Ike.DH_MODP3072)) }
        assertThrows(IkeUnsupportedSuiteException::class.java) { IkeWire.parseIkeSuite(proposal(Ike.PRF_HMAC_SHA512, 15, Ike.DH_MODP3072)) }
        assertThrows(IkeUnsupportedSuiteException::class.java) { IkeWire.parseIkeSuite(proposal(Ike.PRF_HMAC_SHA512, Ike.INTEGRITY_HMAC_SHA512_256, 19)) }
    }

    @Test
    fun exactOffersCarryOneTransformPerType() {
        val ike = ProposalSpec(256, Ike.INTEGRITY_HMAC_SHA512_256, Ike.PRF_HMAC_SHA512, Ike.DH_MODP3072, 86400)
        assertTrue(ike.supported)
        assertEquals("aes-cbc-256/hmac-sha2-512/hmac-sha2-512-256/modp3072", ike.toString())
        assertEquals(
            "0000002c010100040300000c0100000c800e01000300000802000007030000080300000e000000080400000f",
            IkeWire.marshalProposals(listOf(IkeOffers.ikeOffer(ike))).toHex(),
        )
        val esp = ProposalSpec(256, Ike.INTEGRITY_HMAC_SHA512_256, dhId = Ike.DH_MODP2048, lifetimeSeconds = 80000)
        val spi = "deadbeef".hexToBytes()
        val encoded = IkeWire.marshalProposals(listOf(IkeOffers.espOffer(spi, esp)))
        assertEquals("0000002801030403deadbeef0300000c0100000c800e0100030000080300000e0000000805000000", encoded.toHex())
        // The first CHILD_SA is made in IKE_AUTH, so the PFS group stays out of the offer.
        val parsed = IkeWire.parseProposals(encoded).single()
        assertEquals(listOf(Ike.TRANSFORM_ENCRYPTION, Ike.TRANSFORM_INTEGRITY, Ike.TRANSFORM_ESN), parsed.transforms.map { it.type })
        assertEquals(suiteOf(parsed), IkeChild.parseEspSuite(parsed))
        assertFalse(ProposalSpec(192, Ike.INTEGRITY_HMAC_SHA512_256).supported)
        assertFalse(ProposalSpec(256, Ike.INTEGRITY_HMAC_SHA512_256, prfId = 4).supported)
        assertFalse(ProposalSpec(256, Ike.INTEGRITY_HMAC_SHA512_256, dhId = 21).supported)
        assertThrows(IllegalArgumentException::class.java) { IkeOffers.ikeOffer(esp) }
    }

    private fun suiteOf(proposal: Proposal) = IkeChild.parseEspSuite(proposal)

    private val noSim = object : AkaProvider {
        override fun authenticate(identity: SimIdentity, challenge: AkaChallenge): AkaResult = error("unused")
    }

    @Test
    fun offerChainFollowsTheProfile() {
        val identity = SimIdentity(iccid = "8944100000000000000", imsi = "234150123456789", homeMcc = "234", homeMnc = "15")
        val exact = ProposalSpec(256, Ike.INTEGRITY_HMAC_SHA256_128, Ike.PRF_HMAC_SHA256, Ike.DH_MODP2048)
        fun chain(profile: CarrierProfile, forceLegacy: Boolean = false) =
            IkeHandshake.offerChain(TunnelParams(identity, profile, "epdg.test", noSim, forceLegacy = forceLegacy))
        assertEquals(listOf(OfferKind.MODERN, OfferKind.LEGACY), chain(CarrierProfile()))
        assertEquals(listOf(OfferKind.MODERN), chain(CarrierProfile(id = "carrier")))
        assertEquals(listOf(OfferKind.LEGACY), chain(CarrierProfile(id = "carrier", ikeProposal = CarrierProfiles.IKE_LEGACY)))
        assertEquals(listOf(OfferKind.EXACT, OfferKind.MODERN), chain(CarrierProfile(id = "carrier", ikeExactProposal = exact)))
        assertEquals(listOf(OfferKind.EXACT, OfferKind.MODERN), chain(CarrierProfile(id = "carrier", espExactProposal = ProposalSpec(256, Ike.INTEGRITY_HMAC_SHA256_128))))
        assertEquals(listOf(OfferKind.EXACT, OfferKind.LEGACY), chain(CarrierProfile(id = "carrier", ikeProposal = CarrierProfiles.IKE_LEGACY, ikeExactProposal = exact)))
        assertEquals(listOf(OfferKind.LEGACY), chain(CarrierProfile(id = "carrier", ikeExactProposal = exact), forceLegacy = true))
    }
}
