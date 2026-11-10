package io.github.mangi.eta.data.repository

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
import okhttp3.Request

internal object GeminiModelCatalog {
    private val json = Json { ignoreUnknownKeys = true }

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

    private fun JsonObject.string(name: String): String? =
        (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun JsonObject.positiveInt(name: String): Int? =
        (this[name] as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 }

    private fun JsonObject.stringList(name: String): List<String>? =
        (this[name] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
}
