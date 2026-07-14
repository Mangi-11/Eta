package fuck.andes.ui.components

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.R as LucideR
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownTable
import com.mikepenz.markdown.compose.elements.MarkdownTableHeader
import com.mikepenz.markdown.compose.elements.MarkdownTableRow
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.markdownDimens
import com.mikepenz.markdown.model.markdownPadding
import com.mikepenz.markdown.model.rememberMarkdownState
import com.mikepenz.markdown.model.rememberStreamingMarkdownState
import fuck.andes.ui.model.AgentChatMessageUi
import fuck.andes.ui.model.AgentMessageUi
import fuck.andes.ui.model.RunTraceMessageUi
import fuck.andes.ui.model.SuggestionChipsMessageUi
import fuck.andes.ui.model.ThinkingMessageUi
import fuck.andes.ui.model.ToolActivityMessageUi
import fuck.andes.ui.model.ToolActivityStatusUi
import fuck.andes.ui.model.ToolSummaryMessageUi
import fuck.andes.ui.model.UserMessageUi
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun rememberDataUrlBitmap(dataUrl: String) = remember(dataUrl) {
    val base64 = dataUrl.substringAfter("base64,", "")
    if (base64.isBlank()) null else {
        runCatching {
            val bytes = Base64.decode(base64, Base64.NO_WRAP)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        }.getOrNull()
    }
}

/**
 * 按语义片段推进流式文本，避免逐字符动画造成闪烁。
 */
@Composable
fun StreamingText(
    text: String,
    animate: Boolean = true,
    chunkDelayMs: Long = 15,
    content: @Composable (displayedText: String) -> Unit
) {
    var displayedText by remember { mutableStateOf(if (animate) "" else text) }
    var previousText by remember { mutableStateOf("") }
    var previousAnimate by remember { mutableStateOf(animate) }

    LaunchedEffect(text, animate) {
        val animateChangedFromTrueToFalse = previousAnimate && !animate
        previousAnimate = animate

        if (!animate) {
            if (animateChangedFromTrueToFalse && displayedText.length < text.length) {
                previousText = text
                animateNewContent(displayedText, text, chunkDelayMs) { displayedText = it }
            } else {
                displayedText = text
                previousText = text
            }
        } else {
            when {
                text.isEmpty() -> {
                    displayedText = ""
                    previousText = text
                }
                previousText.isEmpty() || !text.startsWith(previousText) -> {
                    displayedText = ""
                    previousText = text
                    animateNewContent("", text, chunkDelayMs) { displayedText = it }
                }
                text.length > previousText.length -> {
                    previousText = text
                    animateNewContent(displayedText, text, chunkDelayMs) { displayedText = it }
                }
                else -> previousText = text
            }
        }
    }

    content(displayedText)
}

private suspend fun animateNewContent(
    currentText: String,
    fullText: String,
    chunkDelayMs: Long,
    onUpdate: (String) -> Unit,
) {
    val newContent = fullText.substring(currentText.length)
    val chunks = splitIntoWords(newContent)

    val builder = StringBuilder(currentText)
    for (chunk in chunks) {
        builder.append(chunk)
        onUpdate(builder.toString())
        kotlinx.coroutines.delay(chunkDelayMs)
    }
}

private fun splitIntoWords(text: String): List<String> {
    if (text.isEmpty()) return emptyList()

    val chunks = mutableListOf<String>()
    val lines = text.split('\n')

    for ((index, line) in lines.withIndex()) {
        val words = WordSplitRegex.findAll(line).map { it.value }.filter { it.isNotEmpty() }
        chunks.addAll(words)
        if (index < lines.size - 1) {
            chunks.add("\n")
        }
    }

    return chunks
}

private val WordSplitRegex = Regex("""(\s+|\S+)""")

/**
 * 等待首个文本片段时的轻量反馈。
 */
@Composable
fun AITypingIndicator(modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "dots")
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(3) { index ->
            val delay = index * 150
            val alpha by infiniteTransition.animateFloat(
                initialValue = 0.3f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(600, delayMillis = delay, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "alpha"
            )
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .graphicsLayer(alpha = alpha)
                    .background(MiuixTheme.colorScheme.onSurfaceVariantSummary, CircleShape)
            )
        }
    }
}

@Composable
fun ChatMessageItem(
    message: AgentChatMessageUi,
    onSuggestionClick: (String) -> Unit,
    onRunTraceClick: () -> Unit,
    onOpenBrowser: () -> Unit,
    showBrowserShortcut: Boolean,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    when (message) {
        is UserMessageUi -> UserMessageBubble(message = message, modifier = modifier)
        is AgentMessageUi -> AgentMessageBlock(message = message, modifier = modifier)
        is ThinkingMessageUi -> ThinkingRow(message = message, modifier = modifier, compact = compact)
        is RunTraceMessageUi -> RunTraceRow(message = message, onClick = onRunTraceClick, modifier = modifier)
        is ToolActivityMessageUi -> ToolActivityInline(
            message = message,
            onOpenBrowser = onOpenBrowser,
            showBrowserShortcut = showBrowserShortcut,
            modifier = modifier,
            compact = compact,
        )
        is ToolSummaryMessageUi -> ToolSummaryInline(message = message, modifier = modifier, compact = compact)
        is SuggestionChipsMessageUi -> SuggestionChipsRow(message = message, onSuggestionClick = onSuggestionClick, modifier = modifier)
    }
}

/**
 * 把连续的思考与工具调用收束为一个可展开的工作过程，避免 Agent 事件退化为聊天气泡噪音。
 */
@Composable
internal fun AgentWorkProcess(
    id: String,
    messages: List<AgentChatMessageUi>,
    onOpenBrowser: () -> Unit,
    currentBrowserMessageId: String?,
    modifier: Modifier = Modifier,
) {
    val running = messages.any { message ->
        (message is ThinkingMessageUi && message.isStreaming) ||
            (message is ToolActivityMessageUi && message.status == ToolActivityStatusUi.Running)
    }
    val toolCount = messages.count { it is ToolActivityMessageUi }
    var expanded by remember(id) { mutableStateOf(running) }

    LaunchedEffect(running) {
        if (running) {
            expanded = true
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(
                    if (running) LucideR.drawable.lucide_ic_atom else LucideR.drawable.lucide_ic_wrench
                ),
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = if (running) {
                    MiuixTheme.colorScheme.primary
                } else {
                    MiuixTheme.colorScheme.onSurfaceVariantSummary
                },
            )
            Spacer(modifier = Modifier.width(9.dp))
            Text(
                text = when {
                    running && toolCount > 0 -> "正在处理 · 第 $toolCount 步"
                    running -> "正在分析任务"
                    toolCount > 0 -> "已处理 $toolCount 个步骤"
                    else -> "已完成分析"
                },
                style = MiuixTheme.textStyles.body2,
                color = if (running) {
                    MiuixTheme.colorScheme.onSurface
                } else {
                    MiuixTheme.colorScheme.onSurfaceVariantSummary
                },
                modifier = Modifier.weight(1f),
            )
            Icon(
                painter = painterResource(
                    if (expanded) LucideR.drawable.lucide_ic_chevron_down
                    else LucideR.drawable.lucide_ic_chevron_right
                ),
                contentDescription = if (expanded) "收起工作过程" else "展开工作过程",
                modifier = Modifier.size(16.dp),
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(0.5.dp)
                .background(MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.14f)),
        )

        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn() + expandVertically(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                )
            ),
            exit = fadeOut() + shrinkVertically(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                )
            ),
        ) {
            Column(modifier = Modifier.padding(top = 3.dp, bottom = 6.dp)) {
                messages.forEach { message ->
                    ChatMessageItem(
                        message = message,
                        onSuggestionClick = {},
                        onRunTraceClick = {},
                        onOpenBrowser = onOpenBrowser,
                        showBrowserShortcut = message.id == currentBrowserMessageId,
                        compact = true,
                    )
                }
            }
        }
    }
}

// ── 用户消息：轻盈美观气泡 ──────────────────────────────────────────────

@Composable
private fun UserMessageBubble(
    message: UserMessageUi,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.End,
    ) {
        Card(
            modifier = Modifier.widthIn(max = 312.dp),
            cornerRadius = 18.dp,
            insideMargin = PaddingValues(horizontal = 15.dp, vertical = 11.dp),
            colors = CardDefaults.defaultColors(
                color = MiuixTheme.colorScheme.surfaceContainerHigh,
                contentColor = MiuixTheme.colorScheme.onSurface,
            ),
        ) {
            if (message.images.isNotEmpty()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(bottom = 6.dp)
                ) {
                    message.images.forEach { dataUrl ->
                        val bitmap = rememberDataUrlBitmap(dataUrl)
                        if (bitmap != null) {
                            Image(
                                bitmap = bitmap,
                                contentDescription = null,
                                modifier = Modifier
                                    .size(100.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .border(0.5.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(12.dp)),
                                contentScale = ContentScale.Crop,
                            )
                        }
                    }
                }
            }
            if (message.content.isNotBlank()) {
                SelectionContainer {
                    Text(
                        text = message.content,
                        style = MiuixTheme.textStyles.body1,
                        color = MiuixTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

// ── Agent 结果 ───────────────────────────────────────────────────────

@Composable
private fun AgentMessageBlock(
    message: AgentMessageUi,
    modifier: Modifier = Modifier,
) {
    @Suppress("DEPRECATION")
    val clipboardManager = LocalClipboardManager.current
    var copied by remember(message.id) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            kotlinx.coroutines.delay(1_400)
            copied = false
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 6.dp),
    ) {
        when {
            message.content.isBlank() && message.isStreaming -> {
                AITypingIndicator(
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            message.isStreaming -> {
                SelectionContainer {
                    StreamingMarkdown(
                        content = message.content,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            message.renderMarkdown -> {
                SelectionContainer {
                    StableMarkdown(
                        content = message.content,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            message.content.isNotBlank() -> {
                SelectionContainer {
                    Text(
                        text = message.content,
                        style = MiuixTheme.textStyles.body1,
                        color = MiuixTheme.colorScheme.onSurface,
                    )
                }
            }
        }

        if (!message.isStreaming && message.content.isNotBlank()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(modifier = Modifier.weight(1f))
                IconButton(
                    onClick = {
                        @Suppress("DEPRECATION")
                        clipboardManager.setText(AnnotatedString(message.content))
                        copied = true
                    },
                    minWidth = 32.dp,
                    minHeight = 32.dp,
                ) {
                    Icon(
                        painter = painterResource(
                            if (copied) LucideR.drawable.lucide_ic_check
                            else LucideR.drawable.lucide_ic_copy
                        ),
                        contentDescription = if (copied) "已复制" else "复制回答",
                        modifier = Modifier.size(16.dp),
                        tint = if (copied) {
                            MiuixTheme.colorScheme.primary
                        } else {
                            MiuixTheme.colorScheme.onSurfaceVariantSummary
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun StableMarkdown(
    content: String,
    modifier: Modifier = Modifier,
) {
    val markdownState = rememberMarkdownState(
        content = content,
        retainState = true,
    )
    Markdown(
        markdownState = markdownState,
        colors = chatMarkdownColors(),
        typography = chatMarkdownTypography(),
        padding = chatMarkdownPadding(),
        dimens = chatMarkdownDimens(),
        components = chatMarkdownComponents(),
        modifier = modifier,
        loading = {
            Text(
                text = content,
                style = MiuixTheme.textStyles.body1,
                color = MiuixTheme.colorScheme.onSurface,
                modifier = it,
            )
        },
        error = {
            Text(
                text = content,
                style = MiuixTheme.textStyles.body1,
                color = MiuixTheme.colorScheme.onSurface,
                modifier = it,
            )
        },
    )
}

@Composable
private fun StreamingMarkdown(
    content: String,
    modifier: Modifier = Modifier,
) {
    var generation by remember { mutableStateOf(0) }
    key(generation) {
        val markdownState = rememberStreamingMarkdownState()
        var appendedContent by remember { mutableStateOf("") }

        LaunchedEffect(content) {
            if (!content.startsWith(appendedContent)) {
                generation += 1
                return@LaunchedEffect
            }

            val chunk = content.substring(appendedContent.length)
            if (chunk.isNotEmpty()) {
                markdownState.append(chunk)
                appendedContent = content
            }
        }

        Markdown(
            streamingMarkdownState = markdownState,
            colors = chatMarkdownColors(),
            typography = chatMarkdownTypography(),
            padding = chatMarkdownPadding(),
            dimens = chatMarkdownDimens(),
            components = chatMarkdownComponents(),
            modifier = modifier,
        )
    }
}

@Composable
private fun chatMarkdownTypography() = markdownTypography(
    h1 = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    h2 = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
    h3 = TextStyle(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
    h4 = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    h5 = TextStyle(fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold),
    h6 = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    text = chatMarkdownBodyStyle(),
    paragraph = chatMarkdownBodyStyle(),
    ordered = chatMarkdownBodyStyle(),
    bullet = chatMarkdownBodyStyle(),
    list = chatMarkdownBodyStyle(),
    quote = MiuixTheme.textStyles.body2.copy(fontSize = 15.sp, lineHeight = 23.sp),
    code = MiuixTheme.textStyles.footnote1.copy(
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontFamily = FontFamily.Monospace,
    ),
    inlineCode = chatMarkdownBodyStyle().copy(fontFamily = FontFamily.Monospace),
    table = MiuixTheme.textStyles.footnote1.copy(fontSize = 13.sp, lineHeight = 18.sp),
)

@Composable
private fun chatMarkdownBodyStyle() = MiuixTheme.textStyles.body1.copy(
    fontSize = 16.sp,
    lineHeight = 24.sp,
)

@Composable
private fun chatMarkdownColors() = markdownColor(
    text = MiuixTheme.colorScheme.onSurface,
    codeBackground = MiuixTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.78f),
    inlineCodeBackground = MiuixTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.72f),
    dividerColor = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.18f),
    tableBackground = MiuixTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.48f),
)

@Composable
private fun chatMarkdownDimens() = markdownDimens(
    dividerThickness = 0.5.dp,
    codeBackgroundCornerSize = 10.dp,
    blockQuoteThickness = 2.dp,
    tableCellWidth = 132.dp,
    tableCellPadding = 12.dp,
    tableCornerSize = 10.dp,
)

@Composable
private fun chatMarkdownPadding() = markdownPadding(
    block = 3.dp,
    list = 2.dp,
    listItemTop = 2.dp,
    listItemBottom = 2.dp,
    listIndent = 10.dp,
    codeBlock = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
    blockQuote = PaddingValues(horizontal = 12.dp),
    blockQuoteText = PaddingValues(vertical = 4.dp),
)

@Composable
private fun chatMarkdownComponents() = markdownComponents(
    table = { model ->
        MarkdownTable(
            content = model.content,
            node = model.node,
            style = model.typography.table,
            headerBlock = { content, header, tableWidth, style ->
                MarkdownTableHeader(
                    content = content,
                    header = header,
                    tableWidth = tableWidth,
                    style = style,
                    maxLines = 3,
                )
            },
            rowBlock = { content, row, tableWidth, style ->
                MarkdownTableRow(
                    content = content,
                    header = row,
                    tableWidth = tableWidth,
                    style = style,
                    maxLines = 3,
                )
            },
        )
    },
)

// ── 思考过程 ─────────────────────────────────────────────────────────

@Composable
private fun ThinkingRow(
    message: ThinkingMessageUi,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    var expanded by remember(message.id) { mutableStateOf(!message.collapsed) }
    LaunchedEffect(message.isStreaming, message.collapsed) {
        if (message.isStreaming) expanded = true
        if (!message.isStreaming && message.collapsed) expanded = false
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = if (compact) 14.dp else 24.dp, vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier
                .clickable { expanded = !expanded }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (message.isStreaming) {
                    "正在思考"
                } else {
                    "已思考${message.elapsedSeconds?.let { "（用时 ${it} 秒）" }.orEmpty()}"
                },
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                painter = painterResource(
                    if (expanded) LucideR.drawable.lucide_ic_chevron_down 
                    else LucideR.drawable.lucide_ic_chevron_right
                ),
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.7f),
            )
        }

        AnimatedVisibility(visible = expanded && message.content.isNotBlank()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min)
                    .padding(top = 4.dp)
            ) {
                // Full-height vertical accent line matching the thinking content
                Box(
                    modifier = Modifier
                        .width(12.dp)
                        .fillMaxHeight(),
                    contentAlignment = Alignment.TopCenter
                ) {
                    Box(
                        modifier = Modifier
                            .width(1.5.dp)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(1.dp))
                            .background(MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.15f))
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = message.content,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

// ── 工具调用：优雅极简时间线 ─────────────────────────────────────────

@Composable
private fun ToolActivityInline(
    message: ToolActivityMessageUi,
    onOpenBrowser: () -> Unit,
    showBrowserShortcut: Boolean,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    var isExpanded by remember(message.id) { mutableStateOf(false) }

    // Running pulse alpha
    val pulseTransition = rememberInfiniteTransition(label = "pulse_alpha")
    val pulseAlpha by pulseTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable { isExpanded = !isExpanded }
            .padding(horizontal = if (compact) 14.dp else 24.dp, vertical = 4.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            // Timeline line and status dot
            Box(
                modifier = Modifier.width(20.dp),
                contentAlignment = Alignment.Center
            ) {
                // Vertical connector line
                Box(
                    modifier = Modifier
                        .width(1.5.dp)
                        .height(32.dp)
                        .background(MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.15f))
                )
                // Colored dot
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(message.status.statusColor())
                        .graphicsLayer(alpha = if (message.status == ToolActivityStatusUi.Running) pulseAlpha else 1.0f)
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Tool Icon
            Icon(
                painter = painterResource(message.toolName.toToolIcon()),
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.7f)
            )

            Spacer(modifier = Modifier.width(8.dp))

            // Tool label
            Text(
                text = message.toolName.toToolLabel(),
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Minimalist status label
                Text(
                    text = message.status.statusLabel(),
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.graphicsLayer(alpha = if (message.status == ToolActivityStatusUi.Running) pulseAlpha else 1.0f)
                )
                Icon(
                    painter = painterResource(
                        if (isExpanded) LucideR.drawable.lucide_ic_chevron_down 
                        else LucideR.drawable.lucide_ic_chevron_right
                    ),
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.5f),
                )
            }
        }

        AnimatedVisibility(visible = isExpanded) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 28.dp, top = 6.dp),
                insideMargin = PaddingValues(12.dp),
                colors = CardDefaults.defaultColors(
                    color = MiuixTheme.colorScheme.surfaceContainerHigh,
                    contentColor = MiuixTheme.colorScheme.onSurface,
                ),
            ) {
                if (message.argumentsSummary.isNotBlank()) {
                    Text(
                        text = "操作",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 2.dp)
                    )
                    Text(
                        text = message.argumentsSummary,
                        style = MiuixTheme.textStyles.footnote2.copy(fontFamily = FontFamily.Monospace),
                        color = MiuixTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
                if (message.resultSummary != null && message.resultSummary.isNotBlank()) {
                    Text(
                        text = "结果",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(bottom = 2.dp)
                    )
                    Text(
                        text = message.resultSummary,
                        style = MiuixTheme.textStyles.footnote2.copy(fontFamily = FontFamily.Monospace),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                }
                if (showBrowserShortcut) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(
                            text = "打开当前浏览器",
                            onClick = onOpenBrowser,
                            colors = ButtonDefaults.textButtonColorsPrimary(),
                            minHeight = 36.dp,
                            textStyle = MiuixTheme.textStyles.body2,
                        )
                    }
                }
            }
        }
    }
}

// ── Run trace：轻量入口行 ─────────────────────────────────────────────

@Composable
private fun RunTraceRow(
    message: RunTraceMessageUi,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MiuixTheme.colorScheme.surfaceContainer)
            .border(0.5.dp, MiuixTheme.colorScheme.primary.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(LucideR.drawable.lucide_ic_check),
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MiuixTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "可用能力",
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Icon(
            painter = painterResource(LucideR.drawable.lucide_ic_chevron_down),
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

// ── 工具摘要 ──────────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ToolSummaryInline(
    message: ToolSummaryMessageUi,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    FlowRow(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = if (compact) 14.dp else 24.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        message.tools.forEach { tool ->
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(MiuixTheme.colorScheme.surfaceContainer.copy(alpha = 0.5f))
                    .border(0.5.dp, MiuixTheme.colorScheme.primary.copy(alpha = 0.08f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    painter = painterResource(tool.toToolIcon()),
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                    tint = MiuixTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = tool.toToolLabel(),
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
            }
        }
    }
}

// ── 建议语 ────────────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SuggestionChipsRow(
    message: SuggestionChipsMessageUi,
    onSuggestionClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        message.prompts.forEach { prompt ->
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(MiuixTheme.colorScheme.surfaceContainer)
                    .border(0.5.dp, MiuixTheme.colorScheme.primary.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
                    .clickable { onSuggestionClick(prompt) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    painter = painterResource(LucideR.drawable.lucide_ic_sparkles),
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                    tint = MiuixTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = prompt,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

// ── 辅助 ──────────────────────────────────────────────────────────────

@Composable
private fun ToolActivityStatusUi.statusColor() = when (this) {
    ToolActivityStatusUi.Running -> StatusRunning
    ToolActivityStatusUi.Success -> StatusSuccess
    ToolActivityStatusUi.Failed -> StatusError
}

private fun ToolActivityStatusUi.statusLabel(): String = when (this) {
    ToolActivityStatusUi.Running -> "执行中"
    ToolActivityStatusUi.Success -> "已完成"
    ToolActivityStatusUi.Failed -> "失败"
}

@Composable
private fun String.toToolIcon(): Int = when (this) {
    "observe_screen" -> LucideR.drawable.lucide_ic_scan_text
    "tap_element" -> LucideR.drawable.lucide_ic_mouse_pointer_click
    "tap_area" -> LucideR.drawable.lucide_ic_locate_fixed
    "long_press" -> LucideR.drawable.lucide_ic_hand
    "swipe" -> LucideR.drawable.lucide_ic_move
    "scroll" -> LucideR.drawable.lucide_ic_scroll
    "paste_text" -> LucideR.drawable.lucide_ic_clipboard_paste
    "input_text" -> LucideR.drawable.lucide_ic_keyboard
    "replace_text" -> LucideR.drawable.lucide_ic_replace
    "clear_text" -> LucideR.drawable.lucide_ic_eraser
    "wait_for_text" -> LucideR.drawable.lucide_ic_clock
    "search_apps" -> LucideR.drawable.lucide_ic_search
    "get_current_context" -> LucideR.drawable.lucide_ic_map_pin
    "launch_app" -> LucideR.drawable.lucide_ic_rocket
    "open_uri" -> LucideR.drawable.lucide_ic_external_link
    "browser_use" -> LucideR.drawable.lucide_ic_globe
    "press_key" -> LucideR.drawable.lucide_ic_command
    "open_system_panel" -> LucideR.drawable.lucide_ic_panel_top_open
    "terminal", "run_command" -> LucideR.drawable.lucide_ic_square_terminal
    "read_file" -> LucideR.drawable.lucide_ic_file_text
    "write_file" -> LucideR.drawable.lucide_ic_file_pen
    "list_directory" -> LucideR.drawable.lucide_ic_folder_open
    else -> LucideR.drawable.lucide_ic_settings
}

private fun String.toToolLabel(): String = when (this) {
    "observe_screen" -> "查看屏幕"
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
    "get_current_context" -> "时间与位置"
    "launch_app" -> "打开应用"
    "open_uri" -> "打开链接"
    "browser_use" -> "浏览网页"
    "press_key" -> "按键"
    "open_system_panel" -> "系统面板"
    "terminal" -> "终端"
    "run_command" -> "执行命令"
    "read_file" -> "读取文件"
    "write_file" -> "写入文件"
    "list_directory" -> "列目录"
    else -> this
}
