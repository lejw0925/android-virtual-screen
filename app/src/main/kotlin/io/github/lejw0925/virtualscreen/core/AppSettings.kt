package io.github.lejw0925.virtualscreen.core

import android.content.Context
import android.view.WindowInsets
import android.view.WindowManager
import kotlin.math.roundToInt
import io.github.lejw0925.virtualscreen.backend.BackendKind
import io.github.lejw0925.virtualscreen.backend.VirtualScreenConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class UserSettings(val backend: BackendKind = BackendKind.ROOT,val config: VirtualScreenConfig = VirtualScreenConfig(),val theme: String = "MonetSystem",val language: String = "system",val restoreApp: Boolean = true,val lastComponent: String = "",val lan: Boolean = false,val fitViewer: Boolean = true)
class AppSettings(private val context: Context) {
    private val prefs = context.getSharedPreferences("user_settings",Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(runCatching { UserSettings(BackendKind.valueOf(prefs.getString("backend","ROOT")!!),
        VirtualScreenConfig(prefs.getInt("width",720),prefs.getInt("height",1600),prefs.getInt("density",320),prefs.getInt("rotation",0),prefs.getBoolean("off",false),prefs.getInt("idle",20)),
        prefs.getString("theme","MonetSystem")!!,prefs.getString("language","system")!!,prefs.getBoolean("restore",true),prefs.getString("last","")!!,prefs.getBoolean("lan",false),prefs.getBoolean("fitViewer",true)) }.getOrDefault(UserSettings()))
    val flow = mutable.asStateFlow()
    private val guideState = MutableStateFlow(prefs.getBoolean("guideCompleted",false))
    val guideCompleted = guideState.asStateFlow()
    fun completeGuide() { prefs.edit().putBoolean("guideCompleted",true).apply(); guideState.value=true }
    val backendConfigured: Boolean get() = prefs.contains("backend")
    @Volatile private var viewerWidth=0
    @Volatile private var viewerHeight=0
    fun setViewerSize(width: Int,height: Int) { viewerWidth=width; viewerHeight=height }
    fun viewerSize(): Pair<Int,Int> = viewerWidth to viewerHeight
    fun configForViewer(width: Int = viewerWidth,height: Int = viewerHeight): VirtualScreenConfig {
        val s=mutable.value
        if(!s.fitViewer) return s.config
        val size=if(width>0 && height>0) width to height else runCatching {
            val metrics=context.getSystemService(WindowManager::class.java).maximumWindowMetrics
            val insets=metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars() or WindowInsets.Type.displayCutout())
            (metrics.bounds.width()-insets.left-insets.right) to (metrics.bounds.height()-insets.top-insets.bottom)
        }.getOrElse { return s.config }
        return fitDisplayConfig(s.config,size.first,size.second)
    }
    fun update(block: (UserSettings) -> UserSettings) {
        val s = block(mutable.value)
        prefs.edit().putString("backend",s.backend.name).putInt("width",s.config.width).putInt("height",s.config.height).putInt("density",s.config.density).putInt("rotation",s.config.rotation)
            .putBoolean("off",s.config.allowScreenOff).putInt("idle",s.config.idleTimeoutMinutes).putString("theme",s.theme).putString("language",s.language).putBoolean("restore",s.restoreApp).putString("last",s.lastComponent).putBoolean("lan",s.lan).putBoolean("fitViewer",s.fitViewer).apply()
        mutable.value = s
    }
}

/** Keep the requested width whenever limits permit, rounding only the final pixel. */
internal fun fitDisplayConfig(config: VirtualScreenConfig,viewWidth: Int,viewHeight: Int): VirtualScreenConfig {
    if(viewWidth<=0 || viewHeight<=0) return config
    val height=(config.width.toDouble()*viewHeight/viewWidth).roundToInt()
    val boundedHeight=height.coerceIn(480,4096)
    val width=if(height==boundedHeight) config.width else (boundedHeight.toDouble()*viewWidth/viewHeight).roundToInt().coerceIn(320,4096)
    return config.copy(width=width,height=boundedHeight)
}
