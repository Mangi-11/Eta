package io.github.mangi.eta.ui.components

import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test
import top.yukonga.miuix.kmp.basic.PopupPositionProvider

class InputPopupPositionProviderTest {
    @Test
    fun calculatePosition_alignsToWindowEndAboveInputContainer() {
        val result = InputPopupPositionProvider(
            inputContainerTopPx = 1_800,
            windowHorizontalInsetPx = 40,
        ).calculatePosition(
            anchorBounds = IntRect(left = 400, top = 1_850, right = 500, bottom = 1_900),
            windowBounds = IntRect(left = 0, top = 72, right = 1_080, bottom = 2_200),
            layoutDirection = LayoutDirection.Ltr,
            popupContentSize = IntSize(width = 300, height = 600),
            popupMargin = IntRect(left = 0, top = 24, right = 0, bottom = 24),
            alignment = PopupPositionProvider.Align.TopEnd,
        )

        assertEquals(740, result.x)
        assertEquals(1_176, result.y)
    }

    @Test
    fun calculatePosition_clampsToSafeWindowBounds() {
        val result = InputPopupPositionProvider(inputContainerTopPx = 300).calculatePosition(
            anchorBounds = IntRect(left = 80, top = 320, right = 160, bottom = 360),
            windowBounds = IntRect(left = 24, top = 72, right = 1_056, bottom = 2_200),
            layoutDirection = LayoutDirection.Ltr,
            popupContentSize = IntSize(width = 400, height = 500),
            popupMargin = IntRect(left = 0, top = 24, right = 0, bottom = 24),
            alignment = PopupPositionProvider.Align.TopEnd,
        )

        assertEquals(24, result.x)
        assertEquals(96, result.y)
    }

    @Test
    fun calculateInputBarPopupLayout_placesBottomAboveInputWhenKeyboardClosed() {
        val density = 3f
        val layout = calculateInputBarPopupLayout(
            inputContainerTopPx = 2_700f,
            anchorTopPx = 2_820f,
            anchorBottomPx = 2_910f,
            measuredHeightPx = 600f,
            statusBarTopPx = 120f,
            windowBottomPx = 3_000f,
            safeMarginPx = 36f,
            marginPx = 24f,
            density = density,
        )

        // 目标底边 = 2700 - 24 = 2676；未开键盘时向下空间不足，走底部对齐：gap = 2676 - 2910 = -234px = -78dp
        assertEquals((-78).dp, layout.gap)
    }

    @Test
    fun calculateInputBarPopupLayout_keepsPopupAboveInputWhenKeyboardOpen() {
        val density = 3f
        val anchorTop = 1_620f
        val measuredHeight = 600f
        val layout = calculateInputBarPopupLayout(
            inputContainerTopPx = 1_500f,
            anchorTopPx = anchorTop,
            anchorBottomPx = 1_710f,
            measuredHeightPx = measuredHeight,
            statusBarTopPx = 120f,
            windowBottomPx = 3_000f,
            safeMarginPx = 36f,
            marginPx = 24f,
            density = density,
        )

        // 开键盘时窗口底边仍为 3000，GlassPopupSurface 会选中向下展开（top = anchorTop + gap），
        // gap 应自动补偿高度使底边仍落在 1500 - 24 = 1476：top = 1476 - 600 = 876 => gap = 876 - 1620 = -744px = -248dp
        assertEquals((-248).dp, layout.gap)
        val computedTop = anchorTop + layout.gap.value * density
        assertEquals(1_476f, computedTop + measuredHeight, 0.01f)
    }
}
