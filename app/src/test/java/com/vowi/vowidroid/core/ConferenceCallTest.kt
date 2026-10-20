package com.vowi.vowidroid.core

import com.vowi.vowidroid.core.call.Call
import com.vowi.vowidroid.core.call.CallRuntime
import com.vowi.vowidroid.core.call.CallState
import com.vowi.vowidroid.core.call.G711Factory
import com.vowi.vowidroid.core.call.MediaDirection
import com.vowi.vowidroid.core.call.MergeResult
import com.vowi.vowidroid.core.call.Sdp
import com.vowi.vowidroid.core.ims.ImsSession
import com.vowi.vowidroid.core.ims.tagOf
import com.vowi.vowidroid.core.sip.SipBuilder
import com.vowi.vowidroid.core.sip.SipMessage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Conference calls (3GPP TS 24.147), caller ID privacy (TS 24.607) and the
 * call-waiting switch (TS 24.615) against the fake P-CSCF.
 */
class ConferenceCallTest {
    private lateinit var bed: ImsTestBed
    private lateinit var calls: CallRuntime
    private val incoming = LinkedBlockingQueue<Call>()
    private val pool = Executors.newCachedThreadPool()
    private val domain get() = bed.session.identities.domain

    @Before
    fun setUp() {
        bed = ImsTestBed()
        calls = CallRuntime(bed.session, G711Factory)
        calls.listener = object : CallRuntime.Listener {
            override fun onIncoming(call: Call) {
                incoming.offer(call)
            }

            override fun onStateChanged(call: Call) = Unit
        }
        bed.register(object : ImsSession.Listener {
            override fun onRequest(session: ImsSession, request: SipMessage, respond: (ByteArray) -> Unit): Boolean = calls.handle(request, respond)
            override fun onFailure(error: Exception) = Unit
        })
    }

    @After
    fun tearDown() {
        runCatching { calls.close() }
        pool.shutdownNow()
        bed.close()
    }

    @Test
    fun mergingTwoCallsStartsAConferenceAndRefersBothCalls() {
        val first = answered("call-a", "+15550001111")
        val second = answered("call-b", "+15550002222", holds = "call-a")
        val merging = pool.submit(Callable { calls.merge(first.call.id, second.call.id) })

        // The live call goes on hold first.
        val hold = fromUe("INVITE")
        assertEquals("call-b", hold.callId)
        assertEquals(MediaDirection.SENDONLY, Sdp.parse(hold.body).direction)
        fromUe("ACK")

        // Our own leg to the conference factory (TS 23.003 §13.10).
        val factory = fromUe("INVITE")
        assertEquals("sip:mmtel@conf-factory.$domain", factory.uri)
        assertEquals(MediaDirection.SENDRECV, Sdp.parse(factory.body).direction)
        fromUe("ACK")

        // The focus takes over each call by a REFER with Replaces (RFC 3891: to-tag theirs, from-tag ours).
        val referA = fromUe("REFER")
        val referB = fromUe("REFER")
        assertEquals(factory.callId, referA.callId)
        assertEquals(factory.callId, referB.callId)
        assertEquals("<sip:+15550001111@$domain;user=phone?Replaces=call-a%3Bto-tag%3Dfar-call-a%3Bfrom-tag%3D${first.ueTag}>", referA.value("Refer-To"))
        assertEquals("<sip:+15550002222@$domain;user=phone?Replaces=call-b%3Bto-tag%3Dfar-call-b%3Bfrom-tag%3D${second.ueTag}>", referB.value("Refer-To"))
        assertTrue(referA.value("Referred-By").isNotEmpty())

        val result = merging.get(10, TimeUnit.SECONDS)
        assertEquals(listOf(first.call.id, second.call.id), result.joined)
        assertTrue(result.conference.conference)
        assertEquals(CallState.ACTIVE, result.conference.state)

        // The conference's own list of people (RFC 4575), without our own number.
        val subscribe = fromUe("SUBSCRIBE")
        assertEquals("conference", subscribe.value("Event"))
        assertEquals(CallRuntime.CONFERENCE_INFO, subscribe.value("Accept"))
        bed.pcscf.sendToUe(conferenceNotify(subscribe, 1, FULL_STATE))
        assertEquals(200, response().statusCode)
        // The 200 goes out before the list is applied, so wait for the focus's own URIs.
        val people = waitFor { calls.call(result.conference.id)!!.participants.takeIf { list -> list.size == 2 && list.all { it.uri.startsWith("tel:") } } }
        assertEquals(setOf("tel:+15550001111", "tel:+15550002222"), people.map { it.uri }.toSet())
        assertEquals("Alice", people.first { it.uri == "tel:+15550001111" }.name)

        // A partial update takes someone out.
        bed.pcscf.sendToUe(conferenceNotify(subscribe, 2, BOB_LEFT))
        assertEquals(200, response().statusCode)
        waitFor { calls.call(result.conference.id)!!.participants.takeIf { it.size == 1 } }

        // Removing someone is a REFER whose target asks for a BYE (TS 24.147 §5.3.1.5.4).
        calls.removeParticipant(result.conference.id, "tel:+15550001111")
        val remove = fromUe("REFER")
        assertEquals(factory.callId, remove.callId)
        assertEquals("<tel:+15550001111;method=BYE>", remove.value("Refer-To"))
    }

    @Test
    fun aThirdCallJoinsTheRunningConference() {
        val first = answered("call-a", "+15550001111")
        val second = answered("call-b", "+15550002222", holds = "call-a")
        val merging = pool.submit(Callable { calls.merge(first.call.id, second.call.id) })
        fromUe("INVITE")
        fromUe("ACK")
        val factory = fromUe("INVITE")
        fromUe("ACK")
        fromUe("REFER")
        fromUe("REFER")
        val conference: MergeResult = merging.get(10, TimeUnit.SECONDS)
        fromUe("SUBSCRIBE")
        // The focus replaced both calls, so their far ends hang up the old dialogs.
        bed.pcscf.sendToUe(FakePcscf.request("BYE", "call-a", from = "<sip:+15550001111@$domain>;tag=far-call-a", to = first.ueTo, cseq = 2))
        assertEquals(200, response().statusCode)
        bed.pcscf.sendToUe(FakePcscf.request("BYE", "call-b", from = "<sip:+15550002222@$domain>;tag=far-call-b", to = second.ueTo, cseq = 2))
        assertEquals(200, response().statusCode)
        waitFor { calls.call(first.call.id)?.state?.takeIf { it == CallState.ENDED } }

        // A third call holds the conference when answered, then joins it.
        val third = answered("call-c", "+15550003333", holds = factory.callId)
        val joining = pool.submit(Callable { calls.merge(conference.conference.id, third.call.id) })
        val holdThird = fromUe("INVITE")
        assertEquals("call-c", holdThird.callId)
        assertEquals(MediaDirection.SENDONLY, Sdp.parse(holdThird.body).direction)
        fromUe("ACK")
        val resume = fromUe("INVITE")
        assertEquals(factory.callId, resume.callId)
        assertEquals(MediaDirection.SENDRECV, Sdp.parse(resume.body).direction)
        fromUe("ACK")
        val refer = fromUe("REFER")
        assertEquals(factory.callId, refer.callId)
        assertTrue(refer.value("Refer-To").startsWith("<sip:+15550003333@$domain;user=phone?Replaces=call-c%3B"))
        val joined = joining.get(10, TimeUnit.SECONDS)
        assertEquals(listOf(third.call.id), joined.joined)
        assertEquals(conference.conference.id, joined.conference.id)
        assertFalse(joined.conference.held)
    }

    @Test
    fun callerIdIsHiddenShownOrLeftToTheNetwork() {
        // Busy, so each call ends at once.
        bed.pcscf.inviteAnswer = { 486 to null }
        calls.dial("+15550003333", hideCallerId = true)
        val hidden = fromUe("INVITE")
        assertEquals("id", hidden.value("Privacy"))
        assertTrue(hidden.value("From").startsWith("\"Anonymous\" <sip:anonymous@anonymous.invalid>;tag="))
        // The network still learns who is calling, to bill and route it (TS 24.607 §4.5.2).
        assertTrue(hidden.value("P-Preferred-Identity").contains("+15551234567"))
        fromUe("ACK")

        calls.dial("+15550003333", hideCallerId = false)
        val shown = fromUe("INVITE")
        assertEquals("none", shown.value("Privacy"))
        assertFalse(shown.value("From").contains("anonymous"))
        fromUe("ACK")

        calls.dial("+15550003333")
        assertEquals("", fromUe("INVITE").value("Privacy"))
        fromUe("ACK")
    }

    @Test
    fun withCallWaitingOffASecondCallHearsBusy() {
        val first = answered("call-a", "+15550001111")
        calls.callWaiting = { false }
        bed.pcscf.sendToUe(invite("call-b", "+15550002222"))
        val busy = response()
        assertEquals(486, busy.statusCode)
        assertEquals("call-b", busy.callId)
        assertNull(incoming.poll(300, TimeUnit.MILLISECONDS))
        assertFalse(calls.call(first.call.id)!!.held)
    }

    // --- Helpers --------------------------------------------------------------

    private class Answered(val call: Call, val ueTo: String) {
        val ueTag: String get() = tagOf(ueTo)!!
    }

    /** The network calls the UE from [number] and the UE answers; [holds] is the call that answering puts on hold. */
    private fun answered(callId: String, number: String, holds: String? = null): Answered {
        bed.pcscf.sendToUe(invite(callId, number))
        assertEquals(180, response().statusCode)
        val call = incoming.poll(5, TimeUnit.SECONDS) ?: throw AssertionError("the call was not reported")
        assertEquals(number, call.number)
        val answering = pool.submit(Callable { calls.answer(call.id) })
        if (holds != null) {
            val hold = fromUe("INVITE")
            assertEquals(holds, hold.callId)
            assertEquals(MediaDirection.SENDONLY, Sdp.parse(hold.body).direction)
            fromUe("ACK")
        }
        val ok = response()
        assertEquals(200, ok.statusCode)
        assertEquals(callId, ok.callId)
        answering.get(5, TimeUnit.SECONDS)
        bed.pcscf.sendToUe(
            SipBuilder.request("ACK", "sip:+15551234567@10.0.0.2")
                .header("Via", "SIP/2.0/TCP 10.0.0.1:6100;branch=z9hG4bK-$callId-ack")
                .header("Max-Forwards", "70")
                .header("From", "<sip:$number@$domain>;tag=far-$callId")
                .header("To", ok.value("To"))
                .header("Call-ID", callId)
                .header("CSeq", "1 ACK")
                .build(),
        )
        return Answered(call, ok.value("To"))
    }

    private fun invite(callId: String, number: String): ByteArray =
        SipBuilder.request("INVITE", "sip:+15551234567@10.0.0.2")
            .header("Via", "SIP/2.0/TCP 10.0.0.1:6100;branch=z9hG4bK-$callId-1")
            .header("Max-Forwards", "70")
            .header("From", "<sip:$number@$domain>;tag=far-$callId")
            .header("To", "<sip:+15551234567@$domain>")
            .header("Call-ID", callId)
            .header("CSeq", "1 INVITE")
            .header("Contact", "<sip:$number@10.0.0.1:6100;transport=tcp>")
            .body("application/sdp", FakePcscf.sdp("sendrecv"))
            .build()

    private fun conferenceNotify(subscribe: SipMessage, cseq: Int, xml: String): ByteArray = FakePcscf.request(
        "NOTIFY", subscribe.callId, from = subscribe.value("To") + ";tag=pcscf", to = subscribe.value("From"), cseq = cseq,
        extra = listOf("Event" to "conference", "Subscription-State" to "active;expires=3600"),
        contentType = CallRuntime.CONFERENCE_INFO, body = xml.toByteArray(),
    )

    private fun response(): SipMessage = bed.pcscf.responsesFromUe.poll(5, TimeUnit.SECONDS) ?: throw AssertionError("no response from the UE")

    private fun fromUe(method: String): SipMessage {
        val message = bed.pcscf.fromUe.poll(5, TimeUnit.SECONDS) ?: throw AssertionError("the UE sent no $method")
        assertEquals(method, message.method)
        return message
    }

    private fun <T> waitFor(read: () -> T?): T {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            read()?.let { return it }
            Thread.sleep(20)
        }
        return read() ?: throw AssertionError("timed out")
    }

    private companion object {
        val FULL_STATE = """
            <?xml version="1.0" encoding="UTF-8"?>
            <conference-info xmlns="urn:ietf:params:xml:ns:conference-info" entity="sip:conf-1@ims.example" state="full" version="1">
              <users>
                <user entity="tel:+15551234567"><endpoint entity="sip:ue@ims.example"><status>connected</status></endpoint></user>
                <user entity="tel:+15550001111"><display-text>Alice</display-text><endpoint entity="sip:a@ims.example"><status>connected</status></endpoint></user>
                <user entity="tel:+15550002222"><endpoint entity="sip:b@ims.example"><status>connected</status></endpoint></user>
              </users>
            </conference-info>
        """.trimIndent()

        val BOB_LEFT = """
            <?xml version="1.0" encoding="UTF-8"?>
            <conference-info xmlns="urn:ietf:params:xml:ns:conference-info" entity="sip:conf-1@ims.example" state="partial" version="2">
              <users><user entity="tel:+15550002222" state="deleted"/></users>
            </conference-info>
        """.trimIndent()
    }
}
