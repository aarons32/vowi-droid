package com.vowi.vowidroid.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Status reports find their text by TP-MR, and a long text is delivered once every part is. */
class DeliveryReportsTest {
    @Test
    fun aSinglePartTextIsDeliveredByItsReference() {
        val update = DeliveryReports.apply("17", 17, "delivered")!!
        assertEquals("", update.pendingRefs)
        assertEquals("delivered", update.deliveryState)
    }

    @Test
    fun aReportForAnotherReferenceDoesNotMatch() {
        assertNull(DeliveryReports.apply("17", 18, "delivered"))
        assertNull(DeliveryReports.apply("", 18, "delivered"))
    }

    @Test
    fun aLongTextWaitsForEveryPart() {
        val first = DeliveryReports.apply("40,41,42", 41, "delivered")!!
        assertEquals("40,42", first.pendingRefs)
        assertNull(first.deliveryState)
        val second = DeliveryReports.apply(first.pendingRefs, 40, "delivered")!!
        assertNull(second.deliveryState)
        val last = DeliveryReports.apply(second.pendingRefs, 42, "delivered")!!
        assertEquals("", last.pendingRefs)
        assertEquals("delivered", last.deliveryState)
    }

    @Test
    fun temporaryErrorsWaitAndPermanentOnesFail() {
        val waiting = DeliveryReports.apply("5", 5, "temporary_error")!!
        assertEquals("5", waiting.pendingRefs)
        assertNull(waiting.deliveryState)
        val failed = DeliveryReports.apply("5,6", 6, "permanent_error")!!
        assertEquals("", failed.pendingRefs)
        assertEquals("failed", failed.deliveryState)
        assertEquals("failed", DeliveryReports.apply("5", 5, "temporary_error_no_retry")!!.deliveryState)
    }

    @Test
    fun peersMatchAcrossNumberFormats() {
        assertTrue(DeliveryReports.samePeer("+15551234567", "5551234567"))
        assertTrue(DeliveryReports.samePeer("+1 (555) 123-4567", "15551234567"))
        assertFalse(DeliveryReports.samePeer("+15551234567", "+15557654321"))
        assertFalse(DeliveryReports.samePeer("", "+15557654321"))
    }
}
