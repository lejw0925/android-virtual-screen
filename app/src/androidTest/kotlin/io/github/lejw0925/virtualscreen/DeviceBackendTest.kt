package io.github.lejw0925.virtualscreen

import android.content.Context
import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.lejw0925.virtualscreen.backend.*
import io.github.lejw0925.virtualscreen.core.*
import io.github.lejw0925.virtualscreen.tunnel.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class DeviceBackendTest {
    private val context: Context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val fixture="io.github.lejw0925.virtualscreen.fixture/.FixtureActivity"
    @Test fun rootDisplayScreenshotInputUnicodeNodesAndTakeover()=runBlocking {
        val root=RootBackend(context)
        assertTrue("Root grant required",root.requestPermission())
        verify(root)
    }
    @Test fun shizukuSurfaceScreenshotAndInput()=runBlocking {
        val backend=ShizukuBackend(context)
        var authorized=backend.probe().displayCreation != CapabilityStatus.REQUIRES_AUTHORIZATION
        repeat(20) { if(!authorized) { delay(100); authorized=backend.probe().displayCreation != CapabilityStatus.REQUIRES_AUTHORIZATION } }
        if(InstrumentationRegistry.getArguments().getString("require_shizuku") == "true")
            assertTrue("Shizuku must be authorized; this validation cannot be skipped",authorized)
        else assumeTrue("Grant Shizuku in the app before running this test",authorized)
        verify(backend)
    }
    @Test fun shizukuReconfigurationKeepsTheNewBindingAndInvalidatesOldObservations() = runBlocking {
        val authorized=ShizukuBackend(context).probe().displayCreation != CapabilityStatus.REQUIRES_AUTHORIZATION
        if(InstrumentationRegistry.getArguments().getString("require_shizuku") == "true") assertTrue("Shizuku is required",authorized)
        else assumeTrue("Grant Shizuku before validating reconfiguration",authorized)
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        instrumentation.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        instrumentation.runOnMainSync {
            context.startForegroundService(android.content.Intent(context,VirtualScreenForegroundService::class.java))
        }
        val manager=(context.applicationContext as VirtualScreenApplication).sessions
        manager.selectBackend(BackendKind.SHIZUKU)
        val lease=(manager.acquireLease("reconfiguration-test") as LeaseDecision.Granted).lease
        try {
            instrumentation.uiAutomation.executeShellCommand("am force-stop io.github.lejw0925.virtualscreen.fixture").use { it.close() }
            assertTrue(manager.create(lease.owner,lease.token,VirtualScreenConfig(720,1600,320,0,false,0)).ok)
            assertTrue(manager.launch(lease.owner,lease.token,fixture).ok)
            delay(500)
            for(height in listOf(1476,1600,1476)) {
                val oldSession=manager.state.value.sessionId
                val oldObservation=manager.observe(lease.owner,lease.token).getOrThrow().values["observation_id"] as String
                val configured=manager.configure(lease.owner,lease.token,VirtualScreenConfig(720,height,320,0,false,0))
                assertTrue("Reconfigure failed: ${configured.code}",configured.ok)
                assertNotEquals(oldSession,manager.state.value.sessionId)
                assertEquals("STALE_OBSERVATION",runCatching {
                    manager.operate(lease.owner,lease.token,oldObservation,BackendOperation.Tap(10,10))
                }.exceptionOrNull()?.message)
                assertTrue(manager.launch(lease.owner,lease.token,fixture).ok)
                delay(500)
                val frame=manager.observe(lease.owner,lease.token).getOrThrow().values["frame"] as Bitmap
                assertEquals(720,frame.width)
                assertEquals(height,frame.height)
            }
        } finally { manager.shutdown() }
    }

    @Test fun rootCancelledPreviewKeepsTheDisplayAndTransportInSync()=runBlocking {
        val root=RootBackend(context)
        assertTrue(root.requestPermission())
        val handle=root.create(VirtualScreenConfig(720,1600,320,0,false,0)).getOrThrow()
        try {
            assertTrue(root.launch(handle,fixture,restartApproved=true).ok)
            repeat(6) {
                val preview=launch(Dispatchers.IO) { root.observe(handle) }
                delay(20)
                preview.cancelAndJoin()
                assertTrue("Cancelling a preview ended its Root display",root.checkAlive(handle))
                val frame=root.observe(handle).getOrThrow()
                assertEquals(720,frame.width); assertEquals(1600,frame.height)
            }
        } finally { root.close(handle) }
    }
    @Test fun rootCancelledCreationCleansUpAndCanCreateAgain()=runBlocking {
        val root=RootBackend(context)
        assertTrue(root.requestPermission())
        val config=VirtualScreenConfig(720,1600,320,0,false,0)
        val creating=launch(Dispatchers.IO) { root.create(config) }
        delay(20)
        creating.cancelAndJoin()
        val handle=root.create(config).getOrThrow()
        try { assertTrue(root.checkAlive(handle)) }
        finally { root.close(handle) }
    }
    private suspend fun verify(backend: VirtualScreenBackend) {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        instrumentation.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        instrumentation.runOnMainSync {
            context.startActivity(android.content.Intent(context,io.github.lejw0925.virtualscreen.ui.MainActivity::class.java).putExtra("device_test",true).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            // Match the product's foreground service and viewer lifecycle.
            context.startForegroundService(android.content.Intent(context,VirtualScreenForegroundService::class.java))
        }
        // Keep the actual viewer bound to the tested display. A second manager
        // leaves the host idle and some ROMs freeze it during fixture input.
        val manager=(context.applicationContext as VirtualScreenApplication).sessions
        manager.selectBackend(backend.kind)
        var lease=manager.phoneTakeover()
        try {
            // Keep this disposable fixture test isolated from a task left by a
            // previous instrumentation run on the physical display.
            instrumentation.uiAutomation.executeShellCommand("am force-stop io.github.lejw0925.virtualscreen.fixture").use { it.close() }
            val created=manager.create(lease.owner,lease.token,VirtualScreenConfig(720,1600,320,0,false,0))
            assertTrue("Create: ${created.code}",created.ok)
            val launched=manager.launch(lease.owner,lease.token,fixture)
            assertTrue("Launch: ${launched.code}",launched.ok)
            // A successful launch can still be showing Android's splash window.
            // Wait for the disposable fixture's actual view hierarchy before
            // issuing input; never retry an input that may have been accepted.
            withTimeout(10_000) {
                while (true) {
                    val dump=android.os.ParcelFileDescriptor.AutoCloseInputStream(
                        instrumentation.uiAutomation.executeShellCommand("dumpsys activity top")
                    ).bufferedReader().use { it.readText() }
                    if (dump.contains("test_counter")) break
                    delay(150)
                }
            }
            suspend fun observe(): OperationEnvelope {
                var result=manager.observe(lease.owner,lease.token)
                repeat(10) { if(result.isFailure) { delay(250); result=manager.observe(lease.owner,lease.token) } }
                return result.getOrThrow()
            }
            val observation=observe()
            val frame=observation.values["frame"] as Bitmap
            assertTrue(frame.width > 0 && frame.height > 0)
            File(context.getExternalFilesDir(null),"${backend.kind.wireName}-preview.jpg").outputStream().use { frame.compress(Bitmap.CompressFormat.JPEG,90,it) }
            val id=observation.values["observation_id"] as String
            val tapped=manager.operate(lease.owner,lease.token,id,BackendOperation.Tap(350,160))
            assertTrue("Tap: ${tapped.code} ${tapped.values}",tapped.ok)
            assertEquals("STALE_OBSERVATION",runCatching { manager.operate(lease.owner,lease.token,id,BackendOperation.Tap(350,160)) }.exceptionOrNull()?.message)
            var current=observe()
            assertTrue(manager.operate(lease.owner,lease.token,current.values["observation_id"] as String,BackendOperation.Tap(350,280)).ok)
            delay(200)
            current=observe()
            val inserted=manager.operate(lease.owner,lease.token,current.values["observation_id"] as String,BackendOperation.Text("中文測試 Android 你好",true))
            assertTrue("Unicode: ${inserted.code} ${inserted.values}",inserted.ok)
            delay(300)
            current=observe()
            (current.values["frame"] as Bitmap).let { bitmap -> File(context.getExternalFilesDir(null),"${backend.kind.wireName}-unicode.jpg").outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG,90,it) } }
            repeat(10) { if(ScreenAccessibilityService.current == null) delay(250) }
            // Android may defer binding target-app accessibility during instrumentation.
            // Node effects are verified in the normal app process by DeviceCheckReceiver.
            if(ScreenAccessibilityService.current != null) {
                val nodes=manager.nodes(lease.owner,lease.token,current.values["observation_id"] as String).getOrThrow()
                assertTrue("Unicode did not reach virtual editor",nodes.nodes.any { it.text?.contains("中文測試") == true })
                val clickable=nodes.nodes.firstOrNull { it.clickable && it.text?.contains("Tap counter",ignoreCase=true) == true }
                assertNotNull(clickable)
                assertTrue(manager.operate(lease.owner,lease.token,current.values["observation_id"] as String,BackendOperation.NodeAction(clickable!!.id,"click")).ok)
                current=observe()
            }
            val swiped=manager.operate(lease.owner,lease.token,current.values["observation_id"] as String,BackendOperation.Swipe(listOf(350 to 1200,350 to 700),350))
            assertTrue("Swipe: ${swiped.code} ${swiped.values}",swiped.ok)
            manager.phoneTakeover()
            assertEquals("LEASE_REQUIRED",manager.observe(lease.owner,lease.token).exceptionOrNull()?.message)
        } finally { manager.shutdown() }
    }
    @Test fun bundledExecutablesRunAsOrdinaryAppUid() {
        assertTrue(android.os.Process.myUid() >= 10000)
        for(name in listOf("frpc","cloudflared")) {
            val binary=File(context.applicationInfo.nativeLibraryDir,"lib$name.so")
            assertTrue(binary.canExecute())
            val process=ProcessBuilder(binary.absolutePath,"--version").redirectErrorStream(true).start()
            val output=process.inputStream.bufferedReader().readText()
            assertTrue(process.waitFor(20,TimeUnit.SECONDS)); assertEquals(output,0,process.exitValue())
            assertTrue(output.contains(if(name=="frpc")"0.65.0" else "2025.8.1"))
        }
    }
}
