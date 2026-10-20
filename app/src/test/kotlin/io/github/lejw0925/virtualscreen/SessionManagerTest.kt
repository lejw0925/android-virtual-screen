package io.github.lejw0925.virtualscreen

import android.app.Application
import android.content.Context
import io.github.lejw0925.virtualscreen.backend.*
import io.github.lejw0925.virtualscreen.core.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

internal class FakeBackend(override val kind: BackendKind): VirtualScreenBackend {
    var closed=0; var inputs=0; var cancelled=0
    var frames=0; var inspections=0
    var previews=0
    var observationFailure: String?=null
    var inspectionFailure: String?=null
    var nodeFailure: String?=null
    var testNodes: List<NodeInfo> = emptyList()
    var truncated=false
    var createGate: CompletableDeferred<Unit>?=null
    val entered=CompletableDeferred<Unit>()
    override suspend fun probe()=BackendCapabilities(displayCreation=CapabilityStatus.AVAILABLE)
    override suspend fun create(config: VirtualScreenConfig): Result<VirtualScreenHandle> {
        entered.complete(Unit); createGate?.await(); return Result.success(VirtualScreenHandle("handle",7,config))
    }
    override suspend fun close(handle: VirtualScreenHandle)=BackendResult.success().also { closed++ }
    override suspend fun launch(handle: VirtualScreenHandle,component: String,uri: String?,restartApproved: Boolean)=BackendResult.success()
    override suspend fun observe(handle: VirtualScreenHandle): Result<ScreenObservation> {
        frames++
        return observationFailure?.let { Result.failure(IllegalStateException(it)) } ?: Result.success(ScreenObservation("backend-id",handle.id,0,720,1600,0))
    }
    override suspend fun inspect(handle: VirtualScreenHandle): Result<ScreenObservation> {
        inspections++
        return inspectionFailure?.let { Result.failure(IllegalStateException(it)) }
            ?: Result.success(ScreenObservation("backend-id",handle.id,0,720,1600,0))
    }
    override suspend fun showPreview(handle: VirtualScreenHandle)=BackendResult.success("preview_requested" to true).also { previews++ }
    override suspend fun nodes(handle: VirtualScreenHandle,observationId: String)=nodeFailure?.let { Result.failure(IllegalStateException(it)) }
        ?: Result.success(NodeSnapshot(observationId,handle.displayId,"fixture",testNodes,truncated))
    override suspend fun operate(handle: VirtualScreenHandle,operation: BackendOperation)=BackendResult.success().also { inputs++ }
    override fun cancelActive() { cancelled++ }
}
@RunWith(RobolectricTestRunner::class) @Config(sdk=[34],application=Application::class)
class SessionManagerTest {
    private val context: Context get()=RuntimeEnvironment.getApplication()
    @Test fun startingServerClearsTheInactiveDisplayError() = runBlocking {
        val backend = object : VirtualScreenBackend by FakeBackend(BackendKind.ROOT) {
            override suspend fun create(config: VirtualScreenConfig) =
                Result.failure<VirtualScreenHandle>(IllegalStateException("DISPLAY_GONE"))
        }
        val manager = VirtualScreenSessionManager(context, backend, FakeBackend(BackendKind.SHIZUKU))
        val lease = (manager.acquireLease("remote") as LeaseDecision.Granted).lease
        assertFalse(manager.create(lease.owner, lease.token).ok)
        assertEquals("DISPLAY_GONE", manager.state.value.errorCode)

        manager.clearInactiveDisplayError()

        assertEquals(SessionStatus.STOPPED, manager.state.value.status)
        assertNull(manager.state.value.errorCode)
        assertNull(manager.state.value.displayId)
    }
    @Test fun closingDisplayClearsThePreviousCreationError() = runBlocking {
        val backend = object : VirtualScreenBackend by FakeBackend(BackendKind.ROOT) {
            override suspend fun create(config: VirtualScreenConfig) =
                Result.failure<VirtualScreenHandle>(IllegalStateException("DISPLAY_GONE"))
        }
        val manager = VirtualScreenSessionManager(context, backend, FakeBackend(BackendKind.SHIZUKU))
        val lease = (manager.acquireLease("remote") as LeaseDecision.Granted).lease
        assertFalse(manager.create(lease.owner, lease.token).ok)
        assertEquals("DISPLAY_GONE", manager.state.value.errorCode)

        assertTrue(manager.close(lease.owner, lease.token).ok)

        assertEquals(SessionStatus.STOPPED, manager.state.value.status)
        assertNull(manager.state.value.errorCode)
    }
    @Test fun inputConsumesObservationAndTakeoverFencesOldOwner()=runBlocking {
        val root=FakeBackend(BackendKind.ROOT); val manager=VirtualScreenSessionManager(context,root,FakeBackend(BackendKind.SHIZUKU))
        val lease=(manager.acquireLease("remote") as LeaseDecision.Granted).lease
        assertTrue(manager.create(lease.owner,lease.token).ok)
        val observation=manager.observe(lease.owner,lease.token).getOrThrow().values["observation_id"] as String
        assertTrue(manager.operate(lease.owner,lease.token,observation,BackendOperation.Tap(10,10)).ok)
        assertEquals("STALE_OBSERVATION",runCatching { manager.operate(lease.owner,lease.token,observation,BackendOperation.Tap(10,10)) }.exceptionOrNull()?.message)
        assertEquals(1,root.inputs)
        manager.phoneTakeover()
        assertEquals("LEASE_REQUIRED",manager.observe(lease.owner,lease.token).exceptionOrNull()?.message)
    }
    @Test fun takeoverDuringCreateReleasesOrphanDisplay()=runBlocking {
        val root=FakeBackend(BackendKind.ROOT).apply { createGate=CompletableDeferred() }
        val manager=VirtualScreenSessionManager(context,root,FakeBackend(BackendKind.SHIZUKU))
        val lease=(manager.acquireLease("remote") as LeaseDecision.Granted).lease
        val task=async { manager.create(lease.owner,lease.token) }
        root.entered.await(); manager.phoneTakeover(); root.createGate!!.complete(Unit)
        assertFalse(task.await().ok); assertEquals(1,root.closed); assertNull(manager.state.value.displayId)
    }
    @Test fun fullScreenPhoneSessionKeepsLeaseAcrossLocalGesturesAndReleasesOnExit()=runBlocking {
        val manager=VirtualScreenSessionManager(context,FakeBackend(BackendKind.ROOT),FakeBackend(BackendKind.SHIZUKU))
        manager.beginPhoneSession("viewer")
        val gesture=manager.beginPhoneGesture()
        manager.finishPhoneControl(gesture.token)
        assertEquals(gesture,manager.leaseStatus())
        manager.endPhoneSession("viewer")
        assertNull(manager.leaseStatus())
    }
    @Test fun oldViewerExitCannotReleaseNewViewerOrRemoteLease() {
        val manager=VirtualScreenSessionManager(context,FakeBackend(BackendKind.ROOT),FakeBackend(BackendKind.SHIZUKU))
        manager.beginPhoneSession("old")
        val phone=manager.beginPhoneSession("new")
        manager.endPhoneSession("old")
        assertEquals(phone.token,manager.leaseStatus()?.token)
        assertTrue(manager.acquireLease("remote") is LeaseDecision.Rejected)
        manager.endPhoneSession("new")
        val remote=(manager.acquireLease("remote") as LeaseDecision.Granted).lease
        manager.endPhoneSession("old")
        assertEquals(remote,manager.leaseStatus())
    }
    @Test fun visiblePhoneViewerRetainsControlPastIdleLeaseExpiry() {
        var clock=0L
        val manager=VirtualScreenSessionManager(context,FakeBackend(BackendKind.ROOT),FakeBackend(BackendKind.SHIZUKU),ControlLeaseManager(100) { clock })
        manager.beginPhoneSession("viewer")
        clock=101
        assertTrue(manager.acquireLease("remote") is LeaseDecision.Rejected)
        assertEquals(VirtualScreenSessionManager.PHONE_CLIENT,manager.leaseStatus()?.owner)
        manager.endPhoneSession("viewer")
        assertTrue(manager.acquireLease("remote") is LeaseDecision.Granted)
    }
    @Test fun leaseAuthenticatedOperationMovesExpiryToFiveMinutesAfterThatOperation() = runBlocking {
        var clock=0L
        val manager=VirtualScreenSessionManager(context,FakeBackend(BackendKind.ROOT),FakeBackend(BackendKind.SHIZUKU),ControlLeaseManager(300_000) { clock })
        val lease=(manager.acquireLease("remote") as LeaseDecision.Granted).lease
        assertTrue(manager.create(lease.owner,lease.token).ok)
        clock=299_999
        assertTrue(manager.observe(lease.owner,lease.token,captureFrame=false).isSuccess)
        clock=599_998
        assertEquals("remote",manager.leaseStatus()?.owner)
        clock=599_999
        assertNull(manager.leaseStatus())
        manager.shutdown()
    }
    @Test fun backendSwitchReleasesActiveDisplayBeforeChanging()=runBlocking {
        val root=FakeBackend(BackendKind.ROOT); val manager=VirtualScreenSessionManager(context,root,FakeBackend(BackendKind.SHIZUKU))
        val lease=(manager.acquireLease("remote") as LeaseDecision.Granted).lease
        manager.create(lease.owner,lease.token)
        manager.selectBackend(BackendKind.SHIZUKU)
        assertEquals(1,root.closed); assertEquals(BackendKind.SHIZUKU,manager.state.value.backend); assertNull(manager.state.value.displayId)
    }
    @Test fun onlyOwnerCanCancelAndExpiredLeaseCannotReuseObservation()=runBlocking {
        var clock=0L
        val root=FakeBackend(BackendKind.ROOT)
        val manager=VirtualScreenSessionManager(context,root,FakeBackend(BackendKind.SHIZUKU),ControlLeaseManager(100) { clock })
        val lease=(manager.acquireLease("remote") as LeaseDecision.Granted).lease
        manager.create(lease.owner,lease.token)
        val observed=manager.observe(lease.owner,lease.token).getOrThrow().values["observation_id"] as String
        val before=root.cancelled
        manager.cancelActive("other-client")
        assertEquals(before,root.cancelled)
        assertEquals(observed,manager.state.value.observationId)
        manager.cancelActive("remote")
        assertNull(manager.state.value.observationId)
        clock=101
        val successor=(manager.acquireLease("successor") as LeaseDecision.Granted).lease
        assertEquals("STALE_OBSERVATION",runCatching { manager.operate(successor.owner,successor.token,observed,BackendOperation.Tap(10,10)) }.exceptionOrNull()?.message)
        assertEquals(0,root.inputs)
    }
}
