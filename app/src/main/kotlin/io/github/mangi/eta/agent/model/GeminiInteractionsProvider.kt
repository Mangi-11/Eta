package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentRunController
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

internal object GeminiInteractionsProvider : AgentProviderClient {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    override val id: String = "gemini_interactions"
    override val capabilities = ProviderCapabilities(
        endpoint = EndpointKind.GEMINI_INTERACTIONS,
        streamingText = true,
        streamingToolCalls = true,
        imageInput = true,
        toolResultImages = false,
        strictTools = false,
        parallelToolCalls = true,
    )

    override fun complete(
        request: ProviderRequest,
        runController: AgentRunController,
        onEvent: (ProviderEvent) -> Unit,
    ): ProviderResponse {
        val config = request.effectiveConfig
        val headers = Headers.Builder()
            .apply {
                if (config.apiKey.isNotBlank()) add("x-goog-api-key", config.apiKey)
                ProviderRequestHeaders.mergeInto(this, config.baseUrl, config.customHeaders, request.sessionId)
                set("Content-Type", "application/json; charset=utf-8")
                set("Accept", "text/event-stream")
            }.build()
        val httpRequest = Request.Builder().url(ProviderUrls.geminiInteractionsUrl(config.baseUrl))
            .headers(headers)
            .post(GeminiInteractionsRequest.build(config, request.messages, request.effectiveTools, request.purpose)
                .toString().toRequestBody(jsonMediaType))
            .build()
        val call = AgentHttpClient.modelClient(request.purpose).newCall(httpRequest)
        val binding = runController.register { call.cancel() }
        try {
            runController.throwIfCancelled()
            onEvent(ProviderEvent.RequestStarted)
            call.execute().use { response ->
                onEvent(ProviderEvent.ResponseHeaders(response.code))
                runController.throwIfCancelled()
                if (!response.isSuccessful) throw AgentModelFailure.http(
                    response.code, response.peekBody(16_384).string(), includeParameterDetails = true,
                )
                val previousAssistant = (request.messages.length() - 1 downTo 0).firstNotNullOfOrNull {
                    request.messages.optJSONObject(it)?.takeIf { message -> message.optString("role") == "assistant" }
                }
                val pendingSuggestions = previousAssistant?.let(GeminiEphemeralState::pendingSuggestions) ?: org.json.JSONArray()
                val assistant = GeminiInteractionsStream(onEvent, pendingSuggestions).read(response.body.byteStream(), runController)
                onEvent(ProviderEvent.Completed(assistant.optString("finish_reason")))
                return ProviderResponse(assistant)
            }
        } catch (failure: Throwable) {
            runController.throwIfCancelled()
            throw failure
        } finally {
            binding.close()
        }
    }
}
