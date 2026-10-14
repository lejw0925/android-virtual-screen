package io.github.lejw0925.virtualscreen.ui

import android.app.Activity
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.WindowCompat
import io.github.lejw0925.virtualscreen.R
import io.github.lejw0925.virtualscreen.VirtualScreenApplication

/** Select the native background before Compose draws its first frame. */
internal fun ComponentActivity.prepareWindowTheme(app: VirtualScreenApplication) {
    val dark = when (app.settings.flow.value.theme) {
        "Dark", "MonetDark" -> true
        "Light", "MonetLight" -> false
        else -> resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    }
    setTheme(if (dark) R.style.Theme_VirtualScreen_Dark else R.style.Theme_VirtualScreen_Light)
}

/** Native transitions and Compose page fades must expose the same background. */
@Composable internal fun WindowAppearance(background: Color) {
    val activity = LocalView.current.context as? ComponentActivity ?: return
    val color = background.toArgb()
    val lightBars = background.luminance() > .5f
    SideEffect {
        val window = activity.window
        if ((window.decorView.background as? ColorDrawable)?.color != color) {
            window.setBackgroundDrawable(color.toDrawable())
        }
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = lightBars
            isAppearanceLightNavigationBars = lightBars
        }
        if (activity is SettingsActivity && Build.VERSION.SDK_INT >= 34) {
            activity.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN, R.anim.avs_slide_in_right, R.anim.avs_slide_out_left, color)
            activity.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, R.anim.avs_slide_in_left, R.anim.avs_slide_out_right, color)
        }
    }
}
