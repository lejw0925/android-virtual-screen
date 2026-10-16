package io.github.lejw0925.virtualscreen.tunnel

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.util.Base64
import io.github.lejw0925.virtualscreen.core.CredentialVault
import io.github.lejw0925.virtualscreen.core.ActivityLogger
import io.github.lejw0925.virtualscreen.core.ActivityLogEntry
import io.github.lejw0925.virtualscreen.core.code
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

data class TunnelState(val mode: TunnelMode = TunnelMode.OFF,val running: Boolean = false,val publicAddress: String? = null,val errorCode: String? = null,val phase: String = "stopped",
    val loginUrl: String? = null,val certificateReady: Boolean = false,val logs: List<String> = emptyList(),val publicVerified: Boolean = false)
data class CloudflareTunnel(val id: String,val name: String)

/** Exactly one long-lived child; CLI management uses private temporary files. */
class TunnelManager(context: Context,private val mcpPort: Int = 8732,private val log: ActivityLogger = ActivityLogger.NONE) {
    private val app = context.applicationContext
    private val vault = CredentialVault(app)
    private val directory = File(app.noBackupFilesDir,"tunnel-runtime").apply { mkdirs() }
    private val certificate = File(directory,".cloudflared/cert.pem")
    private val scope = CoroutineScope(SupervisorJob()+Dispatchers.IO+CoroutineExceptionHandler { _, _ ->
        mutableState.value = state().copy(running=false,phase="error",publicAddress=null,errorCode="TUNNEL_PROCESS_IO_FAILED")
    })
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(TunnelState())
    val flow = mutableState.asStateFlow()
    fun state() = flow.value
    @Volatile private var child: Process? = null
    @Volatile private var epoch = 0L
    @Volatile private var secrets: List<String> = emptyList()
    private var configuredAddress: String? = null
    private var supervisor: Job? = null
    private var publicProbe: Job? = null
    private var probeCall: okhttp3.Call? = null
    private var verifiedAddress: String? = null
    private var dns: AndroidDnsBridge? = null
    private val http = OkHttpClient.Builder().callTimeout(30,TimeUnit.SECONDS).build()
    private val probeHttp = http.newBuilder().callTimeout(8,TimeUnit.SECONDS).connectTimeout(5,TimeUnit.SECONDS).readTimeout(5,TimeUnit.SECONDS).build()
    private var connectionGeneration = 0L
    private var progress = TunnelConnectionProgress()
    private var watchingNetwork = false
    private val connectivity = app.getSystemService(ConnectivityManager::class.java)
    private val networkCallback = object: ConnectivityManager.NetworkCallback() {
        override fun onLost(network: Network) {
            synchronized(this@TunnelManager) {
                connectionGeneration++
                verifiedAddress=null
                progress=TunnelConnectionProgress()
                publicProbe?.cancel(); publicProbe=null
                probeCall?.cancel(); probeCall=null
                mutableState.update { if(it.running) it.copy(phase="reconnecting",errorCode=null,publicVerified=false) else it }
            }
        }
        override fun onAvailable(network: Network) { if(state().running) appendLog("NETWORK_AVAILABLE") }
    }
    init { directory.listFiles()?.forEach { it.deleteRecursively() } }
    private fun watchNetwork() {
        if(!watchingNetwork) runCatching { connectivity.registerDefaultNetworkCallback(networkCallback); watchingNetwork=true }
    }
    fun saveProfile(frpc: FrpcConfig?,cloudflare: CloudflareConfig?) {
        val data = JSONObject(vault.get("profile") ?: "{}")
        frpc?.let { data.put("frpc",JSONObject().put("server",it.serverAddress).put("port",it.serverPort).put("token",it.token).put("name",it.proxyName).put("protocol",it.protocol)
            .put("remotePort",it.remotePort).put("hostname",it.hostname).put("publicPort",it.publicPort).put("toml",it.advancedToml).put("certificate",it.certificatePem).put("privateKey",it.privateKeyPem)) }
        cloudflare?.let { data.put("cloudflare",JSONObject().put("token",it.tunnelToken).put("hostname",it.hostname).put("id",it.tunnelId)) }
        vault.put("profile",data.toString())
    }
    fun loadProfile(): Pair<FrpcConfig?,CloudflareConfig?> = runCatching {
        val data = JSONObject(vault.get("profile") ?: "{}")
        val f = data.optJSONObject("frpc")?.let { FrpcConfig(it.getString("server"),it.optInt("port",7000),it.optString("token").takeIf(String::isNotBlank),it.optString("name","virtual-screen-mcp"),it.optString("protocol","tcp"),it.optInt("remotePort",8732),it.optString("hostname"),if(it.has("publicPort")) it.optInt("publicPort").takeIf { p -> p > 0 } else null,it.optString("toml").takeIf(String::isNotBlank),it.optString("certificate").takeIf(String::isNotBlank),it.optString("privateKey").takeIf(String::isNotBlank)) }
        val c = data.optJSONObject("cloudflare")?.let { CloudflareConfig(it.optString("token").takeIf(String::isNotBlank),it.optString("hostname").takeIf(String::isNotBlank),it.optString("id").takeIf(String::isNotBlank)) }
        f to c
    }.getOrDefault(null to null)

    suspend fun start(mode: TunnelMode,frpc: FrpcConfig? = null,cloudflare: CloudflareConfig? = null): TunnelState = withContext(Dispatchers.IO) { mutex.withLock {
        stop()
        if(mode == TunnelMode.OFF) return@withLock state()
        try {
            val command: List<String>
            val environment = mutableMapOf<String,String>()
            when(mode) {
                TunnelMode.FRPC -> {
                    val config = requireNotNull(frpc) { "FRPC_CONFIG_REQUIRED" }
                    val cert = config.certificatePem?.takeIf(String::isNotBlank)?.let { require(it.length <= 65536 && "BEGIN CERTIFICATE" in it) { "INVALID_TLS_CERTIFICATE" }; privateFile("frpc-cert.pem",it) }
                    val key = config.privateKeyPem?.takeIf(String::isNotBlank)?.let { require(it.length <= 32768 && "PRIVATE KEY" in it) { "INVALID_TLS_PRIVATE_KEY" }; privateFile("frpc-key.pem",it) }
                    val (toml,address) = TunnelConfiguration.frpc(config,mcpPort,cert?.absolutePath,key?.absolutePath)
                    configuredAddress = address
                    secrets = listOfNotNull(config.token,config.advancedToml,config.privateKeyPem)
                    val file = privateFile("frpc.toml",toml)
                    command = listOf(binary("frpc").absolutePath,"-c",file.absolutePath)
                }
                TunnelMode.CLOUDFLARE_QUICK -> {
                    command = listOf(binary("cloudflared").absolutePath,"--no-autoupdate","tunnel","--protocol","http2","--url","http://127.0.0.1:$mcpPort")
                }
                TunnelMode.CLOUDFLARE_NAMED -> {
                    val config = requireNotNull(cloudflare) { "CLOUDFLARE_CONFIG_REQUIRED" }
                    val host = TunnelConfiguration.hostname(config.hostname ?: error("CLOUDFLARE_HOSTNAME_REQUIRED"))
                    configuredAddress = "https://$host/mcp"
                    val token = config.tunnelToken?.takeIf(String::isNotBlank)
                    if(token != null) {
                        secrets = listOf(token)
                        environment["TUNNEL_TOKEN"] = token
                        command = listOf(binary("cloudflared").absolutePath,"--no-autoupdate","tunnel","--protocol","http2","run")
                    } else {
                        val id = config.tunnelId ?: error("TUNNEL_ID_REQUIRED")
                        val credentials = vault.get("tunnel-$id") ?: error("TUNNEL_CREDENTIALS_REQUIRED")
                        secrets = listOf(credentials)
                        val cred = privateFile("credentials.json",credentials)
                        val file = privateFile("config.yml",TunnelConfiguration.cloudflare(id,host,cred.absolutePath,mcpPort))
                        command = listOf(binary("cloudflared").absolutePath,"--no-autoupdate","tunnel","--config",file.absolutePath,"--protocol","http2","run",id)
                    }
                }
                else -> error("INVALID_MODE")
            }
            saveProfile(frpc,cloudflare)
            val expected = ++epoch
            mutableState.value = TunnelState(mode,true,phase="connecting",certificateReady=hasCertificate(),logs=state().logs)
            watchNetwork()
            appendLog("TUNNEL_START ${mode.name}")
            supervisor = scope.launch {
                var attempts = 0
                while(isActive && epoch == expected) {
                    val process = runCatching { launchChild(command,environment,expected) }.getOrElse {
                        if(epoch == expected) mutableState.value = state().copy(running=false,errorCode="TUNNEL_EXEC_FAILED",phase="error")
                        return@launch
                    }
                    synchronized(this@TunnelManager) {
                        connectionGeneration++
                        progress = TunnelConnectionProgress()
                        verifiedAddress = null
                        publicProbe?.cancel(); publicProbe = null
                        probeCall?.cancel(); probeCall = null
                        if(mode == TunnelMode.CLOUDFLARE_QUICK) configuredAddress = null
                    }
                    try {
                        process.inputStream.bufferedReader().useLines { lines -> lines.forEach { line -> if(epoch == expected) consumeLine(line,expected) } }
                    } catch (_: java.io.IOException) {
                        if(epoch == expected) appendLog("TUNNEL_PROCESS_IO_FAILED")
                    }
                    if(epoch != expected) { terminate(process); break }
                    if(process.isAlive) process.destroy()
                    val exit = process.waitFor()
                    if(epoch != expected) break
                    synchronized(this@TunnelManager) { if(child === process) child = null }
                    mutableState.update { it.copy(phase="reconnecting",errorCode=null,publicVerified=false) }
                    appendLog("TUNNEL_EXIT_$exit; automatic retry")
                    attempts++
                    delay((1000L shl attempts.coerceAtMost(5)).coerceAtMost(30000))
                }
            }
        } catch(c: CancellationException) { stop(); throw c }
          catch(e: Exception) { mutableState.value = TunnelState(mode,errorCode=e.code(),phase="error",certificateReady=hasCertificate(),logs=state().logs); appendLog(e.code()) }
        state()
    } }
    @Synchronized fun stop() {
        epoch++
        connectionGeneration++
        supervisor?.cancel(); supervisor = null
        publicProbe?.cancel(); publicProbe = null
        probeCall?.cancel(); probeCall = null
        verifiedAddress = null
        val process = child; child = null
        process?.let(::terminate)
        dns?.close(); dns = null
        if(watchingNetwork) { runCatching { connectivity.unregisterNetworkCallback(networkCallback) }; watchingNetwork=false }
        directory.listFiles()?.forEach { it.deleteRecursively() }
        secrets = emptyList(); configuredAddress = null
        mutableState.value = TunnelState(certificateReady=hasCertificate(),logs=state().logs)
    }
    @Synchronized private fun consumeLine(line: String,expected: Long) {
        if(epoch != expected) return
        if(state().mode == TunnelMode.CLOUDFLARE_QUICK) Regex("https://[a-zA-Z0-9-]+\\.trycloudflare\\.com").find(line)?.value?.let { configuredAddress = "$it/mcp" }
        val current=state()
        val next=progress.update(current,line,configuredAddress)
        if(next.phase == "reconnecting" && current.phase != "reconnecting") {
            connectionGeneration++
            verifiedAddress=null
            publicProbe?.cancel(); publicProbe=null
            probeCall?.cancel(); probeCall=null
        }
        mutableState.value=if(next.phase == "connected" && next.mode in setOf(TunnelMode.CLOUDFLARE_QUICK,TunnelMode.CLOUDFLARE_NAMED) && verifiedAddress != configuredAddress)
            next.copy(phase="verifying",publicVerified=false)
        else next
        if(state().phase == "verifying" && publicProbe?.isActive != true) {
            configuredAddress?.let { address ->
                val generation=connectionGeneration
                publicProbe = scope.launch { verifyPublicOrigin(address,expected,generation) }
            }
        }
        appendLog(line)
    }
    private suspend fun verifyPublicOrigin(address: String,expected: Long,generation: Long) {
        var attempt = 0
        while(currentCoroutineContext().isActive && epoch == expected && connectionGeneration == generation && state().running && state().phase == "verifying") {
            val request = Request.Builder().url(address.removeSuffix("/mcp") + "/").get().build()
            val call = synchronized(this) {
                if(epoch != expected || connectionGeneration != generation) return
                probeHttp.newCall(request).also { probeCall=it }
            }
            val ready = try {
                call.execute().use { it.isSuccessful && it.header("X-Virtual-Screen") == "mcp" }
            } catch(c: CancellationException) { throw c }
              catch(e: Exception) {
                if(epoch == expected && (attempt == 0 || attempt % 5 == 0)) appendLog("PUBLIC_ORIGIN_PENDING ${e.javaClass.simpleName}")
                false
            } finally { synchronized(this) { if(probeCall === call) probeCall=null } }
            if(ready) {
                synchronized(this) {
                    if(epoch == expected && connectionGeneration == generation && state().running && state().phase == "verifying") {
                        verifiedAddress=address
                        mutableState.update { it.copy(phase="connected",publicAddress=address,errorCode=null,publicVerified=true) }
                        appendLog("PUBLIC_ORIGIN_VERIFIED")
                    }
                }
                return
            }
            attempt++
            delay((2000L shl attempt.coerceAtMost(4)).coerceAtMost(30_000))
        }
    }
    @Synchronized private fun appendLog(line: String) {
        val safe = TunnelConfiguration.redact(line,secrets)
        val time = java.time.LocalTime.now().toString().take(12)
        mutableState.update { it.copy(logs=(it.logs + "$time $safe").takeLast(160)) }
        log.record(ActivityLogEntry(category="tunnel",operation=state().mode.name.lowercase(),status=state().phase,detail=safe))
    }
    fun hasCertificate(): Boolean = runCatching { vault.get("origin-cert") != null }.getOrDefault(false)

    suspend fun login(): TunnelState = withContext(Dispatchers.IO) { mutex.withLock {
        stop()
        if(hasCertificate()) { mutableState.value = state().copy(phase="credentials_ready",certificateReady=true); return@withLock state() }
        mutableState.value = TunnelState(TunnelMode.CLOUDFLARE_NAMED,phase="login",logs=state().logs)
        val expected = ++epoch
        try {
            val process = launchChild(listOf(binary("cloudflared").absolutePath,"--no-autoupdate","tunnel","--origincert",certificate.absolutePath,"login"),expected=expected)
            supervisor = scope.launch {
                val watchdog = launch { delay(300000); if(process.isAlive) process.destroy() }
                try {
                    process.inputStream.bufferedReader().useLines { lines -> lines.forEach { line ->
                        if(epoch == expected) {
                            Regex("https://dash\\.cloudflare\\.com/argotunnel[^\\s]*").find(line)?.value?.let { url -> mutableState.value = state().copy(loginUrl=url) }
                            appendLog(line)
                        }
                    } }
                    val exit = process.waitFor()
                    if(epoch == expected) {
                        if(exit == 0 && certificate.isFile && certificate.length() in 50..65536) {
                            val pem = certificate.readText(); parseCertificate(pem)
                            vault.put("origin-cert",pem)
                            mutableState.value = state().copy(phase="credentials_ready",certificateReady=true,loginUrl=null)
                        } else mutableState.value = state().copy(phase="error",errorCode="CLOUDFLARE_LOGIN_CERTIFICATE_NOT_RECEIVED",loginUrl=null)
                    }
                } catch(e: Exception) { if(epoch == expected) mutableState.value = state().copy(phase="error",errorCode=e.code(),loginUrl=null) }
                finally { watchdog.cancel(); terminate(process); certificate.delete(); synchronized(this@TunnelManager) { if(child === process) child = null } }
            }
        } catch(e: Exception) { if(epoch == expected) mutableState.value = state().copy(phase="error",errorCode=e.code()) }
        state()
    } }
    suspend fun listTunnels(): List<CloudflareTunnel> = management {
        val output = command(listOf("tunnel","--origincert",certificate.absolutePath,"list","--output","json"))
        val array = JSONArray(output.substring(output.indexOf('[')))
        List(array.length()) { array.getJSONObject(it).let { CloudflareTunnel(it.getString("id"),it.getString("name")) } }
    }
    suspend fun createTunnel(name: String): CloudflareTunnel = management {
        mutableState.update { it.copy(phase="tunnel_configuring",errorCode=null) }
        val created=CloudflareTunnelSetup.create(name,certificate.absolutePath,File(directory,"new-tunnel.json")) { command(it) }
        vault.put("tunnel-${created.tunnel.id}",created.credentials)
        if(created.reconciled) appendLog("TUNNEL_CREATE_RECONCILED")
        mutableState.update { it.copy(phase="credentials_ready",errorCode=null) }
        created.tunnel
    }
    suspend fun obtainTunnelCredentials(id: String) = management {
        require(id.matches(Regex("[a-fA-F0-9-]{36}"))) { "INVALID_TUNNEL_ID" }
        val file = File(directory,"selected-tunnel.json")
        try {
            command(listOf("tunnel","--origincert",certificate.absolutePath,"token","--credentials-file",file.absolutePath,id))
            val data = JSONObject(file.readText())
            require(data.getString("TunnelID") == id) { "TUNNEL_CREDENTIALS_MISMATCH" }
            vault.put("tunnel-$id",data.toString())
        } finally { file.delete() }
    }
    suspend fun bindDns(id: String,hostname: String) = management {
        require(id.matches(Regex("[a-fA-F0-9-]{36}"))) { "INVALID_TUNNEL_ID" }
        mutableState.value = state().copy(phase="dns_binding")
        // Never pass --overwrite-dns: a conflicting existing record is a visible error.
        command(listOf("tunnel","--origincert",certificate.absolutePath,"route","dns",id,TunnelConfiguration.hostname(hostname)))
        mutableState.value = state().copy(phase="dns_ready",errorCode=null)
    }
    suspend fun importTunnelCredentials(json: String): String {
        val data = JSONObject(json); val id = data.getString("TunnelID")
        require(id.matches(Regex("[a-fA-F0-9-]{36}")) && data.has("AccountTag") && data.has("TunnelSecret")) { "INVALID_TUNNEL_CREDENTIALS" }
        vault.put("tunnel-$id",data.toString()); return id
    }
    suspend fun availableDomains(): List<String> = withContext(Dispatchers.IO) {
        val cert = parseCertificate(vault.get("origin-cert") ?: error("CLOUDFLARE_LOGIN_REQUIRED"))
        val request = Request.Builder().url("https://api.cloudflare.com/client/v4/zones/${cert.getString("zoneID")}")
            .header("Authorization","Bearer ${cert.getString("apiToken")}").build()
        http.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "CLOUDFLARE_DOMAIN_QUERY_FAILED" }
            val data = JSONObject(response.body.string()); check(data.optBoolean("success")) { "CLOUDFLARE_DOMAIN_QUERY_FAILED" }
            listOf(data.getJSONObject("result").getString("name"))
        }
    }
    private suspend fun <T> management(block: suspend () -> T): T = withContext(Dispatchers.IO) { mutex.withLock {
        check(!state().running && child == null) { "STOP_TUNNEL_BEFORE_CONFIGURATION" }
        val cert = vault.get("origin-cert") ?: error("CLOUDFLARE_LOGIN_REQUIRED")
        privateFile(".cloudflared/cert.pem",cert)
        val expected=epoch
        try { block() }
        catch(c: CancellationException) { throw c }
        catch(e: Exception) {
            if(epoch == expected) mutableState.update { it.copy(phase="error",errorCode=e.code()) }
            throw e
        } finally { certificate.delete() }
    } }
    private suspend fun command(args: List<String>,timeoutMillis: Long = 90_000): String {
        val expected = epoch
        val process = launchChild(listOf(binary("cloudflared").absolutePath,"--no-autoupdate")+args,expected=expected)
        val output = StringBuilder()
        val drain = scope.launch { runCatching { process.inputStream.bufferedReader().useLines { lines -> lines.forEach { line -> if(output.length < 262144) output.appendLine(line); appendLog(line) } } } }
        try {
            check(process.waitFor(timeoutMillis,TimeUnit.MILLISECONDS)) { "CLOUDFLARE_COMMAND_TIMEOUT" }
            drain.join()
            currentCoroutineContext().ensureActive()
            check(epoch == expected) { "TUNNEL_CONFIGURATION_CANCELLED" }
            check(process.exitValue()==0) { if("already exists" in output || "record with that host" in output) "DNS_RECORD_CONFLICT" else "CLOUDFLARE_COMMAND_FAILED" }
            return output.toString()
        } finally { terminate(process); drain.cancel(); synchronized(this) { if(child === process) child = null } }
    }
    @Synchronized private fun launchChild(command: List<String>,environment: Map<String,String> = emptyMap(),expected: Long): Process {
        check(epoch == expected) { "TUNNEL_CONFIGURATION_CANCELLED" }
        check(child?.isAlive != true) { "TUNNEL_PROCESS_BUSY" }
        // Resolver creation, process launch and publication share stop()'s lock.
        // A concurrent stop cannot leave a new DNS bridge or child behind.
        return try { builder(command,environment).start().also { child = it } }
        catch(e: Exception) { dns?.close(); dns = null; throw e }
    }
    private fun terminate(process: Process) {
        runCatching {
            if(process.isAlive) process.destroy()
            if(!process.waitFor(1500,TimeUnit.MILLISECONDS)) { process.destroyForcibly(); process.waitFor(1000,TimeUnit.MILLISECONDS) }
        }
    }
    private fun parseCertificate(pem: String): JSONObject {
        val block = Regex("-----BEGIN ARGO TUNNEL TOKEN-----([\\s\\S]*?)-----END ARGO TUNNEL TOKEN-----").find(pem)?.groupValues?.get(1) ?: error("INVALID_ORIGIN_CERTIFICATE")
        val data = JSONObject(Base64.decode(block,Base64.DEFAULT).toString(Charsets.UTF_8))
        require(data.optString("apiToken").isNotBlank() && data.optString("zoneID").isNotBlank()) { "INVALID_ORIGIN_CERTIFICATE" }
        return data
    }
    private fun privateFile(name: String,text: String) = File(directory,name).apply { parentFile?.mkdirs(); writeText(text); setReadable(false,false); setWritable(false,false); setReadable(true,true); setWritable(true,true) }
    private fun binary(name: String) = File(app.applicationInfo.nativeLibraryDir,"lib$name.so").also { check(it.canExecute()) { "TUNNEL_BINARY_MISSING" } }
    @Synchronized private fun builder(command: List<String>,environment: Map<String,String> = emptyMap()) = ProcessBuilder(command).directory(directory).redirectErrorStream(true).apply {
        environment().putAll(environment)
        val resolver = dns ?: AndroidDnsBridge().also { dns = it }
        environment()["VIRTUAL_SCREEN_DNS_ADDRESS"] = resolver.address
        environment()["TUNNEL_DNS_RESOLVER_ADDRS"] = resolver.address
        environment()["TMPDIR"] = app.cacheDir.absolutePath
        environment()["SSL_CERT_DIR"] = "/apex/com.android.conscrypt/cacerts:/system/etc/security/cacerts"
    }
}
