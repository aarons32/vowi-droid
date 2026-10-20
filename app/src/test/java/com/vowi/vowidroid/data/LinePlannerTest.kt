package com.vowi.vowidroid.data

import com.vowi.vowidroid.core.sim.CardIdentity
import com.vowi.vowidroid.core.sim.FoundCard
import com.vowi.vowidroid.core.sim.SimCards
import com.vowi.vowidroid.core.sim.SimLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A SIM card's life: put in, moved, switched off, ejected, taken out and put back. */
class LinePlannerTest {
    private val imsi = "310260123456789"
    private val iccid = "8901260012345678901"
    private val slot1 = SimLocation.PhoneSlot(0)
    private val slot2 = SimLocation.PhoneSlot(1)
    private val reader = SimLocation.UsbReader("/dev/bus/usb/001/004", "ACS ACR39U")
    private val line = LineConfig(SimCards.lineId(imsi), 1, "T-Mobile", enabled = true, imsi = imsi, iccid = iccid, subscriptionId = 1, lastSeen = "slot:2")

    private fun card(location: SimLocation, subscriptionId: Int = -1) = FoundCard(location, imsi, iccid, "T-Mobile", subscriptionId = subscriptionId)

    @Test
    fun aCardPutInStartsItsLineAndAMoveReconnectsIt() {
        assertEquals(LinePlanner.Action.START, LinePlanner.action(line, card(slot2), previous = null, running = false))
        assertEquals(LinePlanner.Action.NONE, LinePlanner.action(line, card(slot2), previous = slot2, running = true))
        assertEquals(LinePlanner.Action.MOVE, LinePlanner.action(line, card(slot1), previous = slot2, running = true))
        assertEquals(LinePlanner.Action.MOVE, LinePlanner.action(line, card(reader), previous = slot1, running = true))
    }

    @Test
    fun aCardTakenOutStopsItsLineAndItsPlaceAndSubscriptionAreRemembered() {
        assertEquals(LinePlanner.Action.STOP, LinePlanner.action(line, null, previous = slot2, running = true))
        val after = LinePlanner.update(line.copy(lastSeen = "usb:ACS ACR39U"), null, null)
        assertEquals("usb:ACS ACR39U", after.lastSeen)
        assertEquals(1, after.subscriptionId)
        val moved = LinePlanner.update(line, card(slot1, subscriptionId = 3), null)
        assertEquals("slot:1", moved.lastSeen)
        assertEquals(0, moved.slot)
        assertEquals(3, moved.subscriptionId)
        assertEquals("a reader keeps the last slot", 1, LinePlanner.update(line, card(reader), null).slot)
    }

    @Test
    fun aSwitchedOffCardIsInButTheLineWaits() {
        val off = FoundCard(slot2, null, "", "", issue = SimCards.ISSUE_AIRPLANE)
        assertEquals(LinePlanner.Action.STOP, LinePlanner.action(line, off, previous = slot2, running = true))
        assertEquals(LinePlanner.Action.NONE, LinePlanner.action(line, off, previous = slot2, running = false))
        val waiting = LinePlanner.update(line, off, null)
        assertEquals("slot:2", waiting.lastSeen)
        assertEquals("the subscription is kept for when the card is back on", 1, waiting.subscriptionId)
        // Back on: the line starts.
        assertEquals(LinePlanner.Action.START, LinePlanner.action(waiting, card(slot2, subscriptionId = 1), previous = slot2, running = false))
    }

    @Test
    fun ejectStopsTheLineUntilTheCardIsTakenOutAndPutBack() {
        val ejected = line.copy(ejected = true)
        // Still in: it stays ejected and off.
        assertTrue(LinePlanner.update(ejected, card(slot2), null).ejected)
        assertEquals(LinePlanner.Action.STOP, LinePlanner.action(ejected, card(slot2), previous = slot2, running = true))
        assertEquals(LinePlanner.Action.NONE, LinePlanner.action(ejected, card(slot2), previous = slot2, running = false))
        // Taken out: the ejection ends…
        val out = LinePlanner.update(ejected, null, null)
        assertFalse(out.ejected)
        // …so putting it back, anywhere, starts the line.
        assertEquals(LinePlanner.Action.START, LinePlanner.action(LinePlanner.update(out, card(reader), null), card(reader), previous = null, running = false))
    }

    @Test
    fun aLineSwitchedOffNeverRuns() {
        val off = line.copy(enabled = false)
        assertEquals(LinePlanner.Action.NONE, LinePlanner.action(off, card(slot2), previous = null, running = false))
        assertEquals(LinePlanner.Action.STOP, LinePlanner.action(off, card(slot2), previous = slot2, running = true))
    }

    @Test
    fun anOldLineIsTiedToItsSubscriberOnceAndAReplacementCardIsRecorded() {
        val legacy = LineConfig("phone-sim-2", 1, "Phone SIM 2 · T-Mobile", enabled = true)
        assertNull(legacy.imsi)
        val bound = LinePlanner.update(legacy, card(slot1, subscriptionId = 1), CardIdentity(imsi, iccid))
        assertEquals(imsi, bound.imsi)
        assertEquals(iccid, bound.iccid)
        assertEquals(1, bound.subscriptionId)
        // The subscriber never changes by itself; the card can.
        val replaced = LinePlanner.update(bound, FoundCard(slot1, imsi, "8901260099999999999", ""), CardIdentity(imsi, "8901260099999999999"))
        assertEquals(imsi, replaced.imsi)
        assertEquals("8901260099999999999", replaced.iccid)
        assertEquals("T-Mobile", LineStore.withoutSlot("Phone SIM 2 · T-Mobile"))
        assertEquals("Work", LineStore.withoutSlot("Work"))
        assertEquals("Phone SIM 2", LineStore.withoutSlot("Phone SIM 2"))
    }
}
