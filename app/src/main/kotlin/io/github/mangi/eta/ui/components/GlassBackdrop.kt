package io.github.mangi.eta.ui.components

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.unit.Density
import io.github.mangi.eta.ui.app.LocalGlassPageBackdrop
import io.github.mangi.eta.ui.app.LocalGlassSceneBackdrop
import top.yukonga.miuix.kmp.basic.TextFieldColors
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.glass.GlassTextFieldDefaults
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
 * 玻璃弹窗 / 底部抽屉内输入框与嵌套容器的通透底色：直接复用 `GlassTextFieldDefaults`
 * 的背景色，自动对齐浅/深色与动态取色下的对比度。
 */
@Composable
internal fun glassOverlayFieldBackgroundColor(): Color =
    GlassTextFieldDefaults.textFieldColors().backgroundColor

@Composable
internal fun glassOverlayTextFieldColors(): TextFieldColors =
    GlassTextFieldDefaults.textFieldColors()
