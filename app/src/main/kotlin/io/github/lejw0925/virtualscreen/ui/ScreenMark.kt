package io.github.lejw0925.virtualscreen.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.material3.MaterialTheme

/** Two independent screens and a small agent spark, drawn at native resolution. */
@Composable internal fun ScreenMark(modifier: Modifier = Modifier,enabled: Boolean = false) {
    val light by animateFloatAsState(if(enabled) 1f else 0f,tween(450),label="MCP screen light")
    val accent=androidx.compose.ui.graphics.lerp(MaterialTheme.colorScheme.onSurfaceVariant,Color.White,light)
    val lightTheme=MaterialTheme.colorScheme.background.luminance()>.5f
    val panelOn=if(lightTheme) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.surface
    val panel=androidx.compose.ui.graphics.lerp(MaterialTheme.colorScheme.surfaceVariant,panelOn,light)
    val shadow=MaterialTheme.colorScheme.onBackground.copy(alpha=if(lightTheme) .16f*light else 0f)
    val edge=MaterialTheme.colorScheme.onBackground.copy(alpha=if(lightTheme) .2f*light else 0f)
    Canvas(modifier) {
        val u=size.minDimension/120f
        drawCircle(Brush.radialGradient(listOf(accent.copy(alpha=.03f+.16f*light),Color.Transparent)),size.minDimension*(.42f+.08f*light))
        drawRoundRect(accent.copy(alpha=.12f),Offset(24*u,17*u),Size(48*u,80*u),CornerRadius(12*u),style=Stroke(2*u))
        drawRoundRect(shadow,Offset(43*u,29*u),Size(52*u,82*u),CornerRadius(14*u))
        drawRoundRect(Brush.linearGradient(listOf(accent,accent.copy(alpha=.72f))),Offset(45*u,28*u),Size(48*u,80*u),CornerRadius(12*u))
        drawRoundRect(edge,Offset(45*u,28*u),Size(48*u,80*u),CornerRadius(12*u),style=Stroke(1.25f*u))
        drawRoundRect(panel,Offset(49*u,33*u),Size(40*u,63*u),CornerRadius(8*u))
        drawRoundRect(accent.copy(alpha=.14f),Offset(55*u,44*u),Size(28*u,18*u),CornerRadius(4*u))
        drawLine(accent.copy(alpha=.45f),Offset(57*u,72*u),Offset(81*u,72*u),3*u,StrokeCap.Round)
        drawLine(accent.copy(alpha=.24f),Offset(57*u,81*u),Offset(75*u,81*u),3*u,StrokeCap.Round)
        drawLine(Color.White,Offset(63*u,102*u),Offset(75*u,102*u),3*u,StrokeCap.Round)
        drawLine(accent.copy(alpha=.25f+.75f*light),Offset(96*u,13*u),Offset(96*u,29*u),3*u,StrokeCap.Round)
        drawLine(accent.copy(alpha=.25f+.75f*light),Offset(88*u,21*u),Offset(104*u,21*u),3*u,StrokeCap.Round)
    }
}
