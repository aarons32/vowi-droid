package com.vowi.vowidroid.core

import com.vowi.vowidroid.core.call.ConferenceInfo
import com.vowi.vowidroid.core.call.Participant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The conference event package's documents (RFC 4575). */
class ConferenceInfoTest {
    @Test
    fun aFullStateListsEveryoneButTheDisconnected() {
        val update = ConferenceInfo.parse(
            """<?xml version="1.0"?>
            <ci:conference-info xmlns:ci="urn:ietf:params:xml:ns:conference-info" entity="sip:c@x" state="full" version="1">
              <ci:users>
                <ci:user entity="tel:+15550001111"><ci:display-text>Alice</ci:display-text><ci:endpoint><ci:status>connected</ci:status></ci:endpoint></ci:user>
                <ci:user entity="tel:+15550002222"><ci:endpoint><ci:status>dialing-out</ci:status></ci:endpoint></ci:user>
                <ci:user entity="tel:+15550003333"><ci:endpoint><ci:status>disconnected</ci:status></ci:endpoint></ci:user>
              </ci:users>
            </ci:conference-info>""",
        )!!
        assertTrue(update.full)
        val people = ConferenceInfo.apply(listOf(Participant("tel:+1999", null, "connected")), update)
        assertEquals(listOf("tel:+15550001111", "tel:+15550002222"), people.map { it.uri })
        assertEquals("Alice", people[0].name)
        assertEquals("dialing-out", people[1].status)
    }

    @Test
    fun aPartialUpdateMatchesPeopleByTheirNumber() {
        // While the merge is under way VowiDroid lists the callers by its own URIs…
        val known = listOf(
            Participant("sip:5550001111@ims.mnc260.mcc310.3gppnetwork.org;user=phone", null, "connecting"),
            Participant("sip:+15550002222@ims.mnc260.mcc310.3gppnetwork.org;user=phone", null, "connecting"),
        )
        // …and the focus then reports them under tel URIs.
        val update = ConferenceInfo.parse(
            """<conference-info state="partial"><users>
                 <user entity="tel:+15550001111"><endpoint><status>connected</status></endpoint></user>
                 <user entity="tel:+1-555-000-2222" state="deleted"/>
               </users></conference-info>""",
        )!!
        assertFalse(update.full)
        val people = ConferenceInfo.apply(known, update)
        assertEquals(listOf(Participant("tel:+15550001111", null, "connected")), people)
    }

    @Test
    fun numbersAndKeys() {
        assertEquals("+15550001111", ConferenceInfo.number("tel:+1-555-000-1111"))
        assertEquals("5550001111", ConferenceInfo.number("sip:5550001111@ims.example;user=phone"))
        assertNull(ConferenceInfo.number("sip:alice@example.com"))
        assertEquals(ConferenceInfo.key("tel:+15550001111"), ConferenceInfo.key("sip:5550001111@x;user=phone"))
        assertEquals("sip:alice@example.com", ConferenceInfo.key("sip:Alice@Example.com"))
    }

    @Test
    fun documentTypesAndOtherDocumentsAreRefused() {
        assertNull(ConferenceInfo.parse("""<?xml version="1.0"?><!DOCTYPE x [<!ENTITY e SYSTEM "file:///etc/hosts">]><conference-info>&e;</conference-info>"""))
        assertNull(ConferenceInfo.parse("<presence/>"))
        assertNull(ConferenceInfo.parse("not xml"))
    }
}
