package io.github.mangi.eta.agent.model

import io.github.mangi.eta.data.model.ReasoningEffort
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.json.JSONArray
import org.json.JSONObject

internal object VertexGenerateContentRequest {
    private val protectedFields = setOf(
        "contents", "systemInstruction", "system_instruction", "tools", "toolConfig", "tool_config",
    )
    private val COMPACTION_EXTRA_BLOCKED_FIELDS = setOf("messages", "instructions", "system", "tool_choice")
    private val ALLOWED_THINKING_LEVELS = setOf("minimal", "low", "medium", "high", "xhigh")

    fun build(
        config: AgentModelClient.ModelConfig,
        messages: JSONArray,
        tools: JSONArray,
        purpose: ProviderRequestPurpose = ProviderRequestPurpose.CHAT,
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

        body.put("contents", VertexContentHistory.contents(messages))

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
        val toolEntries = JSONArray()
        if (declarations.length() > 0) {
            toolEntries.put(JSONObject().put("functionDeclarations", declarations))
        }
        if (config.usesHostedWebSearch) {
            toolEntries.put(JSONObject().put("googleSearch", JSONObject()))
        }
        if (toolEntries.length() > 0) {
            body.put("tools", toolEntries)
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
        val expectsThinking = effort != ReasoningEffort.DEFAULT ||
            config.reasoningCapabilities?.supportedEfforts?.isNotEmpty() == true ||
            config.thinkingEnabled
        if (effort != ReasoningEffort.DEFAULT) {
            require(effort != ReasoningEffort.OFF) { "当前 Gemini 模型不支持关闭思考，请选择 Default 或支持的思考档位。" }
            require(effort.wireValue in ALLOWED_THINKING_LEVELS) {
                "Vertex AI Gemini 协议不支持所选思考档位。"
            }
            generation.put(
                "thinkingConfig",
                JSONObject()
                    .put("includeThoughts", true)
                    .put("thinkingLevel", effort.wireValue.uppercase()),
            )
        } else if (expectsThinking) {
            generation.put(
                "thinkingConfig",
                JSONObject().put("includeThoughts", true),
            )
        }
        if (generation.length() > 0) {
            body.put("generationConfig", generation)
        }
        return body
    }
}

/** 缺少 thoughtSignature 的持久历史只作为明确标注的文本记录，不伪造原生工具调用链。 */
internal object VertexContentHistory {
    private data class LiveCall(
        val name: String,
        val originalId: String?,
    )

    fun contents(messages: JSONArray): JSONArray {
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
                            val mappedId = originalId
                                ?: toolCalls?.optJSONObject(callCursor)?.optString("id")?.takeIf(String::isNotBlank)
                                ?: "vertex-call-$callCursor"
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
                    if (url.startsWith("data:image/", ignoreCase = true)) {
                        val comma = url.indexOf(',')
                        require(comma > 0 && url.substring(0, comma).endsWith(";base64", ignoreCase = true)) {
                            "Gemini 图片输入必须为 Base64 图片或 HTTP 地址。"
                        }
                        converted.put(
                            JSONObject().put(
                                "inlineData",
                                JSONObject()
                                    .put("mimeType", url.substring(5, comma).substringBefore(';'))
                                    .put("data", url.substring(comma + 1)),
                            ),
                        )
                    } else {
                        require(url.startsWith("https://") || url.startsWith("http://") || url.startsWith("gs://")) {
                            "Gemini 图片输入地址无效。"
                        }
                        val fileData = JSONObject().put("fileUri", url)
                        inferImageMimeType(url)?.let { fileData.put("mimeType", it) }
                        converted.put(JSONObject().put("fileData", fileData))
                    }
                }
                else -> throw IllegalArgumentException("Vertex AI Gemini 不支持该输入内容类型。")
            }
        }
        return converted
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
