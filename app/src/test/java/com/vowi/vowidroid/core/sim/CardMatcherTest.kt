package com.vowi.vowidroid.core.sim

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Lines follow their subscriber between slots, USB readers and replacement cards. */
class CardMatcherTest {
    private val imsiA = "310260123456789"
    private val iccidA = "8901260012345678901"
    private val imsiB = "310260987654321"
    private val iccidB = "8901260098765432109"
    private val slot1 = SimLocation.PhoneSlot(0)
    private val slot2 = SimLocation.PhoneSlot(1)
    private val reader = SimLocation.UsbReader("/dev/bus/usb/001/004", "ACS ACR39U")
    private val lineA = SavedLine("line-a", imsiA, iccidA, null, "slot:2")

    @Test
    fun lineIdsComeFromTheImsiWithoutShowingIt() {
        val id = SimCards.lineId(imsiA)
        assertEquals(id, SimCards.lineId(" $imsiA "))
        assertNotEquals(id, SimCards.lineId(imsiB))
        assertTrue(id.startsWith("sim-"))
        assertEquals(16, id.length)
        assertFalse(id.contains("310260"))
    }

    @Test
    fun aLineFollowsItsSubscriberToAnotherSlotOrAReader() {
        val lines = listOf(lineA)
        assertEquals(slot1, CardMatcher.match(lines, listOf(FoundCard(slot1, imsiA, iccidA, "T-Mobile"))).placed["line-a"]?.location)
        assertEquals(slot2, CardMatcher.match(lines, listOf(FoundCard(slot2, imsiA, iccidA, "T-Mobile"))).placed["line-a"]?.location)
        val inReader = CardMatcher.match(lines, listOf(FoundCard(reader, imsiA, iccidA, "T-Mobile")))
        assertEquals(reader, inReader.placed["line-a"]?.location)
        assertTrue(inReader.unknown.isEmpty())
        assertTrue("nothing to record", inReader.bindings.isEmpty())
    }

    @Test
    fun aReplacementCardWithTheSameImsiKeepsTheLineAndIsRecorded() {
        val match = CardMatcher.match(listOf(lineA), listOf(FoundCard(slot1, imsiA, iccidB, "T-Mobile")))
        assertEquals(slot1, match.placed["line-a"]?.location)
        assertEquals(CardIdentity(imsiA, iccidB), match.bindings["line-a"])
    }

    @Test
    fun anotherSubscribersCardIsNewAndTheLineStaysWithoutItsCard() {
        val match = CardMatcher.match(listOf(lineA), listOf(FoundCard(slot2, imsiB, iccidB, "Mint")))
        assertNull(match.placed["line-a"])
        assertEquals(listOf(imsiB), match.unknown.map { it.imsi })
    }

    @Test
    fun swappedCardsSwapTheirLines() {
        val lines = listOf(lineA, SavedLine("line-b", imsiB, iccidB, null, "slot:1"))
        val match = CardMatcher.match(lines, listOf(FoundCard(slot1, imsiB, iccidB, ""), FoundCard(slot2, imsiA, iccidA, "")))
        assertEquals(slot2, match.placed["line-a"]?.location)
        assertEquals(slot1, match.placed["line-b"]?.location)
    }

    @Test
    fun aLockedCardInAReaderIsKnownByItsIccid() {
        val locked = FoundCard(reader, null, iccidA, "ACS ACR39U", issue = SimCards.ISSUE_PIN)
        val match = CardMatcher.match(listOf(lineA), listOf(locked))
        assertEquals(reader, match.placed["line-a"]?.location)
        assertFalse(match.placed["line-a"]!!.readable)
        // A locked card nobody owns is listed, so the reason can be shown.
        val unknown = CardMatcher.match(emptyList(), listOf(locked))
        assertEquals(listOf(locked), unknown.unknown)
    }

    @Test
    fun aSwitchedOffCardIsTheOneLastSeenInThatSlot() {
        val off = FoundCard(slot2, null, "", "", issue = SimCards.ISSUE_AIRPLANE)
        val match = CardMatcher.match(listOf(lineA), listOf(off))
        assertEquals(slot2, match.placed["line-a"]?.location)
        assertEquals(SimCards.ISSUE_AIRPLANE, match.placed["line-a"]?.issue)
        // In another slot it is somebody's card, but not this line's, and not offered either.
        val elsewhere = CardMatcher.match(listOf(lineA), listOf(FoundCard(slot1, null, "", "", issue = SimCards.ISSUE_AIRPLANE)))
        assertNull(elsewhere.placed["line-a"])
        assertTrue(elsewhere.unknown.isEmpty())
    }

    @Test
    fun aLineSavedBeforeCardsWereTrackedTakesTheCardInItsOldSlot() {
        val legacy = SavedLine("phone-sim-2", null, null, 1, null)
        val match = CardMatcher.match(listOf(legacy), listOf(FoundCard(slot2, imsiA, iccidA, "T-Mobile")))
        assertEquals(CardIdentity(imsiA, iccidA), match.bindings["phone-sim-2"])
        assertEquals(slot2, match.placed["phone-sim-2"]?.location)
    }

    @Test
    fun aLoneOldLineTakesTheLoneCardWhenThePhoneNumbersItsSlotsDifferently() {
        // The phone now reports the same card in slot 1, as this test phone did.
        val legacy = SavedLine("phone-sim-2", null, null, 1, null)
        val match = CardMatcher.match(listOf(legacy), listOf(FoundCard(slot1, imsiA, iccidA, "T-Mobile")))
        assertEquals(CardIdentity(imsiA, iccidA), match.bindings["phone-sim-2"])
        assertEquals(slot1, match.placed["phone-sim-2"]?.location)
        assertTrue(match.unknown.isEmpty())
        // With two cards and no slot match, nothing is guessed.
        val two = CardMatcher.match(listOf(legacy), listOf(FoundCard(slot1, imsiA, iccidA, ""), FoundCard(reader, imsiB, iccidB, "")))
        assertNull(two.placed["phone-sim-2"])
        assertEquals(2, two.unknown.size)
    }

    @Test
    fun anOldLineNeverTakesACardAnotherLineOwns() {
        val lines = listOf(SavedLine("line-b", imsiB, iccidB, null, null), SavedLine("phone-sim-2", null, null, 1, null))
        val match = CardMatcher.match(lines, listOf(FoundCard(slot2, imsiB, iccidB, "")))
        assertEquals(slot2, match.placed["line-b"]?.location)
        assertNull(match.placed["phone-sim-2"])
        assertTrue(match.bindings.isEmpty())
    }

    @Test
    fun anEjectedCardStaysEjectedUntilItIsTakenOut() {
        assertTrue(CardMatcher.stillEjected(ejected = true, present = true))
        assertFalse(CardMatcher.stillEjected(ejected = true, present = false))
        assertFalse(CardMatcher.stillEjected(ejected = false, present = true))
    }
}
