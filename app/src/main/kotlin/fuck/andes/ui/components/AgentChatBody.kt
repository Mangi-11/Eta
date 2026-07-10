package fuck.andes.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Scaffold
import com.composables.icons.lucide.R as LucideR
import fuck.andes.agent.browser.AgentBrowserSession
import fuck.andes.ui.model.AgentChatMessageUi
import fuck.andes.ui.model.AgentMessageUi
import fuck.andes.ui.model.PendingImageUi
import fuck.andes.ui.model.RunTraceMessageUi
import fuck.andes.ui.model.SuggestionChipsMessageUi
import fuck.andes.ui.model.ThinkingMessageUi
import fuck.andes.ui.model.ToolActivityMessageUi
import fuck.andes.ui.model.ToolSummaryMessageUi
import fuck.andes.ui.model.UserMessageUi
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 聊天主体：消息流 + 底部输入框。
 *
 * AI 对话使用正向时间线：第一条消息从对话区顶部开始，后续回复顺序向下追加。
 * 空 assistant 占位不参与布局，避免刚发送时出现一个无内容消息节点。
 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
fun AgentChatBody(
    messages: List<AgentChatMessageUi>,
    input: String,
    isStreaming: Boolean,
    thinkingEnabled: Boolean,
    pendingImages: List<PendingImageUi>,
    onInputChange: (String) -> Unit,
    onThinkingChange: (Boolean) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onAttachImage: (String) -> Unit,
    onRemoveImage: (String) -> Unit,
    onSuggestionClick: (String) -> Unit,
    onRunTraceClick: () -> Unit,
    onOpenBrowser: () -> Unit,
    isDrawerOpen: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberLazyListState()
    val keyboard = LocalSoftwareKeyboardController.current
    val density = LocalDensity.current
    val imeBottomPx = WindowInsets.ime.getBottom(density)
    val isKeyboardVisible = imeBottomPx > 0
    val browserSnapshot by AgentBrowserSession.snapshots.collectAsState()

    val visibleMessages = remember(messages) {
        messages.filterNot { message ->
            message is AgentMessageUi && message.content.isBlank()
        }
    }
    val currentBrowserMessageId = remember(
        visibleMessages,
        browserSnapshot.available,
        browserSnapshot.lastAgentRunId,
        browserSnapshot.lastAgentToolCallId,
    ) {
        val runId = browserSnapshot.lastAgentRunId
        val toolCallId = browserSnapshot.lastAgentToolCallId
        if (!browserSnapshot.available || runId == null || toolCallId == null) {
            null
        } else {
            visibleMessages.lastOrNull { message ->
                message is ToolActivityMessageUi &&
                    message.toolName == "browser_use" &&
                    message.id.startsWith("$runId-tool-") &&
                    message.id.endsWith("-$toolCallId")
            }?.id
        }
    }
    var sentFromKeyboard by remember { mutableStateOf(false) }
    var keepBottomAnchored by remember { mutableStateOf(true) }

    LaunchedEffect(isStreaming) {
        if (isStreaming && sentFromKeyboard) {
            keyboard?.hide()
            sentFromKeyboard = false
        }
    }

    LaunchedEffect(isDrawerOpen) {
        if (isDrawerOpen) {
            keyboard?.hide()
        }
    }

    AgentChatScaffold(
        visibleMessages = visibleMessages,
        hasMessages = visibleMessages.isNotEmpty(),
        scrollState = scrollState,
        input = input,
        isStreaming = isStreaming,
        thinkingEnabled = thinkingEnabled,
        pendingImages = pendingImages,
        showEmptySuggestions = !isKeyboardVisible,
        keepBottomAnchored = keepBottomAnchored,
        isKeyboardVisible = isKeyboardVisible,
        onBottomAnchorChanged = { keepBottomAnchored = it },
        onInputChange = onInputChange,
        onThinkingChange = onThinkingChange,
        onSend = {
            sentFromKeyboard = true
            onSend()
        },
        onStop = onStop,
        onAttachImage = onAttachImage,
        onRemoveImage = onRemoveImage,
        onSuggestionClick = onSuggestionClick,
        onRunTraceClick = onRunTraceClick,
        onOpenBrowser = onOpenBrowser,
        currentBrowserMessageId = currentBrowserMessageId,
        modifier = modifier,
    )
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun AgentChatScaffold(
    visibleMessages: List<AgentChatMessageUi>,
    hasMessages: Boolean,
    scrollState: LazyListState,
    input: String,
    isStreaming: Boolean,
    thinkingEnabled: Boolean,
    pendingImages: List<PendingImageUi>,
    showEmptySuggestions: Boolean,
    keepBottomAnchored: Boolean,
    isKeyboardVisible: Boolean,
    onBottomAnchorChanged: (Boolean) -> Unit,
    onInputChange: (String) -> Unit,
    onThinkingChange: (Boolean) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onAttachImage: (String) -> Unit,
    onRemoveImage: (String) -> Unit,
    onSuggestionClick: (String) -> Unit,
    onRunTraceClick: () -> Unit,
    onOpenBrowser: () -> Unit,
    currentBrowserMessageId: String?,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(
            left = 0.dp,
            top = 0.dp,
            right = 0.dp,
            bottom = 0.dp,
        ),
        bottomBar = {
            AgentChatBottomBar(
                input = input,
                isStreaming = isStreaming,
                thinkingEnabled = thinkingEnabled,
                pendingImages = pendingImages,
                onInputChange = onInputChange,
                onThinkingChange = onThinkingChange,
                onSend = onSend,
                onStop = onStop,
                onAttachImage = onAttachImage,
                onRemoveImage = onRemoveImage,
            )
        },
    ) { innerPadding ->
        val bottomPadding = innerPadding.calculateBottomPadding()
        if (!hasMessages) {
            EmptyChatState(
                showSuggestions = showEmptySuggestions,
                onSuggestionClick = onSuggestionClick,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = bottomPadding),
            )
        } else {
            AgentChatMessages(
                visibleMessages = visibleMessages,
                scrollState = scrollState,
                bottomPadding = bottomPadding,
                keepBottomAnchored = keepBottomAnchored,
                isKeyboardVisible = isKeyboardVisible,
                onBottomAnchorChanged = onBottomAnchorChanged,
                onSuggestionClick = onSuggestionClick,
                onRunTraceClick = onRunTraceClick,
                onOpenBrowser = onOpenBrowser,
                currentBrowserMessageId = currentBrowserMessageId,
            )
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun AgentChatMessages(
    visibleMessages: List<AgentChatMessageUi>,
    scrollState: LazyListState,
    bottomPadding: Dp,
    keepBottomAnchored: Boolean,
    isKeyboardVisible: Boolean,
    onBottomAnchorChanged: (Boolean) -> Unit,
    onSuggestionClick: (String) -> Unit,
    onRunTraceClick: () -> Unit,
    onOpenBrowser: () -> Unit,
    currentBrowserMessageId: String?,
) {
    val density = LocalDensity.current
    val bottomPaddingPx = with(density) { bottomPadding.roundToPx() }
    val bottomItemIndex = visibleMessages.size
    val bottomAnchorKey = visibleMessages.lastOrNull()?.bottomAnchorKey()
    val isAtBottom by remember(scrollState, bottomPaddingPx) {
        derivedStateOf { scrollState.isScrolledToBottom(bottomPaddingPx) }
    }

    LaunchedEffect(
        isKeyboardVisible,
        isAtBottom,
        scrollState.isScrollInProgress,
    ) {
        if (!isKeyboardVisible || scrollState.isScrollInProgress || isAtBottom) {
            onBottomAnchorChanged(isAtBottom)
        }
    }

    LaunchedEffect(
        bottomPadding,
        visibleMessages.size,
        bottomAnchorKey,
        keepBottomAnchored,
    ) {
        if (keepBottomAnchored) {
            scrollState.requestScrollToItem(bottomItemIndex)
        }
    }

    LazyColumn(
        state = scrollState,
        verticalArrangement = Arrangement.Top,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            top = 12.dp,
            bottom = 12.dp + bottomPadding,
        ),
    ) {
        items(
            items = visibleMessages,
            key = { it.id },
        ) { message ->
            ChatMessageItem(
                message = message,
                onSuggestionClick = onSuggestionClick,
                onRunTraceClick = onRunTraceClick,
                onOpenBrowser = onOpenBrowser,
                showBrowserShortcut = message is ToolActivityMessageUi &&
                    message.toolName == "browser_use" &&
                    message.id == currentBrowserMessageId,
            )
        }
        item(key = ChatBottomSentinelKey) {
            Spacer(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp),
            )
        }
    }
}

@Composable
private fun AgentChatBottomBar(
    input: String,
    isStreaming: Boolean,
    thinkingEnabled: Boolean,
    pendingImages: List<PendingImageUi>,
    onInputChange: (String) -> Unit,
    onThinkingChange: (Boolean) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onAttachImage: (String) -> Unit,
    onRemoveImage: (String) -> Unit,
) {
    AgentChatInputBar(
        input = input,
        isStreaming = isStreaming,
        thinkingEnabled = thinkingEnabled,
        pendingImages = pendingImages,
        onInputChange = onInputChange,
        onThinkingChange = onThinkingChange,
        onSend = onSend,
        onStop = onStop,
        onAttachImage = onAttachImage,
        onRemoveImage = onRemoveImage,
        modifier = Modifier
            .fillMaxWidth()
            .imePadding()
            .navigationBarsPadding(),
    )
}

private const val ChatBottomSentinelKey = "agent-chat-bottom-sentinel"

private fun LazyListState.isScrolledToBottom(bottomPaddingPx: Int): Boolean {
    val layoutInfo = layoutInfo
    if (layoutInfo.totalItemsCount == 0) return true
    val lastVisibleItem = layoutInfo.visibleItemsInfo.lastOrNull() ?: return false
    val lastItemIndex = layoutInfo.totalItemsCount - 1
    val visibleBottom = layoutInfo.viewportEndOffset - bottomPaddingPx
    return lastVisibleItem.index >= lastItemIndex &&
        lastVisibleItem.offset + lastVisibleItem.size <= visibleBottom + 2
}

private fun AgentChatMessageUi.bottomAnchorKey(): String = when (this) {
    is UserMessageUi -> "$id:${content.hashCode()}:${images.size}"
    is AgentMessageUi -> "$id:${content.hashCode()}:$isStreaming:${usage.hashCode()}"
    is ThinkingMessageUi -> "$id:${content.hashCode()}:$isStreaming:$elapsedSeconds:$collapsed"
    is RunTraceMessageUi -> "$id:${capabilities.size}"
    is ToolSummaryMessageUi -> "$id:${tools.hashCode()}"
    is ToolActivityMessageUi -> "$id:$toolName:$status:${argumentsSummary.hashCode()}:${resultSummary.hashCode()}:$imageCount"
    is SuggestionChipsMessageUi -> "$id:${prompts.hashCode()}"
}

@Composable
private fun EmptyChatState(
    showSuggestions: Boolean,
    onSuggestionClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val suggestions = listOf(
        SuggestionItem(
            title = "分析当前屏幕",
            description = "截图并描述当前屏幕",
            iconRes = LucideR.drawable.lucide_ic_scan_text,
            prompt = "截图并描述当前屏幕",
            highlighted = false,
        ),
        SuggestionItem(
            title = "打开微信",
            description = "快速启动微信应用",
            iconRes = LucideR.drawable.lucide_ic_rocket,
            prompt = "帮我打开微信",
            highlighted = false,
        ),
        SuggestionItem(
            title = "查看内存压力",
            description = "读取 /proc/meminfo 和 /proc/pressure/，总结内存与系统压力",
            iconRes = LucideR.drawable.lucide_ic_search,
            prompt = "读取 /proc/meminfo 和 /proc/pressure/，重点分析 PSI（Pressure Stall Information）指标，总结当前内存压力和系统状态",
            highlighted = false,
        ),
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(start = 24.dp, top = 88.dp, end = 24.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.Top,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.12f))
                .padding(11.dp),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(LucideR.drawable.lucide_ic_bot),
                contentDescription = null,
                modifier = Modifier.size(26.dp),
                tint = MiuixTheme.colorScheme.primary,
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "有什么可以帮你？",
            style = MiuixTheme.textStyles.headline1,
            color = MiuixTheme.colorScheme.onSurface,
        )

        Spacer(modifier = Modifier.height(20.dp))

        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            suggestions.forEachIndexed { index, item ->
                AnimatedVisibility(
                    visible = showSuggestions,
                    enter = fadeIn(
                        animationSpec = tween(
                            durationMillis = 220,
                            delayMillis = index * 45,
                        )
                    ) + slideInVertically(
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = Spring.StiffnessMediumLow,
                        ),
                        initialOffsetY = { it / 3 },
                    ) + expandVertically(
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = Spring.StiffnessMediumLow,
                        )
                    ),
                    exit = fadeOut(
                        animationSpec = tween(durationMillis = 130)
                    ) + slideOutVertically(
                        animationSpec = tween(durationMillis = 180),
                        targetOffsetY = { -it / 4 },
                    ) + shrinkVertically(
                        animationSpec = tween(durationMillis = 180)
                    ),
                ) {
                    SuggestionCard(
                        item = item,
                        onClick = { onSuggestionClick(item.prompt) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SuggestionCard(
    item: SuggestionItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bgColor = if (item.highlighted) {
        MiuixTheme.colorScheme.primary.copy(alpha = 0.08f)
    } else {
        MiuixTheme.colorScheme.surface
    }
    val borderColor = if (item.highlighted) {
        MiuixTheme.colorScheme.primary.copy(alpha = 0.16f)
    } else {
        MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.12f)
    }
    val contentColor = if (item.highlighted) {
        MiuixTheme.colorScheme.primary
    } else {
        MiuixTheme.colorScheme.onSurface
    }

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(bgColor)
            .border(0.5.dp, borderColor, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (item.highlighted) {
            Icon(
                painter = painterResource(item.iconRes),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = contentColor,
            )
            Spacer(modifier = Modifier.width(10.dp))
        }
        Text(
            text = item.title,
            style = MiuixTheme.textStyles.body1,
            color = contentColor,
        )
    }
}

private data class SuggestionItem(
    val title: String,
    val description: String,
    val iconRes: Int,
    val prompt: String,
    val highlighted: Boolean = false,
)
