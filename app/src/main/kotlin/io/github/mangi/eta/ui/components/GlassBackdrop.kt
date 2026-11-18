package io.github.mangi.eta.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.ui.app.LocalGlassPageBackdrop
import io.github.mangi.eta.ui.app.LocalGlassSceneBackdrop
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.glass.GlassIconButton
import top.yukonga.miuix.kmp.material.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun rememberGlassBackdrop(
    containerColor: Color = MiuixTheme.colorScheme.surface,
): LayerBackdrop? {
    if (!isRuntimeShaderSupported()) return null
    return rememberLayerBackdrop {
        drawRect(containerColor)
        drawContent()
    }
}

internal fun Modifier.captureGlassBackdrop(backdrop: LayerBackdrop?): Modifier =
    if (backdrop == null) this else layerBackdrop(backdrop)

@Composable
internal fun currentGlassOverlayBackdrop(): Backdrop? =
    LocalGlassSceneBackdrop.current ?: LocalGlassPageBackdrop.current

/**
 * 玻璃顶栏内的动作按钮：统一 28.dp Glass 图标尺寸，并在禁用态阻断点击与补齐无障碍语义。
 */
@Composable
internal fun MiuixTopBarActionButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    backdrop: Backdrop? = null,
    tint: Color = MiuixTheme.colorScheme.onSurface,
) {
    val resolvedTint = if (enabled) tint else MiuixTheme.colorScheme.disabledOnSecondaryVariant
    GlassIconButton(
        onClick = { if (enabled) onClick() },
        backdrop = backdrop,
        modifier = modifier.then(if (!enabled) Modifier.semantics { disabled() } else Modifier),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(28.dp),
            tint = resolvedTint,
        )
    }
}

internal val MiuixSheetZeroMargin = PaddingValues(0.dp)
