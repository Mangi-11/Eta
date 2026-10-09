package io.github.mangi.eta.agent.overlay

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.Path
import top.yukonga.miuix.kmp.squircle.addSquircleRect
import top.yukonga.miuix.kmp.basic.IconButton
import androidx.compose.material.icons.rounded.ContentCopy
import kotlinx.coroutines.launch
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material.icons.rounded.Check
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.mangi.eta.ui.markdown.MarkdownTone
import io.github.mangi.eta.ui.markdown.StaticMarkdown
import io.github.mangi.eta.R
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

// Miuix 未提供语义 success 色，沿用项目既有值；失败色走主题 error
private val SuccessColor = Color(0xFF34C759)


@Composable
internal fun overlayPhaseAccent(phase: AgentOverlayPhase): Color = when (phase) {
    AgentOverlayPhase.RUNNING -> MiuixTheme.colorScheme.primary
    AgentOverlayPhase.PAUSED -> Color(0xFFFF9F0A)
    AgentOverlayPhase.FINISHED -> SuccessColor
    AgentOverlayPhase.FAILED -> MiuixTheme.colorScheme.error
}

// 边缘流光取主题强调色与相邻冷暖色，避免高饱和彩虹在浅色页面上喧宾夺主。
private val EdgeLightColors = listOf(
    Color(0xFF7AB8FF),
    Color(0xFFB59CFF),
    Color(0xFFFF9EC7),
    Color(0xFFFFC98A),
    Color(0xFF7AB8FF),
)

/**
 * 屏幕边缘光窗口：全屏触摸穿透（FLAG_NOT_TOUCHABLE），不压暗页面，不挡操作。
 * 窗口类型 TYPE_ACCESSIBILITY_OVERLAY，截图时被 takeScreenshotOfWindow 过滤，对 Agent 透明。
 * - RUNNING：沿屏幕圆角边缘流动的细光带，外侧一层柔光；告诉用户手机正被接管。
 * - PAUSED：静止的淡色描边，提示任务仍在、控制权暂回用户。
 * - FINISHED / FAILED：不绘制。
 */
/**
 * 屏幕圆角半径（像素）。Display 只按圆弧上报一个半径，实际面板是连续曲率的圆角；
 * 光带用同一半径画连续曲线，转角处比圆弧更饱满，贴住屏幕边缘而不是在角上内缩一截。
 */
@Immutable
internal data class ScreenCornerRadii(val topLeft: Float, val topRight: Float, val bottomRight: Float, val bottomLeft: Float) {
    /** 四角在主流机型上一致；取最大值作为连续曲线的转角尺寸，不让任何一角露出直边。 */
    val radius: Float get() = maxOf(topLeft, topRight, bottomRight, bottomLeft)
}

@Composable
internal fun AgentOverlayGlow(state: AgentOverlayState, corners: ScreenCornerRadii) {
    val phase = state.phase
    if (phase != AgentOverlayPhase.RUNNING && phase != AgentOverlayPhase.PAUSED) return
    val running = phase == AgentOverlayPhase.RUNNING
    val pausedAccent = overlayPhaseAccent(AgentOverlayPhase.PAUSED)
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val reveal by animateFloatAsState(if (shown) 1f else 0f, tween(420), label = "edge_reveal")

    val rotation = if (running) {
        val transition = rememberInfiniteTransition(label = "edge_light")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(4200, easing = LinearEasing), RepeatMode.Restart),
            label = "edge_rotation",
        ).value
    } else 0f

    Box(
        modifier = Modifier.fillMaxSize().drawBehind {
            val density = this.density
            val inset = 1.5f * density
            // 光带沿屏幕轮廓的连续曲线走：路径向内收 inset，转角同步减小，保证与屏幕边缘等距。
            val edge = Path().apply {
                addSquircleRect(
                    width = size.width - inset * 2,
                    height = size.height - inset * 2,
                    cornerRadius = (corners.radius - inset).coerceAtLeast(0f),
                )
                translate(Offset(inset, inset))
            }.asAndroidPath()
            drawIntoCanvas { canvas ->
                if (!running) {
                    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                        style = android.graphics.Paint.Style.STROKE
                        strokeWidth = 3f * density
                        color = pausedAccent.copy(alpha = 0.55f * reveal).toArgb()
                    }
                    canvas.nativeCanvas.drawPath(edge, paint)
                    return@drawIntoCanvas
                }
                val shader = android.graphics.SweepGradient(
                    size.width / 2f, size.height / 2f, EdgeLightColors.map { it.toArgb() }.toIntArray(), null,
                ).apply {
                    setLocalMatrix(android.graphics.Matrix().apply { setRotate(rotation, size.width / 2f, size.height / 2f) })
                }
                // 外层柔光与内层亮线共用同一个旋转渐变，光带看起来是一体流动的。
                val halo = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    style = android.graphics.Paint.Style.STROKE
                    strokeWidth = 14f * density
                    maskFilter = android.graphics.BlurMaskFilter(12f * density, android.graphics.BlurMaskFilter.Blur.NORMAL)
                    this.shader = shader
                    alpha = (0.55f * reveal * 255).toInt()
                }
                val line = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    style = android.graphics.Paint.Style.STROKE
                    strokeWidth = 3f * density
                    this.shader = shader
                    alpha = (0.95f * reveal * 255).toInt()
                }
                canvas.nativeCanvas.drawPath(edge, halo)
                canvas.nativeCanvas.drawPath(edge, line)
            }
        }
    )
}

@Composable
internal fun OverlaySupplementInput(
    value: String,
    onValueChange: (String) -> Unit,
    onCancel: () -> Unit,
    onSend: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val textColor = MiuixTheme.colorScheme.onSurface
    val fieldBg = MiuixTheme.colorScheme.surfaceContainer

    LaunchedEffect(Unit) {
        delay(180)
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
                    text = stringResource(R.string.overlay_supplement_hint),
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
            TextButton(
                text = stringResource(R.string.action_cancel),
                onClick = onCancel,
                minWidth = 44.dp,
                minHeight = 32.dp,
                insideMargin = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            TextButton(
                text = stringResource(R.string.overlay_send),
                onClick = onSend,
                enabled = value.isNotBlank(),
                minWidth = 44.dp,
                minHeight = 32.dp,
                insideMargin = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}

/**
 * 任务结束后的结果卡片：底部浮起的圆角面板，顶部抓手提示可下滑关闭。
 * 关闭途径有三条：下滑、系统返回（由 Service 注册）、底部"完成"按钮；
 * 次要操作"复制"放在同一行，正文可滚动，过长时底部渐隐提示还有内容。
 */
@Composable
internal fun AgentResultCard(
    state: AgentOverlayState,
    onClose: () -> Unit,
    onOpenEta: () -> Unit,
) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }
    val scope = rememberCoroutineScope()
    fun close() {
        if (!visible) return
        visible = false
        // 等退场动画结束再移除窗口，避免卡片被瞬间抽走。
        scope.launch {
            delay(ResultCardExitMs.toLong())
            onClose()
        }
    }

    val isFailed = state.phase == AgentOverlayPhase.FAILED
    val accent = overlayPhaseAccent(state.phase)
    val statusLabel = stringResource(
        if (isFailed) R.string.overlay_substatus_failed else R.string.overlay_substatus_finished,
    )
    val content = state.detailText.ifBlank { state.status.localizedText() }
    val density = LocalDensity.current
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val settledOffset by animateFloatAsState(
        targetValue = dragOffset,
        animationSpec = spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMedium),
        label = "result_drag",
    )
    val dismissThreshold = with(density) { 96.dp.toPx() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .padding(start = 10.dp, end = 10.dp, bottom = 10.dp),
    ) {
        AnimatedVisibility(
            visible = visible,
            enter = slideInVertically(spring(Spring.DampingRatioLowBouncy, Spring.StiffnessMediumLow)) { it } +
                fadeIn(tween(200)),
            exit = slideOutVertically(tween(ResultCardExitMs)) { it } + fadeOut(tween(ResultCardExitMs)),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .graphicsLayer { translationY = settledOffset }
                    .shadow(16.dp, RoundedCornerShape(ResultCardCorner), ambientColor = Color.Black.copy(alpha = 0.18f))
                    .clip(RoundedCornerShape(ResultCardCorner))
                    .background(MiuixTheme.colorScheme.surface)
                    .pointerInput(Unit) {
                        detectVerticalDragGestures(
                            onDragEnd = { if (dragOffset > dismissThreshold) close() else dragOffset = 0f },
                            onDragCancel = { dragOffset = 0f },
                        ) { change, amount ->
                            change.consume()
                            // 只允许向下拖；向上带阻尼，避免卡片被拖离底边。
                            dragOffset = (dragOffset + amount).coerceAtLeast(0f)
                        }
                    },
            ) {
                Box(
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .align(Alignment.CenterHorizontally)
                        .size(width = 36.dp, height = 4.dp)
                        .clip(CircleShape)
                        .background(MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.3f)),
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(accent.copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = if (isFailed) Icons.Rounded.Close else Icons.Rounded.Check,
                            contentDescription = null,
                            modifier = Modifier.size(13.dp),
                            tint = accent,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = statusLabel,
                        color = MiuixTheme.colorScheme.onSurface,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }

                Box(modifier = Modifier.weight(1f, fill = false)) {
                    val scroll = rememberScrollState()
                    StaticMarkdown(
                        content = content,
                        tone = MarkdownTone.Answer,
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(scroll)
                            .padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 12.dp),
                    )
                    if (scroll.canScrollForward) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .height(28.dp)
                                .background(
                                    Brush.verticalGradient(
                                        listOf(Color.Transparent, MiuixTheme.colorScheme.surface),
                                    ),
                                ),
                        )
                    }
                }

                ResultCardActions(content = content, onOpenEta = onOpenEta, onDone = ::close)
            }
        }
    }
}

@Composable
private fun ResultCardActions(content: String, onOpenEta: () -> Unit, onDone: () -> Unit) {
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1_400)
            copied = false
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // 复制是图标按钮，主次两个文字按钮分别是"完成"（留在当前应用）与"回到 Eta"（查看完整过程）。
        IconButton(
            onClick = {
                clipboard.setText(AnnotatedString(content))
                copied = true
            },
            backgroundColor = MiuixTheme.colorScheme.secondaryContainer,
            minWidth = 44.dp,
            minHeight = 44.dp,
            cornerRadius = 22.dp,
        ) {
            Icon(
                imageVector = if (copied) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
                contentDescription = stringResource(if (copied) R.string.copy_copied else R.string.ui_copy_4edd1d),
                modifier = Modifier.size(18.dp),
                tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
            )
        }
        TextButton(
            text = stringResource(R.string.overlay_result_done),
            onClick = onDone,
            modifier = Modifier.weight(1f),
            minHeight = 44.dp,
        )
        TextButton(
            text = stringResource(R.string.overlay_result_open_eta),
            onClick = onOpenEta,
            modifier = Modifier.weight(1f),
            minHeight = 44.dp,
            colors = ButtonDefaults.textButtonColorsPrimary(),
        )
    }
}

private val ResultCardCorner = 28.dp
private const val ResultCardExitMs = 200
