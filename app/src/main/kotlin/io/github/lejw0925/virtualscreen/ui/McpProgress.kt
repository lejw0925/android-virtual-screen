package io.github.lejw0925.virtualscreen.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import kotlinx.coroutines.delay

@Stable
internal class McpProgressMotion(val layer: GraphicsLayer, var lastRunning: Boolean) {
    val rise = Animatable(0f)
    val fall = Animatable(0f)
    var visible by mutableStateOf(false)
    var falling by mutableStateOf(false)
    var origin = Offset.Zero
    var bounds = Rect.Zero
    var exitBounds = Rect.Zero
}

@Composable
internal fun rememberMcpProgressMotion(changing: Boolean, running: Boolean, wanted: Boolean): McpProgressMotion {
    val layer = rememberGraphicsLayer()
    val motion = remember(layer) { McpProgressMotion(layer, running) }
    LaunchedEffect(changing, running, wanted, motion) {
        val stateChanged = running != motion.lastRunning
        motion.lastRunning = running
        if (!wanted) {
            motion.visible = false
            motion.falling = false
            motion.rise.snapTo(0f)
            motion.fall.snapTo(0f)
            return@LaunchedEffect
        }
        if(changing || (stateChanged && running)) {
            motion.falling = false
            motion.visible = true
            motion.fall.snapTo(0f)
            motion.rise.animateTo(1f, spring(dampingRatio = .48f, stiffness = 380f))
        }
        if(!changing && motion.visible) {
            // Let even a quick operation's entrance settle before its pieces fall away.
            motion.rise.animateTo(1f, spring(dampingRatio = .48f, stiffness = 380f))
            delay(120)
            motion.exitBounds = motion.bounds.translate(-motion.origin)
            motion.falling = true
            motion.fall.animateTo(1.4f, tween(1400, easing = LinearEasing))
            motion.visible = false
            motion.falling = false
            motion.rise.snapTo(0f)
        }
    }
    return motion
}

@Composable
internal fun McpProgressBar(motion: McpProgressMotion, changing: Boolean, modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        if(motion.visible) {
            val bar = Modifier.onGloballyPositioned { coordinates ->
                motion.bounds = Rect(coordinates.positionInRoot(), coordinates.size.toSize())
            }.graphicsLayer {
                translationY = (1f - motion.rise.value) * 40.dp.toPx()
                alpha = motion.rise.value.coerceIn(0f, 1f)
            }.drawWithContent {
                motion.layer.record { this@drawWithContent.drawContent() }
                if(!motion.falling) drawLayer(motion.layer)
            }
            if(changing) LinearWavyProgressIndicator(modifier = bar)
            else LinearWavyProgressIndicator(progress = { 1f }, modifier = bar)
        }
    }
}

@Composable
internal fun McpProgressExit(motion: McpProgressMotion, modifier: Modifier = Modifier) {
    Canvas(modifier.onGloballyPositioned { motion.origin = it.positionInRoot() }) {
        if(!motion.falling) return@Canvas
        val bounds = motion.exitBounds
        if(bounds.isEmpty) return@Canvas
        val segmentWidth = bounds.width / 8
        val distance = size.height - bounds.top + bounds.height
        translate(bounds.left, bounds.top) {
            repeat(8) { index ->
                val seconds = (motion.fall.value - index * .065f).coerceAtLeast(0f)
                val drop = distance * 1.8f * seconds * seconds
                val drift = (index - 3.5f) * 8.dp.toPx() * seconds
                translate(drift, drop) {
                    rotate((if(index % 2 == 0) -1 else 1) * 28f * seconds * seconds,
                        pivot = Offset(segmentWidth * (index + .5f), bounds.height / 2)) {
                        clipRect(left = index * segmentWidth, top = 0f,
                            right = (index + 1) * segmentWidth, bottom = bounds.height) {
                            drawLayer(motion.layer)
                        }
                    }
                }
            }
        }
    }
}
