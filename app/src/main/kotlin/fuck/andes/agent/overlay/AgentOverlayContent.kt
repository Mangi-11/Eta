package fuck.andes.agent.overlay

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.R as LucideR
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

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
    CollapsedAgentOrb(state = state, onExpand = onToggleCollapse)
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
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        TaskStatusPanel(
            state = state,
            onCollapse = onCollapse,
            onPause = onPause,
            onResume = onResume,
            onStop = onStop,
            modifier = Modifier
                .widthIn(max = 420.dp)
                .fillMaxWidth(),
        )
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
    modifier: Modifier = Modifier,
) {
    val finalPhase = state.phase == AgentOverlayPhase.FINISHED || state.phase == AgentOverlayPhase.FAILED
    Card(
        modifier = modifier
            .fillMaxWidth()
            .shadow(3.dp, RoundedCornerShape(28.dp)),
        cornerRadius = 28.dp,
        insideMargin = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
    ) {
        StreamingDetailText(
            text = state.detailText.ifBlank { state.statusText },
            phase = state.phase,
            expanded = finalPhase,
        )
        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PhaseActions(
                phase = state.phase,
                onPause = onPause,
                onResume = onResume,
                onStop = onStop,
            )
            Spacer(modifier = Modifier.weight(1f))
            OverlayActionPill(
                text = if (finalPhase) "关闭" else "收起",
                icon = if (finalPhase) LucideR.drawable.lucide_ic_x else LucideR.drawable.lucide_ic_chevron_down,
                primary = false,
                phase = state.phase,
                onClick = if (finalPhase) onStop else onCollapse,
            )
        }
    }
}

@Composable
private fun StreamingDetailText(
    text: String,
    phase: AgentOverlayPhase,
    expanded: Boolean,
    modifier: Modifier = Modifier,
) {
    val heightModifier = if (expanded) {
        Modifier.heightIn(max = 176.dp)
    } else {
        Modifier.height(42.dp)
    }
    Text(
        text = text,
        modifier = modifier
            .fillMaxWidth()
            .then(heightModifier),
        color = if (expanded) {
            MiuixTheme.colorScheme.onSurfaceVariantSummary
        } else {
            phaseAccent(phase)
        },
        fontSize = MiuixTheme.textStyles.footnote1.fontSize,
        lineHeight = 18.sp,
        maxLines = if (expanded) 9 else 2,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun PhaseActions(
    phase: AgentOverlayPhase,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        when (phase) {
            AgentOverlayPhase.RUNNING -> {
                OverlayActionPill(
                    text = "接管",
                    icon = LucideR.drawable.lucide_ic_play,
                    primary = false,
                    phase = phase,
                    onClick = onPause,
                )
                OverlayActionPill(
                    text = "停止",
                    primary = false,
                    phase = phase,
                    onClick = onStop,
                    dotColor = phaseAccent(AgentOverlayPhase.FAILED),
                )
            }

            AgentOverlayPhase.PAUSED -> {
                OverlayActionPill(
                    text = "继续",
                    icon = LucideR.drawable.lucide_ic_play,
                    primary = false,
                    phase = phase,
                    onClick = onResume,
                )
                OverlayActionPill(
                    text = "停止",
                    primary = false,
                    phase = phase,
                    onClick = onStop,
                    dotColor = phaseAccent(AgentOverlayPhase.FAILED),
                )
            }

            AgentOverlayPhase.FINISHED, AgentOverlayPhase.FAILED -> {
                OverlayActionPill(
                    text = stateLabel(phase),
                    primary = false,
                    phase = phase,
                    onClick = onStop,
                    dotColor = phaseAccent(phase),
                )
            }
        }
    }
}

@Composable
private fun OverlayActionPill(
    text: String,
    primary: Boolean,
    phase: AgentOverlayPhase,
    onClick: () -> Unit,
    icon: Int? = null,
    dotColor: Color? = null,
) {
    val accent = phaseAccent(phase)
    val backgroundColor = if (primary) {
        accent
    } else {
        accent.copy(alpha = 0.12f)
    }
    val contentColor = if (primary) {
        Color.White
    } else {
        accent
    }
    Row(
        modifier = Modifier
            .height(32.dp)
            .widthIn(min = 68.dp)
            .clip(RoundedCornerShape(percent = 50))
            .background(backgroundColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            icon != null -> {
                Icon(
                    painter = painterResource(icon),
                    contentDescription = null,
                    modifier = Modifier.size(15.dp),
                    tint = contentColor,
                )
                Spacer(modifier = Modifier.width(6.dp))
            }

            dotColor != null -> {
                StatusDot(color = dotColor, modifier = Modifier.size(15.dp))
                Spacer(modifier = Modifier.width(6.dp))
            }
        }
        Text(
            text = text,
            color = contentColor,
            fontSize = MiuixTheme.textStyles.footnote1.fontSize,
            lineHeight = 16.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun stateLabel(phase: AgentOverlayPhase): String = when (phase) {
    AgentOverlayPhase.RUNNING -> "执行中"
    AgentOverlayPhase.PAUSED -> "已暂停"
    AgentOverlayPhase.FINISHED -> "已完成"
    AgentOverlayPhase.FAILED -> "失败"
}

@Composable
private fun StatusDot(color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.drawBehind {
            drawCircle(color = color, radius = size.minDimension * 0.22f)
        }
    )
}
