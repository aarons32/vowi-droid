package com.vowi.vowidroid.core

import com.vowi.vowidroid.core.ims.EventSubscription
import com.vowi.vowidroid.core.ims.ImsSession
import com.vowi.vowidroid.core.ims.MessageSummary
import com.vowi.vowidroid.core.ims.VoicemailState
import com.vowi.vowidroid.core.ims.VoicemailWatcher
import com.vowi.vowidroid.core.sip.SipMessage
import com.vowi.vowidroid.core.sms.SmsPdu
import com.vowi.vowidroid.core.util.hexToBytes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Voicemail alerts: message-summary bodies and subscriptions (RFC 3842, TS 24.606) and SMS indications (TS 23.038, TS 23.040). */
class VoicemailTest {
    @Test
    fun theVoiceCountsDecideWhenTheyArePresent() {
        val state = MessageSummary.parse("Messages-Waiting: yes\r\nMessage-Account: sip:+15550009999@ims.example\r\nVoice-Message: 2/8 (0/2)\r\n")!!
        assertTrue(state.waiting)
        assertEquals(2, state.newMessages)
        assertEquals(8, state.oldMessages)
        assertEquals("sip:+15550009999@ims.example", state.account)

        val heard = MessageSummary.parse("Messages-Waiting: yes\r\nVoice-Message: 0/3\r\n")!!
        assertFalse(heard.waiting)
        assertEquals(0, heard.newMessages)

        val someNumber = MessageSummary.parse("messages-waiting: YES\n")!!
        assertTrue(someNumber.waiting)
        assertNull(someNumber.newMessages)

        assertFalse(MessageSummary.parse("Messages-Waiting: no\r\n")!!.waiting)
        assertNull(MessageSummary.parse("Content of some other kind"))
    }

    @Test
    fun smsIndicationsComeFromTheCodingGroupOrTheHeader() {
        val address = "0591" + "2143F5" // 5 digits, international
        val stamp = "42102030405000"
        // DCS 0xC8: discard after updating, indication on, voicemail (TS 23.038 §4).
        val discard = SmsPdu.voicemailIndication(("04" + address + "00" + "C8" + stamp + "00").hexToBytes())
        assertNotNull(discard)
        assertTrue(discard!!.active)
        assertTrue(discard.discard)
        assertNull(discard.count)

        // DCS 0xD0: store the text, indication off.
        val off = SmsPdu.voicemailIndication(("04" + address + "00" + "D0" + stamp + "05" + "C82293F904").hexToBytes())!!
        assertFalse(off.active)
        assertFalse(off.discard)

        // TS 23.040 §9.2.3.24.2 in the header: store, voicemail, three messages.
        val header = SmsPdu.voicemailIndication(("44" + address + "00" + "00" + stamp + "06" + "0401028003").hexToBytes())!!
        assertTrue(header.active)
        assertEquals(3, header.count)
        assertFalse(header.discard)

        // An ordinary text is not an indication.
        assertNull(SmsPdu.voicemailIndication(("04" + address + "00" + "00" + stamp + "05" + "C82293F904").hexToBytes()))
    }

    @Test
    fun theWatcherSubscribesToItsOwnIdentityAndReadsEveryNotify() {
        ImsTestBed().use { bed ->
            val changes = LinkedBlockingQueue<VoicemailState>()
            lateinit var watcher: VoicemailWatcher
            bed.register(listener { request, respond -> watcher.handle(request, respond) })
            watcher = VoicemailWatcher(bed.session) { changes.offer(it) }
            watcher.start()
            val subscribe = fromUe(bed, "SUBSCRIBE")
            assertEquals(bed.session.identities.publicIdentity, subscribe.uri)
            assertEquals(VoicemailWatcher.EVENT, subscribe.value("Event"))
            assertEquals(VoicemailWatcher.CONTENT_TYPE, subscribe.value("Accept"))
            assertEquals("3600", subscribe.value("Expires"))

            // The voicemail server reports in the subscription's dialog.
            bed.pcscf.sendToUe(notify(subscribe.callId, subscribe.value("To") + ";tag=pcscf", subscribe.value("From"), "Messages-Waiting: yes\r\nVoice-Message: 2/0\r\n"))
            assertEquals(200, bed.pcscf.responsesFromUe.poll(5, TimeUnit.SECONDS)!!.statusCode)
            assertEquals(2, changes.poll(5, TimeUnit.SECONDS)!!.newMessages)

            // Some networks alert without any subscription.
            bed.pcscf.sendToUe(notify("unsolicited-mwi", "<sip:voicemail@ims.example>;tag=vm", "<${bed.session.identities.publicIdentity}>", "Messages-Waiting: no\r\n"))
            assertEquals(200, bed.pcscf.responsesFromUe.poll(5, TimeUnit.SECONDS)!!.statusCode)
            assertFalse(changes.poll(5, TimeUnit.SECONDS)!!.waiting)

            // Stopping ends the subscription in its own dialog.
            watcher.stop()
            val unsubscribe = fromUe(bed, "SUBSCRIBE")
            assertEquals(subscribe.callId, unsubscribe.callId)
            assertEquals("0", unsubscribe.value("Expires"))
        }
    }

    @Test
    fun aTerminatedNotifyEndsTheSubscription() {
        ImsTestBed().use { bed ->
            val notified = LinkedBlockingQueue<SipMessage>()
            lateinit var subscription: EventSubscription
            bed.register(listener { request, respond -> subscription.handle(request, respond) })
            subscription = EventSubscription(bed.session, "conference", "sip:focus@ims.example", "application/conference-info+xml", expires = 600) { notified.offer(it) }
            assertTrue(subscription.start())
            assertTrue(subscription.active)
            val subscribe = fromUe(bed, "SUBSCRIBE")
            assertEquals("600", subscribe.value("Expires"))

            bed.pcscf.sendToUe(
                FakePcscf.request(
                    "NOTIFY", subscribe.callId, from = subscribe.value("To") + ";tag=pcscf", to = subscribe.value("From"), cseq = 1,
                    extra = listOf("Event" to "conference", "Subscription-State" to "terminated;reason=noresource"),
                ),
            )
            assertEquals(200, bed.pcscf.responsesFromUe.poll(5, TimeUnit.SECONDS)!!.statusCode)
            assertNotNull(notified.poll(5, TimeUnit.SECONDS))
            assertFalse(subscription.active)
            // Nothing more goes out for a subscription the network ended.
            subscription.stop()
            assertNull(bed.pcscf.fromUe.poll(500, TimeUnit.MILLISECONDS))
        }
    }

    private fun listener(handle: (SipMessage, (ByteArray) -> Unit) -> Boolean) = object : ImsSession.Listener {
        override fun onRequest(session: ImsSession, request: SipMessage, respond: (ByteArray) -> Unit): Boolean = handle(request, respond)
        override fun onFailure(error: Exception) = Unit
    }

    private fun notify(callId: String, from: String, to: String, body: String): ByteArray = FakePcscf.request(
        "NOTIFY", callId, from = from, to = to, cseq = 1,
        extra = listOf("Event" to VoicemailWatcher.EVENT, "Subscription-State" to "active;expires=3599"),
        contentType = VoicemailWatcher.CONTENT_TYPE, body = body.toByteArray(),
    )

    private fun fromUe(bed: ImsTestBed, method: String): SipMessage {
        val message = bed.pcscf.fromUe.poll(5, TimeUnit.SECONDS) ?: throw AssertionError("the UE sent no $method")
        assertEquals(method, message.method)
        return message
    }
}
