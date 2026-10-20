package io.github.lejw0925.virtualscreen

import android.app.Application
import io.github.lejw0925.virtualscreen.backend.BackendKind
import io.github.lejw0925.virtualscreen.core.ControlLeaseManager
import io.github.lejw0925.virtualscreen.core.LeaseDecision
import io.github.lejw0925.virtualscreen.core.VirtualScreenSessionManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LeaseExpiryTest {
    @Test fun idleRemoteLeaseExpiresWithoutClientPollingWhilePreviewKeepsRefreshing() = runTest {
        val backend = FakeBackend(BackendKind.ROOT)
        val manager = VirtualScreenSessionManager(RuntimeEnvironment.getApplication(), backend, FakeBackend(BackendKind.SHIZUKU),
            ControlLeaseManager { testScheduler.currentTime })
        backgroundScope.launch { manager.monitorLeaseExpiry() }
        val lease = (manager.acquireLease("remote") as LeaseDecision.Granted).lease
        assertTrue(manager.create(lease.owner, lease.token).ok)
        manager.observe(lease.owner, lease.token).getOrThrow()
        backgroundScope.launch { while (isActive) { manager.preview(); delay(1000) } }
        runCurrent()
        advanceTimeBy(299_999)
        runCurrent()
        assertNotNull(manager.state.value.lease)
        advanceTimeBy(1)
        runCurrent()
        assertNull(manager.state.value.lease)
        assertNull(manager.state.value.observationId)
        assertNotNull(manager.state.value.displayId)
        assertEquals(0, backend.closed)
        assertTrue(backend.frames > 1)
        assertEquals("LEASE_REQUIRED", manager.observe(lease.owner, lease.token).exceptionOrNull()?.message)
    }

    @Test fun leaseActivityReschedulesExpiryAndReleasedLeaseTimerCannotClearNewOwner() = runTest {
        val manager = VirtualScreenSessionManager(RuntimeEnvironment.getApplication(), FakeBackend(BackendKind.ROOT), FakeBackend(BackendKind.SHIZUKU),
            ControlLeaseManager { testScheduler.currentTime })
        backgroundScope.launch { manager.monitorLeaseExpiry() }
        val first = (manager.acquireLease("first") as LeaseDecision.Granted).lease
        assertTrue(manager.create(first.owner, first.token).ok)
        runCurrent()
        advanceTimeBy(299_999)
        manager.observe(first.owner, first.token, captureFrame = false).getOrThrow()
        runCurrent()
        advanceTimeBy(1)
        runCurrent()
        assertEquals(first.token, manager.state.value.lease?.token)
        assertTrue(manager.releaseLease(first.owner, first.token) is LeaseDecision.Granted)
        val next = (manager.acquireLease("next") as LeaseDecision.Granted).lease
        runCurrent()
        advanceTimeBy(299_999)
        runCurrent()
        assertEquals(next.token, manager.state.value.lease?.token)
        advanceTimeBy(1)
        runCurrent()
        assertNull(manager.state.value.lease)
    }

    @Test fun previewCannotStartAfterLeaseReleaseButCanStartAfterReacquiringControl() = runTest {
        val backend = FakeBackend(BackendKind.ROOT)
        val manager = VirtualScreenSessionManager(RuntimeEnvironment.getApplication(), backend, FakeBackend(BackendKind.SHIZUKU))
        val first = (manager.acquireLease("remote") as LeaseDecision.Granted).lease
        assertTrue(manager.create(first.owner, first.token).ok)
        assertEquals(true, manager.showRemotePreview().values["preview_requested"])
        manager.releaseLease(first.owner, first.token)
        assertEquals(false, manager.showRemotePreview().values["preview_requested"])
        assertEquals(1, backend.previews)
        manager.acquireLease("remote")
        assertEquals(true, manager.showRemotePreview().values["preview_requested"])
        assertEquals(2, backend.previews)
    }

    @Test fun notificationCloseInvalidatesTheLeaseAndOnlyClosesItsOriginalSession() = runTest {
        val backend = FakeBackend(BackendKind.ROOT)
        val manager = VirtualScreenSessionManager(RuntimeEnvironment.getApplication(), backend, FakeBackend(BackendKind.SHIZUKU))
        val first = (manager.acquireLease("remote") as LeaseDecision.Granted).lease
        assertTrue(manager.create(first.owner, first.token).ok)
        val session = manager.state.value.sessionId!!
        manager.observe(first.owner, first.token, captureFrame = false).getOrThrow()
        manager.phoneTakeover()
        manager.closeFromPhone(session)
        assertEquals(1, backend.closed)
        assertNull(manager.state.value.displayId)
        assertNull(manager.state.value.observationId)
        assertNull(manager.leaseStatus())
        assertEquals("LEASE_REQUIRED", manager.observe(first.owner, first.token).exceptionOrNull()?.message)

        val next = (manager.acquireLease("next") as LeaseDecision.Granted).lease
        assertTrue(manager.create(next.owner, next.token).ok)
        val nextSession = manager.state.value.sessionId
        manager.closeFromPhone(session)
        assertEquals(nextSession, manager.state.value.sessionId)
        assertNotNull(manager.state.value.displayId)
        assertEquals(next.token, manager.leaseStatus()!!.token)
        assertEquals(1, backend.closed)
        manager.shutdown()
    }
}
