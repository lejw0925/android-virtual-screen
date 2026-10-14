package io.github.lejw0925.virtualscreen

import android.app.Application
import io.github.lejw0925.virtualscreen.backend.*
import io.github.lejw0925.virtualscreen.core.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

internal class RestartBackend(private val delegate: FakeBackend = FakeBackend(BackendKind.ROOT)) : VirtualScreenBackend by delegate {
    data class Launch(val component: String, val uri: String?, val approved: Boolean)
    val launches = mutableListOf<Launch>()
    var conflict = true
    var initialGate: CompletableDeferred<Unit>? = null
    val entered = CompletableDeferred<Unit>()
    override suspend fun launch(handle: VirtualScreenHandle, component: String, uri: String?, restartApproved: Boolean): BackendResult {
        launches += Launch(component, uri, restartApproved)
        entered.complete(Unit)
        if (!restartApproved) initialGate?.await()
        return if (conflict && !restartApproved) BackendResult.failure("APP_RESTART_CONFIRMATION_REQUIRED")
        else BackendResult.success("restarted" to restartApproved)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AppRestartApprovalTest {
    private data class Fixture(val backend: RestartBackend, val sessions: VirtualScreenSessionManager, val lease: Lease)
    private suspend fun fixture(nowMillis: () -> Long): Fixture {
        val backend = RestartBackend()
        val sessions = VirtualScreenSessionManager(RuntimeEnvironment.getApplication(), backend, FakeBackend(BackendKind.SHIZUKU),
            restartApprovals = AppRestartApprovalManager(nowMillis))
        val lease = (sessions.acquireLease("remote") as LeaseDecision.Granted).lease
        assertTrue(sessions.create(lease.owner, lease.token).ok)
        return Fixture(backend, sessions, lease)
    }
    private suspend fun Fixture.launch(uri: String? = null) = sessions.launch(lease.owner, lease.token, "fixture/.Main", uri, "在虚拟屏测试应用", "Test agent")

    @Test fun noConflictLaunchesImmediatelyWithoutApproval() = runTest {
        val f = fixture { testScheduler.currentTime }; f.backend.conflict = false
        assertTrue(f.launch().ok)
        assertTrue(f.sessions.restartApprovals.pending.value.isEmpty())
        assertEquals(listOf(RestartBackend.Launch("fixture/.Main", null, false)), f.backend.launches)
    }

    @Test fun approvalWaitsPastOneMinuteAndPreservesLeaseComponentAndUri() = runTest {
        val f = fixture { testScheduler.currentTime }
        val launch = async { f.launch("fixture://exact-target") }; runCurrent()
        val request = f.sessions.restartApprovals.pending.value.single()
        assertEquals("Test agent", request.clientName); assertEquals("在虚拟屏测试应用", request.purpose)
        advanceTimeBy(65_000); runCurrent()
        assertFalse(launch.isCompleted)
        // Waiting for the phone must not block other backend operations.
        f.sessions.healthCheck()
        assertTrue(f.sessions.observe(f.lease.owner, f.lease.token, captureFrame = false).isSuccess)
        assertTrue(f.sessions.restartApprovals.approve(request.id))
        assertTrue(launch.await().ok)
        assertEquals(f.lease.token, f.sessions.leaseStatus()!!.token)
        assertNull(f.sessions.state.value.observationId)
        assertEquals(listOf(false, true), f.backend.launches.map { it.approved })
        assertTrue(f.backend.launches.all { it.component == "fixture/.Main" && it.uri == "fixture://exact-target" })
        assertFalse(f.sessions.restartApprovals.approve(request.id))
        assertTrue(f.sessions.restartApprovals.pending.value.isEmpty())
    }

    @Test fun denyAndUndeliverableNotificationLeaveOriginalAppRunning() = runTest {
        for (undeliverable in listOf(false, true)) {
            val f = fixture { testScheduler.currentTime }
            val launch = async { f.launch() }; runCurrent()
            val id = f.sessions.restartApprovals.pending.value.single().id
            if (undeliverable) f.sessions.restartApprovals.notificationUnavailable(id) else f.sessions.restartApprovals.deny(id)
            assertEquals(if (undeliverable) "NOTIFICATION_PERMISSION_REQUIRED" else "APP_RESTART_DENIED", launch.await().code)
            assertEquals(1, f.backend.launches.size); assertFalse(f.backend.launches.single().approved)
            assertFalse(f.sessions.restartApprovals.approve(id))
        }
    }

    @Test fun timeoutRemovesNotificationAtExactlyTwoMinutes() = runTest {
        val f = fixture { testScheduler.currentTime }
        val launch = async { f.launch() }; runCurrent()
        val id = f.sessions.restartApprovals.pending.value.single().id
        advanceTimeBy(119_999); runCurrent(); assertFalse(launch.isCompleted)
        advanceTimeBy(1); runCurrent()
        assertEquals("APP_RESTART_TIMEOUT", launch.await().code)
        assertTrue(f.sessions.restartApprovals.pending.value.isEmpty())
        assertFalse(f.sessions.restartApprovals.approve(id)); assertEquals(1, f.backend.launches.size)
    }

    @Test fun lateApprovalCannotBeatDeadlineEvenBeforeTimerRuns() = runTest {
        var now = 0L
        val f = fixture { now }
        val launch = async { f.launch() }; runCurrent()
        val id = f.sessions.restartApprovals.pending.value.single().id
        now = 120_000
        assertFalse(f.sessions.restartApprovals.approve(id))
        assertEquals("APP_RESTART_TIMEOUT", launch.await().code)
        assertEquals(1, f.backend.launches.size)
    }

    @Test fun cancellingRequestRemovesApprovalAndReleasesLaunchSlot() = runTest {
        val f = fixture { testScheduler.currentTime }
        val launch = async { f.launch() }; runCurrent()
        val id = f.sessions.restartApprovals.pending.value.single().id
        launch.cancelAndJoin()
        assertTrue(f.sessions.restartApprovals.pending.value.isEmpty()); assertFalse(f.sessions.restartApprovals.approve(id))
        val next = async { f.launch() }; runCurrent()
        assertNotEquals(id, f.sessions.restartApprovals.pending.value.single().id)
        next.cancelAndJoin(); assertTrue(f.backend.launches.none { it.approved })
    }

    @Test fun takeoverCloseDisconnectAndBackendSwitchInvalidateOldApproval() = runTest {
        for (action in listOf("takeover", "close", "disconnect", "backend")) {
            val f = fixture { testScheduler.currentTime }
            val launch = async { f.launch() }; runCurrent()
            val id = f.sessions.restartApprovals.pending.value.single().id
            when (action) {
                "takeover" -> f.sessions.phoneTakeover()
                "close" -> f.sessions.close(f.lease.owner, f.lease.token)
                "disconnect" -> f.sessions.disconnectClient(f.lease.owner)
                else -> f.sessions.selectBackend(BackendKind.SHIZUKU)
            }
            assertEquals(action, "CONTROL_CHANGED", launch.await().code)
            assertTrue(f.sessions.restartApprovals.pending.value.isEmpty()); assertFalse(f.sessions.restartApprovals.approve(id))
            assertTrue(f.backend.launches.none { it.approved })
        }
    }

    @Test fun takeoverAfterAllowButBeforeContinuationCannotRestartApp() = runTest {
        val f = fixture { testScheduler.currentTime }
        val launch = async { runCatching { f.launch() } }; runCurrent()
        f.sessions.restartApprovals.approve(f.sessions.restartApprovals.pending.value.single().id)
        f.sessions.phoneTakeover()
        assertEquals("CONTROL_CHANGED", launch.await().exceptionOrNull()?.message)
        assertEquals(1, f.backend.launches.size)
    }

    @Test fun takeoverDuringInitialProbeCannotCreateStaleNotification() = runTest {
        val f = fixture { testScheduler.currentTime }; f.backend.initialGate = CompletableDeferred()
        val launch = async { runCatching { f.launch() } }; f.backend.entered.await()
        f.sessions.phoneTakeover(); f.backend.initialGate!!.complete(Unit)
        assertEquals("CONTROL_CHANGED", launch.await().exceptionOrNull()?.message)
        assertTrue(f.sessions.restartApprovals.pending.value.isEmpty()); assertEquals(1, f.backend.launches.size)
    }

    @Test fun concurrentLaunchCannotReplacePendingApplicationOrApproval() = runTest {
        val f = fixture { testScheduler.currentTime }
        val first = async { f.launch() }; runCurrent()
        val id = f.sessions.restartApprovals.pending.value.single().id
        val second = f.sessions.launch(f.lease.owner, f.lease.token, "other/.Main")
        assertEquals("APP_LAUNCH_BUSY", second.code)
        assertEquals(id, f.sessions.restartApprovals.pending.value.single().id)
        f.sessions.restartApprovals.approve(id); assertTrue(first.await().ok)
        assertTrue(f.backend.launches.all { it.component == "fixture/.Main" })
    }
}
