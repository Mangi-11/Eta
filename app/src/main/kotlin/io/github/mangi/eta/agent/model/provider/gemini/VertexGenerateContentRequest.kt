package io.github.mangi.eta.agent.model.provider.gemini

import io.github.mangi.eta.R
import io.github.mangi.eta.core.AppStrings
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.data.provider.GeminiModelCapabilities
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.json.JSONArray
import org.json.JSONObject
import io.github.mangi.eta.agent.model.provider.AgentModelClient
import io.github.mangi.eta.agent.model.provider.ProviderRequestPurpose
import io.github.mangi.eta.agent.model.provider.RequestBodyMerge
import io.github.mangi.eta.agent.model.provider.providerMessageText

internal object VertexGenerateContentRequest {
    private val protectedFields = setOf(
        "contents", "systemInstruction", "system_instruction", "tools", "toolConfig", "tool_config",
        "continuationToken", "continuation_token",
    )
    private val COMPACTION_EXTRA_BLOCKED_FIELDS = setOf("messages", "instructions", "system", "tool_choice")

    fun build(
        config: AgentModelClient.ModelConfig,
        messages: JSONArray,
        tools: JSONArray,
        purpose: ProviderRequestPurpose = ProviderRequestPurpose.CHAT,
        toolResultImages: Map<String, List<AgentModelClient.ModelImage>> = emptyMap(),
    ): JSONObject {
        val body = JSONObject()
        if (config.extraBodyJson.isNotBlank()) {
            val extra = JSONObject(config.extraBodyJson)
            for (key in extra.keys()) {
                if (key !in protectedFields && (purpose != ProviderRequestPurpose.COMPACTION || key !in COMPACTION_EXTRA_BLOCKED_FIELDS)) {
                    body.put(key, extra.get(key))
                }
            }
        }
        RequestBodyMerge.mergeCustomBody(
            body,
            config.customBody.filterNot {
                it.key in protectedFields || (purpose == ProviderRequestPurpose.COMPACTION && it.key in COMPACTION_EXTRA_BLOCKED_FIELDS)
            },
        )

        body.put("contents", VertexContentHistory.contents(messages, toolResultImages))

        val system = (0 until messages.length()).mapNotNull { index ->
            messages.optJSONObject(index)?.takeIf { it.optString("role") == "system" }
                ?.let { providerMessageText(it.opt("content")) }?.takeIf(String::isNotBlank)
        }.joinToString("\n\n")
        if (system.isNotBlank()) {
            body.put(
                "systemInstruction",
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))),
            )
        }

        val declarations = JSONArray()
        for (index in 0 until tools.length()) {
            val function = tools.optJSONObject(index)?.optJSONObject("function") ?: continue
            val projected = GeminiToolSchema.project(
                function.optJSONObject("parameters") ?: JSONObject().put("type", "object"),
            ).apply { remove("\$schema") }
            declarations.put(
                JSONObject()
                    .put("name", function.getString("name"))
                    .put("description", function.optString("description"))
                    .put("parametersJsonSchema", projected),
            )
        }
        if (declarations.length() > 0) {
            body.put("tools", JSONArray().put(JSONObject().put("functionDeclarations", declarations)))
        }
        if (purpose == ProviderRequestPurpose.COMPACTION && declarations.length() > 0) {
            body.put(
                "toolConfig",
                JSONObject().put(
                    "functionCallingConfig",
                    JSONObject().put("mode", "NONE"),
                ),
            )
        }

        val generation = body.optJSONObject("generationConfig")
            ?: body.optJSONObject("generation_config")?.also { body.remove("generation_config") }
            ?: JSONObject()
        // 思考配置由运行参数统一控制，避免自定义 JSON 绕过模型能力与档位约束。
        generation.remove("thinkingConfig")
        generation.remove("thinking_config")
        val effort = config.effectiveReasoningEffort
        val thinking = JSONObject().put("includeThoughts", true)
        if (effort != ReasoningEffort.DEFAULT) {
            require(effort in GeminiModelCapabilities.reasoningCapabilities(config.model)?.supportedEfforts.orEmpty()) {
                AppStrings.get(
                    R.string.provider_err_vertex_thinking_unsupported,
                    "当前 Vertex AI 模型不支持所选思考档位，请使用 Default 或模型支持的档位。",
                )
            }
            thinking.put("thinkingLevel", effort.wireValue.uppercase())
        }
        generation.put("thinkingConfig", thinking)
        body.put("generationConfig", generation)
        return body
    }
}

/** 缺少 thoughtSignature 的持久历史只作为明确标注的文本记录，不伪造原生工具调用链。 */
internal object VertexContentHistory {
    private data class LiveCall(
        val name: String,
        val originalId: String?,
    )

    fun contents(
        messages: JSONArray,
        toolResultImages: Map<String, List<AgentModelClient.ModelImage>> = emptyMap(),
    ): JSONArray {
        val start = (0 until messages.length()).firstOrNull {
            messages.optJSONObject(it)?.let(GeminiEphemeralState::isRunStart) == true
        } ?: (messages.length() - 1 downTo 0).firstOrNull {
            messages.optJSONObject(it)?.let { message ->
                message.optString("role") == "user" && !message.optBoolean("_eta_observation")
            } == true
        } ?: messages.length()

        val result = JSONArray()
        val history = mutableListOf<String>()
        val liveCalls = mutableMapOf<String, LiveCall>()

        fun appendTurn(role: String, parts: JSONArray) {
            if (parts.length() == 0) return
            val last = result.optJSONObject(result.length() - 1)
            if (last != null && last.optString("role") == role) {
                val existing = last.getJSONArray("parts")
                for (i in 0 until parts.length()) existing.put(parts.get(i))
            } else {
                result.put(JSONObject().put("role", role).put("parts", parts))
            }
        }

        fun flushHistory() {
            if (history.isEmpty()) return
            val text = "[以下是 Eta 保存的历史对话文本记录；工具调用与结果仅供参考，不是当前待执行请求。]\n" +
                history.joinToString("\n\n") + "\n[历史记录结束]"
            appendTurn("user", JSONArray().put(JSONObject().put("text", text)))
            history.clear()
        }

        for (index in 0 until messages.length()) {
            val message = messages.optJSONObject(index) ?: continue
            val role = message.optString("role")
            if (role == "system") continue
            val nativeParts = GeminiEphemeralState.vertexParts(message)
            if (index < start || (role == "assistant" && nativeParts == null) ||
                (role == "tool" && message.optString("tool_call_id") !in liveCalls)
            ) {
                history += historyText(message)
                continue
            }
            flushHistory()
            when (role) {
                "user" -> appendTurn("user", userParts(message.opt("content")))
                "assistant" -> if (nativeParts != null) {
                    val clonedParts = JSONArray()
                    val toolCalls = message.optJSONArray("tool_calls")
                    var callCursor = 0
                    for (partIndex in 0 until nativeParts.length()) {
                        val part = JSONObject(nativeParts.getJSONObject(partIndex).toString())
                        clonedParts.put(part)
                        val fn = part.optJSONObject("functionCall")
                        if (fn != null) {
                            val originalId = fn.optString("id").takeIf(String::isNotBlank)
                            val mappedId = requireNotNull(toolCalls?.optJSONObject(callCursor)?.optString("id")
                                ?.takeIf(String::isNotBlank)) {
                                AppStrings.get(R.string.provider_err_vertex_tool_call_id_missing, "Vertex AI 工具调用缺少 Eta 调用标识。")
                            }
                            liveCalls[mappedId] = LiveCall(fn.getString("name"), originalId)
                            callCursor += 1
                        }
                    }
                    appendTurn("model", clonedParts)
                }
                "tool" -> {
                    val callId = message.getString("tool_call_id")
                    val call = liveCalls.remove(callId) ?: continue
                    val raw = providerMessageText(message.opt("content"))
                    val responsePayload = parseResponseObject(raw)
                    val functionResponse = JSONObject()
                        .put("name", call.name)
                        .put("response", responsePayload)
                    call.originalId?.let { functionResponse.put("id", it) }
                    toolResultImages[callId]?.takeIf { it.isNotEmpty() }?.let { images ->
                        val parts = JSONArray()
                        for (image in images) parts.put(imagePart(image.reference, image.mimeType))
                        functionResponse.put("parts", parts)
                    }
                    appendTurn("user", JSONArray().put(JSONObject().put("functionResponse", functionResponse)))
                }
            }
        }
        flushHistory()
        return result
    }

    private fun parseResponseObject(raw: String): JSONObject {
        val trimmed = raw.trim()
        if (trimmed.startsWith("{")) {
            runCatching {
                val parsed = Json.parseToJsonElement(trimmed)
                if (parsed is JsonObject) return JSONObject(parsed.toString())
            }
        }
        return JSONObject().put("result", raw)
    }

    private fun historyText(message: JSONObject): String = buildString {
        append('[').append(message.optString("role")).append("] ")
        append(providerMessageText(message.opt("content")))
        val calls = message.optJSONArray("tool_calls")
        if (calls != null) append("\n工具调用记录：").append(calls)
        if (message.optString("role") == "tool") append("\n调用标识：").append(message.optString("tool_call_id"))
    }

    private fun userParts(value: Any?): JSONArray {
        if (value !is JSONArray) {
            return JSONArray().put(JSONObject().put("text", providerMessageText(value)))
        }
        val converted = JSONArray()
        for (index in 0 until value.length()) {
            val item = value.optJSONObject(index) ?: continue
            when (item.optString("type")) {
                "text", "input_text" -> converted.put(JSONObject().put("text", item.optString("text")))
                "image_url" -> {
                    val url = item.optJSONObject("image_url")?.optString("url").orEmpty()
                    converted.put(imagePart(url))
                }
                else -> throw IllegalArgumentException(
                    AppStrings.get(R.string.provider_err_vertex_input_type_unsupported, "Vertex AI Gemini 不支持该输入内容类型。"),
                )
            }
        }
        return converted
    }

    private fun imagePart(url: String, mimeType: String? = null): JSONObject {
        if (url.startsWith("data:image/", ignoreCase = true)) {
            val comma = url.indexOf(',')
            require(comma > 0 && url.substring(0, comma).endsWith(";base64", ignoreCase = true)) {
                AppStrings.get(R.string.provider_err_gemini_image_source, "Gemini 图片输入必须为 Base64 图片或可访问的图片地址。")
            }
            return JSONObject().put(
                "inlineData",
                JSONObject().put("mimeType", url.substring(5, comma).substringBefore(';'))
                    .put("data", url.substring(comma + 1)),
            )
        }
        require(url.startsWith("https://", true) || url.startsWith("http://", true) || url.startsWith("gs://")) {
            AppStrings.get(R.string.provider_err_gemini_image_url_invalid, "Gemini 图片输入地址无效。")
        }
        val resolvedMimeType = requireNotNull(mimeType?.takeIf { it.startsWith("image/") } ?: inferImageMimeType(url)) {
            AppStrings.get(R.string.provider_err_gemini_image_mime_unknown, "无法识别 Gemini 图片地址的媒体类型。")
        }
        return JSONObject().put("fileData", JSONObject().put("fileUri", url).put("mimeType", resolvedMimeType))
    }

    private fun inferImageMimeType(url: String): String? {
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        return when {
            path.endsWith(".png") -> "image/png"
            path.endsWith(".webp") -> "image/webp"
            path.endsWith(".gif") -> "image/gif"
            path.endsWith(".jpg") || path.endsWith(".jpeg") -> "image/jpeg"
            else -> null
        }
    }
}
