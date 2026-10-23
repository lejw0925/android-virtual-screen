package io.github.lejw0925.virtualscreen.ui

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessibilityNew
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import io.github.lejw0925.virtualscreen.VirtualScreenApplication
import io.github.lejw0925.virtualscreen.core.ScreenAccessibilityService
import io.github.lejw0925.virtualscreen.core.isAccessibilityServiceEnabled
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine

internal fun openAccessibilitySettings(context: Context) {
    val settings = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
    val target = if (settings.resolveActivity(context.packageManager) != null) settings
        else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri())
    context.startActivity(target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun AccessibilityPermission(app: VirtualScreenApplication, busy: Boolean, onAuthorize: () -> Unit) {
    val l = LocalLanguage.current
    val context = LocalContext.current
    val connected by ScreenAccessibilityService.connected.collectAsStateWithLifecycle()
    var authorized by remember(app) { mutableStateOf(isAccessibilityServiceEnabled(app)) }
    LifecycleResumeEffect(connected, busy) {
        authorized = isAccessibilityServiceEnabled(app)
        onPauseOrDispose {}
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Default.AccessibilityNew, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(l.text("无障碍服务", "Accessibility service", "無障礙服務"), Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium)
                StatusChip(
                    text = when {
                        connected -> l.text("已启用", "Enabled", "已啟用")
                        authorized -> l.text("等待连接", "Not connected", "等待連線")
                        else -> l.text("未授权", "Not authorized", "未授權")
                    },
                    tone = when {
                        connected -> StatusTone.Positive
                        authorized -> StatusTone.Neutral
                        else -> StatusTone.Negative
                    },
                )
            }
            Text(when {
                connected -> l.text("已授权，服务已连接。", "Authorized and connected.", "已授權，服務已連線。")
                authorized -> l.text("已授权，但服务尚未连接。可一键重新启用。", "Authorized, but not connected. Try authorizing again.", "已授權，但服務尚未連線。可一鍵重新啟用。")
                else -> l.text("使用当前 Root / Shizuku 后端授权，也可以在系统设置中手动开启。", "Authorize with the selected Root / Shizuku backend, or enable it in system settings.", "使用目前 Root / Shizuku 後端授權，也可以在系統設定中手動開啟。")
            }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            AccessibilityActions(!busy && !connected, onAuthorize) { openAccessibilitySettings(context) }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AccessibilityActions(canAuthorize: Boolean, onAuthorize: () -> Unit, onSettings: () -> Unit) {
    val l = LocalLanguage.current
    val leading = ButtonGroupDefaults.connectedLeadingButtonShapes()
    val trailing = ButtonGroupDefaults.connectedTrailingButtonShapes()
    ButtonGroup(
        overflowIndicator = { ButtonGroupDefaults.OverflowIndicator(it) },
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        customItem(buttonGroupContent = {
            val interaction = remember { MutableInteractionSource() }
            Button(
                onClick = onAuthorize,
                enabled = canAuthorize,
                modifier = Modifier.weight(1f).animateWidth(interaction, 6.dp).heightIn(min = 48.dp),
                shapes = ButtonShapes(leading.shape, leading.pressedShape),
                interactionSource = interaction,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
            ) {
                Text(l.text("一键授权", "Authorize", "一鍵授權"), style = MaterialTheme.typography.labelLargeEmphasized)
            }
        }, menuContent = { menu ->
            TextButton(onClick = { menu.dismiss(); onAuthorize() }, enabled = canAuthorize) {
                Text(l.text("一键授权", "Authorize", "一鍵授權"))
            }
        })
        customItem(buttonGroupContent = {
            val interaction = remember { MutableInteractionSource() }
            FilledTonalButton(
                onClick = onSettings,
                modifier = Modifier.weight(1f).animateWidth(interaction, 6.dp).heightIn(min = 48.dp),
                shapes = ButtonShapes(trailing.shape, trailing.pressedShape),
                interactionSource = interaction,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
            ) {
                Text(l.text("系统设置", "System settings", "系統設定"), style = MaterialTheme.typography.labelLargeEmphasized)
            }
        }, menuContent = { menu ->
            TextButton(onClick = { menu.dismiss(); onSettings() }) {
                Text(l.text("系统设置", "System settings", "系統設定"))
            }
        })
    }
}

/** Keep undelivered results across activity navigation; only the resumed screen shows them. */
@Composable
internal fun AccessibilitySnackbar(app: VirtualScreenApplication, modifier: Modifier = Modifier) {
    val host = remember { SnackbarHostState() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val context = LocalContext.current
    val l = LocalLanguage.current
    val activation = app.sessions.accessibilityActivation
    LaunchedEffect(activation, lifecycle, context, l) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            combine(activation.notice, ScreenAccessibilityService.connected) { notice, connected -> notice to connected }
                .collectLatest { (notice, connected) ->
                    if (notice == null) return@collectLatest
                    if (notice.enabled != connected) {
                        activation.dismissNotice(notice.id)
                        return@collectLatest
                    }
                    val result = host.showSnackbar(
                        message = if (notice.enabled) l.text(
                            "已自动启用无障碍服务", "Accessibility service enabled automatically", "已自動啟用無障礙服務",
                        ) else l.text(
                            "未能自动启用无障碍服务，请在系统设置中手动开启。",
                            "Could not enable accessibility automatically. Turn it on in system settings.",
                            "未能自動啟用無障礙服務，請在系統設定中手動開啟。",
                        ),
                        actionLabel = if (notice.enabled) null else l.text("去开启", "Open settings", "前往開啟"),
                        withDismissAction = !notice.enabled,
                        duration = if (notice.enabled) SnackbarDuration.Short else SnackbarDuration.Long,
                    )
                    activation.dismissNotice(notice.id)
                    if (result == SnackbarResult.ActionPerformed) openAccessibilitySettings(context)
                }
        }
    }
    SnackbarHost(
        hostState = host,
        modifier = modifier.navigationBarsPadding().imePadding(),
        snackbar = { data ->
            Snackbar(
                snackbarData = data,
                shape = MaterialTheme.shapes.large,
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurface,
                actionColor = MaterialTheme.colorScheme.primary,
                dismissActionContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
}
