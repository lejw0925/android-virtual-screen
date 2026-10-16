package io.github.lejw0925.virtualscreen

import android.app.Application
import io.github.lejw0925.virtualscreen.backend.BackendKind
import io.github.lejw0925.virtualscreen.core.ControlLeaseManager
import io.github.lejw0925.virtualscreen.core.VirtualScreenSessionManager
import io.github.lejw0925.virtualscreen.mcp.McpServerController
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URI
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class McpSessionCleanupTest {
    @Test fun abandonedHandshakesExpireAfterOneMinuteAndCanReconnect() {
        TestServer().use { test ->
            val abandoned = test.initialize(complete = false)
            test.clock.set(59_999)
            test.server.recycleIdleSessions()
            assertEquals(1, test.server.state().sessionCount)
            test.clock.set(60_000)
            test.server.recycleIdleSessions()
            assertEquals(0, test.server.state().sessionCount)
            assertEquals(404, test.request("notifications/initialized", abandoned, notification = true).status)
            val fresh = test.initialize()
            assertNotEquals(abandoned, fresh)
            assertEquals(200, test.request("tools/list", fresh).status)
        }
    }

    @Test fun validActivityRenewsIdleTimeButInvalidCredentialsOrVersionDoNot() {
        TestServer().use { test ->
            val idle = test.initialize()
            val active = test.initialize()
            test.clock.set(299_999)
            assertEquals(200, test.request("ping", active).status)
            assertEquals(401, test.request("ping", idle, auth = "invalid").status)
            assertEquals(400, test.request("ping", idle, version = "2025-03-26").status)
            test.clock.set(300_000)
            assertEquals(404, test.request("ping", idle).status)
            assertEquals(listOf(active), test.server.state().clients.map { it.id })
            test.clock.set(599_998)
            test.server.recycleIdleSessions()
            assertEquals(1, test.server.state().sessionCount)
            test.clock.set(599_999)
            test.server.recycleIdleSessions()
            assertEquals(0, test.server.state().sessionCount)
            assertEquals(404, test.request("ping", active).status)
        }
    }

    @Test fun initializationReclaimsExpiredSessionsBeforeCheckingCapacity() {
        TestServer().use { test ->
            val stale = List(32) { test.initialize() }
            val rejected = test.initializeResponse()
            assertEquals(503, rejected.status)
            assertEquals("15", rejected.retryAfter)
            test.clock.set(300_000)
            val fresh = test.initialize()
            assertEquals(listOf(fresh), test.server.state().clients.map { it.id })
            assertEquals(404, test.request("ping", stale.first()).status)
        }
    }

    @Test fun concurrentInitializationsCannotExceedCapacity() {
        TestServer().use { test ->
            val pool = Executors.newFixedThreadPool(8)
            try {
                val attempts = List(40) { CompletableFuture.supplyAsync({ test.initializeResponse() }, pool) }
                val statuses = attempts.map { it.get(10, TimeUnit.SECONDS).status }
                assertEquals(32, statuses.count { it == 200 })
                assertEquals(8, statuses.count { it == 503 })
                assertEquals(32, test.server.state().sessionCount)
            } finally { pool.shutdownNow() }
        }
    }

    @Test fun currentControllerSurvivesUntilItsLeaseExpiresWithoutClosingTheDisplay() {
        TestServer(leaseMillis = 600_000).use { test ->
            val client = test.initialize()
            val lease = test.tool(client, "acquire_control", JSONObject())
                .body!!.getJSONObject("result").getJSONObject("structuredContent").getString("lease_token")
            val created = test.tool(client, "create_screen", JSONObject().put("lease_token", lease))
            assertTrue(created.body!!.getJSONObject("result").getJSONObject("structuredContent").getBoolean("ok"))
            test.clock.set(300_000)
            test.server.recycleIdleSessions()
            assertEquals(1, test.server.state().sessionCount)
            assertEquals(client, test.sessions.leaseStatus()?.owner)
            test.clock.set(600_000)
            test.server.recycleIdleSessions()
            assertEquals(0, test.server.state().sessionCount)
            assertNull(test.sessions.leaseStatus())
            assertNotNull(test.sessions.state.value.displayId)
            assertEquals(404, test.request("ping", client).status)
        }
    }

    @Test fun runningToolIsNotReapedAndIdleTimeStartsAfterCompletion() {
        TestServer().use { test ->
            val client = test.initialize()
            val lease = test.tool(client, "acquire_control", JSONObject())
                .body!!.getJSONObject("result").getJSONObject("structuredContent").getString("lease_token")
            val gate = CompletableDeferred<Unit>().also { test.root.createGate = it }
            val operation = CompletableFuture.supplyAsync {
                test.tool(client, "create_screen", JSONObject().put("lease_token", lease))
            }
            try {
                runBlocking { withTimeout(5_000) { test.root.entered.await() } }
                // Remove lease protection so the in-flight HTTP request alone prevents expiration.
                test.sessions.releaseLease(client, lease)
                test.clock.set(300_000)
                test.server.recycleIdleSessions()
                assertEquals(1, test.server.state().sessionCount)
                assertFalse(operation.isDone)
                gate.complete(Unit)
                val response = operation.get(5, TimeUnit.SECONDS)
                assertEquals(200, response.status)
                assertEquals("CONTROL_CHANGED", response.body!!.getJSONObject("result").getJSONObject("structuredContent").getString("code"))
                test.clock.set(599_999)
                test.server.recycleIdleSessions()
                assertEquals(1, test.server.state().sessionCount)
                test.clock.set(600_000)
                test.server.recycleIdleSessions()
                assertEquals(0, test.server.state().sessionCount)
            } finally { gate.complete(Unit) }
        }
    }

    @Test fun expiredClientCannotKeepPendingApprovalOrReuseItsNotification() {
        TestServer(leaseMillis = 600_000).use { test ->
            val client = test.initialize()
            val phone = test.sessions.phoneTakeover()
            val requested = test.tool(client, "request_control", JSONObject().put("action", "request"))
            val approvalId = requested.body!!.getJSONObject("result").getJSONObject("structuredContent").getString("approval_id")
            assertEquals(1, test.server.approvals.pending.value.size)
            test.clock.set(300_000)
            test.server.recycleIdleSessions()
            assertTrue(test.server.approvals.pending.value.isEmpty())
            test.server.approvals.approve(approvalId, always = true)
            assertFalse(test.server.approvals.alwaysAllow.value)
            assertEquals(phone, test.sessions.leaseStatus())
            assertEquals(404, test.request("ping", client).status)
        }
    }

    private data class Response(val status: Int, val body: JSONObject?, val session: String?, val retryAfter: String?)

    private class TestServer(leaseMillis: Long = 300_000) : AutoCloseable {
        val clock = AtomicLong()
        val root = FakeBackend(BackendKind.ROOT)
        val sessions = VirtualScreenSessionManager(RuntimeEnvironment.getApplication(), root,
            FakeBackend(BackendKind.SHIZUKU), ControlLeaseManager(leaseMillis) { clock.get() })
        val server = McpServerController(RuntimeEnvironment.getApplication(), sessions) { clock.get() }
        private val counter = AtomicInteger()
        private val port = ServerSocket(0).use { it.localPort }

        init { server.start(port = port).getOrThrow() }

        fun request(method: String, session: String? = null, params: JSONObject = JSONObject(),
            auth: String = server.state().token, version: String = "2025-11-25", notification: Boolean = false): Response {
            val connection = URI("http://127.0.0.1:$port/mcp").toURL().openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "POST"
                connection.connectTimeout = 5_000
                connection.readTimeout = 5_000
                connection.setRequestProperty("Authorization", "Bearer $auth")
                connection.setRequestProperty("Accept", "application/json, text/event-stream")
                connection.setRequestProperty("Content-Type", "application/json")
                if (session != null) {
                    connection.setRequestProperty("Mcp-Session-Id", session)
                    connection.setRequestProperty("MCP-Protocol-Version", version)
                }
                val body = JSONObject().put("jsonrpc", "2.0").put("method", method).put("params", params)
                if (!notification) body.put("id", counter.incrementAndGet())
                connection.doOutput = true
                connection.outputStream.use { it.write(body.toString().toByteArray()) }
                val status = connection.responseCode
                val text = (if (status >= 400) connection.errorStream else connection.inputStream)
                    ?.bufferedReader()?.use { it.readText() }
                return Response(status, text?.takeIf { it.isNotBlank() }?.let(::JSONObject),
                    connection.getHeaderField("Mcp-Session-Id"), connection.getHeaderField("Retry-After"))
            } finally { connection.disconnect() }
        }

        fun initializeResponse() = request("initialize", params = JSONObject().put("protocolVersion", "2025-11-25")
            .put("capabilities", JSONObject()).put("clientInfo", JSONObject().put("name", "cleanup-test").put("version", "1")))

        fun initialize(complete: Boolean = true): String {
            val response = initializeResponse()
            assertEquals(200, response.status)
            val session = response.session!!
            if (complete) assertEquals(202, request("notifications/initialized", session, notification = true).status)
            return session
        }

        fun tool(session: String, name: String, arguments: JSONObject = JSONObject()) = request("tools/call", session,
            JSONObject().put("name", name).put("arguments", JSONObject(arguments.toString()).put("purpose", "Verify session cleanup")))

        override fun close() {
            server.stop()
            runBlocking { sessions.shutdown() }
        }
    }
}
