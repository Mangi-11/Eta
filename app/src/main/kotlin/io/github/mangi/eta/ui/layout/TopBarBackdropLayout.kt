package io.github.mangi.eta.ui.layout

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth

@Composable
internal fun TopBarBackdropLayout(
    backgroundHeight: Density.(barHeight: Int) -> Int,
    modifier: Modifier = Modifier,
    backgroundModifier: Modifier = Modifier,
    background: @Composable BoxScope.() -> Unit = {},
    content: @Composable () -> Unit,
) {
    Layout(
        modifier = modifier,
        content = {
            Box(modifier = backgroundModifier, content = background)
            Box { content() }
        },
    ) { (backgroundMeasurable, contentMeasurable), constraints ->
        val foreground = contentMeasurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
        val width = constraints.constrainWidth(foreground.width)
        val height = constraints.constrainHeight(foreground.height)
        val backdrop = backgroundMeasurable.measure(Constraints.fixed(width, backgroundHeight(height)))
        // 向下延伸的背景只占绘制空间，列表避让量由顶栏内容决定。
        layout(width, height) {
            backdrop.placeRelative(0, 0)
            foreground.placeRelative(0, 0)
        }
    }
}
