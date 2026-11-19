package io.github.mangi.eta.data.repository

import io.github.mangi.eta.agent.model.AgentModelFailure
import io.github.mangi.eta.agent.model.ProviderRequestHeaders
import io.github.mangi.eta.agent.model.ProviderUrls
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.data.model.GeminiProviderSetting
import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.model.ModelSource
import io.github.mangi.eta.data.provider.GeminiModelCapabilities
import io.github.mangi.eta.data.provider.OfficialModelCatalog
import java.util.UUID
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

internal data class VertexConnectionTestResult(
    val modelId: String,
    val usedGlobalFallback: Boolean = false,
)

internal object GeminiModelCatalog {
    private val json = Json { ignoreUnknownKeys = true }
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private const val DEFAULT_VERTEX_PROBE_MODEL = "gemini-4-hydrogen"
    private const val VERTEX_PROBE_BODY = """{"contents":[{"role":"user","parts":[{"text":"ping"}]}]}"""

    data class Page(val models: List<Model>, val nextPageToken: String?)

    fun parsePage(body: String): Page {
        val root = try {
            json.parseToJsonElement(body) as? JsonObject ?: error("Gemini 模型目录格式无效")
        } catch (_: SerializationException) {
            error("Gemini 模型目录不是有效的 JSON")
        }
        require(root["error"] == null || root["error"] == JsonNull) { "Gemini 返回了模型目录错误" }
        val entries = (root["models"] as? JsonArray) ?: error("Gemini 模型目录缺少 models 列表")
        val models = entries.mapNotNull { entry ->
            val row = (entry as? JsonObject) ?: error("Gemini 模型目录包含无效模型项")
            val modelId = row.string("name")?.trim()?.removePrefix("models/")
                ?.takeIf(String::isNotBlank) ?: error("Gemini 模型目录缺少模型名称")
            val methods = row.stringList("supportedGenerationMethods").orEmpty()
            if (methods.isNotEmpty() && "generateContent" !in methods && "interactions" !in methods) {
                return@mapNotNull null
            }
            val capabilities = GeminiModelCapabilities.reasoningCapabilities(modelId)
            val thinking = (row["thinking"] as? JsonPrimitive)?.booleanOrNull
            Model(
                id = UUID.randomUUID().toString(),
                modelId = modelId,
                displayName = row.string("displayName")?.takeIf(String::isNotBlank) ?: modelId,
                source = ModelSource.REMOTE,
                ownedBy = "google",
                contextWindow = row.positiveInt("inputTokenLimit"),
                contextWindowOverride = row.positiveInt("inputTokenLimit"),
                inputModalities = if (capabilities != null) listOf(Model.TEXT_MODALITY, Model.IMAGE_MODALITY) else emptyList(),
                outputModalities = listOf(Model.TEXT_MODALITY),
                toolCall = true.takeIf { capabilities != null },
                reasoning = thinking ?: true.takeIf { capabilities != null },
                reasoningCapabilities = capabilities.takeUnless { thinking == false },
            )
        }
        val token = root["nextPageToken"]?.let { value ->
            require(value is JsonPrimitive && value.isString) { "Gemini 模型目录分页令牌格式无效" }
            value.content.takeIf(String::isNotBlank)
        }
        return Page(models, token)
    }

    fun fetch(
        provider: GeminiProviderSetting,
        controller: AgentRunController,
        maxBytes: Long,
        execute: (Request, Long) -> String,
    ): List<Model> {
        if (provider.isVertexAi) {
            throw IllegalStateException("Vertex AI API Key 不支持拉取远端模型列表，请使用内置预设或手动添加模型。")
        }
        val models = linkedMapOf<String, Model>()
        val seenTokens = mutableSetOf<String>()
        var nextPageToken: String? = null
        var remainingBytes = maxBytes
        do {
            controller.throwIfCancelled()
            val url = ProviderUrls.geminiModelsUrl(provider.baseUrl).toHttpUrl().newBuilder()
                .setQueryParameter("pageSize", "1000")
                .apply { nextPageToken?.let { setQueryParameter("pageToken", it) } }
                .build()
            val request = Request.Builder()
                .url(url)
                .headers(okhttp3.Headers.Builder()
                    .add("Accept", "application/json")
                    .apply {
                        if (provider.apiKey.isNotBlank()) add("x-goog-api-key", provider.apiKey)
                        ProviderRequestHeaders.mergeInto(this, provider.baseUrl, provider.customHeaders)
                    }
                    .build())
                .get()
                .build()
            val body = execute(request, remainingBytes)
            remainingBytes -= body.toByteArray(Charsets.UTF_8).size
            require(remainingBytes >= 0) { "服务商模型目录超过大小限制" }
            val page = parsePage(body)
            page.models.forEach { model -> models.putIfAbsent(model.modelId.lowercase(), model) }
            val token = page.nextPageToken
            require(token == null || seenTokens.add(token)) { "Gemini 模型目录返回重复分页令牌" }
            nextPageToken = token
        } while (nextPageToken != null)
        return OfficialModelCatalog.enrich(provider, models.values.toList()).map { model ->
            model.copy(contextWindowOverride = model.contextWindow)
        }
    }

    fun testVertexConnection(
        provider: GeminiProviderSetting,
        client: OkHttpClient,
        controller: AgentRunController,
    ): VertexConnectionTestResult {
        require(provider.apiKey.isNotBlank()) { "请先填写 API Key" }
        val probeModel = provider.models.firstOrNull { it.isEnabled }?.modelId?.trim()?.takeIf(String::isNotBlank)
            ?: provider.models.firstOrNull()?.modelId?.trim()?.takeIf(String::isNotBlank)
            ?: DEFAULT_VERTEX_PROBE_MODEL
        val initialUrl = ProviderUrls.vertexCountTokensUrl(
            baseUrl = provider.baseUrl,
            model = probeModel,
            project = provider.vertexProject,
            location = provider.vertexLocation,
        )
        val allowFallback = provider.vertexProject.isBlank() &&
            !provider.baseUrl.contains("/projects/", ignoreCase = true)
        val fallbackProject = executeVertexProbe(provider, client, controller, initialUrl, allowFallback)
        if (fallbackProject != null) {
            val fallbackUrl = ProviderUrls.vertexGlobalProjectFallbackUrl(
                baseUrl = provider.baseUrl,
                model = probeModel,
                project = fallbackProject,
                suffix = ":countTokens",
            )
            executeVertexProbe(provider, client, controller, fallbackUrl, allowFallback = false)
            return VertexConnectionTestResult(modelId = probeModel, usedGlobalFallback = true)
        }
        return VertexConnectionTestResult(modelId = probeModel, usedGlobalFallback = false)
    }

    private fun executeVertexProbe(
        provider: GeminiProviderSetting,
        client: OkHttpClient,
        controller: AgentRunController,
        url: String,
        allowFallback: Boolean,
    ): String? {
        controller.throwIfCancelled()
        val request = Request.Builder()
            .url(url)
            .headers(
                okhttp3.Headers.Builder()
                    .add("Accept", "application/json")
                    .add("Content-Type", "application/json; charset=utf-8")
                    .apply {
                        if (provider.apiKey.isNotBlank()) add("x-goog-api-key", provider.apiKey.trim())
                        ProviderRequestHeaders.mergeInto(this, provider.baseUrl, provider.customHeaders)
                    }
                    .build(),
            )
            .post(VERTEX_PROBE_BODY.toRequestBody(jsonMediaType))
            .build()
        val call = client.newCall(request)
        val binding = controller.register { call.cancel() }
        try {
            call.execute().use { response ->
                controller.throwIfCancelled()
                if (response.isSuccessful) return null
                val errorBody = response.peekBody(16_384).string()
                if (allowFallback && response.code == 404) {
                    ProviderUrls.extractVertexGlobalFallbackProject(errorBody)?.let { return it }
                }
                throw AgentModelFailure.http(response.code, errorBody, includeParameterDetails = true)
            }
        } finally {
            binding.close()
        }
    }

    private fun JsonObject.string(name: String): String? =
        (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun JsonObject.positiveInt(name: String): Int? =
        (this[name] as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 }

    private fun JsonObject.stringList(name: String): List<String>? =
        (this[name] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
}
