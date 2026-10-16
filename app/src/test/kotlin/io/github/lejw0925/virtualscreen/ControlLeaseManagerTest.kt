package io.github.lejw0925.virtualscreen

import io.github.lejw0925.virtualscreen.core.*
import org.junit.Assert.*
import org.junit.Test

class ControlLeaseManagerTest {
    @Test fun exclusiveLeaseExpiresAfterIdleAndTouchRetainsGeneration() {
        var now=0L; val manager=ControlLeaseManager(300000) { now }
        val first=(manager.acquire("A") as LeaseDecision.Granted).lease
        assertEquals(first,(manager.acquire("A") as LeaseDecision.Granted).lease)
        assertEquals("CONTROL_BUSY",(manager.acquire("B") as LeaseDecision.Rejected).code)
        now=299999
        val touched=(manager.touch("A",first.token) as LeaseDecision.Granted).lease
        assertEquals(first.generation,touched.generation)
        now=300001; assertTrue(manager.check("A",first.token))
        now=600000; assertFalse(manager.check("A",first.token)); assertNull(manager.snapshot())
        val second=(manager.acquire("B") as LeaseDecision.Granted).lease
        assertTrue(second.generation > first.generation)
    }
    @Test fun phoneTakeoverRejectsOldTokenAndReleaseDoesNotAffectNewOwner() {
        val manager=ControlLeaseManager()
        val old=(manager.acquire("remote") as LeaseDecision.Granted).lease
        val phone=manager.takeover("phone")
        assertFalse(manager.check(old.owner,old.token))
        assertEquals("LEASE_TOKEN_INVALID",(manager.release(old.owner,old.token) as LeaseDecision.Rejected).code)
        assertTrue(manager.check(phone.owner,phone.token))
    }
}
