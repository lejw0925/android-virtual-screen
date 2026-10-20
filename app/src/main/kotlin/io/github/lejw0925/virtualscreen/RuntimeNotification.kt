package io.github.lejw0925.virtualscreen

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.graphics.drawable.Icon
import android.annotation.SuppressLint
import androidx.annotation.DrawableRes
import androidx.core.net.toUri
import io.github.lejw0925.virtualscreen.core.AppRestartRequest
import io.github.lejw0925.virtualscreen.core.ControlRequest
import io.github.lejw0925.virtualscreen.core.PreviewStatus
import io.github.lejw0925.virtualscreen.core.SessionState
import io.github.lejw0925.virtualscreen.core.SessionStatus
import io.github.lejw0925.virtualscreen.core.VirtualScreenSessionManager
import io.github.lejw0925.virtualscreen.ui.MainActivity
import io.github.lejw0925.virtualscreen.mcp.McpServerState
import io.github.lejw0925.virtualscreen.mcp.ToolActivity
import io.github.lejw0925.virtualscreen.tunnel.TunnelState

internal data class RuntimeNotificationContent(
    val title: String,
    val detail: String,
    val chip: String,
    val working: Boolean = false,
    val liveSession: String? = null,
    @DrawableRes val icon: Int = R.drawable.ic_status_ready,
    val displaySession: String? = null,
    val approval: NotificationApproval? = null,
)

internal enum class NotificationApprovalKind { CONTROL, APP_RESTART }

internal data class NotificationApproval(
    val id: String,
    val kind: NotificationApprovalKind,
    val title: String,
    val detail: String,
    val expiresAtMillis: Long,
) {
    companion object {
        fun control(context: Context, request: ControlRequest) = NotificationApproval(
            request.id, NotificationApprovalKind.CONTROL,
            context.getString(R.string.control_approval_title, request.clientName),
            context.getString(R.string.control_approval_detail, request.purpose), request.expiresAtMillis)

        fun restart(context: Context, request: AppRestartRequest) = NotificationApproval(
            request.id, NotificationApprovalKind.APP_RESTART,
            context.getString(R.string.app_restart_title, request.clientName, request.appName),
            context.getString(R.string.app_restart_detail, request.purpose), request.expiresAtMillis)
    }
}

internal object RuntimeNotification {
    const val CHANNEL = "virtual-screen-control"
    const val ACTION_UNPIN = "io.github.lejw0925.virtualscreen.UNPIN_LIVE_UPDATE"
    const val EXTRA_SESSION = "notification_session"
    const val EXTRA_CONTROL_APPROVAL = "control_approval"
    const val EXTRA_RESTART_APPROVAL = "restart_approval"

    fun approvalContentIntent(context: Context, approval: NotificationApproval): PendingIntent {
        val control = approval.kind == NotificationApprovalKind.CONTROL
        return PendingIntent.getActivity(context, 0,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                .setData(("virtualscreen://approval/${if (control) "control" else "restart"}/${approval.id}").toUri())
                .putExtra(if (control) EXTRA_CONTROL_APPROVAL else EXTRA_RESTART_APPROVAL, approval.id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    fun supportsLiveUpdates(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA &&
        Build.VERSION.SDK_INT_FULL >= Build.VERSION_CODES_FULL.BAKLAVA_1

    fun describe(context: Context, screen: SessionState, server: McpServerState, activity: ToolActivity?,
                 tunnel: TunnelState, changing: Boolean, controlRequests: List<ControlRequest> = emptyList(),
                 restartRequests: List<AppRestartRequest> = emptyList()): RuntimeNotificationContent {
        val approval = restartRequests.firstOrNull()?.let { NotificationApproval.restart(context, it) }
            ?: controlRequests.firstOrNull()?.let { NotificationApproval.control(context, it) }
        val remote = screen.lease.let { it != null && it.owner != VirtualScreenSessionManager.PHONE_CLIENT }
        val currentActivity = activity?.takeIf { remote && it.owner == screen.lease?.owner }
        val working = currentActivity?.phase == "running" && approval == null
        val error = screen.status == SessionStatus.ERROR || server.lastError != null || tunnel.errorCode != null
        val title = when {
            error -> R.string.notification_error
            working -> R.string.notification_working
            remote && screen.displayId != null -> R.string.notification_agent
            screen.displayId != null -> R.string.notification_local
            screen.status == SessionStatus.STARTING -> R.string.notification_display_starting
            changing -> R.string.notification_server_starting
            server.running -> R.string.notification_ready
            tunnel.running -> R.string.notification_tunnel_ready
            else -> R.string.notification_running
        }
        val chip = when {
            approval != null -> R.string.notification_chip_approval
            error -> R.string.notification_chip_error
            working -> R.string.notification_chip_active
            remote && screen.displayId != null -> R.string.notification_chip_remote
            screen.displayId != null -> R.string.notification_chip_local
            else -> R.string.notification_chip_ready
        }
        val detail = when {
            approval != null -> approval.detail
            error -> context.getString(R.string.notification_failure)
            currentActivity?.purpose?.isNotBlank() == true && screen.displayId != null -> currentActivity.purpose
            screen.previewStatus == PreviewStatus.UNAVAILABLE -> context.getString(R.string.notification_preview_unavailable)
            screen.displayId != null -> context.getString(R.string.notification_isolated)
            changing || screen.status == SessionStatus.STARTING -> context.getString(R.string.notification_starting_detail)
            server.running && server.sessionCount > 0 -> context.getString(R.string.notification_clients, server.sessionCount)
            tunnel.running -> context.getString(R.string.notification_tunnel_waiting)
            else -> context.getString(R.string.notification_waiting)
        }
        val icon = when {
            approval != null -> R.drawable.ic_status_approval
            error -> R.drawable.ic_status_error
            working -> R.drawable.ic_status_active
            remote && screen.displayId != null -> R.drawable.ic_status_remote
            else -> R.drawable.ic_status_ready
        }
        return RuntimeNotificationContent(approval?.title ?: context.getString(title), detail, context.getString(chip), working,
            screen.sessionId.takeIf { screen.displayId != null && screen.status == SessionStatus.RUNNING },
            icon, screen.sessionId.takeIf { screen.displayId != null }, approval)
    }

    @SuppressLint("NewApi")
    fun build(context: Context, content: RuntimeNotificationContent, dismissedSession: String? = null): Notification {
        fun action(request: Int, command: String) = PendingIntent.getService(context, request,
            Intent(context, VirtualScreenForegroundService::class.java).setAction(command)
                .setData(("virtualscreen://runtime/" + (content.displaySession ?: "idle") + "/" + command).toUri())
                .putExtra(EXTRA_SESSION, content.displaySession),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = content.approval?.let { approvalContentIntent(context, it) } ?: PendingIntent.getActivity(context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val builder = Notification.Builder(context, CHANNEL)
            .setSmallIcon(content.icon)
            .setContentTitle(context.getString(R.string.app_name) + " · " + content.title)
            .setContentText(content.detail).setStyle(Notification.BigTextStyle().bigText(content.detail))
            .setContentIntent(open).setOnlyAlertOnce(true).setShowWhen(false).setOngoing(true)
            .setCategory(Notification.CATEGORY_PROGRESS).setVisibility(Notification.VISIBILITY_PRIVATE)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        when {
            content.approval != null -> approvalActions(context, content.approval).forEach(builder::addAction)
            content.displaySession != null -> {
                builder.addAction(notificationAction(context, R.drawable.ic_notification_takeover,
                    R.string.notification_takeover, action(2, VirtualScreenForegroundService.ACTION_TAKEOVER)))
                builder.addAction(notificationAction(context, R.drawable.ic_notification_close,
                    R.string.notification_close_display, action(1, VirtualScreenForegroundService.ACTION_CLOSE_DISPLAY)))
            }
            else -> builder.addAction(notificationAction(context, R.drawable.ic_notification_close,
                R.string.notification_stop, action(1, VirtualScreenForegroundService.ACTION_STOP)))
        }
        if (supportsLiveUpdates()) {
            val live = content.liveSession != null && content.liveSession != dismissedSession
            builder.setRequestPromotedOngoing(live)
            if (live) {
                builder.setShortCriticalText(content.chip)
                val unpin = PendingIntent.getService(context, 3,
                    Intent(context, VirtualScreenForegroundService::class.java).setAction(ACTION_UNPIN)
                        .setData(("virtualscreen://live/" + content.liveSession).toUri()).putExtra(EXTRA_SESSION, content.liveSession),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                builder.setDeleteIntent(unpin)
            }
        }
        return builder.build()
    }

    fun approvalActions(context: Context, approval: NotificationApproval): List<Notification.Action> {
        fun action(command: String): PendingIntent {
            val kind = if (approval.kind == NotificationApprovalKind.CONTROL) "control" else "restart"
            val key = if (approval.kind == NotificationApprovalKind.CONTROL) "approval_id" else "restart_id"
            return PendingIntent.getService(context, 0,
                Intent(context, VirtualScreenForegroundService::class.java).setAction(command)
                    .setData(("virtualscreen://$kind/${approval.id}/$command").toUri()).putExtra(key, approval.id),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        return if (approval.kind == NotificationApprovalKind.CONTROL) listOf(
            notificationAction(context, R.drawable.ic_notification_allow, R.string.control_allow_once, action(VirtualScreenForegroundService.ACTION_ALLOW_ONCE)),
            notificationAction(context, R.drawable.ic_notification_always, R.string.control_allow_always, action(VirtualScreenForegroundService.ACTION_ALLOW_ALWAYS)),
            notificationAction(context, R.drawable.ic_notification_close, R.string.control_deny, action(VirtualScreenForegroundService.ACTION_DENY)),
        ) else listOf(
            notificationAction(context, R.drawable.ic_notification_allow, R.string.app_restart_allow, action(VirtualScreenForegroundService.ACTION_ALLOW_RESTART)),
            notificationAction(context, R.drawable.ic_notification_close, R.string.control_deny, action(VirtualScreenForegroundService.ACTION_DENY_RESTART)),
        )
    }

    private fun notificationAction(context: Context, @DrawableRes icon: Int, title: Int, intent: PendingIntent): Notification.Action {
        val builder = Notification.Action.Builder(Icon.createWithResource(context, icon), context.getString(title), intent)
        if (Build.VERSION.SDK_INT >= 37) {
            builder.setStyleHint(Notification.Action.STYLE_ICON_AND_TEXT)
            builder.setEmphasisHint(if (title in setOf(R.string.notification_takeover, R.string.control_allow_once, R.string.app_restart_allow))
                Notification.Action.EMPHASIS_PRIMARY else Notification.Action.EMPHASIS_SECONDARY)
        }
        return builder.build()
    }
}
