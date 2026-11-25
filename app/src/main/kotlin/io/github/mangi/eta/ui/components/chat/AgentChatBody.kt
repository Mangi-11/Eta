package io.github.mangi.eta.ui.components.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
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
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.DocumentScanner
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.RocketLaunch
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastCoerceIn
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.browser.BrowserSessionSnapshot
import io.github.mangi.eta.agent.imagegen.ImageGenToolCatalog
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.data.repository.ConversationBranchBoundary
import io.github.mangi.eta.ui.state.AgentConversationRevisionReducer
import io.github.mangi.eta.ui.layout.rememberFloatingBottomBarInsets
import io.github.mangi.eta.ui.markdown.StreamingMarkdownStateCache
import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.AgentContextUsageUi
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.AgentModelPickerUiState
import io.github.mangi.eta.ui.model.ConversationHistoryLoadState
import io.github.mangi.eta.ui.model.MessageEditUiState
import io.github.mangi.eta.ui.model.PendingFileReferenceUi
import io.github.mangi.eta.ui.model.PendingImageUi
import io.github.mangi.eta.ui.model.QueuedMessageUi
import io.github.mangi.eta.ui.model.ThinkingMessageUi
import io.github.mangi.eta.ui.model.ToolActivityMessageUi
import io.github.mangi.eta.ui.model.ToolSummaryMessageUi
import io.github.mangi.eta.ui.model.UserMessageUi
import io.github.mangi.eta.ui.model.branchMessageOutline
import io.github.mangi.eta.ui.model.latestContextUsage
import io.github.mangi.eta.ui.components.SpeechAutoReadAloud
import io.github.mangi.eta.ui.components.SpeechPlaybackHost
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.glass.GlassIconButton
import top.yukonga.miuix.kmp.glass.glassPress
import top.yukonga.miuix.kmp.squircle.squircleBorder
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import kotlin.math.exp
import kotlin.math.min
import io.github.mangi.eta.ui.components.CharacterAvatar
import io.github.mangi.eta.ui.components.LocalAgentBrowserSession
import io.github.mangi.eta.ui.components.LocalRoleplayChat
import io.github.mangi.eta.ui.components.captureGlassBackdrop
import io.github.mangi.eta.ui.components.color
import io.github.mangi.eta.ui.components.rememberGlassBackdrop
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/**
 * 聊天主体：消息流 + 底部输入框。
 *
 * AI 对话使用正向时间线：第一条消息从对话区顶部开始，后续回复顺序向下追加。
 * 空 assistant 占位不参与布局，避免刚发送时出现一个无内容消息节点。
 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun AgentChatBody(
    messages: List<AgentChatMessageUi>,
    modelPickerState: AgentModelPickerUiState,
    isCompacting: Boolean,
    isBranching: Boolean,
    input: String,
    isStreaming: Boolean,
    reasoningEffort: ReasoningEffort,
    availableReasoningEfforts: List<ReasoningEffort>,
    pendingImages: List<PendingImageUi>,
    pendingFileReferences: List<PendingFileReferenceUi>,
    queuedMessages: List<QueuedMessageUi> = emptyList(),
    inputSyncVersion: Int = 0,
    focusInputOnSync: Boolean = true,
    messageEdit: MessageEditUiState?,
    onReasoningEffortChange: (ReasoningEffort) -> Unit,
    onCompactContext: () -> Unit,
    canCompactContext: Boolean,
    onModelSelected: (String) -> Unit,
    onSubmit: (String) -> Unit,
    onInterruptAndSubmit: (String) -> Unit = onSubmit,
    onSteerQueuedMessage: (String) -> Unit = {},
    onRecallQueuedMessage: (String, String) -> Unit = { _, _ -> },
    onRemoveQueuedMessage: (String) -> Unit = {},
    onStop: () -> Unit,
    onAttachImage: (String) -> Unit,
    onRemoveImage: (String) -> Unit,
    onAttachFiles: (List<String>) -> Unit,
    onAttachFolder: (String) -> Unit,
    onAttachFilePath: (String) -> Unit,
    onRemoveFileReference: (String) -> Unit,
    onEditMessage: (String) -> Unit,
    onCancelMessageEdit: () -> Unit,
    onDeleteMessage: (String) -> Unit,
    onRegenerateMessage: (String) -> Unit,
    onBranchMessage: (String) -> Unit,
    onSelectReplyCandidate: (String, Int) -> Unit,
    onSuggestionClick: (String) -> Unit,
    onRunTraceClick: () -> Unit,
    onOpenBrowser: () -> Unit,
    hasEarlierMessages: Boolean = false,
    messageStartIndex: Int = 0,
    historyLoadState: ConversationHistoryLoadState = ConversationHistoryLoadState.Idle,
    onLoadEarlierMessages: () -> Unit = {},
    onHistoryViewportChanged: (Boolean) -> Unit = {},
    isDrawerOpen: Boolean = false,
    topInset: Dp = 0.dp,
    modifier: Modifier = Modifier,
) {
    val visibleMessages = remember(messages, messageEdit?.targetMessageId, messageEdit?.preserveFollowingMessages) {
        AgentConversationRevisionReducer.visibleMessagesForEdit(
            messages = messages,
            targetMessageId = messageEdit?.takeUnless { it.preserveFollowingMessages }?.targetMessageId,
        ).filterNot { message ->
            message is AgentMessageUi && message.content.isBlank()
        }
    }
    val initialScrollIndex = remember {
        visibleMessages.toTimelineEntries().size + if (hasEarlierMessages) 1 else 0
    }
    val scrollState = rememberLazyListState(initialFirstVisibleItemIndex = initialScrollIndex)
    val keyboard = LocalSoftwareKeyboardController.current
    val density = LocalDensity.current
    val imeBottomPx = WindowInsets.ime.getBottom(density)
    val isKeyboardVisible = imeBottomPx > 0
    val browserSnapshot = LocalAgentBrowserSession.current?.snapshots?.collectAsStateWithLifecycle()?.value
        ?: BrowserSessionSnapshot()
    val contextUsage = remember(messages, modelPickerState.selectedModel) {
        latestContextUsage(messages, modelPickerState.selectedModel)
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
    var keepBottomAnchored by rememberSaveable { mutableStateOf(true) }

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

    SpeechPlaybackHost {
        SpeechAutoReadAloud(messages = messages, isStreaming = isStreaming)
        AgentChatScaffold(
            visibleMessages = visibleMessages,
            hasMessages = visibleMessages.isNotEmpty() || hasEarlierMessages,
            scrollState = scrollState,
            input = input,
            modelPickerState = modelPickerState,
            isCompacting = isCompacting,
            isBranching = isBranching,
            isDrawerOpen = isDrawerOpen,
            contextUsage = contextUsage,
            isStreaming = isStreaming,
            reasoningEffort = reasoningEffort,
            availableReasoningEfforts = availableReasoningEfforts,
            pendingImages = pendingImages,
            pendingFileReferences = pendingFileReferences,
            queuedMessages = queuedMessages,
            inputSyncVersion = inputSyncVersion,
            focusInputOnSync = focusInputOnSync,
            messageEdit = messageEdit,
            showEmptySuggestions = !isKeyboardVisible,
            keepBottomAnchored = keepBottomAnchored,
            onBottomAnchorChanged = { keepBottomAnchored = it },
            onSubmit = { text ->
                sentFromKeyboard = true
                // 发送即重新锚定底部：用户从历史上方直接发送时，同帧内 isStreaming 与
                // 新消息一起到位，立即回到底部并恢复后续的流式平滑跟底。
                keepBottomAnchored = true
                onSubmit(text)
            },
            onInterruptAndSubmit = { text ->
                sentFromKeyboard = true
                keepBottomAnchored = true
                onInterruptAndSubmit(text)
            },
            onSteerQueuedMessage = { id ->
                keepBottomAnchored = true
                onSteerQueuedMessage(id)
            },
            onRecallQueuedMessage = onRecallQueuedMessage,
            onRemoveQueuedMessage = onRemoveQueuedMessage,
            onReasoningEffortChange = onReasoningEffortChange,
            onCompactContext = onCompactContext,
            canCompactContext = canCompactContext,
            onModelSelected = onModelSelected,
            onStop = onStop,
            onAttachImage = onAttachImage,
            onRemoveImage = onRemoveImage,
            onAttachFiles = onAttachFiles,
            onAttachFolder = onAttachFolder,
            onAttachFilePath = onAttachFilePath,
            onRemoveFileReference = onRemoveFileReference,
            onEditMessage = onEditMessage,
            onCancelMessageEdit = onCancelMessageEdit,
            onDeleteMessage = onDeleteMessage,
            onRegenerateMessage = onRegenerateMessage,
            onBranchMessage = onBranchMessage,
            onSelectReplyCandidate = onSelectReplyCandidate,
            onSuggestionClick = onSuggestionClick,
            onRunTraceClick = onRunTraceClick,
            onOpenBrowser = onOpenBrowser,
            currentBrowserMessageId = currentBrowserMessageId,
            hasEarlierMessages = hasEarlierMessages,
            messageStartIndex = messageStartIndex,
            historyLoadState = historyLoadState,
            onLoadEarlierMessages = onLoadEarlierMessages,
            onHistoryViewportChanged = onHistoryViewportChanged,
            topInset = topInset,
            modifier = modifier,
        )
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun AgentChatScaffold(
    visibleMessages: List<AgentChatMessageUi>,
    hasMessages: Boolean,
    scrollState: LazyListState,
    input: String,
    modelPickerState: AgentModelPickerUiState,
    isCompacting: Boolean,
    isBranching: Boolean,
    isDrawerOpen: Boolean,
    contextUsage: AgentContextUsageUi,
    isStreaming: Boolean,
    reasoningEffort: ReasoningEffort,
    availableReasoningEfforts: List<ReasoningEffort>,
    pendingImages: List<PendingImageUi>,
    pendingFileReferences: List<PendingFileReferenceUi>,
    queuedMessages: List<QueuedMessageUi>,
    inputSyncVersion: Int,
    focusInputOnSync: Boolean,
    messageEdit: MessageEditUiState?,
    showEmptySuggestions: Boolean,
    keepBottomAnchored: Boolean,
    onBottomAnchorChanged: (Boolean) -> Unit,
    onSubmit: (String) -> Unit,
    onInterruptAndSubmit: (String) -> Unit,
    onSteerQueuedMessage: (String) -> Unit,
    onRecallQueuedMessage: (String, String) -> Unit,
    onRemoveQueuedMessage: (String) -> Unit,
    onReasoningEffortChange: (ReasoningEffort) -> Unit,
    onCompactContext: () -> Unit,
    canCompactContext: Boolean,
    onModelSelected: (String) -> Unit,
    onStop: () -> Unit,
    onAttachImage: (String) -> Unit,
    onRemoveImage: (String) -> Unit,
    onAttachFiles: (List<String>) -> Unit,
    onAttachFolder: (String) -> Unit,
    onAttachFilePath: (String) -> Unit,
    onRemoveFileReference: (String) -> Unit,
    onEditMessage: (String) -> Unit,
    onCancelMessageEdit: () -> Unit,
    onDeleteMessage: (String) -> Unit,
    onRegenerateMessage: (String) -> Unit,
    onBranchMessage: (String) -> Unit,
    onSelectReplyCandidate: (String, Int) -> Unit,
    onSuggestionClick: (String) -> Unit,
    onRunTraceClick: () -> Unit,
    onOpenBrowser: () -> Unit,
    currentBrowserMessageId: String?,
    hasEarlierMessages: Boolean,
    messageStartIndex: Int,
    historyLoadState: ConversationHistoryLoadState,
    onLoadEarlierMessages: () -> Unit,
    onHistoryViewportChanged: (Boolean) -> Unit,
    topInset: Dp,
    modifier: Modifier = Modifier,
) {
    val chatBackdrop = rememberGlassBackdrop()

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
                imeAvoidanceEnabled = !isDrawerOpen,
                isCompacting = isCompacting,
                contextUsage = contextUsage,
                showContextUsage = hasMessages,
                isStreaming = isStreaming,
                reasoningEffort = reasoningEffort,
                availableReasoningEfforts = availableReasoningEfforts,
                pendingImages = pendingImages,
                pendingFileReferences = pendingFileReferences,
                queuedMessages = queuedMessages,
                inputSyncVersion = inputSyncVersion,
                focusInputOnSync = focusInputOnSync,
                messageEdit = messageEdit,
                glassBackdrop = chatBackdrop,
                onSubmit = onSubmit,
                onInterruptAndSubmit = onInterruptAndSubmit,
                onSteerQueuedMessage = onSteerQueuedMessage,
                onRecallQueuedMessage = onRecallQueuedMessage,
                onRemoveQueuedMessage = onRemoveQueuedMessage,
                onReasoningEffortChange = onReasoningEffortChange,
                onCompactContext = onCompactContext,
                canCompactContext = canCompactContext,
                onStop = onStop,
                onAttachImage = onAttachImage,
                onRemoveImage = onRemoveImage,
                onAttachFiles = onAttachFiles,
                onAttachFolder = onAttachFolder,
                onAttachFilePath = onAttachFilePath,
                onRemoveFileReference = onRemoveFileReference,
                onCancelMessageEdit = onCancelMessageEdit,
            )
        },
    ) { innerPadding ->
        val bottomPadding = innerPadding.calculateBottomPadding()
        Box(
            modifier = Modifier
                .fillMaxSize()
                .captureGlassBackdrop(chatBackdrop),
        ) {
            if (!hasMessages) {
                EmptyChatState(
                    showSuggestions = showEmptySuggestions,
                    onSuggestionClick = onSuggestionClick,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = topInset, bottom = bottomPadding),
                )
            } else {
                AgentConversationMessages(
                    visibleMessages = visibleMessages,
                    scrollState = scrollState,
                    isStreaming = isStreaming,
                    topInset = topInset,
                    bottomInset = bottomPadding,
                    keepBottomAnchored = keepBottomAnchored,
                    onBottomAnchorChanged = onBottomAnchorChanged,
                    onSuggestionClick = onSuggestionClick,
                    onRunTraceClick = onRunTraceClick,
                    onOpenBrowser = onOpenBrowser,
                    onEditMessage = onEditMessage,
                    onDeleteMessage = onDeleteMessage,
                    onRegenerateMessage = onRegenerateMessage,
                    onBranchMessage = onBranchMessage,
                    onSelectReplyCandidate = onSelectReplyCandidate,
                    onEditGeneratedImage = onAttachImage,
                    messageActionsEnabled = !isStreaming && !isCompacting && messageEdit == null,
                    editTargetMessageId = messageEdit?.targetMessageId,
                    currentBrowserMessageId = currentBrowserMessageId,
                    hasEarlierMessages = hasEarlierMessages,
                    messageStartIndex = messageStartIndex,
                    historyLoadState = historyLoadState,
                    onLoadEarlierMessages = onLoadEarlierMessages,
                    onHistoryViewportChanged = onHistoryViewportChanged,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun AgentConversationMessages(
    visibleMessages: List<AgentChatMessageUi>,
    scrollState: LazyListState,
    isStreaming: Boolean,
    bottomInset: Dp,
    keepBottomAnchored: Boolean,
    onBottomAnchorChanged: (Boolean) -> Unit,
    assistantOverlay: Boolean = false,
    topInset: Dp = 0.dp,
    onSuggestionClick: (String) -> Unit = {},
    onRunTraceClick: () -> Unit = {},
    onOpenBrowser: () -> Unit = {},
    onEditMessage: (String) -> Unit = {},
    onDeleteMessage: (String) -> Unit = {},
    onRegenerateMessage: (String) -> Unit = {},
    onBranchMessage: (String) -> Unit = {},
    onSelectReplyCandidate: (String, Int) -> Unit = { _, _ -> },
    onEditGeneratedImage: ((String) -> Unit)? = null,
    messageActionsEnabled: Boolean = false,
    editTargetMessageId: String? = null,
    currentBrowserMessageId: String? = null,
    hasEarlierMessages: Boolean = false,
    messageStartIndex: Int = 0,
    historyLoadState: ConversationHistoryLoadState = ConversationHistoryLoadState.Idle,
    onLoadEarlierMessages: () -> Unit = {},
    onHistoryViewportChanged: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // 上一版条目只作复用比对，不参与组合观察。
    val timelineCache = remember { TimelineEntriesCache() }
    val timelineEntries = remember(visibleMessages) {
        visibleMessages.toTimelineEntries(previous = timelineCache.entries)
    }
    SideEffect { timelineCache.entries = timelineEntries }
    val activeWorkProcessKey = if (isStreaming) {
        (timelineEntries.lastOrNull() as? AgentTimelineEntry.WorkProcess)?.key
    } else {
        null
    }
    // 复制按钮只出现在每轮对话的最终结果上，中间步骤的过渡文本不提供复制入口。
    // 流式进行中当前这一轮尚未收尾，此时的“最后一条正文”只是中间步骤，不标记。
    val finalResultMessageIds = remember(visibleMessages, isStreaming) {
        resolveFinalResultMessageIds(visibleMessages, isStreaming = isStreaming)
    }
    val branchMessageIds = remember(visibleMessages, isStreaming) {
        ConversationBranchBoundary.eligibleMessageIds(visibleMessages.branchMessageOutline(), running = isStreaming)
    }
    // 流式消息的渲染会话按 id 提升到列表层持有：item 滚出视口被 LazyColumn 销毁后，
    // 滑回时复用同一解析会话与打字机进度，避免整段内容重新解析并重放显现动画。
    val streamingMarkdownCache = remember { StreamingMarkdownStateCache() }
    val streamingMarkdownStates = streamingMarkdownCache.states
    val streamingMessageIds = remember(visibleMessages) {
        visibleMessages.mapNotNullTo(mutableSetOf()) { message ->
            when (message) {
                is AgentMessageUi -> message.id.takeIf { message.isStreaming }
                is ThinkingMessageUi -> message.id.takeIf { message.isStreaming }
                else -> null
            }
        }.also { ids ->
            ids.forEach { streamingMarkdownCache.getOrCreate(it) }
        }
    }
    LaunchedEffect(streamingMarkdownCache, streamingMessageIds) {
        snapshotFlow { streamingMarkdownCache.unusedMessageIds(streamingMessageIds) }
            .collect { unused ->
                if (unused.isNotEmpty()) streamingMarkdownCache.discardUnused(streamingMessageIds)
            }
    }
    val earlierMessagesItemCount = if (hasEarlierMessages) 1 else 0
    val bottomItemIndex = timelineEntries.size + earlierMessagesItemCount
    val isUserDragging by scrollState.interactionSource.collectIsDraggedAsState()
    val isAtBottom by remember(scrollState) {
        derivedStateOf { !scrollState.canScrollForward }
    }
    val isListMeasured by remember(scrollState) {
        derivedStateOf {
            scrollState.layoutInfo.let { it.totalItemsCount > 0 && it.viewportEndOffset > it.viewportStartOffset }
        }
    }
    val densityScale = LocalDensity.current.density
    val coroutineScope = rememberCoroutineScope()
    var expandSuppressVersion by remember { mutableIntStateOf(0) }
    var suppressAutoReanchor by remember { mutableStateOf(false) }

    LaunchedEffect(expandSuppressVersion) {
        if (expandSuppressVersion == 0) return@LaunchedEffect
        suppressAutoReanchor = true
        delay(EXPAND_REANCHOR_SUPPRESS_MS)
        suppressAutoReanchor = false
    }

    val onExpandItem = remember(onBottomAnchorChanged) {
        {
            suppressAutoReanchor = true
            expandSuppressVersion += 1
            onBottomAnchorChanged(false)
        }
    }

    LaunchedEffect(
        isListMeasured,
        isUserDragging,
        isAtBottom,
        keepBottomAnchored,
        suppressAutoReanchor,
    ) {
        if (!isListMeasured) return@LaunchedEffect
        val next = resolveKeepBottomAnchored(
            current = keepBottomAnchored,
            isUserDragging = isUserDragging,
            isAtBottom = isAtBottom,
            suppressAutoReanchor = suppressAutoReanchor,
        )
        if (next != keepBottomAnchored) {
            onBottomAnchorChanged(next)
        }
    }

    val tailMessage = visibleMessages.lastOrNull() as? AgentMessageUi
    val currentTailMessage by rememberUpdatedState(tailMessage)
    // 显现进度属于尾部条目的高频状态；列表作用域只观察离散化后的布尔值。
    val isTailRendering by remember {
        derivedStateOf {
            currentTailMessage?.let { message ->
                streamingMarkdownStates[message.id]?.let { state ->
                    state.revealedContent != message.content
                }
            } == true
        }
    }
    var isBottomSettling by remember { mutableStateOf(isStreaming) }

    LaunchedEffect(isStreaming, isTailRendering, keepBottomAnchored, isUserDragging) {
        if (!keepBottomAnchored || isUserDragging) {
            isBottomSettling = false
        } else if (isStreaming || isTailRendering) {
            isBottomSettling = true
        } else if (isBottomSettling) {
            // 显现完成后还会切换稳定排版并插入操作行，等其完成测量并对齐底部后收口。
            withFrameNanos { }
            withFrameNanos { }
            withTimeoutOrNull(BOTTOM_SETTLE_TIMEOUT_MS) {
                snapshotFlow { !scrollState.canScrollForward }.first { it }
            }
            isBottomSettling = false
        }
    }

    val historyPaging = rememberChatHistoryPaging(
        scrollState = scrollState,
        entries = timelineEntries,
        messageStartIndex = messageStartIndex,
        messageCount = visibleMessages.size,
        hasEarlier = hasEarlierMessages,
        loadState = historyLoadState,
        enabled = !isStreaming && editTargetMessageId == null,
        canReleasePages = !isTailRendering && !isBottomSettling,
        keepBottomAnchored = keepBottomAnchored,
        onBottomAnchorChanged = onBottomAnchorChanged,
        onLoadEarlier = onLoadEarlierMessages,
        onViewportChanged = onHistoryViewportChanged,
    )

    val shouldFollowBottom by rememberUpdatedState(
        resolveBottomFollowEnabled(
            isStreaming = isStreaming,
            keepBottomAnchored = keepBottomAnchored,
            isUserDragging = isUserDragging,
            isBottomSettling = isBottomSettling,
        )
    )
    val currentBottomItemIndex by rememberUpdatedState(bottomItemIndex)
    val bottomFollowDecisions = remember(scrollState) {
        Channel<BottomFollowDecision>(Channel.CONFLATED)
    }

    LaunchedEffect(isStreaming, keepBottomAnchored) {
        if (isStreaming && keepBottomAnchored) {
            suppressAutoReanchor = false
        }
        if (shouldRequestInitialBottom(
                isStreaming = isStreaming,
                keepBottomAnchored = keepBottomAnchored,
                isUserDragging = isUserDragging,
            )
        ) {
            scrollState.requestScrollToItem(currentBottomItemIndex)
        }
    }

    // 流式输出及渲染收尾期间发布最新的跟底距离。历史消息中的步骤/思考展开同样会改变
    // 列表高度，但那是用户主动查看内容，不能被误判成尾部文字增长。
    LaunchedEffect(scrollState) {
        snapshotFlow {
            val layoutInfo = scrollState.layoutInfo
            val sentinel = layoutInfo.visibleItemsInfo.firstOrNull { item ->
                item.key == ChatBottomSentinelKey
            }
            BottomFollowLayout(
                enabled = shouldFollowBottom,
                bottomItemIndex = currentBottomItemIndex,
                sentinelBottom = sentinel?.let { it.offset + it.size },
                // 输入器高度属于滚动内容的 bottom inset，而不是滚动容器高度。
                // 跟底目标应是 afterContentPadding 之前的正文边界。
                viewportEnd = layoutInfo.viewportEndOffset - layoutInfo.afterContentPadding,
                lastVisibleIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index,
            )
        }
            .distinctUntilChanged()
            .collect { layout ->
                val decision = resolveBottomFollowDecision(
                    enabled = layout.enabled,
                    bottomItemIndex = layout.bottomItemIndex,
                    sentinelBottom = layout.sentinelBottom,
                    viewportEnd = layout.viewportEnd,
                    lastVisibleIndex = layout.lastVisibleIndex,
                )
                bottomFollowDecisions.trySend(decision)
            }
    }

    // 一个持续存在的帧时钟从当前屏幕位置追向最新目标。新字符继续到达时只更新目标，
    // 不取消并重启动画，因此速度连续；用户开始拖动后，enabled=false 会立即停止跟随。
    LaunchedEffect(scrollState, bottomFollowDecisions) {
        var remainingDistancePx = 0f
        var requestIndex: Int? = null
        var previousFrameNanos = 0L

        fun accept(decision: BottomFollowDecision) {
            remainingDistancePx = decision.scrollByPx.toFloat()
            requestIndex = decision.requestIndex
        }

        while (currentCoroutineContext().isActive) {
            if (remainingDistancePx <= 0f && requestIndex == null) {
                accept(bottomFollowDecisions.receive())
                previousFrameNanos = 0L
            }
            while (true) {
                val latest = bottomFollowDecisions.tryReceive().getOrNull() ?: break
                accept(latest)
            }

            if (!shouldFollowBottom) {
                remainingDistancePx = 0f
                requestIndex = null
                continue
            }

            val targetIndex = requestIndex
            if (targetIndex != null) {
                scrollState.requestScrollToItem(targetIndex)
                requestIndex = null
                remainingDistancePx = 0f
                continue
            }
            if (remainingDistancePx <= 0f) continue

            val frameNanos = withFrameNanos { it }
            val elapsedSeconds = if (previousFrameNanos == 0L) {
                1f / 60f
            } else {
                ((frameNanos - previousFrameNanos) / 1_000_000_000f).fastCoerceIn(0f, 0.05f)
            }
            previousFrameNanos = frameNanos

            while (true) {
                val latest = bottomFollowDecisions.tryReceive().getOrNull() ?: break
                accept(latest)
            }
            if (!shouldFollowBottom || requestIndex != null || remainingDistancePx <= 0f) continue

            val step = smoothBottomFollowStep(
                distancePx = remainingDistancePx,
                elapsedSeconds = elapsedSeconds,
                density = densityScale,
            )
            var consumedStep = 0f
            try {
                scrollState.scroll {
                    scrollBy(step)
                    consumedStep = step
                }
                remainingDistancePx = if (consumedStep > 0f) {
                    (remainingDistancePx - consumedStep).coerceAtLeast(0f)
                } else {
                    0f
                }
            } catch (cancelled: CancellationException) {
                if (!currentCoroutineContext().isActive) throw cancelled
                remainingDistancePx = 0f
            }
        }
    }

    // 滚动层保持整屏，输入器作为后绘制浮层；输入器高度进入列表的
    // afterContentPadding，确保跟到底部时最后一行停在输入器上方。
    val messagesBackdrop = if (assistantOverlay) null else rememberGlassBackdrop()
    CompositionLocalProvider(LocalOnChatItemExpand provides onExpandItem) {
        Box(modifier = modifier.clipToBounds()) {
            LazyColumn(
                state = scrollState,
                verticalArrangement = Arrangement.Top,
                modifier = Modifier
                    .fillMaxSize()
                    .captureGlassBackdrop(messagesBackdrop)
                    .scrollEndHaptic()
                    .nestedScroll(historyPaging.scrollConnection)
                    .overScrollVertical(),
                contentPadding = PaddingValues(
                    top = topInset + if (assistantOverlay) 8.dp else 10.dp,
                    bottom = bottomInset + 14.dp,
                ),
                overscrollEffect = null,
            ) {
                if (hasEarlierMessages) {
                    item(key = ChatHistoryBoundaryKey) {
                        AgentHistoryLoadStatus(
                            state = historyLoadState,
                            enabled = !isStreaming && editTargetMessageId == null,
                            onRetry = historyPaging.retry,
                        )
                    }
                }
                items(
                    items = timelineEntries,
                    key = { it.key },
                    contentType = { it.contentType() },
                ) { entry ->
                    val itemModifier = Modifier.animateItem(
                        fadeInSpec = tween(durationMillis = 180),
                        placementSpec = null,
                        // 历史轮次被编辑、删除或重新生成时必须立即退出；退出动画会让已从
                        // 状态中裁掉的旧消息继续绘制，并与同位置的新流式消息短暂重叠。
                        fadeOutSpec = null,
                    )
                    when (entry) {
                        is AgentTimelineEntry.Message -> {
                            val message = entry.message
                            ChatMessageItem(
                                message = message,
                                assistantOverlay = assistantOverlay,
                                retainedStreamingState = (message as? AgentMessageUi)
                                    ?.let { streamingMarkdownStates[it.id] },
                                onSuggestionClick = onSuggestionClick,
                                onRunTraceClick = onRunTraceClick,
                                onOpenBrowser = onOpenBrowser,
                                showBrowserShortcut = message is ToolActivityMessageUi &&
                                    message.toolName == "browser_use" &&
                                    message.id == currentBrowserMessageId,
                                showCopyAction = message !is AgentMessageUi ||
                                    message.characterEditable || message.id in finalResultMessageIds,
                                showMessageActions = !assistantOverlay && (
                                    message.id in finalResultMessageIds ||
                                        (message is AgentMessageUi && message.characterEditable)
                                    ),
                                messageActionsEnabled = messageActionsEnabled,
                                showBranchAction = !assistantOverlay && message.id in branchMessageIds,
                                onBranchMessage = onBranchMessage,
                                isEditing = message.id == editTargetMessageId,
                                onEditMessage = onEditMessage,
                                onDeleteMessage = onDeleteMessage,
                                onRegenerateMessage = onRegenerateMessage,
                                onSelectReplyCandidate = onSelectReplyCandidate,
                                onEditGeneratedImage = if (!assistantOverlay) onEditGeneratedImage else null,
                                modifier = itemModifier,
                            )
                        }

                        is AgentTimelineEntry.WorkProcess -> {
                            AgentWorkProcess(
                                id = entry.key,
                                active = entry.key == activeWorkProcessKey,
                                messages = entry.messages,
                                assistantOverlay = assistantOverlay,
                                onOpenBrowser = onOpenBrowser,
                                currentBrowserMessageId = currentBrowserMessageId,
                                retainedStreamingStates = streamingMarkdownStates,
                                modifier = itemModifier,
                            )
                        }
                    }
                }
                item(key = ChatBottomSentinelKey) {
                    Spacer(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp),
                    )
                }
            }

            AnimatedVisibility(
                visible = !keepBottomAnchored && !isAtBottom,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = bottomInset + 8.dp),
                enter = fadeIn(tween(160)) + scaleIn(tween(180), initialScale = 0.82f),
                exit = fadeOut(tween(100)) + scaleOut(tween(120), targetScale = 0.86f),
            ) {
                val scrollToBottom: () -> Unit = {
                    suppressAutoReanchor = false
                    onBottomAnchorChanged(true)
                    coroutineScope.launch {
                        scrollState.animateScrollToItem(bottomItemIndex)
                    }
                }
                GlassIconButton(
                    onClick = scrollToBottom,
                    backdrop = messagesBackdrop,
                    size = 40.dp,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ArrowDownward,
                        contentDescription = stringResource(R.string.ui_back_to_bottom_32282e),
                        modifier = Modifier.size(18.dp),
                        tint = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                    )
                }
            }
        }
    }
}

private data class BottomFollowLayout(
    val enabled: Boolean,
    val bottomItemIndex: Int,
    val sentinelBottom: Int?,
    val viewportEnd: Int,
    val lastVisibleIndex: Int?,
)

internal data class BottomFollowDecision(
    val scrollByPx: Int = 0,
    val requestIndex: Int? = null,
)

internal fun resolveBottomFollowDecision(
    enabled: Boolean,
    bottomItemIndex: Int,
    sentinelBottom: Int?,
    viewportEnd: Int,
    lastVisibleIndex: Int?,
): BottomFollowDecision {
    if (!enabled) return BottomFollowDecision()
    val overflow = sentinelBottom?.minus(viewportEnd)
    return when {
        overflow != null && overflow > 0 -> BottomFollowDecision(scrollByPx = overflow)
        sentinelBottom == null &&
            lastVisibleIndex != null &&
            lastVisibleIndex < bottomItemIndex -> BottomFollowDecision(requestIndex = bottomItemIndex)
        else -> BottomFollowDecision()
    }
}

internal fun smoothBottomFollowStep(
    distancePx: Float,
    elapsedSeconds: Float,
    density: Float,
): Float {
    if (distancePx <= 0f || elapsedSeconds <= 0f) return 0f
    if (distancePx <= BOTTOM_FOLLOW_SNAP_DISTANCE_PX) return distancePx

    val frameSeconds = elapsedSeconds.coerceAtMost(BOTTOM_FOLLOW_MAX_FRAME_SECONDS)
    val easedStep = distancePx * (1f - exp(-frameSeconds / BOTTOM_FOLLOW_RESPONSE_SECONDS))
    val speedLimitedStep = BOTTOM_FOLLOW_MAX_SPEED_DP_PER_SECOND * density * frameSeconds
    return min(distancePx, min(easedStep.coerceAtLeast(BOTTOM_FOLLOW_MIN_STEP_PX), speedLimitedStep))
}

internal sealed interface AgentTimelineEntry {
    val key: String

    data class Message(
        val message: AgentChatMessageUi,
    ) : AgentTimelineEntry {
        override val key: String = message.id
    }

    data class WorkProcess(
        override val key: String,
        val messages: List<AgentChatMessageUi>,
    ) : AgentTimelineEntry
}

/**
 * 投影层只替换真正变化的消息实例；工作过程分组若成员实例全部未变，就复用上一版条目，
 * 让已结束的分组在正文流式刷新时保持参数同一性并跳过重组。
 */
internal fun List<AgentChatMessageUi>.toTimelineEntries(
    previous: List<AgentTimelineEntry> = emptyList(),
): List<AgentTimelineEntry> = buildList {
    val previousWorkByMessageId = previous.asSequence()
        .filterIsInstance<AgentTimelineEntry.WorkProcess>()
        .flatMap { entry -> entry.messages.asSequence().map { it.id to entry } }
        .toMap()
    val workMessages = mutableListOf<AgentChatMessageUi>()

    fun flushWorkProcess() {
        if (workMessages.isEmpty()) return
        // 历史页可能从同一工作过程的中间开始；前插补齐后保留已显示分组的身份和滚动锚点。
        val messageIds = workMessages.mapTo(HashSet()) { it.id }
        val previousEntry = workMessages.firstNotNullOfOrNull { previousWorkByMessageId[it.id] }
            ?.takeIf { entry -> entry.messages.all { it.id in messageIds } }
        val key = previousEntry?.key ?: "work-${workMessages.first().id}"
        val reusable = previousEntry?.takeIf { entry ->
            entry.messages.size == workMessages.size &&
                entry.messages.indices.all { index -> entry.messages[index] === workMessages[index] }
        }
        add(reusable ?: AgentTimelineEntry.WorkProcess(key = key, messages = workMessages.toList()))
        workMessages.clear()
    }

    this@toTimelineEntries.forEach { message ->
        if (message.isWorkProcessMessage()) {
            workMessages += message
        } else {
            flushWorkProcess()
            add(AgentTimelineEntry.Message(message))
        }
    }
    flushWorkProcess()
}

private fun AgentTimelineEntry.contentType(): Any = when (this) {
    is AgentTimelineEntry.WorkProcess -> AgentTimelineEntry.WorkProcess::class
    is AgentTimelineEntry.Message -> message::class
}

private fun AgentChatMessageUi.isWorkProcessMessage(): Boolean =
    this is ThinkingMessageUi ||
        this is ToolSummaryMessageUi ||
        (this is ToolActivityMessageUi && toolName != ImageGenToolCatalog.TOOL_NAME)

/**
 * 一轮对话（两条用户消息之间）里最后一条 Agent 正文视为最终结果，其余为中间步骤。
 * 流式传输期间当前轮次尚未结束，最后一轮不标记，等传输结束后复制按钮才出现；
 * 之前已结束轮次的最终结果不受影响。
 */
internal fun resolveFinalResultMessageIds(
    messages: List<AgentChatMessageUi>,
    isStreaming: Boolean = false,
): Set<String> {
    val ids = LinkedHashSet<String>()
    var lastAgentMessageId: String? = null
    messages.forEach { message ->
        when (message) {
            is UserMessageUi -> {
                lastAgentMessageId?.let(ids::add)
                lastAgentMessageId = null
            }
            is AgentMessageUi -> lastAgentMessageId = message.id
            else -> Unit
        }
    }
    if (!isStreaming) {
        lastAgentMessageId?.let(ids::add)
    }
    return ids
}

@Composable
private fun AgentChatBottomBar(
    imeAvoidanceEnabled: Boolean,
    input: String,
    isCompacting: Boolean,
    contextUsage: AgentContextUsageUi,
    showContextUsage: Boolean,
    isStreaming: Boolean,
    reasoningEffort: ReasoningEffort,
    availableReasoningEfforts: List<ReasoningEffort>,
    pendingImages: List<PendingImageUi>,
    pendingFileReferences: List<PendingFileReferenceUi>,
    queuedMessages: List<QueuedMessageUi>,
    inputSyncVersion: Int,
    focusInputOnSync: Boolean,
    messageEdit: MessageEditUiState?,
    glassBackdrop: Backdrop?,
    onSubmit: (String) -> Unit,
    onInterruptAndSubmit: (String) -> Unit,
    onSteerQueuedMessage: (String) -> Unit,
    onRecallQueuedMessage: (String, String) -> Unit,
    onRemoveQueuedMessage: (String) -> Unit,
    onReasoningEffortChange: (ReasoningEffort) -> Unit,
    onCompactContext: () -> Unit,
    canCompactContext: Boolean,
    onStop: () -> Unit,
    onAttachImage: (String) -> Unit,
    onRemoveImage: (String) -> Unit,
    onAttachFiles: (List<String>) -> Unit,
    onAttachFolder: (String) -> Unit,
    onAttachFilePath: (String) -> Unit,
    onRemoveFileReference: (String) -> Unit,
    onCancelMessageEdit: () -> Unit,
) {
    val surface = MiuixTheme.colorScheme.surface
    val bottomBarInsets = rememberFloatingBottomBarInsets(
        baseInsets = WindowInsets.navigationBars,
        fixedBottomPadding = 10.dp,
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (imeAvoidanceEnabled) Modifier.imePadding() else Modifier),
    ) {
        // 底部以连续垂直渐隐承接悬浮输入胶囊，避免一整块不透明实心矩形把消息流生硬截断。
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        0.0f to Color.Transparent,
                        0.26f to surface.copy(alpha = 0.86f),
                        0.55f to surface.copy(alpha = 0.98f),
                        1.0f to surface,
                    ),
                ),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(bottomBarInsets)
                .padding(top = 14.dp, start = 14.dp, end = 14.dp, bottom = 10.dp),
        ) {
            AgentChatInputBar(
                input = input,
                isCompacting = isCompacting,
                contextUsage = contextUsage,
                showContextUsage = showContextUsage,
                isStreaming = isStreaming,
                reasoningEffort = reasoningEffort,
                availableReasoningEfforts = availableReasoningEfforts,
                pendingImages = pendingImages,
                pendingFileReferences = pendingFileReferences,
                queuedMessages = queuedMessages,
                inputSyncVersion = inputSyncVersion,
                focusInputOnSync = focusInputOnSync,
                isEditingMessage = messageEdit != null,
                editHasLaterTurns = messageEdit?.hasLaterTurns == true,
                preserveFollowingMessages = messageEdit?.preserveFollowingMessages == true,
                onSubmit = onSubmit,
                onInterruptAndSubmit = onInterruptAndSubmit,
                onSteerQueuedMessage = onSteerQueuedMessage,
                onRecallQueuedMessage = onRecallQueuedMessage,
                onRemoveQueuedMessage = onRemoveQueuedMessage,
                onReasoningEffortChange = onReasoningEffortChange,
                onCompactContext = onCompactContext,
                canCompactContext = canCompactContext,
                onStop = onStop,
                onAttachImage = onAttachImage,
                onRemoveImage = onRemoveImage,
                onAttachFiles = onAttachFiles,
                onAttachFolder = onAttachFolder,
                onAttachFilePath = onAttachFilePath,
                onRemoveFileReference = onRemoveFileReference,
                onCancelMessageEdit = onCancelMessageEdit,
                glassBackdrop = glassBackdrop,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private const val BOTTOM_FOLLOW_RESPONSE_SECONDS = 0.085f
private const val BOTTOM_FOLLOW_MAX_FRAME_SECONDS = 0.05f
private const val BOTTOM_FOLLOW_MAX_SPEED_DP_PER_SECOND = 720f
private const val BOTTOM_FOLLOW_MIN_STEP_PX = 0.5f
private const val BOTTOM_FOLLOW_SNAP_DISTANCE_PX = 0.75f
private const val EXPAND_REANCHOR_SUPPRESS_MS = 350L
private const val BOTTOM_SETTLE_TIMEOUT_MS = 600L

private class TimelineEntriesCache {
    var entries: List<AgentTimelineEntry> = emptyList()
}

internal fun resolveKeepBottomAnchored(
    current: Boolean,
    isUserDragging: Boolean,
    isAtBottom: Boolean,
    suppressAutoReanchor: Boolean = false,
): Boolean = when {
    isUserDragging -> isAtBottom
    suppressAutoReanchor -> current
    isAtBottom -> true
    else -> current
}

internal fun resolveBottomFollowEnabled(
    isStreaming: Boolean,
    keepBottomAnchored: Boolean,
    isUserDragging: Boolean,
    isBottomSettling: Boolean = false,
): Boolean = (isStreaming || isBottomSettling) && keepBottomAnchored && !isUserDragging

internal fun shouldRequestInitialBottom(
    isStreaming: Boolean,
    keepBottomAnchored: Boolean,
    isUserDragging: Boolean,
): Boolean = isStreaming && keepBottomAnchored && !isUserDragging

@Composable
private fun EmptyChatState(
    showSuggestions: Boolean,
    onSuggestionClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val character = LocalRoleplayChat.current
    val isCharacterConversation = character != null
    val suggestions = listOf(
        SuggestionItem(
            title = stringResource(R.string.ui_analyze_current_screen_ebf08f),
            icon = Icons.Rounded.DocumentScanner,
            prompt = stringResource(R.string.suggestion_analyze_screen_prompt),
        ),
        SuggestionItem(
            title = stringResource(R.string.ui_open_wechat_6b2c28),
            icon = Icons.Rounded.RocketLaunch,
            prompt = stringResource(R.string.suggestion_open_wechat_prompt),
        ),
        SuggestionItem(
            title = stringResource(R.string.ui_browse_the_web_da7afb),
            icon = Icons.Rounded.Language,
            prompt = stringResource(R.string.suggestion_browse_web_prompt),
        ),
        SuggestionItem(
            title = stringResource(R.string.ui_check_memory_pressure_2d9600),
            icon = Icons.Rounded.Terminal,
            prompt = stringResource(R.string.suggestion_memory_pressure_prompt),
        ),
    )

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(bottom = 56.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (character != null) {
                val isDark = MiuixTheme.colorScheme.surface.luminance() < 0.5f
                CharacterAvatar(
                    name = character.characterName,
                    avatarPath = character.avatarPath,
                    size = 68.dp,
                    shape = CircleShape,
                    borderWidth = 0.5.dp,
                    borderColor = if (isDark) {
                        Color.White.copy(alpha = 0.14f)
                    } else {
                        Color.Black.copy(alpha = 0.08f)
                    },
                )
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = character.characterName,
                    style = MiuixTheme.textStyles.title2.copy(
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = (-0.2).sp,
                    ),
                    color = MiuixTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.chat_character_story_start),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            } else {
                Text(
                    text = stringResource(R.string.ui_how_can_i_help_you_e75391),
                    style = MiuixTheme.textStyles.title2.copy(
                        fontSize = 22.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = (-0.2).sp,
                    ),
                    color = MiuixTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(26.dp))
            }

            AnimatedVisibility(
                visible = showSuggestions && !isCharacterConversation,
                enter = fadeIn(
                    animationSpec = tween(durationMillis = 220)
                ) + slideInVertically(
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMediumLow,
                    ),
                    initialOffsetY = { it / 3 },
                ),
                exit = fadeOut(
                    animationSpec = tween(durationMillis = 130)
                ) + slideOutVertically(
                    animationSpec = tween(durationMillis = 180),
                    targetOffsetY = { it / 4 },
                ),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    suggestions.chunked(2).forEach { rowItems ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            rowItems.forEach { item ->
                                SuggestionCard(
                                    item = item,
                                    onClick = { onSuggestionClick(item.prompt) },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
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
    val isDark = MiuixTheme.colorScheme.surface.luminance() < 0.5f
    val ink = MiuixTheme.colorScheme.onSurface.copy(alpha = 1f)
    val fill = ink.copy(alpha = if (isDark) 0.055f else 0.028f)
    val border = ink.copy(alpha = if (isDark) 0.10f else 0.065f)
    val interactionSource = remember { MutableInteractionSource() }
    val shapeModifier = Modifier
        .glassPress(interactionSource)
        .squircleSurface(color = fill, cornerRadius = 15.dp)
        .squircleBorder(width = 0.5.dp, color = border, cornerRadius = 15.dp)
        .clickable(
            interactionSource = interactionSource,
            indication = null,
            onClick = onClick,
        )
    Row(
        modifier = modifier
            .then(shapeModifier)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(MiuixTheme.colorScheme.primary.copy(alpha = if (isDark) 0.16f else 0.10f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = item.icon,
                contentDescription = null,
                modifier = Modifier.size(15.dp),
                tint = MiuixTheme.colorScheme.primary,
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = item.title,
            style = MiuixTheme.textStyles.body2.copy(
                fontSize = 13.5.sp,
                fontWeight = FontWeight.Medium,
            ),
            color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.88f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private data class SuggestionItem(
    val title: String,
    val icon: ImageVector,
    val prompt: String,
)
