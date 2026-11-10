package io.github.mangi.eta.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * 聊天消息底部操作栏与统计卡片使用的统一等线宽（16x16 视口、1.35px 描边）线性图标集，
 * 避免实心图标与细线图标混排造成视觉重心失衡。
 */
internal object ChatActionIcons {
    private const val STROKE = 1.35f

    val Copy: ImageVector by lazy {
        icon("ChatCopy") {
            // 前景圆角方框
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(3.6f, 4.4f)
                horizontalLineTo(9.8f)
                quadTo(11.6f, 4.4f, 11.6f, 6.2f)
                verticalLineTo(12.4f)
                quadTo(11.6f, 14.2f, 9.8f, 14.2f)
                horizontalLineTo(3.6f)
                quadTo(1.8f, 14.2f, 1.8f, 12.4f)
                verticalLineTo(6.2f)
                quadTo(1.8f, 4.4f, 3.6f, 4.4f)
                close()
            }
            // 后景折角边线
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(5.2f, 4.2f)
                verticalLineTo(3.6f)
                quadTo(5.2f, 1.8f, 7.0f, 1.8f)
                horizontalLineTo(12.4f)
                quadTo(14.2f, 1.8f, 14.2f, 3.6f)
                verticalLineTo(9.4f)
                quadTo(14.2f, 11.2f, 12.4f, 11.2f)
                horizontalLineTo(11.8f)
            }
        }
    }

    val Check: ImageVector by lazy {
        icon("ChatCheck") {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(2.4f, 8.4f)
                lineTo(6.1f, 12.0f)
                lineTo(13.6f, 4.2f)
            }
        }
    }

    val Volume: ImageVector by lazy {
        icon("ChatVolume") {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(2.2f, 6.3f)
                horizontalLineTo(4.6f)
                lineTo(8.1f, 3.3f)
                verticalLineTo(12.7f)
                lineTo(4.6f, 9.7f)
                horizontalLineTo(2.2f)
                quadTo(1.6f, 9.7f, 1.6f, 9.1f)
                verticalLineTo(6.9f)
                quadTo(1.6f, 6.3f, 2.2f, 6.3f)
                close()
            }
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round,
            ) {
                moveTo(10.6f, 5.7f)
                quadTo(12.1f, 8.0f, 10.6f, 10.3f)
                moveTo(12.6f, 3.9f)
                quadTo(15.0f, 8.0f, 12.6f, 12.1f)
            }
        }
    }

    val Stop: ImageVector by lazy {
        icon("ChatStop") {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(4.6f, 3.2f)
                horizontalLineTo(11.4f)
                quadTo(12.8f, 3.2f, 12.8f, 4.6f)
                verticalLineTo(11.4f)
                quadTo(12.8f, 12.8f, 11.4f, 12.8f)
                horizontalLineTo(4.6f)
                quadTo(3.2f, 12.8f, 3.2f, 11.4f)
                verticalLineTo(4.6f)
                quadTo(3.2f, 3.2f, 4.6f, 3.2f)
                close()
            }
        }
    }

    val Refresh: ImageVector by lazy {
        icon("ChatRefresh") {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(13.8f, 8.2f)
                arcTo(
                    horizontalEllipseRadius = 5.9f,
                    verticalEllipseRadius = 5.9f,
                    theta = 0f,
                    isMoreThanHalf = true,
                    isPositiveArc = true,
                    x1 = 12.3f,
                    y1 = 4.0f,
                )
                lineTo(13.8f, 5.4f)
            }
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(13.8f, 2.2f)
                verticalLineTo(5.4f)
                horizontalLineTo(10.6f)
            }
        }
    }

    val Trash: ImageVector by lazy {
        icon("ChatTrash") {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(2.0f, 4.0f)
                horizontalLineTo(14.0f)
                moveTo(5.6f, 4.0f)
                verticalLineTo(2.7f)
                quadTo(5.6f, 1.9f, 6.5f, 1.9f)
                horizontalLineTo(9.5f)
                quadTo(10.4f, 1.9f, 10.4f, 2.7f)
                verticalLineTo(4.0f)
                moveTo(3.1f, 4.0f)
                lineTo(3.7f, 13.0f)
                quadTo(3.8f, 14.1f, 4.9f, 14.1f)
                horizontalLineTo(11.1f)
                quadTo(12.2f, 14.1f, 12.3f, 13.0f)
                lineTo(12.9f, 4.0f)
                moveTo(6.5f, 7.0f)
                verticalLineTo(11.0f)
                moveTo(9.5f, 7.0f)
                verticalLineTo(11.0f)
            }
        }
    }

    val Branch: ImageVector by lazy {
        icon("ChatBranch") {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(1.5f, 8.0f)
                horizontalLineTo(6.0f)
                quadTo(7.6f, 8.0f, 8.9f, 6.7f)
                lineTo(11.1f, 4.5f)
                moveTo(5.8f, 8.0f)
                quadTo(7.6f, 8.0f, 8.9f, 9.3f)
                lineTo(11.1f, 11.5f)
            }
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = STROKE,
            ) {
                moveTo(13.9f, 3.3f)
                arcTo(1.55f, 1.55f, 0f, isMoreThanHalf = true, isPositiveArc = true, 10.8f, 3.3f)
                arcTo(1.55f, 1.55f, 0f, isMoreThanHalf = true, isPositiveArc = true, 13.9f, 3.3f)
                moveTo(13.9f, 12.7f)
                arcTo(1.55f, 1.55f, 0f, isMoreThanHalf = true, isPositiveArc = true, 10.8f, 12.7f)
                arcTo(1.55f, 1.55f, 0f, isMoreThanHalf = true, isPositiveArc = true, 13.9f, 12.7f)
            }
        }
    }

    val Edit: ImageVector by lazy {
        icon("ChatEdit") {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(8.5f, 2.5f)
                horizontalLineTo(4.0f)
                quadTo(2.2f, 2.5f, 2.2f, 4.3f)
                verticalLineTo(12.0f)
                quadTo(2.2f, 13.8f, 4.0f, 13.8f)
                horizontalLineTo(11.7f)
                quadTo(13.5f, 13.8f, 13.5f, 12.0f)
                verticalLineTo(7.8f)
                moveTo(7.6f, 8.4f)
                lineTo(13.8f, 2.2f)
            }
        }
    }

    val Gauge: ImageVector by lazy {
        icon("ChatGauge") {
            // 仪表盘外弧（从左下 135° 顺时针到右下 45°）
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(3.4f, 13.1f)
                arcTo(
                    horizontalEllipseRadius = 6.5f,
                    verticalEllipseRadius = 6.5f,
                    theta = 0f,
                    isMoreThanHalf = true,
                    isPositiveArc = true,
                    x1 = 12.6f,
                    y1 = 13.1f,
                )
            }
            // 指针
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round,
            ) {
                moveTo(8.0f, 8.5f)
                lineTo(11.5f, 5.0f)
            }
            // 中心轴承圆点
            path(fill = SolidColor(Color.Black)) {
                moveTo(9.2f, 8.5f)
                arcTo(1.2f, 1.2f, 0f, isMoreThanHalf = true, isPositiveArc = true, 6.8f, 8.5f)
                arcTo(1.2f, 1.2f, 0f, isMoreThanHalf = true, isPositiveArc = true, 9.2f, 8.5f)
                close()
            }
        }
    }

    val Database: ImageVector by lazy {
        icon("ChatDatabase") {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                // 顶部椭圆
                moveTo(13.8f, 3.8f)
                arcTo(5.8f, 2.2f, 0f, isMoreThanHalf = true, isPositiveArc = true, 2.2f, 3.8f)
                arcTo(5.8f, 2.2f, 0f, isMoreThanHalf = true, isPositiveArc = true, 13.8f, 3.8f)
                // 两侧竖线与中底弧线
                moveTo(2.2f, 3.8f)
                verticalLineTo(11.8f)
                arcTo(5.8f, 2.2f, 0f, isMoreThanHalf = false, isPositiveArc = false, 13.8f, 11.8f)
                verticalLineTo(3.8f)
                moveTo(2.2f, 7.8f)
                arcTo(5.8f, 2.2f, 0f, isMoreThanHalf = false, isPositiveArc = false, 13.8f, 7.8f)
            }
        }
    }

    /** 顶栏与菜单共用的“新建对话”线性气泡加号图标（24x24 视口）。 */
    val NewChat: ImageVector by lazy {
        ImageVector.Builder(
            name = "ChatNewConversation",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.85f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(4.38f, 16.4f)
                arcTo(
                    horizontalEllipseRadius = 8.8f,
                    verticalEllipseRadius = 8.8f,
                    theta = 0f,
                    isMoreThanHalf = true,
                    isPositiveArc = true,
                    x1 = 7.6f,
                    y1 = 19.62f,
                )
                lineTo(2.9f, 21.1f)
                close()
            }
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.85f,
                strokeLineCap = StrokeCap.Round,
            ) {
                moveTo(8.7f, 12f)
                horizontalLineTo(15.3f)
                moveTo(12f, 8.7f)
                verticalLineTo(15.3f)
            }
        }.build()
    }

    private inline fun icon(
        name: String,
        block: ImageVector.Builder.() -> Unit,
    ): ImageVector = ImageVector.Builder(
        name = name,
        defaultWidth = 16.dp,
        defaultHeight = 16.dp,
        viewportWidth = 16f,
        viewportHeight = 16f,
    ).apply(block).build()
}
