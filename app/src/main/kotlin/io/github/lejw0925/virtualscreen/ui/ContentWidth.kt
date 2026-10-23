package io.github.lejw0925.virtualscreen.ui

import androidx.activity.compose.LocalActivity
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min

/**
 * Readable content widths per Material 3 window size class. Compact windows use the full width;
 * medium and expanded windows cap the reading measure instead of stretching text lines.
 */
internal enum class ContentWidth(val maxWidth: Dp) {
    COMPACT(Dp.Unspecified),
    MEDIUM(600.dp),
    EXPANDED(840.dp),
    LARGE(1040.dp),
}

@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
@Composable
internal fun currentContentWidth(): ContentWidth {
    val activity = LocalActivity.current ?: return ContentWidth.COMPACT
    val sizeClass = calculateWindowSizeClass(activity)
    return when (sizeClass.widthSizeClass) {
        WindowWidthSizeClass.Compact -> ContentWidth.COMPACT
        WindowWidthSizeClass.Medium -> ContentWidth.MEDIUM
        WindowWidthSizeClass.Expanded -> ContentWidth.EXPANDED
        else -> ContentWidth.LARGE
    }
}

/** Caps a preferred width to the window size class limit, keeping the smaller of the two. */
internal fun ContentWidth.cap(preferred: Dp): Dp =
    if (maxWidth == Dp.Unspecified) preferred else min(preferred, maxWidth)
