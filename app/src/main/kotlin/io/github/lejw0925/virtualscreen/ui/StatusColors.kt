package io.github.lejw0925.virtualscreen.ui

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Semantic status colors kept outside [androidx.compose.material3.ColorScheme].
 * `tertiary` stays the theme's third accent so warnings never render as success.
 */
@Immutable
internal data class StatusColors(
    val success: Color,
    val onSuccess: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warning: Color,
    val onWarning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
)

internal val LightStatusColors = StatusColors(
    success = Color(0xFF2D6846),
    onSuccess = Color(0xFFFFFFFF),
    successContainer = Color(0xFFBAF0C9),
    onSuccessContainer = Color(0xFF00210F),
    warning = Color(0xFF8A5100),
    onWarning = Color(0xFFFFFFFF),
    warningContainer = Color(0xFFFFDDB0),
    onWarningContainer = Color(0xFF2B1700),
)

internal val DarkStatusColors = StatusColors(
    success = Color(0xFF9FD4AF),
    onSuccess = Color(0xFF07391F),
    successContainer = Color(0xFF225135),
    onSuccessContainer = Color(0xFFBAF0C9),
    warning = Color(0xFFFFB95C),
    onWarning = Color(0xFF462A00),
    warningContainer = Color(0xFF5C3D00),
    onWarningContainer = Color(0xFFFFDDB0),
)

internal val LocalStatusColors = staticCompositionLocalOf { LightStatusColors }
