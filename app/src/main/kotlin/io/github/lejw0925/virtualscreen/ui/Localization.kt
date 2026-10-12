package io.github.lejw0925.virtualscreen.ui

import androidx.compose.runtime.compositionLocalOf
import java.util.Locale

enum class UiLanguage(val tag: String) { ZH_HANS("zh-Hans"), ZH_HANT("zh-Hant"), EN("en");
    fun text(hans: String,en: String,hant: String): String = when(this) { ZH_HANS -> hans; ZH_HANT -> hant; EN -> en }
}
val LocalLanguage = compositionLocalOf { uiLanguage() }
fun uiLanguage(locale: Locale = Locale.getDefault()): UiLanguage = when(locale.language) {
    "zh" -> if(locale.script.equals("Hant",true) || locale.country in setOf("TW","HK","MO")) UiLanguage.ZH_HANT else UiLanguage.ZH_HANS
    else -> UiLanguage.EN
}

fun systemLanguage(context: android.content.Context): UiLanguage = uiLanguage(runCatching {
    val locales=context.getSystemService(android.app.LocaleManager::class.java).systemLocales
    if(locales.isEmpty) android.content.res.Resources.getSystem().configuration.locales[0] else locales[0]
}.getOrElse { Locale.getDefault() })

fun localizedError(l: UiLanguage,code: String): String = when(code) {
    "PURPOSE_REQUIRED" -> l.text("请使用手机系统语言填写操作目的。","Provide the operation purpose in the phone’s system language.","請使用手機系統語言填寫操作目的。")
    "CONTROL_BUSY", "TAKEOVER_REQUIRED" -> l.text("已有其他控制方。请在手机端接管或等待其释放。","Another controller holds the lease. Take over on the phone or wait for release.","已有其他控制方。請在手機端接管或等待其釋放。")
    "LEASE_REQUIRED" -> l.text("请重新申请控制权；当前租约已失效。","Acquire control again; the current lease is invalid.","請重新申請控制權；目前租約已失效。")
    "STALE_OBSERVATION", "CONTROL_CHANGED", "STALE_VIRTUAL_SCREEN" -> l.text("屏幕或控制权已改变。请重新观察后再操作。","The screen or controller changed. Observe again before acting.","螢幕或控制權已改變。請重新觀察後再操作。")
    "NO_VIRTUAL_SCREEN", "DISPLAY_GONE" -> l.text("虚拟屏未运行，请先启动。","The virtual screen is not running. Start it first.","虛擬螢幕未執行，請先啟動。")
    "ROOT_PERMISSION_DENIED", "ROOT_PERMISSION_REQUIRED", "ROOT_REQUIRED" -> l.text("需要 Root 授权，请在设置中授权并检测。","Root authorization is required. Authorize and probe in Settings.","需要 Root 授權，請在設定中授權並檢測。")
    "SHIZUKU_PERMISSION_REQUIRED", "SHIZUKU_NOT_RUNNING", "SHIZUKU_SERVICE_DIED" -> l.text("请启动 Shizuku，并在设置中授权。","Start Shizuku and authorize this app in Settings.","請啟動 Shizuku，並在設定中授權。")
    "NODES_UNAVAILABLE", "ACCESSIBILITY_NOT_ENABLED", "NODE_TREE_UNAVAILABLE", "ACCESSIBILITY_NODES_UNAVAILABLE" -> l.text("节点不可用。可启用无障碍服务，或使用截图和坐标。","Nodes are unavailable. Enable accessibility or use screenshots and coordinates.","節點不可用。可啟用無障礙服務，或使用截圖和座標。")
    "AMBIGUOUS_NODE", "INVALID_NODE_TARGET" -> l.text("目标不唯一，请重新观察并选择一个明确的节点。","The target is ambiguous. Observe again and select one specific node.","目標不唯一，請重新觀察並選擇一個明確的節點。")
    "NODE_NOT_FOUND", "NODE_TREE_TRUNCATED" -> l.text("当前观察无法确认目标，请重新观察或使用截图定位。","This observation cannot confirm the target. Observe again or locate it in a screenshot.","目前觀察無法確認目標，請重新觀察或使用截圖定位。")
    "APP_RESTART_CONFIRMATION_REQUIRED" -> l.text("应用正在其他屏幕运行，请在手机端确认重启。","The app is running on another display. Confirm restart on the phone.","應用程式正在其他螢幕執行，請在手機端確認重新啟動。")
    "SCREEN_OFF_UNAVAILABLE" -> l.text("当前设备不允许此时操作，请点亮并解锁手机。","The device cannot operate in this state. Wake and unlock the phone.","目前裝置不允許此時操作，請點亮並解鎖手機。")
    "WAIT_TIMEOUT" -> l.text("等待超时，已返回当前观察。","Wait timed out; the current observation is returned.","等待逾時，已傳回目前觀察。")
    "UI_INPUT_TIMEOUT", "INPUT_DISPATCH_UNCONFIRMED" -> l.text("操作结果未确认，请重新观察；不要直接重放。","The effect is unconfirmed. Observe again before deciding to repeat.","操作結果未確認，請重新觀察；不要直接重播。")
    "MCP_PORT_IN_USE" -> l.text("MCP 端口已被占用，请停止占用服务后重试。","The MCP port is in use. Stop the conflicting service and retry.","MCP 連接埠已被佔用，請停止佔用服務後重試。")
    "DNS_RECORD_CONFLICT" -> l.text("DNS 记录已存在，请修改域名配置。","A DNS record already exists. Choose another hostname.","DNS 記錄已存在，請修改網域設定。")
    "INVALID_ARGUMENT", "MISSING_ARGUMENT", "UNKNOWN_ARGUMENT", "ARGUMENT_NOT_APPLICABLE" -> l.text("参数不完整或不适用于当前操作，请检查工具说明。","Arguments are missing, invalid or inapplicable. Check the tool schema.","參數不完整或不適用於目前操作，請檢查工具說明。")
    else -> l.text("操作未完成，请检查权限与连接。","The operation did not complete. Check permissions and connections.","操作未完成，請檢查權限與連線。")
}
