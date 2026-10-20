package io.github.lejw0925.virtualscreen

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.lejw0925.virtualscreen.backend.*
import io.github.lejw0925.virtualscreen.core.*
import io.github.lejw0925.virtualscreen.mcp.McpToolRegistry
import io.github.lejw0925.virtualscreen.mcp.McpServerState
import io.github.lejw0925.virtualscreen.tunnel.TunnelMode
import androidx.annotation.RequiresApi
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
                val result=app.runtimeWork { when(intent.getStringExtra("check")) {
                    "configuration" -> {
                        val settings=app.settings.flow.value
                        JSONObject().put("ok",true).put("backend",settings.backend.name).put("theme",settings.theme)
                            .put("language",settings.language).put("guide_completed",app.settings.guideCompleted.value).put("mcp_wanted",app.mcpWanted)
                    }
                    "complete_guide" -> {
                        app.settings.completeGuide()
                        JSONObject().put("ok",true)
                    }
                    "settings" -> {
                        val page=intent.getStringExtra("page") ?: "HOME"
                        require(page in setOf("HOME","BACKEND","DISPLAY","MCP","TUNNEL","APPEARANCE","BACKGROUND","LOGS","ABOUT","LICENSES","GUIDE"))
                        context.startActivity(Intent(context,io.github.lejw0925.virtualscreen.ui.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        context.startActivity(Intent(context,io.github.lejw0925.virtualscreen.ui.SettingsActivity::class.java).putExtra("page",page).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        JSONObject().put("ok",true)
                    }
                    "authorize_shizuku" -> {
                        app.settings.update { it.copy(backend=BackendKind.SHIZUKU) }
                        app.sessions.selectBackend(BackendKind.SHIZUKU)
                        app.sessions.requestAuthorization()
                        JSONObject().put("ok",true)
                    }
                    "status" -> {
                        val state=app.sessions.state.value
                        val notification=app.getSystemService(android.app.NotificationManager::class.java).activeNotifications.firstOrNull { it.id==8732 }?.notification
                        JSONObject().put("ok",true).put("display",state.displayId).put("session",state.sessionId).put("state",state.status.name)
                            .put("preview",state.previewStatus.name).put("mcp_running",app.mcp.state().running)
                            .put("notification_icon_matches",notification?.smallIcon?.resId in setOf(R.drawable.ic_status_ready,
                                R.drawable.ic_status_active, R.drawable.ic_status_remote, R.drawable.ic_status_approval, R.drawable.ic_status_error))
                            .put("notification_icon",notification?.smallIcon?.resId?.let { app.resources.getResourceEntryName(it) })
                            .put("notification_actions",org.json.JSONArray(notification?.actions?.map { action ->
                                JSONObject().put("title",action.title).put("icon",action.getIcon()?.resId?.let { app.resources.getResourceEntryName(it) })
                            }.orEmpty()))
                            .put("notification_title",notification?.extras?.getString(android.app.Notification.EXTRA_TITLE))
                            .put("notification_text",notification?.extras?.getString(android.app.Notification.EXTRA_TEXT))
                            .apply {
                                if (android.os.Build.VERSION.SDK_INT >= 36 && RuntimeNotification.supportsLiveUpdates() && notification != null) {
                                    put("live_update_requested", notification.extras.getBoolean(android.app.Notification.EXTRA_REQUEST_PROMOTED_ONGOING))
                                    put("live_update_eligible", notification.hasPromotableCharacteristics())
                                    put("live_update_promoted", notification.flags and android.app.Notification.FLAG_PROMOTED_ONGOING != 0)
                                    put("live_update_allowed", app.getSystemService(android.app.NotificationManager::class.java).canPostPromotedNotifications())
                                    put("status_chip", notification.shortCriticalText)
                                }
                            }
                    }
                    "live_update" -> {
                        if (android.os.Build.VERSION.SDK_INT < 36 || !RuntimeNotification.supportsLiveUpdates())
                            error("LIVE_UPDATES_UNAVAILABLE")
                        else liveUpdateCheck(app,intent.getStringExtra("preview"))
                    }
                    "clear_notification_preview" -> {
                        app.getSystemService(android.app.NotificationManager::class.java).cancel(8877)
                        JSONObject().put("ok",true)
                    }
                    "notification_action" -> {
                        val title=when(intent.getStringExtra("action")) {
                            "takeover" -> R.string.notification_takeover
                            "close_display" -> R.string.notification_close_display
                            else -> error("UNKNOWN_NOTIFICATION_ACTION")
                        }
                        val notification=app.getSystemService(android.app.NotificationManager::class.java)
                            .activeNotifications.firstOrNull { it.id==8732 }?.notification ?: error("NOTIFICATION_MISSING")
                        val action=notification.actions.singleOrNull { it.title.toString()==app.getString(title) }
                            ?: error("NOTIFICATION_ACTION_MISSING")
                        action.actionIntent.send()
                        JSONObject().put("ok",true).put("action",action.title)
                    }
                    "notification_content" -> {
                        val id = intent.getStringExtra("approval_id") ?: error("APPROVAL_ID_REQUIRED")
                        val control = app.mcp.approvals.pending.value.firstOrNull { it.id == id }
                        val restart = app.sessions.restartApprovals.pending.value.firstOrNull { it.id == id }
                        val approval = when {
                            control != null -> NotificationApproval.control(context, control)
                            restart != null -> NotificationApproval.restart(context, restart)
                            else -> error("APPROVAL_NOT_PENDING")
                        }
                        RuntimeNotification.approvalContentIntent(context, approval).send()
                        JSONObject().put("ok", true).put("approval_id", id)
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
                    "tool_probe" -> {
                        app.sessions.selectBackend(BackendKind.ROOT)
                        app.sessions.requestAuthorization()
                        val report=io.github.lejw0925.virtualscreen.mcp.McpToolProbe(app.sessions).run()
                        app.sessions.applyToolProbe(report)
                        JSONObject().put("ok",report.passedTools==report.totalTools)
                            .put("passed",report.passedTools).put("total",report.totalTools)
                            .put("failures",report.failedTools.toString()).put("error",app.sessions.state.value.errorCode)
                    }
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
                    "close_preview" -> {
                        app.sessions.shutdown()
                        JSONObject().put("ok",true).put("closed",true)
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
                    "fixture_counter" -> {
                        val lease = app.sessions.leaseStatus()?.takeIf { it.owner == VirtualScreenSessionManager.PHONE_CLIENT }
                            ?: error("PHONE_VIEWER_REQUIRED")
                        val observed = app.sessions.observe(lease.owner, lease.token, captureFrame = false).getOrThrow()
                        val nodes = app.sessions.nodes(lease.owner, lease.token, observed.values.getValue("observation_id") as String).getOrThrow()
                        check(nodes.packageName == "io.github.lejw0925.virtualscreen.fixture") { "FIXTURE_REQUIRED" }
                        val counterNode = nodes.nodes.firstOrNull { node -> node.text?.startsWith("Tap counter:", ignoreCase = true) == true }
                            ?: error("FIXTURE_COUNTER_MISSING")
                        val counter = counterNode.text ?: error("FIXTURE_COUNTER_MISSING")
                        val bounds = counterNode.bounds ?: error("FIXTURE_COUNTER_BOUNDS_MISSING")
                        JSONObject().put("ok", true).put("counter", counter.substringAfter(':').trim().toInt())
                            .put("node_count", nodes.nodes.size).put("bounds", org.json.JSONArray(listOf(bounds.left,bounds.top,bounds.right,bounds.bottom)))
                            .put("viewer_size", org.json.JSONArray(app.settings.viewerSize().toList()))
                            .put("display_config", org.json.JSONArray(listOf(app.sessions.state.value.config.width,app.sessions.state.value.config.height)))
                    }
                    "nodes" -> {
                        val backend=BackendKind.valueOf(intent.getStringExtra("backend") ?: "ROOT")
                        app.sessions.shutdown(); app.sessions.selectBackend(backend)
                        if(backend==BackendKind.ROOT) app.sessions.requestAuthorization()
                        val lease=app.sessions.phoneTakeover()
                        try {
                            check(app.sessions.create(lease.owner,lease.token).ok) { "CREATE_FAILED" }
                            check(app.sessions.launch(lease.owner,lease.token,"io.github.lejw0925.virtualscreen.fixture/.FixtureActivity").ok) { "LAUNCH_FAILED" }
                            val nodes=withTimeout(10_000) {
                                while (true) {
                                    val observed=app.sessions.observe(lease.owner,lease.token).getOrThrow()
                                    val candidate=app.sessions.nodes(lease.owner,lease.token,observed.values["observation_id"] as String).getOrNull()
                                    if (candidate?.nodes?.any { it.text?.contains("Tap counter",ignoreCase=true)==true && it.clickable } == true)
                                        return@withTimeout candidate
                                    delay(150)
                                }
                                error("FIXTURE_WINDOW_NOT_READY")
                            }
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
                } }
                output.writeText(result.toString())
            } catch(e: Exception) { output.writeText(JSONObject().put("ok",false).put("code",e.code()).put("detail",e.message).toString()) }
            finally { pending.finish() }
        }
    }

    @RequiresApi(36)
    private fun liveUpdateCheck(app: VirtualScreenApplication, preview: String? = null): JSONObject {
        val screen=SessionState(status=SessionStatus.RUNNING,sessionId="device-check",displayId=9)
        val content=RuntimeNotification.describe(app,screen,McpServerState(running=true),null,io.github.lejw0925.virtualscreen.tunnel.TunnelState(),false)
        val notification=RuntimeNotification.build(app,content)
        val remote=screen.copy(lease=Lease("device-check-agent","not-a-real-lease",Long.MAX_VALUE,1))
        val purpose="检查通知图标与展开的操作目的，保持完整显示。".repeat(12)
        val working=io.github.lejw0925.virtualscreen.mcp.ToolActivity("device-check","device-check-agent","tap",purpose,"running")
        val request=ControlRequest("device-check","device-check-agent","Notification check",purpose,1,Long.MAX_VALUE)
        val restart=AppRestartRequest("device-check","device-check-agent","Notification check","Fixture",purpose,Long.MAX_VALUE)
        val cases=listOf(
            "local" to content,
            "remote" to RuntimeNotification.describe(app,remote,McpServerState(running=true),working.copy(phase="completed"),io.github.lejw0925.virtualscreen.tunnel.TunnelState(),false),
            "working" to RuntimeNotification.describe(app,remote,McpServerState(running=true),working,io.github.lejw0925.virtualscreen.tunnel.TunnelState(),false),
            "control_approval" to RuntimeNotification.describe(app,screen,McpServerState(running=true),null,io.github.lejw0925.virtualscreen.tunnel.TunnelState(),false,listOf(request)),
            "restart_approval" to RuntimeNotification.describe(app,remote,McpServerState(running=true),working,io.github.lejw0925.virtualscreen.tunnel.TunnelState(),false,restartRequests=listOf(restart)),
        )
        val variants=cases.map { (name,description) ->
            val built=RuntimeNotification.build(app,description)
            if(name==preview) app.getSystemService(android.app.NotificationManager::class.java).notify(8877,built)
            JSONObject().put("state",name).put("chip",built.shortCriticalText).put("eligible",built.hasPromotableCharacteristics())
                .put("icon",app.resources.getResourceEntryName(built.smallIcon.resId))
                .put("purpose_preserved",name=="local" || built.extras.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT).toString().contains(purpose))
                .put("actions",org.json.JSONArray(built.actions.map { action ->
                    JSONObject().put("title",action.title).put("icon",app.resources.getResourceEntryName(action.getIcon().resId))
                        .apply { if(android.os.Build.VERSION.SDK_INT >= 37) put("icon_and_text",action.styleHint==android.app.Notification.Action.STYLE_ICON_AND_TEXT) }
                }))
        }
        return JSONObject().put("ok",true).put("requested",notification.extras.getBoolean("android.requestPromotedOngoing"))
            .put("eligible",notification.hasPromotableCharacteristics()).put("chip",content.chip)
            .put("category",notification.category).put("ongoing",notification.flags and android.app.Notification.FLAG_ONGOING_EVENT != 0)
            .put("variants",org.json.JSONArray(variants))
    }
}
