package fuck.andes.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fuck.andes.ui.model.RunTimelineItemUi
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.Check
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Contacts
import top.yukonga.miuix.kmp.icon.extended.ConvertFile
import top.yukonga.miuix.kmp.icon.extended.Image
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.More
import top.yukonga.miuix.kmp.icon.extended.Tune
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun RunTimeline(
    items: List<RunTimelineItemUi>,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.padding(horizontal = 12.dp)) {
        items.forEachIndexed { index, item ->
            val isLast = index == items.lastIndex
            TimelineRow(
                item = item,
                isLast = isLast,
            )
        }
    }
}

@Composable
private fun TimelineRow(
    item: RunTimelineItemUi,
    isLast: Boolean,
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.width(28.dp),
        ) {
            TimelineNode(item = item)
            if (!isLast) {
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .weight(1f)
                        .background(MiuixTheme.colorScheme.dividerLine),
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        TimelineContent(
            item = item,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun TimelineNode(item: RunTimelineItemUi) {
    val tint = when (item) {
        is RunTimelineItemUi.Error -> MiuixTheme.colorScheme.onSurfaceVariantActions
        is RunTimelineItemUi.FinalResult -> MiuixTheme.colorScheme.primary
        else -> MiuixTheme.colorScheme.onSurfaceVariantActions
    }
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(MiuixTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        when (item) {
            is RunTimelineItemUi.UserRequest -> Icon(
                imageVector = MiuixIcons.Contacts,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = tint,
            )
            is RunTimelineItemUi.ModelThinking -> Icon(
                imageVector = MiuixIcons.Info,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = tint,
            )
            is RunTimelineItemUi.ToolCall,
            is RunTimelineItemUi.ToolResult -> Icon(
                imageVector = MiuixIcons.Tune,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = tint,
            )
            is RunTimelineItemUi.Screenshot -> Icon(
                imageVector = MiuixIcons.Image,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = tint,
            )
            is RunTimelineItemUi.TerminalOutput -> Icon(
                imageVector = MiuixIcons.ConvertFile,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = tint,
            )
            is RunTimelineItemUi.FinalResult -> Icon(
                imageVector = MiuixIcons.Basic.Check,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = tint,
            )
            is RunTimelineItemUi.Error -> Icon(
                imageVector = MiuixIcons.Close,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = tint,
            )
        }
    }
}

@Composable
private fun TimelineContent(
    item: RunTimelineItemUi,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            when (item) {
                is RunTimelineItemUi.UserRequest -> {
                    Text(
                        text = "任务",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = item.content,
                        style = MiuixTheme.textStyles.body1,
                        color = MiuixTheme.colorScheme.onSurfaceContainer,
                    )
                }
                is RunTimelineItemUi.ModelThinking -> {
                    Text(
                        text = "思考",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = item.content,
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
                is RunTimelineItemUi.ToolCall -> {
                    Text(
                        text = "调用工具 · ${item.toolName}",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = item.argumentsSummary,
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
                is RunTimelineItemUi.ToolResult -> {
                    Text(
                        text = if (item.success) "工具返回" else "工具失败",
                        style = MiuixTheme.textStyles.footnote1,
                        color = if (item.success) {
                            MiuixTheme.colorScheme.onSurfaceVariantActions
                        } else {
                            MiuixTheme.colorScheme.primary
                        },
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = item.summary,
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
                is RunTimelineItemUi.Screenshot -> {
                    Text(
                        text = "观察屏幕",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = item.description,
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
                is RunTimelineItemUi.TerminalOutput -> {
                    Text(
                        text = "终端 · ${item.command}",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = item.outputPreview,
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                is RunTimelineItemUi.FinalResult -> {
                    Text(
                        text = "完成",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = item.content,
                        style = MiuixTheme.textStyles.body1,
                        color = MiuixTheme.colorScheme.onSurfaceContainer,
                    )
                }
                is RunTimelineItemUi.Error -> {
                    Text(
                        text = "错误",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = item.message,
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        }
    }
}
