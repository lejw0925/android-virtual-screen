package io.github.lejw0925.virtualscreen

import android.app.Application
import io.github.lejw0925.virtualscreen.backend.*
import io.github.lejw0925.virtualscreen.core.*
import io.github.lejw0925.virtualscreen.mcp.*
import io.github.lejw0925.virtualscreen.ui.UiLanguage
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class) @Config(sdk=[34],application=Application::class)
class McpToolsTest {
    @Test fun controlAndTaskMigrationErrorsExplainRecoveryWithoutReplayingInput() {
        val lease=McpToolRegistry.encode(OperationEnvelope.failure("LEASE_REQUIRED")).getJSONObject("structuredContent")
        assertTrue(lease.getBoolean("requires_control"))
        assertTrue(lease.getBoolean("requires_observation"))
        val leaseTools=lease.getJSONArray("recovery_tools")
        assertEquals(listOf("get_status","acquire_control","observe"),List(leaseTools.length()) { leaseTools.getString(it) })
        val moved=McpToolRegistry.encode(OperationEnvelope.failure("APP_MOVED_TO_PHONE")).getJSONObject("structuredContent")
        assertTrue(moved.getBoolean("requires_relaunch"))
        val movedTools=moved.getJSONArray("recovery_tools")
        assertEquals(listOf("launch_app","observe"),List(movedTools.length()) { movedTools.getString(it) })
    }
    @Test fun configuringAnAbsentDisplayCannotCreateOne() = runBlocking {
        val root=FakeBackend(BackendKind.ROOT)
        val sessions=VirtualScreenSessionManager(RuntimeEnvironment.getApplication(),root,FakeBackend(BackendKind.SHIZUKU))
        val lease=(sessions.acquireLease("test") as LeaseDecision.Granted).lease
        val result=McpToolRegistry(sessions).call("configure_screen",JSONObject().put("lease_token",lease.token)
            .put("purpose","Reconfigure an existing display"),"test").getJSONObject("structuredContent")
        assertEquals("NO_VIRTUAL_SCREEN",result.getString("code"))
        assertNull(sessions.state.value.displayId)
    }

    @Test fun splitToolsExposeOnlyApplicableParametersAndRejectMixedActions() = runBlocking {
        val root=FakeBackend(BackendKind.ROOT)
        val sessions=VirtualScreenSessionManager(RuntimeEnvironment.getApplication(),root,FakeBackend(BackendKind.SHIZUKU))
        val registry=McpToolRegistry(sessions)
        val specs=registry.tools.associateBy { it.name }
        assertEquals(setOf("acquire_control","release_control","request_control","get_status","get_request_receipt",
            "create_screen","configure_screen","close_screen","list_apps","launch_app","observe","tap_at","long_press_at","swipe_path","scroll_at",
            "input_text","node_action","press_key","wait"),specs.keys)
        assertFalse(specs.getValue("acquire_control").schema.getJSONObject("properties").has("lease_token"))
        assertTrue(specs.getValue("get_request_receipt").schema.getJSONArray("required").toString().contains("request_id"))
        assertTrue(specs.getValue("list_apps").readOnly)
        assertFalse(specs.getValue("launch_app").readOnly)
        val invalid=mapOf("tap_at" to JSONObject().put("points",JSONArray("[[1,2],[3,4]]")).put("x",5).put("y",5),
            "swipe_path" to JSONObject().put("points",JSONArray("[[1,2],[3,4]]")).put("x",5),
            "close_screen" to JSONObject().put("width",720),"list_apps" to JSONObject().put("component","fixture/.Main"))
        for((name,args) in invalid) {
            args.put("purpose","Reject mixed operation arguments").put("lease_token","fixture")
            if(name in setOf("tap_at","swipe_path")) args.put("observation_id","fixture")
            assertEquals(name,"UNKNOWN_ARGUMENT",registry.call(name,args,"test").getJSONObject("structuredContent").getString("code"))
        }
        assertEquals("UNKNOWN_TOOL",registry.call("renew_control",JSONObject().put("purpose","Reject manual lease renewal"),"test").getJSONObject("structuredContent").getString("code"))
        assertEquals("UNKNOWN_TOOL",registry.call("pointer",JSONObject().put("purpose","Check removed multiplexed tool"),"test").getJSONObject("structuredContent").getString("code"))
        assertEquals(0,root.inputs)
    }

    @Test fun displayDefaultsAndResolutionOverridesPreserveHostDensity() = runBlocking {
        val sessions=VirtualScreenSessionManager(RuntimeEnvironment.getApplication(),FakeBackend(BackendKind.ROOT),FakeBackend(BackendKind.SHIZUKU),
            configuredDisplay={ VirtualScreenConfig(1216,2493,560) })
        val registry=McpToolRegistry(sessions)
        val lease=(sessions.acquireLease("agent") as LeaseDecision.Granted).lease
        suspend fun screen(action: String,extra: Map<String,Int> = emptyMap()) = registry.call("${action}_screen",JSONObject(extra).put("lease_token",lease.token).put("purpose","Verify display density"),"agent").getJSONObject("structuredContent")
        assertTrue(screen("create").getBoolean("ok"))
        assertEquals(VirtualScreenConfig(1216,2493,560),sessions.state.value.config)
        screen("close")
        assertTrue(screen("create",mapOf("width" to 720)).getBoolean("ok"))
        assertEquals(720,sessions.state.value.config.width)
        assertEquals(1216.0/560,720.0/sessions.state.value.config.density,.01)
        screen("close")
        assertTrue(screen("create",mapOf("width" to 720,"density" to 320)).getBoolean("ok"))
        assertEquals(320,sessions.state.value.config.density)
        sessions.shutdown()
    }

    @Test fun controlRequestPollRequiresMatchingAuthenticatedSession() = runBlocking {
        val sessions=VirtualScreenSessionManager(RuntimeEnvironment.getApplication(),FakeBackend(BackendKind.ROOT),FakeBackend(BackendKind.SHIZUKU))
        sessions.phoneTakeover()
        val approvals=ControlApprovalManager(sessions)
        val registry=McpToolRegistry(sessions,approvals)
        val pending=registry.call("request_control",JSONObject().put("action","request").put("purpose","Resume approved task"),"agent").getJSONObject("structuredContent")
        assertEquals("pending",pending.getString("approval_status")); assertFalse(pending.has("lease_token"))
        val id=pending.getString("approval_id")
        approvals.approve(id)
        val args=JSONObject().put("action","status").put("approval_id",id).put("purpose","Check control approval")
        assertEquals("CONTROL_REQUEST_NOT_FOUND",registry.call("request_control",args,"other").getJSONObject("structuredContent").getString("code"))
        assertTrue(registry.call("request_control",args,"agent").getJSONObject("structuredContent").has("lease_token"))
        sessions.shutdown()
    }
    @Test fun missingPurposeAndInapplicableArgumentsNeverDispatchInput() = runBlocking {
        val root=FakeBackend(BackendKind.ROOT)
        val sessions=VirtualScreenSessionManager(RuntimeEnvironment.getApplication(),root,FakeBackend(BackendKind.SHIZUKU))
        val registry=McpToolRegistry(sessions)
        val lease=(sessions.acquireLease("test") as LeaseDecision.Granted).lease
        sessions.create("test",lease.token)
        val observed=sessions.observe("test",lease.token).getOrThrow().values.getValue("observation_id")
        val args=JSONObject().put("lease_token",lease.token).put("observation_id",observed).put("x",10).put("y",20)
        assertEquals("PURPOSE_REQUIRED",registry.call("tap_at",args,"test").getJSONObject("structuredContent").getString("code"))
        args.put("purpose","Verify the tap counter").put("points",JSONArray("[[10,20],[20,30]]"))
        assertEquals("UNKNOWN_ARGUMENT",registry.call("tap_at",args,"test").getJSONObject("structuredContent").getString("code"))
        args.remove("points")
        assertTrue(registry.call("tap_at",args,"test").getJSONObject("structuredContent").getBoolean("ok"))
        assertEquals(1,root.inputs)
        sessions.shutdown()
    }
    @Test fun acceptedInputReturnsFreshObservationByDefault() = runBlocking {
        val root=FakeBackend(BackendKind.ROOT)
        val sessions=VirtualScreenSessionManager(RuntimeEnvironment.getApplication(),root,FakeBackend(BackendKind.SHIZUKU))
        val registry=McpToolRegistry(sessions)
        val lease=(sessions.acquireLease("test") as LeaseDecision.Granted).lease
        sessions.create("test",lease.token)
        val initial=sessions.observe("test",lease.token).getOrThrow().values.getValue("observation_id") as String
        val args=JSONObject().put("purpose","Click and continue from the new state").put("lease_token",lease.token)
            .put("observation_id",initial).put("x",10).put("y",20)
        val result=registry.call("tap_at",args,"test").getJSONObject("structuredContent")
        assertEquals("available",result.getString("observation_status"))
        assertFalse(result.getBoolean("requires_observation"))
        val next=result.getString("observation_id")
        assertNotEquals(initial,next)
        assertEquals(2,root.frames)
        args.put("observation_id",next)
        assertTrue(registry.call("tap_at",args,"test").getJSONObject("structuredContent").getBoolean("ok"))
        assertEquals(2,root.inputs)
        sessions.shutdown()
    }
    @Test fun launchReturnsFreshObservationByDefaultAndCanOptOut() = runBlocking {
        val root=FakeBackend(BackendKind.ROOT)
        val sessions=VirtualScreenSessionManager(RuntimeEnvironment.getApplication(),root,FakeBackend(BackendKind.SHIZUKU))
        val registry=McpToolRegistry(sessions)
        val lease=(sessions.acquireLease("test") as LeaseDecision.Granted).lease
        sessions.create("test",lease.token)
        val launch=JSONObject().put("purpose","Open the fixture app").put("lease_token",lease.token)
            .put("component","io.github.lejw0925.virtualscreen.fixture/.FixtureActivity")
        val result=registry.call("launch_app",launch,"test").getJSONObject("structuredContent")
        assertEquals("available",result.getString("observation_status"))
        assertFalse(result.getBoolean("requires_observation"))
        assertTrue(result.has("observation_id"))
        launch.put("observe_after","none")
        val optedOut=registry.call("launch_app",launch,"test").getJSONObject("structuredContent")
        assertFalse(optedOut.has("observation_status"))
        assertTrue(optedOut.getBoolean("requires_observation"))
        assertTrue(optedOut.getBoolean("ok"))
        sessions.shutdown()
    }
    @Test fun reconfigureReturnsFreshObservationAfterRebuildingDisplay() = runBlocking {
        val root=FakeBackend(BackendKind.ROOT)
        val sessions=VirtualScreenSessionManager(RuntimeEnvironment.getApplication(),root,FakeBackend(BackendKind.SHIZUKU))
        val registry=McpToolRegistry(sessions)
        val lease=(sessions.acquireLease("test") as LeaseDecision.Granted).lease
        sessions.create("test",lease.token)
        val result=registry.call("configure_screen",JSONObject().put("purpose","Rebuild the display").put("lease_token",lease.token),"test")
            .getJSONObject("structuredContent")
        assertEquals("available",result.getString("observation_status"))
        assertFalse(result.getBoolean("requires_observation"))
        assertTrue(result.has("observation_id"))
        assertEquals(1,root.closed)
        sessions.shutdown()
    }
    @Test fun malformedSwipeAndMissingCoordinatesPreserveObservation() = runBlocking {
        val root=FakeBackend(BackendKind.ROOT)
        val sessions=VirtualScreenSessionManager(RuntimeEnvironment.getApplication(),root,FakeBackend(BackendKind.SHIZUKU))
        val registry=McpToolRegistry(sessions)
        val lease=(sessions.acquireLease("test") as LeaseDecision.Granted).lease
        sessions.create("test",lease.token)
        val observed=sessions.observe("test",lease.token).getOrThrow().values.getValue("observation_id")
        val args=JSONObject().put("lease_token",lease.token).put("observation_id",observed).put("purpose","Verify swipe validation").put("points",JSONArray("[[1.5,2],[3,4]]"))
        assertEquals("INVALID_ARGUMENT",registry.call("swipe_path",args,"test").getJSONObject("structuredContent").getString("code"))
        args.remove("points"); args
        assertEquals("MISSING_ARGUMENT",registry.call("tap_at",args,"test").getJSONObject("structuredContent").getString("code"))
        assertEquals(observed,sessions.state.value.observationId); assertEquals(0,root.inputs)
        sessions.shutdown()
    }
    @Test fun localizedDiscoveryAndFeedbackKeepStableMachineNames() = runBlocking {
        val sessions=VirtualScreenSessionManager(RuntimeEnvironment.getApplication(),FakeBackend(BackendKind.ROOT),FakeBackend(BackendKind.SHIZUKU))
        var language=UiLanguage.ZH_HANS
        val registry=McpToolRegistry(sessions) { language }
        val names=registry.tools.map { it.name }; assertEquals(19,names.size)
        for(l in UiLanguage.entries) {
            language=l
            assertEquals(names,registry.tools.map { it.name })
            val schema=registry.tools.first().schema
            assertTrue(schema.getJSONArray("required").toString().contains("purpose"))
            val error=registry.call("get_status",JSONObject(),"viewer").getJSONObject("structuredContent")
            assertEquals("PURPOSE_REQUIRED",error.getString("code")); assertEquals(l.tag,error.getString("language"))
            assertTrue(error.getString("message").isNotBlank())
        }
        language=UiLanguage.ZH_HANT
        assertTrue(registry.tools.first { it.name=="create_screen" }.description.contains("虛擬螢幕"))
    }
    @Test fun competingClientCannotReplaceTheActiveOperationPurpose() = runBlocking {
        val root=FakeBackend(BackendKind.ROOT).apply { createGate=CompletableDeferred() }
        val sessions=VirtualScreenSessionManager(RuntimeEnvironment.getApplication(),root,FakeBackend(BackendKind.SHIZUKU))
        val registry=McpToolRegistry(sessions)
        val lease=(sessions.acquireLease("controller") as LeaseDecision.Granted).lease
        val purpose="Create a screen to test the app"
        val operation=async { registry.call("create_screen",JSONObject().put("lease_token",lease.token).put("purpose",purpose),"controller") }
        try {
            root.entered.await()
            val rejected=registry.call("acquire_control",JSONObject().put("purpose","Request control"),"other").getJSONObject("structuredContent")
            assertFalse(rejected.getBoolean("ok"))
            registry.call("get_status",JSONObject().put("purpose","Check availability"),"other")
            assertEquals("controller",registry.activity.value?.owner)
            assertEquals(purpose,registry.activity.value?.purpose)
            assertEquals("running",registry.activity.value?.phase)
            root.createGate!!.complete(Unit)
            assertTrue(operation.await().getJSONObject("structuredContent").getBoolean("ok"))
            assertEquals("completed",registry.activity.value?.phase)
        } finally { root.createGate!!.complete(Unit); operation.cancelAndJoin(); sessions.shutdown() }
    }
    @Test fun mcpCreateUsesTheConfiguredPhoneViewportInsteadOfFixedDefaults() = runBlocking {
        val config=VirtualScreenConfig(720,1476,320)
        val sessions=VirtualScreenSessionManager(RuntimeEnvironment.getApplication(),FakeBackend(BackendKind.ROOT),FakeBackend(BackendKind.SHIZUKU),configuredDisplay={ config })
        val registry=McpToolRegistry(sessions)
        val lease=(sessions.acquireLease("remote") as LeaseDecision.Granted).lease
        val result=registry.call("create_screen",JSONObject().put("lease_token",lease.token).put("purpose","Verify phone viewport defaults"),"remote")
        assertTrue(result.getJSONObject("structuredContent").getBoolean("ok"))
        assertEquals(config,sessions.state.value.config)
        sessions.shutdown()
    }

    @Test fun unsupportedKeysAndShortLongPressAreRejectedBeforeInput() = runBlocking {
        val root=FakeBackend(BackendKind.ROOT)
        val sessions=VirtualScreenSessionManager(RuntimeEnvironment.getApplication(),root,FakeBackend(BackendKind.SHIZUKU))
        val registry=McpToolRegistry(sessions)
        val lease=(sessions.acquireLease("test") as LeaseDecision.Granted).lease
        sessions.create("test",lease.token)
        val id=sessions.observe("test",lease.token).getOrThrow().values["observation_id"]
        val args=JSONObject().put("purpose","Validate navigation isolation").put("lease_token",lease.token).put("observation_id",id).put("key_code",3)
        assertEquals("INVALID_ARGUMENT",registry.call("press_key",args,"test").getJSONObject("structuredContent").getString("code"))
        args.remove("key_code"); args.put("x",5).put("y",5).put("duration_ms",100)
        assertEquals("INVALID_ARGUMENT",registry.call("long_press_at",args,"test").getJSONObject("structuredContent").getString("code"))
        assertEquals(0,root.inputs); assertEquals(id,sessions.state.value.observationId)
    }
    @Test fun treeObservationAvoidsFrameCaptureAndAmbiguousSelectorDoesNotAct() = runBlocking {
        val root=FakeBackend(BackendKind.ROOT).apply { testNodes=listOf(NodeInfo("one",text="OK",clickable=true),NodeInfo("two",text="OK",clickable=true)) }
        val sessions=VirtualScreenSessionManager(RuntimeEnvironment.getApplication(),root,FakeBackend(BackendKind.SHIZUKU))
        val registry=McpToolRegistry(sessions)
        val lease=(sessions.acquireLease("test") as LeaseDecision.Granted).lease
        sessions.create("test",lease.token)
        val args=JSONObject().put("purpose","Find a unique button").put("lease_token",lease.token).put("mode","tree")
        val observed=registry.call("observe",args,"test").getJSONObject("structuredContent")
        assertTrue(observed.getBoolean("ok")); assertEquals(0,root.frames); assertEquals(1,root.inspections)
        args.remove("mode"); args.put("observation_id",observed.getString("observation_id")).put("action","click").put("selector",JSONObject().put("text","OK"))
        assertEquals("AMBIGUOUS_NODE",registry.call("node_action",args,"test").getJSONObject("structuredContent").getString("code")); assertEquals(0,root.inputs)
        root.testNodes=listOf(root.testNodes.first())
        args.remove("action"); args.remove("selector"); args.remove("observation_id"); args.put("mode","tree")
        val fresh=registry.call("observe",args,"test").getJSONObject("structuredContent")
        args.remove("mode"); args.put("observation_id",fresh.getString("observation_id")).put("action","click").put("selector",JSONObject().put("text","OK"))
        assertTrue(registry.call("node_action",args,"test").getJSONObject("structuredContent").getBoolean("ok")); assertEquals(1,root.inputs)
    }
    @Test fun acceptedActionIsNotRepeatedWhenAfterObservationFails() = runBlocking {
        val root=FakeBackend(BackendKind.ROOT)
        val sessions=VirtualScreenSessionManager(RuntimeEnvironment.getApplication(),root,FakeBackend(BackendKind.SHIZUKU))
        val registry=McpToolRegistry(sessions)
        val lease=(sessions.acquireLease("test") as LeaseDecision.Granted).lease
        sessions.create("test",lease.token)
        val id=sessions.observe("test",lease.token).getOrThrow().values["observation_id"]
        root.observationFailure="DISPLAY_FRAME_PENDING"
        val args=JSONObject().put("purpose","Click and inspect the result").put("lease_token",lease.token).put("observation_id",id).put("x",5).put("y",5).put("observe_after","both")
        val result=registry.call("tap_at",args,"test").getJSONObject("structuredContent")
        assertTrue(result.getBoolean("ok")); assertEquals("accepted",result.getString("execution_status")); assertEquals("failed",result.getString("observation_status")); assertTrue(result.getBoolean("requires_observation"))
        assertEquals(1,root.inputs); assertNull(sessions.state.value.observationId)
        assertEquals("STALE_OBSERVATION",registry.call("tap_at",args,"test").getJSONObject("structuredContent").getString("code")); assertEquals(1,root.inputs)
    }
    @Test fun conditionalWaitInputsAndTruncatedAbsenceCannotReportSuccess() = runBlocking {
        val root=FakeBackend(BackendKind.ROOT).apply { truncated=true }
        val sessions=VirtualScreenSessionManager(RuntimeEnvironment.getApplication(),root,FakeBackend(BackendKind.SHIZUKU))
        val registry=McpToolRegistry(sessions)
        val lease=(sessions.acquireLease("test") as LeaseDecision.Granted).lease
        sessions.create("test",lease.token)
        val args=JSONObject().put("purpose","Wait for a missing node").put("lease_token",lease.token).put("condition","node_absent").put("timeout_ms",0)
        assertEquals("MISSING_ARGUMENT",registry.call("wait",args,"test").getJSONObject("structuredContent").getString("code"))
        args.put("selector",JSONObject().put("text","missing"))
        assertEquals("NODE_TREE_TRUNCATED",registry.call("wait",args,"test").getJSONObject("structuredContent").getString("code"))
    }
    @Test fun receiptQueriesCannotExecuteInputOrReadAnotherClientsResult() = runBlocking {
        val root=FakeBackend(BackendKind.ROOT)
        val sessions=VirtualScreenSessionManager(RuntimeEnvironment.getApplication(),root,FakeBackend(BackendKind.SHIZUKU))
        val registry=McpToolRegistry(sessions)
        val lease=(sessions.acquireLease("test") as LeaseDecision.Granted).lease
        sessions.create("test",lease.token)
        val id=sessions.observe("test",lease.token).getOrThrow().values["observation_id"]
        val tap=JSONObject().put("purpose","Test a single input").put("lease_token",lease.token).put("observation_id",id).put("x",5).put("y",5)
        assertTrue(registry.call("tap_at",tap,"test","tap-request").getJSONObject("structuredContent").getBoolean("ok"))
        val query=JSONObject().put("purpose","Recover the operation receipt").put("request_id","tap-request")
        val receipt=registry.call("get_request_receipt",query,"test","query-request").getJSONObject("structuredContent")
        assertTrue(receipt.getBoolean("receipt_found")); assertEquals("accepted",receipt.getJSONObject("receipt").getString("execution_status"))
        assertFalse(registry.call("get_request_receipt",query,"other").getJSONObject("structuredContent").getBoolean("receipt_found"))
        assertEquals(1,root.inputs)
    }
}
