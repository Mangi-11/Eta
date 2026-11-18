package io.github.mangi.eta.ui.layout

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptivePageLayoutTest {
    @Test
    fun wideScreenDecisionUsesTheProvidedUnscaledDensity() {
        val widthPx = 1200

        assertTrue(isWideScreen(widthPx, Density(density = 2f)))
        assertFalse(isWideScreen(widthPx, Density(density = 2.2f)))
    }

    @Test
    fun floatingBottomExtraInsetLiftsCapsuleAboveLargeRoundedCornersWhenNavBarHidden() {
        val density = Density(density = 3.5f)
        val fixedPx = with(density) { 10.dp.roundToPx() }
        val maxCornerPx = with(density) { 26.dp.roundToPx() }
        val minTotalPx = with(density) { 12.dp.roundToPx() }

        // 203px 圆角 * 0.42 ≈ 85px（约 24.3dp），扣除已有 10dp（35px）后额外补充 50px。
        val extraPx = computeFloatingBottomExtraInsetPx(
            baseBottomInsetPx = 0,
            bottomCornerRadiusPx = 203,
            fixedBottomPaddingPx = fixedPx,
            maxCornerTotalPaddingPx = maxCornerPx,
            minTotalPaddingPx = minTotalPx,
        )

        assertEquals(50, extraPx)
    }

    @Test
    fun floatingBottomExtraInsetDoesNotStackOnGestureBarOrThreeButtonNavigation() {
        val density = Density(density = 3.5f)
        val fixedPx = with(density) { 10.dp.roundToPx() }
        val maxCornerPx = with(density) { 26.dp.roundToPx() }
        val minTotalPx = with(density) { 12.dp.roundToPx() }

        // 15dp 小白条（53px）+ 10dp = 88px > 圆角目标 85px，直接沿用导航栏高度而不叠加。
        val gestureExtraPx = computeFloatingBottomExtraInsetPx(
            baseBottomInsetPx = 53,
            bottomCornerRadiusPx = 203,
            fixedBottomPaddingPx = fixedPx,
            maxCornerTotalPaddingPx = maxCornerPx,
            minTotalPaddingPx = minTotalPx,
        )
        assertEquals(53, gestureExtraPx)

        // 三键导航（48dp = 168px）完全维持原始导航栏避让高度。
        val threeButtonExtraPx = computeFloatingBottomExtraInsetPx(
            baseBottomInsetPx = 168,
            bottomCornerRadiusPx = 203,
            fixedBottomPaddingPx = fixedPx,
            maxCornerTotalPaddingPx = maxCornerPx,
            minTotalPaddingPx = minTotalPx,
        )
        assertEquals(168, threeButtonExtraPx)
    }

    @Test
    fun floatingBottomExtraInsetClampsExtremeCornerAndFallsBackOnFlatScreen() {
        val density = Density(density = 3.5f)
        val fixedPx = with(density) { 10.dp.roundToPx() }
        val maxCornerPx = with(density) { 26.dp.roundToPx() }
        val minTotalPx = with(density) { 12.dp.roundToPx() }

        val clampedExtraPx = computeFloatingBottomExtraInsetPx(
            baseBottomInsetPx = 0,
            bottomCornerRadiusPx = 300,
            fixedBottomPaddingPx = fixedPx,
            maxCornerTotalPaddingPx = maxCornerPx,
            minTotalPaddingPx = minTotalPx,
        )
        assertEquals(maxCornerPx - fixedPx, clampedExtraPx)

        val flatScreenExtraPx = computeFloatingBottomExtraInsetPx(
            baseBottomInsetPx = 0,
            bottomCornerRadiusPx = 0,
            fixedBottomPaddingPx = fixedPx,
            maxCornerTotalPaddingPx = maxCornerPx,
            minTotalPaddingPx = minTotalPx,
        )
        assertEquals(minTotalPx - fixedPx, flatScreenExtraPx)
    }
}
