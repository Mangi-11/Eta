package io.github.mangi.eta.agent.model

import io.github.mangi.eta.R
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.runtime.AgentTokenUsage
import io.github.mangi.eta.core.AppStrings
import java.io.InputStream
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.json.JSONArray
import org.json.JSONObject

/** 解析 Vertex AI `streamGenerateContent?alt=sse` 响应，完整保留同轮回放所需的 `thoughtSignature`。 */
internal class VertexGenerateContentStream(
    private val onEvent: (ProviderEvent) -> Unit,
) {
    private data class RecordedCall(
        val id: String,
        val name: String,
        val argumentsJson: String,
    )

    private data class TextBlock(
        val index: Int,
        val content: StringBuilder = StringBuilder(),
    )

    private val rawParts = mutableListOf<JSONObject>()
    private val textBlocks = mutableListOf<TextBlock>()
    private val thoughtChunks = mutableListOf<String>()
    private val calls = mutableListOf<RecordedCall>()
    private val upstreamCallIds = mutableSetOf<String>()
    private val callIdPrefix = "vertex-${UUID.randomUUID()}"

    private var nextBlockIndex = 0
    private var activeThinkingIndex: Int? = null
    private var activeThinkingBuilder = StringBuilder()
    private var activeTextBlock: TextBlock? = null

    private var finishReason: String? = null
    private var promptBlockReason: String? = null
    private var usage = AgentTokenUsage()
    private var receivedChars = 0L

    fun read(stream: InputStream, controller: AgentRunController): JSONObject {
        readProviderSse(stream, controller, dispatchPartialFrameAtEof = false, onEvent = ::accept)
        return finish()
    }

    private fun accept(@Suppress("UNUSED_PARAMETER") eventName: String, data: String): Boolean {
        val trimmed = data.trim()
        if (trimmed == "[DONE]") return false
        if (trimmed.isEmpty()) return true
        receivedChars += data.length
        if (data.length > MAX_EVENT_CHARS || receivedChars > MAX_STREAM_CHARS) malformed("GEMINI_STREAM_LIMIT")
        val chunk = parseObject(trimmed)

        chunk.optJSONObject("error")?.let { error ->
            throw AgentModelFailure.stream(
                error,
                AppStrings.get(R.string.provider_err_vertex_stream_failed, "Vertex AI Gemini 流式响应失败。"),
            )
        }
        chunk.optJSONObject("usageMetadata")?.let(::mergeUsage)
        chunk.optJSONObject("promptFeedback")?.optString("blockReason")?.takeIf(String::isNotBlank)?.let {
            promptBlockReason = it
        }

        val candidates = chunk.optJSONArray("candidates") ?: return true
        val candidate = candidates.optJSONObject(0) ?: return true

        candidate.optString("finishReason").takeIf(String::isNotBlank)?.let {
            finishReason = it
        }

        val parts = candidate.optJSONObject("content")?.optJSONArray("parts") ?: return true
        for (i in 0 until parts.length()) {
            val part = parts.optJSONObject(i) ?: malformed()
            processPart(part)
        }
        return true
    }

    private fun processPart(rawPart: JSONObject) {
        val part = JSONObject(rawPart.toString())
        val isThought = part.optBoolean("thought", false)
        val functionCall = part.optJSONObject("functionCall")
        if (part.has("functionCall") && functionCall == null) malformed("GEMINI_INVALID_TOOL_CALL")
        val hasText = part.has("text")
        val text = if (hasText) part.optString("text", "") else ""
        val hasSignature = part.has("thoughtSignature") && part.optString("thoughtSignature").isNotEmpty()

        recordRawPart(part, isThought = isThought, isFunctionCall = functionCall != null, hasSignature = hasSignature)

        when {
            functionCall != null -> {
                closeActiveThinking()
                closeActiveText()
                val name = functionCall.optString("name").takeIf(String::isNotBlank) ?: malformed()
                if (functionCall.has("partialArgs") || functionCall.optBoolean("willContinue") ||
                    functionCall.has("args") && functionCall.optJSONObject("args") == null ||
                    calls.isEmpty() && !hasSignature
                ) malformed("GEMINI_INVALID_TOOL_CALL")
                val argsJson = (functionCall.optJSONObject("args") ?: JSONObject()).toString()
                val upstreamId = functionCall.optString("id")
                if (upstreamId.isNotBlank() && !upstreamCallIds.add(upstreamId)) malformed("GEMINI_INVALID_TOOL_CALL")
                val id = "$callIdPrefix-${calls.size}"
                val blockIndex = nextBlockIndex++
                calls += RecordedCall(id = id, name = name, argumentsJson = argsJson)
                onEvent(ProviderEvent.BlockStart(AssistantBlockKind.TOOL_CALL, blockIndex, id, name))
                onEvent(ProviderEvent.BlockDelta(AssistantBlockKind.TOOL_CALL, blockIndex, argsJson))
                onEvent(
                    ProviderEvent.BlockEnd(
                        AssistantBlockKind.TOOL_CALL,
                        blockIndex,
                        blockId = id,
                        name = name,
                        content = argsJson,
                    ),
                )
            }

            isThought -> {
                if (text.isNotEmpty()) {
                    closeActiveText()
                    val index = activeThinkingIndex ?: (nextBlockIndex++).also {
                        activeThinkingIndex = it
                        activeThinkingBuilder = StringBuilder()
                        onEvent(ProviderEvent.BlockStart(AssistantBlockKind.THINKING, it))
                    }
                    activeThinkingBuilder.append(text)
                    onEvent(ProviderEvent.BlockDelta(AssistantBlockKind.THINKING, index, text))
                }
            }

            hasText -> {
                // 忽略末尾仅携带 thoughtSignature 或 finishReason 的空字符串块，避免展示空正文。
                if (text.isNotEmpty()) {
                    closeActiveThinking()
                    val block = activeTextBlock ?: TextBlock(nextBlockIndex++).also {
                        activeTextBlock = it
                        textBlocks += it
                        onEvent(ProviderEvent.BlockStart(AssistantBlockKind.TEXT, it.index))
                    }
                    block.content.append(text)
                    onEvent(ProviderEvent.BlockDelta(AssistantBlockKind.TEXT, block.index, text))
                }
            }
        }
    }

    private fun recordRawPart(
        part: JSONObject,
        isThought: Boolean,
        isFunctionCall: Boolean,
        hasSignature: Boolean,
    ) {
        if (rawParts.size >= MAX_PARTS) malformed("GEMINI_STREAM_LIMIT")
        // 仅合并相邻、同类型且均不含 thoughtSignature 的纯文本分片；含签名或工具调用的 part 始终原样独立保留。
        val previous = rawParts.lastOrNull()
        val canMergeText = previous != null &&
            !isFunctionCall &&
            !hasSignature &&
            part.has("text") &&
            previous.has("text") &&
            !previous.has("functionCall") &&
            !previous.has("thoughtSignature") &&
            previous.optBoolean("thought", false) == isThought
        if (canMergeText) {
            previous.put("text", previous.optString("text", "") + part.optString("text", ""))
        } else {
            rawParts += part
        }
    }

    private fun closeActiveThinking() {
        val index = activeThinkingIndex ?: return
        val content = activeThinkingBuilder.toString()
        if (content.isNotEmpty()) thoughtChunks += content
        onEvent(ProviderEvent.BlockEnd(AssistantBlockKind.THINKING, index, content = content))
        activeThinkingIndex = null
        activeThinkingBuilder = StringBuilder()
    }

    private fun closeActiveText() {
        val block = activeTextBlock ?: return
        onEvent(ProviderEvent.BlockEnd(AssistantBlockKind.TEXT, block.index, content = block.content.toString()))
        activeTextBlock = null
    }

    private fun finish(): JSONObject {
        closeActiveThinking()
        closeActiveText()
        if (!usage.isEmpty) onEvent(ProviderEvent.Usage(usage))

        val reasonFromServer = finishReason
        when {
            promptBlockReason != null || reasonFromServer in BLOCKED_FINISH_REASONS ->
                throw AgentModelFailure(
                    "CONTENT_FILTER",
                    false,
                    AppStrings.get(R.string.provider_err_vertex_blocked, "Vertex AI 拦截了本次请求或回复。"),
                )
            reasonFromServer == "MAX_TOKENS" || reasonFromServer == "CONTINUATION" ->
                throw AgentModelFailure(
                    "MODEL_OUTPUT_LIMIT",
                    false,
                    AppStrings.get(
                        R.string.provider_err_vertex_output_limit,
                        "Vertex AI 达到单次输出上限，回复未完成。请缩小任务范围或降低思考档位后重试。",
                    ),
                )
            reasonFromServer in INVALID_TOOL_FINISH_REASONS -> malformed("GEMINI_INVALID_TOOL_CALL")
            reasonFromServer == null || reasonFromServer == "FINISH_REASON_UNSPECIFIED" ->
                throw AgentModelFailure.incompleteStream(
                    AppStrings.get(R.string.provider_err_vertex_incomplete, "Vertex AI 响应提前结束，未收到完整终态。"),
                )
            reasonFromServer != "STOP" -> malformed()
        }

        val reason = if (calls.isNotEmpty()) "tool_calls" else "stop"
        val result = JSONObject()
            .put("role", "assistant")
            .put("content", textBlocks.joinToString("\n") { it.content.toString() })
            .put("finish_reason", reason)
        if (thoughtChunks.any(String::isNotEmpty)) {
            result.put("reasoning_content", thoughtChunks.joinToString("\n"))
        }
        if (calls.isNotEmpty()) {
            result.put(
                "tool_calls",
                JSONArray().also { target ->
                    calls.forEach { call ->
                        target.put(
                            JSONObject()
                                .put("id", call.id)
                                .put("type", "function")
                                .put(
                                    "function",
                                    JSONObject()
                                        .put("name", call.name)
                                        .put("arguments", call.argumentsJson),
                                ),
                        )
                    }
                },
            )
        }
        if (rawParts.isNotEmpty()) {
            val serializedParts = JSONArray().also { array -> rawParts.forEach(array::put) }
            GeminiEphemeralState.attachVertexParts(result, serializedParts)
        }
        return result
    }

    private fun mergeUsage(raw: JSONObject) {
        fun token(key: String, previous: Int?): Int? {
            if (!raw.has(key)) return previous
            val value = raw.opt(key) as? Number ?: return previous
            return value.toLong().takeIf { it in 0..Int.MAX_VALUE.toLong() && it.toDouble() == value.toDouble() }
                ?.toInt() ?: previous
        }
        val promptTokens = token("promptTokenCount", usage.inputTokens)
        val candidateTokens = token("candidatesTokenCount", null)
        val thoughtTokens = token("thoughtsTokenCount", usage.reasoningTokens)
        val combinedOutput = when {
            candidateTokens != null && thoughtTokens != null -> candidateTokens + thoughtTokens
            candidateTokens != null -> candidateTokens
            thoughtTokens != null -> thoughtTokens
            else -> usage.outputTokens
        }
        usage = AgentTokenUsage(
            contextTokens = token("totalTokenCount", usage.contextTokens),
            inputTokens = promptTokens,
            outputTokens = combinedOutput,
            reasoningTokens = thoughtTokens,
            cachedTokens = token("cachedContentTokenCount", usage.cachedTokens),
        )
    }

    private fun parseObject(value: String): JSONObject = try {
        val parsed = Json.parseToJsonElement(value)
        if (parsed !is JsonObject) malformed()
        JSONObject(parsed.toString())
    } catch (_: IllegalArgumentException) {
        malformed()
    } catch (_: org.json.JSONException) {
        malformed()
    }

    private fun malformed(code: String = "GEMINI_STREAM_INVALID"): Nothing =
        throw AgentModelFailure(
            code,
            false,
            when (code) {
                "GEMINI_INVALID_TOOL_CALL" -> AppStrings.get(
                    R.string.provider_err_vertex_invalid_tool_call,
                    "Vertex AI 返回的工具调用不完整或无效。",
                )
                else -> AppStrings.get(
                    R.string.provider_err_vertex_stream_invalid,
                    "Vertex AI 流式响应格式无效或超过容量限制。",
                )
            },
        )

    private companion object {
        const val MAX_EVENT_CHARS = 2 * 1024 * 1024
        const val MAX_STREAM_CHARS = 16 * 1024 * 1024
        const val MAX_PARTS = 4096
        val INVALID_TOOL_FINISH_REASONS = setOf("MALFORMED_FUNCTION_CALL", "UNEXPECTED_TOOL_CALL", "TOO_MANY_TOOL_CALLS")
        val BLOCKED_FINISH_REASONS = setOf("SAFETY", "RECITATION", "PROHIBITED_CONTENT", "BLOCKLIST", "SPII")
    }
}
