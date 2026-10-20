package com.vowi.vowidroid.core

import com.vowi.vowidroid.core.ike.EpdgErrors
import com.vowi.vowidroid.core.ike.EpdgRejectedException
import com.vowi.vowidroid.core.ike.Ike
import com.vowi.vowidroid.core.ike.IkeChild
import com.vowi.vowidroid.core.ike.IkeException
import com.vowi.vowidroid.core.ike.IkeHandshake
import com.vowi.vowidroid.core.ike.IkeWire
import com.vowi.vowidroid.core.ike.NoProposalChosenException
import com.vowi.vowidroid.core.ike.Payload
import com.vowi.vowidroid.core.WifiCallingEngine.RetryPolicy
import com.vowi.vowidroid.core.util.concat
import com.vowi.vowidroid.core.util.putU16
import com.vowi.vowidroid.core.util.toHex
import com.vowi.vowidroid.core.util.u16
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** The ePDG's 3GPP reasons, the retry policy they lead to, and the P-CSCF attribute IDs. */
class EpdgErrorsTest {
    @Test
    fun carrierReasonsBecomeTypedRejections() {
        val error = assertThrows(EpdgRejectedException::class.java) {
            IkeHandshake.rejectFatalNotifications(listOf(IkeWire.makeNotify(9000)))
        }
        assertEquals(9000, error.code)
        assertEquals("NON_3GPP_ACCESS_TO_EPC_NOT_ALLOWED", error.name)
        assertEquals(EpdgErrors.Policy.FATAL, error.policy)
        assertNull(error.backoffSeconds)
        assertTrue(error.message!!, error.message!!.contains("does not allow Wi-Fi calling"))
    }

    @Test
    fun networkFailureCarriesTheBackoffTimer() {
        // GPRS timer 3: unit 1 (hours) with value 2.
        val timer = IkeWire.makeNotify(EpdgErrors.NOTIFY_BACKOFF_TIMER, byteArrayOf(1, (1 shl 5 or 2).toByte()))
        val error = assertThrows(EpdgRejectedException::class.java) {
            IkeHandshake.rejectFatalNotifications(listOf(IkeWire.makeNotify(10500), timer))
        }
        assertEquals(EpdgErrors.Policy.BACK_OFF, error.policy)
        assertEquals(2 * 3600L, error.backoffSeconds)
        assertEquals(10 * 60L, EpdgErrors.backoffSeconds(byteArrayOf(1, 1)))
        assertEquals(30L, EpdgErrors.backoffSeconds(byteArrayOf(1, (4 shl 5 or 1).toByte())))
        assertNull(EpdgErrors.backoffSeconds(byteArrayOf(1, (7 shl 5).toByte())))
    }

    @Test
    fun otherErrorsKeepTheirOwnExceptions() {
        assertThrows(NoProposalChosenException::class.java) { IkeHandshake.rejectFatalNotifications(listOf(IkeWire.makeNotify(Ike.NOTIFY_NO_PROPOSAL_CHOSEN))) }
        val generic = assertThrows(IkeException::class.java) { IkeHandshake.rejectFatalNotifications(listOf(IkeWire.makeNotify(Ike.NOTIFY_INVALID_SYNTAX))) }
        assertTrue(generic !is EpdgRejectedException)
        // Status notifications are not errors.
        IkeHandshake.rejectFatalNotifications(listOf(IkeWire.makeNotify(Ike.NOTIFY_INITIAL_CONTACT)))
    }

    @Test
    fun thePolicyStopsOnFatalCodesAndPausesOnBackoffCodes() {
        val policy = RetryPolicy
        val fatal = EpdgRejectedException(11001, "RAT_TYPE_NOT_ALLOWED", EpdgErrors.Policy.FATAL, explanation = "no")
        assertEquals(RetryPolicy.Decision.Stop, policy.decide(fatal, 1))
        // Wrapped by the connector or the engine, the reason still counts.
        assertEquals(RetryPolicy.Decision.Stop, policy.decide(IOException("tunnel", fatal), 3))

        val pause = EpdgRejectedException(10500, "NETWORK_FAILURE", EpdgErrors.Policy.BACK_OFF, backoffSeconds = 600, explanation = "pause")
        assertEquals(RetryPolicy.Decision.Wait(600_000, carrierAsked = true), policy.decide(pause, 1))
        val unnamed = EpdgRejectedException(10500, "NETWORK_FAILURE", EpdgErrors.Policy.BACK_OFF, explanation = "pause")
        assertEquals(RetryPolicy.Decision.Wait(policy.CARRIER_PAUSE_MS, carrierAsked = true), policy.decide(unnamed, 1))
        val tooLong = EpdgRejectedException(10500, "NETWORK_FAILURE", EpdgErrors.Policy.BACK_OFF, backoffSeconds = 48 * 3600, explanation = "pause")
        assertEquals(RetryPolicy.Decision.Wait(policy.MAX_CARRIER_PAUSE_MS, carrierAsked = true), policy.decide(tooLong, 1))

        // A plain failure backs off 10 s, 20 s, then 30 s for good.
        assertEquals(listOf(10_000L, 20_000L, 30_000L, 30_000L, 30_000L, 30_000L), (1..6).map { (policy.decide(IOException("x"), it) as RetryPolicy.Decision.Wait).delayMs })
        assertEquals(10_000L, RetryPolicy.plainBackoffMs(0))
        assertEquals(false, (policy.decide(IOException("x"), 1) as RetryPolicy.Decision.Wait).carrierAsked)
    }

    @Test
    fun pcscfIsRequestedAndReadUnderEveryKnownAttribute() {
        val request = IkeChild.configurationRequest().body
        val requested = ArrayList<Int>()
        var offset = 4
        while (offset + 4 <= request.size) {
            requested += request.u16(offset) and 0x7fff
            offset += 4 + request.u16(offset + 2)
        }
        for (attribute in intArrayOf(20, 21, 16384, 16386, 16389, 16390)) assertTrue("requests $attribute", attribute in requested)

        fun attribute(type: Int, value: ByteArray): ByteArray {
            val out = ByteArray(4 + value.size)
            out.putU16(0, type)
            out.putU16(2, value.size)
            System.arraycopy(value, 0, out, 4, value.size)
            return out
        }
        val v6 = ByteArray(16) { if (it == 0) 0x20 else if (it == 15) 1 else 0 }
        val reply = concat(
            byteArrayOf(IkeChild.CONFIG_REPLY.toByte(), 0, 0, 0),
            attribute(IkeChild.CONFIG_INTERNAL_IP4_ADDRESS, byteArrayOf(10, 0, 0, 2)),
            attribute(16384, byteArrayOf(10, 0, 0, 1)),
            attribute(16389, byteArrayOf(10, 0, 0, 3)),
            attribute(16386, v6),
            attribute(IkeChild.CONFIG_P_CSCF_IP4_ADDRESS, byteArrayOf(10, 0, 0, 1)), // the same address again, under the standard ID
        )
        val configuration = IkeChild.parseConfiguration(Payload(Ike.PAYLOAD_CP, reply))
        val pcscf = IkeChild.pcscfForAssignedFamilies(configuration.pcscf, hasIpv4 = true, hasIpv6 = true)
        assertEquals(3, pcscf.size)
        assertEquals(listOf("0a000001", "0a000003"), pcscf.filter { it.size == 4 }.map { it.toHex() })
        assertEquals(16, pcscf.last().size)
    }
}
