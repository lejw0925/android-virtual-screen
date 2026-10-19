package io.github.lejw0925.virtualscreen

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.lejw0925.virtualscreen.backend.BackendKind
import io.github.lejw0925.virtualscreen.backend.CapabilityStatus
import io.github.lejw0925.virtualscreen.mcp.McpToolProbe
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeviceToolProbeTest {
    @Test fun rootAuthorizationProbeCreatesExercisesAndCleansUpDisplay() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val app = instrumentation.targetContext.applicationContext as VirtualScreenApplication
        instrumentation.runOnMainSync {
            app.settings.completeGuide()
            app.startActivity(android.content.Intent(app, io.github.lejw0925.virtualscreen.ui.SettingsActivity::class.java)
                .putExtra("page", "BACKEND").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            app.startForegroundService(android.content.Intent(app, VirtualScreenForegroundService::class.java))
        }
        instrumentation.waitForIdleSync()
        app.sessions.selectBackend(BackendKind.ROOT)
        app.sessions.requestAuthorization()

        val report = McpToolProbe(app.sessions).run()

        assertEquals(report.failedTools.toString(), CapabilityStatus.AVAILABLE, report.capabilities.displayCreation)
        assertEquals(report.failedTools.toString(), report.totalTools, report.passedTools)
        assertNull(app.sessions.state.value.displayId)
        assertNull(app.sessions.leaseStatus())
        assertNull(app.sessions.state.value.errorCode)
    }
}
