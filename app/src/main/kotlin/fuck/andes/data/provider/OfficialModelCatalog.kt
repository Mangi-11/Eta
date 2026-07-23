package fuck.andes.data.provider

import fuck.andes.data.model.Model
import fuck.andes.data.model.ModelSource
import fuck.andes.data.model.ProviderSetting
import fuck.andes.data.model.ProviderSourceTypes

internal object OfficialModelCatalog {
    private val modelsByCatalogId: Map<String, List<Model>> = mapOf(
        ProviderSourceTypes.OPENAI to listOf(
            officialModel(
                id = "builtin-openai-gpt-5-5",
                modelId = "gpt-5.5",
                displayName = "GPT-5.5",
                ownedBy = "openai",
                inputModalities = listOf(Model.TEXT_MODALITY, Model.IMAGE_MODALITY),
                toolCall = true,
                reasoning = true,
            )
        ),
        ProviderSourceTypes.ANTHROPIC to listOf(
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
                id = "builtin-bailian-kimi-k2-7-code",
                modelId = "kimi-k2.7-code",
                displayName = "Kimi K2.7 Code",
                ownedBy = "moonshot",
                toolCall = true,
                reasoning = true,
                contextWindow = 256_000,
            ),
            officialModel(
                id = "builtin-bailian-kimi-k2-6",
                modelId = "kimi-k2.6",
                displayName = "Kimi K2.6",
                ownedBy = "moonshot",
                inputModalities = listOf(Model.TEXT_MODALITY, Model.IMAGE_MODALITY),
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
                contextWindow = 1_000_000,
            ),
            officialModel(
                id = "builtin-deepseek-v4-flash",
                modelId = "deepseek-v4-flash",
                displayName = "DeepSeek V4 Flash",
                ownedBy = "deepseek",
                toolCall = true,
                reasoning = true,
                contextWindow = 1_000_000,
            ),
        ),
        ProviderSourceTypes.MOONSHOT to listOf(
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
            officialModel(
                id = "builtin-kimi-k2-5",
                modelId = "kimi-k2.5",
                displayName = "Kimi K2.5",
                ownedBy = "moonshot",
                inputModalities = listOf(Model.TEXT_MODALITY, Model.IMAGE_MODALITY),
                toolCall = true,
                reasoning = true,
                structuredOutput = true,
                contextWindow = 256_000,
            ),
        ),
        ProviderSourceTypes.MIMO to listOf(
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
                inputModalities = listOf(Model.TEXT_MODALITY, Model.IMAGE_MODALITY),
                toolCall = true,
                reasoning = true,
                contextWindow = 1_000_000,
            ),
        ),
        ProviderSourceTypes.STEPFUN to listOf(
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
        modelsForCatalogId(catalogIdFor(provider)).withStableSortOrder()

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
                inputModalities = model.inputModalities.takeUnless {
                    it.isEmpty() || (it == listOf(Model.TEXT_MODALITY) && official.inputModalities != it)
                } ?: official.inputModalities,
                outputModalities = model.outputModalities.takeUnless {
                    it.isEmpty() || (it == listOf(Model.TEXT_MODALITY) && official.outputModalities != it)
                } ?: official.outputModalities,
                attachment = model.attachment ?: official.attachment,
                toolCall = model.toolCall ?: official.toolCall,
                reasoning = model.reasoning ?: official.reasoning,
                structuredOutput = model.structuredOutput ?: official.structuredOutput,
                supportsTemperature = model.supportsTemperature ?: official.supportsTemperature,
            )
        }
    }

    private fun modelsForCatalogId(catalogId: String?): List<Model> =
        modelsByCatalogId[catalogId].orEmpty()

    private fun List<Model>.withStableSortOrder(): List<Model> =
        mapIndexed { index, model -> model.copy(sortOrder = index) }

    private fun catalogIdFor(provider: ProviderSetting): String? =
        ProviderSourceRegistry.resolve(provider)
            .takeUnless { it == ProviderSourceTypes.CUSTOM }

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
