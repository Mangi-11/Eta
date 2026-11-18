package io.github.mangi.eta.ui.components

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.ui.app.LocalGlassPageBackdrop
import io.github.mangi.eta.ui.app.LocalGlassSceneBackdrop
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.TextFieldColors
import top.yukonga.miuix.kmp.basic.TextFieldDefaults
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

/**
 * 按需录制根场景的 Backdrop 代理：仅当弹窗、底部抽屉或气泡菜单实际采样时才将根容器
 * 挂载到 `layerBackdrop`，避免常态列表滚动与页面转场每帧额外重绘整屏离屏纹理。
 */
@Stable
internal class DemandSceneBackdrop(
    private val delegate: LayerBackdrop,
    private val fallbackColor: Color = Color.Transparent,
) : Backdrop {
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var lastSampledNanos: Long = 0L

    @Volatile
    private var delegateAttached: Boolean = false

    var isActive by mutableStateOf(false)
        private set

    override val isCoordinatesDependent: Boolean
        get() = delegate.isCoordinatesDependent

    override val offsetResidualX: Float
        get() = delegate.offsetResidualX

    override val offsetResidualY: Float
        get() = delegate.offsetResidualY

    fun setDelegateAttached(attached: Boolean) {
        delegateAttached = attached
    }

    fun refreshIdleState(nowNanos: Long = System.nanoTime()) {
        if (isActive && nowNanos - lastSampledNanos > IDLE_TIMEOUT_NANOS) {
            isActive = false
        }
    }

    override fun DrawScope.drawBackdrop(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?,
        downscaleFactor: Float,
    ) {
        lastSampledNanos = System.nanoTime()
        if (!isActive) {
            mainHandler.post {
                if (!isActive) isActive = true
            }
        }
        if (!delegateAttached) {
            if (fallbackColor.alpha > 0f) {
                drawRect(fallbackColor)
            }
            return
        }
        with(delegate) {
            drawBackdrop(density, coordinates, layerBlock, downscaleFactor)
        }
    }

    private companion object {
        const val IDLE_TIMEOUT_NANOS = 350_000_000L
    }
}

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

/**
 * 玻璃弹窗 / 底部抽屉内输入框的提亮底色：Miuix 默认 `DialogGlassLight` 叠加遮罩后呈浅灰，
 * 若仍使用 `secondaryContainer`/`surfaceContainerHigh` 会与面板底色融在一起；半透白能自然拉开层次。
 */
@Composable
internal fun glassOverlayFieldBackgroundColor(): Color {
    val isDark = MiuixTheme.colorScheme.background.luminance() < 0.5f
    return if (isDark) Color.White.copy(alpha = 0.09f) else Color.White.copy(alpha = 0.80f)
}

@Composable
internal fun glassOverlayTextFieldColors(): TextFieldColors = TextFieldDefaults.textFieldColors(
    backgroundColor = glassOverlayFieldBackgroundColor(),
    labelColor = MiuixTheme.colorScheme.onSurfaceVariantSummary,
)

internal val MiuixSheetZeroMargin = PaddingValues(0.dp)
