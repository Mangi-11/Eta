package io.github.mangi.eta.agent.runtime

import android.content.Context
import io.github.mangi.eta.agent.accessibility.AgentAccessibilityKeeper
import io.github.mangi.eta.agent.browser.BrowserSessionRegistry
import io.github.mangi.eta.agent.model.AgentConversationCodec
import io.github.mangi.eta.agent.model.AgentConversationToolCatalog
import io.github.mangi.eta.agent.tool.ConversationHistoryTool
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.AgentModelExecutionException
import io.github.mangi.eta.agent.model.AgentModelFailure
import io.github.mangi.eta.agent.model.AgentHttpClient
import io.github.mangi.eta.agent.memory.AgentMemoryContext
import io.github.mangi.eta.agent.memory.AgentMemoryContextBuilder
import io.github.mangi.eta.agent.memory.ConversationMemoryPins
import io.github.mangi.eta.agent.roleplay.CharacterMemoryTools
import io.github.mangi.eta.agent.roleplay.RoleplayRunContext
import io.github.mangi.eta.agent.subagent.AgentSubagentToolCatalog
import io.github.mangi.eta.agent.subagent.SubagentExecutor
import io.github.mangi.eta.agent.mcp.McpRunSnapshot
import io.github.mangi.eta.agent.mcp.McpToolExecutor
import io.github.mangi.eta.agent.mcp.RoutingToolExecutor
import io.github.mangi.eta.agent.overlay.AgentOverlayVisibilityPolicy
import io.github.mangi.eta.agent.skill.SkillCompatibilityChecker
import io.github.mangi.eta.agent.skill.SkillContext
import io.github.mangi.eta.agent.skill.SkillRuntime
import io.github.mangi.eta.agent.skill.PublicGitHubSkillSource
import io.github.mangi.eta.agent.tool.AgentLocalTools
import io.github.mangi.eta.agent.tool.AgentToolRequirements
import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import io.github.mangi.eta.agent.tool.PendingSkillConflictCapabilityParser
import io.github.mangi.eta.agent.tool.ToolExecutionDecision
import io.github.mangi.eta.agent.voice.EtaAssistantOverlayService
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.data.repository.AgentMemoryRepository
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/**
 * 单次 Runtime run 的阻塞执行器。
 *
 * 它只拥有模型、工具和终态提交，不持有 Service、Messenger、Compose 或 WindowManager 状态。
 * 所有外部副作用都通过窄回调交回宿主。
 */
internal class AgentRuntimeRunExecutor(
    context: Context,
    private val currentPermissions: () -> AgentRuntimePolicy.Permissions,
    private val snapshotRequest: (AgentRuntimeWire.RunRequest) -> AgentRuntimeWire.RunRequest,
    private val guiControl: GuiControlCoordinator,
    private val onAcceptedEvent: (AgentEvent, EntrySurfaceGuard?) -> Unit,
    private val persistArtifacts: (
        AgentRuntimeWire.RunRequest,
        AgentRuntimeWire.RunResult,
        List<AgentEvent>,
    ) -> Unit,
) {
    data class Outcome(
        val result: AgentRuntimeWire.RunResult,
        val entrySurfaceGuard: EntrySurfaceGuard?,
        val completedRequest: AgentRuntimeWire.RunRequest? = null,
        val response: AgentModelClient.ModelResponse.Text? = null,
        val shouldUpdateHost: Boolean,
    )

    private val appContext = context.applicationContext

    fun execute(
        session: AgentRuntimeSession,
        request: AgentRuntimeWire.RunRequest,
    ): Outcome {
        val runController = session.controller
        val archivedEvents = AgentRunArchiveStore.createEventBuffer(request.handoff)
        var entrySurfaceGuard: EntrySurfaceGuard? = null
        var toolExecutor: AutoCloseable? = null
        var toolsBinding: AgentRunController.ResourceBinding? = null
        var guiRun: GuiControlCoordinator.RunControl? = null
        var browserBinding: AutoCloseable? = null
        var response: AgentModelClient.ModelResponse.Text? = null
        var cancelled = false
        var checkpointRecorder: AgentRunCheckpointRecorder? = null
        val timing = AgentRunTiming(AndroidAgentLogger)

        // 失败清理需要在 try 外拿到会话 id。
        var memoryConversationId: String? = null
        val result = try {
            val contextWindow = request.config.knownContextWindow
            checkpointRecorder = AgentRunCheckpointRecorder.create(appContext, request)
            entrySurfaceGuard = EntrySurfaceGuard.from(
                handoff = request.handoff,
                logger = AndroidAgentLogger,
                etaVoiceSurfaceDismissal = {
                    EtaAssistantOverlayService.dismissForForegroundOperation(appContext)
                },
            )
            val skillIndexService = SkillRuntime.createIndexService(appContext)
            val skillLoader = SkillRuntime.createLoader(appContext)
            val skillResourceReader = SkillRuntime.createResourceReader(appContext)
            val skillPackageInstaller = SkillRuntime.createPackageInstaller(appContext)
            val githubSkillSource = PublicGitHubSkillSource(
                cacheRoot = appContext.cacheDir,
                baseClient = AgentHttpClient.client,
            )
            val skillContext = SkillContext(
                installedSkills = skillIndexService.listInstalledSkills()
                    .filter { SkillCompatibilityChecker.evaluate(it).available },
            )
            val memoryEnabled = runBlocking { AgentMemoryRepository.isEnabled() }
            val uiPayload = request.handoff
                ?.takeIf { it.source == AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE }
                ?.let { AgentUiHandoffPayload.from(it.payload) }
            val conversationId = uiPayload?.conversationId
                ?.takeIf { it.isNotBlank() }
            memoryConversationId = conversationId
            val roleplayContext = conversationId?.let { id ->
                runBlocking { RoleplayRunContext.resolve(appContext, id, contextWindow, memoryEnabled) }
            }
            if (request.operation == AgentRuntimeWire.OP_REWRITE_REPLY) {
                require(roleplayContext != null) { "只有角色会话可以改写角色回复" }
                val target = request.rewriteTargetMessageId?.takeIf { it.isNotBlank() && it.length <= 256 }
                    ?: throw IllegalArgumentException("缺少有效的角色回复目标")
                require(runBlocking {
                    EtaDatabase.get(appContext).conversationDao().hasAssistantMessage(conversationId, target)
                }) { "角色回复目标不存在或不属于当前会话" }
            }
            val characterMemoryTools = roleplayContext?.let { roleplay ->
                CharacterMemoryTools(appContext, roleplay.characterId) {
                    runBlocking { AgentMemoryRepository.isEnabled() }
                }
            }
            val memoryContext = if (memoryEnabled) {
                runCatching {
                    ConversationMemoryPins.contextFor(
                        conversationId = conversationId,
                        snapshot = AgentMemoryRepository.snapshot(),
                        contextWindow = request.config.contextWindow,
                    )
                }.getOrElse { throwable ->
                    AndroidAgentLogger.warnThrottled("agent_memory_context_failed") {
                        "Agent memory context unavailable: type=${throwable.safeLogType()}"
                    }
                    AgentMemoryContextBuilder.empty(request.config.contextWindow)
                }
            } else {
                AgentMemoryContext.DISABLED
            }
            if (request.config.terminalTools || request.config.deviceDirectTools) {
                // 启动探测由 App 拥有；模型首轮等待其结果，并在历史曾授权但临时未就绪时自动复检。
                runController.throwIfCancelled()
                io.github.mangi.eta.agent.device.RootAccess.awaitOrRetryIfPreviouslyGranted(appContext)
            }
            val pendingSkillConflict = PendingSkillConflictCapabilityParser.parse(request.history)
            val mcpSnapshot = runBlocking {
                runCatching { McpRunSnapshot.load() }.getOrElse { throwable ->
                    AndroidAgentLogger.warnThrottled("agent_mcp_snapshot_failed") {
                        "MCP tool snapshot unavailable: type=${throwable.safeLogType()}"
                    }
                    McpRunSnapshot.EMPTY
                }
            }
            val mcpTools = JSONArray().also(mcpSnapshot::appendModelTools)
            val browserKey = AgentConversationIdentity.fromHandoff(request.handoff, request.runId)
            browserBinding = BrowserSessionRegistry.acquireRun(browserKey, request.runId)
            guiRun = guiControl.openRun(request.runId, runController) { status ->
                acceptEvent(
                    session, AgentEvent.GuiControlChanged(status), archivedEvents,
                    entrySurfaceGuard, checkpointRecorder,
                )
            }
            val executor = AgentLocalTools(
                context = appContext,
                logger = AndroidAgentLogger,
                browserRunId = request.runId,
                browserSession = BrowserSessionRegistry.get(browserKey),
                guiControl = guiRun,
                browserToolsEnabled = {
                    request.config.browserTools && currentPermissions().browserTools
                },
                terminalToolsEnabled = {
                    request.config.terminalTools && currentPermissions().terminalTools
                },
                deviceDirectToolsEnabled = {
                    request.config.deviceDirectTools && currentPermissions().deviceDirectTools
                },
                deviceSensitiveReadToolsEnabled = {
                    request.config.deviceSensitiveReadTools &&
                        currentPermissions().deviceSensitiveReadTools
                },
                deviceSensitiveActionToolsEnabled = {
                    request.config.deviceSensitiveActionTools &&
                        currentPermissions().deviceSensitiveActionTools
                },
                memoryToolsEnabled = {
                    runBlocking { AgentMemoryRepository.isEnabled() }
                },
                memoryWritable = roleplayContext == null,
                screenshotExcludedPackages = {
                    entrySurfaceGuard?.consumeScreenshotExcludedPackages().orEmpty()
                },
                beforeToolExecution = { toolName ->
                    val requiresAccessibility =
                        AgentToolRequirements.requiresAccessibility(toolName)
                    if (
                        !requiresAccessibility &&
                        !AgentOverlayVisibilityPolicy.requiresEntrySurfaceDismissal(toolName)
                    ) {
                        ToolExecutionDecision.Allow
                    } else {
                        val accessibility = if (requiresAccessibility) {
                            AgentAccessibilityKeeper.ensureEnabledForGuiOperation(appContext)
                        } else {
                            null
                        }
                        when {
                            accessibility != null && !accessibility.available ->
                                ToolExecutionDecision.Reject(
                                    code = accessibility.code,
                                    message = accessibility.message,
                                )
                            entrySurfaceGuard?.dismissOnce() == false ->
                                ToolExecutionDecision.Reject(
                                    code = "ENTRY_SURFACE_NOT_READY",
                                    message = "入口窗口关闭未完成；本次工具未执行，请勿在当前任务中重复调用",
                                )
                            else -> ToolExecutionDecision.Allow
                        }
                    }
                },
                skillIndexService = skillIndexService,
                skillLoader = skillLoader,
                skillResourceReader = skillResourceReader,
                githubSkillSource = githubSkillSource,
                skillPackageInstaller = skillPackageInstaller,
                runAvailableSkillIds = skillContext.installedSkills.mapTo(mutableSetOf()) { it.id },
                pendingSkillConflict = pendingSkillConflict,
                virtualDisplayEnabled = { currentPermissions().virtualDisplay },
            )
            val routingExecutor = RoutingToolExecutor(
                local = executor,
                mcp = McpToolExecutor(mcpSnapshot),
            )
            toolExecutor = routingExecutor
            toolsBinding = runController.register(routingExecutor::close)
            timing.preparationFinished(skillContext.installedSkills.size)
            val historyTool = if (request.operation == AgentRuntimeWire.OP_CHAT) {
                val id = conversationId
                if (id != null) {
                    ConversationHistoryTool {
                        val checkpoint = runBlocking { EtaDatabase.get(appContext).conversationDao().contextCheckpoint(id) }
                        val journal = AgentConversationCodec.decodeTranscript(checkpoint?.journalJson)
                            .ifEmpty { AgentConversationCodec.decodeTranscript(checkpoint?.historyJson) }
                        journal + session.transcript
                    }
                } else {
                    val baseHistory = request.history + listOfNotNull(
                        request.prompt.takeIf { it.isNotBlank() || request.images.isNotEmpty() }?.let { prompt ->
                            AgentModelClient.buildUserHistoryMessage(prompt, request.images)
                                .copy(messageId = "user-${request.runId}")
                        },
                    )
                    ConversationHistoryTool { baseHistory + session.transcript }
                }
            } else null
            val subagentAllowed = {
                request.config.subagentTools && currentPermissions().subagentTools
            }
            val subagentExecutor = SubagentExecutor(
                conversationKey = browserKey,
                parentController = runController,
                configProvider = {
                    val rawSubagentConfig = runBlocking {
                        RuntimeConfigRepository.subagentRuntimeConfig(request.config)
                    }
                    AgentRuntimePolicy.constrain(rawSubagentConfig, currentPermissions())
                },
                capabilitiesProvider = { AgentToolCapabilities.capture(appContext) },
                baseToolExecutor = routingExecutor,
                skillContext = skillContext,
                memoryContext = memoryContext,
                mcpTools = mcpTools,
                executionContext = executor.terminalExecutionContext,
                onProgress = { progressEvent ->
                    acceptEvent(
                        session,
                        progressEvent,
                        archivedEvents,
                        entrySurfaceGuard,
                        checkpointRecorder,
                    )
                },
            )
            val runTools = JSONArray(mcpTools.toString()).also { tools ->
                if (subagentAllowed()) AgentSubagentToolCatalog.appendSchemas(tools)
                if (historyTool != null) tools.put(AgentConversationToolCatalog.schema())
                if (characterMemoryTools != null && memoryEnabled) {
                    val temp = JSONArray().also(CharacterMemoryTools::appendSchemas)
                    for (index in 0 until temp.length()) {
                        val schema = temp.getJSONObject(index)
                        val name = schema.getJSONObject("function").getString("name")
                        if (Prefs.isToolEnabled(name)) {
                            tools.put(schema)
                        }
                    }
                }
            }
            val runToolExecutor = AgentModelClient.ToolExecutor { call ->
                if (call.name in AgentSubagentToolCatalog.names) {
                    if (!subagentAllowed()) {
                        AgentModelClient.ToolResult(
                            JSONObject()
                                .put("ok", false)
                                .put("code", "SUBAGENT_DISABLED")
                                .put("message", "Subagent 权限已关闭")
                                .toString(),
                        )
                    } else if (call.name == AgentSubagentToolCatalog.PROFILE_TOOL_NAME) {
                        subagentExecutor.executeProfileManagement(call)
                    } else {
                        subagentExecutor.execute(call.round, call)
                    }
                } else if (call.name == AgentConversationToolCatalog.READ_HISTORY && historyTool != null) {
                    historyTool.execute(call)
                } else if (call.name in CharacterMemoryTools.NAMES && characterMemoryTools != null) {
                    if (!Prefs.isToolEnabled(call.name)) {
                        AgentModelClient.ToolResult(
                            JSONObject()
                                .put("ok", false)
                                .put("code", "TOOL_DISABLED")
                                .put("message", "工具 ${call.name} 已在工具列表中关闭，本次未执行")
                                .toString(),
                        )
                    } else {
                        characterMemoryTools.execute(call)
                    }
                } else routingExecutor.execute(call).also { result ->
                    if (call.name == "memory_write") {
                        runCatching { JSONObject(result.content) }.getOrNull()
                            ?.takeIf { it.optBoolean("ok") }
                            ?.let { ConversationMemoryPins.recordOwnWrite(conversationId, it.optString("revision")) }
                    }
                }
            }
            val completedResponse = AgentModelClient.complete(
                config = request.config,
                sessionId = request.effectiveModelSessionId,
                operationId = request.runId,
                initialUserMessageId = uiPayload?.promptMessageId(request.runId) ?: "user-${request.runId}",
                initialSupplementIndex = uiPayload?.lastSupplementIndex ?: 0,
                roleplayContext = roleplayContext,
                rewriteReply = request.operation == AgentRuntimeWire.OP_REWRITE_REPLY,
                compactOnly = request.operation == AgentRuntimeWire.OP_COMPACT,
                onContextSnapshot = { snapshot ->
                    // 压缩会把历史里的记忆写入摘要掉，下一轮需要重新注入最新记忆。
                    ConversationMemoryPins.forget(conversationId)
                    val committed = snapshot.copy(operationId = request.runId)
                    AgentRunCheckpointStore.saveContext(appContext, request.runId, committed)
                    session.updateContext(committed)
                },
                onTranscript = { transcript ->
                    AgentRunCheckpointStore.saveTranscript(appContext, request.runId, transcript)
                    session.updateTranscript(transcript)
                },
                capabilitiesProvider = { AgentToolCapabilities.capture(appContext) },
                prompt = request.prompt,
                assistantScreenContext = request.assistantScreenContext,
                toolExecutor = runToolExecutor,
                images = request.images,
                history = request.history,
                runController = runController,
                skillContext = skillContext,
                memoryContext = memoryContext,
                additionalTools = runTools,
                executionContext = executor.terminalExecutionContext,
            ) { event ->
                timing.accept(event)
                acceptEvent(
                    session,
                    event,
                    archivedEvents,
                    entrySurfaceGuard,
                    checkpointRecorder,
                )
            }
            response = completedResponse
            AgentRuntimeWire.RunResult(
                runId = request.runId,
                ok = true,
                content = completedResponse.content,
                reasoningContent = completedResponse.reasoningContent,
                transcript = completedResponse.transcript,
                contextSnapshot = completedResponse.contextSnapshot?.copy(operationId = request.runId),
                operation = request.operation,
                rewriteTargetMessageId = request.rewriteTargetMessageId,
            )
        } catch (throwable: Throwable) {
            // 失败时历史是否完整落盘不确定，放弃固定的记忆快照；记忆未变时重新注入的内容逐字节相同。
            ConversationMemoryPins.forget(memoryConversationId)
            cancelled = runController.isCancelled || throwable is AgentRunCancelledException
            val modelFailure = throwable as? AgentModelExecutionException
            val message = if (cancelled) {
                "已停止"
            } else {
                throwable.message ?: throwable.javaClass.simpleName
            }
            if (cancelled) {
                AndroidAgentLogger.info("Agent runtime stopped")
            } else {
                val requestFailure = modelFailure?.cause as? AgentModelFailure
                AndroidAgentLogger.error(
                    "Agent runtime failed: type=${throwable.safeLogType()}, " +
                        "model_code=${requestFailure?.code.orEmpty()}, " +
                        "cause_type=${requestFailure?.cause?.safeLogType().orEmpty()}"
                )
                val event = AgentEvent.RunFailed(message)
                runCatching {
                    acceptEvent(
                        session,
                        event,
                        archivedEvents,
                        entrySurfaceGuard,
                        checkpointRecorder,
                    )
                }.onFailure { checkpointFailure ->
                    AndroidAgentLogger.error(
                        "Agent runtime failure checkpoint failed: " +
                            "type=${checkpointFailure.safeLogType()}"
                    )
                    session.emit(event)
                }
            }
            AgentRuntimeWire.RunResult(
                runId = request.runId,
                ok = false,
                content = "",
                error = message,
                reasoningContent = modelFailure?.reasoningContent.orEmpty(),
                transcript = modelFailure?.transcript.orEmpty(),
                contextSnapshot = modelFailure?.contextSnapshot?.copy(operationId = request.runId) ?: session.contextSnapshot,
                operation = request.operation,
                rewriteTargetMessageId = request.rewriteTargetMessageId,
            )
        } finally {
            runCatching { toolsBinding?.close() }
            runCatching { toolExecutor?.close() }
            runCatching { guiRun?.close() }
            runCatching { browserBinding?.close() }
        }

        if (cancelled && session.isTerminal) {
            runCatching {
                persistArtifacts(snapshotRequest(request), result, archivedEvents.orEmpty())
            }.onFailure { throwable ->
                AndroidAgentLogger.error(
                    "Agent runtime cancelled result persistence failed: " +
                        "type=${throwable.safeLogType()}"
                )
            }
            return Outcome(
                result = result,
                entrySurfaceGuard = entrySurfaceGuard,
                shouldUpdateHost = true,
            )
        }

        val completedRequest = runCatching { snapshotRequest(request) }
            .getOrElse { throwable ->
                AndroidAgentLogger.error(
                    "Agent runtime request snapshot failed: type=${throwable.safeLogType()}"
                )
                request
            }
        val committed = session.complete(result) {
            runCatching { checkpointRecorder?.seal() }
                .onFailure { throwable ->
                    AndroidAgentLogger.error(
                        "Agent runtime checkpoint seal failed: type=${throwable.safeLogType()}"
                    )
                }
            runCatching { persistArtifacts(completedRequest, result, archivedEvents.orEmpty()) }
                .onFailure { throwable ->
                    AndroidAgentLogger.error(
                        "Agent runtime artifact persistence failed: type=${throwable.safeLogType()}"
                    )
                }
        }
        return Outcome(
            result = result,
            entrySurfaceGuard = entrySurfaceGuard,
            completedRequest = completedRequest.takeIf { committed },
            response = response.takeIf { committed },
            shouldUpdateHost = committed,
        )
    }

    private fun acceptEvent(
        session: AgentRuntimeSession,
        event: AgentEvent,
        archivedEvents: MutableList<AgentEvent>?,
        entrySurfaceGuard: EntrySurfaceGuard?,
        checkpointRecorder: AgentRunCheckpointRecorder?,
    ) {
        checkpointRecorder?.accept(event)
        if (!session.emit(event)) return
        if (event !is AgentEvent.GuiControlChanged) archivedEvents?.add(event)
        if (event is AgentEvent.ModelRetryScheduled) {
            AndroidAgentLogger.warn("Agent runtime event: ${event.toLogLine()}")
        } else if (event !is AgentEvent.AssistantBlockDelta) {
            AndroidAgentLogger.debug { "Agent runtime event: ${event.toLogLine()}" }
        }
        runCatching { onAcceptedEvent(event, entrySurfaceGuard) }
            .onFailure { throwable ->
                AndroidAgentLogger.warnThrottled("runtime_event_projection_failed") {
                    "Agent runtime event projection failed: type=${throwable.safeLogType()}"
                }
            }
    }
}
