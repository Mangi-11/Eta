package io.github.mangi.eta.data.repository.catalog

import io.github.mangi.eta.R
import io.github.mangi.eta.agent.model.provider.AgentHttpClient
import io.github.mangi.eta.agent.model.provider.AgentModelFailure
import io.github.mangi.eta.agent.model.provider.ProviderRequestHeaders
import io.github.mangi.eta.agent.model.provider.ProviderUrls
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.core.AppStrings
import io.github.mangi.eta.data.model.GeminiProviderSetting
import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.model.ModelSource
import io.github.mangi.eta.data.model.selectedOrFirstModel
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
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

internal object GeminiModelCatalog {
    private val json = Json { ignoreUnknownKeys = true }
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private const val VERTEX_PROBE_BODY = """{"contents":[{"role":"user","parts":[{"text":"ping"}]}]}"""

    data class Page(val models: List<Model>, val nextPageToken: String?)

    fun parsePage(body: String): Page {
        val root = try {
            json.parseToJsonElement(body) as? JsonObject ?: error(
                AppStrings.get(
                    R.string.repo_err_gemini_catalog_invalid_format,
                    "Gemini 模型目录格式无效",
                ),
            )
        } catch (_: SerializationException) {
            error(
                AppStrings.get(
                    R.string.repo_err_gemini_catalog_invalid_json,
                    "Gemini 模型目录不是有效的 JSON",
                ),
            )
        }
        require(root["error"] == null || root["error"] == JsonNull) {
            AppStrings.get(
                R.string.repo_err_gemini_catalog_error,
                "Gemini 返回了模型目录错误",
            )
        }
        val entries = (root["models"] as? JsonArray) ?: error(
            AppStrings.get(
                R.string.repo_err_gemini_catalog_missing_models,
                "Gemini 模型目录缺少 models 列表",
            ),
        )
        val models = entries.mapNotNull { entry ->
            val row = (entry as? JsonObject) ?: error(
                AppStrings.get(
                    R.string.repo_err_gemini_catalog_invalid_entry,
                    "Gemini 模型目录包含无效模型项",
                ),
            )
            val modelId = row.string("name")?.trim()?.removePrefix("models/")
                ?.takeIf(String::isNotBlank) ?: error(
                AppStrings.get(
                    R.string.repo_err_gemini_catalog_missing_model_name,
                    "Gemini 模型目录缺少模型名称",
                ),
            )
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
            require(value is JsonPrimitive && value.isString) {
                AppStrings.get(
                    R.string.repo_err_gemini_catalog_invalid_page_token,
                    "Gemini 模型目录分页令牌格式无效",
                )
            }
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
            throw IllegalStateException(
                AppStrings.get(
                    R.string.repo_err_vertex_remote_fetch_unsupported,
                    "Vertex AI API Key 不支持拉取远端模型列表，请使用内置预设或手动添加模型。",
                ),
            )
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
            require(remainingBytes >= 0) {
                AppStrings.get(
                    R.string.repo_err_provider_catalog_size_limit,
                    "服务商模型目录超过大小限制",
                )
            }
            val page = parsePage(body)
            page.models.forEach { model -> models.putIfAbsent(model.modelId.lowercase(), model) }
            val token = page.nextPageToken
            require(token == null || seenTokens.add(token)) {
                AppStrings.get(
                    R.string.repo_err_gemini_catalog_duplicate_page_token,
                    "Gemini 模型目录返回重复分页令牌",
                )
            }
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
        preferredModelId: String?,
    ): String {
        require(provider.apiKey.isNotBlank()) {
            AppStrings.get(R.string.repo_err_api_key_required, "请先填写 API Key")
        }
        ProviderUrls.validateVertexChatEndpoint(provider.baseUrl, provider.vertexProject, provider.vertexLocation)
        val model = if (provider.models.isEmpty()) OfficialModelCatalog.modelsForProvider(provider).firstOrNull()
            else provider.selectedOrFirstModel(preferredModelId)
        requireNotNull(model) {
            AppStrings.get(
                R.string.repo_err_vertex_model_required,
                "请先保存连接并为当前地域添加或启用一个 Gemini 4 模型。",
            )
        }
        val url = ProviderUrls.vertexCountTokensUrl(
            baseUrl = provider.baseUrl,
            model = model.modelId,
            project = provider.vertexProject,
            location = provider.vertexLocation,
        )
        controller.throwIfCancelled()
        val request = Request.Builder()
            .url(url)
            .headers(
                Headers.Builder().apply {
                    add("x-goog-api-key", provider.apiKey.trim())
                    ProviderRequestHeaders.mergeInto(this, provider.baseUrl, provider.customHeaders + model.customHeaders)
                    set("Accept", "application/json")
                    set("Content-Type", "application/json; charset=utf-8")
                }.build(),
            )
            .post(VERTEX_PROBE_BODY.toRequestBody(jsonMediaType))
            .build()
        val call = AgentHttpClient.vertexClient(client).newCall(request)
        val binding = controller.register(call::cancel)
        try {
            call.execute().use { response ->
                controller.throwIfCancelled()
                val body = response.peekBody(16_384).string()
                if (!response.isSuccessful) {
                    throw AgentModelFailure.http(response.code, body, includeParameterDetails = true)
                }
                val valid = runCatching { JSONObject(body).opt("totalTokens") is Number }.getOrDefault(false)
                if (!valid) {
                    throw AgentModelFailure(
                        "VERTEX_INVALID_TOKEN_COUNT",
                        false,
                        AppStrings.get(
                            R.string.repo_err_vertex_invalid_token_count,
                            "Vertex AI 返回了无效的 token 计数响应。",
                        ),
                    )
                }
                return GeminiModelCapabilities.normalize(model.modelId)
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
