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
    "CONTROL_BUSY", "TAKEOVER_REQUIRED" -> l.text("控制权被占用。可使用 request_control 申请手机端审批，或等待释放。","Control is occupied. Use request_control for phone approval, or wait for release.","控制權被佔用。可使用 request_control 申請手機端審批，或等待釋放。")
    "CONTROL_REQUEST_DENIED", "CONTROL_REQUEST_CANCELLED" -> l.text("接管申请已拒绝或取消。","The control request was denied or cancelled.","接管申請已拒絕或取消。")
    "CONTROL_REQUEST_EXPIRED", "CONTROL_REQUEST_STALE", "CONTROL_REQUEST_NOT_FOUND" -> l.text("接管申请已过期或控制方已改变，请重新申请。","The request expired or its controller changed. Submit a new request.","接管申請已過期或控制方已改變，請重新申請。")
    "ACCESSIBILITY_AUTHORIZATION_FAILED" -> l.text("无障碍服务未能自动启用，请在系统设置中手动开启。","Could not enable accessibility automatically. Turn it on in system settings.","無障礙服務未能自動啟用，請在系統設定中手動開啟。")
    "LEASE_REQUIRED" -> l.text("请重新申请控制权；当前租约已失效。","Acquire control again; the current lease is invalid.","請重新申請控制權；目前租約已失效。")
    "STALE_OBSERVATION", "CONTROL_CHANGED", "STALE_VIRTUAL_SCREEN" -> l.text("屏幕或控制权已改变。请重新观察后再操作。","The screen or controller changed. Observe again before acting.","螢幕或控制權已改變。請重新觀察後再操作。")
    "NO_VIRTUAL_SCREEN", "DISPLAY_GONE" -> l.text("虚拟屏未运行，请先启动。","The virtual screen is not running. Start it first.","虛擬螢幕未執行，請先啟動。")
    "NO_RUNNING_APP" -> l.text("虚拟屏中目前没有运行应用，无法读取 UI 树。已返回可启动应用清单，请先打开一个应用。","No app is currently running on the virtual display, so its UI tree is unavailable. The launchable app list is included; open an app first.","虛擬螢幕中目前沒有執行中的應用程式，無法讀取 UI 樹。已傳回可啟動應用程式清單，請先開啟一個應用程式。")
    "DISPLAY_FRAME_PENDING" -> l.text("应用画面尚未就绪，请稍后重新观察。","The app has not produced a frame yet. Observe again shortly.","應用程式畫面尚未就緒，請稍後重新觀察。")
    "PHONE_GESTURE_ACTIVE", "PHONE_VIEWER_ACTIVE" -> l.text("手机仍在手动操作虚拟屏，请结束当前触摸后再允许接管。","The phone is still controlling the virtual display. Finish the current gesture before allowing control.","手機仍在手動操作虛擬螢幕，請結束目前觸控後再允許接管。")
    "ROOT_PERMISSION_DENIED", "ROOT_PERMISSION_REQUIRED", "ROOT_REQUIRED" -> l.text("需要 Root 授权，请在设置中授权并检测。","Root authorization is required. Authorize and probe in Settings.","需要 Root 授權，請在設定中授權並檢測。")
    "SHIZUKU_SERVICE_BIND_TIMEOUT" -> l.text("Shizuku 服务连接超时，请重新授权后重试。","Shizuku service binding timed out. Authorize again and retry.","Shizuku 服務連線逾時，請重新授權後重試。")
    "SHIZUKU_PERMISSION_REQUIRED", "SHIZUKU_NOT_RUNNING", "SHIZUKU_SERVICE_DIED" -> l.text("请启动 Shizuku 或 Stellar，并在设置中授权。","Start Shizuku or Stellar and authorize this app in Settings.","請啟動 Shizuku 或 Stellar，並在設定中授權。")
    "NODES_UNAVAILABLE", "ACCESSIBILITY_NOT_ENABLED", "NODE_TREE_UNAVAILABLE", "ACCESSIBILITY_NODES_UNAVAILABLE" -> l.text("节点不可用。可启用无障碍服务，或使用截图和坐标。","Nodes are unavailable. Enable accessibility or use screenshots and coordinates.","節點不可用。可啟用無障礙服務，或使用截圖和座標。")
    "NODE_WINDOW_NOT_READY" -> l.text("无障碍服务已连接，但当前虚拟屏应用窗口尚未提供节点。请稍后重新观察，或使用截图。","Accessibility is connected, but the current virtual-display app window has not exposed nodes yet. Observe again shortly or request a screenshot.","無障礙服務已連線，但目前虛擬螢幕應用程式視窗尚未提供節點。請稍後重新觀察，或使用截圖。")
    "AMBIGUOUS_NODE", "INVALID_NODE_TARGET" -> l.text("目标不唯一，请重新观察并选择一个明确的节点。","The target is ambiguous. Observe again and select one specific node.","目標不唯一，請重新觀察並選擇一個明確的節點。")
    "NODE_NOT_FOUND", "NODE_TREE_TRUNCATED" -> l.text("当前观察无法确认目标，请重新观察或使用截图定位。","This observation cannot confirm the target. Observe again or locate it in a screenshot.","目前觀察無法確認目標，請重新觀察或使用截圖定位。")
    "APP_RESTART_CONFIRMATION_REQUIRED" -> l.text("应用正在其他屏幕运行，请在手机端确认重启。","The app is running on another display. Confirm restart on the phone.","應用程式正在其他螢幕執行，請在手機端確認重新啟動。")
    "APP_MOVED_TO_PHONE" -> l.text("应用已移到手机主屏。请在虚拟屏重新打开应用，并在手机端确认重启。","The app moved to the phone display. Launch it on the virtual screen again and approve its restart on the phone.","應用程式已移到手機主螢幕。請在虛擬螢幕重新開啟應用程式，並在手機端確認重新啟動。")
    "APP_RESTART_DENIED", "APP_RESTART_CANCELLED" -> l.text("应用重启申请已拒绝或取消。","The app restart request was denied or cancelled.","應用程式重新啟動申請已拒絕或取消。")
    "APP_RESTART_TIMEOUT" -> l.text("两分钟内未确认，应用重启申请已超时。","The app restart request timed out after two minutes without confirmation.","兩分鐘內未確認，應用程式重新啟動申請已逾時。")
    "APP_LAUNCH_BUSY" -> l.text("另一个应用启动请求正在处理，请等待其结束。","Another app launch is in progress. Wait for it to finish.","另一個應用程式啟動請求正在處理，請等待其結束。")
    "NOTIFICATION_PERMISSION_REQUIRED" -> l.text("无法显示重启审批通知，请允许应用通知后重试。","Restart approval notifications are disabled. Enable app notifications and retry.","無法顯示重新啟動審批通知，請允許應用程式通知後重試。")
    "SCREEN_OFF_UNAVAILABLE" -> l.text("当前设备不允许此时操作，请点亮并解锁手机。","The device cannot operate in this state. Wake and unlock the phone.","目前裝置不允許此時操作，請點亮並解鎖手機。")
    "WAIT_TIMEOUT" -> l.text("等待超时，已返回当前观察。","Wait timed out; the current observation is returned.","等待逾時，已傳回目前觀察。")
    "UI_INPUT_TIMEOUT", "INPUT_DISPATCH_UNCONFIRMED" -> l.text("操作结果未确认，请重新观察；不要直接重放。","The effect is unconfirmed. Observe again before deciding to repeat.","操作結果未確認，請重新觀察；不要直接重播。")
    "MCP_PORT_IN_USE" -> l.text("MCP 端口已被占用，请停止占用服务后重试。","The MCP port is in use. Stop the conflicting service and retry.","MCP 連接埠已被佔用，請停止佔用服務後重試。")
    "DNS_RECORD_CONFLICT" -> l.text("DNS 记录已存在，请修改域名配置。","A DNS record already exists. Choose another hostname.","DNS 記錄已存在，請修改網域設定。")
    "INVALID_ARGUMENT", "MISSING_ARGUMENT", "UNKNOWN_ARGUMENT", "ARGUMENT_NOT_APPLICABLE" -> l.text("参数不完整或不适用于当前操作，请检查工具说明。","Arguments are missing, invalid or inapplicable. Check the tool schema.","參數不完整或不適用於目前操作，請檢查工具說明。")
    else -> l.text("操作未完成，请检查权限与连接。","The operation did not complete. Check permissions and connections.","操作未完成，請檢查權限與連線。")
}
