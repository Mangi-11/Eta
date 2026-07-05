package fuck.andes.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import fuck.andes.ui.model.PermissionStatusUi
import fuck.andes.ui.model.RunStatusUi
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import androidx.compose.ui.res.painterResource
import com.composables.icons.lucide.R as LucideR
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.runtime.Composable

// 语义状态色
val StatusSuccess = Color(0xFF00BD13)
val StatusWarning = Color(0xFFFFB200)
val StatusError: Color @Composable get() = MiuixTheme.colorScheme.error
val StatusRunning: Color @Composable get() = MiuixTheme.colorScheme.primary
val StatusIdle: Color @Composable get() = MiuixTheme.colorScheme.onSurfaceVariantSummary

// 图标 tint 色系（与设置页一致，统一使用 ColorOS 设置主色）
// 不要用 coui_color_*_variant、截图平均取样色或 Material/iOS 近似色替代。
val IconTintBlue = Color(0xFF0066FF)
val IconTintGreen = Color(0xFF00BD13)
val IconTintPurple = Color(0xFF0066FF)
val IconTintOrange = Color(0xFFFF7700)

// ── RunStatusUi 映射 ──────────────────────────────────────────────────

@Composable
fun RunStatusUi.color(): Color = when (this) {
    RunStatusUi.Running -> StatusRunning
    RunStatusUi.Success -> StatusSuccess
    RunStatusUi.Failed -> StatusError
    RunStatusUi.Cancelled -> StatusIdle
}

fun RunStatusUi.label(): String = when (this) {
    RunStatusUi.Running -> "运行中"
    RunStatusUi.Success -> "已完成"
    RunStatusUi.Failed -> "失败"
    RunStatusUi.Cancelled -> "已取消"
}

// ── PermissionStatusUi 映射 ───────────────────────────────────────────

@Composable
fun PermissionStatusUi.color(): Color = when (this) {
    PermissionStatusUi.Available -> StatusIdle
    PermissionStatusUi.Warning -> StatusWarning
    PermissionStatusUi.Missing -> StatusError
    PermissionStatusUi.Disabled -> StatusIdle
}

fun PermissionStatusUi.label(): String = when (this) {
    PermissionStatusUi.Available -> "已就绪"
    PermissionStatusUi.Warning -> "需注意"
    PermissionStatusUi.Missing -> "未授权"
    PermissionStatusUi.Disabled -> "已禁用"
}

// ── 共享 UI 组件 ──────────────────────────────────────────────────────

/** 带彩色 tint 的图标，用于列表项左侧（与设置页风格一致）。 */
@Composable
fun TintedIcon(
    icon: ImageVector,
    tint: Color,
) {
    Icon(
        imageVector = icon,
        contentDescription = null,
        modifier = Modifier.padding(end = 16.dp).size(24.dp),
        tint = tint,
    )
}

/** Card 内分隔线，缩进对齐 BasicComponent 文字起始位置。 */
@Composable
fun PrefDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 64.dp),
    )
}

/**
 * 带箭头的列表项，复刻 ArrowPreference 行为但 title 与 summary 之间有 2dp 呼吸间距。
 * 用于需要 title + summary + 右侧箭头的场景。
 */
@Composable
fun ArrowItem(
    title: String,
    modifier: Modifier = Modifier,
    summary: String? = null,
    startAction: @Composable (() -> Unit)? = null,
    endActions: @Composable RowScope.() -> Unit = {},
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
) {
    BasicComponent(
        modifier = modifier,
        insideMargin = PaddingValues(16.dp),
        startAction = startAction,
        endActions = {
            Row(
                modifier = Modifier.padding(end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                endActions()
            }
            Icon(
                painter = painterResource(LucideR.drawable.lucide_ic_chevron_right),
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        },
        onClick = onClick,
        enabled = enabled,
    ) {
        Text(
            text = title,
            fontSize = MiuixTheme.textStyles.headline1.fontSize,
            fontWeight = FontWeight.Medium,
            color = if (enabled) MiuixTheme.colorScheme.onBackground
                else MiuixTheme.colorScheme.disabledOnSurface,
        )
        if (summary != null) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = summary,
                fontSize = MiuixTheme.textStyles.body2.fontSize,
                color = if (enabled) MiuixTheme.colorScheme.onSurfaceVariantSummary
                    else MiuixTheme.colorScheme.disabledOnSurface,
            )
        }
    }
}
