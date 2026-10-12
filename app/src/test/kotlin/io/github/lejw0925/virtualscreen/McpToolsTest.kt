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
    @Test fun missingPurposeAndInapplicableArgumentsNeverDispatchInput() = runBlocking {
        val root=FakeBackend(BackendKind.ROOT)
        val sessions=VirtualScreenSessionManager(RuntimeEnvironment.getApplication(),root,FakeBackend(BackendKind.SHIZUKU))
        val registry=McpToolRegistry(sessions)
        val lease=(sessions.acquireLease("test") as LeaseDecision.Granted).lease
        sessions.create("test",lease.token)
        val observed=sessions.observe("test",lease.token).getOrThrow().values.getValue("observation_id")
        val args=JSONObject().put("lease_token",lease.token).put("observation_id",observed).put("action","tap").put("x",10).put("y",20)
        assertEquals("PURPOSE_REQUIRED",registry.call("pointer",args,"test").getJSONObject("structuredContent").getString("code"))
        args.put("purpose","Verify the tap counter").put("points",JSONArray("[[10,20],[20,30]]"))
        assertEquals("ARGUMENT_NOT_APPLICABLE",registry.call("pointer",args,"test").getJSONObject("structuredContent").getString("code"))
        args.remove("points")
        assertTrue(registry.call("pointer",args,"test").getJSONObject("structuredContent").getBoolean("ok"))
        assertEquals(1,root.inputs)
        sessions.shutdown()
    }
    @Test fun malformedSwipeAndMissingCoordinatesPreserveObservation() = runBlocking {
        val root=FakeBackend(BackendKind.ROOT)
        val sessions=VirtualScreenSessionManager(RuntimeEnvironment.getApplication(),root,FakeBackend(BackendKind.SHIZUKU))
        val registry=McpToolRegistry(sessions)
        val lease=(sessions.acquireLease("test") as LeaseDecision.Granted).lease
        sessions.create("test",lease.token)
        val observed=sessions.observe("test",lease.token).getOrThrow().values.getValue("observation_id")
        val args=JSONObject().put("lease_token",lease.token).put("observation_id",observed).put("purpose","Verify swipe validation").put("action","swipe").put("points",JSONArray("[[1.5,2],[3,4]]"))
        assertEquals("INVALID_ARGUMENT",registry.call("pointer",args,"test").getJSONObject("structuredContent").getString("code"))
        args.remove("points"); args.put("action","tap")
        assertEquals("MISSING_ARGUMENT",registry.call("pointer",args,"test").getJSONObject("structuredContent").getString("code"))
        assertEquals(observed,sessions.state.value.observationId); assertEquals(0,root.inputs)
        sessions.shutdown()
    }
    @Test fun localizedDiscoveryAndFeedbackKeepStableMachineNames() = runBlocking {
        val sessions=VirtualScreenSessionManager(RuntimeEnvironment.getApplication(),FakeBackend(BackendKind.ROOT),FakeBackend(BackendKind.SHIZUKU))
        var language=UiLanguage.ZH_HANS
        val registry=McpToolRegistry(sessions) { language }
        val names=registry.tools.map { it.name }; assertEquals(10,names.size)
        for(l in UiLanguage.entries) {
            language=l
            assertEquals(names,registry.tools.map { it.name })
            val schema=registry.tools.first().schema
            assertTrue(schema.getJSONArray("required").toString().contains("purpose"))
            val error=registry.call("service_status",JSONObject(),"viewer").getJSONObject("structuredContent")
            assertEquals("PURPOSE_REQUIRED",error.getString("code")); assertEquals(l.tag,error.getString("language"))
            assertTrue(error.getString("message").isNotBlank())
        }
        language=UiLanguage.ZH_HANT
        assertTrue(registry.tools.first { it.name=="screen" }.description.contains("虛擬螢幕"))
    }
    @Test fun competingClientCannotReplaceTheActiveOperationPurpose() = runBlocking {
        val root=FakeBackend(BackendKind.ROOT).apply { createGate=CompletableDeferred() }
        val sessions=VirtualScreenSessionManager(RuntimeEnvironment.getApplication(),root,FakeBackend(BackendKind.SHIZUKU))
        val registry=McpToolRegistry(sessions)
        val lease=(sessions.acquireLease("controller") as LeaseDecision.Granted).lease
        val purpose="Create a screen to test the app"
        val operation=async { registry.call("screen",JSONObject().put("action","create").put("lease_token",lease.token).put("purpose",purpose),"controller") }
        try {
            root.entered.await()
            val rejected=registry.call("control",JSONObject().put("action","acquire").put("purpose","Request control"),"other").getJSONObject("structuredContent")
            assertFalse(rejected.getBoolean("ok"))
            registry.call("service_status",JSONObject().put("purpose","Check availability"),"other")
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
        val result=registry.call("screen",JSONObject().put("action","create").put("lease_token",lease.token).put("purpose","Verify phone viewport defaults"),"remote")
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
        args.remove("key_code"); args.put("action","long_press").put("x",5).put("y",5).put("duration_ms",100)
        assertEquals("INVALID_ARGUMENT",registry.call("pointer",args,"test").getJSONObject("structuredContent").getString("code"))
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
        val args=JSONObject().put("purpose","Click and inspect the result").put("lease_token",lease.token).put("observation_id",id).put("action","tap").put("x",5).put("y",5).put("observe_after","both")
        val result=registry.call("pointer",args,"test").getJSONObject("structuredContent")
        assertTrue(result.getBoolean("ok")); assertEquals("accepted",result.getString("execution_status")); assertEquals("failed",result.getString("observation_status")); assertTrue(result.getBoolean("requires_observation"))
        assertEquals(1,root.inputs); assertNull(sessions.state.value.observationId)
        assertEquals("STALE_OBSERVATION",registry.call("pointer",args,"test").getJSONObject("structuredContent").getString("code")); assertEquals(1,root.inputs)
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
        val tap=JSONObject().put("purpose","Test a single input").put("lease_token",lease.token).put("observation_id",id).put("action","tap").put("x",5).put("y",5)
        assertTrue(registry.call("pointer",tap,"test","tap-request").getJSONObject("structuredContent").getBoolean("ok"))
        val query=JSONObject().put("purpose","Recover the operation receipt").put("request_id","tap-request")
        val receipt=registry.call("service_status",query,"test","query-request").getJSONObject("structuredContent")
        assertTrue(receipt.getBoolean("receipt_found")); assertEquals("accepted",receipt.getJSONObject("receipt").getString("execution_status"))
        assertFalse(registry.call("service_status",query,"other").getJSONObject("structuredContent").getBoolean("receipt_found"))
        assertEquals(1,root.inputs)
    }
}
