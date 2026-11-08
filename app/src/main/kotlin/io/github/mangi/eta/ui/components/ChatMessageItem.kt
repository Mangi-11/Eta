package io.github.mangi.eta.ui.components

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.automirrored.rounded.CallSplit
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Compress
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.itos.coui.dialog.CouiDialogDefaults
import com.itos.coui.effect.couiButtonFeedback
import com.itos.coui.layout.CouiPageDefaults
import com.itos.coui.menu.CouiMenuItem
import com.itos.coui.menu.Menu
import com.itos.coui.menu.couiMenuAnchor
import com.itos.coui.menu.rememberCouiMenuAnchor
import com.itos.coui.shape.CouiCapsuleShape
import com.itos.coui.shape.CouiShape
import com.itos.coui.theme.CouiTheme
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.model.AgentFileReferencePromptCodec
import io.github.mangi.eta.agent.overlay.toolDisplayName
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.ui.app.LocalUiStyle
import io.github.mangi.eta.ui.markdown.MarkdownTone
import io.github.mangi.eta.ui.markdown.StaticMarkdown
import io.github.mangi.eta.ui.markdown.StreamingMarkdown
import io.github.mangi.eta.ui.markdown.StreamingMarkdownState
import io.github.mangi.eta.ui.markdown.THINKING_LINE_HEIGHT_SP
import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.RunTraceMessageUi
import io.github.mangi.eta.ui.model.SuggestionChipsMessageUi
import io.github.mangi.eta.ui.model.SystemNoticeCode
import io.github.mangi.eta.ui.model.SystemNoticeMessageUi
import io.github.mangi.eta.ui.model.ThinkingMessageUi
import io.github.mangi.eta.ui.model.ToolActivityMessageUi
import io.github.mangi.eta.ui.model.ToolSummaryMessageUi
import io.github.mangi.eta.ui.model.UserMessageUi
import io.github.mangi.eta.ui.voice.SpeechReadAloudButton
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.RichTooltip
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TooltipAnchorPosition
import top.yukonga.miuix.kmp.basic.TooltipBox
import top.yukonga.miuix.kmp.basic.TooltipDefaults
import top.yukonga.miuix.kmp.basic.rememberTooltipState
import top.yukonga.miuix.kmp.squircle.squircleBorder
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun rememberDataUrlBitmap(dataUrl: String) = remember(dataUrl) {
    decodeDataUrlBitmap(dataUrl)
}

internal fun decodeDataUrlBitmap(dataUrl: String): ImageBitmap? {
    val base64 = dataUrl.substringAfter("base64,", "")
    if (base64.isBlank()) return null
    return runCatching {
        val bytes = Base64.decode(base64, Base64.NO_WRAP)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
    }.getOrNull()
}

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

/**
 * 只有正在执行的状态才持有无限动画。历史思考和工具条目保持静态，避免长会话里
 * 每个已完成节点都持续产生帧时钟与状态更新。
 */
@Composable
internal fun rememberActivePulse(
    active: Boolean,
    label: String,
): Float {
    if (!active) return 1f
    val transition = rememberInfiniteTransition(label = label)
    val alpha by transition.animateFloat(
        initialValue = 0.58f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(820, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "${label}_alpha",
    )
    return alpha
}

@Composable
internal fun ChatMessageItem(
    message: AgentChatMessageUi,
    onSuggestionClick: (String) -> Unit,
    onRunTraceClick: () -> Unit,
    onOpenBrowser: () -> Unit,
    showBrowserShortcut: Boolean,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    assistantOverlay: Boolean = false,
    retainedStreamingState: StreamingMarkdownState? = null,
    showCopyAction: Boolean = true,
    showMessageActions: Boolean = false,
    showBranchAction: Boolean = false,
    messageActionsEnabled: Boolean = true,
    isEditing: Boolean = false,
    onEditMessage: (String) -> Unit = {},
    onDeleteMessage: (String) -> Unit = {},
    onRegenerateMessage: (String) -> Unit = {},
    onBranchMessage: (String) -> Unit = {},
    onSelectReplyCandidate: (String, Int) -> Unit = { _, _ -> },
) {
    when (message) {
        is UserMessageUi -> UserMessageBubble(
            message = message,
            assistantOverlay = assistantOverlay,
            actionsEnabled = messageActionsEnabled,
            isEditing = isEditing,
            onEdit = { onEditMessage(message.id) },
            onDelete = { onDeleteMessage(message.id) },
            modifier = modifier,
        )
        is AgentMessageUi -> AgentMessageBlock(
            message = message,
            allowSpeech = true,
            assistantOverlay = assistantOverlay,
            retainedStreamingState = retainedStreamingState,
            showCopyAction = showCopyAction,
            showMessageActions = showMessageActions,
            showBranchAction = showBranchAction,
            onBranch = { onBranchMessage(message.id) },
            messageActionsEnabled = messageActionsEnabled,
            onDelete = { onDeleteMessage(message.id) },
            onRegenerate = { onRegenerateMessage(message.id) },
            onEdit = { onEditMessage(message.id) },
            onSelectCandidate = { onSelectReplyCandidate(message.id, it) },
            modifier = modifier,
        )
        is SystemNoticeMessageUi -> if (message.code == SystemNoticeCode.ContextCompaction) {
            ContextCompactionMarker(message = message, modifier = modifier)
        } else {
            AgentMessageBlock(
                message = AgentMessageUi(
                    id = message.id,
                    content = buildString {
                        append(
                            stringResource(
                                when (message.code) {
                                    SystemNoticeCode.Stopped -> R.string.system_notice_stopped
                                    SystemNoticeCode.EmptyResult -> R.string.system_notice_empty_result
                                    SystemNoticeCode.ContextCompaction -> R.string.context_compaction
                                    SystemNoticeCode.ModelRetry -> R.string.system_notice_model_retry
                                    SystemNoticeCode.RuntimeFailed -> R.string.system_notice_runtime_failed
                                    SystemNoticeCode.Interrupted -> R.string.system_notice_interrupted
                                },
                            ),
                        )
                        message.detail?.takeIf(String::isNotBlank)?.let { detail ->
                            append("\n\n")
                            append(detail)
                        }
                    },
                    renderMarkdown = false,
                ),
                assistantOverlay = false,
                retainedStreamingState = null,
                showCopyAction = showCopyAction,
                showMessageActions = showMessageActions,
                messageActionsEnabled = messageActionsEnabled,
                onDelete = { onDeleteMessage(message.id) },
                onRegenerate = { onRegenerateMessage(message.id) },
                modifier = modifier,
            )
        }
        is ThinkingMessageUi -> ThinkingRow(
            message = message,
            retainedStreamingState = retainedStreamingState,
            modifier = modifier,
            compact = compact,
        )
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

// ── 用户消息：轻盈美观气泡 ──────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun UserMessageBubble(
    message: UserMessageUi,
    assistantOverlay: Boolean,
    actionsEnabled: Boolean,
    isEditing: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    @Suppress("DEPRECATION")
    val clipboardManager = LocalClipboardManager.current
    val tooltipState = rememberTooltipState(isPersistent = true)
    val haptic = LocalHapticFeedback.current
    var showCouiMenu by remember { mutableStateOf(false) }
    val couiAnchor = rememberCouiMenuAnchor()
    LaunchedEffect(actionsEnabled) {
        if (!actionsEnabled) {
            tooltipState.dismiss()
            showCouiMenu = false
        }
    }
    LaunchedEffect(tooltipState) {
        snapshotFlow { tooltipState.isVisible }.collect { visible ->
            if (visible) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }
    val visiblePrompt = remember(message.content) {
        AgentFileReferencePromptCodec.parse(message.content)
    }
    val isDark = MiuixTheme.colorScheme.background.luminance() < 0.5f
    val overlayBubbleColor = if (isDark) {
        Color(0xFF37393D)
    } else {
        Color(0xFFE5E7EA)
    }
    val uiStyle = LocalUiStyle.current
    val bubbleContent: @Composable () -> Unit = {
        if (message.images.isNotEmpty()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(bottom = 8.dp),
            ) {
                message.images.forEach { dataUrl ->
                    val bitmap = rememberDataUrlBitmap(dataUrl)
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap,
                            contentDescription = stringResource(R.string.chat_image_attachment),
                            modifier = Modifier
                                .size(100.dp)
                                .clip(RoundedCornerShape(12.dp)),
                            contentScale = ContentScale.Crop,
                        )
                    }
                }
            }
        }
        if (visiblePrompt.references.isNotEmpty()) {
            SentFileReferenceFlow(
                references = visiblePrompt.references,
                modifier = Modifier.padding(
                    bottom = if (visiblePrompt.request.isNotBlank()) 8.dp else 0.dp,
                ),
            )
        }
        if (visiblePrompt.request.isNotBlank()) {
            SelectionContainer {
                Text(
                    text = visiblePrompt.request,
                    style = MiuixTheme.textStyles.body1,
                    color = MiuixTheme.colorScheme.onSurface,
                )
            }
        }
        if (message.isEdited) {
            Text(
                text = stringResource(R.string.ui_edited_c36776),
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                horizontal = if (assistantOverlay) 16.dp else 20.dp,
                vertical = if (assistantOverlay) 4.dp else 8.dp,
            ),
        horizontalArrangement = Arrangement.End,
    ) {
        if (uiStyle == AppearanceUiStyle.COUI && !assistantOverlay) {
            val couiDark = CouiTheme.isDark
            val couiColors = CouiPageDefaults.themeColors(couiDark)
            val destructiveColor = CouiDialogDefaults.themeColors(couiDark).destructive
            val couiBubbleShape = CouiShape(20.dp, 2.2f)
            val couiBubbleBg = if (couiDark) Color(0xFF1F2D3E) else Color(0xFFEBF4FF)
            val copyText = stringResource(R.string.ui_copy_4edd1d)
            val editText = stringResource(R.string.ui_edit_a7f814)
            val deleteText = stringResource(R.string.ui_delete_3755f5)
            val menuItems = remember(copyText, editText, deleteText, destructiveColor) {
                listOf(
                    CouiMenuItem(value = 0, text = copyText, icon = Icons.Rounded.ContentCopy),
                    CouiMenuItem(value = 1, text = editText, icon = Icons.Rounded.Edit),
                    CouiMenuItem(
                        value = 2,
                        text = deleteText,
                        icon = Icons.Rounded.Delete,
                        iconTint = destructiveColor,
                        dividerBefore = true,
                    ),
                )
            }
            Box {
                Column(
                    modifier = Modifier
                        .widthIn(max = 320.dp)
                        .couiMenuAnchor(couiAnchor)
                        .clip(couiBubbleShape)
                        .background(couiBubbleBg, couiBubbleShape)
                        .then(
                            if (isEditing) {
                                Modifier.border(1.dp, couiColors.accent, couiBubbleShape)
                            } else {
                                Modifier
                            },
                        )
                        .combinedClickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {},
                            onLongClick = if (actionsEnabled) {
                                {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    couiAnchor.open { showCouiMenu = true }
                                }
                            } else {
                                null
                            },
                        )
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    bubbleContent()
                }
                Menu(
                    expanded = showCouiMenu && actionsEnabled,
                    onDismissRequest = { showCouiMenu = false },
                    items = menuItems,
                    onItemClick = { item ->
                        showCouiMenu = false
                        when (item.value) {
                            0 -> {
                                @Suppress("DEPRECATION")
                                clipboardManager.setText(AnnotatedString(message.content))
                                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                            }
                            1 -> onEdit()
                            2 -> onDelete()
                        }
                    },
                    anchor = couiAnchor,
                )
            }
        } else {
            TooltipBox(
                positionProvider = TooltipDefaults.rememberTooltipPositionProvider(
                    positioning = TooltipAnchorPosition.Below,
                ),
                tooltip = {
                    RichTooltip(insideMargin = PaddingValues(horizontal = 8.dp, vertical = 6.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            MessageTooltipAction(
                                icon = Icons.Rounded.ContentCopy,
                                label = stringResource(R.string.ui_copy_4edd1d),
                                onClick = {
                                    @Suppress("DEPRECATION")
                                    clipboardManager.setText(AnnotatedString(message.content))
                                    haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                                    tooltipState.dismiss()
                                },
                            )
                            MessageTooltipAction(
                                icon = Icons.Rounded.Edit,
                                label = stringResource(R.string.ui_edit_a7f814),
                                onClick = {
                                    tooltipState.dismiss()
                                    onEdit()
                                },
                            )
                            MessageTooltipAction(
                                icon = Icons.Rounded.Delete,
                                label = stringResource(R.string.ui_delete_3755f5),
                                onClick = {
                                    tooltipState.dismiss()
                                    onDelete()
                                },
                            )
                        }
                    }
                },
                state = tooltipState,
                focusable = true,
                enableUserInput = actionsEnabled,
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(max = 320.dp)
                        .then(
                            if (assistantOverlay) {
                                Modifier.background(overlayBubbleColor, RoundedCornerShape(10.dp))
                            } else {
                                Modifier.squircleSurface(
                                    color = MiuixTheme.colorScheme.surfaceContainerHigh,
                                    topStart = 20.dp,
                                    topEnd = 20.dp,
                                    bottomEnd = if (isEditing) 20.dp else 6.dp,
                                    bottomStart = 20.dp,
                                )
                            },
                        )
                        .then(
                            if (isEditing) {
                                Modifier.squircleBorder(
                                    width = 1.dp,
                                    color = MiuixTheme.colorScheme.primary,
                                    cornerRadius = 20.dp,
                                )
                            } else {
                                Modifier
                            },
                        )
                        .padding(
                            horizontal = if (assistantOverlay) 12.dp else 16.dp,
                            vertical = if (assistantOverlay) 8.dp else 12.dp,
                        ),
                ) {
                    bubbleContent()
                }
            }
        }
    }
}

@Composable
private fun MessageTooltipAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            imageVector = icon,
            // 下方文字已经命名该操作，图标不再重复朗读。
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MiuixTheme.colorScheme.onSurface,
        )
        Text(
            text = label,
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurface,
        )
    }
}

// ── 上下文压缩：时间线中的轻量胶囊标记 ─────────────────────────────────

/**
 * 压缩不是一轮对话结果，而是上下文维护事件；用居中胶囊标记与助手正文区分，
 * 进行中通过图标脉冲反馈，结束后保留压缩前后的 token 信息。
 */
@Composable
private fun ContextCompactionMarker(
    message: SystemNoticeMessageUi,
    modifier: Modifier = Modifier,
) {
    val pulseAlpha = rememberActivePulse(active = message.running, label = "compaction_pulse")
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(percent = 50))
                .background(MiuixTheme.colorScheme.surface)
                .border(
                    0.5.dp,
                    MiuixTheme.colorScheme.outline.copy(alpha = 0.5f),
                    RoundedCornerShape(percent = 50),
                )
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Rounded.Compress,
                contentDescription = null,
                modifier = Modifier
                    .size(12.dp)
                    .graphicsLayer(alpha = if (message.running) pulseAlpha else 1f),
                tint = if (message.running) {
                    MiuixTheme.colorScheme.primary
                } else {
                    MiuixTheme.colorScheme.onSurfaceVariantSummary
                },
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = message.detail?.takeIf(String::isNotBlank)
                    ?: stringResource(R.string.context_compaction),
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ── Agent 结果 ───────────────────────────────────────────────────────

@Composable
private fun AgentMessageBlock(
    message: AgentMessageUi,
    allowSpeech: Boolean = false,
    assistantOverlay: Boolean = false,
    retainedStreamingState: StreamingMarkdownState?,
    showCopyAction: Boolean,
    showMessageActions: Boolean,
    showBranchAction: Boolean = false,
    onBranch: () -> Unit = {},
    messageActionsEnabled: Boolean,
    onDelete: () -> Unit,
    onRegenerate: () -> Unit,
    onEdit: () -> Unit = {},
    onSelectCandidate: (Int) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    @Suppress("DEPRECATION")
    val clipboardManager = LocalClipboardManager.current
    val haptic = LocalHapticFeedback.current
    var copied by remember(message.id) { mutableStateOf(false) }
    val keepStreamingMarkdown = remember(message.id) { message.isStreaming }
    DisposableEffect(retainedStreamingState) {
        retainedStreamingState?.attachContent()
        onDispose { retainedStreamingState?.detachContent() }
    }
    var streamingRevealComplete by remember(message.id) {
        mutableStateOf(!keepStreamingMarkdown)
    }
    // 渲染会话由列表层按 message.id 持有，item 滚出视口被销毁后滑回时复用同一
    // 会话；没有外部持有者时（如嵌套条目）退回组合内 remember，行为与之前一致。
    val streamingState = if (keepStreamingMarkdown) {
        retainedStreamingState ?: remember(message.id) { StreamingMarkdownState() }
    } else {
        null
    }
    val completedDocument = (streamingState ?: retainedStreamingState)
        ?.snapshot?.completedDocumentFor(message.content)
    val revealComplete = streamingRevealComplete && !message.isStreaming &&
        (streamingState == null || completedDocument != null)
    LaunchedEffect(retainedStreamingState, revealComplete, message.content) {
        retainedStreamingState?.revealedContent = message.content.takeIf { revealComplete }
    }
    LaunchedEffect(copied) {
        if (copied) {
            kotlinx.coroutines.delay(1_400)
            copied = false
        }
    }

    val character = LocalRoleplayChat.current.takeUnless { assistantOverlay }
    val isGreeting = character != null && message.id.startsWith(GreetingMessagePrefix)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                horizontal = if (assistantOverlay) 16.dp else 20.dp,
                vertical = 8.dp,
            ),
    ) {
        if (character != null) {
            CharacterReplyHeader(character = character, isGreeting = isGreeting)
        }
        when {
            message.content.isBlank() && message.isStreaming -> {
                AITypingIndicator(
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            streamingState != null && !revealComplete -> {
                StreamingMarkdown(
                    state = streamingState,
                    content = message.content,
                    isStreaming = message.isStreaming,
                    tone = MarkdownTone.Answer,
                    onRevealCompleteChange = { streamingRevealComplete = it },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            message.renderMarkdown -> {
                SelectionContainer {
                    StaticMarkdown(
                        content = message.content,
                        tone = MarkdownTone.Answer,
                        parsed = completedDocument,
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

        message.searchSuggestionsHtml.forEach { html ->
            SearchSuggestionsView(html = html, modifier = Modifier.padding(top = 8.dp))
        }

        // 回答显现完成后才出现操作行；淡入展开避免贴底滚动时整页突然跳动一行高度。
        AnimatedVisibility(
            visible = showCopyAction &&
                !message.isStreaming &&
                message.content.isNotBlank() &&
                revealComplete,
            enter = fadeIn(tween(160)) + expandVertically(tween(160)),
            exit = fadeOut(tween(120)) + shrinkVertically(tween(120)),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // 抵消首个按钮的内部水平留白，使图标与上方正文及工作过程图标共用左侧中心线。
                    .offset(x = -AnswerActionCenterOffset)
                    .padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                AnswerActionButton(
                    icon = if (copied) ChatActionIcons.Check else ChatActionIcons.Copy,
                    contentDescription = stringResource(
                        if (copied) R.string.copy_copied else R.string.copy_answer,
                    ),
                    onClick = {
                        @Suppress("DEPRECATION")
                        clipboardManager.setText(AnnotatedString(message.content))
                        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                        copied = true
                    },
                    tint = if (copied) MiuixTheme.colorScheme.primary else null,
                )
                if (allowSpeech) SpeechReadAloudButton(message.id, message.content)
                if (showMessageActions) {
                    if (message.characterEditable) {
                        AnswerActionButton(
                            icon = ChatActionIcons.Edit,
                            contentDescription = stringResource(R.string.chat_edit_character_reply),
                            onClick = onEdit,
                            enabled = messageActionsEnabled,
                        )
                    }
                    TooltipBox(text = stringResource(R.string.ui_regenerate_2e1905), enabled = messageActionsEnabled) {
                        AnswerActionButton(
                            icon = ChatActionIcons.Refresh,
                            contentDescription = stringResource(R.string.ui_regenerate_reply_84a7d9),
                            onClick = onRegenerate,
                            enabled = messageActionsEnabled,
                        )
                    }
                    TooltipBox(text = stringResource(R.string.ui_delete_3755f5), enabled = messageActionsEnabled) {
                        AnswerActionButton(
                            icon = ChatActionIcons.Trash,
                            contentDescription = stringResource(R.string.ui_delete_this_conversation_3f351b),
                            onClick = onDelete,
                            enabled = messageActionsEnabled,
                        )
                    }
                    if (showBranchAction) {
                        TooltipBox(text = stringResource(R.string.conversation_branch)) {
                            AnswerActionButton(
                                icon = ChatActionIcons.Branch,
                                contentDescription = stringResource(R.string.conversation_branch),
                                onClick = onBranch,
                                enabled = messageActionsEnabled,
                            )
                        }
                    }
                    if (!assistantOverlay && message.usage != null && message.usage.hasPerformanceStats) {
                        TurnStatsPill(
                            usage = message.usage,
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                    if (message.characterEditable && message.candidateCount > 1) {
                        Spacer(Modifier.weight(1f))
                        CandidateSwitcher(
                            selected = message.selectedCandidate,
                            count = message.candidateCount,
                            enabled = messageActionsEnabled,
                            onSelect = onSelectCandidate,
                        )
                    }
                }
            }
        }
    }
}

/** 开场白消息由开始对话时写入，标识前缀与写入方保持一致。 */
private const val GreetingMessagePrefix = "greeting-"

/**
 * 角色回复的署名行：小头像加角色名，开场白额外标注“故事开始”。
 * 正文保持与普通回答同宽，避免为头像缩进后长段落在窄屏上过于拥挤。
 */
@Composable
private fun CharacterReplyHeader(character: RoleplayChatUi, isGreeting: Boolean) {
    Row(
        modifier = Modifier.padding(bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CharacterAvatar(
            name = character.characterName,
            avatarPath = character.avatarPath,
            size = 28.dp,
            shape = CircleShape,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = character.characterName,
            style = MiuixTheme.textStyles.body2,
            fontWeight = FontWeight.Medium,
            color = MiuixTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (isGreeting) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.chat_character_story_start),
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(percent = 50))
                    .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.1f))
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
    }
}

/** 思考卡片与运行记录等全宽描边卡片共用的圆角与描边强度，相邻时轮廓一致。 */
private val ChatCardCorner = 12.dp
private const val ChatCardOutlineAlpha = 0.5f

/** 回答操作按钮的紧凑触控尺寸（28.dp 盒 + 16.dp 图标）；行整体左移 6.dp 对齐正文左边缘。 */
internal val AnswerActionSize = 28.dp
private val AnswerActionCenterOffset = 6.dp

/** 回答下方的轻量操作：图标显式着色，因此禁用态需要自己换成禁用色，否则看起来仍可点。 */
@Composable
internal fun AnswerActionButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    tint: Color? = null,
) {
    val isCoui = LocalUiStyle.current == AppearanceUiStyle.COUI
    val couiDark = CouiTheme.isDark
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .size(AnswerActionSize)
            .clip(RoundedCornerShape(8.dp))
            .then(
                if (isCoui) {
                    Modifier.couiButtonFeedback(couiDark, interactionSource, enabled = enabled)
                } else {
                    Modifier
                },
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(16.dp),
            tint = when {
                !enabled -> MiuixTheme.colorScheme.disabledOnSurface
                tint != null -> tint
                else -> MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.72f)
            },
        )
    }
}

@Composable
private fun CandidateSwitcher(
    selected: Int,
    count: Int,
    enabled: Boolean,
    onSelect: (Int) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(MiuixTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 2.dp),
    ) {
        AnswerActionButton(
            icon = Icons.Rounded.ChevronLeft,
            contentDescription = stringResource(R.string.chat_previous_candidate),
            onClick = { onSelect(selected - 1) },
            enabled = enabled && selected > 0,
            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Text(
            text = "${selected + 1}/$count",
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(min = 28.dp),
        )
        AnswerActionButton(
            icon = Icons.Rounded.ChevronRight,
            contentDescription = stringResource(R.string.chat_next_candidate),
            onClick = { onSelect(selected + 1) },
            enabled = enabled && selected < count - 1,
            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

// ── 思考过程 ─────────────────────────────────────────────────────────

@Composable
internal fun ThinkingRow(
    message: ThinkingMessageUi,
    retainedStreamingState: StreamingMarkdownState?,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    DisposableEffect(retainedStreamingState) {
        retainedStreamingState?.attachContent()
        onDispose { retainedStreamingState?.detachContent() }
    }
    // null 表示跟随思考生命周期；用户点过之后只认手动选择。
    var manualExpanded by rememberSaveable(message.id) { mutableStateOf<Boolean?>(null) }
    val keepStreamingMarkdown = remember(message.id) { message.isStreaming }
    val streamingState = if (keepStreamingMarkdown) {
        retainedStreamingState ?: remember(message.id) { StreamingMarkdownState() }
    } else {
        null
    }
    val completedDocument = (streamingState ?: retainedStreamingState)
        ?.snapshot?.completedDocumentFor(message.content)
    // 展开态必须在组合期同步推导：若经 LaunchedEffect 晚一帧收起，思考结束的那一帧尾部窗口
    // 已解除而正文仍展开，会以全文高度闪现一帧，再从该高度执行收起动画。
    val expanded = manualExpanded ?: message.isStreaming
    // 自动模式下高度上限覆盖到收起动画结束，退出过程不会先撑开再收拢。
    val tailWindow = manualExpanded == null

    val pulseAlpha = rememberActivePulse(
        active = message.isStreaming,
        label = "thinking_pulse",
    )

    // compact 模式渲染在工作过程时间线内，节点徽章已承担图标，这里只保留文字与展开控制。
    val containerModifier = if (compact) {
        modifier.fillMaxWidth()
    } else {
        modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp)
            .squircleSurface(
                color = MiuixTheme.colorScheme.surface,
                cornerRadius = ChatCardCorner,
            )
            .squircleBorder(
                width = 0.5.dp,
                color = MiuixTheme.colorScheme.outline.copy(alpha = ChatCardOutlineAlpha),
                cornerRadius = ChatCardCorner,
            )
    }

    Column(modifier = containerModifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .clickable {
                    // 尾部窗口下点击视为要看全文，保持展开并放开高度限制。
                    manualExpanded = (tailWindow && expanded) || !expanded
                }
                .then(
                    if (compact) {
                        Modifier.heightIn(min = WorkRowHeight)
                    } else {
                        Modifier.padding(horizontal = 12.dp, vertical = 12.dp)
                    },
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!compact) {
                Icon(
                    imageVector = Icons.Rounded.Lightbulb,
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
            }
            ShimmerText(
                active = message.isStreaming,
                text = if (message.isStreaming) {
                    stringResource(R.string.reasoning_in_progress)
                } else {
                    message.elapsedSeconds?.takeIf { it > 0 }?.let { seconds ->
                        pluralStringResource(
                            R.plurals.reasoning_completed_seconds,
                            seconds,
                            seconds,
                        )
                    } ?: stringResource(R.string.reasoning_completed)
                },
                style = MiuixTheme.textStyles.body2,
                color = if (message.isStreaming) {
                    MiuixTheme.colorScheme.onSurface
                } else {
                    MiuixTheme.colorScheme.onSurfaceVariantSummary
                },
                modifier = Modifier.weight(1f, fill = !compact),
            )
            ExpandChevron(
                expanded = expanded,
                contentDescription = stringResource(
                    if (expanded) R.string.reasoning_collapse else R.string.reasoning_expand,
                ),
                modifier = Modifier.padding(start = 4.dp, end = if (compact) 8.dp else 0.dp),
            )
        }

        AnimatedVisibility(visible = expanded && message.content.isNotBlank()) {
            Column {
                if (!compact) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp)
                            .height(0.5.dp)
                            .background(MiuixTheme.colorScheme.outline.copy(alpha = 0.45f)),
                    )
                }
                val contentModifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = if (compact) 0.dp else 12.dp,
                        end = if (compact) 8.dp else 12.dp,
                        top = if (compact) 0.dp else 8.dp,
                        bottom = if (compact) 8.dp else 12.dp,
                    )
                if (streamingState != null &&
                    (message.isStreaming || completedDocument == null || tailWindow)
                ) {
                    ThinkingTailWindow(
                        enabled = tailWindow,
                        modifier = contentModifier,
                    ) {
                        StreamingMarkdown(
                            state = streamingState,
                            content = message.content,
                            isStreaming = message.isStreaming,
                            tone = MarkdownTone.Thinking,
                        )
                    }
                } else {
                    StaticMarkdown(
                        content = message.content,
                        tone = MarkdownTone.Thinking,
                        parsed = completedDocument,
                        modifier = contentModifier,
                    )
                }
            }
        }
    }
}

/**
 * 进行中的思考只露出最近几行：内容按底部对齐超出部分向上裁掉，顶部渐隐提示还有更早内容。
 * 高度封顶后增量不再推高工作过程卡片，列表也不必随每次思考快照重新跟底。
 */
@Composable
private fun ThinkingTailWindow(
    enabled: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    if (!enabled) {
        Box(modifier) { content() }
        return
    }
    val maxHeight = with(LocalDensity.current) {
        (THINKING_TAIL_WINDOW_LINES * THINKING_LINE_HEIGHT_SP).sp.toDp()
    }
    Box(
        modifier = modifier
            .heightIn(max = maxHeight)
            .clipToBounds()
            // 离屏合成只覆盖这块有界区域，用于让渐隐遮罩作用在文字 alpha 上。
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                if (size.height >= maxHeight.toPx() - 1f) {
                    drawRect(
                        brush = Brush.verticalGradient(
                            0f to Color.Transparent,
                            0.35f to Color.Black,
                        ),
                        blendMode = BlendMode.DstIn,
                    )
                }
            },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight(align = Alignment.Bottom, unbounded = true),
        ) {
            content()
        }
    }
}

private const val THINKING_TAIL_WINDOW_LINES = 4

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
            .squircleSurface(color = MiuixTheme.colorScheme.surface, cornerRadius = ChatCardCorner)
            .squircleBorder(
                width = 0.5.dp,
                color = MiuixTheme.colorScheme.outline.copy(alpha = ChatCardOutlineAlpha),
                cornerRadius = ChatCardCorner,
            )
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Rounded.Check,
            contentDescription = null,
            modifier = Modifier.size(15.dp),
            tint = MiuixTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.ui_available_capacity_743337),
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = Icons.Rounded.ChevronRight,
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
            .padding(horizontal = if (compact) 10.dp else 20.dp, vertical = 4.dp),
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
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = iconForTool(tool),
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                    tint = MiuixTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = toolDisplayName(tool),
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
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Rounded.AutoAwesome,
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
