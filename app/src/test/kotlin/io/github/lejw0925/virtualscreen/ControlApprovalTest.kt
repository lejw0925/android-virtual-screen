package io.github.lejw0925.virtualscreen

import android.app.Application
import io.github.lejw0925.virtualscreen.backend.*
import io.github.lejw0925.virtualscreen.core.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=Application::class)
class ControlApprovalTest {
    private fun sessions() = VirtualScreenSessionManager(RuntimeEnvironment.getApplication(),
        FakeBackend(BackendKind.ROOT),FakeBackend(BackendKind.SHIZUKU))

    @Test fun approvalRevokesOldLeaseAndRequiresFreshObservation() = runBlocking {
        val sessions = sessions()
        val first = (sessions.acquireLease("first") as LeaseDecision.Granted).lease
        sessions.create(first.owner,first.token)
        val observation = sessions.observe(first.owner,first.token).getOrThrow().values["observation_id"] as String
        val approvals = ControlApprovalManager(sessions)
        val request = approvals.request("second","Agent","Continue the task")
        val id = request.values["approval_id"] as String
        assertEquals("pending",request.values["approval_status"])
        assertEquals(first.token,sessions.leaseStatus()!!.token)
        approvals.approve(id)
        val granted = approvals.status("second",id)
        assertTrue(granted.ok)
        assertEquals("second",sessions.leaseStatus()!!.owner)
        assertEquals("LEASE_REQUIRED",sessions.observe(first.owner,first.token).exceptionOrNull()!!.message)
        assertEquals("STALE_OBSERVATION",runCatching { sessions.operate("second",granted.values["lease_token"] as String,observation,BackendOperation.Tap(10,10)) }.exceptionOrNull()!!.message)
        sessions.shutdown()
    }

    @Test fun allowOnceIsSessionBoundAndCannotBeReplayed() {
        val sessions = sessions(); sessions.phoneTakeover()
        val approvals = ControlApprovalManager(sessions)
        val id = approvals.request("agent","Agent","Resume").values["approval_id"] as String
        assertEquals("CONTROL_REQUEST_NOT_FOUND",approvals.status("other",id).code)
        approvals.approve(id)
        assertFalse(approvals.alwaysAllow.value)
        val generation = sessions.leaseStatus()!!.generation
        approvals.approve(id,always=true)
        assertEquals(generation,sessions.leaseStatus()!!.generation)
        assertFalse(approvals.alwaysAllow.value)
        sessions.phoneTakeover()
        assertEquals("CONTROL_REQUEST_STALE",approvals.status("agent",id).code)
        assertEquals("pending",approvals.request("agent","Agent","Resume again").values["approval_status"])
    }

    @Test fun staleOrExpiredApprovalCannotTransferControlOrPersistPermission() {
        var now = 0L; var saved = false
        val sessions = sessions(); sessions.phoneTakeover()
        val approvals = ControlApprovalManager(sessions,saveAlwaysAllow={ saved=it },nowMillis={ now })
        val stale = approvals.request("agent","Agent","Resume").values["approval_id"] as String
        val phone = sessions.phoneTakeover()
        approvals.approve(stale,always=true)
        assertEquals(phone,sessions.leaseStatus()); assertFalse(saved)
        val expired = approvals.request("agent","Agent","Retry").values["approval_id"] as String
        now = 120_001; approvals.approve(expired)
        assertEquals("CONTROL_REQUEST_EXPIRED",approvals.status("agent",expired).code)
        assertEquals(phone,sessions.leaseStatus()); assertTrue(approvals.pending.value.isEmpty())
    }

    @Test fun alwaysAllowDoesNotInterruptAnActiveGestureOrAnotherAgent() {
        val sessions = sessions(); sessions.phoneTakeover()
        val approvals = ControlApprovalManager(sessions)
        val id = approvals.request("agent","Agent","Resume").values["approval_id"] as String
        approvals.approve(id,always=true)
        sessions.phoneTakeover()
        assertEquals("approved",approvals.request("agent","Agent","Resume again").values["approval_status"])
        val competing = approvals.request("other","Other agent","Competing task")
        assertEquals("pending",competing.values["approval_status"])
        val phone = sessions.beginPhoneGesture()
        val gestureRequest = approvals.request("other","Other agent","Resume after gesture")
        assertEquals("pending",gestureRequest.values["approval_status"])
        approvals.approve(gestureRequest.values["approval_id"] as String)
        assertEquals(phone,sessions.leaseStatus())
        sessions.finishPhoneControl(phone.token)
        assertEquals("approved",approvals.request("other","Other agent","Gesture finished").values["approval_status"])
    }

    @Test fun disconnectCancelAndDenyRemovePendingNotifications() {
        val sessions = sessions(); sessions.phoneTakeover()
        val approvals = ControlApprovalManager(sessions)
        val id = approvals.request("agent","Agent","Resume").values["approval_id"] as String
        assertEquals(id,approvals.request("agent","Agent","Duplicate request").values["approval_id"])
        approvals.deny(id)
        assertEquals("CONTROL_REQUEST_DENIED",approvals.status("agent",id).code)
        val cancelled = approvals.request("agent","Agent","Retry").values["approval_id"] as String
        approvals.cancel("agent",cancelled); approvals.approve(cancelled)
        assertEquals(VirtualScreenSessionManager.PHONE_CLIENT,sessions.leaseStatus()!!.owner)
        approvals.request("agent","Agent","Retry again")
        approvals.forgetClient("agent")
        assertTrue(approvals.pending.value.isEmpty())
        assertEquals("CONTROL_REQUEST_NOT_FOUND",approvals.status("agent",id).code)
    }
}
