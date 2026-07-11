package fuck.andes.ui.app

import android.content.ComponentName
import android.content.Context
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import fuck.andes.FuckAndesApp
import fuck.andes.agent.accessibility.AgentAccessibilityService
import fuck.andes.agent.device.DeviceLocationProvider
import fuck.andes.agent.media.AgentImageCodec
import fuck.andes.agent.model.AgentModelClient
import fuck.andes.agent.runtime.AgentEvent
import fuck.andes.agent.runtime.AgentExternalArchivePayload
import fuck.andes.agent.runtime.AgentRunArchiveStore
import fuck.andes.agent.runtime.AgentRuntimeClient
import fuck.andes.agent.runtime.AgentRuntimeWire
import fuck.andes.agent.runtime.AgentTokenUsage
import fuck.andes.agent.runtime.AgentUiHandoffPayload
import fuck.andes.agent.skill.SkillRuntime
import fuck.andes.config.Prefs
import fuck.andes.core.AndroidAgentLogger
import fuck.andes.core.safeLogType
import fuck.andes.data.repository.RuntimeConfigRepository
import fuck.andes.ui.model.AgentChatHomeUiState
import fuck.andes.ui.model.AgentChatMessageUi
import fuck.andes.ui.model.AgentMessageUi
import fuck.andes.ui.model.AgentSkillsUiState
import fuck.andes.ui.model.AgentSystemEnhanceUiState
import fuck.andes.ui.model.AgentToolsUiState
import fuck.andes.ui.model.ConversationModeUi
import fuck.andes.ui.model.ConversationPaneUiState
import fuck.andes.ui.model.ConversationSummaryUi
import fuck.andes.ui.model.PermissionHealthItemUi
import fuck.andes.ui.model.PermissionHealthUiState
import fuck.andes.ui.model.PermissionStatusUi
import fuck.andes.ui.model.PendingImageUi
import fuck.andes.ui.model.SkillItemUi
import fuck.andes.ui.model.SystemEnhanceItemUi
import fuck.andes.ui.model.SystemEnhanceSectionUi
import fuck.andes.ui.model.SystemEnhanceStatusUi
import fuck.andes.ui.model.ThinkingMessageUi
import fuck.andes.ui.model.TokenUsageUi
import fuck.andes.ui.model.ToolActivityMessageUi
import fuck.andes.ui.model.ToolGroupUi
import fuck.andes.ui.model.ToolItemUi
import fuck.andes.ui.model.UserMessageUi
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class AgentAppState(
    context: Context,
    private val scope: CoroutineScope,
) {
    private val appContext = context.applicationContext
    private val runConversationIds = mutableMapOf<String, String>()
    private val runMessageProjector = AgentRunMessageProjector()
    private var currentRunId: String? = null
    private var currentRunJob: Job? = null
    private val persistenceLock = Any()
    private var persistenceJob: Job? = null
    private val runtimeRecoveryInProgress = AtomicBoolean(false)
    private val defaultThinkingEnabled = remoteBooleanForUi(Prefs.Keys.AGENT_THINKING_ENABLED)
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

    var toolsState by mutableStateOf(buildToolsState())
        private set

    var skillsState by mutableStateOf(AgentSkillsUiState(isLoading = true))
        private set

    var permissionHealthState by mutableStateOf(buildPermissionHealthState(appContext))
        private set

    var systemEnhanceState by mutableStateOf(buildSystemEnhanceState())
        private set

    init {
        refreshConversationSummaries()
        runtimeRecoveryInProgress.set(true)
        scope.launch(Dispatchers.IO) {
            try {
                recoverOrphanedRuns()
                importArchivedExternalRuns()
            } finally {
                runtimeRecoveryInProgress.set(false)
            }
        }
    }

    fun refreshRuntimeResults() {
        if (!runtimeRecoveryInProgress.compareAndSet(false, true)) return
        scope.launch(Dispatchers.IO) {
            try {
                recoverOrphanedRuns()
            } finally {
                runtimeRecoveryInProgress.set(false)
            }
        }
    }

    /**
     * App 进程可能在 Agent 操作手机期间被系统杀死，导致最终结果未能更新到会话。
     * 从 Runtime 进程拉取未交付的结果，补回对应会话的 assistant 消息。
     */
    private suspend fun recoverOrphanedRuns() {
        val client = AgentRuntimeClient(appContext, AndroidAgentLogger)
        val completedRuns = runCatching {
            client.drainCompletedRuns()
        }.getOrElse { throwable ->
            AndroidAgentLogger.warnThrottled("agent_ui_drain_results_failed") {
                "Agent UI pending result recovery failed: type=${throwable.safeLogType()}"
            }
            emptyList()
        }
        if (completedRuns.isEmpty()) return
        val ours = completedRuns.filter { it.handoff.source == HANDOFF_SOURCE }
        if (ours.isEmpty()) return
        withContext(Dispatchers.Main) {
            val acknowledgeAfterSave = mutableListOf<String>()
            ours.forEach { completedRun ->
                val runId = completedRun.result.runId.ifBlank { completedRun.handoff.id }
                val payload = AgentUiHandoffPayload.from(completedRun.handoff.payload)
                val conversationId = payload.conversationId
                val state = conversationsById[conversationId] ?: return@forEach
                val result = completedRun.result
                val recovery = AgentPendingResultRecovery.apply(
                    state = state,
                    runId = runId,
                    result = result,
                    promptSupplement = payload.promptSupplement,
                    supplements = payload.supplements,
                )
                if (recovery.alreadyApplied) {
                    acknowledgeAfterSave += runId
                    return@forEach
                }
                updateConversation(conversationId, recovery.state)
                acknowledgeAfterSave += runId
            }
            refreshConversationSummaries()
            persistConversations {
                acknowledgeAfterSave.forEach(client::ackResult)
            }
        }
    }

    private suspend fun importArchivedExternalRuns() {
        val archivedRuns = AgentRunArchiveStore.list(appContext)
            .filter { AgentExternalArchivePayload.from(it.handoff.payload) != null }
        if (archivedRuns.isEmpty()) return

        withContext(Dispatchers.Main) {
            val importedRunIds = archivedRuns.mapNotNull { archivedRun ->
                importExternalRun(archivedRun)
            }
            refreshConversationSummaries()
            persistConversations {
                importedRunIds.forEach { runId ->
                    AgentRunArchiveStore.remove(appContext, runId)
                }
            }
        }
    }

    private fun importExternalRun(archivedRun: AgentRunArchiveStore.ArchivedRun): String? {
        val runId = archivedRun.result.runId.ifBlank { archivedRun.handoff.id }
        if (runId.isBlank()) return null
        val payload = AgentExternalArchivePayload.from(archivedRun.handoff.payload) ?: return null
        val conversationId = archiveConversationId(
            source = archivedRun.handoff.source,
            conversationKey = payload.conversationKey,
        )
        val existingState = conversationsById[conversationId] ?: emptyChatState(
            payload.thinkingEnabled ?: defaultThinkingEnabled
        )
        val alreadyImported = AgentRuntimeHistoryReducer.wasApplied(existingState, runId) ||
            existingState.messages.any {
                it is AgentMessageUi &&
                    (it.id == "assistant-$runId" || it.id.startsWith("assistant-$runId-")) &&
                    !it.isStreaming
            }
        if (alreadyImported) return runId

        if (conversationTitles[conversationId].isNullOrBlank() || conversationTitles[conversationId] == "新对话") {
            conversationTitles = conversationTitles + (conversationId to payload.title.ifBlank { "外部记录" })
        }
        runConversationIds[runId] = conversationId
        updateConversation(
            conversationId,
            existingState.copy(
                input = "",
                isStreaming = true,
                thinkingEnabled = payload.thinkingEnabled ?: existingState.thinkingEnabled,
                pendingImages = emptyList(),
                messages = existingState.messages +
                    UserMessageUi(id = "user-$runId", content = payload.userText) +
                    AgentMessageUi(
                        id = "assistant-$runId",
                        content = "",
                        isStreaming = true,
                        renderMarkdown = false,
                    ),
            )
        )
        archivedRun.events.forEach { event -> applyRunEvent(runId, event) }
        applyRunResult(runId, archivedRun.result)
        conversationUpdatedAt = conversationUpdatedAt + (conversationId to archivedRun.createdAt)
        return runId
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
        val state = emptyChatState(defaultThinkingEnabled)
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

    fun deleteConversation(conversationId: String) {
        val wasSelected = selectedConversationId == conversationId
        conversationsById = conversationsById - conversationId
        conversationTitles = conversationTitles - conversationId
        conversationUpdatedAt = conversationUpdatedAt - conversationId
        if (wasSelected) {
            val nextId = conversationsById.keys.firstOrNull()
            if (nextId != null) {
                selectedConversationId = nextId
                homeState = conversationsById.getValue(nextId)
            } else {
                selectedConversationId = newConversationId()
                val state = emptyChatState(defaultThinkingEnabled)
                conversationsById = conversationsById + (selectedConversationId to state)
                conversationTitles = conversationTitles + (selectedConversationId to "新对话")
                conversationUpdatedAt = conversationUpdatedAt + (selectedConversationId to System.currentTimeMillis())
                homeState = state
            }
        }
        conversationPaneState = conversationPaneState.copy(selectedConversationId = selectedConversationId)
        refreshConversationSummaries()
        persistConversations()
    }

    fun renameConversation(conversationId: String, title: String) {
        val trimmed = title.trim()
        if (trimmed.isBlank()) return
        conversationTitles = conversationTitles + (conversationId to trimmed)
        conversationUpdatedAt = conversationUpdatedAt + (conversationId to System.currentTimeMillis())
        refreshConversationSummaries()
        persistConversations()
    }

    fun sendCurrentMessage() {
        val prompt = homeState.input.trim()
        if (prompt.isBlank() || homeState.isStreaming) return

        if (selectedConversationId.isExternalArchiveConversation()) {
            moveCurrentDraftToNewConversation()
        }

        val conversationId = selectedConversationId
        val history = homeState.history
        val thinkingEnabled = homeState.thinkingEnabled
        val pendingImages = homeState.pendingImages
        val runId = "run-${UUID.randomUUID()}"
        val imageDataUrls = pendingImages.map { it.dataUrl }
        val userMessage = UserMessageUi(id = "user-$runId", content = prompt, images = imageDataUrls)
        val userHistoryMessage = AgentModelClient.buildUserHistoryMessage(
            text = prompt,
            images = pendingImages.map { image ->
                AgentModelClient.ModelImage(
                    dataUrl = image.dataUrl,
                    mimeType = image.mimeType,
                    bytes = image.dataUrl.length,
                    source = image.uri,
                )
            },
        )

        val title = conversationTitles[selectedConversationId]
            ?.takeUnless { it == "新对话" }
            ?: prompt.lineSequence().firstOrNull().orEmpty().trim().take(MAX_TITLE_CHARS).ifBlank { "新对话" }

        conversationTitles = conversationTitles + (selectedConversationId to title)
        runConversationIds[runId] = conversationId
        currentRunId = runId

        updateConversation(
            conversationId,
            homeState.copy(
                input = "",
                isStreaming = true,
                pendingImages = emptyList(),
                history = homeState.history + userHistoryMessage,
                messages = homeState.messages + userMessage,
            )
        )
        refreshConversationSummaries()
        persistConversations()

        currentRunJob = scope.launch(Dispatchers.IO) {
            val config = RuntimeConfigRepository.currentRuntimeConfig()?.copy(
                terminalTools = remoteBooleanForUi(Prefs.Keys.AGENT_TERMINAL_TOOLS),
                browserTools = remoteBooleanForUi(Prefs.Keys.AGENT_BROWSER_TOOLS),
                thinkingEnabled = thinkingEnabled,
            )
            if (config == null) {
                withContext(Dispatchers.Main) {
                    applyRunResult(
                        runId,
                        AgentRuntimeWire.RunResult(
                            runId = runId,
                            ok = false,
                            content = "",
                            error = "请先配置模型提供商和模型",
                        )
                    )
                }
                return@launch
            }
            val modelImages = pendingImages.map { p ->
                AgentModelClient.ModelImage(
                    dataUrl = p.dataUrl,
                    mimeType = p.mimeType,
                    bytes = 0,
                    source = "user_attach",
                )
            }
            val result = AgentRuntimeClient(appContext, AndroidAgentLogger).run(
                request = AgentRuntimeWire.RunRequest(
                    runId = runId,
                    prompt = prompt,
                    config = config,
                    images = modelImages,
                    history = history,
                    handoff = AgentRuntimeWire.EntryHandoff(
                        id = runId,
                        source = HANDOFF_SOURCE,
                        payload = conversationId,
                    ),
                ),
                onEvent = { event -> applyRunEvent(runId, event) },
            )
            withContext(Dispatchers.Main) {
                applyRunResult(runId, result, acknowledgeRuntimeResult = true)
            }
        }
    }

    fun attachImage(uri: String) {
        scope.launch(Dispatchers.IO) {
            val image = AgentImageCodec.fromReference(appContext, uri, "user_attach") ?: return@launch
            val pending = PendingImageUi(
                id = "img-${UUID.randomUUID()}",
                uri = uri,
                dataUrl = image.dataUrl,
                mimeType = image.mimeType,
            )
            withContext(Dispatchers.Main) {
                updateCurrentConversation(homeState.copy(pendingImages = homeState.pendingImages + pending))
            }
        }
    }

    fun removePendingImage(id: String) {
        updateCurrentConversation(homeState.copy(pendingImages = homeState.pendingImages.filterNot { it.id == id }))
    }

    fun stopCurrentRun() {
        val runId = currentRunId ?: return
        currentRunJob?.cancel()
        currentRunJob = null
        currentRunId = null
        scope.launch(Dispatchers.IO) {
            AgentRuntimeClient(appContext, AndroidAgentLogger).cancelRun(runId)
        }
        updateRunTrace(runId) { messages ->
            val finalizedThinking = runMessageProjector.finalizeThinking(runId, messages)
            val finalizedText = runMessageProjector.finalizeText(runId, finalizedThinking)
            runMessageProjector.failRunningTools("已停止", finalizedText)
        }
        replaceLatestAssistantMessage(runId, content = "已停止", isStreaming = false, renderMarkdown = false)
        setConversationStreaming(runId, false)
        runMessageProjector.clearRun(runId)
        runConversationIds.remove(runId)
        refreshConversationSummaries()
        persistConversations()
    }

    fun refreshPermissionHealth() {
        permissionHealthState = buildPermissionHealthState(appContext)
    }

    fun refreshSkills() {
        scope.launch(Dispatchers.IO) {
            val indexService = SkillRuntime.createIndexService(appContext)
            val entries = indexService.listSkillsForManagement(forceRefresh = true)
            val items = entries.map { entry ->
                val capabilities = buildList {
                    if (entry.hasScripts) add("scripts")
                    if (entry.hasReferences) add("references")
                    if (entry.hasAssets) add("assets")
                    if (entry.hasEvals) add("evals")
                }
                SkillItemUi(
                    id = entry.id,
                    name = entry.name,
                    description = entry.description,
                    source = entry.source,
                    enabled = entry.enabled,
                    installed = entry.installed,
                    capabilities = capabilities,
                )
            }
            withContext(Dispatchers.Main) {
                skillsState = AgentSkillsUiState(skills = items, isLoading = false)
            }
        }
    }

    fun toggleSkill(skillId: String, enabled: Boolean) {
        scope.launch(Dispatchers.IO) {
            val indexService = SkillRuntime.createIndexService(appContext)
            indexService.setSkillEnabled(skillId, enabled)
            refreshSkills()
        }
    }

    fun deleteSkill(skillId: String) {
        scope.launch(Dispatchers.IO) {
            val indexService = SkillRuntime.createIndexService(appContext)
            indexService.deleteSkill(skillId)
            refreshSkills()
        }
    }

    fun reinstallBuiltin(skillId: String) {
        scope.launch(Dispatchers.IO) {
            val indexService = SkillRuntime.createIndexService(appContext)
            indexService.installBuiltinSkill(skillId)
            refreshSkills()
        }
    }

    private fun applyRunEvent(runId: String, event: AgentEvent) {
        when (event) {
            is AgentEvent.AssistantBlockStart -> {
                if (event.kind == AgentEvent.AssistantBlockKind.TOOL_CALL) {
                    updateRunTrace(runId) { messages ->
                        runMessageProjector.finalizeTextRound(runId, event.round, messages)
                    }
                }
            }

            is AgentEvent.AssistantBlockDelta -> {
                updateRunTrace(runId) { messages ->
                    when (event.kind) {
                        AgentEvent.AssistantBlockKind.TEXT ->
                            runMessageProjector.appendTextDelta(runId, event.round, event.delta, messages)

                        AgentEvent.AssistantBlockKind.THINKING ->
                            runMessageProjector.appendReasoningDelta(runId, event.round, event.delta, messages)

                        AgentEvent.AssistantBlockKind.TOOL_CALL -> messages
                    }
                }
            }

            is AgentEvent.AssistantBlockEnd -> {
                updateRunTrace(runId) { messages ->
                    when (event.kind) {
                        AgentEvent.AssistantBlockKind.TEXT ->
                            runMessageProjector.finalizeTextRound(runId, event.round, messages)

                        AgentEvent.AssistantBlockKind.THINKING ->
                            runMessageProjector.finalizeThinkingRound(runId, event.round, messages)

                        AgentEvent.AssistantBlockKind.TOOL_CALL -> messages
                    }
                }
            }

            is AgentEvent.UsageReceived -> {
                updateAssistantUsage(runId, event.round, event.usage.toUi())
            }

            is AgentEvent.UserSupplementReceived -> {
                insertSupplementMessage(runId, event.index, event.text)
            }

            is AgentEvent.ToolStarted -> {
                updateRunTrace(runId) { messages ->
                    val finalizedThinking = runMessageProjector.finalizeThinking(runId, messages)
                    val finalizedText = runMessageProjector.finalizeTextRound(runId, event.round, finalizedThinking)
                    runMessageProjector.startTool(runId, event, finalizedText)
                }
            }

            is AgentEvent.ToolFinished -> {
                updateRunTrace(runId) { messages ->
                    runMessageProjector.finishTool(runId, event, messages)
                }
            }

            is AgentEvent.RunFailed -> {
                updateRunTrace(runId) { messages ->
                    val finalizedThinking = runMessageProjector.finalizeThinking(runId, messages)
                    val finalizedText = runMessageProjector.finalizeText(runId, finalizedThinking)
                    runMessageProjector.failRunningTools(event.reason, finalizedText)
                }
            }

            is AgentEvent.AssistantReceived -> {
                if (event.reasoningContent.isNotBlank()) {
                    updateRunTrace(runId) { messages ->
                        runMessageProjector.ensureCompletedThinking(
                            runId = runId,
                            round = event.round,
                            content = event.reasoningContent,
                            messages = messages,
                        )
                    }
                }
            }

            is AgentEvent.RunFinished -> {
                updateRunTrace(runId) { messages ->
                    val finalizedThinking = runMessageProjector.finalizeThinking(runId, messages)
                    runMessageProjector.finalizeText(runId, finalizedThinking)
                }
            }

            is AgentEvent.RunStarted,
            is AgentEvent.ProviderRequestStarted,
            is AgentEvent.ProviderResponseStarted,
            is AgentEvent.ToolImagesAttached,
            is AgentEvent.RoundStarted,
            -> Unit
        }
    }

    private fun applyRunResult(
        runId: String,
        result: AgentRuntimeWire.RunResult,
        acknowledgeRuntimeResult: Boolean = false,
    ) {
        if (runId == currentRunId) {
            currentRunId = null
            currentRunJob = null
        }
        val content = if (result.ok) {
            result.content.ifBlank { "已完成。" }
        } else {
            result.error ?: "Agent Runtime 调用失败"
        }

        applyConversationHistoryResult(runId, result.transcript)
        replaceLatestAssistantMessage(runId, content, isStreaming = false, renderMarkdown = result.ok)
        setConversationStreaming(runId, false)
        runMessageProjector.clearRun(runId)
        runConversationIds.remove(runId)
        refreshConversationSummaries()
        persistConversations(
            onSaved = if (acknowledgeRuntimeResult) {
                {
                    AgentRuntimeClient(appContext, AndroidAgentLogger).ackResult(runId)
                }
            } else {
                null
            }
        )
    }

    private fun updateRunTrace(
        runId: String,
        transform: (List<AgentChatMessageUi>) -> List<AgentChatMessageUi>,
    ) {
        updateMessages(runId, transform)
        refreshConversationSummaries()
    }

    private fun updateAssistantUsage(runId: String, round: Int, usage: TokenUsageUi) {
        if (usage.isEmpty) return
        replaceAssistantMessage(
            runId = runId,
            round = round,
            content = currentAssistantContent(runId, round),
            isStreaming = true,
            usage = usage,
        )
    }

    private fun insertSupplementMessage(runId: String, index: Int, text: String) {
        updateMessages(runId) { messages ->
            AgentPendingResultRecovery.mergeSupplements(
                runId = runId,
                supplements = listOf(
                    AgentUiHandoffPayload.Supplement(
                        index = index,
                        text = text,
                        createdAt = System.currentTimeMillis(),
                    )
                ),
                messages = messages,
            )
        }
        refreshConversationSummaries()
        persistConversations()
    }

    private fun currentAssistantContent(runId: String, round: Int): String =
        conversationStateForRun(runId).messages
            .filterIsInstance<AgentMessageUi>()
            .firstOrNull { it.id == assistantMessageId(runId, round) }
            ?.content
            .orEmpty()

    private fun replaceAssistantMessage(
        runId: String,
        round: Int,
        content: String,
        isStreaming: Boolean,
        renderMarkdown: Boolean? = null,
        usage: TokenUsageUi? = null,
    ) {
        updateMessages(runId) { messages ->
            val assistantId = assistantMessageId(runId, round)
            var replaced = false
            val updated = messages.map { message ->
                if (message is AgentMessageUi && message.id == assistantId) {
                    replaced = true
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
            if (replaced) {
                updated
            } else {
                updated + AgentMessageUi(
                    id = assistantId,
                    content = content,
                    isStreaming = isStreaming,
                    renderMarkdown = renderMarkdown ?: false,
                    usage = usage,
                )
            }
        }
    }

    private fun replaceLatestAssistantMessage(
        runId: String,
        content: String,
        isStreaming: Boolean,
        renderMarkdown: Boolean? = null,
        usage: TokenUsageUi? = null,
    ) {
        replaceAssistantMessage(
            runId = runId,
            round = latestAssistantRound(runId) ?: 1,
            content = content,
            isStreaming = isStreaming,
            renderMarkdown = renderMarkdown,
            usage = usage,
        )
    }

    private fun latestAssistantRound(runId: String): Int? =
        conversationStateForRun(runId).messages
            .filterIsInstance<AgentMessageUi>()
            .mapNotNull { assistantRound(runId, it.id) }
            .maxOrNull()

    private fun assistantMessageId(runId: String, round: Int): String =
        "${assistantMessagePrefix(runId)}$round"

    private fun assistantMessagePrefix(runId: String): String =
        "assistant-$runId-"

    private fun assistantRound(runId: String, messageId: String): Int? =
        messageId.removePrefix(assistantMessagePrefix(runId))
            .takeIf { it != messageId }
            ?.toIntOrNull()

    private fun updateMessages(
        runId: String,
        transform: (List<AgentChatMessageUi>) -> List<AgentChatMessageUi>,
    ) {
        val conversationId = conversationIdForRun(runId)
        val state = conversationsById[conversationId] ?: return
        updateConversation(conversationId, state.copy(messages = transform(state.messages)))
    }

    private fun applyConversationHistoryResult(
        runId: String,
        additions: List<AgentModelClient.ConversationMessage>,
    ) {
        val conversationId = conversationIdForRun(runId)
        val state = conversationsById[conversationId] ?: return
        val outcome = AgentRuntimeHistoryReducer.apply(state, runId, additions)
        if (!outcome.alreadyApplied) updateConversation(conversationId, outcome.state)
    }

    private fun updateCurrentConversation(state: AgentChatHomeUiState) {
        updateConversation(selectedConversationId, state)
    }

    private fun moveCurrentDraftToNewConversation() {
        val draft = homeState
        selectedConversationId = newConversationId()
        val state = emptyChatState(defaultThinkingEnabled).copy(
            input = draft.input,
            thinkingEnabled = draft.thinkingEnabled,
            pendingImages = draft.pendingImages,
        )
        conversationsById = conversationsById + (selectedConversationId to state)
        conversationTitles = conversationTitles + (selectedConversationId to "新对话")
        conversationUpdatedAt = conversationUpdatedAt + (selectedConversationId to System.currentTimeMillis())
        homeState = state
        conversationPaneState = conversationPaneState.copy(selectedConversationId = selectedConversationId)
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
                        conversationUpdatedAt[id]?.let(ConversationTimeLabels::label) ?: "最近"
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

    private fun persistConversations(onSaved: (() -> Unit)? = null) {
        val selected = selectedConversationId
        val conversations = conversationsById
        val titles = conversationTitles
        val timestamps = conversationUpdatedAt
        synchronized(persistenceLock) {
            val previous = persistenceJob
            persistenceJob = scope.launch(Dispatchers.IO) {
                try {
                    previous?.join()
                    AgentConversationStore.save(
                        context = appContext,
                        selectedConversationId = selected,
                        conversationsById = conversations,
                        titles = titles,
                        updatedAt = timestamps,
                    )
                    onSaved?.invoke()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (throwable: Throwable) {
                    AndroidAgentLogger.error(
                        "Agent conversation persistence failed: type=${throwable.safeLogType()}"
                    )
                }
            }
        }
    }

    private companion object {
        const val HANDOFF_SOURCE = "agent_ui"
        const val MAX_TITLE_CHARS = 24
        const val MAX_PREVIEW_CHARS = 48

        fun emptyChatState(thinkingEnabled: Boolean): AgentChatHomeUiState =
            AgentChatHomeUiState(
                messages = emptyList(),
                history = emptyList(),
                input = "",
                isStreaming = false,
                thinkingEnabled = thinkingEnabled,
            )

        fun newConversationId(): String = "conv-${UUID.randomUUID()}"
    }
}

private const val EXTERNAL_ARCHIVE_CONVERSATION_PREFIX = "archive-"

private fun String.isExternalArchiveConversation(): Boolean =
    startsWith(EXTERNAL_ARCHIVE_CONVERSATION_PREFIX)

private fun archiveConversationId(source: String, conversationKey: String): String =
    "$EXTERNAL_ARCHIVE_CONVERSATION_PREFIX${stableArchiveId("$source:$conversationKey")}"

private fun stableArchiveId(value: String): String =
    java.security.MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .take(12)
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

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
                id = "web",
                title = "网页浏览",
                tools = listOf(
                    ToolItemUi("browser_use", "Agent 浏览器", "离屏打开网页，并保持可接管的浏览会话"),
                    ToolItemUi("browser_read", "阅读网页", "提取渲染后的正文、列表与链接"),
                    ToolItemUi("browser_interact", "网页交互", "查找、点击并输入页面元素"),
                    ToolItemUi("browser_screenshot", "页面截图", "把当前网页视口交给视觉模型"),
                ),
            ),
            ToolGroupUi(
                id = "app",
                title = "应用与系统",
                tools = listOf(
                    ToolItemUi("search_apps", "搜索应用", "按名称或包名查询已安装应用"),
                    ToolItemUi("get_current_context", "时间与位置", "读取系统时间与最近位置"),
                    ToolItemUi("launch_app", "打开 App", "启动指定包名或应用名"),
                    ToolItemUi("open_uri", "用应用打开", "把链接或 deep link 显式交给外部应用"),
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
    val backgroundRunningEnabled = isIgnoringBatteryOptimizations(context)
    val overlayEnabled = Settings.canDrawOverlays(context)
    val appListEnabled = hasAppListAccess(context)
    val accessibilityEnabled = isAgentAccessibilityEnabled(context) || AgentAccessibilityService.isAvailable()
    val rootEnabled = isRootAvailable()
    val locationAccess = DeviceLocationProvider.accessState(context)

    return PermissionHealthUiState(
        items = listOf(
            PermissionHealthItemUi(
                id = "background",
                title = "后台运行权限",
                summary = "",
                status = if (backgroundRunningEnabled) PermissionStatusUi.Available else PermissionStatusUi.Missing,
                primaryActionLabel = if (backgroundRunningEnabled) null else "去开启",
            ),
            PermissionHealthItemUi(
                id = "overlay",
                title = "悬浮窗权限",
                summary = "",
                status = if (overlayEnabled) PermissionStatusUi.Available else PermissionStatusUi.Missing,
                primaryActionLabel = if (overlayEnabled) null else "去授权",
            ),
            PermissionHealthItemUi(
                id = "app_list",
                title = "应用列表读取",
                summary = "",
                status = if (appListEnabled) PermissionStatusUi.Available else PermissionStatusUi.Missing,
                primaryActionLabel = if (appListEnabled) null else "去开启",
            ),
            PermissionHealthItemUi(
                id = "location",
                title = "位置权限",
                summary = when (locationAccess) {
                    DeviceLocationProvider.AccessState.DENIED -> "用于按需理解手机所在位置"
                    DeviceLocationProvider.AccessState.FOREGROUND_ONLY -> "小布入口需要设为“始终允许”"
                    DeviceLocationProvider.AccessState.DISABLED -> "系统定位服务已关闭"
                    DeviceLocationProvider.AccessState.AVAILABLE -> "仅在 Agent 调用工具时读取"
                },
                status = when (locationAccess) {
                    DeviceLocationProvider.AccessState.DENIED -> PermissionStatusUi.Missing
                    DeviceLocationProvider.AccessState.FOREGROUND_ONLY -> PermissionStatusUi.Warning
                    DeviceLocationProvider.AccessState.DISABLED -> PermissionStatusUi.Disabled
                    DeviceLocationProvider.AccessState.AVAILABLE -> PermissionStatusUi.Available
                },
                primaryActionLabel = when (locationAccess) {
                    DeviceLocationProvider.AccessState.DENIED -> "去授权"
                    DeviceLocationProvider.AccessState.FOREGROUND_ONLY -> "去设置"
                    DeviceLocationProvider.AccessState.DISABLED -> "去开启"
                    DeviceLocationProvider.AccessState.AVAILABLE -> null
                },
            ),
            PermissionHealthItemUi(
                id = "accessibility",
                title = "无障碍辅助权限",
                summary = "",
                status = if (accessibilityEnabled) PermissionStatusUi.Available else PermissionStatusUi.Missing,
                primaryActionLabel = if (accessibilityEnabled) null else "去开启",
            ),
            PermissionHealthItemUi(
                id = "root",
                title = "Root 权限",
                summary = "",
                status = if (rootEnabled) PermissionStatusUi.Available else PermissionStatusUi.Missing,
                primaryActionLabel = if (rootEnabled) null else "去开启",
            ),
        )
    )
}

private fun remoteBooleanForUi(key: String): Boolean {
    val default = Prefs.Keys.BOOLEAN_DEFAULTS[key] ?: true
    return Prefs.remotePreferencesForUi(FuckAndesApp.serviceInstance)
        ?.getBoolean(key, default)
        ?: Prefs.isEnabled(key)
}

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

private fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    return powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: false
}

private fun isRootAvailable(): Boolean {
    return try {
        val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
        val exitCode = process.waitFor()
        exitCode == 0
    } catch (e: Exception) {
        false
    }
}

private fun hasAppListAccess(context: Context): Boolean {
    return try {
        val pm = context.packageManager
        val packages = pm.getInstalledPackages(0)
        packages.size > 10
    } catch (e: Exception) {
        false
    }
}
