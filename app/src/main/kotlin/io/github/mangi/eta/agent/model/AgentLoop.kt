package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.runtime.AgentRunCancelledException
import io.github.mangi.eta.agent.roleplay.RoleplayRunContext
import java.util.concurrent.CancellationException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
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
    private val recoverVirtualUi: ((AgentModelClient.ToolStop) -> AgentModelClient.ToolResult)? = null,
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

    private var toolCallValidator = AgentToolCallValidator(tools)
    private val accumulatedReasoning = StringBuilder()
    private val sensitiveToolCallIds = linkedSetOf<String>()
    private var pendingToolImageMessage: JSONObject? = null
    private val context = AgentContextSession(
        config, messages, systemCount, operationId, provider, runController,
        { sensitiveToolCallIds }, onEvent, onContextSnapshot, { transcript.length() },
        roleplay = roleplayContext != null,
    )
    private var supplementIndex = initialSupplementIndex
    private var virtualUiRecoveryAttempted = false

    fun contextSnapshot(): AgentContextSnapshot? = context.snapshot()

    private fun appendMessage(message: JSONObject) {
        messages.put(message)
        transcript.put(message)
    }

    private var publishedTranscriptSize = 0
    private val batchCheckpoints = linkedMapOf<String, JSONObject>()

    fun transcriptSnapshot(): List<AgentModelClient.ConversationMessage> =
        AgentConversationCodec.transcript(transcript, 0, sensitiveToolCallIds) +
            batchCheckpoints.map { (id, checkpoint) ->
                AgentModelClient.ConversationMessage(role = "tool", toolCallId = id, content = checkpoint.toString())
            }

    private fun publishTranscript(force: Boolean = false) {
        if (!force && publishedTranscriptSize == transcript.length()) return
        onTranscript(transcriptSnapshot())
        publishedTranscriptSize = transcript.length()
    }

    fun compactOnly(): Result {
        context.compact(force = true)
        return Result("", "", emptySet())
    }

    fun reasoningSnapshot(): String = accumulatedReasoning.toString().trim()

    fun run(): Result {
        var round = 1
        var precedingTools: JSONArray? = null

        while (true) {
            runController.throwIfCancelled()
            if (purpose.allowsTools) appendPendingSteeringMessage()

            // Anthropic 思考签名绑定发出工具调用时的 system 与 tools；工具结果回传后再刷新目录。
            val roundTools = if (AnthropicEphemeralState.hasPendingToolResponse(messages)) {
                precedingTools ?: tools
            } else if (purpose.allowsTools) {
                toolsForRound?.invoke() ?: tools
            } else {
                JSONArray()
            }
            precedingTools = roundTools
            toolCallValidator = AgentToolCallValidator(roundTools)
            publishTranscript()
            if (purpose.allowsTools) context.compact()
            val requestMessages = AssistantScreenContextProjection.project(
                roleplayContext?.projectMessages(messages) ?: messages,
            )
            var roundInputTokens: Int? = null
            val reasoningLengthBeforeRound = accumulatedReasoning.length
            val completedRound = try {
                modelRetry.complete(
                    initialRound = round,
                    request = ProviderRequest(config, requestMessages, roundTools, sessionId, purpose),
                    provider = provider,
                    controller = runController,
                    onEvent = { event ->
                        if (event is AgentEvent.RoundStarted) roundInputTokens = null
                        onEvent(event)
                    },
                    onProviderEvent = { attemptRound, providerEvent ->
                        if (!purpose.allowsTools && (providerEvent is ProviderEvent.HostedToolStarted ||
                                providerEvent is ProviderEvent.BlockStart && providerEvent.kind == AssistantBlockKind.TOOL_CALL)) {
                            throw AgentModelFailure("REPLY_REWRITE_TOOL_CALL", false, "改写回复时模型请求了工具，已停止；原回复未改变。")
                        }
                        if (providerEvent is ProviderEvent.Usage) {
                            roundInputTokens = providerEvent.contextInputTokens ?: roundInputTokens
                        }
                        if (providerEvent is ProviderEvent.BlockDelta &&
                            providerEvent.kind == AssistantBlockKind.THINKING
                        ) {
                            accumulatedReasoning.append(providerEvent.delta)
                        }
                        providerEvent.toAgentEvent(attemptRound)?.let(onEvent)
                    },
                    discardAttemptReasoning = { accumulatedReasoning.setLength(reasoningLengthBeforeRound) },
                ).also { response ->
                    if (purpose == ProviderRequestPurpose.CHAT) validateChatResponse(response.response)
                }
            } finally {
                // 同一回合的重试仍需原始观察；整个回合结束后才移除截图。
                discardPendingToolImageMessage()
            }
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

            if (toolCalls.isNotEmpty()) {
                val (outcomes, stop) = executeToolBatch(round, toolCalls, providerResponse.stopReason)
                appendToolImages(round, outcomes)
                publishTranscript()
                stop?.let { paused ->
                    if (!paused.isVirtualUiPause) throw AgentModelFailure(paused.code, false, paused.message)
                    if (recoverVirtualUi == null || virtualUiRecoveryAttempted) {
                        throw AgentUiExecutionPausedException(paused.copy(message =
                            if (virtualUiRecoveryAttempted) "已尝试重启虚拟屏中的应用，但操作仍未恢复，任务已暂停并保留现场。请检查应用后继续；此前动作的效果仍需核对。"
                            else paused.message))
                    }
                    virtualUiRecoveryAttempted = true
                    round += 1
                    recoverVirtualUi(round, paused)
                }
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

    private fun recoverVirtualUi(round: Int, stop: AgentModelClient.ToolStop) {
        val restart = AgentModelClient.ToolCall("$operationId-ui-restart-$round", "virtual_screen",
            JSONObject().put("action", "restart").toString())
        val observe = AgentModelClient.ToolCall("$operationId-ui-observe-$round", "observe_screen",
            JSONObject().put("include_screenshot", true).put("include_ui_tree", true).toString())
        appendMessage(AgentConversationCodec.assistantHistoryMessage(
            JSONObject().put("content", "虚拟屏应用操作未恢复，先尝试重启应用并重新观察。"),
            listOf(restart, observe),
        ).put("_eta_message_id", "assistant-$operationId-$round"))
        onEvent(AgentEvent.AssistantReceived(round, 0, "", listOf(restart.name, observe.name)))
        onEvent(AgentEvent.ToolStarted(round, restart.id, restart.name,
            traceFormatter.summarizeArguments(restart), traceFormatter.displayCommand(restart)))
        runController.throwIfCancelled()
        val restarted = try {
            requireNotNull(recoverVirtualUi).invoke(stop)
        } catch (_: Exception) {
            runController.throwIfCancelled()
            AgentModelClient.ToolResult(JSONObject().put("ok", false).put("code", "UI_RESTART_FAILED").toString())
        }
        sensitiveToolCallIds += restart.id
        emitToolFinished(round, restart, restarted)
        appendMessage(AgentConversationCodec.toolResultMessage(restart, restarted))
        publishTranscript()
        val recoverySucceeded = restarted.stop == null &&
            runCatching { JSONObject(restarted.content).optBoolean("ok") }.getOrDefault(false)
        val observation = if (recoverySucceeded) executeTool(round, observe) else rejectedToolOutcome(
            round, observe, "UI_EXECUTION_PAUSED", "应用重启未完成，任务已暂停并保留现场。",
        )
        appendMessage(AgentConversationCodec.toolResultMessage(observation.call, observation.result))
        appendToolImages(round, listOf(observation))
        publishTranscript()
        if (!recoverySucceeded || observation.result.stop != null ||
            !runCatching { JSONObject(observation.result.content).optBoolean("ok") }.getOrDefault(false)) {
            throw AgentUiExecutionPausedException(stop.copy(message =
                if (recoverySucceeded) "已重启虚拟屏中的应用，但重新观察未成功，任务已暂停并保留现场。请检查应用后继续。"
                else "已尝试重启虚拟屏中的应用，但重启未完成，任务已暂停并保留现场。请检查应用后继续。"))
        }
    }

    private fun validateChatResponse(response: ProviderResponse) {
        val message = response.assistantMessage
        if (AgentConversationCodec.parseToolCalls(message).isNotEmpty()) return
        val content = message.optString("content").trim()
        if (content.isNotBlank() && content != "null") return
        throw when (response.stopReason) {
            AssistantStopReason.OUTPUT_LIMIT ->
                AgentModelFailure("MODEL_OUTPUT_LIMIT", false, "模型输出额度已耗尽但未生成正文，请检查输出上限或降低思考强度。")
            AssistantStopReason.CONTENT_FILTER ->
                AgentModelFailure("MODEL_CONTENT_FILTER", false, "模型回复被服务商过滤，未返回正文。")
            else -> AgentModelFailure("MODEL_EMPTY_RESPONSE", false, "模型未返回正文或工具调用，请检查服务商状态。")
        }
    }

    private fun appendPendingSteeringMessage(): Boolean {
        val supplement = runController.pollSteeringMessage() ?: return false
        appendMessage(steeringMessage(supplement))
        context.userAppended()
        return true
    }

    private fun appendPendingSteeringOrSeal(): Boolean {
        val supplement = runController.pollSteeringOrSeal() ?: return false
        appendMessage(steeringMessage(supplement))
        context.userAppended()
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
    ): Pair<List<ToolOutcome>, AgentModelClient.ToolStop?> {
        fun publish(outcome: ToolOutcome) {
            val message = AgentConversationCodec.toolResultMessage(outcome.call, outcome.result)
            batchCheckpoints.remove(outcome.call.id)?.let { checkpoint ->
                message.put("_eta_batch_checkpoint", checkpoint)
            }
            appendMessage(message)
            publishTranscript()
        }
        if (stopReason == AssistantStopReason.TOOL_USE) {
            return executeOrderedTools(round, toolCalls, onOutcome = ::publish)
        }
        val outcomes = toolCalls.map { call ->
            val outcome = if (stopReason == AssistantStopReason.OUTPUT_LIMIT) {
                rejectedToolOutcome(round, call, "TRUNCATED_TOOL_CALL",
                    "模型输出达到长度上限，工具参数可能不完整；本次调用未执行，请重新提交完整参数。")
            } else {
                rejectedToolOutcome(round, call, "UNEXPECTED_TOOL_CALL",
                    "模型在 ${stopReason.name} 终止状态下返回了工具调用；本批调用未执行，请重新规划。")
            }
            publish(outcome)
            outcome
        }
        return outcomes to null
    }

    private fun executeOrderedTools(
        round: Int,
        toolCalls: List<AgentModelClient.ToolCall>,
        parallel: Boolean = true,
        stopOnError: Boolean = false,
        onBeforeCall: (AgentModelClient.ToolCall) -> Unit = {},
        onOutcome: (ToolOutcome) -> Unit = {},
    ): Pair<List<ToolOutcome>, AgentModelClient.ToolStop?> {
        val outcomes = mutableListOf<ToolOutcome>()
        var stop: AgentModelClient.ToolStop? = null
        var failed = false
        fun record(outcome: ToolOutcome) {
            outcomes += outcome
            if (stop == null) stop = outcome.result.stop
            if (!traceFormatter.isSuccessResult(outcome.result)) failed = true
            onOutcome(outcome)
        }
        val segments = if (parallel) AgentToolBatchPlanner.plan(toolCalls) else
            listOf(AgentToolBatchPlanner.Segment(parallel = false, calls = toolCalls))
        for (segment in segments) {
            // Bounded waves prevent queued work from starting beyond a pause/cancellation.
            for (wave in segment.calls.chunked(AgentToolBatchPlanner.MAX_CONCURRENT_CALLS)) {
                if (segment.parallel && wave.size > 1 && stop == null && !stopOnError) {
                    executeParallelTools(round, wave, onBeforeCall, ::record)
                } else {
                    for (call in wave) {
                        runController.throwIfCancelled()
                        val outcome = when {
                            stop != null -> rejectedToolOutcome(round, call, "UI_EXECUTION_PAUSED",
                                "本批次已因虚拟屏无响应或无进展暂停；后续工具未执行。")
                            stopOnError && failed -> rejectedToolOutcome(round, call, "BATCH_STOPPED_ON_ERROR",
                                "本批次前序工具失败；该项未执行。请核实失败原因和已完成步骤后重新规划。")
                            else -> {
                                onBeforeCall(call)
                                executeTool(round, call)
                            }
                        }
                        record(outcome)
                    }
                }
            }
        }
        return outcomes to stop
    }

    private fun executeParallelTools(
        round: Int,
        toolCalls: List<AgentModelClient.ToolCall>,
        onBeforeCall: (AgentModelClient.ToolCall) -> Unit,
        onOutcome: (ToolOutcome) -> Unit,
    ) {
        runController.throwIfCancelled()
        val executor = Executors.newFixedThreadPool(toolCalls.size) { runnable ->
            Thread(runnable, "eta-tool-batch").apply { isDaemon = true }
        }
        val futures = CopyOnWriteArrayList<Future<AgentModelClient.ToolResult>>()
        val binding = runController.register {
            futures.forEach { it.cancel(true) }
            executor.shutdownNow()
        }
        try {
            // Validation, events, transcript and sensitive IDs stay on the loop thread.
            // Workers only execute the existing routed tool handler.
            val jobs = toolCalls.map { call ->
                runController.throwIfCancelled()
                val validation = toolCallValidator.validate(call)
                if (validation != null) {
                    null to rejectedToolOutcome(round, call, "INVALID_TOOL_ARGUMENTS", validation)
                } else {
                    onBeforeCall(call)
                    emitToolStarted(round, call)
                    val future = executor.submit<AgentModelClient.ToolResult> { invokeTool(call) }
                    futures += future
                    runController.throwIfCancelled()
                    future to null
                }
            }
            jobs.forEachIndexed { index, (future, rejected) ->
                runController.throwIfCancelled()
                val outcome = rejected ?: finishTool(round, toolCalls[index], requireNotNull(future).get())
                onOutcome(outcome)
            }
        } catch (failure: ExecutionException) {
            runController.throwIfCancelled()
            throw failure.cause ?: failure
        } catch (failure: CancellationException) {
            runController.throwIfCancelled()
            throw failure
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw AgentRunCancelledException()
        } catch (failure: Exception) {
            runController.throwIfCancelled()
            throw failure
        } finally {
            futures.forEach { it.cancel(true) }
            executor.shutdownNow()
            binding.close()
        }
    }

    private fun executeTool(
        round: Int,
        toolCall: AgentModelClient.ToolCall,
    ): ToolOutcome {
        runController.throwIfCancelled()
        toolCallValidator.validate(toolCall)?.let { validationError ->
            return rejectedToolOutcome(round, toolCall, "INVALID_TOOL_ARGUMENTS", validationError)
        }
        if (toolCall.name == AgentBatchToolCatalog.NAME) {
            val arguments = JSONObject(toolCall.argumentsJson)
            val calls = AgentBatchToolCatalog.calls(toolCall)
            val sequential = arguments.optString("mode", "auto") == "sequential"
            val stopOnError = arguments.optString("on_error", if (sequential) "stop" else "continue") == "stop"
            // Preflight the whole wrapper before executing any subcall.  Sequential mode
            // may use every tool that survived the current capability/restriction filter;
            // auto mode remains limited to the read-only set that can be safely parallelized.
            calls.forEachIndexed { index, call ->
                val validation = when {
                    call.name == AgentBatchToolCatalog.NAME -> "不能在 batch 中嵌套 batch"
                    !sequential && call.name !in AgentToolBatchPlanner.batchTools ->
                        "auto 模式只支持只读查询；需要执行此工具时请设置 mode=sequential"
                    else -> toolCallValidator.validate(call)
                }
                if (validation != null) return rejectedToolOutcome(round, toolCall,
                    "INVALID_TOOL_ARGUMENTS", "calls[$index]: $validation")
            }
            emitToolStarted(round, toolCall)
            // Publish only names, indices and execution states.  Tool arguments and raw
            // results remain sensitive, but a resumed run can avoid repeating completed
            // mutations and can distinguish an in-flight call from untouched later steps.
            val progress = JSONArray(calls.mapIndexed { index, call ->
                JSONObject().put("index", index).put("tool", call.name).put("status", "not_started")
            })
            val checkpoint = JSONObject().put("ok", false).put("code", "TOOL_INTERRUPTED")
                .put("total", calls.size).put("results", progress)
                .put("message", "批次未完成；completed 项已执行、failed 项已返回失败、skipped/not_started 项未执行，unknown 项效果未知。先核实未知效果，不要重放已完成动作。原始参数与结果未写入持久会话。")
            batchCheckpoints[toolCall.id] = checkpoint
            publishTranscript(force = true)
            fun updateProgress(call: AgentModelClient.ToolCall, status: String) {
                progress.getJSONObject(calls.indexOfFirst { it.id == call.id }).put("status", status)
                publishTranscript(force = true)
            }
            val (outcomes, stop) = executeOrderedTools(round, calls,
                parallel = !sequential && !stopOnError,
                stopOnError = stopOnError,
                onBeforeCall = { call -> updateProgress(call, "unknown") },
                onOutcome = { outcome ->
                    val wasStarted = progress.getJSONObject(calls.indexOfFirst { it.id == outcome.call.id })
                        .getString("status") == "unknown"
                    val status = when {
                        !wasStarted -> "skipped"
                        outcome.result.stop != null || !traceFormatter.isSuccessResult(outcome.result) -> "failed"
                        else -> "completed"
                    }
                    updateProgress(outcome.call, status)
                },
            )
            checkpoint.remove("code")
            checkpoint.put("ok", stop == null && outcomes.all { traceFormatter.isSuccessResult(it.result) })
                .put("message", "以下仅保留每项执行状态；completed 项已执行，不要重复。原始参数与结果仅供当前回合使用，未写入持久会话。")
            val results = JSONArray()
            outcomes.forEachIndexed { index, outcome ->
                results.put(JSONObject()
                    .put("index", index)
                    .put("tool", outcome.call.name)
                    .put("ok", traceFormatter.isSuccessResult(outcome.result))
                    .put("result", runCatching { JSONObject(outcome.result.content) }
                        .getOrNull() ?: outcome.result.content))
            }
            return finishTool(round, toolCall, AgentModelClient.ToolResult(
                content = JSONObject()
                    .put("ok", stop == null && outcomes.all { traceFormatter.isSuccessResult(it.result) })
                    .put("total", outcomes.size)
                    .put("results", results)
                    .toString(),
                sensitive = true,
                images = outcomes.flatMap { it.result.images },
                stop = stop,
            ))
        }
        emitToolStarted(round, toolCall)
        return finishTool(round, toolCall, invokeTool(toolCall))
    }

    private fun invokeTool(toolCall: AgentModelClient.ToolCall): AgentModelClient.ToolResult {
        runController.throwIfCancelled()
        return try {
            toolExecutor.execute(toolCall)
        } catch (cancelled: AgentRunCancelledException) {
            throw cancelled
        } catch (throwable: Exception) {
            runController.throwIfCancelled()
            AgentModelClient.ToolResult(JSONObject()
                .put("ok", false)
                .put("code", "TOOL_ERROR")
                .put("message", throwable.message ?: throwable.javaClass.simpleName)
                .toString())
        }
    }

    private fun finishTool(
        round: Int,
        toolCall: AgentModelClient.ToolCall,
        result: AgentModelClient.ToolResult,
    ): ToolOutcome {
        if (result.sensitive || AgentSensitiveToolPolicy.isSensitive(toolCall.name)) {
            sensitiveToolCallIds += toolCall.id
        }
        emitToolFinished(round, toolCall, result)
        return ToolOutcome(toolCall, result)
    }

    private fun emitToolStarted(round: Int, toolCall: AgentModelClient.ToolCall) {
        onEvent(AgentEvent.ToolStarted(round, toolCall.id, toolCall.name,
            traceFormatter.summarizeArguments(toolCall), traceFormatter.displayCommand(toolCall)))
    }

    private fun rejectedToolOutcome(
        round: Int,
        toolCall: AgentModelClient.ToolCall,
        code: String,
        message: String,
    ): ToolOutcome {
        emitToolStarted(round, toolCall)
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
            is ProviderEvent.Completed -> null
        }

    private fun AssistantBlockKind.toRuntimeKind(): AgentEvent.AssistantBlockKind =
        when (this) {
            AssistantBlockKind.TEXT -> AgentEvent.AssistantBlockKind.TEXT
            AssistantBlockKind.THINKING -> AgentEvent.AssistantBlockKind.THINKING
            AssistantBlockKind.TOOL_CALL -> AgentEvent.AssistantBlockKind.TOOL_CALL
        }

}
