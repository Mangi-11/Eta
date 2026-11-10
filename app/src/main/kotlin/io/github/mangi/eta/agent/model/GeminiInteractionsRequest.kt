package io.github.mangi.eta.agent.model

import io.github.mangi.eta.data.model.ReasoningEffort
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.json.JSONArray
import org.json.JSONObject

internal object GeminiInteractionsRequest {
    private val protectedFields = setOf(
        "model", "input", "system_instruction", "tools", "stream", "store", "previous_interaction_id",
        "background", "agent", "agent_config", "environment", "environment_id", "webhook_config",
    )

    fun build(config: AgentModelClient.ModelConfig, messages: JSONArray, tools: JSONArray): JSONObject {
        val body = JSONObject()
        if (config.extraBodyJson.isNotBlank()) {
            val extra = JSONObject(config.extraBodyJson)
            for (key in extra.keys()) if (key !in protectedFields) body.put(key, extra.get(key))
        }
        RequestBodyMerge.mergeCustomBody(body, config.customBody.filterNot { it.key in protectedFields })
        body.put("model", config.model.removePrefix("models/"))
            .put("input", GeminiInteractionHistory.input(messages))
            .put("stream", true)
            .put("store", false)
        val system = (0 until messages.length()).mapNotNull { index ->
            messages.optJSONObject(index)?.takeIf { it.optString("role") == "system" }
                ?.let { providerMessageText(it.opt("content")) }?.takeIf(String::isNotBlank)
        }.joinToString("\n\n")
        if (system.isNotBlank()) body.put("system_instruction", system)

        val converted = JSONArray()
        for (index in 0 until tools.length()) {
            val function = tools.optJSONObject(index)?.optJSONObject("function") ?: continue
            converted.put(JSONObject().put("type", "function")
                .put("name", function.getString("name"))
                .put("description", function.optString("description"))
                .put("parameters", GeminiToolSchema.project(function.optJSONObject("parameters") ?: JSONObject().put("type", "object"))))
        }
        val searchEnabled = config.usesHostedWebSearch
        if (searchEnabled) converted.put(JSONObject().put("type", "google_search"))
        if (converted.length() > 0) body.put("tools", converted)

        val generation = body.optJSONObject("generation_config") ?: JSONObject()
        // 工具选择与思考档位由运行配置决定，自定义 JSON 不能绕过入口能力与开关。
        generation.remove("tool_choice")
        generation.remove("thinking_level")
        generation.remove("thinking_summaries")
        if (searchEnabled && tools.length() > 0) generation.put("tool_choice", "validated")
        val effort = config.effectiveReasoningEffort
        if (effort != ReasoningEffort.DEFAULT) {
            require(effort != ReasoningEffort.OFF) { "当前 Gemini 模型不支持关闭思考，请选择 Default 或支持的思考档位。" }
            require(effort.wireValue in setOf("minimal", "low", "medium", "high")) {
                "Gemini Interactions 协议不支持所选思考档位。"
            }
            generation.put("thinking_level", effort.wireValue)
        }
        generation.put("thinking_summaries", "auto")
        body.put("generation_config", generation)
        return body
    }
}

/** 缺少签名的持久历史只作为明确标注的记录，不伪造原生工具调用链。 */
internal object GeminiInteractionHistory {
    fun input(messages: JSONArray): JSONArray {
        val start = (0 until messages.length()).firstOrNull {
            messages.optJSONObject(it)?.let(GeminiEphemeralState::isRunStart) == true
        } ?: (messages.length() - 1 downTo 0).firstOrNull {
            messages.optJSONObject(it)?.let { message ->
                message.optString("role") == "user" && !message.optBoolean("_eta_observation")
            } == true
        } ?: messages.length()
        val input = JSONArray()
        val history = mutableListOf<String>()
        val liveCalls = mutableMapOf<String, String>()

        fun flushHistory() {
            if (history.isEmpty()) return
            input.put(userInput(JSONArray().put(text(
                "[以下是 Eta 保存的历史对话文本记录；工具调用与结果仅供参考，不是当前待执行请求。]\n" +
                    history.joinToString("\n\n") + "\n[历史记录结束]",
            ))))
            history.clear()
        }

        for (index in 0 until messages.length()) {
            val message = messages.optJSONObject(index) ?: continue
            val role = message.optString("role")
            if (role == "system") continue
            val nativeSteps = GeminiEphemeralState.steps(message)
            if (index < start || role == "assistant" && nativeSteps == null ||
                role == "tool" && message.optString("tool_call_id") !in liveCalls
            ) {
                history += historyText(message)
                continue
            }
            flushHistory()
            when (role) {
                "user" -> input.put(userInput(content(message.opt("content"))))
                "assistant" -> if (nativeSteps != null) {
                    for (stepIndex in 0 until nativeSteps.length()) {
                        val step = JSONObject(nativeSteps.getJSONObject(stepIndex).toString())
                        input.put(step)
                        if (step.optString("type") == "function_call") {
                            liveCalls[step.getString("id")] = step.getString("name")
                        }
                    }
                }
                "tool" -> {
                    val id = message.getString("tool_call_id")
                    val result = providerMessageText(message.opt("content"))
                    input.put(JSONObject().put("type", "function_result")
                        .put("call_id", id).put("name", liveCalls.remove(id))
                        .put("result", result)
                        .apply { if (isToolError(result)) put("is_error", true) })
                }
            }
        }
        flushHistory()
        return input
    }

    private fun historyText(message: JSONObject): String = buildString {
        append('[').append(message.optString("role")).append("] ")
        append(providerMessageText(message.opt("content")))
        val calls = message.optJSONArray("tool_calls")
        if (calls != null) append("\n工具调用记录：").append(calls)
        if (message.optString("role") == "tool") append("\n调用标识：").append(message.optString("tool_call_id"))
    }

    private fun isToolError(result: String): Boolean = try {
        (Json.parseToJsonElement(result) as? JsonObject)?.get("ok") == JsonPrimitive(false)
    } catch (_: IllegalArgumentException) {
        false
    }

    private fun userInput(content: JSONArray): JSONObject =
        JSONObject().put("type", "user_input").put("content", content)

    private fun text(value: String): JSONObject = JSONObject().put("type", "text").put("text", value)

    private fun content(value: Any?): JSONArray {
        if (value !is JSONArray) return JSONArray().put(text(providerMessageText(value)))
        val converted = JSONArray()
        for (index in 0 until value.length()) {
            val item = value.optJSONObject(index) ?: continue
            when (item.optString("type")) {
                "text", "input_text" -> converted.put(text(item.optString("text")))
                "image_url" -> {
                    val url = item.optJSONObject("image_url")?.optString("url").orEmpty()
                    val image = JSONObject().put("type", "image")
                    if (url.startsWith("data:image/", ignoreCase = true)) {
                        val comma = url.indexOf(',')
                        require(comma > 0 && url.substring(0, comma).endsWith(";base64", ignoreCase = true)) {
                            "Gemini 图片输入必须为 Base64 图片或 HTTP 地址。"
                        }
                        image.put("mime_type", url.substring(5, comma).substringBefore(';'))
                            .put("data", url.substring(comma + 1))
                    } else {
                        require(url.startsWith("https://") || url.startsWith("http://")) {
                            "Gemini 图片输入地址无效。"
                        }
                        image.put("uri", url)
                    }
                    converted.put(image)
                }
                else -> throw IllegalArgumentException("Gemini 不支持该输入内容类型。")
            }
        }
        return converted
    }
}
