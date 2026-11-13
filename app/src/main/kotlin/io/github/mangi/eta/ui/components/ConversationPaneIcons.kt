package io.github.mangi.eta.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * 侧边栏专用等线宽（24x24 视口、1.75px 圆角描边）图标集。
 *
 * 保持功能入口、底栏操作与历史标识之间的视觉粗细一致，避免混用实心与粗细不一的系统图标。
 */
internal object ConversationPaneIcons {
    private const val STROKE = 1.75f

    /**
     * Eta（η）专属品牌几何标识：左上起笔带轻盈弧挑，拱肩顺滑延展为修长右笔。
     * 配合实色圆形印章作为反白核心图形，避免与通用星芒图标撞脸。
     */
    val BrandEta: ImageVector by lazy {
        vector24("PaneBrandEta") {
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
        }
    }

    val Model: ImageVector by lazy {
        vector24("PaneModel") {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(8.2f, 5.4f)
                horizontalLineTo(15.8f)
                quadTo(18.6f, 5.4f, 18.6f, 8.2f)
                verticalLineTo(15.8f)
                quadTo(18.6f, 18.6f, 15.8f, 18.6f)
                horizontalLineTo(8.2f)
                quadTo(5.4f, 18.6f, 5.4f, 15.8f)
                verticalLineTo(8.2f)
                quadTo(5.4f, 5.4f, 8.2f, 5.4f)
                close()
                // 四周对称引脚
                moveTo(9.6f, 2.8f)
                verticalLineTo(5.4f)
                moveTo(14.4f, 2.8f)
                verticalLineTo(5.4f)
                moveTo(9.6f, 18.6f)
                verticalLineTo(21.2f)
                moveTo(14.4f, 18.6f)
                verticalLineTo(21.2f)
                moveTo(2.8f, 9.6f)
                horizontalLineTo(5.4f)
                moveTo(2.8f, 14.4f)
                horizontalLineTo(5.4f)
                moveTo(18.6f, 9.6f)
                horizontalLineTo(21.2f)
                moveTo(18.6f, 14.4f)
                horizontalLineTo(21.2f)
            }
            path(fill = SolidColor(Color.Black)) {
                moveTo(12.0f, 8.9f)
                quadTo(12.45f, 11.55f, 15.1f, 12.0f)
                quadTo(12.45f, 12.45f, 12.0f, 15.1f)
                quadTo(11.55f, 12.45f, 8.9f, 12.0f)
                quadTo(11.55f, 11.55f, 12.0f, 8.9f)
                close()
            }
        }
    }

    val Character: ImageVector by lazy {
        vector24("PaneCharacter") {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(14.3f, 8.3f)
                arcTo(3.3f, 3.3f, 0f, isMoreThanHalf = true, isPositiveArc = true, 7.7f, 8.3f)
                arcTo(3.3f, 3.3f, 0f, isMoreThanHalf = true, isPositiveArc = true, 14.3f, 8.3f)
                close()
                moveTo(4.6f, 19.6f)
                curveTo(5.1f, 15.9f, 7.9f, 14.2f, 11.0f, 14.2f)
                curveTo(14.1f, 14.2f, 16.9f, 15.9f, 17.4f, 19.6f)
                moveTo(18.7f, 4.3f)
                verticalLineTo(7.5f)
                moveTo(17.1f, 5.9f)
                horizontalLineTo(20.3f)
            }
        }
    }

    val Tools: ImageVector by lazy {
        vector24("PaneTools") {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                // 左上
                roundedSquare(4.2f, 4.2f, 6.2f, 1.8f)
                // 左下
                roundedSquare(4.2f, 13.6f, 6.2f, 1.8f)
                // 右下
                roundedSquare(13.6f, 13.6f, 6.2f, 1.8f)
                // 右上旋转微菱格
                moveTo(16.7f, 3.7f)
                lineTo(20.3f, 7.3f)
                lineTo(16.7f, 10.9f)
                lineTo(13.1f, 7.3f)
                close()
            }
        }
    }

    val Skills: ImageVector by lazy {
        vector24("PaneSkills") {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(12.0f, 6.2f)
                curveTo(9.8f, 4.7f, 6.4f, 4.4f, 3.6f, 5.3f)
                verticalLineTo(18.1f)
                curveTo(6.4f, 17.2f, 9.8f, 17.5f, 12.0f, 19.0f)
                moveTo(12.0f, 6.2f)
                curveTo(14.2f, 4.7f, 17.6f, 4.4f, 20.4f, 5.3f)
                verticalLineTo(18.1f)
                curveTo(17.6f, 17.2f, 14.2f, 17.5f, 12.0f, 19.0f)
                moveTo(12.0f, 6.2f)
                verticalLineTo(19.0f)
            }
        }
    }

    val Permissions: ImageVector by lazy {
        vector24("PanePermissions") {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(12.0f, 3.2f)
                lineTo(18.5f, 5.7f)
                quadTo(19.3f, 6.0f, 19.3f, 6.9f)
                verticalLineTo(11.3f)
                curveTo(19.3f, 15.7f, 16.1f, 19.1f, 12.0f, 20.8f)
                curveTo(7.9f, 19.1f, 4.7f, 15.7f, 4.7f, 11.3f)
                verticalLineTo(6.9f)
                quadTo(4.7f, 6.0f, 5.5f, 5.7f)
                close()
                moveTo(9.2f, 11.9f)
                lineTo(11.1f, 13.8f)
                lineTo(15.0f, 9.9f)
            }
        }
    }

    val Settings: ImageVector by lazy {
        vector24("PaneSettings") {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                // 中心圆环
                moveTo(14.9f, 12.0f)
                arcTo(2.9f, 2.9f, 0f, isMoreThanHalf = true, isPositiveArc = true, 9.1f, 12.0f)
                arcTo(2.9f, 2.9f, 0f, isMoreThanHalf = true, isPositiveArc = true, 14.9f, 12.0f)
                close()
                // 对称柔弧 6 瓣齿轮外轮廓
                moveTo(12.00f, 3.00f)
                curveTo(13.65f, 3.00f, 13.97f, 5.29f, 15.40f, 6.11f)
                curveTo(16.83f, 6.94f, 18.97f, 6.07f, 19.79f, 7.50f)
                curveTo(20.62f, 8.93f, 18.80f, 10.35f, 18.80f, 12.00f)
                curveTo(18.80f, 13.65f, 20.62f, 15.07f, 19.79f, 16.50f)
                curveTo(18.97f, 17.93f, 16.83f, 17.06f, 15.40f, 17.89f)
                curveTo(13.97f, 18.71f, 13.65f, 21.00f, 12.00f, 21.00f)
                curveTo(10.35f, 21.00f, 10.03f, 18.71f, 8.60f, 17.89f)
                curveTo(7.17f, 17.06f, 5.03f, 17.93f, 4.21f, 16.50f)
                curveTo(3.38f, 15.07f, 5.20f, 13.65f, 5.20f, 12.00f)
                curveTo(5.20f, 10.35f, 3.38f, 8.93f, 4.21f, 7.50f)
                curveTo(5.03f, 6.07f, 7.17f, 6.94f, 8.60f, 6.11f)
                curveTo(10.03f, 5.29f, 10.35f, 3.00f, 12.00f, 3.00f)
                close()
            }
        }
    }

    val Search: ImageVector by lazy {
        vector24("PaneSearch") {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.85f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(17.1f, 10.8f)
                arcTo(6.3f, 6.3f, 0f, isMoreThanHalf = true, isPositiveArc = true, 4.5f, 10.8f)
                arcTo(6.3f, 6.3f, 0f, isMoreThanHalf = true, isPositiveArc = true, 17.1f, 10.8f)
                close()
                moveTo(15.4f, 15.4f)
                lineTo(20.0f, 20.0f)
            }
        }
    }

    val Pin: ImageVector by lazy {
        ImageVector.Builder(
            name = "PanePin",
            defaultWidth = 16.dp,
            defaultHeight = 16.dp,
            viewportWidth = 16f,
            viewportHeight = 16f,
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.4f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(9.4f, 2.3f)
                lineTo(13.7f, 6.6f)
                moveTo(10.1f, 3.0f)
                lineTo(6.9f, 6.2f)
                lineTo(4.3f, 6.6f)
                lineTo(9.4f, 11.7f)
                lineTo(9.8f, 9.1f)
                lineTo(13.0f, 5.9f)
                moveTo(6.8f, 9.2f)
                lineTo(2.6f, 13.4f)
            }
        }.build()
    }

    val ChevronRight: ImageVector by lazy {
        ImageVector.Builder(
            name = "PaneChevronRight",
            defaultWidth = 16.dp,
            defaultHeight = 16.dp,
            viewportWidth = 16f,
            viewportHeight = 16f,
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.6f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(6.1f, 3.7f)
                lineTo(10.4f, 8.0f)
                lineTo(6.1f, 12.3f)
            }
        }.build()
    }

    private inline fun vector24(
        name: String,
        block: ImageVector.Builder.() -> Unit,
    ): ImageVector = ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply(block).build()

    private fun androidx.compose.ui.graphics.vector.PathBuilder.roundedSquare(
        left: Float,
        top: Float,
        size: Float,
        radius: Float,
    ) {
        val right = left + size
        val bottom = top + size
        moveTo(left + radius, top)
        horizontalLineTo(right - radius)
        quadTo(right, top, right, top + radius)
        verticalLineTo(bottom - radius)
        quadTo(right, bottom, right - radius, bottom)
        horizontalLineTo(left + radius)
        quadTo(left, bottom, left, bottom - radius)
        verticalLineTo(top + radius)
        quadTo(left, top, left + radius, top)
        close()
    }
}
