package io.github.mangi.eta.agent.subagent

import android.os.SystemClock
import io.github.mangi.eta.agent.memory.AgentMemoryContext
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.AgentModelExecutionException
import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRunCancelledException
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.runtime.AgentTokenUsage
import io.github.mangi.eta.agent.skill.SkillContext
import io.github.mangi.eta.agent.terminal.TerminalExecutionContext
import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject

/**
 * 执行单次 `subagent` 工具调用：管理独立子上下文、过滤工具目录、实时镜像内部步骤，并向父 Agent 返回结构化摘要。
 */
internal class SubagentExecutor(
    private val conversationKey: String,
    private val parentController: AgentRunController,
    private val configProvider: () -> AgentModelClient.ModelConfig,
    private val capabilitiesProvider: () -> AgentToolCapabilities,
    private val baseToolExecutor: AgentModelClient.ToolExecutor,
    private val skillContext: SkillContext,
    private val memoryContext: AgentMemoryContext,
    private val mcpTools: JSONArray,
    private val executionContext: TerminalExecutionContext?,
    private val onProgress: (AgentEvent.SubagentProgress) -> Unit,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val latestSnapshots = ConcurrentHashMap<String, String>()

    fun latestSnapshotJson(toolCallId: String): String? = latestSnapshots[toolCallId]

    fun execute(
        parentRound: Int,
        toolCall: AgentModelClient.ToolCall,
    ): AgentModelClient.ToolResult {
        parentController.throwIfCancelled()
        val args = runCatching { JSONObject(toolCall.argumentsJson) }.getOrNull()
            ?: return errorResult("INVALID_ARGUMENTS", "subagent 参数必须是合法的 JSON 对象")

        val prompt = args.optString("prompt").trim()
        val description = args.optString("description").trim()
            .replace(Regex("\\s+"), " ")
            .take(120)
        if (prompt.isEmpty()) {
            return errorResult("INVALID_ARGUMENTS", "prompt 不能为空")
        }
        if (description.isEmpty()) {
            return errorResult("INVALID_ARGUMENTS", "description 不能为空")
        }

        val resumeId = args.optString("resume").trim()
        val requestedType = args.optString("subagent_type").trim().lowercase()
        val isResume = resumeId.isNotEmpty()

        if (isResume && requestedType.isNotEmpty()) {
            return errorResult(
                "RESUME_WITH_TYPE_UNAVAILABLE",
                "resume 续接已有 Subagent 时不得同时指定 subagent_type；请仅传递 resume 与 prompt。",
            )
        }

        val entry: SubagentSessionStore.Entry
        val priorHistory: List<AgentModelClient.ConversationMessage>
        if (isResume) {
            val existing = SubagentSessionStore.get(conversationKey, resumeId)
                ?: return errorResult(
                    "SUBAGENT_NOT_FOUND",
                    "未找到可续接的 Subagent：$resumeId（可能已过期或不属于当前会话）",
                )
            if (!existing.running.compareAndSet(false, true)) {
                return errorResult(
                    "SUBAGENT_ALREADY_RUNNING",
                    "Subagent $resumeId 当前正在运行中，无法并发续接",
                )
            }
            entry = existing
            priorHistory = existing.history
        } else {
            val catalog = SubagentProfileCatalog.listAll()
            val customRole = args.optString("role").trim()
            val customTools = args.optJSONArray("tools").toStringList()
            val profile = SubagentProfile.resolve(
                name = requestedType,
                catalog = catalog,
                customRole = customRole,
                customTools = customTools,
            )
            entry = SubagentSessionStore.create(conversationKey, profile)
            priorHistory = emptyList()
        }

        val profile = entry.profile
        val subagentId = entry.subagentId
        val childController = AgentRunController()
        val controllerBinding = parentController.register(childController::cancel)

        val resolvedConfig = runCatching { configProvider() }.getOrElse { throwable ->
            SubagentSessionStore.releaseRunning(conversationKey, subagentId)
            controllerBinding.close()
            return errorResult("SUBAGENT_CONFIG_ERROR", throwable.message ?: "无法加载 Subagent 模型配置")
        }
        val modelLabel = resolvedConfig.modelDisplayName.ifBlank { resolvedConfig.model }
        val tracker = StepTracker(
            subagentId = subagentId,
            subagentType = profile.name,
            description = description,
            prompt = prompt,
            model = modelLabel,
            resumed = isResume,
        )

        var lastEmitNanos = 0L
        fun publishSnapshot(force: Boolean) {
            val now = nanoTime()
            if (!force && now - lastEmitNanos < PROGRESS_THROTTLE_NANOS) return
            lastEmitNanos = now
            val encoded = tracker.snapshot().encode()
            latestSnapshots[toolCall.id] = encoded
            onProgress(
                AgentEvent.SubagentProgress(
                    round = parentRound,
                    toolCallId = toolCall.id,
                    subagentId = subagentId,
                    subagentType = profile.name,
                    snapshotJson = encoded,
                ),
            )
        }

        publishSnapshot(force = true)

        val childToolExecutor = AgentModelClient.ToolExecutor { subCall ->
            childController.throwIfCancelled()
            if (!profile.isToolAllowed(subCall.name)) {
                AgentModelClient.ToolResult(
                    content = JSONObject()
                        .put("ok", false)
                        .put("code", "SUBAGENT_TOOL_FORBIDDEN")
                        .put(
                            "message",
                            "当前 Subagent (${profile.name}) 不允许调用工具 ${subCall.name}",
                        )
                        .toString(),
                )
            } else {
                baseToolExecutor.execute(subCall)
            }
        }

        val subagentConfig = resolvedConfig.copy(
            systemPrompt = profile.buildSystemPrompt(),
            subagentTools = false,
            supportsSearchSuggestions = false,
        )

        return try {
            val response = AgentModelClient.complete(
                config = subagentConfig,
                prompt = prompt,
                toolExecutor = childToolExecutor,
                history = priorHistory,
                runController = childController,
                skillContext = skillContext,
                memoryContext = memoryContext,
                additionalTools = profile.filterTools(mcpTools),
                executionContext = executionContext,
                capabilitiesProvider = capabilitiesProvider,
                sessionId = "$conversationKey-$subagentId",
                operationId = "${toolCall.id}-$subagentId",
                toolFilter = profile::filterTools,
                onEvent = { childEvent ->
                    val force = tracker.onChildEvent(childEvent)
                    publishSnapshot(force = force)
                },
            )
            val updatedHistory = priorHistory +
                AgentModelClient.buildUserHistoryMessage(prompt, emptyList()) +
                response.transcript
            SubagentSessionStore.complete(conversationKey, subagentId, updatedHistory)
            tracker.markCompleted(response.content)
            publishSnapshot(force = true)

            AgentModelClient.ToolResult(
                content = JSONObject()
                    .put("ok", true)
                    .put("subagent_id", subagentId)
                    .put("subagent_type", profile.name)
                    .put("description", description)
                    .put("model", modelLabel)
                    .put("status", SubagentActivitySnapshot.STATUS_COMPLETED)
                    .put("tool_calls", tracker.toolCount())
                    .put("summary", response.content)
                    .put(
                        "resume_hint",
                        "如需在此 Subagent 已有上下文基础上继续追问或深挖，可调用 subagent(resume=\"$subagentId\", prompt=\"...\", description=\"...\")。",
                    )
                    .toString(),
                command = latestSnapshots[toolCall.id],
            )
        } catch (throwable: Throwable) {
            val partial = (throwable as? AgentModelExecutionException)?.let { ex ->
                val partialHistory = priorHistory +
                    AgentModelClient.buildUserHistoryMessage(prompt, emptyList()) +
                    ex.transcript
                SubagentSessionStore.complete(conversationKey, subagentId, partialHistory)
                tracker.partialOutput()
            } ?: run {
                SubagentSessionStore.releaseRunning(conversationKey, subagentId)
                tracker.partialOutput()
            }

            val cancelled = parentController.isCancelled ||
                childController.isCancelled ||
                throwable is AgentRunCancelledException
            val errorMessage = if (cancelled) {
                "已停止"
            } else {
                throwable.message ?: throwable.javaClass.simpleName
            }
            if (cancelled) {
                tracker.markCancelled(partial)
            } else {
                tracker.markFailed(errorMessage, partial)
            }
            publishSnapshot(force = true)

            if (parentController.isCancelled) {
                throw AgentRunCancelledException()
            }

            AgentModelClient.ToolResult(
                content = JSONObject()
                    .put("ok", false)
                    .put("code", if (cancelled) "SUBAGENT_CANCELLED" else "SUBAGENT_FAILED")
                    .put("subagent_id", subagentId)
                    .put("subagent_type", profile.name)
                    .put("description", description)
                    .put("model", modelLabel)
                    .put(
                        "status",
                        if (cancelled) {
                            SubagentActivitySnapshot.STATUS_CANCELLED
                        } else {
                            SubagentActivitySnapshot.STATUS_FAILED
                        },
                    )
                    .put("message", errorMessage)
                    .apply {
                        if (partial.isNotBlank()) put("partial_output", partial)
                        if (!cancelled) {
                            put(
                                "resume_hint",
                                "可调用 subagent(resume=\"$subagentId\", prompt=\"继续完成未完部分\", description=\"$description\") 恢复执行，或由主 Agent 直接接手。",
                            )
                        }
                    }
                    .toString(),
                command = latestSnapshots[toolCall.id],
            )
        } finally {
            controllerBinding.close()
        }
    }

    fun executeProfileManagement(toolCall: AgentModelClient.ToolCall): AgentModelClient.ToolResult {
        parentController.throwIfCancelled()
        val args = runCatching { JSONObject(toolCall.argumentsJson) }.getOrNull()
            ?: return errorResult("INVALID_ARGUMENTS", "subagent_profile 参数必须是合法的 JSON 对象")
        return when (val action = args.optString("action").trim().lowercase()) {
            "list" -> {
                val profiles = SubagentProfileCatalog.listAll()
                val items = JSONArray().also { array ->
                    profiles.forEach { profile ->
                        array.put(
                            JSONObject()
                                .put("name", profile.name)
                                .put("builtin", profile.builtin)
                                .put("description", profile.summary)
                                .put("when_to_use", profile.whenToUse)
                                .put("tools", JSONArray(profile.toolHighlights)),
                        )
                    }
                }
                AgentModelClient.ToolResult(
                    content = JSONObject()
                        .put("ok", true)
                        .put("action", "list")
                        .put("agents_dir", SubagentProfileCatalog.defaultAgentsDir().absolutePath)
                        .put("profiles", items)
                        .toString(),
                )
            }

            "get" -> {
                val name = args.optString("name").trim().lowercase()
                if (name.isEmpty()) return errorResult("INVALID_ARGUMENTS", "get 需要提供 name")
                val profile = SubagentProfileCatalog.get(name)
                    ?: return errorResult("SUBAGENT_NOT_FOUND", "未找到 Subagent Profile：$name")
                AgentModelClient.ToolResult(
                    content = JSONObject()
                        .put("ok", true)
                        .put("action", "get")
                        .put("name", profile.name)
                        .put("builtin", profile.builtin)
                        .put("description", profile.summary)
                        .put("when_to_use", profile.whenToUse)
                        .put("tools", JSONArray(profile.toolHighlights))
                        .put("disallowed_tools", JSONArray(profile.disallowedTools.toList()))
                        .put("prompt", profile.roleInstruction)
                        .apply { profile.filePath?.let { put("file_path", it) } }
                        .toString(),
                )
            }

            "save" -> {
                val name = args.optString("name").trim().lowercase()
                val description = args.optString("description").trim()
                val whenToUse = args.optString("when_to_use").trim()
                val prompt = args.optString("prompt").trim()
                val tools = args.optJSONArray("tools").toStringList()
                val disallowedTools = args.optJSONArray("disallowed_tools").toStringList()
                runCatching {
                    SubagentProfileCatalog.saveCustomProfile(
                        name = name,
                        description = description,
                        whenToUse = whenToUse,
                        tools = tools,
                        disallowedTools = disallowedTools,
                        prompt = prompt,
                    )
                }.fold(
                    onSuccess = { saved ->
                        AgentModelClient.ToolResult(
                            content = JSONObject()
                                .put("ok", true)
                                .put("action", "save")
                                .put("name", saved.name)
                                .put("description", saved.summary)
                                .put("tools", JSONArray(saved.toolHighlights))
                                .put("file_path", saved.filePath.orEmpty())
                                .put("message", "已保存自定义 Subagent \"${saved.name}\"，现在可直接通过 subagent(subagent_type=\"${saved.name}\", ...) 调用。")
                                .toString(),
                        )
                    },
                    onFailure = { error ->
                        errorResult("SUBAGENT_SAVE_FAILED", error.message ?: "保存自定义 Subagent 失败")
                    },
                )
            }

            "delete" -> {
                val name = args.optString("name").trim().lowercase()
                if (name.isEmpty()) return errorResult("INVALID_ARGUMENTS", "delete 需要提供 name")
                if (name in SubagentProfile.BUILTIN_NAMES) {
                    return errorResult("BUILTIN_SUBAGENT_IMMUTABLE", "内置 Subagent ($name) 不可删除")
                }
                val deleted = SubagentProfileCatalog.deleteCustomProfile(name)
                if (!deleted) {
                    return errorResult("SUBAGENT_NOT_FOUND", "未找到可删除的自定义 Subagent：$name")
                }
                AgentModelClient.ToolResult(
                    content = JSONObject()
                        .put("ok", true)
                        .put("action", "delete")
                        .put("name", name)
                        .toString(),
                )
            }

            else -> errorResult("INVALID_ARGUMENTS", "未知的 action：$action（可选：list, get, save, delete）")
        }
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return (0 until length())
            .mapNotNull { optString(it)?.trim()?.takeIf(String::isNotEmpty) }
    }

    private fun errorResult(code: String, message: String): AgentModelClient.ToolResult =
        AgentModelClient.ToolResult(
            content = JSONObject()
                .put("ok", false)
                .put("code", code)
                .put("message", message)
                .toString(),
        )

    private class StepTracker(
        private val subagentId: String,
        private val subagentType: String,
        private val description: String,
        private val prompt: String,
        private val model: String,
        private val resumed: Boolean,
    ) {
        private val lock = Any()
        private val steps = mutableListOf<SubagentStepSnapshot>()
        private var status = SubagentActivitySnapshot.STATUS_RUNNING
        private var currentAction = "正在思考…"
        private val roundTexts = linkedMapOf<Int, StringBuilder>()
        private var finalOutput = ""
        private var errorText: String? = null
        private var latestTurnUsage: AgentTokenUsage? = null
        private var latestStepUsage: AgentTokenUsage? = null
        private var compactionInputTokens = 0
        private var compactionCachedTokens = 0
        private var compactionCacheWriteTokens = 0
        private var compactionOutputTokens = 0
        private var compactionReasoningTokens = 0
        private var compactionLlmMs = 0L

        fun onChildEvent(event: AgentEvent): Boolean = synchronized(lock) {
            when (event) {
                is AgentEvent.RoundStarted -> {
                    currentAction = if (event.round <= 1) "正在思考…" else "第 ${event.round} 轮思考…"
                    true
                }

                is AgentEvent.AssistantBlockDelta -> when (event.kind) {
                    AgentEvent.AssistantBlockKind.THINKING -> {
                        val stepId = "thinking-${event.round}-${event.index}"
                        val existingIndex = steps.indexOfFirst { it.id == stepId }
                        if (existingIndex >= 0) {
                            val current = steps[existingIndex]
                            steps[existingIndex] = current.copy(
                                detail = (current.detail + event.delta).takeLast(MAX_STEP_DETAIL_CHARS),
                                status = SubagentStepSnapshot.STATUS_RUNNING,
                            )
                        } else {
                            steps += SubagentStepSnapshot(
                                id = stepId,
                                kind = SubagentStepSnapshot.KIND_THINKING,
                                title = "思考",
                                detail = event.delta.takeLast(MAX_STEP_DETAIL_CHARS),
                                status = SubagentStepSnapshot.STATUS_RUNNING,
                            )
                        }
                        currentAction = "正在思考…"
                        false
                    }

                    AgentEvent.AssistantBlockKind.TEXT -> {
                        roundTexts.getOrPut(event.round) { StringBuilder() }.append(event.delta)
                        currentAction = "正在整理汇报…"
                        false
                    }

                    AgentEvent.AssistantBlockKind.TOOL_CALL -> false
                }

                is AgentEvent.AssistantBlockEnd -> when (event.kind) {
                    AgentEvent.AssistantBlockKind.THINKING -> {
                        val stepId = "thinking-${event.round}-${event.index}"
                        val existingIndex = steps.indexOfFirst { it.id == stepId }
                        if (existingIndex >= 0) {
                            val current = steps[existingIndex]
                            steps[existingIndex] = current.copy(
                                detail = (event.replacementContent ?: current.detail)
                                    .trim()
                                    .takeLast(MAX_STEP_DETAIL_CHARS),
                                status = SubagentStepSnapshot.STATUS_SUCCESS,
                            )
                        }
                        true
                    }

                    AgentEvent.AssistantBlockKind.TEXT -> {
                        if (event.replacementContent != null) {
                            roundTexts[event.round] = StringBuilder(event.replacementContent)
                        }
                        true
                    }

                    AgentEvent.AssistantBlockKind.TOOL_CALL -> false
                }

                is AgentEvent.ToolStarted -> {
                    finishRunningThinkingSteps()
                    val stepId = "tool-${event.round}-${event.toolCallId}"
                    val title = event.argsPreview.ifBlank { event.name }
                    val detail = event.command?.trim().orEmpty().take(MAX_STEP_DETAIL_CHARS)
                    if (steps.none { it.id == stepId }) {
                        steps += SubagentStepSnapshot(
                            id = stepId,
                            kind = SubagentStepSnapshot.KIND_TOOL,
                            title = title,
                            detail = detail,
                            status = SubagentStepSnapshot.STATUS_RUNNING,
                            toolName = event.name,
                        )
                    }
                    currentAction = title
                    true
                }

                is AgentEvent.ToolFinished -> {
                    val stepId = "tool-${event.round}-${event.toolCallId}"
                    val existingIndex = steps.indexOfLast { it.id == stepId }
                    val stepStatus = if (event.success == false) {
                        SubagentStepSnapshot.STATUS_FAILED
                    } else {
                        SubagentStepSnapshot.STATUS_SUCCESS
                    }
                    if (existingIndex >= 0) {
                        val current = steps[existingIndex]
                        val combinedDetail = listOfNotNull(
                            current.detail.takeIf { it.isNotBlank() },
                            event.resultSummary.trim().takeIf { it.isNotBlank() },
                        ).joinToString("\n").take(MAX_STEP_DETAIL_CHARS)
                        steps[existingIndex] = current.copy(
                            status = stepStatus,
                            detail = combinedDetail,
                        )
                    }
                    currentAction = "正在分析结果…"
                    true
                }

                is AgentEvent.UsageReceived -> {
                    if (event.usage.stepCount != null) {
                        latestTurnUsage = event.usage
                    } else {
                        latestStepUsage = event.usage
                    }
                    false
                }

                is AgentEvent.ContextCompaction -> {
                    if (event.phase == AgentEvent.ContextCompaction.PHASE_COMPLETED) {
                        event.usage?.let { usage ->
                            compactionInputTokens += usage.inputTokens ?: 0
                            compactionCachedTokens += usage.cachedTokens ?: 0
                            compactionCacheWriteTokens += usage.cacheWriteTokens ?: 0
                            compactionOutputTokens += usage.outputTokens ?: 0
                            compactionReasoningTokens += usage.reasoningTokens ?: 0
                            compactionLlmMs += usage.llmMs ?: 0L
                        }
                    }
                    false
                }

                else -> false
            }
        }

        fun markCompleted(content: String) = synchronized(lock) {
            finishRunningThinkingSteps()
            status = SubagentActivitySnapshot.STATUS_COMPLETED
            finalOutput = content.trim().take(MAX_OUTPUT_CHARS)
            val tools = toolCount()
            currentAction = if (tools > 0) "完成 · $tools 次工具调用" else "完成"
        }

        fun markFailed(error: String, partial: String) = synchronized(lock) {
            finishAllRunningSteps(SubagentStepSnapshot.STATUS_FAILED)
            status = SubagentActivitySnapshot.STATUS_FAILED
            errorText = error.take(1_000)
            if (partial.isNotBlank()) finalOutput = partial.take(MAX_OUTPUT_CHARS)
            currentAction = error.lineSequence().firstOrNull()?.take(80) ?: "执行失败"
        }

        fun markCancelled(partial: String) = synchronized(lock) {
            finishAllRunningSteps(SubagentStepSnapshot.STATUS_FAILED)
            status = SubagentActivitySnapshot.STATUS_CANCELLED
            errorText = "已停止"
            if (partial.isNotBlank()) finalOutput = partial.take(MAX_OUTPUT_CHARS)
            currentAction = "已停止"
        }

        fun partialOutput(): String = synchronized(lock) {
            if (finalOutput.isNotBlank()) return finalOutput
            roundTexts.values
                .map { it.toString().trim() }
                .filter { it.isNotEmpty() }
                .joinToString("\n\n")
                .takeLast(MAX_OUTPUT_CHARS)
        }

        fun toolCount(): Int = synchronized(lock) {
            steps.count { it.kind == SubagentStepSnapshot.KIND_TOOL }
        }

        fun snapshot(): SubagentActivitySnapshot = synchronized(lock) {
            val liveOutput = if (finalOutput.isNotBlank()) {
                finalOutput
            } else {
                roundTexts.values.lastOrNull()?.toString()?.trim().orEmpty().takeLast(MAX_OUTPUT_CHARS)
            }
            val baseUsage = latestTurnUsage ?: latestStepUsage
            val totalInput = (baseUsage?.inputTokens ?: 0) + compactionInputTokens
            val totalCached = (baseUsage?.cachedTokens ?: 0) + compactionCachedTokens
            val totalCacheWrite = (baseUsage?.cacheWriteTokens ?: 0) + compactionCacheWriteTokens
            val totalOutput = (baseUsage?.outputTokens ?: 0) + compactionOutputTokens
            val totalReasoning = (baseUsage?.reasoningTokens ?: 0) + compactionReasoningTokens
            val totalLlmMs = (baseUsage?.llmMs ?: 0L) + compactionLlmMs
            val usageSnapshot = if (
                totalInput > 0 || totalCached > 0 || totalCacheWrite > 0 || totalOutput > 0 || totalLlmMs > 0L
            ) {
                SubagentUsageSnapshot(
                    inputTokens = totalInput,
                    cachedTokens = totalCached,
                    cacheWriteTokens = totalCacheWrite,
                    outputTokens = totalOutput,
                    reasoningTokens = totalReasoning,
                    llmMs = totalLlmMs,
                )
            } else null
            SubagentActivitySnapshot(
                subagentId = subagentId,
                subagentType = subagentType,
                description = description,
                prompt = prompt,
                model = model,
                resumed = resumed,
                status = status,
                currentAction = currentAction,
                steps = steps.takeLast(MAX_STEPS),
                output = liveOutput,
                error = errorText,
                usage = usageSnapshot,
            )
        }

        private fun finishRunningThinkingSteps() {
            for (index in steps.indices) {
                val step = steps[index]
                if (step.kind == SubagentStepSnapshot.KIND_THINKING &&
                    step.status == SubagentStepSnapshot.STATUS_RUNNING
                ) {
                    steps[index] = step.copy(status = SubagentStepSnapshot.STATUS_SUCCESS)
                }
            }
        }

        private fun finishAllRunningSteps(terminalStatus: String) {
            for (index in steps.indices) {
                val step = steps[index]
                if (step.status == SubagentStepSnapshot.STATUS_RUNNING) {
                    steps[index] = step.copy(status = terminalStatus)
                }
            }
        }
    }

    companion object {
        private const val PROGRESS_THROTTLE_NANOS = 80_000_000L
        private const val MAX_STEP_DETAIL_CHARS = 2_000
        private const val MAX_OUTPUT_CHARS = 12_000
        private const val MAX_STEPS = 48
    }
}
