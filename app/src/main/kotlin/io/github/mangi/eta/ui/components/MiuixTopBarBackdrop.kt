package io.github.mangi.eta.ui.components

import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.ui.app.LocalAppearanceSettings
import io.github.mangi.eta.ui.layout.TopBarBackdropLayout
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.ProgressiveBlur
import top.yukonga.miuix.kmp.blur.progressiveTextureBlur
import top.yukonga.miuix.kmp.glass.GlassTopAppBarDefaults
import top.yukonga.miuix.kmp.material.isRuntimeShaderSupported

@Composable
internal fun miuixProgressiveTopBarBackdrop(backdrop: Backdrop?): Backdrop? =
    backdrop.takeIf { LocalAppearanceSettings.current.progressiveBlurEnabled && isRuntimeShaderSupported() }

@Composable
internal fun MiuixTopBarBackdrop(
    progressiveBackdrop: Backdrop?,
    backgroundColor: Color,
    fallbackBrush: Brush,
    modifier: Modifier = Modifier,
    captureModifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val background = if (progressiveBackdrop != null) {
        val tint = remember(backgroundColor) { listOf(BlendColorEntry(backgroundColor.copy(alpha = 0.3f))) }
        captureModifier.progressiveTextureBlur(
            backdrop = progressiveBackdrop,
            shape = RectangleShape,
            gradient = ProgressiveBlur.Top.copy(curve = 0.5f),
            blurRadius = GlassTopAppBarDefaults.ProgressiveBlurRadius.value,
            colors = BlurDefaults.blurColors(blendColors = tint),
        )
    } else {
        Modifier.background(fallbackBrush)
    }
    TopBarBackdropLayout(
        modifier = modifier,
        backgroundHeight = { height ->
            if (progressiveBackdrop != null) ((height + 20.dp.toPx()) * 1.15f).roundToInt()
            else height + GlassTopAppBarDefaults.BandOverhang.roundToPx()
        },
        backgroundModifier = background,
        content = content,
    )
}
