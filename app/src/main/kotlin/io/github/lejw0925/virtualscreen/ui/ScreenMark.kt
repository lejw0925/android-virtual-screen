package io.github.lejw0925.virtualscreen.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Two independent screens and a small agent spark, drawn at native resolution. */
@Composable internal fun ScreenMark(modifier: Modifier = Modifier) {
    val accent=MiuixTheme.colorScheme.primary
    Canvas(modifier) {
        val u=size.minDimension/120f
        drawCircle(Brush.radialGradient(listOf(accent.copy(alpha=.15f),Color.Transparent)),size.minDimension*.5f)
        drawRoundRect(accent.copy(alpha=.12f),Offset(24*u,17*u),Size(48*u,80*u),CornerRadius(12*u),style=Stroke(2*u))
        drawRoundRect(Brush.linearGradient(listOf(accent,accent.copy(alpha=.72f))),Offset(45*u,28*u),Size(48*u,80*u),CornerRadius(12*u))
        drawRoundRect(Color.White.copy(alpha=.94f),Offset(49*u,33*u),Size(40*u,63*u),CornerRadius(8*u))
        drawRoundRect(accent.copy(alpha=.14f),Offset(55*u,44*u),Size(28*u,18*u),CornerRadius(4*u))
        drawLine(accent.copy(alpha=.45f),Offset(57*u,72*u),Offset(81*u,72*u),3*u,StrokeCap.Round)
        drawLine(accent.copy(alpha=.24f),Offset(57*u,81*u),Offset(75*u,81*u),3*u,StrokeCap.Round)
        drawLine(Color.White,Offset(63*u,102*u),Offset(75*u,102*u),3*u,StrokeCap.Round)
        drawLine(accent,Offset(96*u,13*u),Offset(96*u,29*u),3*u,StrokeCap.Round)
        drawLine(accent,Offset(88*u,21*u),Offset(104*u,21*u),3*u,StrokeCap.Round)
    }
}
