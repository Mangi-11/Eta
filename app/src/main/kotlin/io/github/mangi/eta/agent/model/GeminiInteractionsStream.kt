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

/** 按 step index 构建完整回放步骤，完成事件只决定终态，不能代替流内累积。 */
internal class GeminiInteractionsStream(
    private val onEvent: (ProviderEvent) -> Unit,
    private val pendingSuggestions: JSONArray = JSONArray(),
) {
    private data class StepState(
        val index: Int,
        val step: JSONObject,
        val arguments: StringBuilder = StringBuilder(),
        var hasArgumentDelta: Boolean = false,
        var stopped: Boolean = false,
    ) {
        val type: String get() = step.getString("type")
        val kind: AssistantBlockKind? get() = when (type) {
            "model_output" -> AssistantBlockKind.TEXT
            "thought" -> AssistantBlockKind.THINKING
            "function_call" -> AssistantBlockKind.TOOL_CALL
            else -> null
        }
    }

    private val steps = sortedMapOf<Int, StepState>()
    private val searchCalls = mutableSetOf<String>()
    private val searchResults = mutableSetOf<String>()
    private var usage = AgentTokenUsage()
    private var assistant: JSONObject? = null
    private var receivedChars = 0L

    fun read(stream: InputStream, controller: AgentRunController): JSONObject {
        readProviderSse(stream, controller, onEvent = ::accept)
        return assistant ?: throw AgentModelFailure.incompleteStream("Gemini 响应提前结束，未收到完整终态。")
    }

    private fun accept(eventName: String, data: String): Boolean {
        if (data.trim() == "[DONE]") {
            throw AgentModelFailure.incompleteStream("Gemini 响应缺少完成事件。")
        }
        receivedChars += data.length
        if (data.length > MAX_EVENT_CHARS || receivedChars > MAX_STREAM_CHARS) malformed("GEMINI_STREAM_LIMIT")
        val event = parseObject(data)
        val type = string(event, "event_type")
        if (eventName.isNotBlank() && eventName != type) malformed()
        event.optJSONObject("metadata")?.optJSONObject("total_usage")?.let(::mergeUsage)
        when (type) {
            "step.start" -> start(index(event), event.optJSONObject("step") ?: malformed())
            "step.delta" -> delta(index(event), event.optJSONObject("delta") ?: malformed())
            "step.stop" -> {
                stop(index(event))
                event.optJSONObject("usage")?.let(::mergeUsage)
            }
            "interaction.created" -> {
                val interaction = event.optJSONObject("interaction") ?: malformed()
                interaction.optJSONObject("usage")?.let(::mergeUsage)
            }
            "interaction.status_update" -> {
                string(event, "status")
                event.optJSONObject("usage")?.let(::mergeUsage)
            }
            "interaction.completed" -> {
                finish(event.optJSONObject("interaction") ?: malformed())
                return false
            }
            "error" -> throw AgentModelFailure.stream(
                event.optJSONObject("error") ?: JSONObject(), "Gemini 流式响应失败。",
            )
            // 新增事件可以忽略；已知步骤里的未知输出必须明确失败。
        }
        return true
    }

    private fun start(index: Int, raw: JSONObject) {
        if (index in steps || steps.size >= MAX_STEPS) malformed()
        val step = JSONObject(raw.toString())
        val type = string(step, "type")
        if (type !in setOf("model_output", "thought", "function_call", "google_search_call", "google_search_result")) {
            malformed("GEMINI_UNSUPPORTED_OUTPUT")
        }
        validateSignature(step)
        val state = StepState(index, step)
        steps[index] = state
        when (type) {
            "model_output", "thought" -> {
                val field = if (type == "thought") "summary" else "content"
                val items = step.optJSONArray(field) ?: JSONArray().also { step.put(field, it) }
                validateTextContents(items)
                onEvent(ProviderEvent.BlockStart(state.kind!!, index))
                val initial = plainText(items)
                if (initial.isNotEmpty()) onEvent(ProviderEvent.BlockDelta(state.kind!!, index, initial))
            }
            "function_call" -> {
                val id = string(step, "id")
                val name = string(step, "name")
                if (steps.values.any { it !== state && it.type == type && it.step.optString("id") == id }) malformed()
                if (step.has("arguments") && step.optJSONObject("arguments") == null) malformed()
                onEvent(ProviderEvent.BlockStart(AssistantBlockKind.TOOL_CALL, index, id, name))
            }
            "google_search_call" -> {
                val id = string(step, "id")
                if (step.has("arguments") && step.optJSONObject("arguments") == null) malformed()
                if (!searchCalls.add(id)) malformed()
                onEvent(ProviderEvent.HostedToolStarted(id, "web_search"))
            }
            "google_search_result" -> {
                val id = string(step, "call_id")
                if (id !in searchCalls || !searchResults.add(id)) malformed()
                validateSearchResults(step)
            }
        }
    }

    private fun delta(index: Int, delta: JSONObject) {
        val state = steps[index]?.takeUnless { it.stopped } ?: malformed()
        when (string(delta, "type")) {
            "text" -> {
                requireType(state, "model_output")
                val value = string(delta, "text", allowEmpty = true)
                val part = lastText(state.step, "content")
                part.put("text", part.getString("text") + value)
                onEvent(ProviderEvent.BlockDelta(AssistantBlockKind.TEXT, index, value))
            }
            "text_annotation_delta" -> {
                requireType(state, "model_output")
                val incoming = delta.optJSONArray("annotations") ?: malformed()
                val part = lastText(state.step, "content")
                val annotations = part.optJSONArray("annotations") ?: JSONArray().also { part.put("annotations", it) }
                for (item in 0 until incoming.length()) {
                    annotations.put(incoming.optJSONObject(item) ?: malformed())
                }
            }
            "thought_summary" -> {
                requireType(state, "thought")
                val item = delta.optJSONObject("content") ?: malformed()
                val items = JSONArray().put(item)
                validateTextContents(items)
                state.step.getJSONArray("summary").put(JSONObject(item.toString()))
                onEvent(ProviderEvent.BlockDelta(AssistantBlockKind.THINKING, index, item.getString("text")))
            }
            "thought_signature" -> {
                requireType(state, "thought")
                // 签名是完整的不透明值，后来的签名替换之前值，不能拼接或解码再编码。
                state.step.put("signature", string(delta, "signature", allowEmpty = true))
            }
            "arguments_delta" -> {
                requireType(state, "function_call")
                val value = string(delta, "arguments", allowEmpty = true)
                state.hasArgumentDelta = true
                state.arguments.append(value)
                if (state.arguments.length > MAX_ARGUMENT_CHARS) malformed("GEMINI_ARGUMENT_LIMIT")
                onEvent(ProviderEvent.BlockDelta(AssistantBlockKind.TOOL_CALL, index, value))
            }
            "google_search_call", "google_search_result" -> {
                requireType(state, delta.getString("type"))
                validateSignature(delta)
                val fields = if (state.type == "google_search_call") setOf("arguments", "signature")
                    else setOf("result", "is_error", "signature")
                for (key in delta.keys()) {
                    if (key !in fields) continue
                    if (key == "arguments" && delta.optJSONObject(key) == null) malformed()
                    if (key == "is_error" && delta.opt(key) !is Boolean) malformed()
                    if (key == "result") {
                        val incoming = delta.optJSONArray(key) ?: malformed()
                        val target = state.step.optJSONArray(key) ?: JSONArray().also { state.step.put(key, it) }
                        for (item in 0 until incoming.length()) target.put(incoming.get(item))
                    } else {
                        state.step.put(key, delta.get(key))
                    }
                }
                if (state.type == "google_search_result") validateSearchResults(state.step)
            }
            else -> malformed("GEMINI_UNSUPPORTED_OUTPUT")
        }
    }

    private fun stop(index: Int) {
        val state = steps[index]?.takeUnless { it.stopped } ?: malformed()
        state.stopped = true
        if (state.type == "function_call") {
            val args = if (state.hasArgumentDelta) parseObject(state.arguments.toString())
                else state.step.optJSONObject("arguments") ?: malformed()
            state.step.put("arguments", args)
        }
        if (state.type == "google_search_result") {
            if (!state.step.has("result")) state.step.put("result", JSONArray())
            onEvent(ProviderEvent.HostedToolFinished(state.step.getString("call_id"), "web_search", !state.step.optBoolean("is_error")))
        }
        state.kind?.let { kind ->
            onEvent(ProviderEvent.BlockEnd(
                kind, index,
                blockId = state.step.optString("id").ifBlank { null },
                name = state.step.optString("name").ifBlank { null },
                content = when (kind) {
                    AssistantBlockKind.TEXT -> formattedText(state.step.getJSONArray("content"))
                    AssistantBlockKind.THINKING -> plainText(state.step.getJSONArray("summary"))
                    AssistantBlockKind.TOOL_CALL -> state.step.getJSONObject("arguments").toString()
                },
                replaceContent = kind == AssistantBlockKind.TEXT,
            ))
        }
    }

    private fun finish(interaction: JSONObject) {
        val status = string(interaction, "status")
        interaction.optJSONObject("usage")?.let(::mergeUsage)
        if (status == "failed" || status == "cancelled") {
            val errors = interaction.optJSONArray("errors")
            throw AgentModelFailure.stream(errors?.optJSONObject(0) ?: JSONObject(), "Gemini 请求未成功完成。")
        }
        if (status !in setOf("completed", "requires_action", "incomplete", "budget_exceeded")) malformed()
        // 累计用量可能先于正文到达；终态统一发布，确保展示层已有对应的正文块。
        if (!usage.isEmpty) onEvent(ProviderEvent.Usage(usage))
        if (status == "incomplete" || status == "budget_exceeded") {
            // 明确的预算终态不能作为成功回答，也不能按网络中断重试或执行已生成的工具调用。
            emitSuggestions(JSONObject())
            throw AgentModelFailure("MODEL_OUTPUT_LIMIT", false,
                "Gemini 输出未完整生成，可能已达到输出或执行预算；请调整输出上限或思考档位后重试。")
        }
        if (steps.values.any { !it.stopped }) {
            throw AgentModelFailure.incompleteStream("Gemini 输出步骤未完整结束。")
        }
        val calls = steps.values.filter { it.type == "function_call" }
        if (status == "requires_action" && calls.isEmpty()) malformed()
        if (calls.isNotEmpty() && status != "requires_action") {
            throw AgentModelFailure.incompleteStream("Gemini 工具调用缺少可执行的完整终态。")
        }
        if (searchCalls != searchResults) {
            throw AgentModelFailure.incompleteStream("Gemini 搜索步骤未完整结束。")
        }
        val reason = when (status) {
            "requires_action" -> "tool_calls"
            else -> "stop"
        }
        val rawSteps = JSONArray()
        val texts = mutableListOf<String>()
        val thoughts = mutableListOf<String>()
        for (state in steps.values) {
            rawSteps.put(state.step)
            when (state.type) {
                "model_output" -> texts += formattedText(state.step.getJSONArray("content"))
                "thought" -> thoughts += plainText(state.step.getJSONArray("summary"))
            }
        }
        val result = JSONObject().put("role", "assistant").put("content", texts.joinToString("\n"))
            .put("finish_reason", reason)
        if (thoughts.any(String::isNotEmpty)) result.put("reasoning_content", thoughts.joinToString("\n"))
        if (calls.isNotEmpty()) {
            result.put("tool_calls", JSONArray().also { target ->
                calls.forEach { call -> target.put(JSONObject()
                    .put("id", call.step.getString("id")).put("type", "function")
                    .put("function", JSONObject().put("name", call.step.getString("name"))
                        .put("arguments", call.step.getJSONObject("arguments").toString()))) }
            })
        }
        GeminiEphemeralState.attachSteps(result, rawSteps)
        emitSuggestions(result)
        assistant = result
    }

    private fun emitSuggestions(assistant: JSONObject) {
        val textSteps = steps.values.filter { it.type == "model_output" && plainText(it.step.getJSONArray("content")).isNotBlank() }
        val budget = SearchSuggestionsBudget()
        val seen = mutableSetOf<String>()
        val deferred = JSONArray()
        fun suggest(afterIndex: Int, html: String) {
            if (html.isBlank() || !seen.add(html)) return
            val target = textSteps.firstOrNull { it.index > afterIndex } ?: textSteps.lastOrNull()
            if (!budget.accept(0, target?.index ?: 0, html)) malformed("GEMINI_SEARCH_SUGGESTIONS_LIMIT")
            if (target != null) onEvent(ProviderEvent.SearchSuggestions(target.index, html))
            else deferred.put(html)
        }
        for (index in 0 until pendingSuggestions.length()) suggest(-1, pendingSuggestions.getString(index))
        for (state in steps.values.filter { it.type == "google_search_result" }) {
            val results = state.step.optJSONArray("result") ?: continue
            for (index in 0 until results.length()) {
                val html = results.optJSONObject(index)?.optString("search_suggestions").orEmpty()
                suggest(state.index, html)
            }
        }
        GeminiEphemeralState.attachPendingSuggestions(assistant, deferred)
    }

    private fun mergeUsage(raw: JSONObject) {
        fun token(key: String, previous: Int?): Int? {
            if (!raw.has(key)) return previous
            val value = raw.opt(key) as? Number ?: malformed()
            return value.toLong().takeIf { it in 0..Int.MAX_VALUE.toLong() && it.toDouble() == value.toDouble() }
                ?.toInt() ?: malformed()
        }
        usage = AgentTokenUsage(
            contextTokens = token("total_tokens", usage.contextTokens),
            inputTokens = token("total_input_tokens", usage.inputTokens),
            outputTokens = token("total_output_tokens", usage.outputTokens),
            reasoningTokens = token("total_thought_tokens", usage.reasoningTokens),
            cachedTokens = token("total_cached_tokens", usage.cachedTokens),
        )
    }

    private fun validateSearchResults(step: JSONObject) {
        if (step.has("is_error") && step.opt("is_error") !is Boolean) malformed()
        if (!step.has("result")) return
        val results = step.optJSONArray("result") ?: malformed()
        for (index in 0 until results.length()) {
            val result = results.optJSONObject(index) ?: malformed()
            if (result.has("search_suggestions")) {
                val html = string(result, "search_suggestions", allowEmpty = true)
                if (html.isNotBlank() && !SearchSuggestionsPayload.isValidHtml(html)) malformed("GEMINI_SEARCH_SUGGESTIONS_LIMIT")
            }
        }
    }

    private fun validateSignature(value: JSONObject) {
        if (value.has("signature")) string(value, "signature", allowEmpty = true)
    }

    private fun validateTextContents(items: JSONArray) {
        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index) ?: malformed()
            if (string(item, "type") != "text") malformed("GEMINI_UNSUPPORTED_OUTPUT")
            string(item, "text", allowEmpty = true)
        }
    }

    private fun lastText(step: JSONObject, key: String): JSONObject {
        val items = step.getJSONArray(key)
        return items.optJSONObject(items.length() - 1) ?: JSONObject().put("type", "text").put("text", "").also(items::put)
    }

    private fun formattedText(items: JSONArray): String = (0 until items.length()).joinToString("") {
        val item = items.getJSONObject(it)
        GeminiCitationFormatter.apply(item.getString("text"), item.optJSONArray("annotations") ?: JSONArray())
    }

    private fun plainText(items: JSONArray): String = (0 until items.length()).joinToString("") { items.getJSONObject(it).getString("text") }

    private fun index(event: JSONObject): Int {
        val value = event.opt("index") as? Number ?: malformed()
        return value.toInt().takeIf { it >= 0 && it.toDouble() == value.toDouble() } ?: malformed()
    }

    private fun requireType(state: StepState, type: String) {
        if (state.type != type) malformed()
    }

    private fun string(value: JSONObject, key: String, allowEmpty: Boolean = false): String =
        (value.opt(key) as? String)?.takeIf { allowEmpty || it.isNotBlank() } ?: malformed()

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
        throw AgentModelFailure(code, false, when (code) {
            "GEMINI_UNSUPPORTED_OUTPUT" -> "Gemini 返回了当前不支持的输出类型。"
            "GEMINI_SEARCH_SUGGESTIONS_LIMIT" -> "Gemini 搜索建议超过展示容量限制。"
            else -> "Gemini 流式响应格式无效或超过容量限制。"
        })

    private companion object {
        const val MAX_EVENT_CHARS = 2 * 1024 * 1024
        const val MAX_STREAM_CHARS = 16 * 1024 * 1024
        const val MAX_ARGUMENT_CHARS = 1024 * 1024
        const val MAX_STEPS = 2048
    }
}
