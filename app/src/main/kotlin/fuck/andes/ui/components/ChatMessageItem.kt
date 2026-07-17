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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.R as LucideR
import com.mikepenz.markdown.compose.components.MarkdownComponentModel
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownCodeFence
import com.mikepenz.markdown.compose.elements.MarkdownHeader
import com.mikepenz.markdown.compose.elements.MarkdownTableBasicText
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.markdownDimens
import com.mikepenz.markdown.model.markdownPadding
import com.mikepenz.markdown.model.rememberMarkdownState
import com.mikepenz.markdown.model.rememberStreamingMarkdownState
import fuck.andes.ui.model.AgentChatMessageUi
import fuck.andes.ui.model.AgentMessageUi
import fuck.andes.ui.model.AgentRunMetricsUi
import fuck.andes.ui.model.RunTraceMessageUi
import fuck.andes.ui.model.SuggestionChipsMessageUi
import fuck.andes.ui.model.ThinkingMessageUi
import fuck.andes.ui.model.ToolActivityMessageUi
import fuck.andes.ui.model.ToolActivityStatusUi
import fuck.andes.ui.model.ToolSummaryMessageUi
import fuck.andes.ui.model.UserMessageUi
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.findChildOfType
import org.intellij.markdown.flavours.gfm.GFMElementTypes.HEADER
import org.intellij.markdown.flavours.gfm.GFMElementTypes.ROW
import org.intellij.markdown.flavours.gfm.GFMTokenTypes.CELL
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.ButtonDefaults
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

    val pulseTransition = rememberInfiniteTransition(label = "work_pulse")
    val pulseAlpha by pulseTransition.animateFloat(
        initialValue = 0.45f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "work_pulse_alpha"
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MiuixTheme.colorScheme.surface)
            .border(
                0.5.dp,
                MiuixTheme.colorScheme.outline.copy(alpha = 0.55f),
                RoundedCornerShape(12.dp),
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 13.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(
                    if (running) LucideR.drawable.lucide_ic_atom else LucideR.drawable.lucide_ic_wrench
                ),
                contentDescription = null,
                modifier = Modifier
                    .size(15.dp)
                    .graphicsLayer(alpha = if (running) pulseAlpha else 1f),
                tint = if (running) {
                    MiuixTheme.colorScheme.primary
                } else {
                    MiuixTheme.colorScheme.onSurfaceVariantSummary
                },
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = when {
                    running && toolCount > 0 -> "正在处理 · 第 $toolCount 步"
                    running -> "正在分析任务"
                    toolCount > 0 -> "已完成 $toolCount 个步骤"
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
                modifier = Modifier.size(14.dp),
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.7f),
            )
        }

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
            Column {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 13.dp)
                        .height(0.5.dp)
                        .background(MiuixTheme.colorScheme.outline.copy(alpha = 0.45f)),
                )
                Column(modifier = Modifier.padding(top = 2.dp, bottom = 8.dp)) {
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
}

// ── 用户消息：轻盈美观气泡 ──────────────────────────────────────────────

private val UserBubbleShape = RoundedCornerShape(
    topStart = 20.dp,
    topEnd = 20.dp,
    bottomEnd = 6.dp,
    bottomStart = 20.dp,
)

@Composable
private fun UserMessageBubble(
    message: UserMessageUi,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.End,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .clip(UserBubbleShape)
                .background(MiuixTheme.colorScheme.surfaceContainerHigh)
                .padding(horizontal = 16.dp, vertical = 11.dp),
        ) {
            if (message.images.isNotEmpty()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(bottom = 8.dp)
                ) {
                    message.images.forEach { dataUrl ->
                        val bitmap = rememberDataUrlBitmap(dataUrl)
                        if (bitmap != null) {
                            Image(
                                bitmap = bitmap,
                                contentDescription = null,
                                modifier = Modifier
                                    .size(100.dp)
                                    .clip(RoundedCornerShape(12.dp)),
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
            .padding(horizontal = 20.dp, vertical = 7.dp),
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

        if (!message.isStreaming && message.runMetrics != null) {
            AgentRunMetricsLine(
                metrics = message.runMetrics,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        if (!message.isStreaming && message.content.isNotBlank()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = {
                        @Suppress("DEPRECATION")
                        clipboardManager.setText(AnnotatedString(message.content))
                        copied = true
                    },
                    minWidth = 30.dp,
                    minHeight = 30.dp,
                ) {
                    Icon(
                        painter = painterResource(
                            if (copied) LucideR.drawable.lucide_ic_check
                            else LucideR.drawable.lucide_ic_copy
                        ),
                        contentDescription = if (copied) "已复制" else "复制回答",
                        modifier = Modifier.size(15.dp),
                        tint = if (copied) {
                            MiuixTheme.colorScheme.primary
                        } else {
                            MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.75f)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun AgentRunMetricsLine(
    metrics: AgentRunMetricsUi,
    modifier: Modifier = Modifier,
) {
    val color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.72f)
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        RunMetricItem(
            iconRes = LucideR.drawable.lucide_ic_arrow_up,
            contentDescription = "输入 Token",
            text = "输入 ${metrics.inputTokens.formatTokenCount()}",
            color = color,
        )
        RunMetricItem(
            iconRes = LucideR.drawable.lucide_ic_database,
            contentDescription = "缓存输入 Token",
            text = "缓存 ${metrics.cachedInputTokens.formatTokenCount()}",
            color = color,
        )
        RunMetricItem(
            iconRes = LucideR.drawable.lucide_ic_arrow_down,
            contentDescription = "输出 Token",
            text = "输出 ${metrics.outputTokens.formatTokenCount()}",
            color = color,
        )
        RunMetricItem(
            iconRes = LucideR.drawable.lucide_ic_clock,
            contentDescription = "单次对话耗时",
            text = metrics.elapsedMs.formatElapsedTime(),
            color = color,
        )
    }
}

@Composable
private fun RunMetricItem(
    iconRes: Int,
    contentDescription: String,
    text: String,
    color: Color,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            modifier = Modifier.size(12.dp),
            tint = color,
        )
        Text(
            text = text,
            style = MiuixTheme.textStyles.footnote1,
            color = color,
        )
    }
}

private fun Int?.formatTokenCount(): String =
    this?.let { String.format(java.util.Locale.US, "%,d", it) } ?: "--"

private fun Long?.formatElapsedTime(): String = when {
    this == null -> "耗时 --"
    this < 1_000L -> "耗时 ${this} ms"
    this < 60_000L -> String.format(java.util.Locale.US, "耗时 %.1f 秒", this / 1_000.0)
    else -> String.format(
        java.util.Locale.US,
        "耗时 %d 分 %.1f 秒",
        this / 60_000L,
        (this % 60_000L) / 1_000.0,
    )
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

// ── Markdown 样式：克制的聊天排版，标题只作强调不作页面标题 ─────────────

@Composable
private fun chatMarkdownTypography() = markdownTypography(
    h1 = chatMarkdownBodyStyle().copy(fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold),
    h2 = chatMarkdownBodyStyle().copy(fontSize = 19.sp, lineHeight = 27.sp, fontWeight = FontWeight.Bold),
    h3 = chatMarkdownBodyStyle().copy(fontSize = 18.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold),
    h4 = chatMarkdownBodyStyle().copy(fontSize = 17.sp, lineHeight = 25.sp, fontWeight = FontWeight.Bold),
    h5 = chatMarkdownBodyStyle().copy(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold),
    h6 = chatMarkdownBodyStyle().copy(fontSize = 15.sp, lineHeight = 23.sp, fontWeight = FontWeight.Bold),
    text = chatMarkdownBodyStyle(),
    paragraph = chatMarkdownBodyStyle(),
    ordered = chatMarkdownBodyStyle(),
    bullet = chatMarkdownBodyStyle(),
    list = chatMarkdownBodyStyle(),
    quote = MiuixTheme.textStyles.body2.copy(
        fontSize = 15.sp,
        lineHeight = 24.sp,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
    ),
    code = TextStyle(
        fontSize = 13.sp,
        lineHeight = 20.sp,
        fontFamily = FontFamily.Monospace,
    ),
    inlineCode = chatMarkdownBodyStyle().copy(
        fontSize = 14.sp,
        fontFamily = FontFamily.Monospace,
    ),
    table = MiuixTheme.textStyles.body2.copy(fontSize = 14.sp, lineHeight = 20.sp),
    textLink = TextLinkStyles(
        style = SpanStyle(
            color = MiuixTheme.colorScheme.primary,
            fontWeight = FontWeight.Medium,
        ),
    ),
)

@Composable
private fun chatMarkdownBodyStyle() = MiuixTheme.textStyles.body1.copy(
    fontSize = 16.sp,
    lineHeight = 26.sp,
)

@Composable
private fun chatMarkdownColors() = markdownColor(
    text = MiuixTheme.colorScheme.onSurface,
    // 代码块与表格的底色、描边由自定义组件绘制，这里只保留行内代码底色与分隔线。
    codeBackground = MiuixTheme.colorScheme.surface,
    inlineCodeBackground = MiuixTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.6f),
    dividerColor = MiuixTheme.colorScheme.outline.copy(alpha = 0.5f),
    tableBackground = Color.Transparent,
)

@Composable
private fun chatMarkdownDimens() = markdownDimens(
    dividerThickness = 0.5.dp,
    codeBackgroundCornerSize = 10.dp,
    blockQuoteThickness = 3.dp,
)

@Composable
private fun chatMarkdownPadding() = markdownPadding(
    block = 7.dp,
    list = 3.dp,
    listItemTop = 3.dp,
    listItemBottom = 3.dp,
    listIndent = 14.dp,
    codeBlock = PaddingValues(horizontal = 13.dp, vertical = 11.dp),
    blockQuote = PaddingValues(horizontal = 12.dp),
    blockQuoteText = PaddingValues(vertical = 3.dp),
    blockQuoteBar = PaddingValues.Absolute(left = 2.dp, top = 3.dp, right = 0.dp, bottom = 3.dp),
)

@Composable
private fun chatMarkdownComponents() = markdownComponents(
    heading1 = { ChatHeadingBlock(it, it.typography.h1, topPadding = 14.dp) },
    heading2 = { ChatHeadingBlock(it, it.typography.h2, topPadding = 13.dp) },
    heading3 = { ChatHeadingBlock(it, it.typography.h3, topPadding = 12.dp) },
    heading4 = { ChatHeadingBlock(it, it.typography.h4, topPadding = 10.dp) },
    heading5 = { ChatHeadingBlock(it, it.typography.h5, topPadding = 9.dp) },
    heading6 = { ChatHeadingBlock(it, it.typography.h6, topPadding = 8.dp) },
    setextHeading1 = { ChatHeadingBlock(it, it.typography.h1, topPadding = 14.dp, setext = true) },
    setextHeading2 = { ChatHeadingBlock(it, it.typography.h2, topPadding = 13.dp, setext = true) },
    codeFence = { model ->
        MarkdownCodeFence(model.content, model.node, style = model.typography.code) { code, language, style ->
            ChatCodeBlock(code = code, language = language, style = style)
        }
    },
    codeBlock = { model ->
        MarkdownCodeBlock(model.content, model.node, style = model.typography.code) { code, language, style ->
            ChatCodeBlock(code = code, language = language, style = style)
        }
    },
    table = { model ->
        ChatMarkdownTable(
            content = model.content,
            node = model.node,
            style = model.typography.table,
        )
    },
)

/**
 * 标题块：在库默认的块间距之上再补段前距，让标题与上文拉开层级。
 */
@Composable
private fun ChatHeadingBlock(
    model: MarkdownComponentModel,
    style: TextStyle,
    topPadding: Dp,
    setext: Boolean = false,
) {
    Column(modifier = Modifier.padding(top = topPadding)) {
        MarkdownHeader(
            content = model.content,
            node = model.node,
            style = style,
            contentChildType = if (setext) {
                MarkdownTokenTypes.SETEXT_CONTENT
            } else {
                MarkdownTokenTypes.ATX_CONTENT
            },
        )
    }
}

/**
 * 代码块：顶栏显示语言标签并提供一键复制，正文等宽字体、超出横向滚动。
 */
@Composable
private fun ChatCodeBlock(
    code: String,
    language: String?,
    style: TextStyle,
) {
    @Suppress("DEPRECATION")
    val clipboardManager = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            kotlinx.coroutines.delay(1_400)
            copied = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MiuixTheme.colorScheme.surface)
            .border(
                0.5.dp,
                MiuixTheme.colorScheme.outline.copy(alpha = 0.5f),
                RoundedCornerShape(10.dp),
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 13.dp, end = 6.dp, top = 3.dp, bottom = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = language?.takeIf { it.isNotBlank() } ?: "code",
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = {
                    @Suppress("DEPRECATION")
                    clipboardManager.setText(AnnotatedString(code))
                    copied = true
                },
                minWidth = 28.dp,
                minHeight = 28.dp,
            ) {
                Icon(
                    painter = painterResource(
                        if (copied) LucideR.drawable.lucide_ic_check
                        else LucideR.drawable.lucide_ic_copy
                    ),
                    contentDescription = if (copied) "已复制" else "复制代码",
                    modifier = Modifier.size(13.dp),
                    tint = if (copied) {
                        MiuixTheme.colorScheme.primary
                    } else {
                        MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.8f)
                    },
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 13.dp)
                .height(0.5.dp)
                .background(MiuixTheme.colorScheme.outline.copy(alpha = 0.45f)),
        )
        Text(
            text = code,
            style = style,
            color = MiuixTheme.colorScheme.onSurface,
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 13.dp, vertical = 11.dp),
        )
    }
}

private val ChatTableCellWidth = 112.dp

/**
 * 表格：细描边容器 + 表头浅底加粗 + 行间发丝分隔线；列宽不足时整体横向滚动。
 */
@Composable
private fun ChatMarkdownTable(
    content: String,
    node: ASTNode,
    style: TextStyle,
) {
    val headerCells = remember(node) {
        node.findChildOfType(HEADER)?.children?.filter { it.type == CELL }.orEmpty()
    }
    val bodyRows = remember(node) {
        node.children.filter { it.type == ROW }
            .map { row -> row.children.filter { it.type == CELL } }
    }
    if (headerCells.isEmpty()) return

    val borderColor = MiuixTheme.colorScheme.outline.copy(alpha = 0.5f)
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
    ) {
        val tableWidth = ChatTableCellWidth * headerCells.size
        val scrollable = maxWidth <= tableWidth
        Column(
            modifier = (if (scrollable) {
                Modifier
                    .horizontalScroll(rememberScrollState())
                    .requiredWidth(tableWidth)
            } else {
                Modifier.fillMaxWidth()
            })
                .clip(RoundedCornerShape(10.dp))
                .border(0.5.dp, borderColor, RoundedCornerShape(10.dp))
                .background(MiuixTheme.colorScheme.surface),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MiuixTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.45f))
                    .height(IntrinsicSize.Max),
            ) {
                headerCells.forEach { cell ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 12.dp, vertical = 9.dp),
                    ) {
                        MarkdownTableBasicText(
                            content = content,
                            cell = cell,
                            style = style.copy(fontWeight = FontWeight.SemiBold),
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            bodyRows.forEach { rowCells ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(0.5.dp)
                        .background(borderColor.copy(alpha = 0.6f)),
                )
                Row(modifier = Modifier.fillMaxWidth()) {
                    rowCells.forEach { cell ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 12.dp, vertical = 9.dp),
                        ) {
                            MarkdownTableBasicText(
                                content = content,
                                cell = cell,
                                style = style,
                                maxLines = 6,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

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

    val pulseTransition = rememberInfiniteTransition(label = "thinking_pulse")
    val pulseAlpha by pulseTransition.animateFloat(
        initialValue = 0.45f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "thinking_pulse_alpha"
    )

    // compact 模式渲染在工作过程卡片内部，不再携带自己的卡片外壳，避免卡中卡。
    val containerModifier = if (compact) {
        modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 2.dp)
    } else {
        modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MiuixTheme.colorScheme.surface)
            .border(
                0.5.dp,
                MiuixTheme.colorScheme.outline.copy(alpha = 0.55f),
                RoundedCornerShape(12.dp),
            )
    }

    Column(modifier = containerModifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .clickable { expanded = !expanded }
                .padding(horizontal = if (compact) 4.dp else 13.dp, vertical = if (compact) 6.dp else 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(LucideR.drawable.lucide_ic_lightbulb),
                contentDescription = null,
                modifier = Modifier
                    .size(15.dp)
                    .graphicsLayer(alpha = if (message.isStreaming) pulseAlpha else 1f),
                tint = if (message.isStreaming) {
                    MiuixTheme.colorScheme.primary
                } else {
                    MiuixTheme.colorScheme.onSurfaceVariantSummary
                },
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (message.isStreaming) {
                    "正在思考…"
                } else {
                    "思考已完成${message.elapsedSeconds?.let { " · 用时 ${it} 秒" }.orEmpty()}"
                },
                style = MiuixTheme.textStyles.body2,
                color = if (message.isStreaming) {
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
                contentDescription = if (expanded) "收起思考过程" else "展开思考过程",
                modifier = Modifier.size(14.dp),
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.7f),
            )
        }

        AnimatedVisibility(visible = expanded && message.content.isNotBlank()) {
            Column {
                if (!compact) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 13.dp)
                            .height(0.5.dp)
                            .background(MiuixTheme.colorScheme.outline.copy(alpha = 0.45f)),
                    )
                }
                Text(
                    text = message.content,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(
                        start = if (compact) 27.dp else 13.dp,
                        end = 13.dp,
                        top = if (compact) 2.dp else 8.dp,
                        bottom = if (compact) 8.dp else 12.dp,
                    ),
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
            .clip(RoundedCornerShape(10.dp))
            .clickable { isExpanded = !isExpanded }
            .padding(horizontal = if (compact) 10.dp else 20.dp, vertical = 3.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 5.dp),
        ) {
            // 工具图标与思考行的灯泡共用同一前导槽位，保证卡片内左边缘对齐。
            Icon(
                painter = painterResource(message.toolName.toToolIcon()),
                contentDescription = null,
                modifier = Modifier.size(15.dp),
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.8f)
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
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                // Status dot
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(message.status.statusColor())
                        .graphicsLayer(alpha = if (message.status == ToolActivityStatusUi.Running) pulseAlpha else 1.0f)
                )
                // Minimalist status label
                Text(
                    text = message.status.statusLabel(),
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.8f),
                    modifier = Modifier.graphicsLayer(alpha = if (message.status == ToolActivityStatusUi.Running) pulseAlpha else 1.0f)
                )
                Icon(
                    painter = painterResource(
                        if (isExpanded) LucideR.drawable.lucide_ic_chevron_down
                        else LucideR.drawable.lucide_ic_chevron_right
                    ),
                    contentDescription = null,
                    modifier = Modifier.size(13.dp),
                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.5f),
                )
            }
        }

        AnimatedVisibility(visible = isExpanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 27.dp, top = 2.dp, bottom = 6.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MiuixTheme.colorScheme.surfaceContainer)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
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
            .padding(horizontal = 20.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MiuixTheme.colorScheme.surface)
            .border(
                0.5.dp,
                MiuixTheme.colorScheme.outline.copy(alpha = 0.55f),
                RoundedCornerShape(12.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(LucideR.drawable.lucide_ic_check),
            contentDescription = null,
            modifier = Modifier.size(15.dp),
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
            painter = painterResource(LucideR.drawable.lucide_ic_chevron_right),
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.7f),
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
            .padding(horizontal = if (compact) 10.dp else 20.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        message.tools.forEach { tool ->
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(MiuixTheme.colorScheme.surface)
                    .border(
                        0.5.dp,
                        MiuixTheme.colorScheme.outline.copy(alpha = 0.5f),
                        RoundedCornerShape(10.dp),
                    )
                    .padding(horizontal = 9.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    painter = painterResource(tool.toToolIcon()),
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                    tint = MiuixTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(5.dp))
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
            .padding(horizontal = 20.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        message.prompts.forEach { prompt ->
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(MiuixTheme.colorScheme.surface)
                    .border(
                        0.5.dp,
                        MiuixTheme.colorScheme.outline.copy(alpha = 0.55f),
                        RoundedCornerShape(10.dp),
                    )
                    .clickable { onSuggestionClick(prompt) }
                    .padding(horizontal = 13.dp, vertical = 8.dp),
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
