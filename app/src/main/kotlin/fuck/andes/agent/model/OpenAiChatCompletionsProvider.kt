package fuck.andes.agent.model

import fuck.andes.agent.runtime.AgentRunController
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject

internal object OpenAiChatCompletionsProvider : AgentProviderClient {
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 60_000
    private const val MAX_ERROR_CHARS = 600

    override val id: String = "openai_chat_completions"

    override val capabilities: ProviderCapabilities =
        ProviderCapabilities(
            endpoint = EndpointKind.CHAT_COMPLETIONS,
            streamingText = true,
            streamingToolCalls = true,
            imageInput = true,
            toolResultImages = false,
            strictTools = false,
            parallelToolCalls = false
        )

    override fun complete(
        request: ProviderRequest,
        runController: AgentRunController,
        onEvent: (ProviderEvent) -> Unit
    ): ProviderResponse {
        val config = request.config
        val connection = (URL(config.chatCompletionsUrl()).openConnection() as HttpURLConnection)
            .apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "text/event-stream")
                setRequestProperty("Authorization", "Bearer ${config.apiKey}")
            }

        val binding = runController.register { connection.disconnect() }
        try {
            runController.throwIfCancelled()
            onEvent(ProviderEvent.RequestStarted)
            val requestBody = buildRequestJson(config, request.messages, request.tools).toString().toByteArray(Charsets.UTF_8)
            connection.outputStream.use { it.write(requestBody) }

            val code = connection.responseCode
            onEvent(ProviderEvent.ResponseHeaders(code))
            runController.throwIfCancelled()
            if (code !in 200..299) {
                val response = readResponse(connection.errorStream)
                error("模型接口返回 HTTP $code：${response.compactError()}")
            }
            val assistantMessage = readStreamingAssistantMessage(connection.inputStream, runController, onEvent)
            onEvent(ProviderEvent.Completed(assistantMessage.optString("finish_reason").ifBlank { null }))
            return ProviderResponse(assistantMessage)
        } finally {
            binding.close()
            connection.disconnect()
        }
    }

    private fun buildRequestJson(
        config: AgentModelClient.ModelConfig,
        messages: JSONArray,
        tools: JSONArray
    ): JSONObject {
        return JSONObject()
            .put("model", config.model)
            .put("stream", true)
            .put("messages", messages)
            .put("tools", tools)
            .put("tool_choice", "auto")
    }

    private fun readStreamingAssistantMessage(
        stream: InputStream?,
        runController: AgentRunController,
        onEvent: (ProviderEvent) -> Unit
    ): JSONObject {
        if (stream == null) error("模型接口未返回响应流")
        val content = StringBuilder()
        val toolCalls = linkedMapOf<Int, StreamingToolCall>()
        var sawStreamData = false
        var sawDone = false
        var finishReason: String? = null

        BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
            while (true) {
                runController.throwIfCancelled()
                val line = reader.readLine() ?: break
                if (!line.startsWith("data:")) continue
                sawStreamData = true
                val payload = line.removePrefix("data:").trim()
                if (payload.isBlank()) continue
                if (payload == "[DONE]") {
                    sawDone = true
                    break
                }
                val chunk = JSONObject(payload)
                val choice = chunk.optJSONArray("choices")?.optJSONObject(0) ?: continue
                val reason = choice.optString("finish_reason")
                if (reason.isNotBlank() && reason != "null") {
                    finishReason = reason
                }
                val delta = choice.optJSONObject("delta") ?: continue
                if (delta.has("content") && !delta.isNull("content")) {
                    val text = delta.optString("content")
                    if (text.isNotEmpty()) {
                        content.append(text)
                        onEvent(ProviderEvent.TextDelta(text))
                    }
                }
                val deltaToolCalls = delta.optJSONArray("tool_calls") ?: continue
                for (i in 0 until deltaToolCalls.length()) {
                    val item = deltaToolCalls.optJSONObject(i) ?: continue
                    val index = item.optInt("index", i)
                    val call = toolCalls.getOrPut(index) { StreamingToolCall(index) }
                    if (item.has("id") && !item.isNull("id")) call.id = item.optString("id")
                    if (item.has("type") && !item.isNull("type")) call.type = item.optString("type").ifBlank { "function" }
                    val function = item.optJSONObject("function")
                    val nameDelta = function?.takeIf { it.has("name") && !it.isNull("name") }?.optString("name").orEmpty()
                    val argsDelta = function?.takeIf { it.has("arguments") && !it.isNull("arguments") }?.optString("arguments").orEmpty()
                    if (nameDelta.isNotEmpty()) call.name.append(nameDelta)
                    if (argsDelta.isNotEmpty()) call.arguments.append(argsDelta)
                    onEvent(
                        ProviderEvent.ToolCallDelta(
                            index = index,
                            id = call.id,
                            name = nameDelta.ifBlank { null },
                            argumentsDelta = argsDelta
                        )
                    )
                }
            }
        }

        if (!sawStreamData) error("模型接口未返回 SSE data chunk")
        if (!sawDone) error("模型接口 SSE 流未正常结束")

        return JSONObject()
            .put("role", "assistant")
            .put("content", content.toString())
            .put("finish_reason", finishReason.orEmpty())
            .also { message ->
                if (toolCalls.isNotEmpty()) {
                    message.put(
                        "tool_calls",
                        JSONArray().also { array ->
                            toolCalls.values.sortedBy { it.index }.forEachIndexed { position, call ->
                                array.put(call.toJson(position))
                            }
                        }
                    )
                }
            }
    }

    private data class StreamingToolCall(
        val index: Int,
        var id: String? = null,
        var type: String = "function",
        val name: StringBuilder = StringBuilder(),
        val arguments: StringBuilder = StringBuilder()
    ) {
        fun toJson(position: Int): JSONObject {
            val functionName = name.toString().trim()
            return JSONObject()
                .put("id", id ?: "tool_call_$position")
                .put("type", type.ifBlank { "function" })
                .put(
                    "function",
                    JSONObject()
                        .put("name", functionName)
                        .put("arguments", arguments.toString())
                )
        }
    }

    private fun readResponse(stream: InputStream?): String {
        if (stream == null) return ""
        return BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
            reader.readText()
        }
    }

    private fun AgentModelClient.ModelConfig.chatCompletionsUrl(): String {
        val normalized = baseUrl.trimEnd('/')
        return if (normalized.endsWith("/chat/completions")) {
            normalized
        } else {
            "$normalized/chat/completions"
        }
    }

    private fun String.compactError(): String =
        replace('\n', ' ')
            .replace('\r', ' ')
            .let { if (it.length > MAX_ERROR_CHARS) it.take(MAX_ERROR_CHARS) + "..." else it }
}
