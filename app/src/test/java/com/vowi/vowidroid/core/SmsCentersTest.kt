package com.vowi.vowidroid.core

import com.vowi.vowidroid.core.carrier.CarrierProfiles
import com.vowi.vowidroid.core.carrier.SmsCenters
import com.vowi.vowidroid.core.sim.SimIdentity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream

/** The hand-kept SMS centre table and how a line picks the number it sends through. */
class SmsCentersTest {
    @Before
    fun setUp() = TestSupport.installCarrierProfiles()

    @After
    fun tearDown() = TestSupport.installCarrierProfiles()

    private fun tMobile(mnc: String) = SimIdentity(iccid = "8901260000000000000", imsi = "310${mnc}0123456789", homeMcc = "310", homeMnc = mnc)

    @Test
    fun everyEntryIsWellFormedAndSourced() {
        val entries = SmsCenters.all()
        assertTrue("the table is empty", entries.isNotEmpty())
        val seen = HashMap<String, String>()
        for (entry in entries) {
            val where = "${entry.carrier} (${entry.country})"
            assertTrue("$where has no carrier name", entry.carrier.isNotEmpty())
            assertTrue("$where has no country", entry.country.length == 2)
            assertTrue("$where number ${entry.smsCenter} is not an international number", Regex("""\+[1-9][0-9]{6,19}""").matches(entry.smsCenter))
            assertTrue("$where has no source", entry.source.startsWith("https://") || entry.source.startsWith("http://"))
            for (plmn in entry.homePlmns) {
                assertTrue("$where PLMN $plmn is not 5 or 6 digits", Regex("""[0-9]{5,6}""").matches(plmn))
                val canonical = CarrierProfiles.canonicalPlmnValue(plmn)
                // One entry serves a network's carriers at large; an MVNO's entry is keyed by its profile on top.
                for (key in entry.profiles.map { "$canonical|$it" }.ifEmpty { listOf(canonical) }) {
                    val other = seen.put(key, where)
                    assertTrue("PLMN $plmn is in both $other and $where", other == null)
                }
            }
        }
    }

    @Test
    fun tMobileUsResolvesItsCentreThroughTheProfile() {
        for (mnc in listOf("260", "240", "026")) {
            val profile = CarrierProfiles.resolve(tMobile(mnc))
            assertEquals("310$mnc", "+12063130004", profile.smsCenter)
        }
        assertEquals("+13123149810", CarrierProfiles.resolve(SimIdentity("8901410000000000000", "310410123456789", "310", "410")).smsCenter)
        assertEquals("+491710760000", SmsCenters.forPlmn("262", "01"))
    }

    @Test
    fun giffgaffHasItsOwnCentreOnO2sNetwork() {
        val giffgaff = SimIdentity("8944100000000000000", "234100123456789", "234", "10", spn = "giffgaff")
        assertEquals("ipcc-giffgaff-23410", CarrierProfiles.resolve(giffgaff).id)
        assertEquals("+447802002606", CarrierProfiles.resolve(giffgaff).smsCenter)
        // A plain O2 SIM on the same network keeps O2's centre, as does the lookup by network alone.
        assertEquals("+447802000332", CarrierProfiles.resolve(SimIdentity("8944100000000000001", "234100123456780", "234", "10")).smsCenter)
        assertEquals("+447802000332", SmsCenters.forPlmn("234", "10"))
        assertEquals("+447802000332", SmsCenters.forProfile("ipcc-o2-23410", "234", "10"))
    }

    @Test
    fun aNetworkOffTheTableHasNoCentre() {
        assertEquals("", SmsCenters.forPlmn("999", "99"))
        assertEquals("", CarrierProfiles.resolve(SimIdentity("8999990000000000000", "999990123456789", "999", "99")).smsCenter)
        assertEquals("", SmsCenters.forPlmn("", ""))
    }

    @Test
    fun aProfilesOwnCentreOutranksTheTable() {
        CarrierProfiles.install {
            ByteArrayInputStream(
                """{"version": 1, "profiles": [{"id": "test-310260", "match": {"home_plmns": ["310260"]},
                   "ims": {"sms_center": "+15550001111"}}]}""".toByteArray(),
            )
        }
        assertEquals("+15550001111", CarrierProfiles.resolve(tMobile("260")).smsCenter)
    }

    @Test
    fun theUsersEntryWinsThenTheSimsThenTheCarriers() {
        assertEquals("+10000000001", SmsCenters.choose(" +10000000001 ", "+10000000002", "+10000000003"))
        assertEquals("+10000000002", SmsCenters.choose("", "+10000000002", "+10000000003"))
        assertEquals("+10000000003", SmsCenters.choose("", "", "+10000000003"))
        assertEquals("", SmsCenters.choose("", "", ""))
    }

    @Test
    fun aMissingOrOddTableIsEmptyNotFatal() {
        SmsCenters.install { ByteArrayInputStream("[]".toByteArray()) }
        assertTrue(SmsCenters.all().isEmpty())
        SmsCenters.install {
            ByteArrayInputStream(
                """{"entries": [{"carrier": "No number", "home_plmns": ["26201"]}, {"carrier": "No PLMN", "sms_center": "+491710760000"},
                   {"carrier": "Good", "country": "DE", "home_plmns": ["26201"], "sms_center": "+491710760000", "source": "https://example.test"}]}""".toByteArray(),
            )
        }
        assertEquals(listOf("Good"), SmsCenters.all().map { it.carrier })
        SmsCenters.install {
            ByteArrayInputStream(
                """{"entries": [{"carrier": "Host", "country": "DE", "home_plmns": ["26201"], "sms_center": "+491710760000", "source": "https://example.test"},
                   {"carrier": "Guest", "country": "DE", "home_plmns": ["26201"], "profiles": ["ipcc-guest-26201"], "sms_center": "+491710760001", "source": "https://example.test"}]}""".toByteArray(),
            )
        }
        assertEquals("+491710760000", SmsCenters.forPlmn("262", "01"))
        assertEquals("+491710760001", SmsCenters.forProfile("ipcc-guest-26201", "262", "01"))
        assertEquals("+491710760000", SmsCenters.forProfile("ipcc-other-26201", "262", "01"))
    }
}
