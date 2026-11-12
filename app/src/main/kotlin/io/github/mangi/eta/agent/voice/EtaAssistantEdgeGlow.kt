package io.github.mangi.eta.agent.voice

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.Shader
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.RoundedCorner
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlinx.coroutines.isActive

private const val POWER_BUTTON_HEIGHT_RATIO = 0.355f
private const val POWER_HALO_HOLD_SECONDS = 0.06f
private const val POWER_HALO_MOVE_SECONDS = 0.46f
private const val POWER_HALO_END_SECONDS = 0.64f
private const val STREAMER_START_SECONDS = 0.05f
internal const val ASSISTANT_EDGE_ARRIVAL_MS = 220L
private const val BOTTOM_REVEAL_SECONDS = 0.20f
private const val STREAMER_SETTLE_SECONDS = 0.48f

private val edgeArrivalSeconds = ASSISTANT_EDGE_ARRIVAL_MS / 1000f
private val bottomRevealEndSeconds = edgeArrivalSeconds + BOTTOM_REVEAL_SECONDS
private val wakeHandoffSeconds = bottomRevealEndSeconds
private val entranceDurationSeconds = max(POWER_HALO_END_SECONDS, STREAMER_SETTLE_SECONDS)

private val haloEasing = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)
private val streamerEasing = CubicBezierEasing(0.20f, 0f, 0.12f, 1f)
private val bottomRevealEasing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)

private val powerAuroraCore = Color(0xFF93C5FD).copy(alpha = 0.42f)
private val powerAuroraMid = Color(0xFFC4B5FD).copy(alpha = 0.26f)

private val streamerGradientArgb = intArrayOf(
    Color(0x0060A5FA).toArgb(),
    Color(0xCC60A5FA).toArgb(),
    Color(0xE6A78BFA).toArgb(),
    Color(0xF2F472B6).toArgb(),
    Color(0xFFFFF3DC).toArgb(),
)

private val bottomRimGradientArgb = intArrayOf(
    Color(0x0060A5FA).toArgb(),
    Color(0xCC60A5FA).toArgb(),
    Color(0xE6A78BFA).toArgb(),
    Color(0xE6F472B6).toArgb(),
    Color(0xCCFBBF24).toArgb(),
    Color(0x00A78BFA).toArgb(),
)

private val bottomLobePalettes = listOf(
    listOf(Color(0xFF4F8BFF).copy(alpha = 0.66f), Color.Transparent),
    listOf(Color(0xFFA78BFA).copy(alpha = 0.58f), Color.Transparent),
    listOf(Color(0xFFFF7AC6).copy(alpha = 0.62f), Color.Transparent),
    listOf(Color(0xFFFFE4B5).copy(alpha = 0.54f), Color.Transparent),
)

@Composable
private fun rememberBottomCornerRadiiPx(): Pair<Float, Float> {
    val context = LocalContext.current
    val fallbackPx = with(LocalDensity.current) { 36.dp.toPx() }
    return remember(context, fallbackPx) {
        val display = (context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)
            ?.getDisplay(Display.DEFAULT_DISPLAY)
        val bottomLeft = display?.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_LEFT)
            ?.radius?.takeIf { it > 0 }?.toFloat() ?: fallbackPx
        val bottomRight = display?.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_RIGHT)
            ?.radius?.takeIf { it > 0 }?.toFloat() ?: fallbackPx
        bottomLeft to bottomRight
    }
}

/**
 * 初次唤醒从右侧电源键激发极光光晕，沿真实屏幕圆角连续包覆滑入底部声场；
 * 聆听期间底部流光与两侧圆角余晖随语音音量柔和呼吸，手动切回语音时直接恢复底部光场。
 */
@Composable
internal fun EtaAssistantEdgeGlow(
    active: Boolean,
    onEntranceFinished: () -> Unit,
    modifier: Modifier = Modifier,
    level: () -> Float = { 0f },
) {
    var entranceNotified by remember { mutableStateOf(false) }
    var firstWakeComplete by remember { mutableStateOf(false) }
    val entranceFinished by rememberUpdatedState(onEntranceFinished)
    val currentLevel by rememberUpdatedState(level)
    val (bottomLeftRadiusPx, bottomRightRadiusPx) = rememberBottomCornerRadiiPx()

    val opacity by animateFloatAsState(
        targetValue = if (active || !firstWakeComplete) 1f else 0f,
        animationSpec = tween(
            durationMillis = if (active || !firstWakeComplete) 140 else 260,
            easing = FastOutSlowInEasing,
        ),
        label = "assistant_edge_glow",
    )
    var seconds by remember { mutableFloatStateOf(0f) }
    var voiceEnvelope by remember { mutableFloatStateOf(0f) }
    val clock = remember { longArrayOf(0L) }

    val fullTrackPath = remember { Path() }
    val segmentPath = remember { Path() }
    val cradleRimPath = remember { Path() }
    val pathMeasure = remember { PathMeasure() }
    val haloStrokePaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
    }
    val coreStrokePaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
    }

    LaunchedEffect(active) {
        if (!active && firstWakeComplete) return@LaunchedEffect
        while (isActive) {
            withFrameNanos { now ->
                if (clock[0] == 0L) clock[0] = now
                seconds = (now - clock[0]) / 1_000_000_000f
                val targetEnergy = if (active) currentLevel().coerceIn(0f, 1f) else 0f
                val smoothing = if (targetEnergy > voiceEnvelope) 0.26f else 0.08f
                voiceEnvelope += (targetEnergy - voiceEnvelope) * smoothing
                if (!entranceNotified && seconds >= wakeHandoffSeconds) {
                    entranceNotified = true
                    entranceFinished()
                }
                if (!firstWakeComplete && seconds >= entranceDurationSeconds) {
                    firstWakeComplete = true
                }
            }
            if (!active && firstWakeComplete && opacity <= 0.01f) break
        }
    }

    Canvas(modifier.fillMaxSize()) {
        val alpha = opacity
        if (alpha <= 0f || size.width <= 0f || size.height <= 0f) return@Canvas
        val width = size.width
        val height = size.height
        val hardware = drawContext.canvas.nativeCanvas.isHardwareAccelerated
        val wakeEntry = !firstWakeComplete && height > width
        val powerY = height * POWER_BUTTON_HEIGHT_RATIO
        val inset = 1.5f * density
        val cornerR = (bottomRightRadiusPx - inset).coerceIn(12f * density, minOf(width, height) * 0.22f)
        val cornerL = (bottomLeftRadiusPx - inset).coerceIn(12f * density, minOf(width, height) * 0.22f)

        // 1. 电源键极光呼吸点亮。
        if (wakeEntry && seconds < POWER_HALO_END_SECONDS) {
            val early = (seconds / POWER_HALO_HOLD_SECONDS).coerceIn(0f, 1f)
            val late = haloEasing.transform(
                ((seconds - POWER_HALO_HOLD_SECONDS) /
                    (POWER_HALO_END_SECONDS - POWER_HALO_HOLD_SECONDS)).coerceIn(0f, 1f),
            )
            val radiusScale = density / 3f
            val radius = ((240f + 560f * early) + 880f * late) * radiusScale
            val driftY = haloEasing.transform((seconds / POWER_HALO_MOVE_SECONDS).coerceIn(0f, 1f))
            val center = Offset(
                width * 1.24f,
                powerY + (height * 0.62f - powerY) * 0.55f * driftY,
            )
            val haloFade = if (seconds < POWER_HALO_HOLD_SECONDS) early else (1f - late)
            drawCircle(
                brush = Brush.radialGradient(
                    colorStops = arrayOf(
                        0f to powerAuroraCore,
                        0.46f to powerAuroraMid,
                        1f to Color.Transparent,
                    ),
                    center = center,
                    radius = radius.coerceAtLeast(1f),
                ),
                center = center,
                radius = radius.coerceAtLeast(1f),
                alpha = alpha * haloFade,
            )
        }

        // 2. 沿右侧边框经右下真实圆角包覆滑入底边的流光拖尾。
        if (wakeEntry && seconds >= STREAMER_START_SECONDS && seconds < STREAMER_SETTLE_SECONDS) {
            val rawProgress = ((seconds - STREAMER_START_SECONDS) /
                (bottomRevealEndSeconds - STREAMER_START_SECONDS)).coerceIn(0f, 1f)
            val travel = streamerEasing.transform(rawProgress)
            val fadeOut = (1f - ((seconds - bottomRevealEndSeconds) /
                (STREAMER_SETTLE_SECONDS - bottomRevealEndSeconds)).coerceIn(0f, 1f))

            fullTrackPath.reset()
            fullTrackPath.moveTo(width - inset, powerY)
            fullTrackPath.lineTo(width - inset, height - inset - cornerR)
            fullTrackPath.arcTo(
                width - inset - cornerR * 2f,
                height - inset - cornerR * 2f,
                width - inset,
                height - inset,
                0f,
                90f,
                false,
            )
            fullTrackPath.lineTo(inset + cornerL, height - inset)
            fullTrackPath.arcTo(
                inset,
                height - inset - cornerL * 2f,
                inset + cornerL * 2f,
                height - inset,
                90f,
                72f,
                false,
            )

            pathMeasure.setPath(fullTrackPath, false)
            val totalLength = pathMeasure.length
            if (totalLength > 1f) {
                val headDist = totalLength * travel
                val tailSpan = totalLength * (0.34f + 0.18f * sin(travel * Math.PI).toFloat())
                val tailDist = (headDist - tailSpan).coerceAtLeast(0f)
                segmentPath.reset()
                if (headDist > tailDist + 1f && pathMeasure.getSegment(tailDist, headDist, segmentPath, true)) {
                    val headPos = FloatArray(2)
                    val tailPos = FloatArray(2)
                    pathMeasure.getPosTan(headDist, headPos, null)
                    pathMeasure.getPosTan(tailDist, tailPos, null)

                    val ribbonShader = LinearGradient(
                        tailPos[0],
                        tailPos[1],
                        headPos[0] + 0.5f,
                        headPos[1] + 0.5f,
                        streamerGradientArgb,
                        floatArrayOf(0f, 0.25f, 0.62f, 0.86f, 1f),
                        Shader.TileMode.CLAMP,
                    )
                    drawIntoCanvas { canvas ->
                        val native = canvas.nativeCanvas
                        haloStrokePaint.shader = ribbonShader
                        haloStrokePaint.strokeWidth = 16f * density
                        haloStrokePaint.maskFilter = if (hardware) {
                            BlurMaskFilter(11f * density, BlurMaskFilter.Blur.NORMAL)
                        } else {
                            null
                        }
                        haloStrokePaint.alpha = (alpha * fadeOut * (if (hardware) 0.62f else 0.24f) * 255f).toInt()
                        native.drawPath(segmentPath, haloStrokePaint)

                        coreStrokePaint.shader = ribbonShader
                        coreStrokePaint.strokeWidth = 3.2f * density
                        coreStrokePaint.alpha = (alpha * fadeOut * 0.92f * 255f).toInt()
                        native.drawPath(segmentPath, coreStrokePaint)
                    }

                    val headCenter = Offset(headPos[0], headPos[1])
                    val headRadius = 20f * density
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                Color(0xFFFFF8EB),
                                Color(0xFFF472B6).copy(alpha = 0.58f),
                                Color.Transparent,
                            ),
                            center = headCenter,
                            radius = headRadius,
                        ),
                        center = headCenter,
                        radius = headRadius,
                        alpha = alpha * fadeOut * 0.72f,
                    )
                }
            }
        }

        // 3. 底部流光温床与双侧圆角柔光托底。
        val bottomEntry = if (wakeEntry) {
            bottomRevealEasing.transform(
                ((seconds - edgeArrivalSeconds * 0.78f) / (BOTTOM_REVEAL_SECONDS * 1.15f)).coerceIn(0f, 1f),
            )
        } else {
            1f
        }
        if (bottomEntry <= 0f) return@Canvas

        val slowDrift = sin(seconds * 0.95f) * 0.045f
        val secondaryDrift = cos(seconds * 0.68f) * 0.03f
        val voiceLift = voiceEnvelope * 0.38f
        val glowHeight = (88f + 34f * voiceEnvelope) * density * (0.72f + 0.28f * bottomEntry)

        val centersX = floatArrayOf(
            (0.12f + slowDrift) * width,
            (0.39f - secondaryDrift) * width,
            (0.66f + secondaryDrift) * width,
            (0.88f - slowDrift * 0.8f) * width,
        )
        val radiiRatio = floatArrayOf(0.32f, 0.38f, 0.36f, 0.30f)

        for (index in bottomLobePalettes.indices) {
            val center = Offset(centersX[index], height + glowHeight * 0.12f)
            val horizontalRadius = (width * radiiRatio[index]).coerceAtLeast(glowHeight)
            val lobeAlpha = (alpha * bottomEntry * (0.78f + voiceLift)).coerceIn(0f, 1f)
            withTransform({ scale(horizontalRadius / glowHeight.coerceAtLeast(1f), 1f, pivot = center) }) {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = bottomLobePalettes[index],
                        center = center,
                        radius = glowHeight.coerceAtLeast(1f),
                    ),
                    center = center,
                    radius = glowHeight.coerceAtLeast(1f),
                    alpha = lobeAlpha,
                )
            }
        }

        // 贴合底部与左右圆角的细腻呼吸边光。
        val rimClimb = (22f + 26f * voiceEnvelope) * density * bottomEntry
        cradleRimPath.reset()
        cradleRimPath.moveTo(inset, height - inset - cornerL - rimClimb)
        cradleRimPath.lineTo(inset, height - inset - cornerL)
        cradleRimPath.arcTo(
            inset,
            height - inset - cornerL * 2f,
            inset + cornerL * 2f,
            height - inset,
            180f,
            -90f,
            false,
        )
        cradleRimPath.lineTo(width - inset - cornerR, height - inset)
        cradleRimPath.arcTo(
            width - inset - cornerR * 2f,
            height - inset - cornerR * 2f,
            width - inset,
            height - inset,
            90f,
            -90f,
            false,
        )
        cradleRimPath.lineTo(width - inset, height - inset - cornerR - rimClimb)

        val shimmerShift = sin(seconds * 1.35f) * width * 0.12f
        val rimShader = LinearGradient(
            shimmerShift,
            height,
            width + shimmerShift,
            height,
            bottomRimGradientArgb,
            floatArrayOf(0f, 0.18f, 0.42f, 0.64f, 0.84f, 1f),
            Shader.TileMode.CLAMP,
        )
        val rimBreath = 0.46f + 0.12f * sin(seconds * 2.1f) + 0.34f * voiceEnvelope
        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            if (hardware) {
                haloStrokePaint.shader = rimShader
                haloStrokePaint.strokeWidth = (8.5f + 4.5f * voiceEnvelope) * density
                haloStrokePaint.maskFilter = BlurMaskFilter(8f * density, BlurMaskFilter.Blur.NORMAL)
                haloStrokePaint.alpha = (alpha * bottomEntry * rimBreath * 0.65f * 255f).toInt().coerceIn(0, 255)
                native.drawPath(cradleRimPath, haloStrokePaint)
            }
            coreStrokePaint.shader = rimShader
            coreStrokePaint.strokeWidth = (2.2f + 0.9f * voiceEnvelope) * density
            coreStrokePaint.alpha = (alpha * bottomEntry * rimBreath * 0.88f * 255f).toInt().coerceIn(0, 255)
            native.drawPath(cradleRimPath, coreStrokePaint)
        }
    }
}
