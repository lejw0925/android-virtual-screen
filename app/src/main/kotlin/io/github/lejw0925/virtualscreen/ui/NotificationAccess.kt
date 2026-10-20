package io.github.lejw0925.virtualscreen.ui

import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.github.lejw0925.virtualscreen.VirtualScreenApplication
import io.github.lejw0925.virtualscreen.RuntimeNotification

@Composable
internal fun LiveUpdatesPreference(app: VirtualScreenApplication) {
    val l = LocalLanguage.current
    val context = LocalContext.current
    val manager = remember(app) { app.getSystemService(NotificationManager::class.java) }
    var notifications by remember { mutableStateOf(manager.areNotificationsEnabled()) }
    var promoted by remember { mutableStateOf(false) }
    LifecycleResumeEffect(manager) {
        notifications = manager.areNotificationsEnabled()
        promoted = Build.VERSION.SDK_INT >= 36 && RuntimeNotification.supportsLiveUpdates() && manager.canPostPromotedNotifications()
        onPauseOrDispose {}
    }
    ArrowPreference(
        title = l.text("实时通知与状态栏胶囊", "Live Updates and status chip", "即時通知與狀態列膠囊"),
        summary = when {
            !notifications -> l.text("未允许通知，请在系统设置中开启。", "Notifications are disabled. Enable them in system settings.", "未允許通知，請在系統設定中開啟。")
            !RuntimeNotification.supportsLiveUpdates() -> l.text("当前系统使用常规运行通知。", "This Android version uses standard notifications.", "目前系統使用一般執行通知。")
            promoted -> l.text("已允许，虚拟屏运行时请求显示。显示效果由系统决定。", "Allowed. Requested while the virtual screen runs; presentation is controlled by the system.", "已允許，虛擬螢幕執行時請求顯示。顯示效果由系統決定。")
            else -> l.text("未允许实时通知，点击前往系统设置。", "Live Updates are disabled. Open system settings to allow them.", "未允許即時通知，點按前往系統設定。")
        },
        icon = Icons.Default.NotificationsActive,
        onClick = {
            val standard = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, app.packageName)
            val promotedSettings = if (RuntimeNotification.supportsLiveUpdates() && notifications) {
                Intent(Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, app.packageName)
            } else standard
            val target = when {
                promotedSettings.resolveActivity(app.packageManager) != null -> promotedSettings
                standard.resolveActivity(app.packageManager) != null -> standard
                else -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, ("package:" + app.packageName).toUri())
            }
            context.startActivity(target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        },
    )
}
