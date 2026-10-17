package io.github.lejw0925.virtualscreen.backend

/** Evidence collected by the in-app MCP diagnostic run. A null value means that
 * the capability was not exercised by the run and must remain unknown. */
data class ProbeEvidence(
    val display: Boolean? = null,
    val capture: Boolean? = null,
    val input: Boolean? = null,
    val nodes: Boolean? = null,
    val screenOff: Boolean? = null,
)

data class ToolProbeResult(
    val tool: String,
    val passed: Boolean,
    val ok: Boolean,
    val code: String? = null,
    val attempted: Boolean = true,
)

data class ToolProbeSummary(
    val tool: String,
    val attempts: Int,
    val passed: Boolean,
    val ok: Boolean,
    val code: String? = null,
)

data class McpToolProbeReport(
    val totalTools: Int,
    val executedTools: Int,
    val passedTools: Int,
    val failedTools: List<ToolProbeSummary>,
    val results: List<ToolProbeSummary>,
    val capabilities: BackendCapabilities,
    val startedAtMillis: Long,
    val finishedAtMillis: Long,
) {
    companion object {
        fun from(
            results: List<ToolProbeResult>,
            evidence: ProbeEvidence,
            nowMillis: () -> Long = { System.currentTimeMillis() },
            startedAtMillis: Long = nowMillis(),
        ): McpToolProbeReport {
            val summaries = results.groupBy { it.tool }.map { (tool, attempts) ->
                val last = attempts.last()
                ToolProbeSummary(
                    tool = tool,
                    attempts = attempts.count { it.attempted },
                    passed = attempts.any { it.passed },
                    ok = last.ok,
                    code = last.code,
                )
            }
            fun status(value: Boolean?) = when (value) {
                true -> CapabilityStatus.AVAILABLE
                false -> CapabilityStatus.UNAVAILABLE
                null -> CapabilityStatus.UNKNOWN
            }
            return McpToolProbeReport(
                totalTools = summaries.size,
                executedTools = summaries.count { it.attempts > 0 },
                passedTools = summaries.count { it.passed },
                failedTools = summaries.filterNot { it.passed },
                results = summaries,
                capabilities = BackendCapabilities(
                    displayCreation = status(evidence.display),
                    frameCapture = status(evidence.capture),
                    inputInjection = status(evidence.input),
                    accessibilityNodes = status(evidence.nodes),
                    screenOff = status(evidence.screenOff),
                    details = "MCP_TOOLS_PROBED",
                ),
                startedAtMillis = startedAtMillis,
                finishedAtMillis = nowMillis(),
            )
        }
    }
}
