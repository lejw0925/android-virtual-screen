package io.github.lejw0925.virtualscreen

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.lejw0925.virtualscreen.backend.*
import io.github.lejw0925.virtualscreen.core.*
import io.github.lejw0925.virtualscreen.mcp.McpToolRegistry
import io.github.lejw0925.virtualscreen.tunnel.TunnelMode
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File

/** Root/shell-only verification adapter. This component does not exist in release. */
class DeviceCheckReceiver: BroadcastReceiver() {
    override fun onReceive(context: Context,intent: Intent) {
        val pending=goAsync()
        context.startForegroundService(Intent(context,VirtualScreenForegroundService::class.java))
        CoroutineScope(SupervisorJob()+Dispatchers.IO).launch {
            val app=context.applicationContext as VirtualScreenApplication
            val output=File(app.filesDir,"device-check.json")
            try {
                val result=when(intent.getStringExtra("check")) {
                    "status" -> {
                        val state=app.sessions.state.value
                        val notification=app.getSystemService(android.app.NotificationManager::class.java).activeNotifications.firstOrNull()?.notification
                        JSONObject().put("ok",true).put("display",state.displayId).put("state",state.status.name)
                            .put("preview",state.previewStatus.name).put("mcp_running",app.mcp.state().running)
                            .put("notification_title",notification?.extras?.getString(android.app.Notification.EXTRA_TITLE))
                            .put("notification_text",notification?.extras?.getString(android.app.Notification.EXTRA_TEXT))
                    }
                    "appearance" -> {
                        val language=intent.getStringExtra("language") ?: "system"
                        require(language in setOf("system","en","zh-Hans","zh-Hant"))
                        app.settings.update { it.copy(language=language,theme=intent.getStringExtra("theme") ?: it.theme) }
                        app.getSystemService(android.app.LocaleManager::class.java).applicationLocales=
                            if(language=="system") android.os.LocaleList.getEmptyLocaleList() else android.os.LocaleList.forLanguageTags(language)
                        JSONObject().put("ok",true)
                    }
                    "mcp" -> { app.mcp.start().getOrThrow(); JSONObject().put("ok",true).put("token",app.mcp.state().token) }
                    "preview" -> {
                        app.sessions.shutdown()
                        val backend=BackendKind.valueOf(intent.getStringExtra("backend") ?: "ROOT")
                        app.settings.update { it.copy(backend=backend) }
                        app.sessions.selectBackend(backend)
                        if(backend==BackendKind.ROOT) app.sessions.requestAuthorization()
                        val lease=app.sessions.phoneTakeover()
                        val created=app.sessions.create(lease.owner,lease.token,app.settings.configForViewer()).also { check(it.ok) { it.code ?: "CREATE_FAILED" } }
                        val launched=app.sessions.launch(lease.owner,lease.token,"io.github.lejw0925.virtualscreen.fixture/.FixtureActivity")
                        check(launched.ok) { launched.code ?: "LAUNCH_FAILED" }
                        JSONObject().put("ok",true).put("backend",backend.name).put("display",app.sessions.state.value.displayId).put("bridge",app.sessions.liveChannel?.isAlive == true)
                    }
                    "geometry" -> {
                        app.sessions.preview()
                        val state=app.sessions.state.value
                        val (width,height)=app.settings.viewerSize()
                        val frame=state.frame
                        frame?.let { File(app.filesDir,"device-frame.jpg").outputStream().use { output -> it.compress(android.graphics.Bitmap.CompressFormat.JPEG,95,output) } }
                        JSONObject().put("ok",true).put("display",state.displayId).put("display_width",state.config.width).put("display_height",state.config.height)
                            .put("status",state.status.name).put("error",state.errorCode)
                            .put("viewer_width",width).put("viewer_height",height).put("frame_width",frame?.width).put("frame_height",frame?.height)
                    }
                    "release_phone" -> { app.sessions.leaseStatus()?.takeIf { it.owner==VirtualScreenSessionManager.PHONE_CLIENT }?.let { app.sessions.releaseLease(it.owner,it.token) }; JSONObject().put("ok",true) }
                    "nodes" -> {
                        val backend=BackendKind.valueOf(intent.getStringExtra("backend") ?: "ROOT")
                        app.sessions.shutdown(); app.sessions.selectBackend(backend)
                        if(backend==BackendKind.ROOT) app.sessions.requestAuthorization()
                        val lease=app.sessions.phoneTakeover()
                        try {
                            check(app.sessions.create(lease.owner,lease.token).ok) { "CREATE_FAILED" }
                            check(app.sessions.launch(lease.owner,lease.token,"io.github.lejw0925.virtualscreen.fixture/.FixtureActivity").ok) { "LAUNCH_FAILED" }
                            delay(600)
                            val observed=app.sessions.observe(lease.owner,lease.token).getOrThrow()
                            val nodes=app.sessions.nodes(lease.owner,lease.token,observed.values["observation_id"] as String).getOrThrow()
                            val target=nodes.nodes.firstOrNull { it.text?.contains("Tap counter",ignoreCase=true) == true && it.clickable } ?: error("CLICKABLE_NODE_MISSING")
                            check(app.sessions.operate(lease.owner,lease.token,nodes.observationId,BackendOperation.NodeAction(target.id,"click")).ok) { "NODE_CLICK_FAILED" }
                            delay(200)
                            var next=app.sessions.observe(lease.owner,lease.token).getOrThrow()
                            val clicked=app.sessions.nodes(lease.owner,lease.token,next.values["observation_id"] as String).getOrThrow()
                            check(clicked.nodes.any { it.text?.contains("Tap counter: 1",ignoreCase=true)==true }) { "NODE_CLICK_EFFECT_MISSING" }
                            val editor=clicked.nodes.firstOrNull { it.editable } ?: error("EDITOR_NODE_MISSING")
                            check(app.sessions.operate(lease.owner,lease.token,clicked.observationId,BackendOperation.NodeAction(editor.id,"set_text","节点中文測試")).ok) { "NODE_TEXT_FAILED" }
                            suspend fun readNodes(): NodeSnapshot {
                                val frame=app.sessions.observe(lease.owner,lease.token).getOrThrow()
                                return app.sessions.nodes(lease.owner,lease.token,frame.values["observation_id"] as String).getOrThrow()
                            }
                            var typed=readNodes()
                            // Accessibility acknowledges before its text-change
                            // event updates cached nodes. Only poll observation;
                            // never replay the accepted write.
                            repeat(10) { if(typed.nodes.none { it.text=="节点中文測試" }) { delay(100); typed=readNodes() } }
                            check(typed.nodes.any { it.text=="节点中文測試" }) { "NODE_TEXT_EFFECT_MISSING" }
                            val scroll=typed.nodes.firstOrNull { it.scrollable } ?: error("SCROLLABLE_NODE_MISSING")
                            check(app.sessions.operate(lease.owner,lease.token,typed.observationId,BackendOperation.NodeAction(scroll.id,"scroll_forward")).ok) { "NODE_SCROLL_FAILED" }
                            JSONObject().put("ok",true).put("backend",backend.name).put("node_count",nodes.nodes.size).put("click_verified",true).put("unicode_verified",true).put("scroll_accepted",true)
                        } finally { app.sessions.shutdown() }
                    }
                    "quick" -> { app.mcp.start().getOrThrow(); app.tunnels.start(TunnelMode.CLOUDFLARE_QUICK); JSONObject().put("ok",true) }
                    "tunnel_state" -> JSONObject().put("ok",true).put("phase",app.tunnels.state().phase).put("address",app.tunnels.state().publicAddress).put("error",app.tunnels.state().errorCode).put("logs",org.json.JSONArray(app.tunnels.state().logs))
                    "cloudflare_login" -> { app.tunnels.login(); JSONObject().put("ok",true) }
                    "login_state" -> JSONObject().put("ok",true).put("phase",app.tunnels.state().phase).put("login_url",app.tunnels.state().loginUrl).put("certificate_ready",app.tunnels.state().certificateReady).put("error",app.tunnels.state().errorCode)
                    "stop" -> { app.tunnels.stop(); app.mcp.stop(); app.sessions.shutdown(); JSONObject().put("ok",true) }
                    else -> error("UNKNOWN_CHECK")
                }
                output.writeText(result.toString())
            } catch(e: Exception) { output.writeText(JSONObject().put("ok",false).put("code",e.code()).put("detail",e.message).toString()) }
            finally { pending.finish() }
        }
    }
}
