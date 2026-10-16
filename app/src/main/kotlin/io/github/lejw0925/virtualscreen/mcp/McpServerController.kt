package io.github.lejw0925.virtualscreen.mcp

import android.content.Context
import io.github.lejw0925.virtualscreen.BuildConfig
import io.github.lejw0925.virtualscreen.core.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject

data class McpClientInfo(val id: String,val name: String,val initialized: Boolean)
data class McpServerState(val running: Boolean = false,val host: String = "127.0.0.1",val port: Int = 8732,val token: String = "",val sessionCount: Int = 0,
    val clients: List<McpClientInfo> = emptyList(),val lastError: String? = null)
private class ClientSession(val id: String,val name: String,val version: String,var lastActivityMillis: Long, val peerIp: String, val forwardedIp: String?) {
    @Volatile var initialized = false
    var activeCalls = 0
    val seen = ConcurrentHashMap.newKeySet<String>()
    val requests = ConcurrentHashMap<String,Job>()
}

/** Streamable HTTP, JSON response mode (GET/SSE is deliberately unsupported). */
class McpServerController(context: Context,private val sessions: VirtualScreenSessionManager,
    private val log: ActivityLogger = ActivityLogger.NONE,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 }) {
    companion object {
        const val DEFAULT_PORT = 8732
        val VERSIONS = listOf("2025-11-25","2025-06-18","2025-03-26")
        private const val MAX_CLIENTS = 32
        private const val HANDSHAKE_IDLE_MILLIS = 60_000L
        private const val SESSION_IDLE_MILLIS = 300_000L
        private const val CLEANUP_INTERVAL_MILLIS = 15_000L
    }
    private val tokenStore = BearerTokenStore(context)
    private val approvalPrefs = context.getSharedPreferences("control_approvals",Context.MODE_PRIVATE)
    private fun tokenFingerprint() = MessageDigest.getInstance("SHA-256").digest(tokenStore.token().toByteArray()).joinToString("") { "%02x".format(it) }
    val approvals = ControlApprovalManager(sessions,approvalPrefs.getString("token",null)==tokenFingerprint(),saveAlwaysAllow = { allowed ->
        approvalPrefs.edit().putString("token",if(allowed) tokenFingerprint() else null).apply()
    })
    private val registry = McpToolRegistry(sessions,approvals,{ owner -> clients[owner]?.name ?: "MCP client" })
    val activity get() = registry.activity
    private val mutableState = MutableStateFlow(McpServerState(token = tokenStore.token()))
    val flow = mutableState.asStateFlow()
    fun state() = flow.value
    fun reportStartupError(code: String) { mutableState.value = state().copy(running=false,lastError=code) }
    private var server: EmbeddedServer<CIOApplicationEngine,CIOApplicationEngine.Configuration>? = null
    private var engineJob: Job? = null
    private val engineEpoch = java.util.concurrent.atomic.AtomicLong()
    private val clients = ConcurrentHashMap<String,ClientSession>()
    private val clientLock = Any()
    private var allowedOrigins: Set<String> = emptySet()
    @Synchronized fun start(lan: Boolean = false,port: Int = DEFAULT_PORT,origins: Set<String> = emptySet()): Result<McpServerState> {
        if (server != null && state().running) return Result.success(state())
        if (server != null) stop()
        return runCatching {
            require(port in 1..65535) { "INVALID_PORT" }
            allowedOrigins = origins
            val host = if(lan) "0.0.0.0" else "127.0.0.1"
            // CIO starts its listener asynchronously. Reject an already occupied
            // endpoint before launch and await the actual bound connectors below.
            java.net.ServerSocket().use { it.bind(java.net.InetSocketAddress(host,port)) }
            val expected = engineEpoch.incrementAndGet()
            val parent = SupervisorJob().also { engineJob = it }
            val rootConfig = serverConfig {
                parentCoroutineContext = parent + Dispatchers.IO + CoroutineExceptionHandler { _, _ ->
                    if(engineEpoch.get() == expected) mutableState.value = state().copy(running = false,lastError = "MCP_PORT_OR_ENGINE_FAILED")
                }
                module { routing {
                    get("/") {
                        call.response.header(HttpHeaders.CacheControl,"no-store")
                        call.response.header("Content-Security-Policy","default-src 'none'; style-src 'unsafe-inline'; frame-ancestors 'none'")
                        call.response.header("X-Content-Type-Options","nosniff")
                        call.response.header("X-Virtual-Screen","mcp")
                        call.respondText(landingPage(),ContentType.Text.Html)
                    }
                    route("/mcp") {
                        post { handlePost(call) }
                        get {
                            if (call.request.headers[HttpHeaders.Accept]?.contains("text/html") == true) call.respondRedirect("/")
                            else if (authorize(call)) call.respond(HttpStatusCode.MethodNotAllowed)
                        }
                        delete {
                            if (!authorize(call)) return@delete
                            val client = getClient(call) ?: return@delete
                            try {
                                disconnectSession(client.id)
                                call.respond(HttpStatusCode.NoContent)
                            } finally { finishCall(client) }
                        }
                    }
                } }
            }
            val engine = embeddedServer(CIO,rootConfig) { connector { this.host = host; this.port = port } }
            server = engine
            engine.start(wait = false)
            runBlocking { withTimeout(5000) { engine.engine.resolvedConnectors() } }
            mutableState.value = McpServerState(true,host,port,tokenStore.token())
            CoroutineScope(parent + Dispatchers.IO).launch {
                while(isActive) {
                    delay(CLEANUP_INTERVAL_MILLIS)
                    recycleIdleSessions()
                }
            }
            sessions.allowRemoteControl()
            state()
        }.onFailure { failure ->
            runCatching { server?.stop(0,500) }; server = null; engineJob?.cancel(); engineJob = null
            val occupied = generateSequence(failure) { it.cause }.any { it is java.net.BindException }
            mutableState.value = state().copy(running = false,lastError = if(occupied) "MCP_PORT_IN_USE" else "MCP_START_FAILED")
        }
    }
    @Synchronized fun stop() {
        engineEpoch.incrementAndGet()
        synchronized(clientLock) {
            mutableState.update { it.copy(running=false) }
            clients.keys.toList().forEach(::disconnectSession)
        }
        server?.stop(100,1500); server = null
        engineJob?.cancel(); engineJob = null
        mutableState.value = state().copy(running = false,sessionCount = 0,clients = emptyList())
        approvals.clear()
    }
    @Synchronized fun rotateToken(): String {
        clients.keys.toList().forEach(::disconnectSession)
        approvals.setAlwaysAllow(false)
        return tokenStore.rotate().also { mutableState.value = state().copy(token = it) }
    }
    fun disconnectSession(id: String) = synchronized(clientLock) {
        clients.remove(id)?.let { client ->
            log.record(ActivityLogEntry(category="mcp",operation="disconnect",status="closed",peerIp=client.peerIp,forwardedIp=client.forwardedIp,client=client.name))
            client.requests.values.forEach { it.cancel() }; sessions.disconnectClient(client.id)
        }
        registry.forgetClient(id)
        updateClients()
    }
    private fun updateClients() { mutableState.update { it.copy(sessionCount = clients.size,clients = clients.values.map { client -> McpClientInfo(client.id,client.name,client.initialized) }) } }
    /** Admission, expiration and request tracking share a lock so cleanup cannot race a live request. */
    internal fun recycleIdleSessions() = synchronized(clientLock) {
        val now = nowMillis()
        val controller = sessions.leaseStatus()?.owner
        clients.values.filter { client ->
            val timeout = if(client.initialized) SESSION_IDLE_MILLIS else HANDSHAKE_IDLE_MILLIS
            client.activeCalls == 0 && client.id != controller && now - client.lastActivityMillis >= timeout
        }.forEach { disconnectSession(it.id) }
    }
    private fun createClient(name: String,version: String,peerIp: String,forwardedIp: String?): ClientSession? = synchronized(clientLock) {
        recycleIdleSessions()
        if(!state().running || clients.size >= MAX_CLIENTS) return@synchronized null
        ClientSession(UUID.randomUUID().toString(),name,version,nowMillis(),peerIp,forwardedIp).also {
            log.record(ActivityLogEntry(category="mcp",operation="connect",status="accepted",peerIp=peerIp,forwardedIp=forwardedIp,client=name))
            it.activeCalls = 1
            clients[it.id] = it
            updateClients()
        }
    }
    private fun finishCall(client: ClientSession) = synchronized(clientLock) {
        client.activeCalls--
        client.lastActivityMillis = nowMillis()
    }
    private suspend fun authorize(call: ApplicationCall): Boolean {
        val origin = call.request.headers[HttpHeaders.Origin]
        if (origin != null && origin !in allowedOrigins) { logHttp(call,"authenticate","origin_rejected"); call.respond(HttpStatusCode.Forbidden); return false }
        val auth = call.request.headers[HttpHeaders.Authorization]
        val provided = auth?.takeIf { it.startsWith("Bearer ",ignoreCase = true) }?.substring(7)
        if (provided == null || !MessageDigest.isEqual(provided.toByteArray(),tokenStore.token().toByteArray())) {
            logHttp(call,"authenticate","unauthorized")
            call.response.header(HttpHeaders.WWWAuthenticate,"Bearer")
            call.respond(HttpStatusCode.Unauthorized); return false
        }
        return true
    }
    private suspend fun getClient(call: ApplicationCall): ClientSession? {
        val id = call.request.headers["Mcp-Session-Id"]
        if (id.isNullOrBlank()) { call.respond(HttpStatusCode.BadRequest); return null }
        val version = call.request.headers["MCP-Protocol-Version"] ?: "2025-03-26"
        val (client,status) = synchronized(clientLock) {
            recycleIdleSessions()
            val client = clients[id]
            when {
                client == null -> null to HttpStatusCode.NotFound
                version != client.version -> null to HttpStatusCode.BadRequest
                else -> {
                    client.activeCalls++
                    client.lastActivityMillis = nowMillis()
                    client to HttpStatusCode.OK
                }
            }
        }
        if(client == null) call.respond(status)
        return client
    }
    private suspend fun handlePost(call: ApplicationCall) {
        if (!authorize(call)) return
        if (call.request.contentType().withoutParameters() != ContentType.Application.Json) { call.respond(HttpStatusCode.UnsupportedMediaType); return }
        val accept = call.request.headers[HttpHeaders.Accept].orEmpty()
        if (!accept.contains("application/json") || !accept.contains("text/event-stream")) { call.respond(HttpStatusCode.NotAcceptable); return }
        val text = try {
            val channel = call.receiveChannel(); val bytes = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            withTimeout(10000) { while(true) { val count = channel.readAvailable(buffer); if(count < 0) break; require(bytes.size()+count <= 131072); bytes.write(buffer,0,count) } }
            bytes.toString(Charsets.UTF_8.name())
        } catch(c: CancellationException) { throw c }
          catch (_: Exception) { call.respond(HttpStatusCode.PayloadTooLarge); return }
        val request = try { JSONObject(text) } catch (_: Exception) { call.respondText(error(null,-32700,"Parse error").toString(),ContentType.Application.Json,HttpStatusCode.BadRequest); return }
        val id = request.opt("id").takeUnless { it == JSONObject.NULL }
        val method = request.optString("method")
        if (request.optString("jsonrpc") != "2.0" || method.isBlank() || (id != null && id !is String && id !is Number)) {
            call.respondText(error(id,-32600,"Invalid request").toString(),ContentType.Application.Json,HttpStatusCode.BadRequest); return
        }
        if (method == "initialize") {
            if (id == null || call.request.headers["Mcp-Session-Id"] != null) { call.respond(HttpStatusCode.BadRequest); return }
            val params = request.optJSONObject("params") ?: JSONObject()
            val requested = params.optString("protocolVersion")
            val version = requested.takeIf { it in VERSIONS } ?: VERSIONS.first()
            val name = params.optJSONObject("clientInfo")?.optString("name")?.take(100).orEmpty().ifBlank { "MCP client" }
            val client = createClient(name,version,peerIp(call),forwardedIp(call))
            if(client == null) {
                call.response.header(HttpHeaders.RetryAfter,"15")
                call.respond(HttpStatusCode.ServiceUnavailable); return
            }
            try {
                call.response.header("Mcp-Session-Id",client.id)
                call.respondText(success(id,JSONObject().put("protocolVersion",version).put("capabilities",JSONObject().put("tools",JSONObject().put("listChanged",false)))
                    .put("serverInfo",JSONObject().put("name","android-virtual-screen").put("version",BuildConfig.VERSION_NAME))
                    .put("instructions",sessions.systemLanguage.text("先申请控制权，身份绑定当前 MCP 会话。每次调用必须使用手机系统语言填写 purpose 操作目的。每次输入后重新观察。跨屏重启需手机确认。","Acquire control before device operations. Identity is bound to Mcp-Session-Id. Every call must supply purpose in the phone’s system language. Observe again after every input. Cross-display app restart requires phone confirmation.","先申請控制權，身分綁定目前 MCP 工作階段。每次呼叫必須使用手機系統語言填寫 purpose 操作目的。每次輸入後重新觀察。跨螢幕重新啟動需手機確認。"))).toString(),ContentType.Application.Json)
            } finally { finishCall(client) }
            return
        }
        val client = getClient(call) ?: return
        try { handleClientPost(call,request,id,method,client) }
        finally { finishCall(client) }
    }
    private suspend fun handleClientPost(call: ApplicationCall,request: JSONObject,id: Any?,method: String,client: ClientSession) {
        if (id == null) {
            when(method) {
                "notifications/initialized" -> synchronized(clientLock) { client.initialized = true; updateClients() }
                "notifications/cancelled" -> request.optJSONObject("params")?.opt("requestId")?.let { client.requests[it.toString()]?.cancel() }
                else -> { call.respond(HttpStatusCode.BadRequest); return }
            }
            call.respond(HttpStatusCode.Accepted); return
        }
        if (!client.initialized) { call.respondText(error(id,-32000,"SESSION_NOT_INITIALIZED").toString(),ContentType.Application.Json); return }
        val key = id.toString()
        if (client.seen.size >= 2048 || !client.seen.add(key)) {
            logHttp(call,"request","duplicate_rejected",client.name)
            call.respondText(error(id,-32001,"DUPLICATE_REQUEST_OR_SESSION_LIMIT; initialize a new session; device inputs are never replayed").toString(),ContentType.Application.Json); return
        }
        val job = currentCoroutineContext().job
        client.requests[key] = job
        val operation = if(method=="tools/call") request.optJSONObject("params")?.optString("name") ?: "invalid_tool" else method
        val started = nowMillis()
        logHttp(call,operation,"started",client.name)
        try {
            val params = request.optJSONObject("params")
            val awaitingRestart = method == "tools/call" && params?.optString("name") == "launch_app"
            // Phone approval may wait two minutes, followed by the bounded backend launch.
            val result = withTimeout(if (awaitingRestart) 150_000L else 60_000L) {
                when(method) {
                    "ping" -> JSONObject()
                    "tools/list" -> JSONObject().put("tools",JSONArray(registry.tools.map { JSONObject().put("name",it.name).put("description",it.description).put("inputSchema",it.schema).put("annotations",JSONObject().put("readOnlyHint",it.readOnly).put("idempotentHint",false).put("openWorldHint",false)) }))
                    "tools/call" -> {
                        val params = request.optJSONObject("params") ?: error("INVALID_PARAMS")
                        registry.call(params.getString("name"),params.optJSONObject("arguments") ?: JSONObject(),client.id,key)
                    }
                    else -> { call.respondText(error(id,-32601,"Method not found").toString(),ContentType.Application.Json); return@withTimeout null }
                }
            }
            if (result != null) {
                val outcome=result.optJSONObject("structuredContent")
                logHttp(call,operation,if(outcome?.optBoolean("ok",true)==false) "failed" else "completed",client.name,
                    "duration_ms=${nowMillis()-started}; code=${outcome?.optString("code").orEmpty()}")
                call.respondText(success(id,result).toString(),ContentType.Application.Json)
            }
        } catch (_: CancellationException) {
            logHttp(call,operation,"cancelled",client.name,"duration_ms=${nowMillis()-started}")
            sessions.cancelActive(client.id)
            withContext(NonCancellable) { runCatching { call.respondText(error(id,-32800,"REQUEST_CANCELLED; observe again before further input").toString(),ContentType.Application.Json) } }
        } catch (_: Exception) {
            logHttp(call,operation,"invalid_parameters",client.name)
            call.respondText(error(id,-32602,"Invalid parameters").toString(),ContentType.Application.Json)
        }
        finally { client.requests.remove(key,job) }
    }
    private fun peerIp(call: ApplicationCall): String = call.request.local.remoteAddress
    private fun forwardedIp(call: ApplicationCall): String? {
        if(peerIp(call) !in setOf("127.0.0.1","::1","0:0:0:0:0:0:0:1")) return null
        // Proxy metadata is recorded separately, never used for authorization.
        return call.request.headers["CF-Connecting-IP"]?.takeIf { it.length <= 45 && it.matches(Regex("[0-9a-fA-F:.]+")) }
    }
    private fun logHttp(call: ApplicationCall,operation: String,status: String,client: String? = null,detail: String? = null) {
        log.record(ActivityLogEntry(category="mcp",operation=operation,status=status,peerIp=peerIp(call),forwardedIp=forwardedIp(call),client=client,detail=detail))
    }
    private fun landingPage(): String {
        val l=sessions.systemLanguage
        val title=l.text("虚拟屏 · MCP 连接","Virtual Screen · MCP connection","虛擬螢幕 · MCP 連線")
        val intro=l.text("隧道已到达手机上的虚拟屏服务。","The tunnel has reached Virtual Screen on your phone.","通道已到達手機上的虛擬螢幕服務。")
        val instruction=l.text("将完整的 /mcp 地址和认证配置添加到 Agent 的 MCP 设置。请在手机应用内复制连接配置。浏览器页面用于检查连接；远程操作仍需要认证和控制租约。","Add the full /mcp URL and authentication configuration to your agent's MCP settings. Copy the connection configuration in the phone app. This page checks connectivity; device operations require authentication and a control lease.","將完整的 /mcp 位址和驗證設定加入 Agent 的 MCP 設定。請在手機應用程式內複製連線設定。瀏覽器頁面用於檢查連線；遠端操作仍需要驗證和控制租約。")
        return """<!doctype html><html lang="${l.tag}"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>$title</title>
            <style>:root{color-scheme:light dark;font:16px/1.6 system-ui,sans-serif}body{max-width:38rem;margin:12vh auto;padding:24px}main{padding:32px;border:1px solid #8886;border-radius:24px}h1{font-size:1.5rem}code{display:block;padding:16px;border-radius:12px;background:#8882;overflow-wrap:anywhere}p{margin-block:20px}</style>
            <main><h1>$title</h1><p>$intro</p><code>POST /mcp · Streamable HTTP · Bearer</code><p>$instruction</p></main></html>""".trimIndent()
    }
    fun clientConfiguration(url: String = "http://127.0.0.1:${state().port}/mcp") = JSONObject().put("mcpServers",JSONObject().put("android-virtual-screen",JSONObject()
        .put("type","http").put("url",url).put("headers",JSONObject().put("Authorization","Bearer ${state().token}")))).toString(2).replace("\\/","/")
    private fun success(id: Any?,result: JSONObject) = JSONObject().put("jsonrpc","2.0").put("id",id ?: JSONObject.NULL).put("result",result)
    private fun error(id: Any?,code: Int,message: String) = JSONObject().put("jsonrpc","2.0").put("id",id ?: JSONObject.NULL).put("error",JSONObject().put("code",code).put("message",message))
}
