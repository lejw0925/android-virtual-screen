package io.github.lejw0925.virtualscreen

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import io.github.lejw0925.virtualscreen.core.SessionStatus
import io.github.lejw0925.virtualscreen.core.PreviewStatus
import io.github.lejw0925.virtualscreen.core.VirtualScreenSessionManager
import io.github.lejw0925.virtualscreen.ui.MainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class VirtualScreenForegroundService : Service() {
    companion object {
        const val ACTION_STOP = "io.github.lejw0925.virtualscreen.STOP"
        const val ACTION_TAKEOVER = "io.github.lejw0925.virtualscreen.TAKEOVER"
        private const val CHANNEL = "virtual-screen-control"
        private const val ID = 8732
    }
    private data class Indicator(val title: String, val detail: String, val active: Boolean)
    private val app get() = application as VirtualScreenApplication
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var previewedSession: String? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW))
        startForeground(ID, notification(Indicator(getString(R.string.notification_ready), getString(R.string.notification_running), false)))
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
                delay(3000)
                runCatching { app.sessions.healthCheck() }
                if(app.sessions.state.value.displayId == null && !app.mcp.state().running && app.sessions.state.value.status != SessionStatus.STARTING) stopSelf()
            }
        }
    }

    private fun notification(indicator: Indicator): Notification {
        fun action(request: Int, command: String) = PendingIntent.getService(this, request,
            Intent(this, VirtualScreenForegroundService::class.java).setAction(command), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
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
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when(intent?.action) {
            ACTION_STOP -> { app.sessions.phoneTakeover(); stopSelf() }
            ACTION_TAKEOVER -> app.sessions.phoneTakeover()
        }
        return START_NOT_STICKY
    }
    override fun onDestroy() { app.sessions.phoneTakeover(); scope.cancel(); app.stopRuntime(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
}
