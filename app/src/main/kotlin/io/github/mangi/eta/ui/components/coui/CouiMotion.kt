package io.github.mangi.eta.ui.components.coui

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChangedIgnoreConsumed
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.ui.app.LocalUiStyle
import io.github.suqi8.coui.kmp.anim.couiSpring
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.suqi8.coui.kmp.utils.COUIIndication
import io.github.suqi8.coui.kmp.utils.couiTouchFeedback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
internal fun Modifier.couiButtonFeedback(
    dark: Boolean,
    interactionSource: MutableInteractionSource,
    enabled: Boolean,
    list: Boolean = false,
): Modifier {
    if (!enabled) return this
    val isCoui = LocalUiStyle.current == AppearanceUiStyle.COUI
    val ink = if (dark) Color.White else Color.Black
    // 语音面板在 MIUIX 样式下也复用此按压反馈，此时未挂载 COUITheme，需按当前深浅色回退，
    // 避免读到 COUITheme 的浅色默认静态值。
    val press: Color
    val hover: Color
    val focus: Color
    if (isCoui) {
        val colors = COUITheme.colorScheme
        press = if (list) colors.pressBackground else colors.press
        hover = colors.hover
        focus = colors.focus
    } else {
        press = ink.copy(alpha = if (list) (if (dark) 0.10f else 0.06f) else (if (dark) 0.16f else 0.08f))
        hover = ink.copy(alpha = if (dark) 0.08f else 0.04f)
        focus = ink.copy(alpha = if (dark) 0.12f else 0.06f)
    }
    val indication = remember(ink, press, hover, focus) {
        COUIIndication(ink, pressColor = press, hoverColor = hover, focusColor = focus)
    }
    return couiTouchFeedback(interactionSource).indication(interactionSource, indication)
}

/** 为需要与录音或进入动画叠加的悬浮控件提供独立的按压进度。 */
internal class CouiPressMotion(private val scope: CoroutineScope, private val density: Float) {
    private val animation = Animatable(1f)
    private var motion: Job? = null
    val scale = animation.asState()

    fun press(size: IntSize) {
        if (size.width <= 0 || size.height <= 0) return
        val shortestSide = minOf(size.width, size.height) / density
        val progress = ((shortestSide - 20f) / 130f).coerceIn(0f, 1f)
        animate(0.85f + 0.12f * progress, response = 0.25f, bounce = 0f)
    }

    fun release() = animate(1f, response = 0.5f, bounce = 0.55f)

    private fun animate(target: Float, response: Float, bounce: Float) {
        motion?.cancel()
        motion = scope.launch {
            animation.animateTo(target, couiSpring(response = response, bounce = bounce))
        }
    }
}

@Composable
internal fun rememberCouiPressMotion(): CouiPressMotion {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current.density
    return remember(scope, density) { CouiPressMotion(scope, density) }
}

internal fun Modifier.couiPressMotion(
    motion: CouiPressMotion,
    enabled: Boolean = true,
    cancelOnConsumedMove: Boolean = false,
): Modifier {
    if (!enabled) return this
    return pointerInput(motion, cancelOnConsumedMove) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            motion.press(size)
            try {
                do {
                    val event = awaitPointerEvent(PointerEventPass.Final)
                    val stolen = cancelOnConsumedMove && event.changes.any {
                        it.isConsumed && it.pressed && it.positionChangedIgnoreConsumed()
                    }
                } while (!stolen && event.changes.any { it.pressed })
            } finally {
                motion.release()
            }
        }
    }
}
