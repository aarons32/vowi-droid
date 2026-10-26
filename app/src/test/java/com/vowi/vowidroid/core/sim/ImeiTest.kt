package com.vowi.vowidroid.core.sim

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class ImeiTest {
    @Test
    fun theCheckDigitIsLuhnOverTheFirstFourteen() {
        // The worked example in common references: 49015420323751 gets check digit 8.
        assertEquals('8', Imei.checkDigit("49015420323751"))
        assertTrue(Imei.checkDigitMatches("490154203237518"))
        assertFalse(Imei.checkDigitMatches("490154203237519"))
    }

    @Test
    fun onlyFifteenDigitsAreWellFormed() {
        assertTrue(Imei.isWellFormed("490154203237518"))
        assertFalse(Imei.isWellFormed("49015420323751"))
        assertFalse(Imei.isWellFormed("4901542032375180"))
        assertFalse(Imei.isWellFormed("49015420323751a"))
        assertEquals("490154203237518", Imei.normalize("49-015420-323751-8 "))
    }

    @Test
    fun generatedIdentitiesAreWellFormedWithAMatchingCheckDigit() {
        val random = Random(7)
        repeat(50) {
            val imei = Imei.generate(random)
            assertTrue(imei, Imei.isWellFormed(imei))
            assertTrue(imei, Imei.checkDigitMatches(imei))
            assertTrue(imei, imei.startsWith("35"))
        }
    }
}
