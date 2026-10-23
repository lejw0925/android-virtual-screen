package io.github.lejw0925.virtualscreen.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
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
import androidx.compose.material.icons.filled.AccessibilityNew
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.*
import androidx.compose.material3.carousel.CarouselDefaults
import androidx.compose.material3.carousel.HorizontalMultiBrowseCarousel
import androidx.compose.material3.carousel.rememberCarouselState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
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
import io.github.lejw0925.virtualscreen.core.ActivityLog
import io.github.lejw0925.virtualscreen.mcp.McpServerState
import io.github.lejw0925.virtualscreen.backend.BackendKind
import kotlinx.coroutines.launch
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun McpHome(
    server: McpServerState,
    changing: Boolean,
    wanted: Boolean,
    address: String,
    backend: BackendKind,
    backendReady: Boolean,
    accessibilityConnected: Boolean,
    activityLog: ActivityLog,
    onToggle: (Boolean) -> Unit,
    onConnect: () -> Unit,
    onSettings: () -> Unit,
    onBackend: () -> Unit,
    onAccessibility: (Boolean) -> Unit,
    onLogs: () -> Unit,
    onCopy: () -> Unit,
    onCopyAddress: () -> Unit,
    modifier: Modifier = Modifier,
    error: String? = null,
) {
    val l = LocalLanguage.current
    val carousel = rememberCarouselState { 2 }
    val scope = rememberCoroutineScope()
    val snap = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    val titles = listOf(l.text("MCP 服务", "MCP server", "MCP 服務"), l.text("连接", "Connections", "連線"))
    val status = when {
        changing && wanted -> l.text("正在开启", "Starting", "正在開啟")
        changing -> l.text("正在关闭", "Stopping", "正在關閉")
        server.running -> l.text("运行中", "Running", "執行中")
        else -> l.text("已关闭", "Off", "已關閉")
    }
    Box(modifier.fillMaxSize().navigationBarsPadding()) {
        val contentWidth = currentContentWidth()
        val availableWidth = contentWidth.cap(640.dp)
        Column(
            Modifier.widthIn(max = availableWidth).fillMaxSize().align(Alignment.TopCenter)
                .padding(bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            McpTopBar(
                backend = backend,
                backendReady = backendReady,
                accessibilityConnected = accessibilityConnected,
                onBackend = onBackend,
                onAccessibility = onAccessibility,
                onSettings = onSettings,
            )
            error?.let {
                Surface(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ) {
                    Text(it, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
                }
            }
            HorizontalMultiBrowseCarousel(
                state = carousel,
                preferredItemWidth = (availableWidth - 56.dp).coerceAtMost(480.dp),
                modifier = Modifier.fillMaxWidth().weight(1f),
                itemSpacing = 10.dp,
                contentPadding = PaddingValues(horizontal = 20.dp),
                flingBehavior = CarouselDefaults.singleAdvanceFlingBehavior(carousel, snapAnimationSpec = snap),
            ) { index ->
                val itemDrawInfo = carouselItemDrawInfo
                val maskColor = MaterialTheme.colorScheme.surface
                val foldProgress = {
                    val range = itemDrawInfo.maxSize - itemDrawInfo.minSize
                    val folded = if (range > 0f) {
                        ((itemDrawInfo.maxSize - itemDrawInfo.size) / range).coerceIn(0f, 1f)
                    } else 0f
                    folded * folded * (3f - 2f * folded)
                }
                Card(
                    modifier = Modifier.fillMaxSize().maskClip(MaterialTheme.shapes.extraLargeIncreased),
                    shape = MaterialTheme.shapes.extraLargeIncreased,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                ) {
                    Box(Modifier.fillMaxSize().drawWithContent {
                        drawContent()
                        drawRect(maskColor, alpha = foldProgress() * .36f)
                    }.graphicsLayer {
                        val progress = foldProgress()
                        val scale = 1f - .14f * progress
                        scaleX = scale
                        scaleY = scale
                        val radius = 12.dp.toPx() * progress
                        renderEffect = if (radius > .1f) BlurEffect(radius, radius, TileMode.Clamp) else null
                    }) {
                        if (index == 0) {
                            McpServiceCard(server.running, changing, status, titles[index], onToggle)
                        } else {
                            Column(
                                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp),
                                verticalArrangement = Arrangement.spacedBy(16.dp),
                            ) {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text(titles[index], Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                                    IconButton(onClick = onConnect, modifier = Modifier.size(48.dp)) {
                                        Icon(Icons.Default.Link, l.text("连接设置", "Connection settings", "連線設定"))
                                    }
                                }
                                Column(
                                    Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    OutlinedTextField(value = address, onValueChange = {}, readOnly = true,
                                        modifier = Modifier.fillMaxWidth(),
                                        label = { Text(l.text("MCP 入口", "MCP endpoint", "MCP 入口")) },
                                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                                        maxLines = 2,
                                        trailingIcon = { IconButton(onClick = onCopyAddress, enabled = server.running, modifier = Modifier.size(48.dp)) {
                                            Icon(Icons.Default.ContentCopy, l.text("复制地址", "Copy address", "複製位址"))
                                        } },
                                    )
                                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically) {
                                        McpStat(server.sessionCount, l.text("客户端", "Clients", "用戶端"), Modifier.weight(1f))
                                        VerticalDivider(Modifier.height(56.dp), color = MaterialTheme.colorScheme.outlineVariant)
                                        McpStat(server.clients.count { it.initialized }, l.text("已初始化", "Initialized", "已初始化"), Modifier.weight(1f))
                                    }
                                    if (server.clients.isNotEmpty()) {
                                        Text(l.text("最近连接", "Recent clients", "最近連線"), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        server.clients.take(2).forEach { client ->
                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                Box(Modifier.size(8.dp).background(if (client.initialized) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape))
                                                Text(client.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            }
                                        }
                                    }
                                    McpConnectionActions(server.running, onCopy, onConnect)
                                }
                                HomeActivityLogs(activityLog, onLogs, Modifier.fillMaxWidth().height(360.dp))
                            }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                titles.forEachIndexed { index, title ->
                    val width by androidx.compose.animation.core.animateDpAsState(
                        if (carousel.currentItem == index) 28.dp else 8.dp,
                        MaterialTheme.motionScheme.defaultSpatialSpec(), label = "carousel page width",
                    )
                    IconButton(
                        onClick = { scope.launch { carousel.animateScrollToItem(index, snap) } },
                        modifier = Modifier.size(48.dp).semantics { contentDescription = title },
                    ) {
                        Box(Modifier.size(width = width, height = 8.dp).background(
                            if (carousel.currentItem == index) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outlineVariant, CircleShape,
                        ))
                    }
                }
            }
        }
    }
}

/** Backend status on the left, the two device-level actions on the right. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun McpTopBar(
    backend: BackendKind,
    backendReady: Boolean,
    accessibilityConnected: Boolean,
    onBackend: () -> Unit,
    onAccessibility: (Boolean) -> Unit,
    onSettings: () -> Unit,
) {
    val l = LocalLanguage.current
    val status = LocalStatusColors.current
    val backendStatus = if (backendReady) l.text("已激活", "Active", "已啟用")
        else l.text("未激活", "Inactive", "未啟用")
    // A plain row instead of TopAppBar so the 20.dp side inset matches the carousel cards exactly.
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp).heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AssistChip(
            onClick = onBackend,
            modifier = Modifier.semantics { stateDescription = backendStatus },
            label = {
                Text(backend.wireName.replaceFirstChar(Char::uppercase),
                    style = MaterialTheme.typography.labelLargeEmphasized)
            },
            leadingIcon = {
                Icon(if (backendReady) Icons.Default.CheckCircle else Icons.Default.ErrorOutline,
                    null, Modifier.size(AssistChipDefaults.IconSize))
            },
            colors = AssistChipDefaults.assistChipColors(
                containerColor = if (backendReady) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.errorContainer,
                labelColor = if (backendReady) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onErrorContainer,
                leadingIconContentColor = if (backendReady) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onErrorContainer,
            ),
            border = null,
        )
        Spacer(Modifier.weight(1f))
        // Both actions share one tonal container so neither reads as the primary action;
        // accessibility shows its state through the icon tint instead of a filled surface.
        FilledTonalIconToggleButton(
            checked = accessibilityConnected,
            onCheckedChange = onAccessibility,
            modifier = Modifier.size(48.dp),
            colors = IconButtonDefaults.filledTonalIconToggleButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                checkedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                checkedContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ),
        ) {
            Icon(Icons.Default.AccessibilityNew,
                l.text("无障碍权限", "Accessibility permission", "無障礙權限"))
        }
        FilledTonalIconButton(
            onClick = onSettings,
            modifier = Modifier.size(48.dp),
            colors = IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        ) {
            Icon(Icons.Default.Settings, l.text("设置", "Settings", "設定"))
        }
    }
}

@Composable
private fun McpServiceCard(
    running: Boolean,
    changing: Boolean,
    status: String,
    title: String,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxSize().padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            StatusChip(status, if (running) StatusTone.Positive else StatusTone.Neutral)
        }
        ScreenPowerScene(running, changing, status, onToggle, Modifier.fillMaxWidth().weight(1f))
        McpServiceButton(running, changing, status, onToggle, Modifier.fillMaxWidth())
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun McpServiceButton(
    running: Boolean,
    changing: Boolean,
    status: String,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val l = LocalLanguage.current
    val containerColor by animateColorAsState(
        if (running) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.primary,
        MaterialTheme.motionScheme.defaultEffectsSpec(), label = "MCP button color",
    )
    val contentColor by animateColorAsState(
        if (running) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onPrimary,
        MaterialTheme.motionScheme.defaultEffectsSpec(), label = "MCP button content",
    )
    Button(
        onClick = { onToggle(!running) },
        enabled = !changing,
        modifier = modifier.heightIn(min = 52.dp).semantics {
            contentDescription = if (running) l.text("关闭 MCP", "Turn off MCP", "關閉 MCP")
                else l.text("开启 MCP", "Turn on MCP", "開啟 MCP")
            stateDescription = status
        },
        shapes = ButtonDefaults.shapes(),
        colors = ButtonDefaults.buttonColors(containerColor = containerColor, contentColor = contentColor),
    ) {
        Icon(Icons.Default.PowerSettingsNew, null, Modifier.size(26.dp))
        Spacer(Modifier.width(10.dp))
        Text(if (running) l.text("关闭服务", "Turn off service", "關閉服務")
            else l.text("开启服务", "Turn on service", "開啟服務"))
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun McpConnectionActions(running: Boolean, onCopy: () -> Unit, onConnect: () -> Unit) {
    val l = LocalLanguage.current
    val leading = ButtonGroupDefaults.connectedLeadingButtonShapes()
    val trailing = ButtonGroupDefaults.connectedTrailingButtonShapes()
    val copy: @Composable (Modifier, MutableInteractionSource?, ButtonShapes) -> Unit = { modifier, interaction, shapes ->
        FilledTonalButton(onClick = onCopy, enabled = running, modifier = modifier.heightIn(min = 48.dp),
            shapes = shapes, interactionSource = interaction,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)) {
            Icon(Icons.Default.ContentCopy, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(l.text("复制配置", "Copy config", "複製設定"), style = MaterialTheme.typography.labelLargeEmphasized,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    val connect: @Composable (Modifier, MutableInteractionSource?, ButtonShapes) -> Unit = { modifier, interaction, shapes ->
        OutlinedButton(onClick = onConnect, modifier = modifier.heightIn(min = 48.dp),
            shapes = shapes, interactionSource = interaction,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)) {
            Text(l.text("连接设置", "Connection", "連線設定"), style = MaterialTheme.typography.labelLargeEmphasized,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.width(6.dp))
            Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(18.dp))
        }
    }
    if (LocalDensity.current.fontScale > 1.15f) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            copy(Modifier.fillMaxWidth(), null, ButtonDefaults.shapes())
            connect(Modifier.fillMaxWidth(), null, ButtonDefaults.shapes())
        }
    } else {
        ButtonGroup(overflowIndicator = { ButtonGroupDefaults.OverflowIndicator(it) },
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
            customItem(buttonGroupContent = {
                val interaction = remember { MutableInteractionSource() }
                copy(Modifier.weight(1f).animateWidth(interaction, 4.dp), interaction, ButtonShapes(leading.shape, leading.pressedShape))
            }, menuContent = { menu ->
                TextButton(onClick = { menu.dismiss(); onCopy() }, enabled = running) { Text(l.text("复制配置", "Copy config", "複製設定")) }
            })
            customItem(buttonGroupContent = {
                val interaction = remember { MutableInteractionSource() }
                connect(Modifier.weight(1f).animateWidth(interaction, 4.dp), interaction, ButtonShapes(trailing.shape, trailing.pressedShape))
            }, menuContent = { menu ->
                TextButton(onClick = { menu.dismiss(); onConnect() }) { Text(l.text("连接设置", "Connection", "連線設定")) }
            })
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun McpStat(value: Int, label: String, modifier: Modifier = Modifier) {
    val animated by animateIntAsState(value, MaterialTheme.motionScheme.defaultSpatialSpec(), label = "client count")
    Column(modifier.padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(animated.coerceAtLeast(0).toString(), style = MaterialTheme.typography.displaySmallEmphasized)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        RunningScreenBackdrop(enabled, Modifier.fillMaxSize())
        Canvas(Modifier.fillMaxSize().clearAndSetSemantics {}) {
            val p = power.value
            val rebound = if(!enabled) abs(p).coerceAtMost(.35f) else 0f
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
                    color = colors.outline,
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
            Box(Modifier.align(Alignment.CenterEnd).offset(x = (-2).dp, y = (-32).dp).size(width = 3.dp, height = 26.dp)
                .background(colors.onSurfaceVariant, RoundedCornerShape(1.5.dp)))
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

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun RunningScreenBackdrop(enabled: Boolean, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.primary
    val opacity = animateFloatAsState(if(enabled) 1f else 0f,
        MaterialTheme.motionScheme.defaultEffectsSpec(), label = "screen ripple visibility")
    // Dispose the repeating animation once the stopped screen has faded out.
    if(enabled || opacity.value > .001f) {
        val transition = rememberInfiniteTransition(label = "running screen ripples")
        val phase = transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(3000, easing = LinearEasing)),
            label = "screen ripple expansion",
        )
        Canvas(modifier.clearAndSetSemantics {}) {
            val alpha = opacity.value.coerceIn(0f, 1f)
            val backgroundRadius = 96.dp.toPx()
            val rippleRadius = 128.dp.toPx()
            drawCircle(color.copy(alpha = .06f * alpha), radius = backgroundRadius, center = center)
            repeat(3) { index ->
                val progress = (phase.value + index / 3f) % 1f
                val arrival = (progress * 6f).coerceAtMost(1f)
                val fade = (1f - progress) * (1f - progress)
                drawCircle(
                    color = color.copy(alpha = .2f * arrival * fade * alpha),
                    radius = rippleRadius * (.65f + .95f * progress),
                    center = center,
                    style = Stroke(width = 1.5.dp.toPx()),
                )
            }
        }
    }
}
