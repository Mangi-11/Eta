package fuck.andes.agent.overlay

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.rememberMarkdownState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.R as LucideR
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.ui.layout.Layout

// Miuix 未提供语义 success 色，沿用项目既有值；失败色走主题 error
private val SuccessColor = Color(0xFF34C759)

// 彩虹光圈颜色（青/黄/橙/粉循环）
private val RainbowColors = listOf(
    Color(0xFFB0F2FF),
    Color(0xFFFAFAA3),
    Color(0xFFFFB472),
    Color(0xFFFB8DFF),
    Color(0xFFB0F2FF),
    Color(0xFFFB8DFF),
    Color(0xFFFFB472),
    Color(0xFFFAFAA3),
    Color(0xFFB0F2FF),
)

private const val SupplementExitDelayMs = 380L

@Composable
private fun phaseAccent(phase: AgentOverlayPhase): Color = when (phase) {
    AgentOverlayPhase.RUNNING -> MiuixTheme.colorScheme.primary
    AgentOverlayPhase.PAUSED -> Color(0xFFFF9F0A)
    AgentOverlayPhase.FINISHED -> SuccessColor
    AgentOverlayPhase.FAILED -> MiuixTheme.colorScheme.error
}

/**
 * 屏幕四边氛围光窗口：全屏触摸穿透。
 * - RUNNING：半透明黑底压暗 + 彩虹色旋转 SweepGradient 光圈。
 * - PAUSED：完全透明（让用户操作设备），光球和卡片自身提示暂停。
 * - FINISHED / FAILED：不再绘制氛围光，只保留结果卡片。
 */
@Composable
internal fun AgentOverlayGlow(state: AgentOverlayState) {
    val phase = state.phase
    if (phase != AgentOverlayPhase.RUNNING) return

    val dimAlpha = 0.31f
    val transition = rememberInfiniteTransition(label = "glow")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(5000), RepeatMode.Restart),
        label = "rotation",
    )

    Box(
        modifier = Modifier.fillMaxSize().drawBehind {
            // 半透明黑底压暗
            drawRect(color = Color.Black.copy(alpha = dimAlpha))

            // 彩虹光圈：SweepGradient 描边 + 模糊，全屏 RectF，旋转
            val w = size.width
            val h = size.height
            val cx = w / 2f
            val cy = h / 2f
            val strokePx = 40f
            val colorsArgb = RainbowColors.map { it.toArgb() }
            val positions = floatArrayOf(
                0f, 0.13f, 0.257f, 0.37f, 0.505f, 0.634f, 0.744f, 0.87f, 1f
            )
            drawIntoCanvas { canvas ->
                val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    style = android.graphics.Paint.Style.STROKE
                    strokeWidth = strokePx
                    maskFilter = android.graphics.BlurMaskFilter(
                        strokePx,
                        android.graphics.BlurMaskFilter.Blur.NORMAL,
                    )
                }
                val shader = android.graphics.SweepGradient(cx, cy, colorsArgb.toIntArray(), positions)
                val matrix = android.graphics.Matrix()
                matrix.setRotate(rotation, cx, cy)
                shader.setLocalMatrix(matrix)
                paint.shader = shader
                val rect = android.graphics.RectF(0f, 0f, w, h)
                canvas.nativeCanvas.drawRoundRect(rect, 30f, 30f, paint)
            }
        }
    )
}

/**
 * 助手光球窗口：始终显示在屏幕右侧中下，点击展开/收起底部任务卡片。
 * 独立小窗口（WRAP_CONTENT），不遮挡页面操作。
 */
@Composable
internal fun AgentOverlayOrb(
    state: AgentOverlayState,
    onToggleCollapse: () -> Unit,
) {
    var visible by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        visible = true
    }

    fun animateDismiss(action: () -> Unit) {
        visible = false
        scope.launch {
            delay(180)
            action()
        }
    }

    AnimatedVisibility(
        visible = visible,
        enter = scaleIn(
            initialScale = 0.5f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioLowBouncy,
                stiffness = Spring.StiffnessMediumLow
            )
        ) + fadeIn(animationSpec = tween(durationMillis = 200)),
        exit = scaleOut(
            targetScale = 0.5f,
            animationSpec = tween(durationMillis = 150)
        ) + fadeOut(animationSpec = tween(durationMillis = 150))
    ) {
        CollapsedAgentOrb(state = state, onExpand = { animateDismiss(onToggleCollapse) })
    }
}

/**
 * 底部任务状态面板窗口：仅展开态显示。
 */
@Composable
internal fun AgentOverlayContent(
    state: AgentOverlayState,
    onCollapse: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onSupplementModeChange: (Boolean) -> Unit,
    onSupplement: (String) -> Unit,
) {
    var visible by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun setSupplementMode(active: Boolean) {
        onSupplementModeChange(active)
    }

    LaunchedEffect(Unit) {
        visible = true
    }

    fun animateDismiss(action: () -> Unit) {
        visible = false
        scope.launch {
            delay(220) // Wait for slide-out animation to complete
            action()
        }
    }

    BottomAnchoredContainer(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = 12.dp, end = 12.dp, bottom = 20.dp),
    ) {
        AnimatedVisibility(
            visible = visible,
            enter = slideInVertically(
                initialOffsetY = { it },
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioLowBouncy,
                    stiffness = Spring.StiffnessMediumLow
                )
            ) + fadeIn(animationSpec = tween(durationMillis = 200)),
            exit = slideOutVertically(
                targetOffsetY = { it },
                animationSpec = tween(durationMillis = 180)
            ) + fadeOut(animationSpec = tween(durationMillis = 180))
        ) {
            TaskStatusPanel(
                state = state,
                onCollapse = { animateDismiss(onCollapse) },
                onPause = onPause,
                onResume = onResume,
                onStop = { animateDismiss(onStop) },
                onSupplementModeChange = ::setSupplementMode,
                onSupplement = onSupplement,
                modifier = Modifier
                    .widthIn(max = 420.dp)
                    .fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun CollapsedAgentOrb(state: AgentOverlayState, onExpand: () -> Unit) {
    AssistantOrb(phase = state.phase, onClick = onExpand)
}

/**
 * 助手光球：外层径向光晕 + 实心球体 + 高光点。
 * 运行中光晕呼吸，暂停/完成/失败静止，颜色随阶段变化。
 */
@Composable
private fun AssistantOrb(
    phase: AgentOverlayPhase,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val accent = phaseAccent(phase)
    val pulsing = phase == AgentOverlayPhase.RUNNING
    val transition = rememberInfiniteTransition(label = "orb")
    val pulse by transition.animateFloat(
        initialValue = 0.6f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1400), RepeatMode.Reverse),
        label = "pulse",
    )
    val haloAlpha = if (pulsing) pulse else 0.85f
    val tapModifier = if (onClick != null) Modifier.clickable { onClick() } else Modifier
    Box(
        modifier = modifier
            .then(tapModifier)
            .size(56.dp)
            .drawBehind {
                val outer = size.minDimension
                val center = Offset(outer / 2f, outer / 2f)
                // 外光晕
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(accent.copy(alpha = 0.5f * haloAlpha), Color.Transparent),
                        center = center,
                        radius = outer / 2f,
                    )
                )
                // 球体
                val ballRadius = outer * 0.3f
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(accent, accent.copy(alpha = 0.8f)),
                        center = Offset(center.x - ballRadius * 0.3f, center.y - ballRadius * 0.3f),
                        radius = ballRadius,
                    ),
                    radius = ballRadius,
                    center = center,
                )
                // 高光
                drawCircle(
                    color = Color.White.copy(alpha = 0.55f),
                    radius = ballRadius * 0.3f,
                    center = Offset(center.x - ballRadius * 0.32f, center.y - ballRadius * 0.38f),
                )
            }
    )
}

/**
 * 底部任务状态卡片：详情流在上，状态和动作固定在底部，增长时只向上展开。
 */
@Composable
private fun TaskStatusPanel(
    state: AgentOverlayState,
    onCollapse: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onSupplementModeChange: (Boolean) -> Unit,
    onSupplement: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val finalPhase = state.phase == AgentOverlayPhase.FINISHED || state.phase == AgentOverlayPhase.FAILED
    var supplementMode by remember { mutableStateOf(false) }
    var supplementText by remember { mutableStateOf("") }
    var supplementTransitionRevision by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    fun enterSupplementMode() {
        supplementTransitionRevision += 1
        supplementMode = true
    }

    fun exitSupplementMode() {
        supplementTransitionRevision += 1
        val revision = supplementTransitionRevision
        supplementMode = false
        supplementText = ""
        scope.launch {
            delay(SupplementExitDelayMs)
            if (supplementTransitionRevision == revision) {
                onSupplementModeChange(false)
            }
        }
    }

    fun closeSupplementMode() {
        focusManager.clearFocus(force = true)
        keyboard?.hide()
        scope.launch {
            delay(80)
            exitSupplementMode()
        }
    }

    fun submitSupplement() {
        val text = supplementText.trim()
        if (text.isBlank()) return
        focusManager.clearFocus(force = true)
        keyboard?.hide()
        scope.launch {
            delay(80)
            exitSupplementMode()
            onSupplement(text)
        }
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMedium,
                )
            )
            .shadow(3.dp, RoundedCornerShape(24.dp)),
        cornerRadius = 24.dp,
        insideMargin = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
    ) {
        DoubaoStatusText(
            text = if (state.phase == AgentOverlayPhase.RUNNING || state.phase == AgentOverlayPhase.PAUSED) {
                state.statusText
            } else {
                state.detailText.ifBlank { state.statusText }
            },
            phase = state.phase
        )
        Spacer(modifier = Modifier.height(12.dp))

        AnimatedVisibility(
            visible = supplementMode,
            enter = expandVertically(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMedium,
                )
            ) + fadeIn(animationSpec = tween(durationMillis = 140)),
            exit = shrinkVertically(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMedium,
                )
            ) + fadeOut(animationSpec = tween(durationMillis = 100)),
        ) {
            SupplementInput(
                value = supplementText,
                onValueChange = { supplementText = it },
                onCancel = ::closeSupplementMode,
                onSend = ::submitSupplement,
                onFocusReady = { onSupplementModeChange(true) }
            )
        }

        AnimatedVisibility(
            visible = !supplementMode,
            enter = expandVertically(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMedium,
                )
            ) + fadeIn(animationSpec = tween(durationMillis = 140)),
            exit = shrinkVertically(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMedium,
                )
            ) + fadeOut(animationSpec = tween(durationMillis = 100)),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DoubaoActionPill(
                        text = "补充",
                        icon = LucideR.drawable.lucide_ic_message_square,
                        onClick = ::enterSupplementMode,
                    )
                    if (state.phase == AgentOverlayPhase.RUNNING) {
                        DoubaoActionPill(
                            text = "接管",
                            icon = LucideR.drawable.lucide_ic_hand,
                            onClick = onPause
                        )
                    } else if (state.phase == AgentOverlayPhase.PAUSED) {
                        DoubaoActionPill(
                            text = "继续",
                            icon = LucideR.drawable.lucide_ic_play,
                            onClick = onResume
                        )
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                DoubaoActionPill(
                    text = if (finalPhase) "关闭" else "停止",
                    icon = if (finalPhase) LucideR.drawable.lucide_ic_x else LucideR.drawable.lucide_ic_circle_stop,
                    onClick = onStop
                )
            }
        }
    }
}

@Composable
private fun SupplementInput(
    value: String,
    onValueChange: (String) -> Unit,
    onCancel: () -> Unit,
    onSend: () -> Unit,
    onFocusReady: () -> Unit,
) {
    val isDark = isSystemInDarkTheme()
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val textColor = if (isDark) Color(0xFFE3E5E8) else Color(0xFF202326)
    val fieldBg = if (isDark) Color(0xFF202329) else Color(0xFFF7F8FA)

    LaunchedEffect(Unit) {
        delay(180)
        onFocusReady()
        delay(20) // Give WindowManager time to update focusable flags
        focusRequester.requestFocus()
        keyboard?.show()
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp, max = 112.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(fieldBg)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            contentAlignment = Alignment.TopStart,
        ) {
            if (value.isBlank()) {
                Text(
                    text = "补充要求，Agent 会基于当前任务继续",
                    color = textColor.copy(alpha = 0.45f),
                    fontSize = 14.sp,
                    lineHeight = 18.sp,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
                textStyle = TextStyle(
                    color = textColor,
                    fontSize = 14.sp,
                    lineHeight = 18.sp,
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
                maxLines = 4,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DoubaoActionPill(
                text = "取消",
                icon = LucideR.drawable.lucide_ic_x,
                onClick = onCancel,
            )
            Spacer(modifier = Modifier.width(8.dp))
            DoubaoActionPill(
                text = "发送",
                icon = LucideR.drawable.lucide_ic_circle_arrow_up,
                enabled = value.isNotBlank(),
                onClick = onSend,
            )
        }
    }
}

@Composable
private fun DoubaoStatusText(
    text: String,
    phase: AgentOverlayPhase,
    modifier: Modifier = Modifier,
) {
    val isRunningOrPaused = phase == AgentOverlayPhase.RUNNING || phase == AgentOverlayPhase.PAUSED
    val isDark = isSystemInDarkTheme()

    // Vibrant colors for running/paused status; standard neutral colors for final output text
    val textColor = if (isRunningOrPaused) {
        if (phase == AgentOverlayPhase.RUNNING) Color(0xFFFA4D7B) else Color(0xFFFF9F0A)
    } else {
        if (isDark) Color(0xFFE3E5E8) else Color(0xFF202326)
    }

    if (isRunningOrPaused) {
        Text(
            text = text,
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 2.dp, vertical = 2.dp),
            color = textColor,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            lineHeight = 20.sp,
            overflow = TextOverflow.Ellipsis,
        )
    } else {
        val markdownState = rememberMarkdownState(content = text, retainState = true)
        val markdownTypography = markdownTypography(
            h1 = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold, color = textColor),
            h2 = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Bold, color = textColor),
            h3 = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold, color = textColor),
            text = TextStyle(fontSize = 14.sp, color = textColor),
            paragraph = TextStyle(fontSize = 14.sp, color = textColor),
            ordered = TextStyle(fontSize = 14.sp, color = textColor),
            bullet = TextStyle(fontSize = 14.sp, color = textColor),
            list = TextStyle(fontSize = 14.sp, color = textColor),
            code = TextStyle(fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = textColor)
        )

        Markdown(
            markdownState = markdownState,
            typography = markdownTypography,
            modifier = modifier
                .fillMaxWidth()
                .heightIn(max = 240.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 2.dp, vertical = 2.dp),
            loading = {
                Text(text = text, color = textColor, fontSize = 14.sp, modifier = it)
            },
            error = {
                Text(text = text, color = textColor, fontSize = 14.sp, modifier = it)
            }
        )
    }
}

@Composable
private fun DoubaoActionPill(
    text: String,
    icon: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val isDark = isSystemInDarkTheme()
    val bgColor = if (isDark) Color(0xFF2C2F36) else Color(0xFFF0F2F5)
    val contentColor = if (isDark) Color(0xFFE3E5E8) else Color(0xFF202326)
    val alpha = if (enabled) 1f else 0.45f

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(bgColor)
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = text,
            tint = contentColor.copy(alpha = alpha),
            modifier = Modifier.size(14.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = text,
            color = contentColor.copy(alpha = alpha),
            fontSize = 12.5.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
internal fun BottomAnchoredContainer(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Layout(
        content = content,
        modifier = modifier
    ) { measurables, constraints ->
        val childConstraints = constraints.copy(minHeight = 0)
        val placeables = measurables.map { it.measure(childConstraints) }
        val height = placeables.maxOfOrNull { it.height } ?: 0
        val layoutHeight = if (constraints.hasFixedHeight) {
            constraints.maxHeight
        } else {
            height
        }
        val layoutWidth = if (constraints.hasFixedWidth) {
            constraints.maxWidth
        } else {
            placeables.maxOfOrNull { it.width } ?: 0
        }
        layout(layoutWidth, layoutHeight) {
            placeables.forEach { placeable ->
                val y = layoutHeight - placeable.height
                placeable.placeRelative(0, y)
            }
        }
    }
}
