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
            streamingText = false,
            streamingToolCalls = false,
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
                setRequestProperty("Accept", "application/json")
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
            val response = readResponse(if (code in 200..299) connection.inputStream else connection.errorStream)
            if (code !in 200..299) {
                error("模型接口返回 HTTP $code：${response.compactError()}")
            }
            val assistantMessage = parseAssistantMessage(response)
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
            .put("stream", false)
            .put("messages", messages)
            .put("tools", tools)
            .put("tool_choice", "auto")
    }

    private fun parseAssistantMessage(response: String): JSONObject {
        val json = JSONObject(response)
        val choice = json.optJSONArray("choices")?.optJSONObject(0)
            ?: error("模型接口未返回 choices")
        val message = choice.optJSONObject("message")
        if (message != null) {
            message.put("finish_reason", choice.optString("finish_reason"))
            return message
        }
        val text = choice.optString("text").trim()
        if (text.isNotBlank()) {
            return JSONObject()
                .put("role", "assistant")
                .put("content", text)
        }
        error("模型接口未返回 assistant message")
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
