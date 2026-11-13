package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.runtime.AgentTokenUsage
import io.github.mangi.eta.agent.roleplay.RoleplayRunContext
import io.github.mangi.eta.agent.subagent.AgentSubagentToolCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import org.json.JSONArray
import org.json.JSONObject

/**
 * 单次 Agent run 的纯编排循环。
 *
 * 一次 assistant 响应及其完整工具批次构成一个 turn；
 * steering 只在 turn 结束后注入，不能用取消网络或关闭工具资源来模拟。循环不设置本地轮次上限，
 * 由模型自然结束、取消或错误终止。
 */
internal class AgentLoop(
    private val config: AgentModelClient.ModelConfig,
    private val messages: JSONArray,
    private val tools: JSONArray,
    private val provider: AgentProviderClient,
    private val toolExecutor: AgentModelClient.ToolExecutor,
    private val runController: AgentRunController,
    private val traceFormatter: AgentTraceFormatter,
    private val onEvent: (AgentEvent) -> Unit,
    private val toolsForRound: (() -> JSONArray)? = null,
    private val modelRetry: AgentModelRetry = AgentModelRetry(),
    private val sessionId: String = java.util.UUID.randomUUID().toString(),
    private val transcript: JSONArray = JSONArray(),
    private val systemCount: Int = 0,
    private val operationId: String = sessionId,
    private val onContextSnapshot: (AgentContextSnapshot) -> Unit = {},
    private val onTranscript: (List<AgentModelClient.ConversationMessage>) -> Unit = {},
    private val purpose: ProviderRequestPurpose = ProviderRequestPurpose.CHAT,
    private val roleplayContext: RoleplayRunContext? = null,
    initialSupplementIndex: Int = 0,
) {
    data class Result(
        val content: String,
        val reasoningContent: String,
        val sensitiveToolCallIds: Set<String>,
    )

    private data class ToolOutcome(
        val call: AgentModelClient.ToolCall,
        val result: AgentModelClient.ToolResult,
    )

    private var availableToolNames = emptySet<String>()
    private val accumulatedReasoning = StringBuilder()
    private val sensitiveToolCallIds = linkedSetOf<String>()
    private val toolBatchLock = Any()
    private var pendingToolImageMessage: JSONObject? = null
    private val turnMetrics = TurnMetricsAccumulator()
    private var precedingTools: JSONArray? = null
    private val context = AgentContextSession(
        config, messages, systemCount, operationId, provider, runController,
        { sensitiveToolCallIds }, onEvent, onContextSnapshot, { transcript.length() },
        roleplay = roleplayContext != null,
        mainToolsProvider = { precedingTools ?: tools },
        mainSessionId = sessionId,
        messageProjector = { source -> roleplayContext?.projectMessages(source) ?: source },
        enableCacheReuse = true,
    )
    private var supplementIndex = initialSupplementIndex

    fun contextSnapshot(): AgentContextSnapshot? = context.snapshot()

    private fun appendMessage(message: JSONObject) {
        messages.put(message)
        transcript.put(message)
    }

    private var publishedTranscriptSize = 0

    private fun publishTranscript() {
        if (publishedTranscriptSize == transcript.length()) return
        onTranscript(AgentConversationCodec.transcript(transcript, 0, sensitiveToolCallIds))
        publishedTranscriptSize = transcript.length()
    }

    fun compactOnly(): Result {
        context.compact(force = true)
        return Result("", "", emptySet())
    }

    fun reasoningSnapshot(): String = accumulatedReasoning.toString().trim()

    fun sensitiveToolCallIdsSnapshot(): Set<String> = sensitiveToolCallIds.toSet()

    fun run(): Result {
        var round = 1

        while (true) {
            runController.throwIfCancelled()
            if (purpose.allowsTools) appendPendingSteeringMessage()

            // 带签名的工具链沿用发出调用时的请求前缀，工具链完成后再刷新目录。
            val roundTools = if (SignedToolContext.hasPendingResponse(messages)) {
                precedingTools ?: tools
            } else if (purpose.allowsTools) {
                toolsForRound?.invoke() ?: tools
            } else {
                JSONArray()
            }
            precedingTools = roundTools
            availableToolNames = (0 until roundTools.length()).mapNotNullTo(mutableSetOf()) { index ->
                roundTools.optJSONObject(index)?.optJSONObject("function")?.optString("name")
            }
            publishTranscript()
            if (purpose.allowsTools) context.compact()
            val requestMessages = AssistantScreenContextProjection.project(
                roleplayContext?.projectMessages(messages) ?: messages,
            )
            var roundInputTokens: Int? = null
            var roundUsage: AgentTokenUsage? = null
            var stepStartedAtMs = monotonicNowMs()
            var firstTokenAtMs: Long? = null
            val reasoningLengthBeforeRound = accumulatedReasoning.length
            val completedRound = try {
                modelRetry.complete(
                    initialRound = round,
                    request = ProviderRequest(config, requestMessages, roundTools, sessionId, purpose),
                    provider = provider,
                    controller = runController,
                    onEvent = { event ->
                        if (event is AgentEvent.RoundStarted) {
                            roundInputTokens = null
                            roundUsage = null
                            stepStartedAtMs = monotonicNowMs()
                            firstTokenAtMs = null
                        }
                        onEvent(event)
                    },
                    onProviderEvent = { attemptRound, providerEvent ->
                        if (!purpose.allowsTools && (providerEvent is ProviderEvent.HostedToolStarted ||
                                providerEvent is ProviderEvent.SearchSuggestions ||
                                providerEvent is ProviderEvent.BlockStart && providerEvent.kind == AssistantBlockKind.TOOL_CALL)) {
                            throw AgentModelFailure("REPLY_REWRITE_TOOL_CALL", false, "改写回复时模型请求了工具，已停止；原回复未改变。")
                        }
                        if (providerEvent is ProviderEvent.RequestStarted) {
                            stepStartedAtMs = monotonicNowMs()
                            firstTokenAtMs = null
                        }
                        if (firstTokenAtMs == null &&
                            providerEvent is ProviderEvent.BlockDelta &&
                            providerEvent.delta.isNotEmpty()
                        ) {
                            firstTokenAtMs = monotonicNowMs()
                        }
                        if (providerEvent is ProviderEvent.Usage) {
                            roundUsage = providerEvent.usage
                            roundInputTokens = providerEvent.contextInputTokens ?: roundInputTokens
                        }
                        if (providerEvent is ProviderEvent.BlockDelta &&
                            providerEvent.kind == AssistantBlockKind.THINKING
                        ) {
                            accumulatedReasoning.append(providerEvent.delta)
                        }
                        if (providerEvent !is ProviderEvent.Usage) {
                            providerEvent.toAgentEvent(attemptRound)?.let(onEvent)
                        }
                    },
                    discardAttemptReasoning = { accumulatedReasoning.setLength(reasoningLengthBeforeRound) },
                ).also { response ->
                    rejectContentFilter(response.response)
                    if (purpose == ProviderRequestPurpose.CHAT) validateChatResponse(response.response)
                }
            } finally {
                // 同一回合的重试仍需原始观察；整个回合结束后才移除截图。
                discardPendingToolImageMessage()
            }
            val completedAtMs = monotonicNowMs()
            val stepLlmMs = (completedAtMs - stepStartedAtMs).coerceAtLeast(0L)
            val stepTtftMs = firstTokenAtMs?.let { (it - stepStartedAtMs).coerceAtLeast(0L) }
            val stepDecodeMs = firstTokenAtMs?.let { (completedAtMs - it).coerceAtLeast(0L) }
            context.observeInputTokens(roundInputTokens)
            round = completedRound.round
            val providerResponse = completedRound.response

            runController.throwIfCancelled()
            val assistantMessage = providerResponse.assistantMessage
            val toolCalls = AgentConversationCodec.parseToolCalls(assistantMessage)
            if (!purpose.allowsTools && toolCalls.isNotEmpty()) {
                throw AgentModelFailure("REPLY_REWRITE_TOOL_CALL", false, "改写回复时模型请求了工具，已停止；原回复未改变。")
            }
            if (purpose == ProviderRequestPurpose.REPLY_REWRITE && providerResponse.stopReason != AssistantStopReason.END_TURN) {
                throw AgentModelFailure("REPLY_REWRITE_INCOMPLETE", false, "模型未返回完整的改写回复；原回复未改变。")
            }
            val assistantReasoning = assistantMessage.optString("reasoning_content")
            if (
                assistantReasoning.isNotBlank() &&
                accumulatedReasoning.length == reasoningLengthBeforeRound
            ) {
                accumulatedReasoning.append(assistantReasoning)
            }

            appendMessage(
                AgentConversationCodec.assistantHistoryMessage(
                    source = assistantMessage,
                    toolCalls = toolCalls,
                ).put("_eta_message_id", "assistant-$operationId-$round")
            )
            onEvent(
                AgentEvent.AssistantReceived(
                    round = round,
                    contentChars = assistantMessage.optString("content").length,
                    reasoningContent = assistantReasoning,
                    toolNames = toolCalls.map { it.name },
                )
            )
            val accumulatedUsage = turnMetrics.recordStep(
                stepLlmMs = stepLlmMs,
                stepTtftMs = stepTtftMs,
                stepDecodeMs = stepDecodeMs,
                stepUsage = roundUsage,
                stepPromptTokens = roundInputTokens,
            )
            if (!accumulatedUsage.isEmpty) {
                onEvent(AgentEvent.UsageReceived(round = round, usage = accumulatedUsage))
            }

            if (toolCalls.isNotEmpty()) {
                val outcomes = executeToolBatch(round, toolCalls, providerResponse.stopReason)
                outcomes.forEach { outcome ->
                    appendMessage(AgentConversationCodec.toolResultMessage(outcome.call, outcome.result))
                }
                publishTranscript()
                appendToolImages(round, outcomes)
                publishTranscript()
                round += 1
                continue
            }

            publishTranscript()

            // assistant 已自然结束时再检查 steering。这样补充消息不会丢掉刚完成的回答。
            if (purpose.allowsTools && appendPendingSteeringOrSeal()) {
                round += 1
                continue
            }

            val content = assistantMessage.optString("content").trim()
            if (content.isBlank() || content == "null") {
                val finishReason = assistantMessage.optString("finish_reason")
                error("模型接口第 $round 轮返回为空${finishReason.takeIf { it.isNotBlank() }?.let { "：$it" }.orEmpty()}")
            }

            publishTranscript()
            if (purpose.allowsTools) context.compact(final = true)
            onEvent(AgentEvent.RunFinished(round = round, contentChars = content.length))
            return Result(
                content = content,
                reasoningContent = reasoningSnapshot(),
                sensitiveToolCallIds = sensitiveToolCallIds.toSet(),
            )
        }
    }

    /**
     * 拒答或过滤会在流中途截断回复，半截正文、思考块和工具调用都不能进入上下文：
     * 带着被截断的签名块继续请求会被上游判定为改动了 thinking 块，同一会话随后每轮都会 400。
     * 因此不写入 history、不执行工具，直接结束本次运行，由用户改写请求或换模型。
     */
    private fun rejectContentFilter(response: ProviderResponse) {
        if (response.stopReason != AssistantStopReason.CONTENT_FILTER) return
        val explanation = response.assistantMessage.optJSONObject("stop_details")
            ?.let { details -> details.optString("explanation").takeUnless { details.isNull("explanation") } }
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        throw AgentModelFailure(
            "MODEL_CONTENT_FILTER",
            false,
            buildString {
                append("模型服务商拦截了本次回复，未完成的内容已丢弃。")
                explanation?.let { append("服务商说明：").append(it) }
                append("请修改或删除触发拦截的请求后重试，或换用其他模型。")
            },
        )
    }

    private fun validateChatResponse(response: ProviderResponse) {
        val message = response.assistantMessage
        if (AgentConversationCodec.parseToolCalls(message).isNotEmpty()) return
        val content = message.optString("content").trim()
        if (content.isNotBlank() && content != "null") return
        throw when (response.stopReason) {
            AssistantStopReason.OUTPUT_LIMIT ->
                AgentModelFailure("MODEL_OUTPUT_LIMIT", false, "模型输出额度已耗尽但未生成正文，请检查输出上限或降低思考强度。")
            else -> AgentModelFailure("MODEL_EMPTY_RESPONSE", false, "模型未返回正文或工具调用，请检查服务商状态。")
        }
    }

    private fun appendPendingSteeringMessage(): Boolean {
        val supplement = runController.pollSteeringMessage() ?: return false
        appendMessage(steeringMessage(supplement))
        context.userAppended()
        turnMetrics.reset()
        return true
    }

    private fun appendPendingSteeringOrSeal(): Boolean {
        val supplement = runController.pollSteeringOrSeal() ?: return false
        appendMessage(steeringMessage(supplement))
        context.userAppended()
        turnMetrics.reset()
        return true
    }

    private fun steeringPrompt(supplement: String): String =
        "用户补充指令：$supplement\n\n请基于当前任务上下文继续执行，不要从头重复已经完成或已经验证过的操作。"

    private fun steeringMessage(supplement: String): JSONObject =
        AgentConversationCodec.userTextMessage(steeringPrompt(supplement))
            .put("_eta_message_id", "user-$operationId-supplement-${++supplementIndex}")

    private fun executeToolBatch(
        round: Int,
        toolCalls: List<AgentModelClient.ToolCall>,
        stopReason: AssistantStopReason,
    ): List<ToolOutcome> {
        if (stopReason != AssistantStopReason.TOOL_USE) {
            return toolCalls.map { call ->
                when (stopReason) {
                    AssistantStopReason.OUTPUT_LIMIT -> rejectedToolOutcome(
                        round, call, "TRUNCATED_TOOL_CALL",
                        "模型输出达到长度上限，工具参数可能不完整；本次调用未执行，请重新提交完整参数。",
                    )
                    else -> rejectedToolOutcome(
                        round, call, "UNEXPECTED_TOOL_CALL",
                        "模型在 ${stopReason.name} 终止状态下返回了工具调用；本批调用未执行，请重新规划。",
                    )
                }
            }
        }

        val outcomes = mutableListOf<ToolOutcome>()
        var index = 0
        while (index < toolCalls.size) {
            val current = toolCalls[index]
            if (current.name == AgentSubagentToolCatalog.TOOL_NAME && current.name in availableToolNames) {
                val parallelBatch = mutableListOf<AgentModelClient.ToolCall>()
                while (
                    index < toolCalls.size &&
                    toolCalls[index].name == AgentSubagentToolCatalog.TOOL_NAME &&
                    toolCalls[index].name in availableToolNames
                ) {
                    parallelBatch += toolCalls[index].copy(round = round)
                    index += 1
                }
                if (parallelBatch.size == 1) {
                    outcomes += executeTool(round, parallelBatch.single())
                } else {
                    outcomes += executeParallelSubagents(round, parallelBatch)
                }
            } else {
                outcomes += executeTool(round, current.copy(round = round))
                index += 1
            }
        }
        return outcomes
    }

    private fun executeParallelSubagents(
        round: Int,
        calls: List<AgentModelClient.ToolCall>,
    ): List<ToolOutcome> {
        runController.throwIfCancelled()
        calls.forEach { toolCall ->
            synchronized(toolBatchLock) {
                onEvent(
                    AgentEvent.ToolStarted(
                        round = round,
                        toolCallId = toolCall.id,
                        name = toolCall.name,
                        argsPreview = traceFormatter.summarizeArguments(toolCall),
                        command = traceFormatter.displayCommand(toolCall),
                    ),
                )
            }
        }
        val outcomes = runBlocking(Dispatchers.IO) {
            supervisorScope {
                calls.map { toolCall ->
                    async {
                        val toolStartMs = monotonicNowMs()
                        val result = try {
                            toolExecutor.execute(toolCall)
                        } catch (throwable: Exception) {
                            if (runController.isCancelled) {
                                AgentModelClient.ToolResult(
                                    content = JSONObject()
                                        .put("ok", false)
                                        .put("code", "SUBAGENT_CANCELLED")
                                        .put("message", "已停止")
                                        .toString(),
                                )
                            } else {
                                AgentModelClient.ToolResult(
                                    content = JSONObject()
                                        .put("ok", false)
                                        .put("code", "TOOL_ERROR")
                                        .put("message", throwable.message ?: throwable.javaClass.simpleName)
                                        .toString(),
                                )
                            }
                        }
                        val elapsedMs = (monotonicNowMs() - toolStartMs).coerceAtLeast(0L)
                        synchronized(toolBatchLock) {
                            if (result.sensitive || AgentSensitiveToolPolicy.isSensitive(toolCall.name)) {
                                sensitiveToolCallIds += toolCall.id
                            }
                            if (!runController.isCancelled) {
                                turnMetrics.recordToolMs(elapsedMs)
                                emitToolFinished(round, toolCall, result)
                            }
                        }
                        ToolOutcome(toolCall, result)
                    }
                }.awaitAll()
            }
        }
        runController.throwIfCancelled()
        return outcomes
    }

    private fun executeTool(
        round: Int,
        toolCall: AgentModelClient.ToolCall,
    ): ToolOutcome {
        runController.throwIfCancelled()
        if (toolCall.name !in availableToolNames) {
            return rejectedToolOutcome(
                round = round,
                toolCall = toolCall,
                code = "TOOL_NOT_AVAILABLE",
                message = "工具未在本轮能力目录中提供，请使用当前可用工具",
            )
        }
        val roundCall = if (toolCall.round == round) toolCall else toolCall.copy(round = round)
        onEvent(
            AgentEvent.ToolStarted(
                round = round,
                toolCallId = roundCall.id,
                name = roundCall.name,
                argsPreview = traceFormatter.summarizeArguments(roundCall),
                command = traceFormatter.displayCommand(roundCall),
            )
        )

        val toolStartMs = monotonicNowMs()
        val result = try {
            toolExecutor.execute(roundCall)
        } catch (throwable: Exception) {
            runController.throwIfCancelled()
            AgentModelClient.ToolResult(
                content = JSONObject()
                    .put("ok", false)
                    .put("code", "TOOL_ERROR")
                    .put("message", throwable.message ?: throwable.javaClass.simpleName)
                    .toString(),
            )
        }
        turnMetrics.recordToolMs((monotonicNowMs() - toolStartMs).coerceAtLeast(0L))
        if (result.sensitive || AgentSensitiveToolPolicy.isSensitive(roundCall.name)) {
            sensitiveToolCallIds += roundCall.id
        }

        emitToolFinished(round, roundCall, result)
        return ToolOutcome(roundCall, result)
    }

    private fun rejectedToolOutcome(
        round: Int,
        toolCall: AgentModelClient.ToolCall,
        code: String,
        message: String,
    ): ToolOutcome {
        onEvent(
            AgentEvent.ToolStarted(
                round = round,
                toolCallId = toolCall.id,
                name = toolCall.name,
                argsPreview = traceFormatter.summarizeArguments(toolCall),
                command = traceFormatter.displayCommand(toolCall),
            )
        )
        val result = AgentModelClient.ToolResult(
            content = JSONObject()
                .put("ok", false)
                .put("code", code)
                .put("message", message)
                .toString(),
            sensitive = AgentSensitiveToolPolicy.isSensitive(toolCall.name),
        )
        if (result.sensitive) sensitiveToolCallIds += toolCall.id
        emitToolFinished(round, toolCall, result)
        return ToolOutcome(toolCall, result)
    }

    private fun emitToolFinished(
        round: Int,
        toolCall: AgentModelClient.ToolCall,
        result: AgentModelClient.ToolResult,
    ) {
        onEvent(
            AgentEvent.ToolFinished(
                round = round,
                toolCallId = toolCall.id,
                name = toolCall.name,
                resultSummary = traceFormatter.summarizeResult(toolCall.name, result),
                imageCount = result.images.size,
                imageBytes = result.images.sumOf { it.bytes },
                success = traceFormatter.isSuccessResult(result),
                command = result.command,
            )
        )
    }

    private fun appendToolImages(
        round: Int,
        outcomes: List<ToolOutcome>,
    ) {
        // 每个已完成结果立即落盘；图片观察仍统一放在完整工具批次之后。
        val imageOutcomes = outcomes.filter { outcome -> outcome.result.images.isNotEmpty() }
        if (imageOutcomes.isEmpty()) {
            return
        }

        // 工具截图是瞬时观察，不是会话资产。下一次思考消费后立即删除。
        discardPendingToolImageMessage()
        val images = imageOutcomes.flatMap { outcome -> outcome.result.images }
        val toolNames = imageOutcomes
            .map { outcome -> outcome.call.name }
            .distinct()
            .joinToString(", ")
        pendingToolImageMessage = AgentConversationCodec.userMessage(
            text = "Latest observation image(s) returned by tool(s): $toolNames.",
            images = images,
        ).put("_eta_observation", true).also(messages::put)

        imageOutcomes.forEach { outcome ->
            onEvent(
                AgentEvent.ToolImagesAttached(
                    round = round,
                    toolName = outcome.call.name,
                    imageCount = outcome.result.images.size,
                    imageBytes = outcome.result.images.sumOf { it.bytes },
                )
            )
        }
    }

    private fun discardPendingToolImageMessage() {
        val pending = pendingToolImageMessage ?: return
        pendingToolImageMessage = null
        for (index in messages.length() - 1 downTo 0) {
            if (messages.optJSONObject(index) === pending) {
                messages.remove(index)
                return
            }
        }
    }

    private fun ProviderEvent.toAgentEvent(round: Int): AgentEvent? =
        when (this) {
            ProviderEvent.RequestStarted -> AgentEvent.ProviderRequestStarted(round)
            is ProviderEvent.ResponseHeaders -> AgentEvent.ProviderResponseStarted(round, httpCode)
            is ProviderEvent.BlockStart -> AgentEvent.AssistantBlockStart(
                round = round,
                kind = kind.toRuntimeKind(),
                index = index,
                blockId = blockId,
                name = name,
            )
            is ProviderEvent.BlockDelta -> AgentEvent.AssistantBlockDelta(
                round = round,
                kind = kind.toRuntimeKind(),
                index = index,
                deltaChars = delta.length,
                delta = delta,
            )
            is ProviderEvent.BlockEnd -> AgentEvent.AssistantBlockEnd(
                round = round,
                kind = kind.toRuntimeKind(),
                index = index,
                blockId = blockId,
                name = name,
                contentChars = content.length,
                replacementContent = content.takeIf { replaceContent },
            )
            is ProviderEvent.Usage -> AgentEvent.UsageReceived(round = round, usage = usage)
            is ProviderEvent.HostedToolStarted -> AgentEvent.HostedToolStarted(
                round = round,
                toolCallId = id,
                name = name,
            )
            is ProviderEvent.HostedToolFinished -> AgentEvent.HostedToolFinished(
                round = round,
                toolCallId = id,
                name = name,
                success = success,
            )
            is ProviderEvent.SearchSuggestions -> AgentEvent.SearchSuggestions(round, index, html)
            is ProviderEvent.Completed -> null
        }

    private fun AssistantBlockKind.toRuntimeKind(): AgentEvent.AssistantBlockKind =
        when (this) {
            AssistantBlockKind.TEXT -> AgentEvent.AssistantBlockKind.TEXT
            AssistantBlockKind.THINKING -> AgentEvent.AssistantBlockKind.THINKING
            AssistantBlockKind.TOOL_CALL -> AgentEvent.AssistantBlockKind.TOOL_CALL
        }

    private fun monotonicNowMs(): Long = System.nanoTime() / 1_000_000L

    private class TurnMetricsAccumulator {
        private var stepCount: Int = 0
        private var llmMs: Long = 0L
        private var toolMs: Long = 0L
        private var ttftMs: Long = 0L
        private var ttftSteps: Int = 0
        private var decodeMs: Long = 0L
        private var decodeTokens: Int = 0
        private var contextTokens: Int? = null
        private var inputTokens: Int? = null
        private var outputTokens: Int? = null
        private var reasoningTokens: Int? = null
        private var cachedTokens: Int? = null
        private var cacheWriteTokens: Int? = null

        fun reset() {
            stepCount = 0
            llmMs = 0L
            toolMs = 0L
            ttftMs = 0L
            ttftSteps = 0
            decodeMs = 0L
            decodeTokens = 0
            contextTokens = null
            inputTokens = null
            outputTokens = null
            reasoningTokens = null
            cachedTokens = null
            cacheWriteTokens = null
        }

        fun recordStep(
            stepLlmMs: Long,
            stepTtftMs: Long?,
            stepDecodeMs: Long?,
            stepUsage: AgentTokenUsage?,
            stepPromptTokens: Int? = null,
        ): AgentTokenUsage {
            stepCount += 1
            llmMs += stepLlmMs.coerceAtLeast(0L)
            if (stepTtftMs != null) {
                ttftMs += stepTtftMs.coerceAtLeast(0L)
                ttftSteps += 1
            }
            val stepOut = stepUsage?.outputTokens
            if (stepDecodeMs != null && stepOut != null && stepOut > 0) {
                decodeMs += stepDecodeMs.coerceAtLeast(0L)
                decodeTokens += stepOut
            }
            stepUsage?.contextTokens?.let { contextTokens = it }
            (stepPromptTokens ?: stepUsage?.inputTokens)?.let { inputTokens = (inputTokens ?: 0) + it }
            stepUsage?.outputTokens?.let { outputTokens = (outputTokens ?: 0) + it }
            stepUsage?.reasoningTokens?.let { reasoningTokens = (reasoningTokens ?: 0) + it }
            stepUsage?.cachedTokens?.let { cachedTokens = (cachedTokens ?: 0) + it }
            stepUsage?.cacheWriteTokens?.let { cacheWriteTokens = (cacheWriteTokens ?: 0) + it }
            return AgentTokenUsage(
                contextTokens = contextTokens,
                inputTokens = inputTokens,
                outputTokens = outputTokens,
                reasoningTokens = reasoningTokens,
                cachedTokens = cachedTokens,
                cacheWriteTokens = cacheWriteTokens,
                requestKind = AgentTokenUsage.REQUEST_KIND_CHAT,
                stepCount = stepCount.takeIf { it > 0 },
                llmMs = llmMs.takeIf { stepCount > 0 },
                toolMs = toolMs.takeIf { it > 0L },
                ttftMs = ttftMs.takeIf { ttftSteps > 0 },
                ttftSteps = ttftSteps.takeIf { it > 0 },
                decodeMs = decodeMs.takeIf { decodeTokens > 0 && it > 0L },
                decodeTokens = decodeTokens.takeIf { decodeMs > 0L && it > 0 },
            )
        }

        fun recordToolMs(elapsedMs: Long) {
            toolMs += elapsedMs.coerceAtLeast(0L)
        }
    }
}
