package io.github.lejw0925.virtualscreen.ui

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lejw0925.virtualscreen.NotificationApproval
import io.github.lejw0925.virtualscreen.NotificationApprovalKind
import io.github.lejw0925.virtualscreen.R
import io.github.lejw0925.virtualscreen.RuntimeNotification
import io.github.lejw0925.virtualscreen.VirtualScreenApplication
import kotlinx.coroutines.delay

internal data class ApprovalTarget(val id: String, val kind: NotificationApprovalKind) {
    companion object {
        fun from(intent: Intent): ApprovalTarget? =
            intent.getStringExtra(RuntimeNotification.EXTRA_CONTROL_APPROVAL)?.takeIf(String::isNotBlank)
                ?.let { ApprovalTarget(it, NotificationApprovalKind.CONTROL) }
                ?: intent.getStringExtra(RuntimeNotification.EXTRA_RESTART_APPROVAL)?.takeIf(String::isNotBlank)
                    ?.let { ApprovalTarget(it, NotificationApprovalKind.APP_RESTART) }
    }
}

/** Resolve the live request by ID; notification extras never contain an authorization decision. */
@Composable
internal fun ApprovalDialog(app: VirtualScreenApplication, target: ApprovalTarget, onClose: () -> Unit) {
    val l = LocalLanguage.current
    val context = LocalContext.current
    val controls by app.mcp.approvals.pending.collectAsStateWithLifecycle()
    val restarts by app.sessions.restartApprovals.pending.collectAsStateWithLifecycle()
    var now by remember { mutableLongStateOf(System.nanoTime() / 1_000_000) }
    var error by remember(target) { mutableStateOf<String?>(null) }
    LaunchedEffect(target) {
        while (true) {
            app.mcp.approvals.refresh()
            now = System.nanoTime() / 1_000_000
            delay(1_000)
        }
    }
    val approval = when (target.kind) {
        NotificationApprovalKind.CONTROL -> controls.firstOrNull { it.id == target.id }?.let { NotificationApproval.control(context, it) }
        NotificationApprovalKind.APP_RESTART -> restarts.firstOrNull { it.id == target.id }?.let { NotificationApproval.restart(context, it) }
    }?.takeIf { it.expiresAtMillis > now }
    BasicAlertDialog(
        onDismissRequest = onClose,
        modifier = androidx.compose.ui.Modifier.fillMaxWidth().widthIn(max = 560.dp),
    ) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            Column(
                Modifier.fillMaxWidth().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Icon(Icons.Default.Security, null, Modifier.align(Alignment.CenterHorizontally),
                    tint = MaterialTheme.colorScheme.secondary)
                Text(
                    approval?.title ?: l.text("申请已失效", "Request no longer available", "申請已失效"),
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(approval?.detail ?: localizedError(l, if (target.kind == NotificationApprovalKind.CONTROL)
                    "CONTROL_REQUEST_STALE" else "APP_RESTART_TIMEOUT"))
                error?.let { Text(localizedError(l, it), color = MaterialTheme.colorScheme.error) }
                if (approval == null) {
                    TextButton(onClick = onClose, modifier = androidx.compose.ui.Modifier.align(Alignment.End)) {
                        Text(l.text("关闭", "Close", "關閉"))
                    }
                } else {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        fun allow(always: Boolean) {
                            val accepted = if (target.kind == NotificationApprovalKind.CONTROL)
                                app.mcp.approvals.approve(target.id, always = always, releasePhoneViewer = true)
                            else app.sessions.restartApprovals.approve(target.id)
                            if (accepted) onClose() else error = "PHONE_GESTURE_ACTIVE"
                        }
                        TextButton(
                            onClick = {
                                if (target.kind == NotificationApprovalKind.CONTROL) app.mcp.approvals.deny(target.id)
                                else app.sessions.restartApprovals.deny(target.id)
                                onClose()
                            },
                            modifier = androidx.compose.ui.Modifier.weight(1f).heightIn(min = 48.dp),
                            contentPadding = PaddingValues(horizontal = 2.dp, vertical = 8.dp),
                        ) {
                            Text(stringResource(R.string.control_deny), textAlign = TextAlign.Center, maxLines = 1,
                                softWrap = false, style = MaterialTheme.typography.labelMedium)
                        }
                        if (target.kind == NotificationApprovalKind.CONTROL) {
                            FilledTonalButton(
                                onClick = { allow(false) },
                                modifier = androidx.compose.ui.Modifier.weight(1f).heightIn(min = 48.dp),
                                contentPadding = PaddingValues(horizontal = 2.dp, vertical = 8.dp),
                            ) {
                                Text(stringResource(R.string.control_allow_once), textAlign = TextAlign.Center, maxLines = 1,
                                    softWrap = false, style = MaterialTheme.typography.labelMedium)
                            }
                            Button(
                                onClick = { allow(true) },
                                modifier = androidx.compose.ui.Modifier.weight(1f).heightIn(min = 48.dp),
                                contentPadding = PaddingValues(horizontal = 2.dp, vertical = 8.dp),
                            ) {
                                Text(stringResource(R.string.control_allow_always), textAlign = TextAlign.Center, maxLines = 1,
                                    softWrap = false, style = MaterialTheme.typography.labelMedium)
                            }
                        } else {
                            Button(
                                onClick = { allow(false) },
                                modifier = androidx.compose.ui.Modifier.weight(2f).heightIn(min = 48.dp),
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                            ) {
                                Text(stringResource(R.string.app_restart_allow), textAlign = TextAlign.Center, maxLines = 1,
                                    softWrap = false, style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }
        }
    }
}
