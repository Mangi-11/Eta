package fuck.andes.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.m3.Markdown
import fuck.andes.ui.model.AgentChatMessageUi
import fuck.andes.ui.model.AgentMessageUi
import fuck.andes.ui.model.RunTraceMessageUi
import fuck.andes.ui.model.SuggestionChipsMessageUi
import fuck.andes.ui.model.ThinkingMessageUi
import fuck.andes.ui.model.TokenUsageUi
import fuck.andes.ui.model.ToolActivityMessageUi
import fuck.andes.ui.model.ToolActivityStatusUi
import fuck.andes.ui.model.ToolSummaryMessageUi
import fuck.andes.ui.model.UserMessageUi
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.Check
import top.yukonga.miuix.kmp.icon.extended.Answer
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun ChatMessageItem(
    message: AgentChatMessageUi,
    onSuggestionClick: (String) -> Unit,
    onRunTraceClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (message) {
        is UserMessageUi -> UserMessageBubble(
            message = message,
            modifier = modifier,
        )
        is AgentMessageUi -> AgentMessageBubble(
            message = message,
            modifier = modifier,
        )
        is ThinkingMessageUi -> ThinkingCard(
            message = message,
            modifier = modifier,
        )
        is RunTraceMessageUi -> RunTraceCard(
            message = message,
            onClick = onRunTraceClick,
            modifier = modifier,
        )
        is ToolActivityMessageUi -> ToolActivityRow(
            message = message,
            modifier = modifier,
        )
        is ToolSummaryMessageUi -> ToolSummaryRow(
            message = message,
            modifier = modifier,
        )
        is SuggestionChipsMessageUi -> FlowRow(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            message.prompts.forEach { prompt ->
                TextButton(
                    text = prompt,
                    onClick = { onSuggestionClick(prompt) },
                )
            }
        }
    }
}

@Composable
private fun UserMessageBubble(
    message: UserMessageUi,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.End,
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(MiuixTheme.colorScheme.primary)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text(
                text = message.content,
                style = MiuixTheme.textStyles.body1,
                color = MiuixTheme.colorScheme.onPrimary,
            )
        }
    }
}

@Composable
private fun AgentMessageBubble(
    message: AgentMessageUi,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.Start,
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(MiuixTheme.colorScheme.surfaceContainerHigh)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Column {
                if (message.content.isBlank() && message.isStreaming) {
                    Text(
                        text = "正在回复…",
                        style = MiuixTheme.textStyles.body1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                } else if (message.renderMarkdown && message.content.isNotBlank()) {
                    Markdown(
                        content = message.content,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Text(
                        text = message.content,
                        style = MiuixTheme.textStyles.body1,
                        color = MiuixTheme.colorScheme.onSurfaceContainer,
                    )
                }
                message.usage?.takeUnless { it.isEmpty }?.let { usage ->
                    UsageFooter(
                        usage = usage,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ThinkingCard(
    message: ThinkingMessageUi,
    modifier: Modifier = Modifier,
) {
    var expanded by remember(message.id) { mutableStateOf(!message.collapsed) }
    LaunchedEffect(message.isStreaming, message.collapsed) {
        if (message.isStreaming) expanded = true
        if (!message.isStreaming && message.collapsed) expanded = false
    }
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        onClick = { expanded = !expanded },
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = MiuixIcons.Answer,
                    contentDescription = null,
                    tint = MiuixTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (message.isStreaming) {
                        "正在思考${message.elapsedSeconds?.let { " · 已处理 ${it}s" }.orEmpty()}"
                    } else {
                        "思考完成${message.elapsedSeconds?.let { "（用时 ${it}s）" }.orEmpty()}"
                    },
                    style = MiuixTheme.textStyles.subtitle,
                    color = MiuixTheme.colorScheme.onSurfaceContainer,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            AnimatedVisibility(visible = expanded && message.content.isNotBlank()) {
                Text(
                    text = message.content,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
        }
    }
}

@Composable
private fun ToolActivityRow(
    message: ToolActivityMessageUi,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(MiuixTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(message.status.statusColor()),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = message.toolName.toToolLabel(),
                style = MiuixTheme.textStyles.subtitle,
                color = MiuixTheme.colorScheme.onSurfaceContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val detail = message.resultSummary ?: message.argumentsSummary
            if (detail.isNotBlank()) {
                Text(
                    text = detail,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = message.status.statusLabel(),
            style = MiuixTheme.textStyles.body2,
            color = message.status.statusColor(),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UsageFooter(
    usage: TokenUsageUi,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        usage.contextTokens?.let { UsagePill("ctx:$it") }
        usage.inputTokens?.let { UsagePill("↓ $it") }
        usage.outputTokens?.let { UsagePill("↑ $it") }
        usage.reasoningTokens?.let { UsagePill("思考 $it") }
        usage.cachedTokens?.let { UsagePill("缓存 $it") }
    }
}

@Composable
private fun UsagePill(text: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(MiuixTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(
            text = text,
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            maxLines = 1,
        )
    }
}

@Composable
private fun RunTraceCard(
    message: RunTraceMessageUi,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        onClick = onClick,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = MiuixIcons.Basic.Check,
                    contentDescription = null,
                    tint = MiuixTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Run trace",
                    style = MiuixTheme.textStyles.headline1,
                    color = MiuixTheme.colorScheme.onSurfaceContainer,
                )
            }
            message.capabilities.forEach { capability ->
                Column(modifier = Modifier.padding(top = 12.dp)) {
                    Text(
                        text = capability.title,
                        style = MiuixTheme.textStyles.subtitle,
                        color = MiuixTheme.colorScheme.onSurfaceContainer,
                    )
                    capability.items.forEach { item ->
                        Text(
                            text = "• $item",
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ToolSummaryRow(
    message: ToolSummaryMessageUi,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        message.tools.forEach { tool ->
            ToolChip(text = tool)
        }
    }
}

@Composable
private fun ToolActivityStatusUi.statusColor() = when (this) {
    ToolActivityStatusUi.Running -> MiuixTheme.colorScheme.primary
    ToolActivityStatusUi.Success -> MiuixTheme.colorScheme.onSurfaceVariantActions
    ToolActivityStatusUi.Failed -> MiuixTheme.colorScheme.error
}

private fun ToolActivityStatusUi.statusLabel(): String = when (this) {
    ToolActivityStatusUi.Running -> "运行中"
    ToolActivityStatusUi.Success -> "成功"
    ToolActivityStatusUi.Failed -> "失败"
}

private fun String.toToolLabel(): String = when (this) {
    "observe_screen" -> "查看当前屏幕"
    "tap_element" -> "点击元素"
    "tap_area" -> "点击区域"
    "long_press" -> "长按"
    "swipe" -> "滑动"
    "scroll" -> "滚动"
    "input_text" -> "输入文字"
    "replace_text" -> "替换文字"
    "clear_text" -> "清空文字"
    "paste_text" -> "粘贴文字"
    "wait_for_text" -> "等待文本"
    "wait_for_package" -> "等待应用"
    "search_apps" -> "搜索应用"
    "launch_app" -> "打开应用"
    "open_uri" -> "打开链接"
    "press_key" -> "按键"
    "open_system_panel" -> "系统面板"
    "terminal" -> "会话终端"
    "run_command" -> "执行命令"
    "read_file" -> "读取文件"
    "write_file" -> "写入文件"
    "list_directory" -> "列目录"
    else -> this
}
