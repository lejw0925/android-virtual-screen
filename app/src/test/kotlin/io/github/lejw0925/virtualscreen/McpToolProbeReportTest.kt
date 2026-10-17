package io.github.lejw0925.virtualscreen

import io.github.lejw0925.virtualscreen.backend.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class McpToolProbeReportTest {
    @Test
    fun aggregatesRepeatedToolAttemptsAndMapsEvidenceToCapabilities() {
        val report = McpToolProbeReport.from(
            results = listOf(
                ToolProbeResult("get_status", passed = true, ok = true),
                ToolProbeResult("observe", passed = false, ok = false, code = "NODES_UNAVAILABLE"),
                ToolProbeResult("observe", passed = true, ok = true),
                ToolProbeResult("tap_at", passed = false, ok = false, code = "INPUT_UNAVAILABLE"),
            ),
            evidence = ProbeEvidence(display = true, capture = true, input = false, nodes = false),
        )

        assertEquals(3, report.totalTools)
        assertEquals(2, report.passedTools)
        assertEquals(listOf("tap_at"), report.failedTools.map { it.tool })
        assertEquals(2, report.results.first { it.tool == "observe" }.attempts)
        assertEquals(CapabilityStatus.AVAILABLE, report.capabilities.displayCreation)
        assertEquals(CapabilityStatus.AVAILABLE, report.capabilities.frameCapture)
        assertEquals(CapabilityStatus.UNAVAILABLE, report.capabilities.inputInjection)
        assertEquals(CapabilityStatus.UNAVAILABLE, report.capabilities.accessibilityNodes)
        assertEquals(CapabilityStatus.UNKNOWN, report.capabilities.screenOff)
        assertTrue(report.finishedAtMillis >= report.startedAtMillis)
    }

    @Test
    fun missingEvidenceRemainsUnknownUntilAProbeHasRun() {
        val report = McpToolProbeReport.from(
            results = listOf(ToolProbeResult("get_status", passed = true, ok = true)),
            evidence = ProbeEvidence(),
        )

        assertEquals(CapabilityStatus.UNKNOWN, report.capabilities.displayCreation)
        assertEquals(CapabilityStatus.UNKNOWN, report.capabilities.frameCapture)
        assertEquals(CapabilityStatus.UNKNOWN, report.capabilities.inputInjection)
        assertEquals(CapabilityStatus.UNKNOWN, report.capabilities.accessibilityNodes)
        assertEquals(CapabilityStatus.UNKNOWN, report.capabilities.screenOff)
    }
}
