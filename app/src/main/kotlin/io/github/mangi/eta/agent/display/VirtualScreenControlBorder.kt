package io.github.mangi.eta.agent.display

import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.SweepGradient
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp

@Composable
internal fun Modifier.virtualScreenControlBorder(active: Boolean): Modifier {
    if (!active) return this
    val transition = rememberInfiniteTransition(label = "virtual-screen-control")
    val rotation = transition.animateFloat(0f, 360f,
        infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Restart), label = "border-rotation")
    return drawWithCache {
        val stroke = 3.dp.toPx()
        val shader = SweepGradient(size.width / 2, size.height / 2,
            intArrayOf(0xFF60D6FF.toInt(), 0xFF6E8FFF.toInt(), 0xFFBB7BFF.toInt(),
                0xFFFF83C7.toInt(), 0xFFFFCD7B.toInt(), 0xFF7AEBBB.toInt(), 0xFF60D6FF.toInt()), null)
        val matrix = Matrix()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = stroke; this.shader = shader }
        val inset = stroke / 2
        val radius = 24.dp.toPx() - inset
        onDrawWithContent {
            drawContent()
            matrix.setRotate(rotation.value, size.width / 2, size.height / 2)
            shader.setLocalMatrix(matrix)
            drawIntoCanvas { it.nativeCanvas.drawRoundRect(inset, inset, size.width - inset, size.height - inset, radius, radius, paint) }
        }
    }
}
