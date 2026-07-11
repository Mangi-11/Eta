package fuck.andes.agent.model

import fuck.andes.agent.runtime.AgentEvent
import fuck.andes.agent.runtime.AgentRunController
import org.json.JSONArray
import org.json.JSONObject

/**
 * 单次 Agent run 的纯编排循环。
 *
 * 轮次边界参考 pi-agent-core：一次 assistant 响应及其完整工具批次构成一个 turn；
 * steering 只在 turn 结束后注入，不能用取消网络或关闭工具资源来模拟。
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
    private val limits: Limits = Limits(),
) {
    data class Limits(
        val maxRounds: Int = 64,
        val maxToolCalls: Int = 256,
    ) {
        init {
            require(maxRounds > 0) { "maxRounds must be positive" }
            require(maxToolCalls > 0) { "maxToolCalls must be positive" }
        }
    }

    data class Result(
        val content: String,
        val reasoningContent: String,
    )

    private data class ToolOutcome(
        val call: AgentModelClient.ToolCall,
        val result: AgentModelClient.ToolResult,
    )

    private val toolCallValidator = AgentToolCallValidator(tools)
    private val accumulatedReasoning = StringBuilder()

    fun reasoningSnapshot(): String = accumulatedReasoning.toString().trim()

    fun run(): Result {
        var round = 1
        var seenToolCalls = 0

        while (true) {
            checkRoundLimit(round)
            runController.throwIfCancelled()
            appendPendingSteeringMessage()
            onEvent(AgentEvent.RoundStarted(round = round, messageCount = messages.length()))

            val reasoningLengthBeforeRound = accumulatedReasoning.length
            val providerResponse = provider.complete(
                request = ProviderRequest(
                    config = config,
                    messages = messages,
                    tools = tools,
                ),
                runController = runController,
            ) { providerEvent ->
                if (
                    providerEvent is ProviderEvent.BlockDelta &&
                    providerEvent.kind == AssistantBlockKind.THINKING
                ) {
                    accumulatedReasoning.append(providerEvent.delta)
                }
                providerEvent.toAgentEvent(round)?.let(onEvent)
            }

            runController.throwIfCancelled()
            val assistantMessage = providerResponse.assistantMessage
            val toolCalls = AgentConversationCodec.parseToolCalls(assistantMessage)
            val assistantReasoning = assistantMessage.optString("reasoning_content")
            if (
                assistantReasoning.isNotBlank() &&
                accumulatedReasoning.length == reasoningLengthBeforeRound
            ) {
                accumulatedReasoning.append(assistantReasoning)
            }

            messages.put(
                AgentConversationCodec.assistantHistoryMessage(
                    source = assistantMessage,
                    toolCalls = toolCalls,
                )
            )
            onEvent(
                AgentEvent.AssistantReceived(
                    round = round,
                    contentChars = assistantMessage.optString("content").length,
                    reasoningContent = assistantReasoning,
                    toolNames = toolCalls.map { it.name },
                )
            )

            if (toolCalls.isNotEmpty()) {
                if (seenToolCalls + toolCalls.size > limits.maxToolCalls) {
                    appendToolOutcomes(
                        round = round,
                        outcomes = toolCalls.map { call ->
                            rejectedToolOutcome(
                                round = round,
                                toolCall = call,
                                code = "TOOL_CALL_LIMIT_EXCEEDED",
                                message = "Agent 工具调用次数超过安全上限 ${limits.maxToolCalls}；本批调用未执行。",
                            )
                        },
                    )
                    error("Agent 工具调用次数超过安全上限 ${limits.maxToolCalls}")
                }
                if (round >= limits.maxRounds) {
                    appendToolOutcomes(
                        round = round,
                        outcomes = toolCalls.map { call ->
                            rejectedToolOutcome(
                                round = round,
                                toolCall = call,
                                code = "ROUND_LIMIT_EXCEEDED",
                                message = "Agent 已到达轮次上限 ${limits.maxRounds}；本批调用未执行。",
                            )
                        },
                    )
                    error("Agent 已到达轮次上限 ${limits.maxRounds}，为避免无法消费工具结果，本批工具未执行")
                }
                val outcomes = when (providerResponse.stopReason) {
                    AssistantStopReason.TOOL_USE ->
                        toolCalls.map { call -> executeTool(round, call) }
                    AssistantStopReason.OUTPUT_LIMIT ->
                        toolCalls.map { call ->
                            rejectedToolOutcome(
                                round = round,
                                toolCall = call,
                                code = "TRUNCATED_TOOL_CALL",
                                message = "模型输出达到长度上限，工具参数可能不完整；本次调用未执行，请重新提交完整参数。",
                            )
                        }
                    else ->
                        toolCalls.map { call ->
                            rejectedToolOutcome(
                                round = round,
                                toolCall = call,
                                code = "UNEXPECTED_TOOL_CALL",
                                message = "模型在 ${providerResponse.stopReason.name} 终止状态下返回了工具调用；" +
                                    "本批调用未执行，请重新规划。",
                            )
                        }
                }
                seenToolCalls += toolCalls.size
                appendToolOutcomes(round, outcomes)
                round += 1
                continue
            }

            // assistant 已自然结束时再检查 steering。这样补充消息不会丢掉刚完成的回答。
            if (appendPendingSteeringOrSeal()) {
                round += 1
                continue
            }

            val content = assistantMessage.optString("content").trim()
            if (content.isBlank() || content == "null") {
                val finishReason = assistantMessage.optString("finish_reason")
                error("模型接口第 $round 轮返回为空${finishReason.takeIf { it.isNotBlank() }?.let { "：$it" }.orEmpty()}")
            }

            onEvent(AgentEvent.RunFinished(round = round, contentChars = content.length))
            return Result(
                content = content,
                reasoningContent = reasoningSnapshot(),
            )
        }
    }

    private fun checkRoundLimit(round: Int) {
        if (round > limits.maxRounds) {
            error("Agent 轮次超过安全上限 ${limits.maxRounds}")
        }
    }

    private fun appendPendingSteeringMessage(): Boolean {
        val supplement = runController.pollSteeringMessage() ?: return false
        messages.put(AgentConversationCodec.userTextMessage(steeringPrompt(supplement)))
        return true
    }

    private fun appendPendingSteeringOrSeal(): Boolean {
        val supplement = runController.pollSteeringOrSeal() ?: return false
        messages.put(AgentConversationCodec.userTextMessage(steeringPrompt(supplement)))
        return true
    }

    private fun steeringPrompt(supplement: String): String =
        "用户补充指令：$supplement\n\n请基于当前任务上下文继续执行，不要从头重复已经完成或已经验证过的操作。"

    private fun executeTool(
        round: Int,
        toolCall: AgentModelClient.ToolCall,
    ): ToolOutcome {
        runController.throwIfCancelled()
        toolCallValidator.validate(toolCall)?.let { validationError ->
            return rejectedToolOutcome(
                round = round,
                toolCall = toolCall,
                code = "INVALID_TOOL_ARGUMENTS",
                message = validationError,
            )
        }
        onEvent(
            AgentEvent.ToolStarted(
                round = round,
                toolCallId = toolCall.id,
                name = toolCall.name,
                argsPreview = traceFormatter.summarizeArguments(toolCall),
            )
        )

        val result = try {
            toolExecutor.execute(toolCall)
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

        runController.throwIfCancelled()
        emitToolFinished(round, toolCall, result)
        return ToolOutcome(toolCall, result)
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
            )
        )
        val result = AgentModelClient.ToolResult(
            content = JSONObject()
                .put("ok", false)
                .put("code", code)
                .put("message", message)
                .toString(),
        )
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
            )
        )
    }

    private fun appendToolOutcomes(
        round: Int,
        outcomes: List<ToolOutcome>,
    ) {
        // Provider 要求同一 assistant 批次的全部 tool result 连续出现；图片观察放在批次之后。
        outcomes.forEach { outcome ->
            messages.put(AgentConversationCodec.toolResultMessage(outcome.call, outcome.result))
        }
        outcomes.forEach { outcome ->
            val images = outcome.result.images
            if (images.isEmpty()) return@forEach
            messages.put(
                AgentConversationCodec.userMessage(
                    text = "Observation image(s) returned by tool ${outcome.call.name}.",
                    images = images,
                )
            )
            onEvent(
                AgentEvent.ToolImagesAttached(
                    round = round,
                    toolName = outcome.call.name,
                    imageCount = images.size,
                    imageBytes = images.sumOf { it.bytes },
                )
            )
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
            )
            is ProviderEvent.Usage -> AgentEvent.UsageReceived(round = round, usage = usage)
            is ProviderEvent.Completed -> null
        }

    private fun AssistantBlockKind.toRuntimeKind(): AgentEvent.AssistantBlockKind =
        when (this) {
            AssistantBlockKind.TEXT -> AgentEvent.AssistantBlockKind.TEXT
            AssistantBlockKind.THINKING -> AgentEvent.AssistantBlockKind.THINKING
            AssistantBlockKind.TOOL_CALL -> AgentEvent.AssistantBlockKind.TOOL_CALL
        }

}
