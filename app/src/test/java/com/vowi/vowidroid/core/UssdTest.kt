package com.vowi.vowidroid.core

import com.vowi.vowidroid.core.ims.ImsSession
import com.vowi.vowidroid.core.ims.UssdException
import com.vowi.vowidroid.core.ims.UssdReply
import com.vowi.vowidroid.core.ims.UssdRuntime
import com.vowi.vowidroid.core.sip.SipMessage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * USSD over IMS (3GPP TS 24.390) against the fake P-CSCF: the code goes out
 * as a dial string in an INVITE, the network's questions come in INFO and
 * its last word in a BYE.
 */
class UssdTest {
    private lateinit var bed: ImsTestBed
    private lateinit var ussd: UssdRuntime
    private val pool = Executors.newCachedThreadPool()

    @Before
    fun setUp() {
        bed = ImsTestBed()
        ussd = UssdRuntime(bed.session)
        bed.register(object : ImsSession.Listener {
            override fun onRequest(session: ImsSession, request: SipMessage, respond: (ByteArray) -> Unit): Boolean = ussd.handle(request, respond)
            override fun onFailure(error: Exception) = Unit
        })
        // The USSD service takes the session without media.
        bed.pcscf.inviteAnswer = { 200 to null }
    }

    @After
    fun tearDown() {
        runCatching { ussd.cancel() }
        pool.shutdownNow()
        bed.close()
    }

    @Test
    fun aCodeGoesOutAsADialStringAndTheByeCarriesTheAnswer() {
        val reply = async { ussd.send("*#21#") }
        val invite = fromUe("INVITE")
        val domain = bed.session.identities.domain
        // TS 24.390 Table A.1-1: the code is the user part, # escaped, with user=dialstring.
        assertEquals("sip:*%2321%23;phone-context=$domain@$domain;user=dialstring", invite.uri)
        assertEquals(UssdRuntime.INFO_PACKAGE, invite.value("Recv-Info"))
        assertTrue(invite.value("Content-Type").startsWith("multipart/mixed"))
        val body = String(invite.body, Charsets.UTF_8)
        assertTrue(body.contains("Content-Type: application/sdp"))
        assertTrue(body.contains("Content-Type: ${UssdRuntime.XML_TYPE}"))
        assertTrue(body.contains("<ussd-string>*#21#</ussd-string>"))
        assertEquals(invite.callId, fromUe("ACK").callId)

        // The service is done: its answer comes in a BYE, which the UE confirms.
        bed.pcscf.sendToUe(fromNetwork("BYE", invite, 2, "Call forwarding is not active"))
        assertEquals(200, response().statusCode)
        val answer = reply.get(5, TimeUnit.SECONDS)
        assertEquals("Call forwarding is not active", answer.text)
        assertTrue(answer.final)
    }

    @Test
    fun aMenuWaitsForTheUsersReplyInAnInfo() {
        val first = async { ussd.send("*225#") }
        val invite = fromUe("INVITE")
        fromUe("ACK")
        bed.pcscf.sendToUe(fromNetwork("INFO", invite, 2, "1. Balance\n2. Data"))
        // TS 24.390 §5.1.2.1: a 200 without a body.
        val ok = response()
        assertEquals(200, ok.statusCode)
        assertEquals(0, ok.body.size)
        val menu = first.get(5, TimeUnit.SECONDS)
        assertEquals("1. Balance\n2. Data", menu.text)
        assertFalse(menu.final)

        val second = async { ussd.reply("1") }
        val info = fromUe("INFO")
        assertEquals(invite.callId, info.callId)
        assertEquals(UssdRuntime.INFO_PACKAGE, info.value("Info-Package"))
        assertTrue(String(info.body, Charsets.UTF_8).contains("<ussd-string>1</ussd-string>"))
        bed.pcscf.sendToUe(fromNetwork("BYE", invite, 3, "Your balance is 10.00"))
        assertEquals(200, response().statusCode)
        val done = second.get(5, TimeUnit.SECONDS)
        assertEquals("Your balance is 10.00", done.text)
        assertTrue(done.final)
    }

    @Test
    fun aRefusedCodeIsAcknowledgedAndReported() {
        bed.pcscf.inviteAnswer = { 403 to null }
        val error = assertThrows(UssdException::class.java) { ussd.send("*#31#") }
        assertTrue(error.message!!.contains("403"))
        val invite = fromUe("INVITE")
        assertEquals(invite.callId, fromUe("ACK").callId)
    }

    @Test
    fun cancellingEndsTheSessionAtOnce() {
        val pending = async { runCatching { ussd.send("*100#") } }
        val invite = fromUe("INVITE")
        fromUe("ACK")
        ussd.cancel()
        assertEquals(invite.callId, fromUe("BYE").callId)
        // The waiting send returns straight away, not after its 15-second wait.
        val result = pending.get(2, TimeUnit.SECONDS)
        assertTrue(result.isFailure)
    }

    @Test
    fun bodiesAreReadFromXmlMultipartAndPlainText() {
        assertEquals("Hi & bye" to null, UssdRuntime.parse(UssdRuntime.xml("Hi & bye"), UssdRuntime.XML_TYPE))
        val multipart = UssdRuntime.multipart("b1", "v=0\r\n".toByteArray(), UssdRuntime.xml("menu"))
        assertEquals("menu" to null, UssdRuntime.parse(multipart, "multipart/mixed; boundary=b1"))
        val error = "<?xml version=\"1.0\"?><ussd-data><error-code>1</error-code></ussd-data>".toByteArray()
        assertEquals("" to 1, UssdRuntime.parse(error, UssdRuntime.XML_TYPE))
        assertEquals("plain" to null, UssdRuntime.parse("plain\r\n".toByteArray(), "text/plain"))
    }

    private fun <T> async(block: () -> T): Future<T> = pool.submit(Callable { block() })

    /** A request from the USSD service inside the session the UE opened with [invite]. */
    private fun fromNetwork(method: String, invite: SipMessage, cseq: Int, text: String): ByteArray {
        val extra = if (method == "INFO") listOf("Info-Package" to UssdRuntime.INFO_PACKAGE, "Content-Disposition" to "info-package") else emptyList()
        return FakePcscf.request(
            method, invite.callId,
            from = invite.value("To") + ";tag=pcscf", to = invite.value("From"), cseq = cseq,
            extra = extra, contentType = UssdRuntime.XML_TYPE, body = UssdRuntime.xml(text),
        )
    }

    private fun response(): SipMessage = bed.pcscf.responsesFromUe.poll(5, TimeUnit.SECONDS) ?: throw AssertionError("no response from the UE")

    private fun fromUe(method: String): SipMessage {
        val message = bed.pcscf.fromUe.poll(5, TimeUnit.SECONDS) ?: throw AssertionError("the UE sent no $method")
        assertEquals(method, message.method)
        return message
    }

    @Suppress("unused")
    private fun UssdReply.describe() = "$text ($final)"
}
