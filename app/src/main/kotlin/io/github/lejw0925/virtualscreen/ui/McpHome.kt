package io.github.lejw0925.virtualscreen.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.material3.carousel.CarouselDefaults
import androidx.compose.material3.carousel.HorizontalMultiBrowseCarousel
import androidx.compose.material3.carousel.rememberCarouselState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.lejw0925.virtualscreen.mcp.McpServerState
import kotlinx.coroutines.launch
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun McpHome(
    server: McpServerState,
    changing: Boolean,
    wanted: Boolean,
    address: String,
    onToggle: (Boolean) -> Unit,
    onConnect: () -> Unit,
    onSettings: () -> Unit,
    onCopy: () -> Unit,
    modifier: Modifier = Modifier,
    error: String? = null,
) {
    val l = LocalLanguage.current
    val carousel = rememberCarouselState { 2 }
    val scope = rememberCoroutineScope()
    val progress = rememberMcpProgressMotion(changing, server.running)
    val snap = spring<Float>(dampingRatio = .72f, stiffness = 240f)
    val titles = listOf(l.text("MCP 服务", "MCP server", "MCP 服務"), l.text("连接", "Connections", "連線"))
    val status = when {
        changing && wanted -> l.text("正在开启", "Starting", "正在開啟")
        changing -> l.text("正在关闭", "Stopping", "正在關閉")
        server.running -> l.text("已就绪", "Ready", "已就緒")
        else -> l.text("已关闭", "Off", "已關閉")
    }
    val statusColor by animateColorAsState(when {
        changing -> MaterialTheme.colorScheme.tertiary
        server.running -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.outline
    }, MaterialTheme.motionScheme.defaultEffectsSpec(), label = "MCP status color")
    val statusScale = animateFloatAsState(if(changing) 1.25f else 1f,
        MaterialTheme.motionScheme.defaultSpatialSpec(), label = "MCP status scale")
    val buttonColor by animateColorAsState(if(server.running) MaterialTheme.colorScheme.errorContainer
        else MaterialTheme.colorScheme.primary, MaterialTheme.motionScheme.defaultEffectsSpec(), label = "MCP button color")
    val buttonContent by animateColorAsState(if(server.running) MaterialTheme.colorScheme.onErrorContainer
        else MaterialTheme.colorScheme.onPrimary, MaterialTheme.motionScheme.defaultEffectsSpec(), label = "MCP button content")
    Box(modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Column(Modifier.widthIn(max = 600.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 28.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(l.text("虚拟屏", "Virtual Screen", "虛擬屏"), style = MaterialTheme.typography.headlineMedium,
                        modifier = Modifier.weight(1f))
                    FilledTonalIconButton(onClick = onSettings, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Default.Settings, l.text("设置", "Settings", "設定"))
                    }
                }
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val cardHeight = 448.dp + 160.dp * (LocalDensity.current.fontScale - 1f).coerceAtLeast(0f)
                    HorizontalMultiBrowseCarousel(
                        state = carousel,
                        preferredItemWidth = (maxWidth - 68.dp).coerceAtMost(440.dp),
                        modifier = Modifier.fillMaxWidth().height(cardHeight),
                        itemSpacing = 12.dp,
                        contentPadding = PaddingValues(horizontal = 24.dp),
                        flingBehavior = CarouselDefaults.singleAdvanceFlingBehavior(carousel, snapAnimationSpec = snap),
                    ) { index ->
                        Card(
                            modifier = Modifier.fillMaxSize().maskClip(RoundedCornerShape(32.dp)),
                            shape = RoundedCornerShape(32.dp),
                            colors = CardDefaults.cardColors(containerColor = if(index == 0)
                                MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.secondaryContainer),
                        ) {
                            Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    if(index == 0) {
                                        Spacer(Modifier.weight(1f))
                                        Box(Modifier.size(32.dp).semantics {
                                            contentDescription = titles[index]
                                            stateDescription = status
                                        }, contentAlignment = Alignment.Center) {
                                            Box(Modifier.size(10.dp).graphicsLayer {
                                                scaleX = statusScale.value
                                                scaleY = statusScale.value
                                            }.background(statusColor, CircleShape))
                                        }
                                    } else {
                                        Text(titles[index], style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                                        Icon(Icons.Default.Link, null)
                                    }
                                }
                                if(index == 0) {
                                    ScreenPowerScene(server.running, changing, status, onToggle,
                                        Modifier.weight(1f).fillMaxWidth())
                                    McpProgressBar(progress, changing, Modifier.fillMaxWidth().height(20.dp))
                                    Button(
                                        onClick = { onToggle(!server.running) },
                                        enabled = !changing,
                                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).semantics {
                                            contentDescription = if(server.running) l.text("关闭 MCP", "Turn off MCP", "關閉 MCP")
                                                else l.text("开启 MCP", "Turn on MCP", "開啟 MCP")
                                            stateDescription = status
                                        },
                                        shapes = ButtonDefaults.shapes(),
                                        colors = ButtonDefaults.buttonColors(containerColor = buttonColor, contentColor = buttonContent),
                                    ) {
                                        Icon(Icons.Default.PowerSettingsNew, null, modifier = Modifier.size(26.dp))
                                    }
                                } else {
                                    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                                        verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                        Text(if(server.running) address else status,
                                            style = MaterialTheme.typography.bodyLarge, fontFamily = FontFamily.Monospace)
                                        Text(l.text("${server.sessionCount} 个客户端", "${server.sessionCount} clients", "${server.sessionCount} 個用戶端"),
                                            style = MaterialTheme.typography.labelLarge)
                                        server.clients.forEach { client ->
                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                Box(Modifier.size(8.dp).background(if(client.initialized)
                                                    MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape))
                                                Text(client.name, style = MaterialTheme.typography.bodyMedium,
                                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            }
                                        }
                                    }
                                    FilledTonalButton(onClick = onCopy, enabled = server.running,
                                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                                        Icon(Icons.Default.ContentCopy, null, Modifier.size(18.dp))
                                        Spacer(Modifier.width(8.dp))
                                        Text(l.text("复制配置", "Copy configuration", "複製設定"))
                                    }
                                    TextButton(onClick = onConnect, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                                        Text(l.text("连接设置", "Connection settings", "連線設定"))
                                        Spacer(Modifier.width(8.dp))
                                        Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(18.dp))
                                    }
                                }
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    titles.forEachIndexed { index, title ->
                        val width by androidx.compose.animation.core.animateDpAsState(
                            if(carousel.currentItem == index) 28.dp else 8.dp,
                            spring(dampingRatio = .6f, stiffness = 320f), label = "carousel page width")
                        IconButton(onClick = { scope.launch { carousel.animateScrollToItem(index, snap) } },
                            modifier = Modifier.size(48.dp).semantics { contentDescription = title }) {
                            Box(Modifier.size(width = width, height = 8.dp).background(
                                if(carousel.currentItem == index) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outlineVariant, CircleShape))
                        }
                    }
                }
                error?.let { Text(it, modifier = Modifier.padding(horizontal = 28.dp),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
            }
        }
        McpProgressExit(progress, Modifier.matchParentSize())
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ScreenPowerScene(
    enabled: Boolean,
    changing: Boolean,
    status: String,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val l = LocalLanguage.current
    val power = remember { Animatable(0f) }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val press = animateFloatAsState(if(pressed) .95f else 1f,
        spring(dampingRatio = .58f, stiffness = 500f), label = "phone press")
    LaunchedEffect(enabled) {
        power.animateTo(if(enabled) 1f else 0f,
            spring(dampingRatio = if(enabled) .48f else .42f, stiffness = if(enabled) 220f else 240f))
    }
    val light = animateFloatAsState(if(enabled) 1f else 0f,
        spring(dampingRatio = 1f, stiffness = 180f), label = "screen illumination")
    val screenColor by animateColorAsState(
        if(enabled) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.inverseSurface,
        spring(stiffness = 180f), label = "screen color")
    val colors = MaterialTheme.colorScheme
    Box(modifier.toggleable(enabled = !changing, value = enabled, role = Role.Switch,
        interactionSource = interaction, indication = null, onValueChange = onToggle)
        .semantics { contentDescription = l.text("MCP 服务开关", "MCP server switch", "MCP 服務開關"); stateDescription = status },
        contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().clearAndSetSemantics {}) {
            val p = power.value
            val upright = p.coerceIn(0f, 1f)
            val rebound = if(!enabled) abs(p).coerceAtMost(.35f) else 0f
            val shadowWidth = 208.dp.toPx() - 112.dp.toPx() * upright + 24.dp.toPx() * rebound
            drawOval(colors.scrim.copy(alpha = (.08f + .04f * (1f - light.value)) / (1f + 3f * rebound)),
                topLeft = Offset(center.x - shadowWidth / 2, center.y + 32.dp.toPx() + 78.dp.toPx() * upright),
                size = Size(shadowWidth, 8.dp.toPx() + 12.dp.toPx() * rebound))
            drawLine(colors.outlineVariant.copy(alpha = .45f * (1f - light.value)),
                Offset(center.x - 116.dp.toPx(), center.y + 38.dp.toPx()),
                Offset(center.x + 116.dp.toPx(), center.y + 38.dp.toPx()), strokeWidth = 1.dp.toPx())
            val edgeAlpha = (1f - p * 2.5f).coerceIn(0f, 1f)
            val edgeLeft = center.x - 104.dp.toPx()
            val edgeTop = center.y + 24.dp.toPx()
            val edgeHeight = 12.dp.toPx()
            val sideLift = -72.dp.toPx() * rebound
            // Keep the landing spring visible after the front face has rotated out of view.
            withTransform({
                translate(top = sideLift)
                rotate(degrees = if(!enabled) p.coerceIn(-.25f, .35f) * 8f else 0f,
                    pivot = Offset(center.x, edgeTop + edgeHeight / 2))
            }) {
                drawRoundRect(
                    brush = Brush.verticalGradient(listOf(colors.outlineVariant, colors.onSurfaceVariant, colors.outline),
                        startY = edgeTop, endY = edgeTop + edgeHeight),
                    topLeft = Offset(edgeLeft, edgeTop), size = Size(208.dp.toPx(), edgeHeight),
                    cornerRadius = CornerRadius(6.dp.toPx()), alpha = edgeAlpha,
                )
                drawRoundRect(colors.surfaceContainerHigh.copy(alpha = edgeAlpha),
                    topLeft = Offset(edgeLeft + 126.dp.toPx(), edgeTop + 4.dp.toPx()),
                    size = Size(28.dp.toPx(), 4.dp.toPx()), cornerRadius = CornerRadius(2.dp.toPx()))
                listOf(12f, 192f).forEach { x ->
                    drawLine(colors.surface.copy(alpha = .6f * edgeAlpha),
                        Offset(edgeLeft + x.dp.toPx(), edgeTop + 1.dp.toPx()),
                        Offset(edgeLeft + x.dp.toPx(), edgeTop + edgeHeight - 1.dp.toPx()), strokeWidth = 1.dp.toPx())
                }
            }
            drawCircle(colors.primary.copy(alpha = .06f * light.value),
                radius = 96.dp.toPx() * (.7f + .3f * p), center = center)
        }
        Box(
            modifier = Modifier.size(width = 128.dp, height = 224.dp).clearAndSetSemantics {}.graphicsLayer {
                val p = power.value
                alpha = (p * 4f).coerceIn(0f, 1f)
                scaleX = (.92f + .08f * p) * press.value
                scaleY = (.92f + .08f * p) * press.value
                rotationZ = -90f * (1f - p)
                rotationX = 88f * (1f - p)
                cameraDistance = 24f * density
                transformOrigin = TransformOrigin.Center
                translationY = 30.dp.toPx() * (1f - p)
            },
        ) {
            Box(Modifier.align(Alignment.CenterEnd).offset(x = 1.dp, y = (-32).dp).size(width = 5.dp, height = 30.dp)
                .background(colors.onSurfaceVariant, RoundedCornerShape(2.dp)))
            Surface(Modifier.fillMaxSize().padding(horizontal = 4.dp), shape = RoundedCornerShape(26.dp),
                color = colors.outline) {
                Box(Modifier.padding(4.dp).clip(RoundedCornerShape(22.dp)).background(screenColor), contentAlignment = Alignment.Center) {
                    Box(Modifier.align(Alignment.TopCenter).padding(top = 10.dp).size(width = 28.dp, height = 4.dp)
                        .background(colors.onPrimaryContainer.copy(alpha = .3f * light.value), CircleShape))
                    Box(Modifier.graphicsLayer { alpha = light.value }) {
                        if(enabled) LoadingIndicator(modifier = Modifier.size(64.dp), color = colors.primary)
                    }
                    Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp).size(width = 36.dp, height = 4.dp)
                        .background(colors.onPrimaryContainer.copy(alpha = .2f * light.value), CircleShape))
                }
            }
        }
    }
}
