package com.vowi.vowidroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The IDs behind the notification categories: one set per line, and the app-wide ones. */
class NotificationChannelsTest {
    @Test
    fun everyLineHasItsOwnCategories() {
        val first = NotificationChannels.Kind.entries.map { NotificationChannels.id(it, "phone-sim-1") }
        val second = NotificationChannels.Kind.entries.map { NotificationChannels.id(it, "usb-1") }
        assertEquals(NotificationChannels.Kind.entries.size, first.toSet().size)
        assertTrue("a category is shared between lines", (first intersect second.toSet()).isEmpty())
        assertEquals("messages.phone-sim-1", NotificationChannels.id(NotificationChannels.Kind.MESSAGES, "phone-sim-1"))
    }

    @Test
    fun groupsNameTheirLine() {
        assertEquals("phone-sim-1", NotificationChannels.lineOf(NotificationChannels.groupId("phone-sim-1")))
        assertNull(NotificationChannels.lineOf("status"))
        assertNull(NotificationChannels.lineOf("line."))
    }

    @Test
    fun appWideCategoriesStayClearOfTheLines() {
        val appWide = listOf(NotificationChannels.STATUS, NotificationChannels.ONGOING_CALL, NotificationChannels.SIM_CARDS)
        assertEquals(appWide.size, appWide.toSet().size)
        for (id in appWide) {
            assertNull(NotificationChannels.lineOf(id))
            assertFalse(NotificationChannels.Kind.entries.any { NotificationChannels.id(it, "status") == id })
        }
    }
}
