package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentRunController
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

internal object VertexGenerateContentProvider : AgentProviderClient {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    override val id: String = "vertex_generate_content"
    override val capabilities = ProviderCapabilities(
        endpoint = EndpointKind.VERTEX_GENERATE_CONTENT,
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
        val payload = VertexGenerateContentRequest.build(
            config, request.messages, request.effectiveTools, request.purpose,
        ).toString()

        val initialUrl = ProviderUrls.vertexStreamGenerateContentUrl(
            baseUrl = config.baseUrl,
            model = config.model,
            project = config.vertexProject,
            location = config.vertexLocation,
        )
        return executeWithOptionalGlobalFallback(
            url = initialUrl,
            headers = headers,
            payload = payload,
            request = request,
            config = config,
            runController = runController,
            onEvent = onEvent,
            allowFallback = config.vertexProject.isBlank() &&
                !config.baseUrl.contains("/projects/", ignoreCase = true),
        )
    }

    private fun executeWithOptionalGlobalFallback(
        url: String,
        headers: Headers,
        payload: String,
        request: ProviderRequest,
        config: AgentModelClient.ModelConfig,
        runController: AgentRunController,
        onEvent: (ProviderEvent) -> Unit,
        allowFallback: Boolean,
    ): ProviderResponse {
        val httpRequest = Request.Builder()
            .url(url)
            .headers(headers)
            .post(payload.toRequestBody(jsonMediaType))
            .build()
        val call = AgentHttpClient.modelClient(request.purpose).newCall(httpRequest)
        val binding = runController.register { call.cancel() }
        var fallbackProject: String? = null
        try {
            runController.throwIfCancelled()
            onEvent(ProviderEvent.RequestStarted)
            call.execute().use { response ->
                runController.throwIfCancelled()
                if (!response.isSuccessful) {
                    val errorBody = response.peekBody(16_384).string()
                    if (allowFallback && response.code == 404) {
                        fallbackProject = ProviderUrls.extractVertexGlobalFallbackProject(errorBody)
                    }
                    if (fallbackProject == null) {
                        onEvent(ProviderEvent.ResponseHeaders(response.code))
                        throw AgentModelFailure.http(response.code, errorBody, includeParameterDetails = true)
                    }
                } else {
                    onEvent(ProviderEvent.ResponseHeaders(response.code))
                    val previousAssistant = (request.messages.length() - 1 downTo 0).firstNotNullOfOrNull {
                        request.messages.optJSONObject(it)?.takeIf { message -> message.optString("role") == "assistant" }
                    }
                    val pendingSuggestions = previousAssistant?.let(GeminiEphemeralState::pendingSuggestions)
                        ?: org.json.JSONArray()
                    val assistant = VertexGenerateContentStream(onEvent, pendingSuggestions)
                        .read(response.body.byteStream(), runController)
                    onEvent(ProviderEvent.Completed(assistant.optString("finish_reason")))
                    return ProviderResponse(assistant)
                }
            }
        } catch (failure: Throwable) {
            runController.throwIfCancelled()
            throw failure
        } finally {
            binding.close()
        }

        // 未填 Project 的 Express 请求被边缘网关路由至区域节点导致 404 时，提取项目号改连 global 端点重试一次。
        val extractedProject = requireNotNull(fallbackProject)
        val fallbackUrl = ProviderUrls.vertexGlobalProjectFallbackUrl(
            baseUrl = config.baseUrl,
            model = config.model,
            project = extractedProject,
        )
        return executeWithOptionalGlobalFallback(
            url = fallbackUrl,
            headers = headers,
            payload = payload,
            request = request,
            config = config,
            runController = runController,
            onEvent = onEvent,
            allowFallback = false,
        )
    }
}
