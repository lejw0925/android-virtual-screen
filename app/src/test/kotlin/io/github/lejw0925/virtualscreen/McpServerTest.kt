package io.github.lejw0925.virtualscreen

import android.app.Application
import io.github.lejw0925.virtualscreen.backend.*
import io.github.lejw0925.virtualscreen.core.*
import io.github.lejw0925.virtualscreen.mcp.*
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URI
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class) @Config(sdk=[34],application=Application::class)
class McpServerTest {
    @Test fun browserLandingExplainsMcpAndUnauthorizedRequestsLogTheirIpWithoutCredentials() {
        val context=RuntimeEnvironment.getApplication()
        val sessions=VirtualScreenSessionManager(context,FakeBackend(BackendKind.ROOT),FakeBackend(BackendKind.SHIZUKU))
        val entries=java.util.Collections.synchronizedList(mutableListOf<ActivityLogEntry>())
        val server=McpServerController(context,sessions,log=ActivityLogger { entries.add(it) })
        val port=ServerSocket(0).use { it.localPort }
        server.start(port=port).getOrThrow()
        try {
            for(path in listOf("/","/mcp")) {
                val connection=URI("http://127.0.0.1:$port$path").toURL().openConnection() as HttpURLConnection
                connection.setRequestProperty("Accept","text/html")
                try {
                    assertEquals(200,connection.responseCode)
                    val page=connection.inputStream.bufferedReader().readText()
                    assertTrue(page.contains("POST /mcp"))
                    assertFalse(page.contains(server.state().token))
                    assertEquals("no-store",connection.getHeaderField("Cache-Control"))
                } finally { connection.disconnect() }
            }
            val connection=URI("http://127.0.0.1:$port/mcp").toURL().openConnection() as HttpURLConnection
            connection.setRequestProperty("Authorization","Bearer private-invalid-token")
            connection.setRequestProperty("Accept","application/json, text/event-stream")
            try { assertEquals(401,connection.responseCode) } finally { connection.disconnect() }
            val rejected=entries.single { it.status=="unauthorized" }
            assertEquals("127.0.0.1",rejected.peerIp)
            assertTrue(rejected.time>0)
            assertFalse(entries.joinToString { it.text() }.contains("private-invalid-token"))
        } finally { server.stop(); runBlocking { sessions.shutdown() } }
    }

    @Test fun occupiedPortReportsFailureAndCanBeRestarted() {
        val context=RuntimeEnvironment.getApplication()
        val manager=VirtualScreenSessionManager(context,FakeBackend(BackendKind.ROOT),FakeBackend(BackendKind.SHIZUKU))
        val server=McpServerController(context,manager)
        val occupied=ServerSocket().apply { bind(java.net.InetSocketAddress("127.0.0.1",0)) }
        try {
            assertTrue("A bound loopback port must fail: ${server.state()}",server.start(port=occupied.localPort).isFailure)
            assertFalse(server.state().running)
            occupied.close()
            assertTrue(server.start(port=ServerSocket(0).use { it.localPort }).isSuccess)
            assertTrue(server.state().running)
        } finally { occupied.close(); server.stop() }
    }
    @Test fun authenticatedSessionsCompeteRejectDuplicateInputAndDisconnectOnRotation() {
        val context=RuntimeEnvironment.getApplication()
        val root=FakeBackend(BackendKind.ROOT)
        val sessions=VirtualScreenSessionManager(context,root,FakeBackend(BackendKind.SHIZUKU))
        val events=java.util.Collections.synchronizedList(mutableListOf<ActivityLogEntry>())
        val server=McpServerController(context,sessions,log=ActivityLogger { events.add(it) })
        val port=ServerSocket(0).use { it.localPort }
        server.start(port=port).getOrThrow()
        var token=server.state().token
        fun request(body: JSONObject?,session: String?=null,method: String="POST",auth: String=token,origin: String?=null): Triple<Int,JSONObject?,String?> {
            val c=URI("http://127.0.0.1:$port/mcp").toURL().openConnection() as HttpURLConnection
            c.requestMethod=method; c.connectTimeout=5000; c.readTimeout=5000
            c.setRequestProperty("Authorization","Bearer $auth"); c.setRequestProperty("Accept","application/json, text/event-stream"); c.setRequestProperty("Content-Type","application/json")
            if(session != null) { c.setRequestProperty("Mcp-Session-Id",session); c.setRequestProperty("MCP-Protocol-Version","2025-11-25") }
            if(origin != null) c.setRequestProperty("Origin",origin)
            body?.let { payload -> c.doOutput=true; c.outputStream.use { output -> output.write(payload.toString().toByteArray()) } }
            val status=c.responseCode
            val text=runCatching { (if(status >= 400)c.errorStream else c.inputStream)?.bufferedReader()?.readText() }.getOrNull()
            val result=Triple(status,text?.takeIf { it.isNotBlank() }?.let(::JSONObject),c.getHeaderField("Mcp-Session-Id")); c.disconnect(); return result
        }
        fun rpc(id: Int,method: String,params: JSONObject=JSONObject())=JSONObject().put("jsonrpc","2.0").put("id",id).put("method",method).put("params",params)
        fun init(): String {
            val result=request(rpc(1,"initialize",JSONObject().put("protocolVersion","2025-11-25").put("clientInfo",JSONObject().put("name","test").put("version","1"))))
            assertEquals(200,result.first); val id=result.third!!
            assertEquals(202,request(JSONObject().put("jsonrpc","2.0").put("method","notifications/initialized"),id).first)
            return id
        }
        fun tool(id: Int,name: String,args: JSONObject=JSONObject(),session: String)=request(rpc(id,"tools/call",JSONObject().put("name",name).put("arguments",JSONObject(args.toString()).put("purpose","Verify isolated display behavior"))),session).second!!
        try {
            assertEquals(401,request(rpc(0,"ping"),auth="wrong").first)
            assertEquals(403,request(rpc(0,"ping"),origin="https://evil.example").first)
            val a=init(); val b=init()
            val granted=tool(2,"acquire_control",JSONObject(),session=a).getJSONObject("result").getJSONObject("structuredContent")
            val lease=granted.getString("lease_token")
            assertEquals("CONTROL_BUSY",tool(2,"acquire_control",JSONObject(),session=b).getJSONObject("result").getJSONObject("structuredContent").getString("code"))
            val auth=JSONObject().put("lease_token",lease)
            assertTrue(tool(3,"create_screen",JSONObject(auth.toString()),a).getJSONObject("result").getJSONObject("structuredContent").getBoolean("ok"))
            val obs=tool(4,"observe",auth,a).getJSONObject("result").getJSONObject("structuredContent").getString("observation_id")
            val input=JSONObject().put("lease_token",lease).put("observation_id",obs).put("x",12).put("y",20)
            assertTrue(tool(5,"tap_at",JSONObject(input.toString()),a).getJSONObject("result").getJSONObject("structuredContent").getBoolean("ok"))
            assertEquals(-32001,tool(5,"tap_at",JSONObject(input.toString()),a).getJSONObject("error").getInt("code"))
            assertEquals(1,root.inputs)
            assertTrue(events.any { it.operation=="connect" && it.client=="test" && it.peerIp=="127.0.0.1" })
            assertTrue(events.any { it.operation=="tap_at" && it.status=="completed" && it.time>0 })
            assertFalse(events.joinToString { it.text() }.contains(lease))
            assertFalse(events.joinToString { it.text() }.contains(token))
            val future=java.util.concurrent.CompletableFuture.supplyAsync {
                tool(7,"wait",JSONObject().put("lease_token",lease).put("timeout_ms",30000),a)
            }
            // Confirm the same HTTP session remains responsive while its tool waits.
            assertEquals(200,request(rpc(8,"ping"),a).first)
            Thread.sleep(150)
            assertEquals(202,request(JSONObject().put("jsonrpc","2.0").put("method","notifications/cancelled").put("params",JSONObject().put("requestId",7)),a).first)
            assertEquals(-32800,future.get(5,java.util.concurrent.TimeUnit.SECONDS).getJSONObject("error").getInt("code"))
            assertNull(sessions.state.value.observationId)
            assertEquals(204,request(null,b,"DELETE").first)
            server.approvals.setAlwaysAllow(true)
            val configuration=server.clientConfiguration("http://127.0.0.1:$port/mcp")
            assertFalse(configuration.contains("\\/"))
            assertEquals("http://127.0.0.1:$port/mcp",JSONObject(configuration).getJSONObject("mcpServers").getJSONObject("android-virtual-screen").getString("url"))
            val oldToken=token; token=server.rotateToken()
            assertFalse(server.approvals.alwaysAllow.value)
            assertEquals(401,request(rpc(6,"ping"),a,auth=oldToken).first)
            assertEquals(404,request(rpc(6,"ping"),a).first)
        } finally { server.stop(); runBlocking { sessions.shutdown() } }
    }
    @Test fun restartApprovalHttpRequestRemainsAlivePastOrdinarySixtySecondLimit() {
        val context=RuntimeEnvironment.getApplication()
        val backend=RestartBackend()
        val sessions=VirtualScreenSessionManager(context,backend,FakeBackend(BackendKind.SHIZUKU))
        val server=McpServerController(context,sessions)
        val port=ServerSocket(0).use { it.localPort }
        server.start(port=port).getOrThrow()
        val token=server.state().token
        fun request(body: JSONObject?,session: String?=null,timeoutMillis: Int=5000,httpMethod: String="POST"): Triple<Int,JSONObject?,String?> {
            val c=URI("http://127.0.0.1:$port/mcp").toURL().openConnection() as HttpURLConnection
            c.requestMethod=httpMethod; c.connectTimeout=5000; c.readTimeout=timeoutMillis
            c.setRequestProperty("Authorization","Bearer $token"); c.setRequestProperty("Accept","application/json, text/event-stream"); c.setRequestProperty("Content-Type","application/json")
            if(session!=null) { c.setRequestProperty("Mcp-Session-Id",session); c.setRequestProperty("MCP-Protocol-Version","2025-11-25") }
            if(body!=null) { c.doOutput=true; c.outputStream.use { it.write(body.toString().toByteArray()) } }
            val status=c.responseCode
            val text=runCatching { (if(status>=400)c.errorStream else c.inputStream)?.bufferedReader()?.readText() }.getOrNull()
            val result=Triple(status,text?.takeIf(String::isNotBlank)?.let(::JSONObject),c.getHeaderField("Mcp-Session-Id")); c.disconnect(); return result
        }
        fun rpc(id: Int,method: String,params: JSONObject=JSONObject())=JSONObject().put("jsonrpc","2.0").put("id",id).put("method",method).put("params",params)
        try {
            val initialized=request(rpc(1,"initialize",JSONObject().put("protocolVersion","2025-11-25").put("clientInfo",JSONObject().put("name","restart-test").put("version","1"))))
            assertEquals(200,initialized.first); val session=initialized.third!!
            assertEquals(202,request(JSONObject().put("jsonrpc","2.0").put("method","notifications/initialized"),session).first)
            val acquire=request(rpc(2,"tools/call",JSONObject().put("name","acquire_control").put("arguments",JSONObject().put("purpose","Prepare virtual screen"))),session).second!!
            val lease=acquire.getJSONObject("result").getJSONObject("structuredContent").getString("lease_token")
            val create=request(rpc(3,"tools/call",JSONObject().put("name","create_screen").put("arguments",JSONObject().put("lease_token",lease).put("purpose","Create virtual screen"))),session).second!!
            assertTrue(create.getJSONObject("result").getJSONObject("structuredContent").getBoolean("ok"))
            val args=JSONObject().put("component","io.github.lejw0925.virtualscreen.fixture/.FixtureActivity").put("lease_token",lease).put("purpose","Open fixture on virtual screen")
            val call=rpc(4,"tools/call",JSONObject().put("name","launch_app").put("arguments",args))
            val waiting=java.util.concurrent.CompletableFuture.supplyAsync { request(call,session,150_000) }
            val pendingDeadline=System.nanoTime()+5_000_000_000L
            while(sessions.restartApprovals.pending.value.isEmpty() && System.nanoTime()<pendingDeadline) Thread.sleep(20)
            val approval=sessions.restartApprovals.pending.value.single()
            assertEquals(200,request(rpc(5,"ping"),session).first)
            Thread.sleep(61_000)
            assertFalse("Cross-display launch was cut off at the normal 60-second request limit",waiting.isDone)
            assertTrue(sessions.restartApprovals.approve(approval.id))
            val reply=waiting.get(10,java.util.concurrent.TimeUnit.SECONDS)
            assertEquals(200,reply.first)
            assertTrue(reply.second!!.getJSONObject("result").getJSONObject("structuredContent").getBoolean("ok"))
            assertEquals(listOf(false,true),backend.launches.map { it.approved })
            fun awaitApproval(): AppRestartRequest {
                val deadline=System.nanoTime()+5_000_000_000L
                while(sessions.restartApprovals.pending.value.isEmpty() && System.nanoTime()<deadline) Thread.sleep(20)
                return sessions.restartApprovals.pending.value.single()
            }
            val cancelled=java.util.concurrent.CompletableFuture.supplyAsync { request(JSONObject(call.toString()).put("id",6),session,150_000) }
            val cancelledApproval=awaitApproval()
            assertEquals(202,request(JSONObject().put("jsonrpc","2.0").put("method","notifications/cancelled").put("params",JSONObject().put("requestId",6)),session).first)
            assertEquals(-32800,cancelled.get(5,java.util.concurrent.TimeUnit.SECONDS).second!!.getJSONObject("error").getInt("code"))
            assertTrue(sessions.restartApprovals.pending.value.isEmpty()); assertFalse(sessions.restartApprovals.approve(cancelledApproval.id))
            val disconnected=java.util.concurrent.CompletableFuture.supplyAsync { request(JSONObject(call.toString()).put("id",7),session,150_000) }
            val disconnectedApproval=awaitApproval()
            assertEquals(204,request(null,session,httpMethod="DELETE").first)
            assertEquals(-32800,disconnected.get(5,java.util.concurrent.TimeUnit.SECONDS).second!!.getJSONObject("error").getInt("code"))
            assertTrue(sessions.restartApprovals.pending.value.isEmpty()); assertFalse(sessions.restartApprovals.approve(disconnectedApproval.id))
            assertNull(sessions.leaseStatus())
            assertEquals(1,backend.launches.count { it.approved })
        } finally { server.stop(); runBlocking { sessions.shutdown() } }
    }
    @Test fun startingMcpAllowsRemoteControlWithoutAdditionalPhoneSwitch() = runBlocking {
        val root=FakeBackend(BackendKind.ROOT)
        val sessions=VirtualScreenSessionManager(RuntimeEnvironment.getApplication(),root,FakeBackend(BackendKind.SHIZUKU))
        val phone=sessions.phoneTakeover()
        sessions.create(phone.owner,phone.token)
        val server=McpServerController(RuntimeEnvironment.getApplication(),sessions)
        try {
            server.start(port=ServerSocket(0).use { it.localPort }).getOrThrow()
            assertNull(sessions.leaseStatus())
            val remote=sessions.acquireLease("remote") as LeaseDecision.Granted
            val local=sessions.phoneTakeover()
            assertFalse(sessions.observe(remote.lease.owner,remote.lease.token).isSuccess)
            sessions.finishPhoneControl(local.token)
            val next=sessions.acquireLease("remote") as LeaseDecision.Granted
            sessions.finishPhoneControl(local.token)
            assertEquals(next.lease,sessions.leaseStatus())
        } finally { server.stop(); sessions.shutdown() }
    }
    @Test fun startingMcpCannotReleaseAnOpenPhoneViewer() = runBlocking {
        val sessions=VirtualScreenSessionManager(RuntimeEnvironment.getApplication(),FakeBackend(BackendKind.ROOT),FakeBackend(BackendKind.SHIZUKU))
        val phone=sessions.beginPhoneSession("viewer")
        val server=McpServerController(RuntimeEnvironment.getApplication(),sessions)
        try {
            server.start(port=ServerSocket(0).use { it.localPort }).getOrThrow()
            assertEquals(phone.token,sessions.leaseStatus()?.token)
            assertTrue(sessions.acquireLease("remote") is LeaseDecision.Rejected)
        } finally { server.stop(); sessions.endPhoneSession("viewer"); sessions.shutdown() }
    }

}
