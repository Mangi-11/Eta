package fuck.andes.ui.app

import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import fuck.andes.FuckAndesApp
import fuck.andes.agent.accessibility.AgentAccessibilityService
import fuck.andes.agent.model.AgentModelClient
import fuck.andes.agent.runtime.AgentEvent
import fuck.andes.agent.runtime.AgentRuntimeClient
import fuck.andes.agent.runtime.AgentRuntimeWire
import fuck.andes.agent.runtime.AgentTokenUsage
import fuck.andes.config.Prefs
import fuck.andes.core.AndroidAgentLogger
import fuck.andes.ui.model.AgentChatHomeUiState
import fuck.andes.ui.model.AgentChatMessageUi
import fuck.andes.ui.model.AgentMessageUi
import fuck.andes.ui.model.AgentRunDetailUiState
import fuck.andes.ui.model.AgentRunsUiState
import fuck.andes.ui.model.AgentSystemEnhanceUiState
import fuck.andes.ui.model.AgentToolsUiState
import fuck.andes.ui.model.ConversationModeUi
import fuck.andes.ui.model.ConversationPaneUiState
import fuck.andes.ui.model.ConversationSummaryUi
import fuck.andes.ui.model.PermissionHealthItemUi
import fuck.andes.ui.model.PermissionHealthUiState
import fuck.andes.ui.model.PermissionStatusUi
import fuck.andes.ui.model.RunStatusUi
import fuck.andes.ui.model.RunSummaryUi
import fuck.andes.ui.model.RunTimelineItemUi
import fuck.andes.ui.model.SystemEnhanceItemUi
import fuck.andes.ui.model.SystemEnhanceSectionUi
import fuck.andes.ui.model.SystemEnhanceStatusUi
import fuck.andes.ui.model.ThinkingMessageUi
import fuck.andes.ui.model.TokenUsageUi
import fuck.andes.ui.model.ToolActivityMessageUi
import fuck.andes.ui.model.ToolActivityStatusUi
import fuck.andes.ui.model.ToolGroupUi
import fuck.andes.ui.model.ToolItemUi
import fuck.andes.ui.model.UserMessageUi
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class AgentAppState(
    context: Context,
    private val scope: CoroutineScope,
) {
    private val appContext = context.applicationContext
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val runDetails = mutableStateMapOf<String, AgentRunDetailUiState>()
    private val runStarts = mutableMapOf<String, Long>()
    private val runToolCounts = mutableMapOf<String, Int>()
    private val runConversationIds = mutableMapOf<String, String>()
    private val runThinkingStartedAt = mutableMapOf<String, Long>()
    private val defaultThinkingEnabled = loadModelConfigForUi().thinkingEnabled
    private val initialConversations = AgentConversationStore.load(appContext, defaultThinkingEnabled)

    private var selectedConversationId: String = initialConversations.selectedConversationId
    private var conversationsById: Map<String, AgentChatHomeUiState> = initialConversations.conversationsById
    private var conversationTitles: Map<String, String> = initialConversations.titles
    private var conversationUpdatedAt: Map<String, Long> = initialConversations.updatedAt

    var homeState by mutableStateOf(
        conversationsById[selectedConversationId] ?: emptyChatState(defaultThinkingEnabled)
    )
        private set

    var conversationPaneState by mutableStateOf(
        ConversationPaneUiState(
            conversations = listOf(
                ConversationSummaryUi(
                    id = selectedConversationId,
                    title = "新对话",
                    preview = "直接输入问题，必要时 Agent 会操作手机",
                    timeLabel = "现在",
                    mode = ConversationModeUi.Chat,
                )
            ),
            selectedConversationId = selectedConversationId,
            searchQuery = "",
        )
    )
        private set

    var runsState by mutableStateOf(AgentRunsUiState(emptyList()))
        private set

    var toolsState by mutableStateOf(buildToolsState())
        private set

    var permissionHealthState by mutableStateOf(buildPermissionHealthState(appContext))
        private set

    var systemEnhanceState by mutableStateOf(buildSystemEnhanceState())
        private set

    init {
        refreshConversationSummaries()
    }

    fun updateInput(text: String) {
        updateCurrentConversation(homeState.copy(input = text))
    }

    fun updateThinkingEnabled(enabled: Boolean) {
        updateCurrentConversation(homeState.copy(thinkingEnabled = enabled))
        persistConversations()
    }

    fun updateSearchQuery(query: String) {
        conversationPaneState = conversationPaneState.copy(searchQuery = query)
    }

    fun selectConversation(conversationId: String) {
        val state = conversationsById[conversationId] ?: return
        selectedConversationId = conversationId
        homeState = state
        conversationPaneState = conversationPaneState.copy(selectedConversationId = conversationId)
        persistConversations()
    }

    fun createConversation() {
        selectedConversationId = newConversationId()
        val state = emptyChatState(loadModelConfigForUi().thinkingEnabled)
        conversationsById = conversationsById + (selectedConversationId to state)
        conversationTitles = conversationTitles + (selectedConversationId to "新对话")
        conversationUpdatedAt = conversationUpdatedAt + (selectedConversationId to System.currentTimeMillis())
        homeState = state
        conversationPaneState = conversationPaneState.copy(
            selectedConversationId = selectedConversationId,
            searchQuery = "",
        )
        refreshConversationSummaries()
        persistConversations()
    }

    fun sendCurrentMessage() {
        val prompt = homeState.input.trim()
        if (prompt.isBlank() || homeState.isStreaming) return

        val conversationId = selectedConversationId
        val history = buildConversationHistory(homeState.messages)
        val thinkingEnabled = homeState.thinkingEnabled
        val runId = "run-${UUID.randomUUID()}"
        val now = System.currentTimeMillis()
        val userMessage = UserMessageUi(id = "user-$runId", content = prompt)
        val assistantMessage = AgentMessageUi(
            id = "assistant-$runId",
            content = "",
            isStreaming = true,
            renderMarkdown = false,
        )

        val title = conversationTitles[selectedConversationId]
            ?.takeUnless { it == "新对话" }
            ?: prompt.lineSequence().firstOrNull().orEmpty().trim().take(MAX_TITLE_CHARS).ifBlank { "新对话" }

        conversationTitles = conversationTitles + (selectedConversationId to title)
        runStarts[runId] = SystemClock.elapsedRealtime()
        runToolCounts[runId] = 0
        runConversationIds[runId] = conversationId

        updateConversation(
            conversationId,
            homeState.copy(
                input = "",
                isStreaming = true,
                messages = homeState.messages + userMessage + assistantMessage,
            )
        )
        putRunSummary(
            RunSummaryUi(
                runId = runId,
                status = RunStatusUi.Running,
                title = title,
                timeLabel = timeFormat.format(Date(now)),
                toolCount = 0,
                durationLabel = "运行中",
            )
        )
        runDetails[runId] = AgentRunDetailUiState(
            runId = runId,
            status = RunStatusUi.Running,
            title = title,
            startedAt = timeFormat.format(Date(now)),
            finishedAt = null,
            durationLabel = null,
            timeline = listOf(
                RunTimelineItemUi.UserRequest(
                    id = "$runId-user",
                    content = prompt,
                ),
                RunTimelineItemUi.ModelThinking(
                    id = "$runId-starting",
                    content = "准备调用 Agent Runtime",
                ),
            ),
        )
        refreshConversationSummaries()
        persistConversations()

        scope.launch(Dispatchers.IO) {
            val config = loadModelConfigForUi().copy(thinkingEnabled = thinkingEnabled)
            val result = AgentRuntimeClient(appContext, AndroidAgentLogger).run(
                request = AgentRuntimeWire.RunRequest(
                    runId = runId,
                    prompt = prompt,
                    config = config,
                    images = emptyList(),
                    history = history,
                ),
                onEvent = { event -> applyRunEvent(runId, event) },
            )
            withContext(Dispatchers.Main) {
                applyRunResult(runId, result)
            }
        }
    }

    fun detailState(runId: String): AgentRunDetailUiState =
        runDetails[runId] ?: AgentRunDetailUiState(
            runId = runId,
            status = RunStatusUi.Failed,
            title = "运行不存在",
            startedAt = "",
            finishedAt = null,
            durationLabel = null,
            timeline = listOf(
                RunTimelineItemUi.Error(
                    id = "$runId-missing",
                    message = "当前会话内没有找到这个 run",
                )
            ),
        )

    fun refreshPermissionHealth() {
        permissionHealthState = buildPermissionHealthState(appContext)
    }

    private fun applyRunEvent(runId: String, event: AgentEvent) {
        when (event) {
            is AgentEvent.RunStarted -> {
                appendTimeline(
                    runId,
                    RunTimelineItemUi.ModelThinking(
                        id = "$runId-runtime",
                        content = "Runtime 已启动，工具 ${event.toolCount} 个，终端工具${if (event.terminalTools) "已启用" else "未启用"}",
                    )
                )
            }

            is AgentEvent.ProviderRequestStarted -> {
                appendTimeline(
                    runId,
                    RunTimelineItemUi.ModelThinking(
                        id = "$runId-provider-${event.round}",
                        content = "第 ${event.round} 轮请求模型",
                    )
                )
            }

            is AgentEvent.ProviderResponseStarted -> {
                appendTimeline(
                    runId,
                    RunTimelineItemUi.ModelThinking(
                        id = "$runId-http-${event.round}",
                        content = "模型开始响应，HTTP ${event.httpCode}",
                    )
                )
            }

            is AgentEvent.AssistantTextDelta -> {
                appendAssistantDelta(runId, event.delta)
            }

            is AgentEvent.AssistantReasoningDelta -> {
                appendReasoningDelta(runId, event.delta)
            }

            is AgentEvent.UsageReceived -> {
                updateAssistantUsage(runId, event.usage.toUi())
            }

            is AgentEvent.ToolStarted -> {
                val count = (runToolCounts[runId] ?: 0) + 1
                runToolCounts[runId] = count
                updateRunToolCount(runId, count)
                appendMessageOnce(
                    runId,
                    ToolActivityMessageUi(
                        id = "$runId-tool-$count",
                        toolName = event.name,
                        status = ToolActivityStatusUi.Running,
                        argumentsSummary = event.argsPreview,
                    )
                )
                appendTimeline(
                    runId,
                    RunTimelineItemUi.ToolCall(
                        id = "$runId-tool-call-$count",
                        toolName = event.name,
                        argumentsSummary = event.argsPreview,
                    )
                )
                refreshConversationSummaries()
            }

            is AgentEvent.ToolFinished -> {
                updateToolActivityFinished(runId, event)
                appendTimeline(
                    runId,
                    RunTimelineItemUi.ToolResult(
                        id = "$runId-tool-result-${event.round}-${event.name}",
                        success = true,
                        summary = event.resultSummary,
                    )
                )
            }

            is AgentEvent.ToolImagesAttached -> {
                appendTimeline(
                    runId,
                    RunTimelineItemUi.Screenshot(
                        id = "$runId-tool-image-${event.round}-${event.toolName}",
                        description = "${event.toolName} 返回 ${event.imageCount} 张观察图片",
                    )
                )
            }

            is AgentEvent.RunFailed -> {
                finalizeThinking(runId)
                markRunningToolsFailed(runId, event.reason)
                appendTimeline(
                    runId,
                    RunTimelineItemUi.Error(
                        id = "$runId-event-error",
                        message = event.reason,
                    )
                )
            }

            is AgentEvent.AssistantReceived -> {
                if (event.reasoningContent.isNotBlank()) {
                    ensureCompletedThinking(runId, event.reasoningContent)
                }
            }

            is AgentEvent.RunFinished -> {
                finalizeThinking(runId)
            }

            is AgentEvent.RoundStarted,
            is AgentEvent.ProviderToolCallDelta -> Unit
        }
    }

    private fun applyRunResult(runId: String, result: AgentRuntimeWire.RunResult) {
        val duration = durationLabel(runId)
        val status = if (result.ok) RunStatusUi.Success else RunStatusUi.Failed
        val content = if (result.ok) {
            result.content.ifBlank { "已完成。" }
        } else {
            result.error ?: "Agent Runtime 调用失败"
        }

        replaceAssistantMessage(
            runId = runId,
            content = content,
            isStreaming = false,
            renderMarkdown = result.ok,
        )
        setConversationStreaming(runId, false)
        updateRunStatus(runId, status, duration)
        appendTimeline(
            runId,
            if (result.ok) {
                RunTimelineItemUi.FinalResult(
                    id = "$runId-final",
                    content = content,
                )
            } else {
                RunTimelineItemUi.Error(
                    id = "$runId-final-error",
                    message = content,
                )
            }
        )
        runDetails[runId]?.let { detail ->
            runDetails[runId] = detail.copy(
                status = status,
                finishedAt = timeFormat.format(Date()),
                durationLabel = duration,
            )
        }
        refreshConversationSummaries()
        persistConversations()
    }

    private fun appendAssistantDelta(runId: String, delta: String) {
        if (delta.isEmpty()) return
        replaceAssistantMessage(
            runId = runId,
            content = currentAssistantContent(runId) + delta,
            isStreaming = true,
            renderMarkdown = false,
        )
        refreshConversationSummaries()
    }

    private fun appendReasoningDelta(runId: String, delta: String) {
        if (delta.isEmpty()) return
        val startedAt = runThinkingStartedAt.getOrPut(runId) { SystemClock.elapsedRealtime() }
        val elapsedSeconds = ((SystemClock.elapsedRealtime() - startedAt) / 1000).toInt().coerceAtLeast(0)
        val thinkingId = "$runId-thinking"
        var updated = false
        updateMessages(runId) { messages ->
            val next = messages.map { message ->
                if (message is ThinkingMessageUi && message.id == thinkingId) {
                    updated = true
                    message.copy(
                        content = message.content + delta,
                        isStreaming = true,
                        elapsedSeconds = elapsedSeconds,
                        collapsed = false,
                    )
                } else {
                    message
                }
            }
            if (updated) {
                next
            } else {
                val assistantIndex = next.indexOfFirst { it is AgentMessageUi && it.id == "assistant-$runId" }
                val thinkingMessage = ThinkingMessageUi(
                    id = thinkingId,
                    content = delta,
                    isStreaming = true,
                    elapsedSeconds = elapsedSeconds,
                    collapsed = false,
                )
                if (assistantIndex >= 0) {
                    next.toMutableList().apply { add(assistantIndex, thinkingMessage) }
                } else {
                    next + thinkingMessage
                }
            }
        }
        refreshConversationSummaries()
    }

    private fun ensureCompletedThinking(runId: String, content: String) {
        val thinkingId = "$runId-thinking"
        if (conversationStateForRun(runId).messages.any { it is ThinkingMessageUi && it.id == thinkingId }) {
            finalizeThinking(runId)
            return
        }
        val startedAt = runThinkingStartedAt.getOrPut(runId) { SystemClock.elapsedRealtime() }
        val elapsedSeconds = ((SystemClock.elapsedRealtime() - startedAt) / 1000).toInt().coerceAtLeast(0)
        updateMessages(runId) { messages ->
            val assistantIndex = messages.indexOfFirst { it is AgentMessageUi && it.id == "assistant-$runId" }
            val thinkingMessage = ThinkingMessageUi(
                id = thinkingId,
                content = content,
                isStreaming = false,
                elapsedSeconds = elapsedSeconds,
                collapsed = true,
            )
            if (assistantIndex >= 0) {
                messages.toMutableList().apply { add(assistantIndex, thinkingMessage) }
            } else {
                messages + thinkingMessage
            }
        }
    }

    private fun finalizeThinking(runId: String) {
        val startedAt = runThinkingStartedAt[runId]
        updateMessages(runId) { messages ->
            messages.map { message ->
                if (message is ThinkingMessageUi && message.id == "$runId-thinking") {
                    val elapsedSeconds = startedAt?.let {
                        ((SystemClock.elapsedRealtime() - it) / 1000).toInt().coerceAtLeast(0)
                    } ?: message.elapsedSeconds
                    message.copy(
                        isStreaming = false,
                        elapsedSeconds = elapsedSeconds,
                        collapsed = true,
                    )
                } else {
                    message
                }
            }
        }
    }

    private fun updateAssistantUsage(runId: String, usage: TokenUsageUi) {
        if (usage.isEmpty) return
        replaceAssistantMessage(
            runId = runId,
            content = currentAssistantContent(runId),
            isStreaming = true,
            usage = usage,
        )
    }

    private fun updateToolActivityFinished(runId: String, event: AgentEvent.ToolFinished) {
        val status = if (event.resultSummary.contains("ok=false", ignoreCase = true)) {
            ToolActivityStatusUi.Failed
        } else {
            ToolActivityStatusUi.Success
        }
        updateMessages(runId) { messages ->
            val targetIndex = messages.indexOfLast {
                it is ToolActivityMessageUi &&
                    it.toolName == event.name &&
                    it.status == ToolActivityStatusUi.Running
            }
            if (targetIndex < 0) return@updateMessages messages
            messages.mapIndexed { index, message ->
                if (index == targetIndex && message is ToolActivityMessageUi) {
                    message.copy(
                        status = status,
                        resultSummary = event.resultSummary,
                        imageCount = event.imageCount,
                    )
                } else {
                    message
                }
            }
        }
    }

    private fun markRunningToolsFailed(runId: String, reason: String) {
        updateMessages(runId) { messages ->
            messages.map { message ->
                if (message is ToolActivityMessageUi && message.status == ToolActivityStatusUi.Running) {
                    message.copy(
                        status = ToolActivityStatusUi.Failed,
                        resultSummary = reason.take(MAX_PREVIEW_CHARS),
                    )
                } else {
                    message
                }
            }
        }
    }

    private fun currentAssistantContent(runId: String): String =
        conversationStateForRun(runId).messages
            .filterIsInstance<AgentMessageUi>()
            .firstOrNull { it.id == "assistant-$runId" }
            ?.content
            .orEmpty()

    private fun replaceAssistantMessage(
        runId: String,
        content: String,
        isStreaming: Boolean,
        renderMarkdown: Boolean? = null,
        usage: TokenUsageUi? = null,
    ) {
        updateMessages(runId) { messages ->
            messages.map { message ->
                if (message is AgentMessageUi && message.id == "assistant-$runId") {
                    message.copy(
                        content = content,
                        isStreaming = isStreaming,
                        renderMarkdown = renderMarkdown ?: message.renderMarkdown,
                        usage = usage ?: message.usage,
                    )
                } else {
                    message
                }
            }
        }
    }

    private fun appendMessageOnce(runId: String, message: AgentChatMessageUi) {
        val state = conversationStateForRun(runId)
        if (state.messages.any { it.id == message.id }) return
        updateMessages(runId) { it + message }
    }

    private fun updateMessages(
        runId: String,
        transform: (List<AgentChatMessageUi>) -> List<AgentChatMessageUi>,
    ) {
        val conversationId = conversationIdForRun(runId)
        val state = conversationsById[conversationId] ?: return
        updateConversation(conversationId, state.copy(messages = transform(state.messages)))
    }

    private fun updateCurrentConversation(state: AgentChatHomeUiState) {
        updateConversation(selectedConversationId, state)
    }

    private fun updateConversation(conversationId: String, state: AgentChatHomeUiState) {
        conversationsById = conversationsById + (conversationId to state)
        conversationUpdatedAt = conversationUpdatedAt + (conversationId to System.currentTimeMillis())
        if (conversationId == selectedConversationId) {
            homeState = state
        }
    }

    private fun setConversationStreaming(runId: String, isStreaming: Boolean) {
        val conversationId = conversationIdForRun(runId)
        val state = conversationsById[conversationId] ?: return
        updateConversation(conversationId, state.copy(isStreaming = isStreaming))
    }

    private fun conversationIdForRun(runId: String): String =
        runConversationIds[runId] ?: selectedConversationId

    private fun conversationStateForRun(runId: String): AgentChatHomeUiState {
        val conversationId = conversationIdForRun(runId)
        return conversationsById[conversationId] ?: emptyChatState(defaultThinkingEnabled)
    }

    private fun appendTimeline(runId: String, item: RunTimelineItemUi) {
        val detail = runDetails[runId] ?: return
        if (detail.timeline.any { it.id == item.id }) return
        runDetails[runId] = detail.copy(timeline = detail.timeline + item)
    }

    private fun putRunSummary(summary: RunSummaryUi) {
        runsState = runsState.copy(
            runs = listOf(summary) + runsState.runs.filterNot { it.runId == summary.runId },
        )
    }

    private fun updateRunToolCount(runId: String, toolCount: Int) {
        runsState = runsState.copy(
            runs = runsState.runs.map { run ->
                if (run.runId == runId) run.copy(toolCount = toolCount) else run
            }
        )
    }

    private fun updateRunStatus(runId: String, status: RunStatusUi, duration: String) {
        runsState = runsState.copy(
            runs = runsState.runs.map { run ->
                if (run.runId == runId) {
                    run.copy(status = status, durationLabel = duration)
                } else {
                    run
                }
            }
        )
    }

    private fun refreshConversationSummaries() {
        val summaries = conversationsById.entries
            .sortedByDescending { (id, _) ->
                conversationUpdatedAt[id] ?: 0L
            }
            .map { (id, state) ->
                val lastMessage = state.messages.lastOrNull()
                ConversationSummaryUi(
                    id = id,
                    title = conversationTitles[id] ?: "新对话",
                    preview = when (lastMessage) {
                        is UserMessageUi -> lastMessage.content
                        is AgentMessageUi -> lastMessage.content.ifBlank { "Agent 正在思考" }
                        is ThinkingMessageUi -> "Agent 正在思考"
                        is ToolActivityMessageUi -> "调用工具：${lastMessage.toolName}"
                        else -> "直接输入问题，必要时 Agent 会操作手机"
                    }.take(MAX_PREVIEW_CHARS),
                    timeLabel = if (state.isStreaming) {
                        "现在"
                    } else {
                        conversationUpdatedAt[id]?.let { timeFormat.format(Date(it)) } ?: "最近"
                    },
                    mode = ConversationModeUi.Chat,
                    isActiveRun = state.isStreaming,
                )
            }
        val query = conversationPaneState.searchQuery.trim()
        conversationPaneState = conversationPaneState.copy(
            selectedConversationId = selectedConversationId,
            conversations = if (query.isBlank()) {
                summaries
            } else {
                summaries.filter {
                    it.title.contains(query, ignoreCase = true) ||
                        it.preview.contains(query, ignoreCase = true)
                }
            },
        )
    }

    private fun persistConversations() {
        AgentConversationStore.save(
            context = appContext,
            selectedConversationId = selectedConversationId,
            conversationsById = conversationsById,
            titles = conversationTitles,
            updatedAt = conversationUpdatedAt,
        )
    }

    private fun buildConversationHistory(messages: List<AgentChatMessageUi>): List<AgentModelClient.ConversationMessage> {
        val candidates = messages.mapNotNull { message ->
            when (message) {
                is UserMessageUi -> AgentModelClient.ConversationMessage(
                    role = "user",
                    content = message.content,
                )

                is AgentMessageUi -> message.content
                    .takeIf { it.isNotBlank() && !message.isStreaming }
                    ?.let { content ->
                        AgentModelClient.ConversationMessage(
                            role = "assistant",
                            content = content,
                        )
                    }

                else -> null
            }
        }.takeLast(MAX_CONTEXT_MESSAGES)

        val reversed = candidates.asReversed()
        var remainingChars = MAX_CONTEXT_CHARS
        val selected = mutableListOf<AgentModelClient.ConversationMessage>()
        reversed.forEach { message ->
            val content = message.content.trim()
            if (content.isBlank() || remainingChars <= 0) return@forEach
            val clipped = if (content.length > remainingChars) {
                content.takeLast(remainingChars)
            } else {
                content
            }
            selected += message.copy(content = clipped)
            remainingChars -= clipped.length
        }
        return selected.asReversed()
    }

    private fun durationLabel(runId: String): String {
        val started = runStarts[runId] ?: return ""
        val seconds = ((SystemClock.elapsedRealtime() - started) / 1000).coerceAtLeast(0)
        return "${seconds} 秒"
    }

    private companion object {
        const val MAX_TITLE_CHARS = 24
        const val MAX_PREVIEW_CHARS = 48
        const val MAX_CONTEXT_MESSAGES = 12
        const val MAX_CONTEXT_CHARS = 24_000

        fun emptyChatState(thinkingEnabled: Boolean): AgentChatHomeUiState =
            AgentChatHomeUiState(
                messages = emptyList(),
                input = "",
                isStreaming = false,
                thinkingEnabled = thinkingEnabled,
            )

        fun newConversationId(): String = "conv-${UUID.randomUUID()}"
    }
}

private fun buildToolsState(): AgentToolsUiState =
    AgentToolsUiState(
        groups = listOf(
            ToolGroupUi(
                id = "screen",
                title = "屏幕与控件",
                tools = listOf(
                    ToolItemUi("observe_screen", "观察屏幕", "截图并读取当前无障碍节点"),
                    ToolItemUi("tap_element", "点击元素", "按最近一次观察到的节点点击"),
                    ToolItemUi("tap_area", "点击区域", "按坐标区域点击"),
                    ToolItemUi("long_press", "长按", "长按坐标或元素"),
                    ToolItemUi("swipe", "滑动", "执行上下左右滑动手势"),
                    ToolItemUi("scroll", "滚动", "滚动页面或指定节点"),
                ),
            ),
            ToolGroupUi(
                id = "text",
                title = "文本与剪贴板",
                tools = listOf(
                    ToolItemUi("input_text", "输入文字", "向当前焦点追加或粘贴文本"),
                    ToolItemUi("replace_text", "替换文本", "替换焦点或节点中的文本"),
                    ToolItemUi("clear_text", "清空文本", "清空焦点或节点文本"),
                    ToolItemUi("paste_text", "粘贴文本", "用剪贴板可靠输入长文本"),
                    ToolItemUi("wait_for_text", "等待文本", "等待指定文本出现在屏幕上"),
                ),
            ),
            ToolGroupUi(
                id = "app",
                title = "应用与系统",
                tools = listOf(
                    ToolItemUi("search_apps", "搜索应用", "按名称或包名查询已安装应用"),
                    ToolItemUi("launch_app", "打开 App", "启动指定包名或应用名"),
                    ToolItemUi("open_uri", "打开 URI", "启动链接或 deep link"),
                    ToolItemUi("press_key", "按键", "返回、主页、最近任务等系统按键"),
                    ToolItemUi("open_system_panel", "系统面板", "打开通知栏、快捷设置等面板"),
                ),
            ),
            ToolGroupUi(
                id = "terminal",
                title = "终端与文件",
                tools = listOf(
                    ToolItemUi("terminal", "会话终端", "user/root shell，会话式执行与异步读取"),
                    ToolItemUi("run_command", "执行命令", "直接执行单条 shell 命令"),
                    ToolItemUi("read_file", "读取文件", "读取手机文件内容"),
                    ToolItemUi("write_file", "写入文件", "写入或覆盖手机文件"),
                    ToolItemUi("list_directory", "列目录", "列出目录内容"),
                ),
            ),
        )
    )

private fun buildPermissionHealthState(context: Context): PermissionHealthUiState {
    val accessibilityEnabled = isAgentAccessibilityEnabled(context) || AgentAccessibilityService.isAvailable()
    val overlayEnabled = Settings.canDrawOverlays(context)
    val config = loadModelConfigForUi()
    return PermissionHealthUiState(
        items = listOf(
            PermissionHealthItemUi(
                id = "accessibility",
                title = "无障碍服务",
                summary = if (accessibilityEnabled) "已启用，Agent 可读取和操作 UI 节点" else "未启用，节点点击和文本编辑能力受限",
                status = if (accessibilityEnabled) PermissionStatusUi.Available else PermissionStatusUi.Missing,
                primaryActionLabel = if (accessibilityEnabled) null else "去开启",
            ),
            PermissionHealthItemUi(
                id = "overlay",
                title = "悬浮窗权限",
                summary = if (overlayEnabled) "已授权，可显示运行状态浮窗" else "未授权，Runtime 浮窗不可见",
                status = if (overlayEnabled) PermissionStatusUi.Available else PermissionStatusUi.Missing,
                primaryActionLabel = if (overlayEnabled) null else "去授权",
            ),
            PermissionHealthItemUi(
                id = "model",
                title = "模型配置",
                summary = if (config.baseUrl.isNotBlank() && config.apiKey.isNotBlank() && config.model.isNotBlank()) {
                    "已配置 ${config.model}"
                } else {
                    "缺少 API 地址、API Key 或模型名"
                },
                status = if (config.baseUrl.isNotBlank() && config.apiKey.isNotBlank() && config.model.isNotBlank()) {
                    PermissionStatusUi.Available
                } else {
                    PermissionStatusUi.Missing
                },
                primaryActionLabel = "设置",
            ),
            PermissionHealthItemUi(
                id = "terminal",
                title = "终端/文件工具",
                summary = if (config.terminalTools) "已启用，Agent 可按需使用 shell 与文件工具" else "未启用，终端和文件工具不会暴露给模型",
                status = if (config.terminalTools) PermissionStatusUi.Available else PermissionStatusUi.Disabled,
                primaryActionLabel = "设置",
            ),
        )
    )
}

private fun loadModelConfigForUi(): AgentModelClient.ModelConfig {
    val prefs = Prefs.remotePreferencesForUi(FuckAndesApp.serviceInstance)
    return if (prefs != null) {
        AgentModelClient.ModelConfig(
            baseUrl = prefs.getTrimmedString(Prefs.Keys.AGENT_BASE_URL),
            apiKey = prefs.getTrimmedString(Prefs.Keys.AGENT_API_KEY),
            model = prefs.getTrimmedString(Prefs.Keys.AGENT_MODEL),
            systemPrompt = prefs.getTrimmedString(Prefs.Keys.AGENT_SYSTEM_PROMPT),
            terminalTools = prefs.getBoolean(
                Prefs.Keys.AGENT_TERMINAL_TOOLS,
                Prefs.Keys.BOOLEAN_DEFAULTS[Prefs.Keys.AGENT_TERMINAL_TOOLS] ?: false,
            ),
            thinkingEnabled = prefs.getBoolean(
                Prefs.Keys.AGENT_THINKING_ENABLED,
                Prefs.Keys.BOOLEAN_DEFAULTS[Prefs.Keys.AGENT_THINKING_ENABLED] ?: false,
            ),
            extraBodyJson = prefs.getTrimmedString(Prefs.Keys.AGENT_EXTRA_BODY_JSON),
        )
    } else {
        AgentModelClient.loadConfig()
    }
}

private fun SharedPreferences.getTrimmedString(key: String): String =
    getString(key, Prefs.Keys.STRING_DEFAULTS[key].orEmpty()).orEmpty().trim()

private fun AgentTokenUsage.toUi(): TokenUsageUi =
    TokenUsageUi(
        contextTokens = contextTokens,
        inputTokens = inputTokens,
        outputTokens = outputTokens,
        reasoningTokens = reasoningTokens,
        cachedTokens = cachedTokens,
    )

private fun buildSystemEnhanceState(): AgentSystemEnhanceUiState =
    AgentSystemEnhanceUiState(
        sections = listOf(
            SystemEnhanceSectionUi(
                id = "runtime",
                title = "Agent Runtime",
                items = listOf(
                    SystemEnhanceItemUi(
                        id = "streaming",
                        title = "流式事件",
                        summary = "模型增量、工具调用和最终结果会同步到当前对话",
                        status = SystemEnhanceStatusUi.Active,
                    ),
                    SystemEnhanceItemUi(
                        id = "overlay",
                        title = "运行浮窗",
                        summary = "Runtime 服务运行时显示状态浮窗",
                        status = SystemEnhanceStatusUi.Active,
                    ),
                ),
            ),
            SystemEnhanceSectionUi(
                id = "future",
                title = "后续能力",
                items = listOf(
                    SystemEnhanceItemUi(
                        id = "memory",
                        title = "记忆系统",
                        summary = "长期记忆和定时触发器后续接入",
                        status = SystemEnhanceStatusUi.Inactive,
                    ),
                    SystemEnhanceItemUi(
                        id = "hook",
                        title = "Hook 二级能力",
                        summary = "系统增强能力保留为后续二级功能",
                        status = SystemEnhanceStatusUi.Inactive,
                    ),
                ),
            ),
        )
    )

private fun isAgentAccessibilityEnabled(context: Context): Boolean {
    val expected = ComponentName(
        context,
        AgentAccessibilityService::class.java,
    ).flattenToString()
    val enabledServices = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
    ).orEmpty()
    return enabledServices.split(':').any { it.equals(expected, ignoreCase = true) }
}
