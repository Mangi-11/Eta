package io.github.mangi.eta.ui.components.coui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import io.github.suqi8.coui.kmp.basic.DropdownGroupDivider
import io.github.suqi8.coui.kmp.basic.DropdownImpl
import io.github.suqi8.coui.kmp.basic.DropdownItem
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.ListPopupColumn
import io.github.suqi8.coui.kmp.basic.ListPopupDefaults
import io.github.suqi8.coui.kmp.basic.PopupPositionProvider
import io.github.suqi8.coui.kmp.overlay.OverlayListPopup
import io.github.suqi8.coui.kmp.theme.COUITheme
import kotlin.math.roundToInt

internal data class CouiMenuItem<T>(
    val value: T,
    val text: String,
    val enabled: Boolean = true,
    val selected: Boolean = false,
    val icon: ImageVector? = null,
    val iconTint: Color = Color.Unspecified,
    val dividerBefore: Boolean = false,
)

internal class CouiMenuAnchor {
    var coordinates: LayoutCoordinates? = null
    private var bounds: IntRect? = null

    fun open(show: () -> Unit) {
        bounds = coordinates?.takeIf { it.isAttached }?.boundsInWindow()?.let {
            IntRect(it.left.roundToInt(), it.top.roundToInt(), it.right.roundToInt(), it.bottom.roundToInt())
        }
        show()
    }

    val positionProvider = object : PopupPositionProvider {
        override fun calculatePosition(
            anchorBounds: IntRect,
            windowBounds: IntRect,
            layoutDirection: LayoutDirection,
            popupContentSize: IntSize,
            popupMargin: IntRect,
            alignment: PopupPositionProvider.Align,
        ): IntOffset = ListPopupDefaults.DropdownPositionProvider.calculatePosition(
            bounds ?: anchorBounds, windowBounds, layoutDirection, popupContentSize, popupMargin, alignment,
        )

        override fun getMargins(): PaddingValues = ListPopupDefaults.DropdownPositionProvider.getMargins()
    }
}

@Composable
internal fun rememberCouiMenuAnchor(): CouiMenuAnchor = remember { CouiMenuAnchor() }

internal fun Modifier.couiMenuAnchor(anchor: CouiMenuAnchor): Modifier =
    onGloballyPositioned { anchor.coordinates = it }

@Composable
internal fun <T> CouiMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    items: List<CouiMenuItem<T>>,
    onItemClick: (CouiMenuItem<T>) -> Unit,
    anchor: CouiMenuAnchor,
) {
    val colors = COUITheme.colorScheme
    val reserveIconSlot = items.any { it.icon != null }
    OverlayListPopup(
        show = expanded && items.isNotEmpty(),
        onDismissRequest = onDismissRequest,
        popupPositionProvider = anchor.positionProvider,
    ) {
        ListPopupColumn {
            items.forEachIndexed { index, item ->
                if (index != 0 && item.dividerBefore) {
                    DropdownGroupDivider()
                }
                DropdownImpl(
                    item = DropdownItem(
                        text = item.text,
                        enabled = item.enabled,
                        selected = item.selected,
                        alert = item.iconTint == colors.error,
                        icon = item.icon?.let { icon ->
                            { modifier ->
                                Icon(
                                    imageVector = icon,
                                    contentDescription = null,
                                    modifier = modifier,
                                    tint = if (item.enabled) item.iconTint.takeOrElse { colors.labelPrimary }
                                        else colors.disabledNeutral,
                                )
                            }
                        },
                    ),
                    optionSize = items.size,
                    index = index,
                    isSelected = item.selected,
                    reserveIconSlot = reserveIconSlot,
                    onSelectedIndexChange = { onItemClick(item) },
                )
            }
        }
    }
}
