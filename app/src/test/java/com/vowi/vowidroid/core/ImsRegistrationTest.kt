package com.vowi.vowidroid.core

import com.vowi.vowidroid.core.carrier.CarrierProfiles
import com.vowi.vowidroid.core.ims.ImsConfig
import com.vowi.vowidroid.core.ims.ImsNetwork
import com.vowi.vowidroid.core.ims.ImsSession
import com.vowi.vowidroid.core.ipsec.TransportSecurity
import com.vowi.vowidroid.core.net.NetStack
import com.vowi.vowidroid.core.sim.AkaChallenge
import com.vowi.vowidroid.core.sim.AkaProvider
import com.vowi.vowidroid.core.sim.AkaResult
import com.vowi.vowidroid.core.sim.SimIdentity
import com.vowi.vowidroid.core.sip.SipMessage
import com.vowi.vowidroid.core.sms.ReceivedSms
import com.vowi.vowidroid.core.sms.ReceivedSmsStatus
import com.vowi.vowidroid.core.sms.SmsOverIms
import com.vowi.vowidroid.core.util.hexToBytes
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * A fake P-CSCF ([FakePcscf]) on a second in-app stack challenges REGISTER
 * with AKAv1-MD5 and a Security-Server offer, accepts the protected
 * re-registration, then delivers a text to the UE's protected port and
 * receives one back.
 */
class ImsRegistrationTest {
    private val ueAddress = byteArrayOf(10, 0, 0, 2)
    private val pcscfAddress = byteArrayOf(10, 0, 0, 1)
    private val ck = ByteArray(16) { 0x11 }
    private val ik = ByteArray(16) { 0x22 }
    private val res = "0102030405060708".hexToBytes()
    private val identity = SimIdentity(iccid = "8901260123456789012", imsi = "310260123456789", homeMcc = "310", homeMnc = "260", gid1 = "DD", smsc = "+12063130004")
    private val toPcscf = Executors.newSingleThreadExecutor()
    private val toUe = Executors.newSingleThreadExecutor()
    private lateinit var ue: UeNetwork
    private lateinit var pcscf: FakePcscf

    private class UeNetwork(override val stack: NetStack, override val security: TransportSecurity, override val pcscf: List<String>) : ImsNetwork

    private val sim = object : AkaProvider {
        override fun authenticate(identity: SimIdentity, challenge: AkaChallenge): AkaResult = AkaResult(res, ck, ik)
    }

    @Before
    fun setUp() {
        TestSupport.installCarrierProfiles()
        val security = TransportSecurity()
        lateinit var ueStack: NetStack
        pcscf = FakePcscf(pcscfAddress, ueAddress, ck, ik, res) { packet -> toUe.execute { ueStack.deliver(packet) } }
        ueStack = NetStack(ueAddress, null) { packet -> toPcscf.execute { pcscf.stack.deliver(packet) } }
        ueStack.filter = object : NetStack.PacketFilter {
            override fun outbound(packet: ByteArray): ByteArray = security.protect(packet)
            override fun inbound(packet: ByteArray): Pair<ByteArray?, String> = security.unprotect(packet)
        }
        ue = UeNetwork(ueStack, security, listOf("10.0.0.1"))
    }

    @After
    fun tearDown() {
        runCatching { ue.stack.close() }
        runCatching { pcscf.close() }
        toPcscf.shutdownNow()
        toUe.shutdownNow()
    }

    @Test
    fun registersOverIpsecAndExchangesTexts() {
        val profile = CarrierProfiles.resolve(identity)
        assertEquals("ipcc-carrier-310026", profile.id)
        val session = ImsSession(ue, identity, profile, sim, ImsConfig(transport = "tcp", autoTransportFallback = false, transactionTimeoutMs = 5_000, deviceId = "phone-sim-2"))
        val received = LinkedBlockingQueue<ReceivedSms>()
        val sms = SmsOverIms(session) { identity.smsc }
        sms.listener = object : SmsOverIms.Listener {
            override fun onSms(sms: ReceivedSms): Boolean = received.offer(sms)
            override fun onStatus(status: ReceivedSmsStatus): Boolean = true
        }
        val failures = ArrayList<Exception>()
        session.listener = object : ImsSession.Listener {
            override fun onRequest(session: ImsSession, request: SipMessage, respond: (ByteArray) -> Unit): Boolean = sms.handle(request, respond)
            override fun onFailure(error: Exception) { failures.add(error) }
        }
        pcscf.start()
        session.connect()
        try {
            val evidence = session.evidence
            assertTrue(evidence.registered)
            assertEquals("ipsec-3gpp", evidence.securityMode)
            assertTrue(evidence.securityVerified)
            assertEquals("+15551234567", evidence.phoneNumber)
            assertTrue(evidence.smsContactConfirmed)
            assertTrue(session.smsReady)
            assertEquals(2, pcscf.registers.get())
            assertTrue(pcscf.sawSecurityVerify)
            assertTrue(pcscf.secondRegisterProtected)

            // A text from the network arrives on the protected server port over TCP.
            pcscf.deliverText("0405912143F500004210203040500005C82293F904".hexToBytes())
            val text = received.poll(5, TimeUnit.SECONDS)
            assertNotNull(text)
            assertEquals("HELLO", text!!.text)
            assertEquals("+12345", text.from)
            assertTrue(pcscf.sawRpAck.await(5, TimeUnit.SECONDS))

            // A text from the UE goes to the service centre as RP-DATA.
            val result = sms.send("+15557654321", "Hi there")
            assertTrue(result.accepted)
            assertEquals(1, result.partsTotal)
            val submitted = pcscf.submitted.poll(5, TimeUnit.SECONDS)
            assertNotNull(submitted)
            assertEquals(0, submitted!![0].toInt() and 0x07) // RP-DATA, mobile originated
        } finally {
            session.close()
        }
        assertTrue(pcscf.sawDeregister.await(5, TimeUnit.SECONDS))
        assertTrue(failures.isEmpty())
    }
}
