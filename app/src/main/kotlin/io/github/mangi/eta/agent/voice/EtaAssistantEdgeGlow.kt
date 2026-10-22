package io.github.mangi.eta.agent.voice

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import kotlinx.coroutines.isActive
import kotlin.math.max
import kotlin.math.sin

private const val POWER_BUTTON_HEIGHT_RATIO = 0.358f
private const val POWER_HALO_HOLD_SECONDS = 0.08f
private const val POWER_HALO_MOVE_SECONDS = 0.624f
private const val POWER_HALO_END_SECONDS = 0.92f
private const val EDGE_TRAIL_START_SECONDS = 0.17f
internal const val ASSISTANT_EDGE_ARRIVAL_MS = 270L
private const val BOTTOM_REVEAL_SECONDS = 0.30f
private val edgeArrivalSeconds = ASSISTANT_EDGE_ARRIVAL_MS / 1000f
private val bottomRevealEndSeconds = edgeArrivalSeconds + BOTTOM_REVEAL_SECONDS
private val entranceDurationSeconds = max(POWER_HALO_END_SECONDS, bottomRevealEndSeconds)
private val haloEasing = CubicBezierEasing(0.33f, 0f, 0.67f, 1f)
private val powerWashColor = Color(0xFF9BB2D2).copy(alpha = 0.36f)
private val edgeTrailColors = listOf(Color.Transparent, Color(0xFF9CAEE8), Color(0xFFFFC99A))
private val edgeCoreColors = listOf(Color.Transparent, Color(0xFFDAE5FF), Color(0xFFFFF7E3))
private val edgeHeadColors = listOf(Color(0xFFFFF7E3), Color(0xFFFFC99A), Color.Transparent)
private val bottomGlowColors = listOf(
    listOf(Color(0xFF71BEFF).copy(alpha = 0.68f), Color.Transparent),
    listOf(Color(0xFFFFFAF0).copy(alpha = 0.68f), Color.Transparent),
    listOf(Color(0xFFF27BDE).copy(alpha = 0.68f), Color.Transparent),
)
private val bottomLineColors = listOf(
    Color(0xFF8CCBFF), Color(0xFFFFF7E8), Color(0xFFFF8CCB), Color(0xFFADB8FF),
)

/** 初次唤醒从电源键位置沿屏幕边缘进入；后续手动切回语音只恢复底部光效。 */
@Composable
internal fun EtaAssistantEdgeGlow(
    active: Boolean,
    onEntranceFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var firstWakeComplete by remember { mutableStateOf(false) }
    val entranceFinished by rememberUpdatedState(onEntranceFinished)
    val opacity = animateFloatAsState(
        targetValue = if (active || !firstWakeComplete) 1f else 0f,
        animationSpec = tween(if (active || !firstWakeComplete) 110 else 220),
        label = "assistant_edge_glow",
    )
    var seconds by remember { mutableFloatStateOf(0f) }
    val clock = remember { longArrayOf(0L) }
    LaunchedEffect(active) {
        if (!active && firstWakeComplete) return@LaunchedEffect
        while (isActive) {
            withFrameNanos { now ->
                if (clock[0] == 0L) clock[0] = now
                seconds = (now - clock[0]) / 1_000_000_000f
                if (!firstWakeComplete && seconds >= entranceDurationSeconds) {
                    firstWakeComplete = true
                    entranceFinished()
                }
            }
            if (!active && firstWakeComplete) break
        }
    }
    Canvas(modifier.fillMaxSize()) {
        val alpha = opacity.value
        if (alpha <= 0f) return@Canvas
        val width = size.width
        val height = size.height
        val wakeEntry = !firstWakeComplete && height > width
        val powerOrigin = Offset(width, height * POWER_BUTTON_HEIGHT_RATIO)
        val edgeProgress = ((seconds - EDGE_TRAIL_START_SECONDS) /
            (edgeArrivalSeconds - EDGE_TRAIL_START_SECONDS)).coerceIn(0f, 1f)
        val bottomEntry = if (wakeEntry) {
            ((seconds - edgeArrivalSeconds) / BOTTOM_REVEAL_SECONDS).coerceIn(0f, 1f)
        } else {
            1f
        }

        if (wakeEntry && seconds < POWER_HALO_END_SECONDS) {
            val earlyProgress = (seconds / POWER_HALO_HOLD_SECONDS).coerceIn(0f, 1f)
            val lateProgress = haloEasing.transform(
                ((seconds - POWER_HALO_HOLD_SECONDS) /
                    (POWER_HALO_END_SECONDS - POWER_HALO_HOLD_SECONDS)).coerceIn(0f, 1f),
            )
            val haloSpace = if (seconds < POWER_HALO_HOLD_SECONDS) {
                200f + 650f * earlyProgress
            } else {
                850f + 950f * lateProgress
            }
            val haloFeather = if (seconds < POWER_HALO_HOLD_SECONDS) {
                150f + 50f * earlyProgress
            } else {
                200f + 1000f * lateProgress
            }
            val radiusScale = density / 3f
            val radius = (haloSpace + haloFeather) * radiusScale
            val centerYProgress = haloEasing.transform((seconds / POWER_HALO_MOVE_SECONDS).coerceIn(0f, 1f))
            val center = Offset(
                width * 1.325f,
                powerOrigin.y + (height * 0.5f - powerOrigin.y) * 0.6f * centerYProgress,
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colorStops = arrayOf(
                        0f to powerWashColor,
                        haloSpace / (haloSpace + haloFeather) to powerWashColor,
                        1f to Color.Transparent,
                    ),
                    center = center,
                    radius = radius,
                ),
                center = center,
                radius = radius,
                alpha = alpha * (if (seconds < POWER_HALO_HOLD_SECONDS) 1f else 1f - lateProgress),
            )
        }
        if (wakeEntry && seconds >= EDGE_TRAIL_START_SECONDS && seconds < edgeArrivalSeconds + 0.10f) {
            val travel = 1f - (1f - edgeProgress) * (1f - edgeProgress)
            val headY = powerOrigin.y + (height - powerOrigin.y) * travel
            val tailY = (headY - height * 0.43f).coerceAtLeast(powerOrigin.y - height * 0.04f)
            val trailFade = (1f - (seconds - edgeArrivalSeconds) / 0.10f).coerceIn(0f, 1f)
            drawLine(
                brush = Brush.verticalGradient(
                    edgeTrailColors,
                    startY = tailY,
                    endY = headY.coerceAtLeast(tailY + 1f),
                ),
                start = Offset(width - 2f * density, tailY),
                end = Offset(width - 2f * density, headY),
                strokeWidth = 18f * density,
                alpha = alpha * trailFade * 0.38f,
            )
            drawLine(
                brush = Brush.verticalGradient(
                    edgeCoreColors,
                    startY = tailY,
                    endY = headY.coerceAtLeast(tailY + 1f),
                ),
                start = Offset(width - 2f * density, tailY),
                end = Offset(width - 2f * density, headY),
                strokeWidth = 4f * density,
                alpha = alpha * trailFade * 0.85f,
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = edgeHeadColors,
                    center = Offset(width, headY),
                    radius = 23f * density,
                ),
                center = Offset(width, headY),
                radius = 23f * density,
                alpha = alpha * trailFade * 0.60f,
            )
        }

        if (bottomEntry <= 0f) return@Canvas
        val drift = sin(seconds * 0.85f) * 0.02f
        val glowHeight = 90f * density
        val revealLeft = width * (1f - bottomEntry)
        clipRect(left = revealLeft, top = height - glowHeight, right = width, bottom = height) {
            for (index in bottomGlowColors.indices) {
                val centerX = when (index) {
                    0 -> (0.08f + drift) * width
                    1 -> (0.52f - drift * 0.5f) * width
                    else -> (0.86f + drift) * width
                }
                val center = Offset(centerX, height)
                val horizontalRadius = width * (if (index == 1) 0.42f else 0.30f)
                withTransform({ scale(horizontalRadius / glowHeight, 1f, pivot = center) }) {
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = bottomGlowColors[index],
                            center = center,
                            radius = glowHeight,
                        ),
                        center = center,
                        radius = glowHeight,
                        alpha = alpha * bottomEntry,
                    )
                }
            }
        }
        val entryLineFade = (1f - (seconds - bottomRevealEndSeconds) / 0.24f).coerceIn(0f, 1f)
        if (entryLineFade > 0f) {
            drawLine(
                brush = Brush.horizontalGradient(
                    bottomLineColors,
                    startX = 0f,
                    endX = width,
                ),
                start = Offset(revealLeft, height - 2f * density),
                end = Offset(width + 2f * density, height - 2f * density),
                strokeWidth = 3.5f * density,
                alpha = alpha * bottomEntry * entryLineFade * 0.8f,
            )
        }
    }
}
