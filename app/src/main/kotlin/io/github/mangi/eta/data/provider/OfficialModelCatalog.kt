package io.github.mangi.eta.data.provider

import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.model.ModelSource
import io.github.mangi.eta.data.model.CustomProviderSetting
import io.github.mangi.eta.data.model.OpenAiCompatibleProviderSetting
import io.github.mangi.eta.data.model.OpenAiEndpointMode
import io.github.mangi.eta.data.model.ProviderSetting
import io.github.mangi.eta.data.model.ProviderSourceTypes

internal object OfficialModelCatalog {
    internal const val CURRENT_REVISION = 3

    // 旧安装只补入本次新增的模型，避免恢复用户主动删除的旧预设。
    private val additionsByRevision = mapOf(
        1 to mapOf(
            ProviderSourceTypes.OPENAI to setOf("gpt-6-astra", "gpt-6-sol", "gpt-6-luna"),
            ProviderSourceTypes.ANTHROPIC to setOf("claude-fable-5-1", "claude-opus-5-5"),
            ProviderSourceTypes.BAILIAN to setOf(
                "qwen3.8-max", "qwen3.8-flash", "deepseek-v4.1-flash", "kimi-k3",
            ),
            ProviderSourceTypes.DEEPSEEK to setOf("deepseek-flash"),
            ProviderSourceTypes.MIMO to setOf("mimo-v2.6-pro", "mimo-v2.6-flash"),
            ProviderSourceTypes.STEPFUN to setOf("step-5-preview"),
        ),
        2 to mapOf(ProviderSourceTypes.OPENAI to setOf("gpt-6.1-sol")),
    )

    private val modelsByCatalogId: Map<String, List<Model>> = mapOf(
        ProviderSourceTypes.OPENAI to listOf(
            officialModel(
                id = "builtin-openai-gpt-6-1-sol",
                modelId = "gpt-6.1-sol",
                displayName = "GPT-6.1 Sol",
                ownedBy = "openai",
                inputModalities = listOf(Model.TEXT_MODALITY, Model.IMAGE_MODALITY),
                toolCall = true,
                reasoning = true,
                structuredOutput = true,
                contextWindow = 1_050_000,
            ),
            officialModel(
                id = "builtin-openai-gpt-6-luna",
                modelId = "gpt-6-luna",
                displayName = "GPT-6 Luna",
                ownedBy = "openai",
                inputModalities = listOf(Model.TEXT_MODALITY, Model.IMAGE_MODALITY),
                toolCall = true,
                reasoning = true,
                structuredOutput = true,
                contextWindow = 1_050_000,
            ),
            officialModel(
                id = "builtin-openai-gpt-6-astra",
                modelId = "gpt-6-astra",
                displayName = "GPT-6 Astra",
                ownedBy = "openai",
                inputModalities = listOf(Model.TEXT_MODALITY, Model.IMAGE_MODALITY),
                toolCall = true,
                reasoning = true,
                structuredOutput = true,
                contextWindow = 1_050_000,
            ),
        ).map { it.copy(contextWindowOverride = it.contextWindow) },
        ProviderSourceTypes.ANTHROPIC to listOf(
            officialModel(
                id = "builtin-anthropic-claude-fable-5-1",
                modelId = "claude-fable-5-1",
                displayName = "Claude Fable 5.1",
                ownedBy = "anthropic",
                inputModalities = listOf(Model.TEXT_MODALITY, Model.IMAGE_MODALITY),
                toolCall = true,
                reasoning = true,
                contextWindow = 1_000_000,
            ),
            officialModel(
                id = "builtin-anthropic-claude-opus-5-5",
                modelId = "claude-opus-5-5",
                displayName = "Claude Opus 5.5",
                ownedBy = "anthropic",
                inputModalities = listOf(Model.TEXT_MODALITY, Model.IMAGE_MODALITY),
                toolCall = true,
                reasoning = true,
                contextWindow = 1_000_000,
            ),
            officialModel(
                id = "builtin-anthropic-claude-fable-5",
                modelId = "claude-fable-5",
                displayName = "Claude Fable 5",
                ownedBy = "anthropic",
                inputModalities = listOf(Model.TEXT_MODALITY, Model.IMAGE_MODALITY),
                toolCall = true,
                reasoning = true,
                contextWindow = 1_000_000,
            ),
            officialModel(
                id = "builtin-anthropic-claude-opus-4-8",
                modelId = "claude-opus-4-8",
                displayName = "Claude Opus 4.8",
                ownedBy = "anthropic",
                inputModalities = listOf(Model.TEXT_MODALITY, Model.IMAGE_MODALITY),
                toolCall = true,
                reasoning = true,
                contextWindow = 1_000_000,
            ),
            officialModel(
                id = "builtin-anthropic-claude-sonnet-5",
                modelId = "claude-sonnet-5",
                displayName = "Claude Sonnet 5",
                ownedBy = "anthropic",
                inputModalities = listOf(Model.TEXT_MODALITY, Model.IMAGE_MODALITY),
                toolCall = true,
                reasoning = true,
                contextWindow = 1_000_000,
            ),
        ),
        ProviderSourceTypes.BAILIAN to listOf(
            officialModel(
                id = "builtin-bailian-qwen3-8-max",
                modelId = "qwen3.8-max",
                displayName = "Qwen3.8 Max",
                ownedBy = "qwen",
                inputModalities = listOf(Model.TEXT_MODALITY, Model.IMAGE_MODALITY, "video"),
                toolCall = true,
                reasoning = true,
                structuredOutput = true,
                contextWindow = 1_000_000,
            ),
            officialModel(
                id = "builtin-bailian-qwen3-8-flash",
                modelId = "qwen3.8-flash",
                displayName = "Qwen3.8 Flash",
                ownedBy = "qwen",
                inputModalities = listOf(Model.TEXT_MODALITY, Model.IMAGE_MODALITY, "video"),
                toolCall = true,
                reasoning = true,
                structuredOutput = true,
                contextWindow = 1_000_000,
            ),
            officialModel(
                id = "builtin-bailian-qwen3-7-plus",
                modelId = "qwen3.7-plus",
                displayName = "Qwen3.7 Plus",
                ownedBy = "qwen",
                inputModalities = listOf(Model.TEXT_MODALITY, Model.IMAGE_MODALITY),
                toolCall = true,
                reasoning = true,
                structuredOutput = true,
                contextWindow = 1_000_000,
            ),
            officialModel(
                id = "builtin-bailian-deepseek-v4-1-flash",
                modelId = "deepseek-v4.1-flash",
                displayName = "DeepSeek V4.1 Flash",
                ownedBy = "deepseek",
                inputModalities = listOf(Model.TEXT_MODALITY, Model.IMAGE_MODALITY),
                toolCall = true,
                reasoning = true,
                structuredOutput = true,
                contextWindow = 1_000_000,
            ),
            officialModel(
                id = "builtin-bailian-kimi-k3",
                modelId = "kimi-k3",
                displayName = "Kimi K3",
                ownedBy = "moonshot",
                inputModalities = listOf(Model.TEXT_MODALITY, Model.IMAGE_MODALITY),
                toolCall = true,
                reasoning = true,
                structuredOutput = true,
                contextWindow = 1_000_000,
            ),
            officialModel(
                id = "builtin-bailian-kimi-k2-7-code",
                modelId = "kimi-k2.7-code",
                displayName = "Kimi K2.7 Code",
                ownedBy = "moonshot",
                inputModalities = listOf(Model.TEXT_MODALITY, Model.IMAGE_MODALITY, "video"),
                toolCall = true,
                reasoning = true,
                contextWindow = 256_000,
            ),
            officialModel(
                id = "builtin-bailian-kimi-k2-6",
                modelId = "kimi-k2.6",
                displayName = "Kimi K2.6",
                ownedBy = "moonshot",
                inputModalities = listOf(Model.TEXT_MODALITY, Model.IMAGE_MODALITY, "video"),
                toolCall = true,
                reasoning = true,
                contextWindow = 256_000,
            ),
        ),
        ProviderSourceTypes.DEEPSEEK to listOf(
            officialModel(
                id = "builtin-deepseek-v4-pro",
                modelId = "deepseek-v4-pro",
                displayName = "DeepSeek V4 Pro",
                ownedBy = "deepseek",
                toolCall = true,
                reasoning = true,
                structuredOutput = true,
                contextWindow = 1_048_576,
            ),
            officialModel(
                id = "builtin-deepseek-flash",
                modelId = "deepseek-flash",
                displayName = "DeepSeek Flash",
                ownedBy = "deepseek",
                inputModalities = listOf(Model.TEXT_MODALITY, Model.IMAGE_MODALITY),
                toolCall = true,
                reasoning = true,
                structuredOutput = true,
                contextWindow = 1_048_576,
            ),
        ),
        ProviderSourceTypes.MOONSHOT to listOf(
            officialModel(
                id = "builtin-kimi-k3",
                modelId = "kimi-k3",
                displayName = "Kimi K3",
                ownedBy = "moonshot",
                inputModalities = listOf(Model.TEXT_MODALITY, Model.IMAGE_MODALITY, "video"),
                toolCall = true,
                reasoning = true,
                structuredOutput = true,
                contextWindow = 1_048_576,
            ),
            officialModel(
                id = "builtin-kimi-k2-7-code",
                modelId = "kimi-k2.7-code",
                displayName = "Kimi K2.7 Code",
                ownedBy = "moonshot",
                inputModalities = listOf(Model.TEXT_MODALITY, Model.IMAGE_MODALITY, "video"),
                toolCall = true,
                reasoning = true,
                structuredOutput = true,
                contextWindow = 256_000,
            ),
            officialModel(
                id = "builtin-kimi-k2-7-code-highspeed",
                modelId = "kimi-k2.7-code-highspeed",
                displayName = "Kimi K2.7 Code HighSpeed",
                ownedBy = "moonshot",
                inputModalities = listOf(Model.TEXT_MODALITY, Model.IMAGE_MODALITY, "video"),
                toolCall = true,
                reasoning = true,
                structuredOutput = true,
                contextWindow = 256_000,
            ),
            officialModel(
                id = "builtin-kimi-k2-6",
                modelId = "kimi-k2.6",
                displayName = "Kimi K2.6",
                ownedBy = "moonshot",
                inputModalities = listOf(Model.TEXT_MODALITY, Model.IMAGE_MODALITY, "video"),
                toolCall = true,
                reasoning = true,
                structuredOutput = true,
                contextWindow = 256_000,
            ),
        ),
        ProviderSourceTypes.MIMO to listOf(
            officialModel(
                id = "builtin-mimo-v2-6-pro",
                modelId = "mimo-v2.6-pro",
                displayName = "MiMo V2.6 Pro",
                ownedBy = "xiaomi",
                inputModalities = listOf(Model.TEXT_MODALITY, Model.IMAGE_MODALITY, "video", "audio"),
                toolCall = true,
                reasoning = true,
                structuredOutput = true,
                contextWindow = 1_000_000,
            ),
            officialModel(
                id = "builtin-mimo-v2-6-flash",
                modelId = "mimo-v2.6-flash",
                displayName = "MiMo V2.6 Flash",
                ownedBy = "xiaomi",
                inputModalities = listOf(Model.TEXT_MODALITY, Model.IMAGE_MODALITY, "video", "audio"),
                toolCall = true,
                reasoning = true,
                structuredOutput = true,
                contextWindow = 1_000_000,
            ),
            officialModel(
                id = "builtin-mimo-v2-5-pro",
                modelId = "mimo-v2.5-pro",
                displayName = "MiMo V2.5 Pro",
                ownedBy = "xiaomi",
                toolCall = true,
                reasoning = true,
                structuredOutput = true,
                contextWindow = 1_000_000,
            ),
            officialModel(
                id = "builtin-mimo-v2-5",
                modelId = "mimo-v2.5",
                displayName = "MiMo V2.5",
                ownedBy = "xiaomi",
                inputModalities = listOf(Model.TEXT_MODALITY, Model.IMAGE_MODALITY, "video", "audio"),
                toolCall = true,
                reasoning = true,
                structuredOutput = true,
                contextWindow = 1_000_000,
            ),
        ),
        ProviderSourceTypes.MINIMAX to listOf(
            officialModel(
                id = "builtin-minimax-m3",
                modelId = "MiniMax-M3",
                displayName = "MiniMax M3",
                ownedBy = "minimax",
                inputModalities = listOf(Model.TEXT_MODALITY, Model.IMAGE_MODALITY, "video"),
                toolCall = true,
                reasoning = true,
                contextWindow = 1_000_000,
            ),
        ),
        ProviderSourceTypes.STEPFUN to listOf(
            officialModel(
                id = "builtin-stepfun-step-5-preview",
                modelId = "step-5-preview",
                displayName = "Step 5 Preview",
                ownedBy = "stepfun",
                inputModalities = listOf(Model.TEXT_MODALITY, Model.IMAGE_MODALITY, "video"),
                toolCall = true,
                reasoning = true,
                structuredOutput = true,
                contextWindow = 1_000_000,
            ),
            officialModel(
                id = "builtin-stepfun-step-3-7-flash",
                modelId = "step-3.7-flash",
                displayName = "Step 3.7 Flash",
                ownedBy = "stepfun",
                inputModalities = listOf(Model.TEXT_MODALITY, Model.IMAGE_MODALITY, "video"),
                toolCall = true,
                reasoning = true,
                contextWindow = 256_000,
            ),
        ),
    )

    fun modelsForProvider(provider: ProviderSetting): List<Model> =
        modelsForCatalogId(catalogIdFor(provider))
            .map { it.withCatalogReasoningCapabilities(catalogIdFor(provider)) }
            .withStableSortOrder()

    fun modelsAddedSince(provider: ProviderSetting, revision: Int): List<Model> {
        val catalogId = catalogIdFor(provider) ?: return emptyList()
        val addedIds = additionsByRevision
            .filterKeys { it > revision }
            .values
            .flatMap { it[catalogId].orEmpty() }
            .toSet()
        return modelsForProvider(provider).filter { it.modelId in addedIds }
    }

    fun enrich(provider: ProviderSetting, models: List<Model>): List<Model> =
        enrich(catalogId = catalogIdFor(provider), models = models)

    internal fun enrich(catalogId: String?, models: List<Model>): List<Model> {
        if (catalogId == null) return models
        val officialById = modelsByCatalogId[catalogId]
            ?.associateBy { it.modelId.lowercase() }
            .orEmpty()
        if (officialById.isEmpty()) return models
        return models.map { model ->
            val official = officialById[model.modelId.lowercase()] ?: return@map model
            model.copy(
                displayName = model.displayName
                    .takeUnless { it.isBlank() || it == model.modelId }
                    ?: official.displayName,
                ownedBy = model.ownedBy ?: official.ownedBy,
                contextWindow = model.contextWindow ?: official.contextWindow,
                inputModalities = model.inputModalities.ifEmpty { official.inputModalities },
                outputModalities = model.outputModalities.ifEmpty { official.outputModalities },
                attachment = model.attachment ?: official.attachment,
                toolCall = model.toolCall ?: official.toolCall,
                reasoning = model.reasoning ?: official.reasoning,
                reasoningCapabilities = if (model.reasoning == false) {
                    null
                } else {
                    model.reasoningCapabilities
                        ?: official.withCatalogReasoningCapabilities(catalogId).reasoningCapabilities
                },
                structuredOutput = model.structuredOutput ?: official.structuredOutput,
                supportsTemperature = model.supportsTemperature ?: official.supportsTemperature,
            )
        }
    }

    private fun modelsForCatalogId(catalogId: String?): List<Model> =
        modelsByCatalogId[catalogId].orEmpty()

    private fun Model.withCatalogReasoningCapabilities(catalogId: String?): Model =
        copy(
            reasoningCapabilities = reasoningCapabilities
                ?: catalogId?.let { ReasoningCapabilityResolver.catalogCapabilities(it, modelId) }
        )

    private fun List<Model>.withStableSortOrder(): List<Model> =
        mapIndexed { index, model -> model.copy(sortOrder = index) }

    private fun catalogIdFor(provider: ProviderSetting): String? {
        val resolved = ProviderSourceRegistry.resolve(provider)
        if (resolved != ProviderSourceTypes.CUSTOM) return resolved
        val endpointMode = when (provider) {
            is OpenAiCompatibleProviderSetting -> provider.endpointMode
            is CustomProviderSetting -> provider.endpointMode
            else -> null
        }
        return ProviderSourceTypes.OPENAI.takeIf { endpointMode == OpenAiEndpointMode.RESPONSES }
    }

    private fun officialModel(
        id: String,
        modelId: String,
        displayName: String,
        ownedBy: String,
        inputModalities: List<String> = listOf(Model.TEXT_MODALITY),
        outputModalities: List<String> = listOf(Model.TEXT_MODALITY),
        contextWindow: Int? = null,
        attachment: Boolean? = null,
        toolCall: Boolean? = null,
        reasoning: Boolean? = null,
        structuredOutput: Boolean? = null,
        supportsTemperature: Boolean? = null,
    ): Model =
        Model(
            id = id,
            modelId = modelId,
            displayName = displayName,
            ownedBy = ownedBy,
            isBuiltIn = true,
            source = ModelSource.CATALOG,
            contextWindow = contextWindow,
            inputModalities = inputModalities,
            outputModalities = outputModalities,
            attachment = attachment,
            toolCall = toolCall,
            reasoning = reasoning,
            structuredOutput = structuredOutput,
            supportsTemperature = supportsTemperature,
        )
}
