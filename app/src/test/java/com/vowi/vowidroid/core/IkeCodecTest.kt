package com.vowi.vowidroid.core

import com.vowi.vowidroid.core.ike.DhExchange
import com.vowi.vowidroid.core.ike.Ike
import com.vowi.vowidroid.core.ike.IkeChild
import com.vowi.vowidroid.core.ike.IkeCrypto
import com.vowi.vowidroid.core.ike.IkeHeader
import com.vowi.vowidroid.core.ike.IkeIntegrityException
import com.vowi.vowidroid.core.ike.IkeMalformedException
import com.vowi.vowidroid.core.ike.IkeSuite
import com.vowi.vowidroid.core.ike.IkeWire
import com.vowi.vowidroid.core.ike.Payload
import com.vowi.vowidroid.core.ike.TrafficSelector
import com.vowi.vowidroid.core.util.hexToBytes
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class IkeCodecTest {
    private val sha1Suite = IkeSuite(Ike.ENCRYPTION_AES_CBC, 128, Ike.PRF_HMAC_SHA1, Ike.INTEGRITY_HMAC_SHA1_96, Ike.DH_MODP1024)

    @Test
    fun headerRoundTrip() {
        val header = IkeHeader(ByteArray(8) { it.toByte() }, ByteArray(8) { (8 - it).toByte() }, nextPayload = Ike.PAYLOAD_SA, exchange = Ike.EXCHANGE_SA_INIT, flags = Ike.FLAG_INITIATOR, messageId = 0x01020304)
        val packet = header.marshal(byteArrayOf(9, 9, 9))
        assertEquals(31, packet.size)
        val (parsed, body) = IkeHeader.parse(packet)
        assertArrayEquals(header.initiatorSpi, parsed.initiatorSpi)
        assertEquals(0x01020304L, parsed.messageId)
        assertEquals(Ike.EXCHANGE_SA_INIT, parsed.exchange)
        assertEquals(31L, parsed.length)
        assertArrayEquals(byteArrayOf(9, 9, 9), body)
        assertThrows(IkeMalformedException::class.java) { IkeHeader.parse(packet.copyOf(30)) }
    }

    @Test
    fun payloadChainRoundTrip() {
        val payloads = listOf(
            Payload(Ike.PAYLOAD_NONCE, ByteArray(32) { 1 }),
            IkeWire.makeNotify(Ike.NOTIFY_NAT_DETECTION_SOURCE_IP, ByteArray(20) { 2 }),
            IkeWire.makeNotify(Ike.NOTIFY_IKEV2_FRAGMENTATION_SUPPORTED),
            Payload(Ike.PAYLOAD_VENDOR, ByteArray(0), critical = true),
        )
        val (first, encoded) = IkeWire.marshalPayloadChain(payloads)
        assertEquals(Ike.PAYLOAD_NONCE, first)
        val parsed = IkeWire.parsePayloadChain(first, encoded)
        assertEquals(4, parsed.size)
        assertTrue(parsed[3].critical)
        assertEquals(Ike.NOTIFY_NAT_DETECTION_SOURCE_IP, IkeWire.parseNotify(parsed[1]).type)
        assertTrue(IkeWire.hasNotify(parsed, Ike.NOTIFY_IKEV2_FRAGMENTATION_SUPPORTED))
        assertFalse(IkeWire.hasNotify(parsed, Ike.NOTIFY_COOKIE))
        assertThrows(IkeMalformedException::class.java) { IkeWire.parsePayloadChain(first, encoded.copyOf(encoded.size - 1)) }
        assertThrows(IkeMalformedException::class.java) { IkeWire.parsePayloadChain(first, encoded + byteArrayOf(0)) }
    }

    @Test
    fun notifyWithSpiAndDeleteRoundTrip() {
        val notify = IkeWire.makeNotify(Ike.NOTIFY_REKEY_SA, ByteArray(0), Ike.PROTOCOL_ESP, byteArrayOf(1, 2, 3, 4))
        val parsed = IkeWire.parseNotify(notify)
        assertEquals(Ike.PROTOCOL_ESP, parsed.protocol)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), parsed.spi)
        assertEquals(Ike.NOTIFY_REKEY_SA, parsed.type)
        val delete = IkeWire.parseDelete(IkeWire.makeDelete(Ike.PROTOCOL_ESP, listOf(byteArrayOf(5, 6, 7, 8))))
        assertEquals(Ike.PROTOCOL_ESP, delete.protocol)
        assertEquals(1, delete.spis.size)
        val ikeDelete = IkeWire.parseDelete(IkeWire.makeDelete(Ike.PROTOCOL_IKE))
        assertEquals(0, ikeDelete.spis.size)
    }

    @Test
    fun suiteParsingRejectsUnsupportedTransforms() {
        val proposal = IkeWire.parseProposals(IkeWire.marshalProposals(listOf(com.vowi.vowidroid.core.ike.IkeOffers.ikeOffer(Ike.DH_MODP2048, false))))[0]
        // A responder's answer carries one transform per type.
        val chosen = com.vowi.vowidroid.core.ike.Proposal(1, Ike.PROTOCOL_IKE, ByteArray(0), listOf(proposal.transforms[0], proposal.transforms[2], proposal.transforms[4], proposal.transforms[6]))
        val suite = IkeWire.parseIkeSuite(chosen)
        assertEquals(128, suite.encryptionBits)
        assertEquals(Ike.PRF_HMAC_SHA256, suite.prfId)
        assertEquals(Ike.DH_MODP2048, suite.dhId)
        val bad = com.vowi.vowidroid.core.ike.Proposal(1, Ike.PROTOCOL_IKE, ByteArray(0), listOf(com.vowi.vowidroid.core.ike.Transform(Ike.TRANSFORM_ENCRYPTION, 20, 128), proposal.transforms[2], proposal.transforms[4], proposal.transforms[6]))
        assertThrows(com.vowi.vowidroid.core.ike.IkeUnsupportedSuiteException::class.java) { IkeWire.parseIkeSuite(bad) }
    }

    @Test
    fun modp1024AgreesAndIs128Bytes() {
        val first = DhExchange(Ike.DH_MODP1024) { ByteArray(it) { 0x42 } }
        assertEquals(1024, first.primeBitLength)
        assertEquals(128, first.public.size)
        val second = DhExchange(Ike.DH_MODP1024) { ByteArray(it) { 0x24 } }
        val shared = first.shared(second.public)
        assertEquals(128, shared.size)
        assertArrayEquals(shared, second.shared(first.public))
        val third = DhExchange(Ike.DH_MODP2048)
        assertEquals(256, third.public.size)
    }

    @Test
    fun encryptedPayloadTamperIsDetected() {
        val header = IkeHeader(ByteArray(8) { 1 }, ByteArray(8) { 2 }, exchange = Ike.EXCHANGE_INFORMATIONAL, flags = Ike.FLAG_INITIATOR, messageId = 3)
        val encKey = ByteArray(16) { 0x11 }
        val intKey = ByteArray(20) { 0x22 }
        val packet = IkeCrypto.encryptPayloads(header, listOf(IkeWire.makeDelete(Ike.PROTOCOL_IKE)), sha1Suite, encKey, intKey)
        val (_, payloads) = IkeCrypto.decryptPayloads(packet, sha1Suite, encKey, intKey)
        assertEquals(Ike.PAYLOAD_DELETE, payloads.single().type)
        val tampered = packet.copyOf()
        tampered[40] = (tampered[40].toInt() xor 1).toByte()
        assertThrows(IkeIntegrityException::class.java) { IkeCrypto.decryptPayloads(tampered, sha1Suite, encKey, intKey) }
        assertThrows(IkeIntegrityException::class.java) { IkeCrypto.decryptPayloads(packet, sha1Suite, encKey, ByteArray(20) { 0x23 }) }
        // An empty INFORMATIONAL (DPD) encrypts and decrypts to no payloads.
        val empty = IkeCrypto.encryptPayloads(header, emptyList(), sha1Suite, encKey, intKey)
        assertEquals(0, IkeCrypto.decryptPayloads(empty, sha1Suite, encKey, intKey).second.size)
    }

    @Test
    fun configurationReplyParses() {
        val body = ("02000000" + "0001" + "0004" + "0a000002" + "0014" + "0004" + "0a000001" + "0015" + "0010" + "20010db8000000000000000000000001" +
            "0003" + "0004" + "08080808").hexToBytes()
        val configuration = IkeChild.parseConfiguration(Payload(Ike.PAYLOAD_CP, body))
        assertArrayEquals(byteArrayOf(10, 0, 0, 2), configuration.localIpv4)
        assertNull(configuration.localIpv6)
        assertEquals(2, configuration.pcscf.size)
        assertEquals(1, configuration.dns.size)
        val filtered = IkeChild.pcscfForAssignedFamilies(configuration.pcscf, hasIpv4 = true, hasIpv6 = false)
        assertEquals(1, filtered.size)
        assertArrayEquals(byteArrayOf(10, 0, 0, 1), filtered[0])
    }

    @Test
    fun trafficSelectorsRoundTrip() {
        val selectors = listOf(
            TrafficSelector(0, 0, 65535, byteArrayOf(10, 0, 0, 2), byteArrayOf(10, 0, 0, 2)),
            TrafficSelector(17, 5060, 5061, ByteArray(16), ByteArray(16) { 0xff.toByte() }),
        )
        val parsed = IkeChild.parseTrafficSelectors(IkeChild.marshalTrafficSelectors(Ike.PAYLOAD_TSR, selectors))
        assertEquals(2, parsed.size)
        assertEquals(17, parsed[1].ipProtocol)
        assertEquals(5061, parsed[1].endPort)
        assertTrue(IkeChild.ipAllowedBySelectors(byteArrayOf(10, 0, 0, 2), parsed))
        assertFalse(IkeChild.ipAllowedBySelectors(byteArrayOf(10, 0, 0, 3), parsed))
        val dual = IkeChild.parseTrafficSelectors(IkeChild.dualStackTrafficSelectors(Ike.PAYLOAD_TSI))
        assertEquals(2, dual.size)
        assertTrue(IkeChild.ipAllowedBySelectors(byteArrayOf(1, 2, 3, 4), dual))
    }
}

private operator fun ByteArray.plus(other: ByteArray): ByteArray = com.vowi.vowidroid.core.util.concat(this, other)
