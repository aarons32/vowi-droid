package com.vowi.vowidroid.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Keypad matching for dialpad suggestions, after AOSP's SmartDialNameMatcher. */
class SmartDialTest {
    @Test
    fun lettersBecomeKeys() {
        assertEquals(listOf("5646", "76484"), SmartDial.keyWords("John Smith"))
        assertEquals(listOf("3737"), SmartDial.keyWords("Ères"))
        assertEquals("18003569377", SmartDial.dialable("1-800-FLOWERS"))
        assertEquals("+15551234,567;8", SmartDial.dialable("+1 (555) 1234,567;8"))
    }

    @Test
    fun namesMatchWordStartsAndInitials() {
        assertTrue(SmartDial.matchesName("John Smith", "5646")) // john
        assertTrue(SmartDial.matchesName("John Smith", "764")) // smi
        assertTrue(SmartDial.matchesName("John Smith", "57")) // initials
        assertTrue(SmartDial.matchesName("John Smith", "56476")) // jo + sm
        assertTrue(SmartDial.matchesName("Mary-Jane Watson", "59")) // jane watson initials
        assertFalse(SmartDial.matchesName("John Smith", "46")) // "hn" is not a word start
        assertFalse(SmartDial.matchesName("John Smith", "7646")) // smith, then back to john
        assertFalse(SmartDial.matchesName("John Smith", "5*"))
    }

    @Test
    fun numbersMatchFromTheStartOrInside() {
        assertTrue(SmartDial.matchesNumber("+1 555 123 4567", "155"))
        assertTrue(SmartDial.matchesNumber("+1 555 123 4567", "555")) // after the country code
        assertTrue(SmartDial.matchesNumber("+1 555 123 4567", "1234")) // inside, three or more
        assertFalse(SmartDial.matchesNumber("+1 555 123 4567", "23"))
        assertFalse(SmartDial.matchesNumber("+1 555 123 4567", ""))
    }

    @Test
    fun numbersCompareAcrossFormats() {
        assertTrue(PhoneNumbers.same("+1 (555) 123-4567", "5551234567"))
        assertTrue(PhoneNumbers.same("12345", "12345"))
        assertFalse(PhoneNumbers.same("12345", "+15550012345")) // a short code is not a full number
        assertFalse(PhoneNumbers.same("+15551234567", "+15557654321"))
    }
}
