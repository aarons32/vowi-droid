package com.vowi.vowidroid.core

import com.vowi.vowidroid.core.sip.Digest
import com.vowi.vowidroid.core.sip.SecurityAgreements
import com.vowi.vowidroid.core.sip.SecurityProposal
import com.vowi.vowidroid.core.sip.SipBuilder
import com.vowi.vowidroid.core.sip.SipMessage
import com.vowi.vowidroid.core.util.hexToBytes
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class SipCodecTest {
    @Test
    fun parsesRequestsWithCompactAndFoldedHeaders() {
        val packet = ("MESSAGE sip:+15551234567@ims.mnc260.mcc310.3gppnetwork.org SIP/2.0\r\n" +
            "v: SIP/2.0/TCP 10.0.0.1:5060;branch=z9hG4bKabc\r\n" +
            "f: <sip:smsc@ims.example>;tag=1\r\n" +
            "t: <sip:+15551234567@ims.example>\r\n" +
            "i: call-1\r\n" +
            "CSeq: 7 MESSAGE\r\n" +
            "Contact: <sip:a@b>,\r\n <sip:c@d;x=\"1,2\">\r\n" +
            "c: application/vnd.3gpp.sms\r\n" +
            "l: 4\r\n\r\n\u0001\u0002\u0003\u0004extra").toByteArray(Charsets.ISO_8859_1)
        val message = SipMessage.parse(packet)
        assertTrue(message.isRequest)
        assertEquals("MESSAGE", message.method)
        assertEquals("call-1", message.callId)
        assertEquals(7L to "MESSAGE", message.cseq())
        assertEquals("application/vnd.3gpp.sms", message.value("Content-Type"))
        assertEquals(listOf("<sip:a@b>", "<sip:c@d;x=\"1,2\">"), message.items("Contact"))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), message.body)
        assertEquals("sip:c@d;x=\"1,2\"", SipMessage.firstUri("<sip:c@d;x=\"1,2\">"))
        assertEquals("3600", SipMessage.parameter("<sip:a@b>;expires=3600;q=0.5", "expires"))
        assertNull(SipMessage.parameter("<sip:a@b>;q=0.5", "expires"))
    }

    @Test
    fun streamReadingSplitsConsecutiveMessages() {
        val first = SipBuilder.response(200, "OK").header("Via", "SIP/2.0/TCP x").header("CSeq", "1 REGISTER").build()
        val second = SipBuilder.request("OPTIONS", "sip:x").header("CSeq", "2 OPTIONS").body("text/plain", "hi".toByteArray()).build()
        val stream = ByteArrayInputStream(first + "\r\n\r\n".toByteArray() + second)
        val one = SipMessage.read(stream)!!
        assertEquals(200, one.statusCode)
        assertEquals("OK", one.reason)
        val two = SipMessage.read(stream)!!
        assertEquals("OPTIONS", two.method)
        assertEquals("hi", String(two.body))
        assertNull(SipMessage.read(stream))
    }

    @Test
    fun digestMatchesRfc2617Example() {
        // RFC 2617 3.5 with the password bytes standing in for RES.
        val response = Digest.response("Mufasa", "testrealm@host.com", "Circle Of Life".toByteArray(), "GET", "/dir/index.html", "dcd98b7102dd2f0e8b11d0f600bfb0c093", "00000001", "0a4f113b", "auth")
        assertEquals("6629fae49393a05397450978507c4ef1", response)
        val challenge = Digest.parseChallenge("Digest realm=\"ims.mnc260.mcc310.3gppnetwork.org\", nonce=\"" + java.util.Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() }) + "\", algorithm=AKAv1-MD5, qop=\"auth,auth-int\"", false)
        assertEquals("auth", challenge.qop)
        val aka = Digest.challengeFromNonce(challenge.nonce)
        assertArrayEquals(ByteArray(16) { it.toByte() }, aka.rand)
        assertArrayEquals(ByteArray(16) { (it + 16).toByte() }, aka.autn)
        val authorization = Digest.authorization(challenge, Digest.Credentials("user@realm", "res".toByteArray(), "", "sip:realm", "REGISTER", "abcd", 1))
        assertTrue(authorization.startsWith("Digest username=\"user@realm\", realm=\"ims.mnc260.mcc310.3gppnetwork.org\""))
        assertTrue(authorization.contains("algorithm=AKAv1-MD5, qop=auth, nc=00000001, cnonce=\"abcd\""))
    }

    @Test
    fun securityServerSelectionPrefersOurOfferAndHighestQ() {
        val proposal = SecurityProposal(spiClient = 1000, spiServer = 1001, portClient = 5062, portServer = 5063)
        val header = proposal.headerValue()
        assertTrue(header.startsWith("ipsec-3gpp;q=1.000;alg=hmac-md5-96;prot=esp;mod=trans;ealg=null;spi-c=0000001000;spi-s=0000001001;port-c=5062;port-s=5063"))
        val values = listOf(
            "ipsec-3gpp;q=0.5;alg=hmac-sha-1-96;prot=esp;mod=trans;ealg=aes-cbc;spi-c=2000;spi-s=2001;port-c=6100;port-s=6101",
            "ipsec-3gpp;q=0.9;alg=hmac-md5-96;prot=esp;mod=trans;ealg=null;spi-c=3000;spi-s=3001;port-c=6200;port-s=6201",
            "tls;q=0.1",
        )
        val agreement = SecurityAgreements.parse(values, proposal)
        assertEquals(3000L, agreement.selected.spiClient)
        assertEquals("hmac-md5-96", agreement.selected.algorithm)
        assertEquals("null", agreement.selected.encryption)
        assertEquals(values.joinToString(", "), agreement.verifyValue)
        val (encryptionKey, integrityKey) = SecurityAgreements.expandKeys(ByteArray(16) { 1 }, ByteArray(16) { 2 }, "aes-cbc", "hmac-sha-1-96")
        assertEquals(16, encryptionKey.size)
        assertEquals(20, integrityKey.size)
        assertEquals(0, integrityKey[19].toInt())
        val (none, md5) = SecurityAgreements.expandKeys(ByteArray(16) { 1 }, ByteArray(16) { 2 }, "null", "hmac-md5-96")
        assertEquals(0, none.size)
        assertEquals(16, md5.size)
    }

    private operator fun ByteArray.plus(other: ByteArray): ByteArray = com.vowi.vowidroid.core.util.concat(this, other)

    @Suppress("unused")
    private fun hex(value: String) = value.hexToBytes()
}
