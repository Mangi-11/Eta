package io.github.mangi.eta.data.repository

import io.github.mangi.eta.agent.model.AgentHttpClient
import io.github.mangi.eta.agent.model.ProviderRequestHeaders
import io.github.mangi.eta.agent.model.ProviderUrls
import io.github.mangi.eta.data.model.AnthropicProviderSetting
import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.model.ModelReasoningCapabilities
import io.github.mangi.eta.data.model.ModelSource
import io.github.mangi.eta.data.model.ProviderSetting
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.data.provider.OfficialModelCatalog
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
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
        val data = json.parseToJsonElement(body)
            .jsonObjectOrNull()
            ?.get("data")
            ?.jsonArrayOrNull()
            ?: return emptyList()
        return data.mapNotNull { element ->
            element.jsonObjectOrNull()?.toModel(defaultOwnedBy = null)
        }
    }

    internal fun parseAnthropicModels(body: String): List<Model> {
        val data = json.parseToJsonElement(body)
            .jsonObjectOrNull()
            ?.get("data")
            ?.jsonArrayOrNull()
            ?: return emptyList()
        return data.mapNotNull { element ->
            element.jsonObjectOrNull()?.toAnthropicModel()
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
                        ProviderRequestHeaders.mergeInto(this, provider.baseUrl, provider.customHeaders)
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
                        ProviderRequestHeaders.mergeInto(this, provider.baseUrl, provider.customHeaders)
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

    /**
     * 判断远端目录中的模型是否可用于 Agent 对话。
     *
     * OpenAI 兼容平台的 /models 会混入语音识别、语音合成、图像/视频生成、
     * embedding、rerank 等非对话模型（例如阿里百炼一次返回数百个）。这些模型
     * 无法参与 Agent 的文本工具调用循环，拉取时按 id 命名特征与输出模态过滤掉。
     */
    internal fun isChatCapableModel(model: Model): Boolean {
        if (model.outputModalities.isNotEmpty() &&
            model.outputModalities.none { it.equals(Model.TEXT_MODALITY, ignoreCase = true) }
        ) {
            return false
        }
        val id = model.modelId.lowercase()
        return NON_CHAT_MODEL_ID_MARKERS.none { it in id }
    }

    private val NON_CHAT_MODEL_ID_MARKERS = listOf(
        // 语音识别
        "asr", "whisper", "paraformer", "sensevoice", "gummy",
        // 语音合成与声音模型
        "tts", "speech", "voice", "cosyvoice", "sambert",
        // 向量与排序
        "embedding", "rerank",
        // 图像生成与理解外的图像专用模型
        "image", "dall-e", "flux", "stable-diffusion", "wanx", "hidream",
        // 视频生成
        "video", "veo-",
        // 其他非对话专用模型
        "ocr", "music", "moderation",
    )

    private fun JsonObject.toModel(defaultOwnedBy: String?): Model? {
        val modelId = string("id")?.trim().orEmpty()
        if (modelId.isBlank()) return null
        val architecture = this["architecture"]?.jsonObjectOrNull()
        val supportedParameters = stringList("supported_parameters", "supportedParameters").orEmpty()
        val reasoningMetadata = this["reasoning"]?.jsonObjectOrNull()
        return Model(
            id = UUID.randomUUID().toString(),
            modelId = modelId,
            displayName = string("display_name", "displayName", "name")?.trim().takeUnless { it.isNullOrBlank() }
                ?: modelId,
            source = ModelSource.REMOTE,
            ownedBy = string("owned_by", "ownedBy")?.trim().takeUnless { it.isNullOrBlank() } ?: defaultOwnedBy,
            contextWindow = positiveInt(
                "context_window",
                "contextWindow",
                "context_length",
                "contextLength",
                "context_limit",
                "contextLimit",
                "max_context_tokens",
                "max_input_tokens",
                "maxInputTokens",
            ),
            inputModalities = inputModalities(architecture),
            outputModalities = stringList("output_modalities", "outputModalities")
                ?: architecture?.stringList("output_modalities", "outputModalities")
                ?: emptyList(),
            attachment = boolean("attachment", "vision", "supports_image_in"),
            toolCall = boolean("tool_call", "toolCall", "tools")
                ?: supportedParameters.supportsAny("tools"),
            reasoning = boolean("reasoning", "thinking", "supports_reasoning")
                ?: supportedParameters.supportsAny(
                    "reasoning",
                    "reasoning_effort",
                    "include_reasoning",
                    "enable_thinking",
                    "thinking_budget",
                )
                ?: reasoningMetadata?.let { true },
            reasoningCapabilities = parseReasoningCapabilities(
                metadata = reasoningMetadata,
                supportedParameters = supportedParameters,
            ),
            structuredOutput = boolean("structured_output", "structuredOutput")
                ?: supportedParameters.supportsAny("structured_outputs", "response_format"),
            supportsTemperature = boolean("supports_temperature", "supportsTemperature")
                ?: supportedParameters.supportsAny("temperature"),
        )
    }

    private fun JsonObject.toAnthropicModel(): Model? {
        val base = toModel(defaultOwnedBy = "anthropic") ?: return null
        val capabilities = this["capabilities"]?.jsonObjectOrNull()
        val effort = capabilities?.get("effort")?.jsonObjectOrNull()
        val thinking = capabilities?.get("thinking")?.jsonObjectOrNull()
        val supportedEfforts = listOf(
            "low" to ReasoningEffort.LOW,
            "medium" to ReasoningEffort.MEDIUM,
            "high" to ReasoningEffort.HIGH,
            "xhigh" to ReasoningEffort.XHIGH,
            "max" to ReasoningEffort.MAX,
        ).mapNotNull { (field, value) ->
            value.takeIf { effort?.capabilitySupported(field) == true }
        }
        val effortSupported = effort?.boolean("supported") == true || supportedEfforts.isNotEmpty()
        val thinkingSupported = thinking?.boolean("supported") == true
        val reasoningSupported = effortSupported || thinkingSupported
        return base.copy(
            contextWindow = positiveInt("max_input_tokens") ?: base.contextWindow,
            attachment = capabilities?.capabilitySupported("image_input") ?: base.attachment,
            reasoning = reasoningSupported.takeIf { capabilities != null } ?: base.reasoning,
            reasoningCapabilities = if (reasoningSupported) {
                ModelReasoningCapabilities(
                    supportedEfforts = supportedEfforts,
                    defaultEffort = ReasoningEffort.HIGH.takeIf {
                        ReasoningEffort.HIGH in supportedEfforts
                    },
                    defaultEnabled = true,
                    canDisable = thinkingSupported,
                )
            } else {
                base.reasoningCapabilities
            },
            structuredOutput = capabilities?.capabilitySupported("structured_outputs")
                ?: base.structuredOutput,
        )
    }

    private fun JsonObject.parseReasoningCapabilities(
        metadata: JsonObject?,
        supportedParameters: List<String>,
    ): ModelReasoningCapabilities? {
        val supportsBudget = supportedParameters.any {
            it == "thinking_budget" || it == "reasoning_budget"
        }
        val supportsToggle = "enable_thinking" in supportedParameters
        if (metadata == null && !supportsBudget && !supportsToggle) return null
        val supportedEfforts = metadata
            ?.stringList("supported_efforts", "supportedEfforts")
            .orEmpty()
            .mapNotNull(ReasoningEffort::fromWireValue)
            .filter { it != ReasoningEffort.DEFAULT }
            .ifEmpty {
                if (supportsBudget) {
                    listOf(
                        ReasoningEffort.LOW,
                        ReasoningEffort.MEDIUM,
                        ReasoningEffort.HIGH,
                        ReasoningEffort.XHIGH,
                        ReasoningEffort.MAX,
                    )
                } else {
                    emptyList()
                }
            }
        val mandatory = metadata?.boolean("mandatory") == true
        return ModelReasoningCapabilities(
            supportedEfforts = supportedEfforts.filter { it != ReasoningEffort.OFF },
            defaultEffort = ReasoningEffort.fromWireValue(
                metadata?.string("default_effort", "defaultEffort")
            ),
            defaultEnabled = metadata?.boolean("default_enabled", "defaultEnabled"),
            mandatory = mandatory,
            canDisable = !mandatory && (
                metadata != null ||
                    supportsToggle ||
                    supportedEfforts.contains(ReasoningEffort.OFF)
                ),
            supportsBudget = supportsBudget,
            maxBudgetTokens = metadata?.positiveInt(
                "max_budget_tokens",
                "maxBudgetTokens",
                "max_reasoning_tokens",
            ),
            supportsMaxTokens = metadata?.boolean("supports_max_tokens", "supportsMaxTokens"),
        )
    }

    private fun JsonObject.string(vararg names: String): String? =
        names.firstNotNullOfOrNull { name -> (this[name] as? JsonPrimitive)?.contentOrNull }

    private fun JsonObject.int(vararg names: String): Int? =
        names.firstNotNullOfOrNull { name -> (this[name] as? JsonPrimitive)?.intOrNull }

    private fun JsonObject.positiveInt(vararg names: String): Int? =
        int(*names)?.takeIf { it > 0 }

    private fun JsonObject.boolean(vararg names: String): Boolean? =
        names.firstNotNullOfOrNull { name -> (this[name] as? JsonPrimitive)?.booleanOrNull }

    private fun JsonObject.stringList(vararg names: String): List<String>? =
        names.firstNotNullOfOrNull { name ->
            this[name]
                ?.jsonArrayOrNull()
                ?.mapNotNull { item -> (item as? JsonPrimitive)?.contentOrNull?.trim() }
                ?.filter { it.isNotBlank() }
                ?.takeIf { it.isNotEmpty() }
        }

    private fun JsonObject.capabilitySupported(name: String): Boolean? =
        this[name]
            ?.jsonObjectOrNull()
            ?.boolean("supported")

    private fun List<String>.supportsAny(vararg names: String): Boolean? =
        takeIf { supported -> names.any(supported::contains) }?.let { true }

    /**
     * 空列表表示远端没有提供输入模态元数据，后续才允许官方目录补齐。
     *
     * 不能把缺失字段直接折叠成 text：否则无法区分“远端明确声明仅文本”和
     * “标准 /models 根本未返回能力字段”，官方目录会错误覆盖前一种情况。
     */
    private fun JsonObject.inputModalities(architecture: JsonObject?): List<String> {
        stringList("input_modalities", "inputModalities")?.let { return it }
        architecture?.stringList("input_modalities", "inputModalities")?.let { return it }
        val capabilityNames = listOf(
            "attachment",
            "vision",
            "supports_image_in",
            "supports_video_in",
        )
        if (capabilityNames.none(::containsKey)) return emptyList()
        return buildList {
            add(Model.TEXT_MODALITY)
            if (boolean("attachment", "vision", "supports_image_in") == true) {
                add(Model.IMAGE_MODALITY)
            }
            if (boolean("supports_video_in") == true) {
                add("video")
            }
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
