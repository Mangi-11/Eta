package fuck.andes.data.repository

import fuck.andes.agent.model.AgentHttpClient
import fuck.andes.agent.model.CustomHeaderFilter
import fuck.andes.agent.model.ProviderUrls
import fuck.andes.data.model.AnthropicProviderSetting
import fuck.andes.data.model.Model
import fuck.andes.data.model.ProviderSetting
import fuck.andes.data.provider.OfficialModelCatalog
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request

internal object RemoteModelFetcher {
    private const val MAX_ERROR_CHARS = 600
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun fetch(provider: ProviderSetting): Result<List<Model>> =
        withContext(Dispatchers.IO) {
            runCatching {
                when (provider) {
                    is AnthropicProviderSetting -> fetchAnthropic(provider)
                    else -> fetchOpenAiCompatible(provider)
                }
            }
        }

    internal fun parseOpenAiModels(body: String): List<Model> {
        val data = json.parseToJsonElement(body).jsonObject["data"]?.jsonArray ?: return emptyList()
        return data.mapNotNull { element ->
            element.jsonObjectOrNull()?.toModel(defaultOwnedBy = null)
        }
    }

    internal fun parseAnthropicModels(body: String): List<Model> {
        val data = json.parseToJsonElement(body).jsonObject["data"]?.jsonArray ?: return emptyList()
        return data.mapNotNull { element ->
            element.jsonObjectOrNull()?.toModel(defaultOwnedBy = "anthropic")
        }
    }

    private fun fetchOpenAiCompatible(provider: ProviderSetting): List<Model> {
        val request = Request.Builder()
            .url(ProviderUrls.openAiModelsUrl(provider.baseUrl))
            .headers(
                okhttp3.Headers.Builder()
                    .add("Accept", "application/json")
                    .apply {
                        if (provider.apiKey.isNotBlank()) {
                            add("Authorization", "Bearer ${provider.apiKey}")
                        }
                        CustomHeaderFilter.mergeInto(this, provider.customHeaders)
                    }
                    .build()
            )
            .get()
            .build()
        return OfficialModelCatalog.enrich(provider, executeJson(request, "拉取模型失败").let(::parseOpenAiModels))
    }

    private fun fetchAnthropic(provider: AnthropicProviderSetting): List<Model> {
        val request = Request.Builder()
            .url(ProviderUrls.anthropicModelsUrl(provider.baseUrl))
            .headers(
                okhttp3.Headers.Builder()
                    .add("Accept", "application/json")
                    .add("anthropic-version", provider.anthropicVersion)
                    .apply {
                        if (provider.apiKey.isNotBlank()) {
                            add("x-api-key", provider.apiKey)
                        }
                        CustomHeaderFilter.mergeInto(this, provider.customHeaders)
                    }
                    .build()
            )
            .get()
            .build()
        return OfficialModelCatalog.enrich(provider, executeJson(request, "拉取 Anthropic 模型失败").let(::parseAnthropicModels))
    }

    private fun executeJson(request: Request, errorPrefix: String): String =
        AgentHttpClient.client.newCall(request).execute().use { response ->
            val body = response.body.string()
            if (!response.isSuccessful) {
                error("$errorPrefix HTTP ${response.code}: ${body.compactError()}")
            }
            body
        }

    private fun JsonObject.toModel(defaultOwnedBy: String?): Model? {
        val modelId = string("id")?.trim().orEmpty()
        if (modelId.isBlank()) return null
        return Model(
            id = UUID.randomUUID().toString(),
            modelId = modelId,
            displayName = string("display_name", "displayName", "name")?.trim().takeUnless { it.isNullOrBlank() }
                ?: modelId,
            ownedBy = string("owned_by", "ownedBy")?.trim().takeUnless { it.isNullOrBlank() } ?: defaultOwnedBy,
            contextWindow = int(
                "context_window",
                "contextWindow",
                "context_length",
                "contextLength",
                "context_limit",
                "contextLimit",
                "max_context_tokens",
            ),
            inputModalities = stringList("input_modalities", "inputModalities")
                ?: modalitiesFromCapabilityFlags(),
            outputModalities = stringList("output_modalities", "outputModalities")
                ?: listOf(Model.TEXT_MODALITY),
            attachment = boolean("attachment", "vision", "supports_image_in"),
            toolCall = boolean("tool_call", "toolCall", "tools"),
            reasoning = boolean("reasoning", "thinking", "supports_reasoning"),
            structuredOutput = boolean("structured_output", "structuredOutput"),
            supportsTemperature = boolean("supports_temperature", "supportsTemperature"),
        )
    }

    private fun JsonObject.string(vararg names: String): String? =
        names.firstNotNullOfOrNull { name -> this[name]?.jsonPrimitive?.contentOrNull }

    private fun JsonObject.int(vararg names: String): Int? =
        names.firstNotNullOfOrNull { name -> this[name]?.jsonPrimitive?.intOrNull }

    private fun JsonObject.boolean(vararg names: String): Boolean? =
        names.firstNotNullOfOrNull { name -> this[name]?.jsonPrimitive?.booleanOrNull }

    private fun JsonObject.stringList(vararg names: String): List<String>? =
        names.firstNotNullOfOrNull { name ->
            this[name]
                ?.jsonArrayOrNull()
                ?.mapNotNull { item -> item.jsonPrimitive.contentOrNull?.trim() }
                ?.filter { it.isNotBlank() }
                ?.takeIf { it.isNotEmpty() }
        }

    private fun JsonObject.modalitiesFromCapabilityFlags(): List<String> =
        buildList {
            add(Model.TEXT_MODALITY)
            if (boolean("attachment", "vision", "supports_image_in") == true) {
                add(Model.IMAGE_MODALITY)
            }
            if (boolean("supports_video_in") == true) {
                add("video")
            }
        }

    private fun JsonElement.jsonObjectOrNull(): JsonObject? =
        runCatching { jsonObject }.getOrNull()

    private fun JsonElement.jsonArrayOrNull(): JsonArray? =
        runCatching { jsonArray }.getOrNull()

    private fun String.compactError(): String =
        replace('\n', ' ')
            .replace('\r', ' ')
            .let { if (it.length > MAX_ERROR_CHARS) it.take(MAX_ERROR_CHARS) + "..." else it }
}
