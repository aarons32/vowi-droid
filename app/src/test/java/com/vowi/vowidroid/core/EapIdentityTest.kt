package com.vowi.vowidroid.core

import com.vowi.vowidroid.core.carrier.CarrierProfile
import com.vowi.vowidroid.core.carrier.IdentityPrivacy
import com.vowi.vowidroid.core.ike.EapAka
import com.vowi.vowidroid.core.ike.EapIdentities
import com.vowi.vowidroid.core.sim.SimIdentity
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyFactory
import java.security.spec.MGF1ParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource

/** The names a line presents to the ePDG under each privacy setting. */
class EapIdentityTest {
    private val identity = SimIdentity(iccid = "8901260000000000000", imsi = "310260123456789", homeMcc = "310", homeMnc = "260")
    private val certificate = TestSupport.resource("test_imsi_cert.der")
    private val privateKey = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(TestSupport.resource("test_imsi_key.pkcs8")))

    private fun decrypt(ciphertext: ByteArray): String {
        val cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")
        cipher.init(Cipher.DECRYPT_MODE, privateKey, OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT))
        return String(cipher.doFinal(ciphertext), Charsets.US_ASCII)
    }

    @Test
    fun theClearIdentityIsThePermanentNai() {
        val names = EapIdentities.forProfile(identity, CarrierProfile())
        val nai = "0310260123456789@nai.epc.mnc260.mcc310.3gppnetwork.org"
        assertEquals(nai, String(names.idi, Charsets.US_ASCII))
        assertEquals(nai, String(names.eap, Charsets.US_ASCII))
        assertEquals(listOf("permanent"), names.keyCandidates.map { it.first })
        assertEquals("permanent", names.mode)
        assertArrayEquals(EapAka.permanentIdentity(identity, CarrierProfile()), names.eap)
    }

    @Test
    fun theEncryptedIdentityHidesTheImsi() {
        val profile = CarrierProfile(
            privacy = IdentityPrivacy(anonymousIdi = true, encryptedEap = true, certificateDer = certificate, keyIdentifier = "CertificateSerialNumber=12345"),
        )
        val names = EapIdentities.forProfile(identity, profile)
        assertEquals("anonymous@nai.epc.mnc260.mcc310.3gppnetwork.org", String(names.idi, Charsets.US_ASCII))
        assertEquals("anonymous+encrypted", names.mode)
        val sent = String(names.eap, Charsets.US_ASCII)
        assertEquals(0, sent[0].code)
        assertTrue("no IMSI in the clear", !sent.contains("310260123456789"))
        val body = sent.substring(1)
        assertTrue(body.endsWith(",CertificateSerialNumber=12345"))
        val ciphertext = Base64.getDecoder().decode(body.substringBefore(','))
        assertEquals(256, ciphertext.size)
        assertEquals("0310260123456789", decrypt(ciphertext))
        // Each sign-in encrypts afresh, so the network cannot link two of them.
        val again = EapIdentities.forProfile(identity, profile)
        assertTrue(!again.eap.contentEquals(names.eap))
        assertEquals(listOf("permanent", "encrypted", "anonymous", "username"), names.keyCandidates.map { it.first })
        assertArrayEquals(names.eap, names.keyCandidates[1].second)
    }

    @Test
    fun anAnonymousIdiCanKeepThePermanentEapIdentity() {
        val profile = CarrierProfile(privacy = IdentityPrivacy(anonymousIdi = true, realm = "wlan.mnc480.mcc311.3gppnetwork.org"))
        val names = EapIdentities.forProfile(identity, profile)
        assertEquals("anonymous@wlan.mnc480.mcc311.3gppnetwork.org", String(names.idi, Charsets.US_ASCII))
        assertEquals("0310260123456789@nai.epc.mnc260.mcc310.3gppnetwork.org", String(names.eap, Charsets.US_ASCII))
        assertEquals("anonymous", names.mode)
    }

    @Test
    fun theKeyIdentifierIsOptionalAndTheClearFallbackIgnoresPrivacy() {
        val profile = CarrierProfile(privacy = IdentityPrivacy(anonymousIdi = true, encryptedEap = true, certificateDer = certificate))
        val sent = String(EapIdentities.forProfile(identity, profile).eap, Charsets.US_ASCII)
        assertTrue(!sent.contains(','))
        val plain = EapIdentities.forProfile(identity, profile, plainOnly = true)
        assertEquals("permanent", plain.mode)
        assertEquals("0310260123456789@nai.epc.mnc260.mcc310.3gppnetwork.org", String(plain.idi, Charsets.US_ASCII))
    }
}
