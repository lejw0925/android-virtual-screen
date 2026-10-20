package io.github.lejw0925.virtualscreen.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp

internal enum class StatusTone { Positive, Neutral, Negative }

/** A standard selected InputChip presented as a read-only status, without a false tap target. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun StatusChip(text: String, tone: StatusTone, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val container = when (tone) {
        StatusTone.Positive -> colors.tertiaryContainer
        StatusTone.Neutral -> colors.surfaceContainerHighest
        StatusTone.Negative -> colors.errorContainer
    }
    val content = when (tone) {
        StatusTone.Positive -> colors.onTertiaryContainer
        StatusTone.Neutral -> colors.onSurfaceVariant
        StatusTone.Negative -> colors.onErrorContainer
    }
    val motion = MaterialTheme.motionScheme
    val morph by animateFloatAsState(
        if (tone == StatusTone.Positive) 1f else 0f,
        motion.defaultSpatialSpec(), label = "status question to check",
    )
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        InputChip(
            selected = tone == StatusTone.Positive,
            onClick = {},
            enabled = false,
            modifier = modifier.clearAndSetSemantics { this.text = AnnotatedString(text) },
            label = { Text(text, style = MaterialTheme.typography.labelLarge) },
            leadingIcon = {
                AnimatedContent(
                    targetState = tone,
                    transitionSpec = {
                        (scaleIn(motion.defaultSpatialSpec(), initialScale = .6f) + fadeIn(motion.fastEffectsSpec())) togetherWith
                            (scaleOut(motion.fastSpatialSpec(), targetScale = .75f) + fadeOut(motion.fastEffectsSpec()))
                    },
                    label = "status icon",
                ) { target ->
                    if (target == StatusTone.Negative) Icon(Icons.Default.Error, null, Modifier.size(18.dp))
                    else QuestionCheckIcon(morph, content, container, Modifier.size(18.dp))
                }
            },
            border = null,
            colors = InputChipDefaults.inputChipColors(
                disabledContainerColor = container,
                disabledSelectedContainerColor = container,
                disabledLabelColor = content,
                disabledLeadingIconColor = content,
            ),
        )
    }
}

/** Compatible cubic paths morph a question mark into a check inside the same circle. */
@Composable
private fun QuestionCheckIcon(progress: Float, tint: Color, container: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val p = progress.coerceIn(0f, 1f)
        fun blend(from: Float, to: Float) = from + (to - from) * p
        scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) {
            drawCircle(tint.copy(alpha = p), radius = 9f, center = Offset(12f, 12f))
            drawCircle(tint, radius = 9f, center = Offset(12f, 12f), style = Stroke(2f))
            val mark = Path().apply {
                moveTo(blend(8.8f, 7f), blend(8f, 12f))
                cubicTo(blend(8.8f, 7.8f), blend(4.3f, 12.8f),
                    blend(15.2f, 8.8f), blend(4.3f, 13.8f), blend(15.2f, 9.5f), blend(8f, 14.5f))
                cubicTo(blend(15.2f, 11.8f), blend(10.2f, 12.2f),
                    blend(12f, 14.7f), blend(10.5f, 9.3f), blend(12f, 17f), blend(13f, 7f))
            }
            drawPath(mark, lerp(tint, container, p), style = Stroke(2f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawCircle(tint.copy(alpha = 1f - p), radius = 1f, center = Offset(12f, 16.5f))
        }
    }
}
