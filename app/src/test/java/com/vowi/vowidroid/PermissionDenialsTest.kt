package com.vowi.vowidroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Refusals stay per permission, the newest is the one put into words, and a granted permission clears its refusal. */
class PermissionDenialsTest {
    @Test
    fun refusalsStayUntilTheirPermissionIsGranted() {
        PermissionDenials.refresh { true }
        PermissionDenials.report(AppPermissions.Need.SIM_ACCESS, 1)
        PermissionDenials.report(AppPermissions.Need.NOTIFICATIONS, 2)
        PermissionDenials.report(AppPermissions.Need.SIM_ACCESS, 3)
        val denials = PermissionDenials.denied.value
        assertEquals(setOf(AppPermissions.Need.SIM_ACCESS, AppPermissions.Need.NOTIFICATIONS), denials.keys)
        assertEquals(3, denials.getValue(AppPermissions.Need.SIM_ACCESS).what)
        assertEquals(AppPermissions.Need.SIM_ACCESS, PermissionDenials.latest(denials)?.need)

        PermissionDenials.refresh { it == AppPermissions.Need.SIM_ACCESS }
        assertEquals(setOf(AppPermissions.Need.NOTIFICATIONS), PermissionDenials.denied.value.keys)

        PermissionDenials.refresh { true }
        assertTrue(PermissionDenials.denied.value.isEmpty())
        assertNull(PermissionDenials.latest(PermissionDenials.denied.value))
    }
}
