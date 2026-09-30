package io.github.mangi.eta.agent.overlay

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb

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

/**
 * 屏幕四边氛围光窗口：全屏触摸穿透（FLAG_NOT_TOUCHABLE），不挡操作。
 * 窗口类型 TYPE_ACCESSIBILITY_OVERLAY，截图时被 takeScreenshotOfWindow 过滤，对 Agent 透明。
 * - RUNNING：半透明黑底压暗 + 彩虹色旋转 SweepGradient 光圈。
 * - PAUSED / FINISHED / FAILED：不绘制。
 *
 * 运行状态本身不再自绘浮窗，状态栏胶囊与结果都由系统实况通知（流体云）承载。
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
