package com.vowi.vowidroid.core

import com.vowi.vowidroid.core.carrier.CarrierProfiles
import com.vowi.vowidroid.core.carrier.TunnelSettings
import com.vowi.vowidroid.core.ike.Ike
import com.vowi.vowidroid.core.ike.ProposalSpec
import com.vowi.vowidroid.core.sim.SimIdentity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/** The exact IKE/ESP proposals and tunnel settings the bundle importer writes into carrier_profiles.json. */
class CarrierProfilesTest {
    private fun install(json: String) = CarrierProfiles.install { ByteArrayInputStream(json.toByteArray(Charsets.UTF_8)) }

    @After
    fun tearDown() = TestSupport.installCarrierProfiles()

    private val identity = SimIdentity(iccid = "8949200000000000000", imsi = "262020123456789", homeMcc = "262", homeMnc = "02")

    private fun document(ike: String, esp: String = "", tunnel: String = "") = """
        {"version": 1, "profiles": [{
          "id": "test-26202",
          "match": {"home_plmns": ["26202"]},
          "epdg": {"hostname": "epdg.example.test"},
          "ike": {"proposal": "modern"$ike}
          ${if (esp.isNotEmpty()) ", \"esp\": {$esp}" else ""}
          ${if (tunnel.isNotEmpty()) ", \"tunnel\": {$tunnel}" else ""}
        }]}
    """.trimIndent()

    @Test
    fun exactProposalsAndTunnelSettingsParse() {
        install(
            document(
                ike = """, "exact_proposal": {"encryption": "aes-cbc", "key_bits": 256, "prf": "hmac-sha2-512", "integrity": "hmac-sha2-512-256", "dh_group": 15, "lifetime_seconds": 86400}""",
                esp = """"exact_proposal": {"encryption": "aes-cbc", "key_bits": 128, "integrity": "hmac-sha2-384-192", "dh_group": 14, "lifetime_seconds": 14400}""",
                tunnel = """"ipsec_mtu": 1350, "natt_keepalive_seconds": 20, "dpd_enabled": false""",
            ),
        )
        val profile = CarrierProfiles.resolve(identity)
        assertEquals("test-26202", profile.id)
        assertEquals(CarrierProfiles.IKE_MODERN, profile.ikeProposal)
        assertEquals(ProposalSpec(256, Ike.INTEGRITY_HMAC_SHA512_256, Ike.PRF_HMAC_SHA512, Ike.DH_MODP3072, 86400), profile.ikeExactProposal)
        assertEquals(ProposalSpec(128, Ike.INTEGRITY_HMAC_SHA384_192, 0, Ike.DH_MODP2048, 14400), profile.espExactProposal)
        assertEquals(TunnelSettings(ipsecMtu = 1350, nattKeepaliveSeconds = 20, dpdEnabled = false), profile.tunnel)
        assertTrue(profile.hasExactProposal)
    }

    @Test
    fun unsupportedExactProposalIsDroppedButTheProfileStays() {
        install(
            document(
                ike = """, "exact_proposal": {"encryption": "aes-cbc", "key_bits": 256, "prf": "hmac-sha2-256", "integrity": "hmac-sha2-256-128", "dh_group": 21}""",
                esp = """"exact_proposal": {"encryption": "aes-gcm", "key_bits": 256, "integrity": "none"}""",
                tunnel = """"ipsec_mtu": 100, "natt_keepalive_seconds": 0, "dpd_enabled": "no"""",
            ),
        )
        val profile = CarrierProfiles.resolve(identity)
        assertEquals("test-26202", profile.id)
        assertEquals("epdg.example.test", profile.epdg)
        assertNull(profile.ikeExactProposal)
        assertNull(profile.espExactProposal)
        assertEquals(TunnelSettings(), profile.tunnel)
        assertFalse(profile.hasExactProposal)
        // A proposal missing its PRF or DH group is not an IKE proposal either.
        install(document(ike = """, "exact_proposal": {"encryption": "aes-cbc", "key_bits": 128, "integrity": "hmac-sha1-96", "dh_group": 2}"""))
        assertNull(CarrierProfiles.resolve(identity).ikeExactProposal)
    }

    @Test
    fun profilesWithoutExactProposalsKeepTheDefaults() {
        install(document(ike = ""))
        val profile = CarrierProfiles.resolve(identity)
        assertNull(profile.ikeExactProposal)
        assertNull(profile.espExactProposal)
        assertEquals(TunnelSettings(), profile.tunnel)
        assertFalse(profile.hasExactProposal)
        assertFalse(profile.legacyIke)
    }

    @Test
    fun importedProfilesCarryTheirBundleProposals() {
        TestSupport.installCarrierProfiles()
        // T-Mobile US MVNO SIM (GID1 DD): the TMobile_MVNO_US bundle.
        val tmobile = CarrierProfiles.resolve(SimIdentity(iccid = "8901260000000000000", imsi = "310260123456789", homeMcc = "310", homeMnc = "260", gid1 = "DD"))
        assertEquals("ipcc-carrier-310026", tmobile.id)
        assertEquals(ProposalSpec(256, Ike.INTEGRITY_HMAC_SHA256_128, Ike.PRF_HMAC_SHA256, Ike.DH_MODP2048, 80000), tmobile.ikeExactProposal)
        assertEquals(ProposalSpec(256, Ike.INTEGRITY_HMAC_SHA256_128, 0, 0, 80000), tmobile.espExactProposal)
        assertEquals(TunnelSettings(dpdEnabled = false), tmobile.tunnel)
        // Vodafone Germany asks for SHA2-512 on both SAs.
        val vodafone = CarrierProfiles.resolve(SimIdentity(iccid = "8949200000000000000", imsi = "262020123456789", homeMcc = "262", homeMnc = "02"))
        assertEquals("ipcc-vodafone-de-26202", vodafone.id)
        assertEquals(ProposalSpec(256, Ike.INTEGRITY_HMAC_SHA512_256, Ike.PRF_HMAC_SHA512, Ike.DH_MODP2048, 86400), vodafone.ikeExactProposal)
        assertEquals(ProposalSpec(256, Ike.INTEGRITY_HMAC_SHA512_256, 0, Ike.DH_MODP2048, 14400), vodafone.espExactProposal)
        // 2degrees NZ still runs DH group 2, with SHA2-512.
        val twoDegrees = CarrierProfiles.resolve(SimIdentity(iccid = "8964240000000000000", imsi = "530240123456789", homeMcc = "530", homeMnc = "24"))
        assertEquals("ipcc-2degrees-53024", twoDegrees.id)
        assertEquals(Ike.DH_MODP1024, twoDegrees.ikeExactProposal?.dhId)
        assertEquals(Ike.PRF_HMAC_SHA512, twoDegrees.ikeExactProposal?.prfId)
        assertTrue(twoDegrees.legacyIke)
        // AT&T: AES-128 and an inner MTU of 1280.
        val att = CarrierProfiles.resolve(SimIdentity(iccid = "8901410000000000000", imsi = "310410123456789", homeMcc = "310", homeMnc = "410"))
        assertEquals(128, att.ikeExactProposal?.encryptionBits)
        assertEquals(1280, att.tunnel.ipsecMtu)
    }
}
