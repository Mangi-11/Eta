package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.runtime.AgentTokenUsage
import io.github.mangi.eta.agent.runtime.SearchSuggestionsBudget
import io.github.mangi.eta.agent.runtime.SearchSuggestionsPayload
import java.io.InputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.json.JSONArray
import org.json.JSONObject

/** 解析 Vertex AI `streamGenerateContent?alt=sse` 响应，完整保留同轮回放所需的 `thoughtSignature`。 */
internal class VertexGenerateContentStream(
    private val onEvent: (ProviderEvent) -> Unit,
    private val pendingSuggestions: JSONArray = JSONArray(),
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

    private var nextBlockIndex = 0
    private var activeThinkingIndex: Int? = null
    private var activeThinkingBuilder = StringBuilder()
    private var activeTextBlock: TextBlock? = null

    private var finishReason: String? = null
    private var promptBlockReason: String? = null
    private var groundingMetadata: JSONObject? = null
    private var hostedSearchStarted = false
    private var usage = AgentTokenUsage()
    private var receivedChars = 0L
    private var receivedChunks = 0

    fun read(stream: InputStream, controller: AgentRunController): JSONObject {
        readProviderSse(stream, controller, ::accept)
        return finish()
    }

    private fun accept(@Suppress("UNUSED_PARAMETER") eventName: String, data: String): Boolean {
        val trimmed = data.trim()
        if (trimmed.isEmpty() || trimmed == "[DONE]") return true
        receivedChars += data.length
        if (data.length > MAX_EVENT_CHARS || receivedChars > MAX_STREAM_CHARS) malformed("GEMINI_STREAM_LIMIT")
        val chunk = parseObject(trimmed)
        receivedChunks += 1

        chunk.optJSONObject("error")?.let { error ->
            throw AgentModelFailure.stream(error, "Vertex AI Gemini 流式响应失败。")
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
        candidate.optJSONObject("groundingMetadata")?.let(::mergeGroundingMetadata)

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
        val hasText = part.has("text")
        val text = if (hasText) part.optString("text", "") else ""
        val hasSignature = part.has("thoughtSignature") && part.optString("thoughtSignature").isNotEmpty()

        recordRawPart(part, isThought = isThought, isFunctionCall = functionCall != null, hasSignature = hasSignature)

        when {
            functionCall != null -> {
                closeActiveThinking()
                closeActiveText()
                val name = functionCall.optString("name").takeIf(String::isNotBlank) ?: malformed()
                val args = functionCall.optJSONObject("args") ?: JSONObject()
                val argsJson = args.toString()
                val id = functionCall.optString("id").takeIf(String::isNotBlank)
                    ?: "vertex-call-${calls.size}"
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

    private fun mergeGroundingMetadata(incoming: JSONObject) {
        val merged = groundingMetadata?.let { JSONObject(it.toString()) } ?: JSONObject()
        for (key in incoming.keys()) {
            merged.put(key, incoming.get(key))
        }
        groundingMetadata = merged
        val hasQueries = (merged.optJSONArray("webSearchQueries")?.length() ?: 0) > 0
        val hasChunks = (merged.optJSONArray("groundingChunks")?.length() ?: 0) > 0
        if (!hostedSearchStarted && (hasQueries || hasChunks)) {
            hostedSearchStarted = true
            onEvent(ProviderEvent.HostedToolStarted(HOSTED_SEARCH_CALL_ID, "web_search"))
        }
    }

    private fun finish(): JSONObject {
        closeActiveThinking()
        closeActiveText()
        if (hostedSearchStarted) {
            onEvent(ProviderEvent.HostedToolFinished(HOSTED_SEARCH_CALL_ID, "web_search", true))
        }

        val finalTexts = applyGroundingCitations()
        if (!usage.isEmpty) onEvent(ProviderEvent.Usage(usage))

        val blockReason = promptBlockReason
        if (blockReason != null && finalTexts.all(String::isBlank) && calls.isEmpty()) {
            throw AgentModelFailure("CONTENT_FILTER", false, "Vertex AI 拦截了本次请求（$blockReason）。")
        }

        val normalizedFinish = finishReason?.uppercase()
        if (normalizedFinish == "MAX_TOKENS" && calls.isEmpty()) {
            emitSuggestions(JSONObject(), finalTexts)
            throw AgentModelFailure(
                "MODEL_OUTPUT_LIMIT",
                false,
                "Vertex AI 输出未完整生成，已达到输出上限；请调整输出上限或思考档位后重试。",
            )
        }
        if (normalizedFinish in BLOCKED_FINISH_REASONS && finalTexts.all(String::isBlank) && calls.isEmpty()) {
            throw AgentModelFailure("CONTENT_FILTER", false, "Vertex AI 终止了本次回复（$normalizedFinish）。")
        }
        if (receivedChunks == 0 || (normalizedFinish == null && calls.isEmpty() && finalTexts.all(String::isBlank))) {
            throw AgentModelFailure.incompleteStream("Vertex AI 响应提前结束，未收到完整终态。")
        }

        val reason = if (calls.isNotEmpty()) "tool_calls" else "stop"
        val result = JSONObject()
            .put("role", "assistant")
            .put("content", finalTexts.joinToString("\n"))
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
        emitSuggestions(result, finalTexts)
        return result
    }

    private fun applyGroundingCitations(): List<String> {
        val rawTexts = textBlocks.map { it.content.toString() }
        val metadata = groundingMetadata ?: return rawTexts
        val annotations = buildCitationAnnotations(metadata)
        if (annotations.length() == 0 || textBlocks.isEmpty()) return rawTexts
        val targetBlock = textBlocks.last()
        val cited = GeminiCitationFormatter.apply(targetBlock.content.toString(), annotations)
        if (cited != targetBlock.content.toString()) {
            onEvent(
                ProviderEvent.BlockEnd(
                    AssistantBlockKind.TEXT,
                    targetBlock.index,
                    content = cited,
                    replaceContent = true,
                ),
            )
        }
        return rawTexts.dropLast(1) + cited
    }

    private fun buildCitationAnnotations(metadata: JSONObject): JSONArray {
        val chunks = metadata.optJSONArray("groundingChunks") ?: return JSONArray()
        val supports = metadata.optJSONArray("groundingSupports") ?: return JSONArray()
        val annotations = JSONArray()
        for (i in 0 until supports.length()) {
            val support = supports.optJSONObject(i) ?: continue
            val segment = support.optJSONObject("segment") ?: continue
            val indices = support.optJSONArray("groundingChunkIndices") ?: continue
            val start = segment.optInt("startIndex", -1)
            val end = segment.optInt("endIndex", -1)
            for (j in 0 until indices.length()) {
                val chunkIndex = indices.optInt(j, -1)
                val web = chunks.optJSONObject(chunkIndex)?.optJSONObject("web") ?: continue
                val url = web.optString("uri").takeIf(String::isNotBlank) ?: continue
                val title = web.optString("title")
                val entry = JSONObject()
                    .put("type", "url_citation")
                    .put("url", url)
                    .put("title", title)
                if (start >= 0) entry.put("start_index", start)
                if (end >= 0) entry.put("end_index", end)
                annotations.put(entry)
            }
        }
        return annotations
    }

    private fun emitSuggestions(assistant: JSONObject, texts: List<String>) {
        val nonBlankBlockIndices = textBlocks.mapIndexedNotNull { idx, block ->
            block.index.takeIf { texts.getOrNull(idx)?.isNotBlank() == true }
        }
        val targetBlockIndex = nonBlankBlockIndices.lastOrNull()
        val budget = SearchSuggestionsBudget()
        val seen = mutableSetOf<String>()
        val deferred = JSONArray()
        fun suggest(html: String) {
            if (html.isBlank() || !seen.add(html)) return
            if (!SearchSuggestionsPayload.isValidHtml(html)) malformed("GEMINI_SEARCH_SUGGESTIONS_LIMIT")
            if (!budget.accept(0, targetBlockIndex ?: 0, html)) malformed("GEMINI_SEARCH_SUGGESTIONS_LIMIT")
            if (targetBlockIndex != null) onEvent(ProviderEvent.SearchSuggestions(targetBlockIndex, html))
            else deferred.put(html)
        }
        for (i in 0 until pendingSuggestions.length()) {
            suggest(pendingSuggestions.getString(i))
        }
        val rendered = groundingMetadata
            ?.optJSONObject("searchEntryPoint")
            ?.optString("renderedContent")
            .orEmpty()
        if (rendered.isNotBlank()) suggest(rendered)
        GeminiEphemeralState.attachPendingSuggestions(assistant, deferred)
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
                "GEMINI_SEARCH_SUGGESTIONS_LIMIT" -> "Vertex AI 搜索建议超过展示容量限制。"
                else -> "Vertex AI 流式响应格式无效或超过容量限制。"
            },
        )

    private companion object {
        const val HOSTED_SEARCH_CALL_ID = "vertex-google-search"
        const val MAX_EVENT_CHARS = 2 * 1024 * 1024
        const val MAX_STREAM_CHARS = 16 * 1024 * 1024
        const val MAX_PARTS = 4096
        val BLOCKED_FINISH_REASONS = setOf("SAFETY", "RECITATION", "PROHIBITED_CONTENT", "BLOCKLIST", "SPII")
    }
}
