package io.github.lejw0925.virtualscreen

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import io.github.lejw0925.virtualscreen.core.ActivityLogEntry
import io.github.lejw0925.virtualscreen.ui.LogSeverity
import io.github.lejw0925.virtualscreen.ui.logSeverity
import io.github.lejw0925.virtualscreen.ui.readableLogColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LogPresentationTest {
    @Test fun severityFiltersErrorsAndWarningsWithoutTreatingSuccessAsWarning() {
        fun entry(status: String) = ActivityLogEntry(category = "mcp", operation = "observe", status = status)
        listOf("ERROR", "failed", "denied", "rejected", "unauthorized").forEach {
            assertEquals(LogSeverity.ERROR, logSeverity(entry(it)))
        }
        listOf("warning", "reconnecting", "pending", "retry").forEach {
            assertEquals(LogSeverity.WARNING, logSeverity(entry(it)))
        }
        assertEquals(LogSeverity.ALL, logSeverity(entry("completed")))
    }

    @Test fun logEventTextMeetsContrastOnLightAndDarkSurfaces() {
        for (scheme in listOf(lightColorScheme(), darkColorScheme())) {
            val color = readableLogColor(scheme.primary, scheme.surfaceContainerHighest, scheme.onSurface)
            assertTrue(contrast(color, scheme.surfaceContainerHighest) >= 4.5f)
        }
        assertEquals(Color.Black, readableLogColor(Color(0xFFD0BCFF), Color(0xFFF3EDF7), Color.Black))
        assertEquals(Color.White, readableLogColor(Color(0xFF49454F), Color(0xFF211F26), Color.White))
    }

    private fun contrast(a: Color, b: Color): Float =
        (maxOf(a.luminance(), b.luminance()) + .05f) / (minOf(a.luminance(), b.luminance()) + .05f)
}
