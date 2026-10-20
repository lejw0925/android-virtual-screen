package io.github.lejw0925.virtualscreen

import android.app.Application
import android.app.Notification
import android.os.Build
import io.github.lejw0925.virtualscreen.core.AppRestartRequest
import io.github.lejw0925.virtualscreen.core.ControlRequest
import io.github.lejw0925.virtualscreen.core.Lease
import io.github.lejw0925.virtualscreen.core.PreviewStatus
import io.github.lejw0925.virtualscreen.core.SessionState
import io.github.lejw0925.virtualscreen.core.SessionStatus
import io.github.lejw0925.virtualscreen.mcp.McpServerState
import io.github.lejw0925.virtualscreen.mcp.ToolActivity
import io.github.lejw0925.virtualscreen.tunnel.TunnelState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RuntimeNotificationTest {
    @Test fun notificationTapOpensTheExactLiveApprovalAndKeepsRequestIntentsSeparate() {
        val request = ControlRequest("control-tap-1", "agent", "Agent", "Read the page", 1, Long.MAX_VALUE)
        fun notification(id: String) = RuntimeNotification.build(context, RuntimeNotification.describe(context,
            SessionState(), McpServerState(running = true), null, TunnelState(), changing = false,
            controlRequests = listOf(request.copy(id = id))))
        val first = notification(request.id)
        val second = notification("control-tap-2")
        val intent = org.robolectric.Shadows.shadowOf(first.contentIntent).savedIntent

        assertEquals(request.id, intent.getStringExtra(RuntimeNotification.EXTRA_CONTROL_APPROVAL))
        assertEquals(io.github.lejw0925.virtualscreen.ui.MainActivity::class.java.name, intent.component!!.className)
        assertEquals("virtualscreen://approval/control/${request.id}", intent.dataString)
        val launchFlags = android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or
            android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP or android.content.Intent.FLAG_ACTIVITY_NEW_TASK
        assertEquals(launchFlags, intent.flags and launchFlags)
        assertFalse(first.contentIntent == second.contentIntent)
        assertEquals(request.id, io.github.lejw0925.virtualscreen.ui.ApprovalTarget.from(intent)!!.id)
    }

    private val context get() = RuntimeEnvironment.getApplication()

    @Test fun activeVirtualScreenProducesAnOngoingLiveUpdateSessionAndShortStatus() {
        val content = RuntimeNotification.describe(context,
            SessionState(status = SessionStatus.RUNNING, sessionId = "screen-1", displayId = 8),
            McpServerState(running = true), null, TunnelState(), changing = false)

        assertEquals("screen-1", content.liveSession)
        assertEquals(context.getString(R.string.notification_chip_local), content.chip)
        val notification = RuntimeNotification.build(context, content)
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertTrue(notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString().contains(content.title))
        assertTrue(notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT) != null)
        assertEquals(R.drawable.ic_status_ready, notification.smallIcon.resId)
        if (Build.VERSION.SDK_INT >= 36 && RuntimeNotification.supportsLiveUpdates()) assertEquals(content.chip, notification.shortCriticalText)
    }

    @Test fun remoteAgentActivityChangesTheStatusChipToActive() {
        val activity = ToolActivity("activity", "agent", "tap", "Open the requested screen", "running")
        val content = RuntimeNotification.describe(context,
            SessionState(status = SessionStatus.RUNNING, sessionId = "screen-1", displayId = 8,
                lease = Lease("agent", "private-token", Long.MAX_VALUE, 1)),
            McpServerState(running = true), activity, TunnelState(), changing = false)

        assertTrue(content.working)
        assertEquals("Open the requested screen", content.detail)
        assertEquals(context.getString(R.string.notification_chip_active), content.chip)
        if (RuntimeNotification.supportsLiveUpdates()) {
            val dismissed = RuntimeNotification.build(context, content, dismissedSession = "screen-1")
            assertNull(dismissed.shortCriticalText)
            assertFalse(dismissed.extras.getBoolean(Notification.EXTRA_REQUEST_PROMOTED_ONGOING))
        }
    }

    @Test fun idleMcpDoesNotRequestLiveUpdatePromotion() {
        val content = RuntimeNotification.describe(context, SessionState(), McpServerState(running = true),
            null, TunnelState(), changing = false)

        assertNull(content.liveSession)
        assertEquals(context.getString(R.string.notification_ready), content.title)
    }

    @Test fun failedVirtualScreenUsesAnActionableStandardOngoingNotification() {
        val content = RuntimeNotification.describe(context, SessionState(status = SessionStatus.ERROR),
            McpServerState(running = false, lastError = "ROOT_PERMISSION_REQUIRED"), null, TunnelState(), changing = false)
        val notification = RuntimeNotification.build(context, content)

        assertNull(content.liveSession)
        assertEquals(context.getString(R.string.notification_failure), content.detail)
        if (RuntimeNotification.supportsLiveUpdates()) assertFalse(notification.extras.getBoolean(Notification.EXTRA_REQUEST_PROMOTED_ONGOING))
        assertTrue(notification.actions.any { it.title == context.getString(R.string.notification_stop) })
    }

    @Test fun unavailablePreviewKeepsItsExistingRecoveryDetail() {
        val content = RuntimeNotification.describe(context,
            SessionState(status = SessionStatus.RUNNING, sessionId = "screen-2", displayId = 8, previewStatus = PreviewStatus.UNAVAILABLE),
            McpServerState(running = true), null, TunnelState(), changing = false)

        assertEquals(context.getString(R.string.notification_preview_unavailable), content.detail)
        assertEquals("screen-2", content.liveSession)
    }

    @Test fun completedRemoteOperationKeepsItsFullPurposeAndComputerIcon() {
        val purpose = "Read the requested page and compare every result. ".repeat(20)
        val content = RuntimeNotification.describe(context,
            SessionState(status = SessionStatus.RUNNING, sessionId = "screen-1", displayId = 8,
                lease = Lease("agent", "private-token", Long.MAX_VALUE, 1)),
            McpServerState(running = true), ToolActivity("activity", "agent", "observe", purpose, "completed"),
            TunnelState(), changing = false)
        val notification = RuntimeNotification.build(context, content)

        assertFalse(content.working)
        assertEquals(R.drawable.ic_status_remote, notification.smallIcon.resId)
        assertEquals(purpose, notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString())
        assertEquals(listOf(context.getString(R.string.notification_takeover), context.getString(R.string.notification_close_display)),
            notification.actions.map { it.title.toString() })
        assertEquals(listOf(R.drawable.ic_notification_takeover, R.drawable.ic_notification_close),
            notification.actions.map { it.getIcon().resId })
        assertNull(notification.contentView)
        assertNull(notification.bigContentView)
    }

    @Test fun activityFromAnotherControllerDoesNotExposeAnUnrelatedPurpose() {
        val content = RuntimeNotification.describe(context,
            SessionState(status = SessionStatus.RUNNING, sessionId = "screen-1", displayId = 8,
                lease = Lease("new-agent", "private-token", Long.MAX_VALUE, 2)),
            McpServerState(running = true), ToolActivity("activity", "old-agent", "tap", "Old purpose", "running"),
            TunnelState(), changing = false)
        assertFalse(content.working)
        assertEquals(context.getString(R.string.notification_isolated), content.detail)
        assertEquals(R.drawable.ic_status_remote, content.icon)
    }

    @Test fun controlApprovalReplacesNormalActionsAndIncludesTheRequestPurpose() {
        val request = ControlRequest("control-1", "agent", "Test agent", "Read the requested page", 1, Long.MAX_VALUE)
        val content = RuntimeNotification.describe(context,
            SessionState(status = SessionStatus.RUNNING, sessionId = "screen-1", displayId = 8),
            McpServerState(running = true), null, TunnelState(), changing = false, controlRequests = listOf(request))
        val notification = RuntimeNotification.build(context, content)

        assertEquals(R.drawable.ic_status_approval, notification.smallIcon.resId)
        assertEquals(context.getString(R.string.notification_chip_approval), content.chip)
        assertTrue(content.title.contains(request.clientName))
        assertTrue(notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString().contains(request.purpose))
        assertEquals(NotificationApprovalKind.CONTROL, content.approval!!.kind)
        assertEquals(listOf(R.drawable.ic_notification_allow, R.drawable.ic_notification_always, R.drawable.ic_notification_close),
            notification.actions.map { it.getIcon().resId })
        assertEquals(listOf(R.string.control_allow_once, R.string.control_allow_always, R.string.control_deny).map(context::getString),
            notification.actions.map { it.title.toString() })
    }

    @Test fun restartApprovalTakesPriorityAndCannotReuseAnotherRequestsPendingIntent() {
        val request = AppRestartRequest("restart-1", "agent", "Test agent", "Fixture", "Open the fixture", Long.MAX_VALUE)
        val control = ControlRequest("control-1", "other", "Other agent", "Take over", 1, Long.MAX_VALUE)
        fun content(restart: AppRestartRequest) = RuntimeNotification.describe(context,
            SessionState(status = SessionStatus.RUNNING, sessionId = "screen-1", displayId = 8,
                lease = Lease("agent", "private-token", Long.MAX_VALUE, 1)),
            McpServerState(running = true), ToolActivity("activity", "agent", "launch_app", "Open the fixture", "running"),
            TunnelState(), changing = false, controlRequests = listOf(control), restartRequests = listOf(restart))
        val first = content(request)
        val notification = RuntimeNotification.build(context, first)
        val next = RuntimeNotification.build(context, content(request.copy(id = "restart-2")))

        assertFalse(first.working)
        assertEquals(NotificationApprovalKind.APP_RESTART, first.approval!!.kind)
        assertTrue(first.title.contains(request.appName))
        assertTrue(first.detail.contains(request.purpose))
        assertEquals(listOf(R.string.app_restart_allow, R.string.control_deny).map(context::getString),
            notification.actions.map { it.title.toString() })
        assertFalse(notification.actions.first().actionIntent == next.actions.first().actionIntent)
        assertEquals(R.drawable.ic_notification_allow, notification.actions.first().getIcon().resId)
    }

    @Test fun workingOperationUsesTheTouchIconAndNeverUsesTheStopAllAction() {
        val content = RuntimeNotification.describe(context,
            SessionState(status = SessionStatus.RUNNING, sessionId = "screen-1", displayId = 8,
                lease = Lease("agent", "private-token", Long.MAX_VALUE, 1)),
            McpServerState(running = true), ToolActivity("activity", "agent", "tap", "Tap the requested button", "running"),
            TunnelState(), changing = false)
        val notification = RuntimeNotification.build(context, content)
        assertEquals(R.drawable.ic_status_active, notification.smallIcon.resId)
        assertFalse(notification.actions.any { it.title == context.getString(R.string.notification_stop) })
    }
}
