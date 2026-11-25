package io.github.mangi.eta.ui.components.pane

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * 侧边栏专用品牌矢量标识；功能与操作图标统一复用 `MiuixIcons.Glass`。
 */
internal object ConversationPaneIcons {
    /**
     * Eta（η）专属品牌几何标识：左上起笔带轻盈弧挑，拱肩顺滑延展为修长右笔。
     * 配合实色圆形印章作为反白核心图形，避免与通用星芒图标撞脸。
     */
    val BrandEta: ImageVector by lazy {
        ImageVector.Builder(
            name = "PaneBrandEta",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2.3f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(6.1f, 7.6f)
                quadTo(7.4f, 5.8f, 8.0f, 7.4f)
                lineTo(8.0f, 15.6f)
                moveTo(8.0f, 10.4f)
                curveTo(8.4f, 6.7f, 11.3f, 5.6f, 13.8f, 6.4f)
                curveTo(15.7f, 7.0f, 16.4f, 8.6f, 16.4f, 11.2f)
                lineTo(16.4f, 18.5f)
            }
        }.build()
    }
}
