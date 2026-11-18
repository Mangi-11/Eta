package io.github.mangi.eta.ui.layout

import android.os.Build
import android.view.RoundedCorner
import android.view.View
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.ui.app.LocalPlatformDensity
import kotlin.math.roundToInt

private val WideScreenMinWidth = 600.dp
private val MaxPageContentWidth = 800.dp
private const val FLOATING_BOTTOM_CORNER_LIFT_RATIO = 0.42f
private val FloatingBottomMinTotalPadding = 12.dp
private val FloatingBottomMaxCornerTotalPadding = 26.dp

@Composable
fun rememberIsWideScreen(): Boolean {
    val containerSize = LocalWindowInfo.current.containerSize
    val density = LocalPlatformDensity.current ?: LocalDensity.current
    return isWideScreen(containerSize.width, density)
}

internal fun isWideScreen(containerWidthPx: Int, density: androidx.compose.ui.unit.Density): Boolean =
    with(density) { containerWidthPx.toDp() >= WideScreenMinWidth }

/**
 * 列表本身保持全宽，只把内容限制在居中的最大宽度内，避免宽屏两侧形成滚动死区。
 */
@Composable
fun WidePageContent(
    modifier: Modifier = Modifier,
    content: @Composable (sidePadding: Dp) -> Unit,
) {
    val isWideScreen = rememberIsWideScreen()
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val sidePadding = if (isWideScreen) {
            ((maxWidth - MaxPageContentWidth) / 2).coerceAtLeast(0.dp)
        } else {
            0.dp
        }
        content(sidePadding)
    }
}

/**
 * 二级页内容只补水平方向的屏幕缺口与手势区；顶部由 TopAppBar、底部由页面末尾留白负责。
 */
@Composable
fun Modifier.horizontalCutoutPadding(): Modifier = windowInsetsPadding(
    WindowInsets.displayCutout
        .union(WindowInsets.navigationBars)
        .only(WindowInsetsSides.Horizontal),
)

/**
 * 为贴底悬浮胶囊计算安全 WindowInsets（不含外层自持的 [fixedBottomPadding]）：
 * 隐藏手势提示条时系统导航栏高度为 0，仅靠 navigationBarsPadding 会让大圆角屏幕下的
 * 胶囊左下/右下角贴近物理玻璃圆弧。通过 rootWindowInsets.getRoundedCorner 读取窗口实际
 * 贴近的底角半径（分屏上半窗或悬浮小窗会自动返回 0），按 0.42 倍（上限 26.dp）抬升底边，
 * 并与系统栏高度取 maxOf，避免在开启小白条或三键导航时重复叠加，同时保持与外层 imePadding
 * 连续消费衔接。
 */
@Composable
fun rememberFloatingBottomBarInsets(
    baseInsets: WindowInsets = WindowInsets.navigationBars,
    fixedBottomPadding: Dp = 10.dp,
    minTotalPadding: Dp = FloatingBottomMinTotalPadding,
    maxCornerTotalPadding: Dp = FloatingBottomMaxCornerTotalPadding,
): WindowInsets {
    val view = LocalView.current
    val platformDensity = LocalPlatformDensity.current
    var attachVersion by remember(view) {
        mutableIntStateOf(if (view.isAttachedToWindow) 1 else 0)
    }
    DisposableEffect(view) {
        if (view.isAttachedToWindow && attachVersion == 0) {
            attachVersion = 1
        }
        val listener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                attachVersion++
            }

            override fun onViewDetachedFromWindow(v: View) = Unit
        }
        view.addOnAttachStateChangeListener(listener)
        onDispose { view.removeOnAttachStateChangeListener(listener) }
    }
    // 全面屏隐藏手势条时 navigationBars 始终为 0，attach 前后数值不变可能不触发 Snapshot 更新，
    // 借 attachVersion 确保首次附着后能读取到完整的 rootWindowInsets 圆角数据。
    return remember(
        view,
        attachVersion,
        baseInsets,
        platformDensity,
        fixedBottomPadding,
        minTotalPadding,
        maxCornerTotalPadding,
    ) {
        FloatingBottomBarWindowInsets(
            view = view,
            baseInsets = baseInsets,
            platformDensity = platformDensity,
            fixedBottomPadding = fixedBottomPadding,
            minTotalPadding = minTotalPadding,
            maxCornerTotalPadding = maxCornerTotalPadding,
        )
    }
}

internal fun computeFloatingBottomExtraInsetPx(
    baseBottomInsetPx: Int,
    bottomCornerRadiusPx: Int,
    fixedBottomPaddingPx: Int,
    maxCornerTotalPaddingPx: Int,
    minTotalPaddingPx: Int,
): Int {
    val cornerTargetTotalPx = if (bottomCornerRadiusPx > 0) {
        (bottomCornerRadiusPx * FLOATING_BOTTOM_CORNER_LIFT_RATIO)
            .roundToInt()
            .coerceAtMost(maxCornerTotalPaddingPx)
    } else {
        0
    }
    val targetTotalPx = maxOf(
        baseBottomInsetPx + fixedBottomPaddingPx,
        cornerTargetTotalPx,
        minTotalPaddingPx,
    )
    return (targetTotalPx - fixedBottomPaddingPx).coerceAtLeast(0)
}

private fun readBottomScreenCornerRadiusPx(view: View): Int {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return 0
    val rootInsets = view.rootWindowInsets ?: return 0
    val leftRadius = rootInsets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_LEFT)?.radius ?: 0
    val rightRadius = rootInsets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_RIGHT)?.radius ?: 0
    return maxOf(leftRadius, rightRadius)
}

private class FloatingBottomBarWindowInsets(
    private val view: View,
    private val baseInsets: WindowInsets,
    private val platformDensity: Density?,
    private val fixedBottomPadding: Dp,
    private val minTotalPadding: Dp,
    private val maxCornerTotalPadding: Dp,
) : WindowInsets {
    override fun getLeft(density: Density, layoutDirection: LayoutDirection): Int =
        baseInsets.getLeft(density, layoutDirection)

    override fun getTop(density: Density): Int =
        baseInsets.getTop(density)

    override fun getRight(density: Density, layoutDirection: LayoutDirection): Int =
        baseInsets.getRight(density, layoutDirection)

    override fun getBottom(density: Density): Int {
        val baseBottomPx = baseInsets.getBottom(density)
        val cornerRadiusPx = readBottomScreenCornerRadiusPx(view)
        val fixedBottomPx = with(density) { fixedBottomPadding.roundToPx() }
        val minTotalPx = with(density) { minTotalPadding.roundToPx() }
        // 物理圆角半径以屏幕硬件像素度量，最大圆角避让高度同步以未缩放物理密度为准，
        // 防止界面调小缩放后底栏重新贴进屏幕玻璃弧线。
        val maxCornerTotalPx = with(platformDensity ?: density) { maxCornerTotalPadding.roundToPx() }
        return computeFloatingBottomExtraInsetPx(
            baseBottomInsetPx = baseBottomPx,
            bottomCornerRadiusPx = cornerRadiusPx,
            fixedBottomPaddingPx = fixedBottomPx,
            maxCornerTotalPaddingPx = maxCornerTotalPx,
            minTotalPaddingPx = minTotalPx,
        )
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FloatingBottomBarWindowInsets) return false
        return view === other.view &&
            baseInsets == other.baseInsets &&
            platformDensity == other.platformDensity &&
            fixedBottomPadding == other.fixedBottomPadding &&
            minTotalPadding == other.minTotalPadding &&
            maxCornerTotalPadding == other.maxCornerTotalPadding
    }

    override fun hashCode(): Int {
        var result = System.identityHashCode(view)
        result = 31 * result + baseInsets.hashCode()
        result = 31 * result + (platformDensity?.hashCode() ?: 0)
        result = 31 * result + fixedBottomPadding.hashCode()
        result = 31 * result + minTotalPadding.hashCode()
        result = 31 * result + maxCornerTotalPadding.hashCode()
        return result
    }
}
