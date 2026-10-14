package io.github.lejw0925.virtualscreen

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.net.Uri
import io.github.lejw0925.virtualscreen.core.AppRestartRequest
import io.github.lejw0925.virtualscreen.core.AppRestartApprovalManager
import io.github.lejw0925.virtualscreen.core.SessionStatus
import io.github.lejw0925.virtualscreen.core.PreviewStatus
import io.github.lejw0925.virtualscreen.core.VirtualScreenSessionManager
import io.github.lejw0925.virtualscreen.ui.MainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.atomic.AtomicInteger

class VirtualScreenForegroundService : Service() {
    companion object {
        const val ACTION_STOP = "io.github.lejw0925.virtualscreen.STOP"
        const val ACTION_TAKEOVER = "io.github.lejw0925.virtualscreen.TAKEOVER"
        private const val ACTION_ALLOW_ONCE = "io.github.lejw0925.virtualscreen.ALLOW_ONCE"
        private const val ACTION_ALLOW_ALWAYS = "io.github.lejw0925.virtualscreen.ALLOW_ALWAYS"
        private const val ACTION_DENY = "io.github.lejw0925.virtualscreen.DENY_CONTROL"
        private const val ACTION_ALLOW_RESTART = "io.github.lejw0925.virtualscreen.ALLOW_APP_RESTART"
        private const val ACTION_DENY_RESTART = "io.github.lejw0925.virtualscreen.DENY_APP_RESTART"
        private const val CHANNEL = "virtual-screen-control"
        private const val APPROVAL_CHANNEL = "virtual-screen-approval"
        private const val RESTART_CHANNEL = "virtual-screen-restart-approval"
        private const val ID = 8732
    }
    private data class Indicator(val title: String, val detail: String, val active: Boolean)
    private val app get() = application as VirtualScreenApplication
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var previewedSession: String? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val approvalNotifications = mutableMapOf<String,Int>()
    private val restartNotifications = mutableMapOf<String,Int>()
    private val nextNotificationId = AtomicInteger(ID + 1)

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW))
        manager.createNotificationChannel(NotificationChannel(APPROVAL_CHANNEL, getString(R.string.control_approval_channel), NotificationManager.IMPORTANCE_HIGH))
        manager.createNotificationChannel(NotificationChannel(RESTART_CHANNEL, getString(R.string.app_restart_channel), NotificationManager.IMPORTANCE_HIGH))
        startForeground(ID, notification(Indicator(getString(R.string.notification_ready), getString(R.string.notification_running), false)))
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"VirtualScreen:MCP").apply { setReferenceCounted(false) }
        app.restoreMcp()
        scope.launch {
            app.mcp.approvals.pending.collect { requests ->
                val ids = requests.map { it.id }.toSet()
                approvalNotifications.keys.toList().filter { it !in ids }.forEach { id -> manager.cancel(approvalNotifications.remove(id)!!) }
                requests.forEach { request ->
                    if(request.id !in approvalNotifications) {
                        val id = nextNotificationId.getAndIncrement()
                        approvalNotifications[request.id] = id
                        manager.notify(id,approvalNotification(request))
                    }
                }
            }
        }
        scope.launch {
            app.sessions.restartApprovals.pending.collect { requests ->
                val ids = requests.map { it.id }.toSet()
                restartNotifications.keys.toList().filter { it !in ids }.forEach { id -> manager.cancel(restartNotifications.remove(id)!!) }
                requests.forEach { request ->
                    if (request.id !in restartNotifications) {
                        val channel = manager.getNotificationChannel(RESTART_CHANNEL)
                        if (!manager.areNotificationsEnabled() || channel?.importance == NotificationManager.IMPORTANCE_NONE) {
                            app.sessions.restartApprovals.notificationUnavailable(request.id)
                        } else {
                            val id = nextNotificationId.getAndIncrement()
                            restartNotifications[request.id] = id
                            runCatching { manager.notify(id, restartNotification(request)) }.onFailure {
                                app.sessions.restartApprovals.notificationUnavailable(request.id)
                            }
                        }
                    }
                }
            }
        }
        scope.launch {
            combine(app.sessions.state, app.mcp.flow, app.mcp.activity) { screen, server, activity ->
                val remote = screen.lease?.owner?.let { it != VirtualScreenSessionManager.PHONE_CLIENT } == true
                val working = activity?.phase == "running" && activity.owner == screen.lease?.owner
                val title = getString(when {
                    screen.status == SessionStatus.ERROR -> R.string.notification_error
                    working -> R.string.notification_working
                    remote && screen.displayId != null -> R.string.notification_agent
                    screen.displayId != null -> R.string.notification_local
                    server.running -> R.string.notification_ready
                    else -> R.string.notification_running
                })
                Indicator(title, if(working) activity.purpose else getString(when {
                    screen.previewStatus==PreviewStatus.UNAVAILABLE -> R.string.notification_preview_unavailable
                    screen.displayId != null -> R.string.notification_isolated
                    else -> R.string.notification_waiting
                }), working)
            }.distinctUntilChanged().collect { manager.notify(ID, notification(it)) }
        }
        scope.launch {
            app.sessions.state.map { state -> state.sessionId?.takeIf {
                state.status == SessionStatus.RUNNING && state.lease?.owner != null && state.lease.owner != VirtualScreenSessionManager.PHONE_CLIENT
            } }.distinctUntilChanged().collect { session ->
                if(session != null && session != previewedSession) {
                    previewedSession = session
                    // Fixed viewer starts directly into PiP without a full-screen task switch.
                    runCatching { app.sessions.showRemotePreview() }
                }
            }
        }
        scope.launch {
            while(currentCoroutineContext().isActive) {
                if(app.mcpWanted || app.mcp.state().running) {
                    if(wakeLock?.isHeld == false) wakeLock?.acquire(600_000)
                } else if(wakeLock?.isHeld == true) wakeLock?.release()
                delay(3000)
                app.mcp.approvals.refresh()
                runCatching { app.sessions.healthCheck() }
                if(app.sessions.state.value.displayId == null && !app.mcpWanted && !app.mcpChanging.value && !app.mcp.state().running &&
                    app.sessions.state.value.status != SessionStatus.STARTING && app.tunnels.flow.value.phase !in setOf("starting","running","logging_in","login")) stopSelf()
            }
        }
    }

    private fun notification(indicator: Indicator): Notification {
        fun action(request: Int, command: String) = PendingIntent.getService(this, request,
            Intent(this, VirtualScreenForegroundService::class.java).setAction(command), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(if(indicator.active) R.drawable.ic_status_active else R.drawable.ic_status_ready)
            .setContentTitle(getString(R.string.app_name) + " · " + indicator.title)
            .setContentText(indicator.detail)
            .setStyle(Notification.BigTextStyle().bigText(indicator.detail))
            .setContentIntent(open).setOnlyAlertOnce(true).setShowWhen(false).setOngoing(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.notification_takeover), action(2, ACTION_TAKEOVER)).build())
            .addAction(Notification.Action.Builder(null, getString(R.string.notification_stop), action(1, ACTION_STOP)).build())
            .build()
    }
    private fun approvalNotification(request: io.github.lejw0925.virtualscreen.core.ControlRequest): Notification {
        fun action(command: String) = PendingIntent.getService(this,0,
            Intent(this,VirtualScreenForegroundService::class.java).setAction(command)
                .setData(Uri.parse("virtualscreen://control/${request.id}/$command")).putExtra("approval_id",request.id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val detail = getString(R.string.control_approval_detail,request.purpose)
        return Notification.Builder(this,APPROVAL_CHANNEL)
            .setSmallIcon(R.drawable.ic_status_active)
            .setContentTitle(getString(R.string.control_approval_title,request.clientName))
            .setContentText(detail).setStyle(Notification.BigTextStyle().bigText(detail))
            .setVisibility(Notification.VISIBILITY_PRIVATE).setAutoCancel(true).setTimeoutAfter(120_000)
            .setDeleteIntent(action(ACTION_DENY))
            .addAction(Notification.Action.Builder(null,getString(R.string.control_allow_once),action(ACTION_ALLOW_ONCE)).build())
            .addAction(Notification.Action.Builder(null,getString(R.string.control_allow_always),action(ACTION_ALLOW_ALWAYS)).build())
            .addAction(Notification.Action.Builder(null,getString(R.string.control_deny),action(ACTION_DENY)).build())
            .build()
    }
    private fun restartNotification(request: AppRestartRequest): Notification {
        fun action(command: String) = PendingIntent.getService(this, 0,
            Intent(this, VirtualScreenForegroundService::class.java).setAction(command)
                .setData(Uri.parse("virtualscreen://restart/${request.id}/$command")).putExtra("restart_id", request.id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val detail = getString(R.string.app_restart_detail, request.purpose)
        return Notification.Builder(this, RESTART_CHANNEL)
            .setSmallIcon(R.drawable.ic_status_active)
            .setContentTitle(getString(R.string.app_restart_title, request.clientName, request.appName))
            .setContentText(detail).setStyle(Notification.BigTextStyle().bigText(detail))
            .setCategory(Notification.CATEGORY_EVENT).setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE).setAutoCancel(true)
            .setTimeoutAfter((request.expiresAtMillis - System.nanoTime() / 1_000_000).coerceIn(1, AppRestartApprovalManager.TIMEOUT_MILLIS))
            .setDeleteIntent(action(ACTION_DENY_RESTART))
            .addAction(Notification.Action.Builder(null, getString(R.string.app_restart_allow), action(ACTION_ALLOW_RESTART)).build())
            .addAction(Notification.Action.Builder(null, getString(R.string.control_deny), action(ACTION_DENY_RESTART)).build())
            .build()
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when(intent?.action) {
            ACTION_STOP -> { app.sessions.phoneTakeover(); app.stopRuntime(); stopSelf() }
            ACTION_TAKEOVER -> app.sessions.phoneTakeover()
            ACTION_ALLOW_ONCE -> intent.getStringExtra("approval_id")?.let { app.mcp.approvals.approve(it) }
            ACTION_ALLOW_ALWAYS -> intent.getStringExtra("approval_id")?.let { app.mcp.approvals.approve(it,always=true) }
            ACTION_DENY -> intent.getStringExtra("approval_id")?.let { app.mcp.approvals.deny(it) }
            ACTION_ALLOW_RESTART -> intent.getStringExtra("restart_id")?.let { app.sessions.restartApprovals.approve(it) }
            ACTION_DENY_RESTART -> intent.getStringExtra("restart_id")?.let { app.sessions.restartApprovals.deny(it) }
        }
        return START_STICKY
    }
    override fun onDestroy() {
        app.sessions.cancelActive(); scope.cancel()
        if(wakeLock?.isHeld == true) wakeLock?.release()
        val manager = getSystemService(NotificationManager::class.java)
        approvalNotifications.values.forEach { manager.cancel(it) }
        restartNotifications.values.forEach { manager.cancel(it) }
        app.stopRuntime(preserveMcp=true); super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
