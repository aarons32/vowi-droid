package com.vowi.vowidroid.core

import com.vowi.vowidroid.core.call.Call
import com.vowi.vowidroid.core.call.CallRuntime
import com.vowi.vowidroid.core.call.CallState
import com.vowi.vowidroid.core.call.G711Factory
import com.vowi.vowidroid.core.call.MediaDirection
import com.vowi.vowidroid.core.call.Sdp
import com.vowi.vowidroid.core.ims.ImsException
import com.vowi.vowidroid.core.ims.ImsSession
import com.vowi.vowidroid.core.net.UdpSocket
import com.vowi.vowidroid.core.sip.SipBuilder
import com.vowi.vowidroid.core.sip.SipMessage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Hold, resume and call waiting against the fake P-CSCF: the UE registers
 * over ipsec-3gpp, the network calls it, and every re-INVITE, ACK and SDP
 * direction is checked on the wire.
 */
class CallHoldTest {
    private lateinit var bed: ImsTestBed
    private lateinit var calls: CallRuntime
    private lateinit var farEndRtp: UdpSocket
    private val incoming = LinkedBlockingQueue<Call>()

    @Before
    fun setUp() {
        bed = ImsTestBed()
        calls = CallRuntime(bed.session, G711Factory)
        calls.glareDelayMs = { 20L }
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
        // The far end's media port, where the UE's RTP lands.
        farEndRtp = bed.pcscf.stack.openUdp(40000, 4)
    }

    @After
    fun tearDown() {
        runCatching { calls.close() }
        bed.close()
    }

    @Test
    fun holdAndResumeChangeTheDirectionBothWays() {
        val (call, ueTo, answer) = answeredCall("call-a")

        // We hold: the offer only sends, the far end answers recvonly.
        assertTrue(calls.hold(call.id).held)
        val hold = fromUe("INVITE")
        assertEquals("call-a", hold.callId)
        assertEquals(MediaDirection.SENDONLY, direction(hold))
        assertTrue(hold.value("Contact").isNotEmpty())
        assertEquals("${hold.cseq()!!.first} ACK", fromUe("ACK").value("CSeq"))
        // RFC 3264 §8: the same o= session, its version one higher.
        assertEquals(origin(answer).first, origin(hold.body).first)
        assertEquals(origin(answer).second + 1, origin(hold.body).second)
        assertEquals(MediaDirection.SENDONLY, calls.mediaDirection(call.id))

        // While held, silence keeps the stream alive: PCMU frames of 20 ms.
        val keepalive = farEndRtp.receive(2_000)
        assertNotNull("hold keepalive", keepalive)
        assertEquals(0, keepalive!!.data[1].toInt() and 0x7f)
        assertEquals(12 + 160, keepalive.data.size)

        // Resume: sendrecv again, and the keepalive stops (no microphone runs in the test).
        assertFalse(calls.resume(call.id).held)
        assertEquals(MediaDirection.SENDRECV, direction(fromUe("INVITE")))
        fromUe("ACK")
        drain(farEndRtp, 300)
        assertNull("no keepalive after resume", farEndRtp.receive(600))

        // The far end holds us: we answer recvonly and the call shows it.
        bed.pcscf.sendToUe(invite("call-a", FakePcscf.sdp("sendonly", version = 2), cseq = 2, to = ueTo))
        assertEquals(MediaDirection.RECVONLY, direction(response(200)))
        bed.pcscf.sendToUe(ack("call-a", 2, ueTo))
        assertTrue(calls.call(call.id)!!.remoteHeld)

        // Holding while held offers inactive (TS 24.610), and nothing is sent.
        assertTrue(calls.hold(call.id).held)
        assertEquals(MediaDirection.INACTIVE, direction(fromUe("INVITE")))
        fromUe("ACK")
        assertNull("inactive sends nothing", farEndRtp.receive(600))

        // The far end resumes from inactive with recvonly; we still hold, so we answer sendonly.
        bed.pcscf.sendToUe(invite("call-a", FakePcscf.sdp("recvonly", version = 3), cseq = 3, to = ueTo))
        assertEquals(MediaDirection.SENDONLY, direction(response(200)))
        bed.pcscf.sendToUe(ack("call-a", 3, ueTo))
        assertFalse(calls.call(call.id)!!.remoteHeld)

        assertFalse(calls.resume(call.id).held)
        assertEquals(MediaDirection.SENDRECV, direction(fromUe("INVITE")))
        fromUe("ACK")

        calls.hangup(call.id)
        assertEquals("call-a", fromUe("BYE").callId)
    }

    @Test
    fun aSecondCallWaitsAndAnsweringItHoldsTheFirst() {
        val (first, _, _) = answeredCall("call-a")

        bed.pcscf.sendToUe(invite("call-b", FakePcscf.sdp("sendrecv")))
        val ringing = response(180)
        assertEquals("call-b", ringing.callId)
        assertEquals(CallRuntime.CALL_WAITING_ALERT, ringing.value("Alert-Info"))
        assertTrue(ringing.value("Contact").isNotEmpty())
        val second = incoming.poll(5, TimeUnit.SECONDS) ?: throw AssertionError("the waiting call was not reported")
        assertEquals("+15550001111", second.number)

        // Answering the waiting call holds the first one before the 200 goes out.
        calls.answer(second.id)
        val hold = fromUe("INVITE")
        assertEquals("call-a", hold.callId)
        assertEquals(MediaDirection.SENDONLY, direction(hold))
        fromUe("ACK")
        val ok = response(200)
        assertEquals("call-b", ok.callId)
        assertEquals(MediaDirection.SENDRECV, direction(ok))
        bed.pcscf.sendToUe(ack("call-b", 1, ok.value("To")))
        assertTrue(calls.call(first.id)!!.held)
        assertEquals(CallState.ACTIVE, calls.call(second.id)!!.state)
        assertFalse(calls.call(second.id)!!.held)

        // Swapping back holds the second call first, then resumes the first.
        calls.resume(first.id)
        val holdSecond = fromUe("INVITE")
        assertEquals("call-b", holdSecond.callId)
        assertEquals(MediaDirection.SENDONLY, direction(holdSecond))
        fromUe("ACK")
        val resumeFirst = fromUe("INVITE")
        assertEquals("call-a", resumeFirst.callId)
        assertEquals(MediaDirection.SENDRECV, direction(resumeFirst))
        fromUe("ACK")
        assertFalse(calls.call(first.id)!!.held)
        assertTrue(calls.call(second.id)!!.held)

        // A third call finds the line busy.
        bed.pcscf.sendToUe(invite("call-c", FakePcscf.sdp("sendrecv")))
        assertEquals("call-c", response(486).callId)
        assertNull(incoming.poll(300, TimeUnit.MILLISECONDS))
    }

    @Test
    fun aRefusedHoldChangesNothingAndGlareIsSorted() {
        val (call, ueTo, _) = answeredCall("call-a")

        // The network refuses the hold: the call stays live, and the 488 is ACKed in its transaction.
        bed.pcscf.inviteAnswer = { 488 to null }
        val error = runCatching { calls.hold(call.id) }.exceptionOrNull()
        assertTrue("hold should fail, got $error", error is ImsException)
        val refused = fromUe("INVITE")
        val refusedAck = fromUe("ACK")
        assertEquals("${refused.cseq()!!.first} ACK", refusedAck.value("CSeq"))
        assertEquals(refused.value("Via"), refusedAck.value("Via"))
        assertFalse(calls.call(call.id)!!.held)
        assertEquals(CallState.ACTIVE, calls.call(call.id)!!.state)
        assertEquals(MediaDirection.SENDRECV, calls.mediaDirection(call.id))

        // Glare on our side: 491, a short wait, and the second offer goes through.
        val answers = ConcurrentLinkedQueue(listOf<Pair<Int, ByteArray?>>(491 to null))
        bed.pcscf.inviteAnswer = { request -> answers.poll() ?: (200 to FakePcscf.mirrorSdp(request.body)) }
        assertTrue(calls.hold(call.id).held)
        fromUe("INVITE")
        fromUe("ACK")
        assertEquals(MediaDirection.SENDONLY, direction(fromUe("INVITE")))
        fromUe("ACK")

        // Glare on the far end's side: its offer meets ours and gets 491; nothing changes.
        var farEndReply: SipMessage? = null
        bed.pcscf.inviteAnswer = { request ->
            bed.pcscf.sendToUe(invite("call-a", FakePcscf.sdp("sendonly", version = 9), cseq = 5, to = ueTo))
            farEndReply = bed.pcscf.responsesFromUe.poll(5, TimeUnit.SECONDS)
            200 to FakePcscf.mirrorSdp(request.body)
        }
        assertFalse(calls.resume(call.id).held)
        assertEquals(491, farEndReply?.statusCode)
        assertEquals(MediaDirection.SENDRECV, direction(fromUe("INVITE")))
        fromUe("ACK")
        assertFalse(calls.call(call.id)!!.remoteHeld)
    }

    @Test
    fun anOfferlessReinviteGetsOurOfferAndTheAckAnswers() {
        val (call, ueTo, _) = answeredCall("call-a")
        bed.pcscf.sendToUe(
            SipBuilder.request("INVITE", "sip:+15551234567@10.0.0.2")
                .header("Via", "SIP/2.0/TCP 10.0.0.1:6100;branch=z9hG4bK-offerless")
                .header("Max-Forwards", "70")
                .header("From", "<sip:+15550001111@ims.mnc260.mcc310.3gppnetwork.org>;tag=far-call-a")
                .header("To", ueTo)
                .header("Call-ID", "call-a")
                .header("CSeq", "4 INVITE")
                .header("Contact", "<sip:+15550001111@10.0.0.1:6100;transport=tcp>")
                .build(),
        )
        val ok = response(200)
        assertEquals(MediaDirection.SENDRECV, direction(ok))
        // The answer comes in the ACK: the far end only listens, so the UE only sends.
        bed.pcscf.sendToUe(ack("call-a", 4, ueTo, FakePcscf.sdp("recvonly", version = 4)))
        val deadline = System.currentTimeMillis() + 5_000
        while (calls.mediaDirection(call.id) != MediaDirection.SENDONLY && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertEquals(MediaDirection.SENDONLY, calls.mediaDirection(call.id))
    }

    @Test
    fun aFinalResponseWithoutSdpKeepsTheMediaFromThe183() {
        // The IMS flow: the answer comes in a reliable 183, and the 200 OK repeats nothing.
        bed.pcscf.inviteProvisional = { request -> 183 to FakePcscf.mirrorSdp(request.body) }
        bed.pcscf.inviteAnswer = { 200 to null }
        val call = calls.dial("+15550002222")
        val invite = fromUe("INVITE")
        assertEquals(MediaDirection.SENDRECV, direction(invite))
        // The PRACK and the ACK may reach the fake network on different connections, so in either order.
        val next = listOf(fromUeAny(), fromUeAny())
        val prack = next.first { it.method == "PRACK" }
        assertEquals("1 ${invite.cseq()!!.first} INVITE", prack.value("RAck"))
        assertEquals("${invite.cseq()!!.first} ACK", next.first { it.method == "ACK" }.value("CSeq"))
        val deadline = System.currentTimeMillis() + 5_000
        while (calls.call(call.id)!!.state != CallState.ACTIVE && System.currentTimeMillis() < deadline) Thread.sleep(20)
        val connected = calls.call(call.id)!!
        assertEquals(CallState.ACTIVE, connected.state)
        assertTrue(connected.mediaReady)
        assertEquals("PCMU", connected.codec)
        assertEquals(MediaDirection.SENDRECV, calls.mediaDirection(call.id))
        // Nothing else goes out: in particular no BYE.
        assertNull(bed.pcscf.fromUe.poll(500, TimeUnit.MILLISECONDS))

        // A 200 OK without SDP and without an earlier answer cannot carry a call.
        bed.pcscf.inviteProvisional = { null }
        val bare = calls.dial("+15550003333")
        // Add call: the call in progress goes on hold before the new INVITE.
        val hold = fromUe("INVITE")
        assertEquals(call.id, hold.callId)
        assertEquals(MediaDirection.SENDONLY, direction(hold))
        fromUe("ACK")
        val second = fromUe("INVITE")
        assertEquals(bare.id, second.callId)
        fromUe("ACK")
        assertEquals("BYE", fromUe("BYE").method)
        assertEquals(CallState.FAILED, calls.call(bare.id)!!.state)
        assertTrue(calls.call(call.id)!!.held)
    }

    // --- Helpers --------------------------------------------------------------

    private data class Answered(val call: Call, val ueTo: String, val answerSdp: ByteArray)

    /** The network calls the UE, the UE answers, the network ACKs. */
    private fun answeredCall(callId: String): Answered {
        bed.pcscf.sendToUe(invite(callId, FakePcscf.sdp("sendrecv")))
        val ringing = response(180)
        assertTrue(ringing.value("Alert-Info").isEmpty())
        val call = incoming.poll(5, TimeUnit.SECONDS) ?: throw AssertionError("the call was not reported")
        assertEquals(CallState.ACTIVE, calls.answer(call.id).state)
        val ok = response(200)
        assertEquals(MediaDirection.SENDRECV, direction(ok))
        val ueTo = ok.value("To")
        bed.pcscf.sendToUe(ack(callId, 1, ueTo))
        return Answered(call, ueTo, ok.body)
    }

    private fun invite(callId: String, sdp: ByteArray, cseq: Int = 1, to: String = "<sip:+15551234567@ims.mnc260.mcc310.3gppnetwork.org>"): ByteArray =
        SipBuilder.request("INVITE", "sip:+15551234567@10.0.0.2")
            .header("Via", "SIP/2.0/TCP 10.0.0.1:6100;branch=z9hG4bK-$callId-$cseq")
            .header("Max-Forwards", "70")
            .header("From", "<sip:+15550001111@ims.mnc260.mcc310.3gppnetwork.org>;tag=far-$callId")
            .header("To", to)
            .header("Call-ID", callId)
            .header("CSeq", "$cseq INVITE")
            .header("Contact", "<sip:+15550001111@10.0.0.1:6100;transport=tcp>")
            .body("application/sdp", sdp)
            .build()

    private fun ack(callId: String, cseq: Int, to: String, sdp: ByteArray? = null): ByteArray {
        val builder = SipBuilder.request("ACK", "sip:+15551234567@10.0.0.2")
            .header("Via", "SIP/2.0/TCP 10.0.0.1:6100;branch=z9hG4bK-$callId-$cseq-ack")
            .header("Max-Forwards", "70")
            .header("From", "<sip:+15550001111@ims.mnc260.mcc310.3gppnetwork.org>;tag=far-$callId")
            .header("To", to)
            .header("Call-ID", callId)
            .header("CSeq", "$cseq ACK")
        if (sdp != null) builder.body("application/sdp", sdp)
        return builder.build()
    }

    private fun response(status: Int): SipMessage {
        val message = bed.pcscf.responsesFromUe.poll(5, TimeUnit.SECONDS) ?: throw AssertionError("no $status from the UE")
        assertEquals(status, message.statusCode)
        return message
    }

    private fun fromUe(method: String): SipMessage {
        val message = bed.pcscf.fromUe.poll(5, TimeUnit.SECONDS) ?: throw AssertionError("the UE sent no $method")
        assertEquals(method, message.method)
        return message
    }

    private fun fromUeAny(): SipMessage = bed.pcscf.fromUe.poll(5, TimeUnit.SECONDS) ?: throw AssertionError("the UE sent nothing")

    private fun direction(message: SipMessage): MediaDirection = Sdp.parse(message.body).direction

    private fun origin(sdp: ByteArray): Pair<Long, Long> {
        val fields = String(sdp, Charsets.ISO_8859_1).lines().first { it.startsWith("o=") }.split(' ')
        return fields[1].toLong() to fields[2].toLong()
    }

    private fun drain(socket: UdpSocket, quietMs: Long) {
        while (socket.receive(quietMs) != null) Unit
    }
}
