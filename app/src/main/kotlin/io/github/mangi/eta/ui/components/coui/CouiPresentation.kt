package io.github.mangi.eta.ui.components.coui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.blur.LayerBackdrop
import io.github.suqi8.coui.kmp.blur.MaterialBlendMode
import io.github.suqi8.coui.kmp.blur.MaterialBlurStyle
import io.github.suqi8.coui.kmp.blur.ViewBlurStyle
import io.github.suqi8.coui.kmp.blur.couiMaterialBlur
import io.github.suqi8.coui.kmp.blur.couiViewBlur
import io.github.suqi8.coui.kmp.blur.layerBackdrop
import io.github.suqi8.coui.kmp.blur.rememberLayerBackdrop
import io.github.suqi8.coui.kmp.effect.LocalCOUIMaterialEffectEnabled
import io.github.suqi8.coui.kmp.effect.LocalMaterialBackdrop
import io.github.suqi8.coui.kmp.effect.LocalPopupMaterialBlur
import io.github.suqi8.coui.kmp.effect.MaterialBackdropProvider
import io.github.suqi8.coui.kmp.effect.MaterialBlurProvider
import io.github.suqi8.coui.kmp.squircle.SquircleShape
import io.github.suqi8.coui.kmp.theme.COUITheme

@Immutable
internal data class CouiPageColors(
    val background: Color,
    val card: Color,
    val title: Color,
    val summary: Color,
    val disabled: Color,
    val accent: Color,
    val divider: Color,
)

internal object CouiPageDefaults {
    val horizontalMargin = 16.dp
    val rowPadding = 16.dp
    val rowMinHeight = 48.dp
    val iconSize = 24.dp
    val iconSpacing = 16.dp
    val contentPadding = 10.dp
    val groupSpacing = 16.dp
    val categoryTopSpacing = 12.dp
    val shape: Shape = SquircleShape(24.dp)

    @Composable
    fun themeColors(): CouiPageColors = COUITheme.colorScheme.let {
        CouiPageColors(
            background = it.backgroundWithCard,
            card = it.cardBackground,
            title = it.labelPrimary,
            summary = it.labelSecondary,
            disabled = it.disabledNeutral,
            accent = it.primary,
            divider = it.divider,
        )
    }
}

internal enum class CouiTextKind { GroupFooter, GroupTitle, ItemSummary, ItemTitle, ItemValue, Search, TopBar }

@Composable
internal fun couiTextStyle(kind: CouiTextKind): TextStyle = with(COUITheme.textStyles) {
    when (kind) {
        CouiTextKind.GroupFooter -> bodyS
        CouiTextKind.GroupTitle -> buttonM
        CouiTextKind.ItemSummary, CouiTextKind.ItemValue -> bodyM
        CouiTextKind.ItemTitle, CouiTextKind.Search -> bodyXL
        CouiTextKind.TopBar -> headlineS
    }
}

@Composable
internal fun CouiText(
    text: String,
    modifier: Modifier = Modifier,
    kind: CouiTextKind = CouiTextKind.ItemTitle,
    color: Color = Color.Unspecified,
    textAlign: TextAlign = TextAlign.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    Text(
        text = text,
        modifier = modifier,
        color = color,
        style = couiTextStyle(kind),
        textAlign = textAlign,
        maxLines = maxLines,
        overflow = overflow,
    )
}

internal val LocalCouiPageBackdrop = staticCompositionLocalOf<LayerBackdrop?> { null }

@Composable
internal fun rememberCouiPageBackdrop(color: Color): LayerBackdrop? {
    if (!LocalCOUIMaterialEffectEnabled.current) return null
    return rememberLayerBackdrop {
        drawRect(color)
        drawContent()
    }
}

@Composable
internal fun CouiBackdropContent(backdrop: LayerBackdrop?, content: @Composable () -> Unit) {
    val capture = if (backdrop == null) Modifier else Modifier.layerBackdrop(backdrop)
    Box(Modifier.fillMaxSize().then(capture)) {
        CompositionLocalProvider(LocalMaterialBackdrop provides null) {
            ProvideCouiOverlays(backdrop, content)
        }
    }
}

@Composable
internal fun ProvideCouiOverlays(backdrop: LayerBackdrop?, content: @Composable () -> Unit) {
    val popupMaterial = remember(backdrop) {
        backdrop?.let {
            MaterialBlurProvider { spec, shape ->
                Modifier.couiMaterialBlur(
                    backdrop = it,
                    style = MaterialBlurStyle(
                        blurRadiusPx = spec.blurRadiusPx,
                        mixColor = spec.mixColor,
                        blendColor = spec.blendColor,
                        blendMode = MaterialBlendMode.valueOf(spec.blendMode.name),
                    ),
                    shape = shape,
                )
            }
        }
    }
    CompositionLocalProvider(
        LocalCouiPageBackdrop provides backdrop,
        LocalPopupMaterialBlur provides popupMaterial,
        content = content,
    )
}

@Composable
internal fun ProvideCouiMaterial(backdrop: LayerBackdrop?, content: @Composable () -> Unit) {
    val material = remember(backdrop) {
        backdrop?.let {
            MaterialBackdropProvider { spec, shape ->
                Modifier.couiViewBlur(
                    backdrop = it,
                    style = ViewBlurStyle(
                        blurRadiusPx = spec.blurRadiusPx,
                        layer1 = spec.layer1,
                        mode1 = spec.mode1,
                        layer2 = spec.layer2,
                        mode2 = spec.mode2,
                    ),
                    shape = shape,
                )
            }
        }
    }
    CompositionLocalProvider(LocalMaterialBackdrop provides material) {
        ProvideCouiOverlays(backdrop, content)
    }
}
