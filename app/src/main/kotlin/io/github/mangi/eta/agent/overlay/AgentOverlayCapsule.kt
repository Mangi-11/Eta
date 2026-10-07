package io.github.mangi.eta.agent.overlay

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.mangi.eta.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 顶部状态胶囊：收起时只显示一句当前步骤，点击展开补充、暂停与停止。
 * 位置固定在状态栏下方居中，不随 Agent 的点击位置移动，避免和目标控件重叠。
 * 状态文字只在语义变化时以上滑淡入切换，流式正文不进入胶囊。
 */
@Composable
internal fun AgentOverlayCapsule(
    state: AgentOverlayState,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onCollapsedSettled: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onSupplementModeChange: (Boolean) -> Unit,
    onSupplement: (String) -> Unit,
) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }
    var supplementMode by remember { mutableStateOf(false) }
    var supplementText by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    fun setSupplementMode(enabled: Boolean) {
        onSupplementModeChange(enabled)
        supplementMode = enabled
        if (!enabled) supplementText = ""
    }

    // 收起键盘后再切换窗口焦点，避免输入法在窗口失焦瞬间闪退或残留。
    fun leaveSupplementMode(then: () -> Unit = {}) {
        focusManager.clearFocus(force = true)
        keyboard?.hide()
        scope.launch {
            delay(80)
            setSupplementMode(false)
            then()
        }
    }

    LaunchedEffect(expanded) {
        if (!expanded && supplementMode) leaveSupplementMode()
    }

    val accent by animateColorAsState(overlayPhaseAccent(state.phase), tween(240), label = "capsule_accent")
    val statusText = state.status.localizedText()
    val expandDescription = stringResource(R.string.overlay_capsule_toggle, statusText)
    // 展开进度只驱动绘制层的透明度与位移，不参与测量；控制区用 AnimatedVisibility 只在两端各测一次。
    val expansion by animateFloatAsState(
        targetValue = if (expanded) 1f else 0f,
        animationSpec = spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMediumLow),
        label = "capsule_expansion",
        finishedListener = { value -> if (value == 0f) onCollapsedSettled() },
    )

    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(spring(Spring.DampingRatioLowBouncy, Spring.StiffnessMediumLow)) { -it } +
            fadeIn(tween(160)),
        exit = slideOutVertically(tween(160)) { -it } + fadeOut(tween(140)),
    ) {
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            Column(
                modifier = Modifier
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .width(CapsuleWidth)
                    .graphicsLayer {
                        // 点击时轻微回弹，展开后保持原尺寸；只改绘制层，不触发重新布局。
                        val press = 1f - 0.03f * (1f - expansion) * expansion * 4f
                        scaleX = press
                        scaleY = press
                    }
                    .shadow(10.dp, RoundedCornerShape(CapsuleCorner), ambientColor = accent, spotColor = accent)
                    .clip(RoundedCornerShape(CapsuleCorner))
                    .background(MiuixTheme.colorScheme.surfaceContainer.copy(alpha = 0.94f)),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onToggleExpanded,
                        )
                        .semantics { contentDescription = expandDescription }
                        .padding(start = 12.dp, end = 16.dp, top = 9.dp, bottom = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PhaseIndicator(phase = state.phase, accent = accent)
                    Spacer(Modifier.width(9.dp))
                    Column(modifier = Modifier.weight(1f)) {
                    AnimatedContent(
                        targetState = statusText,
                        transitionSpec = {
                            (slideInVertically(tween(220)) { it / 2 } + fadeIn(tween(220))) togetherWith
                                (slideOutVertically(tween(180)) { -it / 2 } + fadeOut(tween(160))) using
                                SizeTransform(clip = true) { _, _ -> snap() }
                        },
                        label = "capsule_status",
                    ) { text ->
                        Text(
                            text = text,
                            color = MiuixTheme.colorScheme.onSurface,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    ThoughtLine(thought = state.thought.takeIf { state.phase == AgentOverlayPhase.RUNNING }.orEmpty())
                    }
                }

                AnimatedVisibility(
                    visible = expanded,
                    enter = expandVertically(spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMediumLow), clip = true) +
                        fadeIn(tween(180, delayMillis = 40)),
                    exit = shrinkVertically(spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMedium), clip = true) +
                        fadeOut(tween(90)),
                ) {
                    Column(
                        modifier = Modifier
                            .padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
                            .graphicsLayer { translationY = (1f - expansion) * -6.dp.toPx() },
                    ) {
                        if (supplementMode) {
                            OverlaySupplementInput(
                                value = supplementText,
                                onValueChange = { supplementText = it },
                                onCancel = { leaveSupplementMode() },
                                onSend = {
                                    val text = supplementText.trim()
                                    if (text.isNotBlank()) leaveSupplementMode { onSupplement(text) }
                                },
                            )
                        } else {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                            ) {
                                CapsuleAction(
                                    icon = Icons.Rounded.Edit,
                                    label = stringResource(R.string.overlay_supplement),
                                    tint = MiuixTheme.colorScheme.onSurface,
                                    onClick = { setSupplementMode(true) },
                                )
                                if (state.phase == AgentOverlayPhase.RUNNING) {
                                    CapsuleAction(
                                        icon = Icons.Rounded.Pause,
                                        label = stringResource(R.string.overlay_pause),
                                        tint = MiuixTheme.colorScheme.onSurface,
                                        onClick = onPause,
                                    )
                                } else if (state.phase == AgentOverlayPhase.PAUSED) {
                                    CapsuleAction(
                                        icon = Icons.Rounded.PlayArrow,
                                        label = stringResource(R.string.overlay_resume),
                                        tint = MiuixTheme.colorScheme.primary,
                                        onClick = onResume,
                                    )
                                }
                                CapsuleAction(
                                    icon = Icons.Rounded.Stop,
                                    label = stringResource(R.string.action_stop),
                                    tint = MiuixTheme.colorScheme.error,
                                    onClick = onStop,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 运行中是旋转的弧形光点，暂停为静止空心环，结束为实心点；只在运行时持有帧动画。 */
@Composable
private fun PhaseIndicator(phase: AgentOverlayPhase, accent: Color) {
    val size = 14.dp
    if (phase != AgentOverlayPhase.RUNNING) {
        Box(
            modifier = Modifier
                .size(size)
                .drawBehind {
                    val radius = this.size.minDimension / 2f
                    if (phase == AgentOverlayPhase.PAUSED) {
                        drawCircle(accent, radius * 0.78f, style = androidx.compose.ui.graphics.drawscope.Stroke(radius * 0.32f))
                    } else {
                        drawCircle(accent, radius * 0.6f)
                    }
                },
        )
        return
    }
    val transition = rememberInfiniteTransition(label = "capsule_indicator")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1100, easing = androidx.compose.animation.core.LinearEasing)),
        label = "capsule_indicator_rotation",
    )
    val breathe by transition.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "capsule_indicator_breathe",
    )
    Box(
        modifier = Modifier
            .size(size)
            .graphicsLayer { rotationZ = rotation }
            .drawBehind {
                val radius = this.size.minDimension / 2f
                val center = Offset(radius, radius)
                drawCircle(accent.copy(alpha = 0.18f), radius)
                drawArc(
                    brush = Brush.sweepGradient(listOf(Color.Transparent, accent), center),
                    startAngle = 0f,
                    sweepAngle = 300f,
                    useCenter = false,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(radius * 0.34f, cap = androidx.compose.ui.graphics.StrokeCap.Round),
                )
                drawCircle(accent.copy(alpha = breathe), radius * 0.26f, center)
            },
    )
}

/**
 * 思考尾句：单行、从左侧裁掉旧内容，让最新的字始终可见。
 * 流式增量只改文字，不做逐字动画；行的出现与消失用高度不变的淡入淡出，避免胶囊上下跳动。
 */
@Composable
private fun ThoughtLine(thought: String) {
    val shown = thought.isNotBlank()
    val alpha by animateFloatAsState(if (shown) 1f else 0f, tween(220), label = "thought_alpha")
    var lastThought by remember { mutableStateOf("") }
    if (shown) lastThought = thought
    Text(
        text = lastThought,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        maxLines = 1,
        // 起始端省略：保留尾部最新的推理，像滚动字幕一样向前推进。
        overflow = TextOverflow.StartEllipsis,
        modifier = Modifier
            .padding(top = 1.dp)
            .graphicsLayer { this.alpha = alpha },
    )
}

@Composable
private fun CapsuleAction(icon: ImageVector, label: String, tint: Color, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        backgroundColor = MiuixTheme.colorScheme.surfaceContainerHigh,
        minWidth = 40.dp,
        minHeight = 34.dp,
        cornerRadius = 17.dp,
    ) {
        Icon(imageVector = icon, contentDescription = label, modifier = Modifier.size(17.dp), tint = tint)
    }
}

private val CapsuleCorner = 22.dp
private val CapsuleWidth = 248.dp
