package com.vowi.vowidroid.ui

import com.vowi.vowidroid.ConversationShortcuts
import com.vowi.vowidroid.ui.calls.MmiCodes
import com.vowi.vowidroid.ui.components.Speech
import com.vowi.vowidroid.ui.settings.ForwardingStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Dialled codes, forwarding answers, spoken numbers and conversation shortcut IDs. */
class DialingTest {
    @Test
    fun codesAreToldApartFromNumbers() {
        assertTrue(MmiCodes.isCode("*#21#"))
        assertTrue(MmiCodes.isCode("**21*+12065550100#"))
        assertTrue(MmiCodes.isCode("*225#"))
        assertTrue(MmiCodes.isCode("#21#"))
        // A caller ID prefix ends in the number, so it is a call.
        assertFalse(MmiCodes.isCode("#31#2065550100"))
        assertFalse(MmiCodes.isCode("*672065550100"))
        assertFalse(MmiCodes.isCode("2065550100"))
        assertFalse(MmiCodes.isCode("##"))
        assertTrue(MmiCodes.isDeviceCode("*#06#"))
        assertTrue(MmiCodes.isDeviceCode("*#*#4636#*#*"))
        assertFalse(MmiCodes.isDeviceCode("*#21#"))
    }

    @Test
    fun carrierAnswersAboutForwardingAreRead() {
        assertEquals(ForwardingStatus.Off, ForwardingStatus.from("Call Forwarding Unconditional: not active"))
        assertEquals(ForwardingStatus.Off, ForwardingStatus.from("Service was deactivated"))
        assertEquals(ForwardingStatus.On("+12065550100"), ForwardingStatus.from("Voice calls forwarded to +1 206-555-0100"))
        assertEquals(ForwardingStatus.Said("Request accepted"), ForwardingStatus.from(" Request accepted "))
    }

    @Test
    fun numbersAreSpokenDigitByDigit() {
        assertEquals("+ 1, 2 0 6, 5 5 5, 0 1 0 0", Speech.number("+1 206-555-0100"))
        assertEquals("2 0 6, 5 5 5, 0 1 0 0", Speech.number("(206) 555-0100"))
        assertEquals("* 2 2 5 #", Speech.number("*225#"))
    }

    @Test
    fun shortcutIdsNameTheirConversation() {
        val id = ConversationShortcuts.id("phone-sim-2", "+12065550100")
        assertEquals("phone-sim-2" to "+12065550100", ConversationShortcuts.parse(id))
        assertNull(ConversationShortcuts.parse("sim_setup"))
        assertNull(ConversationShortcuts.parse(null))
        assertNull(ConversationShortcuts.parse("conversation|only-a-line"))
    }
}
