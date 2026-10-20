package com.vowi.vowidroid.core

import com.vowi.vowidroid.core.carrier.CarrierProfiles
import com.vowi.vowidroid.core.sim.SimIdentity
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.Base64

/** The privacy and ePDG certificate blocks of a carrier profile, as the importer writes them. */
class PrivacyProfileTest {
    private val certificate = TestSupport.resource("test_imsi_cert.der")
    private val ca = TestSupport.resource("test_epdg_ca.der")

    @After
    fun restore() = TestSupport.installCarrierProfiles()

    private fun install(json: String) = CarrierProfiles.install { ByteArrayInputStream(json.toByteArray()) }

    @Test
    fun theBlocksAreReadIntoTheProfile() {
        install(
            """{"version": 1, "profiles": [{"id": "test-privacy", "match": {"home_plmns": ["99901"]},
               "privacy": {"idi": "anonymous", "eap": "encrypted", "anonymous_username": "anon", "realm": "@wlan.mnc001.mcc999.3gppnetwork.org",
                           "encryption_certificate": "${Base64.getEncoder().encodeToString(certificate)}", "key_identifier": "CertificateSerialNumber=1", "update_url": "https://keys.example/cert"},
               "epdg": {"hostname": "epdg.test", "certificate": {"required": true, "hostname": "EPDG.test", "authority": "Test CA", "ca": "${Base64.getEncoder().encodeToString(ca)}"}}}]}""",
        )
        val profile = CarrierProfiles.resolve(SimIdentity(iccid = "8999000000000000000", imsi = "999010000000001", homeMcc = "999", homeMnc = "01"))
        assertEquals("test-privacy", profile.id)
        assertTrue(profile.privacy.anonymousIdi)
        assertTrue(profile.privacy.encryptedEap)
        assertTrue(profile.privacy.canEncrypt)
        assertEquals("anon", profile.privacy.anonymousUsername)
        assertEquals("wlan.mnc001.mcc999.3gppnetwork.org", profile.privacy.realm)
        assertArrayEquals(certificate, profile.privacy.certificateDer)
        assertEquals("CertificateSerialNumber=1", profile.privacy.keyIdentifier)
        assertEquals("https://keys.example/cert", profile.privacy.updateUrl)
        assertTrue(profile.epdgCertificate.required)
        assertEquals("epdg.test", profile.epdgCertificate.hostname)
        assertEquals("Test CA", profile.epdgCertificate.authorityName)
        assertArrayEquals(ca, profile.epdgCertificate.caDer)
        assertEquals("epdg.test", profile.epdg)
    }

    @Test
    fun aRefreshedCertificateReplacesTheProfilesOne() {
        install(
            """{"version": 1, "profiles": [{"id": "test-privacy", "match": {"home_plmns": ["99901"]},
               "privacy": {"idi": "anonymous", "eap": "encrypted", "encryption_certificate": "${Base64.getEncoder().encodeToString(certificate)}"}}]}""",
        )
        val fresh = TestSupport.resource("test_epdg_cert.der")
        CarrierProfiles.identityCertificateOverride = { id -> if (id == "test-privacy") fresh else null }
        try {
            val profile = CarrierProfiles.resolve(SimIdentity(iccid = "8999000000000000000", imsi = "999010000000001", homeMcc = "999", homeMnc = "01"))
            assertArrayEquals(fresh, profile.privacy.certificateDer)
        } finally {
            CarrierProfiles.identityCertificateOverride = { null }
        }
    }

    @Test
    fun aProfileWithoutTheBlocksStaysInTheClear() {
        install("""{"version": 1, "profiles": [{"id": "test-plain", "match": {"home_plmns": ["99901"]}}]}""")
        val profile = CarrierProfiles.resolve(SimIdentity(iccid = "8999000000000000000", imsi = "999010000000001", homeMcc = "999", homeMnc = "01"))
        assertFalse(profile.privacy.anonymousIdi)
        assertFalse(profile.privacy.canEncrypt)
        assertNull(profile.privacy.certificateDer)
        assertFalse(profile.epdgCertificate.required)
        assertNull(profile.epdgCertificate.caDer)
    }
}
