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
        toolResultImages = true,
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
        val url = ProviderUrls.vertexStreamGenerateContentUrl(
            baseUrl = config.baseUrl,
            model = config.model,
            project = config.vertexProject,
            location = config.vertexLocation,
        )
        val payload = VertexGenerateContentRequest.build(
            config, request.messages, request.effectiveTools, request.purpose, request.toolResultImages,
        ).toString()
        val httpRequest = Request.Builder()
            .url(url)
            .headers(headers)
            .post(payload.toRequestBody(jsonMediaType))
            .build()
        val client = AgentHttpClient.vertexClient(AgentHttpClient.modelClient(request.purpose))
        val call = client.newCall(httpRequest)
        val binding = runController.register(call::cancel)
        try {
            runController.throwIfCancelled()
            onEvent(ProviderEvent.RequestStarted)
            call.execute().use { response ->
                runController.throwIfCancelled()
                onEvent(ProviderEvent.ResponseHeaders(response.code))
                if (!response.isSuccessful) {
                    throw AgentModelFailure.http(response.code, response.peekBody(16_384).string(), includeParameterDetails = true)
                }
                val assistant = VertexGenerateContentStream(onEvent).read(response.body.byteStream(), runController)
                onEvent(ProviderEvent.Completed(assistant.optString("finish_reason")))
                return ProviderResponse(assistant)
            }
        } catch (failure: Exception) {
            runController.throwIfCancelled()
            throw failure
        } finally {
            binding.close()
        }
    }
}
