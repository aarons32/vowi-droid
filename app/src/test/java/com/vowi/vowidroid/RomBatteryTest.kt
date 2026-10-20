package com.vowi.vowidroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which ROM layer a phone has, from its maker and brand, and that each has pages to open. */
class RomBatteryTest {
    @Test
    fun makersMapToTheirRoms() {
        assertEquals(RomBattery.Rom.ONE_UI, RomBattery.romFor("samsung", "samsung"))
        assertEquals(RomBattery.Rom.HYPEROS, RomBattery.romFor("Xiaomi", "Xiaomi"))
        assertEquals(RomBattery.Rom.HYPEROS, RomBattery.romFor("Xiaomi", "Redmi"))
        assertEquals(RomBattery.Rom.HYPEROS, RomBattery.romFor("Xiaomi", "POCO"))
        assertEquals(RomBattery.Rom.EMUI, RomBattery.romFor("HUAWEI", "HONOR"))
        assertEquals(RomBattery.Rom.COLOROS, RomBattery.romFor("OnePlus", "OnePlus"))
        assertEquals(RomBattery.Rom.COLOROS, RomBattery.romFor("realme", "realme"))
        assertEquals(RomBattery.Rom.ORIGIN_OS, RomBattery.romFor("vivo", "iQOO"))
    }

    @Test
    fun stockAndroidHasNoRomLayer() {
        assertNull(RomBattery.romFor("Google", "google"))
        assertNull(RomBattery.romFor("Fairphone", "Fairphone"))
        assertNull(RomBattery.romFor("", ""))
    }

    @Test
    fun everyRomHasPagesWithAnActionOrAnActivity() {
        for (rom in RomBattery.Rom.entries) {
            val pages = RomBattery.pages(rom)
            assertTrue("$rom has no page", pages.isNotEmpty())
            for (page in pages) {
                assertTrue("$rom page $page names neither an action nor an activity", page.action != null || (page.pkg != null && page.cls != null))
            }
            assertEquals("$rom lists a page twice", pages.size, pages.toSet().size)
        }
    }
}
