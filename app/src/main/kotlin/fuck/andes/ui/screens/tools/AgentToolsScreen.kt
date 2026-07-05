package fuck.andes.ui.screens.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.R as LucideR
import fuck.andes.ui.model.AgentToolsAction
import fuck.andes.ui.model.AgentToolsUiState
import fuck.andes.ui.model.ToolItemUi
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

private object ToolsMetrics {
    val ScreenBottomPadding = 24.dp
    val SectionHorizontalPadding = 20.dp
    val SectionTopPadding = 24.dp
    val SectionBottomPadding = 12.dp
    val GridHorizontalPadding = 20.dp
    val GridGap = 12.dp
    val CardMinHeight = 136.dp
    val CardInsidePadding = 16.dp
    val IconContainerSize = 40.dp
    val IconSize = 20.dp
    val IconTitleGap = 12.dp
    val TitleSummaryGap = 2.dp
}

@Composable
fun AgentToolsScreen(
    state: AgentToolsUiState,
    onAction: (AgentToolsAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(MiuixTheme.colorScheme.surface),
        contentPadding = PaddingValues(bottom = ToolsMetrics.ScreenBottomPadding),
    ) {
        state.groups.forEach { group ->
            item(key = "${group.id}-title") {
                ToolSectionTitle(text = group.title)
            }
            items(
                items = group.tools.chunked(2),
                key = { row -> "${group.id}-${row.joinToString(separator = "-") { it.id }}" },
            ) { row ->
                ToolGridRow(tools = row)
            }
        }
    }
}

@Composable
private fun ToolSectionTitle(
    text: String,
) {
    Text(
        text = text,
        modifier = Modifier.padding(
            start = ToolsMetrics.SectionHorizontalPadding,
            top = ToolsMetrics.SectionTopPadding,
            end = ToolsMetrics.SectionHorizontalPadding,
            bottom = ToolsMetrics.SectionBottomPadding,
        ),
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        style = MiuixTheme.textStyles.footnote1,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun ToolGridRow(
    tools: List<ToolItemUi>,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ToolsMetrics.GridHorizontalPadding)
            .padding(bottom = ToolsMetrics.GridGap),
        horizontalArrangement = Arrangement.spacedBy(ToolsMetrics.GridGap),
    ) {
        tools.forEach { tool ->
            ToolCard(
                tool = tool,
                modifier = Modifier.weight(1f),
            )
        }
        if (tools.size == 1) {
            Spacer(modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun ToolCard(
    tool: ToolItemUi,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .heightIn(min = ToolsMetrics.CardMinHeight),
        cornerRadius = CardDefaults.CornerRadius,
        insideMargin = PaddingValues(ToolsMetrics.CardInsidePadding),
        colors = CardDefaults.defaultColors(
            color = MiuixTheme.colorScheme.surfaceContainer,
            contentColor = MiuixTheme.colorScheme.onSurfaceContainer,
        ),
    ) {
        Box(
            modifier = Modifier
                .size(ToolsMetrics.IconContainerSize)
                .background(colorForTool(tool.id), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(iconForTool(tool.id)),
                contentDescription = null,
                modifier = Modifier.size(ToolsMetrics.IconSize),
                tint = Color.White,
            )
        }
        Spacer(modifier = Modifier.height(ToolsMetrics.IconTitleGap))
        Text(
            text = tool.title,
            color = MiuixTheme.colorScheme.onSurfaceContainer,
            style = MiuixTheme.textStyles.body2,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.height(ToolsMetrics.TitleSummaryGap))
        Text(
            text = tool.summary,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            style = MiuixTheme.textStyles.footnote1,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun colorForTool(toolId: String): Color = when (toolId) {
    // 与设置页保持一致：圆形图标底色统一使用 ColorOS 设置主色，不使用 variant/截图取样色。
    // 屏幕与控件
    "observe", "observe_screen",
    "click", "tap_element",
    "tap_area", "long_press",
    "swipe", "scroll" -> Color(0xFFFF7700)
    
    // 文本与剪贴板
    "clipboard", "paste_text",
    "input_text", "replace_text",
    "clear_text", "wait_text",
    "wait_for_text" -> Color(0xFF0066FF)
    
    // 应用与系统
    "search_apps", "open_app",
    "launch_app", "open_uri",
    "press_key", "open_system_panel" -> Color(0xFF00BD13)
    
    // 终端与文件
    "terminal", "terminal_job",
    "run_command", "read_file",
    "write_file", "list_directory" -> Color(0xFFFFB200)
    
    else -> Color(0xFF0066FF)
}

private fun iconForTool(toolId: String): Int = when (toolId) {
    "observe", "observe_screen" -> LucideR.drawable.lucide_ic_scan_text
    "click", "tap_element" -> LucideR.drawable.lucide_ic_mouse_pointer_click
    "tap_area" -> LucideR.drawable.lucide_ic_locate_fixed
    "long_press" -> LucideR.drawable.lucide_ic_hand
    "swipe" -> LucideR.drawable.lucide_ic_move
    "scroll" -> LucideR.drawable.lucide_ic_scroll
    "clipboard", "paste_text" -> LucideR.drawable.lucide_ic_clipboard_paste
    "input_text" -> LucideR.drawable.lucide_ic_keyboard
    "replace_text" -> LucideR.drawable.lucide_ic_replace
    "clear_text" -> LucideR.drawable.lucide_ic_eraser
    "wait_text", "wait_for_text" -> LucideR.drawable.lucide_ic_clock
    "search_apps" -> LucideR.drawable.lucide_ic_package_search
    "open_app", "launch_app" -> LucideR.drawable.lucide_ic_app_window
    "open_uri" -> LucideR.drawable.lucide_ic_external_link
    "press_key" -> LucideR.drawable.lucide_ic_smartphone
    "open_system_panel" -> LucideR.drawable.lucide_ic_panel_top_open
    "terminal", "terminal_job", "run_command" -> LucideR.drawable.lucide_ic_square_terminal
    "read_file" -> LucideR.drawable.lucide_ic_file_text
    "write_file" -> LucideR.drawable.lucide_ic_file_pen
    "list_directory" -> LucideR.drawable.lucide_ic_folder_open
    else -> LucideR.drawable.lucide_ic_settings
}
