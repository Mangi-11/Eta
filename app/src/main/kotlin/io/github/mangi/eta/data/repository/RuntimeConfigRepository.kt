package io.github.mangi.eta.data.repository

import android.content.SharedPreferences
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.model.GeminiProviderSetting
import io.github.mangi.eta.data.model.AnthropicProviderSetting
import io.github.mangi.eta.data.model.ConversationTitleSettings
import io.github.mangi.eta.data.model.CustomProviderSetting
import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.model.ModelReasoningCapabilities
import io.github.mangi.eta.data.model.OpenAiCompatibleProviderSetting
import io.github.mangi.eta.data.model.OpenAiEndpointMode
import io.github.mangi.eta.data.model.ProviderSetting
import io.github.mangi.eta.data.model.ProviderAuthModes
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.data.model.SubagentSettings
import io.github.mangi.eta.data.model.enabledModel
import io.github.mangi.eta.data.model.runtimeProviderType
import io.github.mangi.eta.data.model.selectedOrFirstModel
import io.github.mangi.eta.data.provider.GeminiModelCapabilities
import io.github.mangi.eta.data.provider.ProviderSourceRegistry
import io.github.mangi.eta.data.provider.ProviderConnectionProfile
import io.github.mangi.eta.data.provider.ReasoningCapabilityResolver
import io.github.libxposed.service.XposedService
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal object RuntimeConfigRepository {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun selectedProviderIdFlow() = SettingsDataStore.selectedProviderIdFlow()

    fun selectedModelIdFlow() = SettingsDataStore.selectedModelIdFlow()

    fun subagentSettingsFlow() = SettingsDataStore.subagentSettingsFlow()

    suspend fun setSubagentSettings(settings: SubagentSettings) {
        SettingsDataStore.setSubagentSettings(settings)
    }

    fun conversationTitleSettingsFlow() = SettingsDataStore.conversationTitleSettingsFlow()

    suspend fun setConversationTitleSettings(settings: ConversationTitleSettings) {
        SettingsDataStore.setConversationTitleSettings(settings)
    }

    /**
     * 解析对话标题生成所用的模型与思考配置。
     *
     * 关闭自动标题时返回 `null`；若指定了独立标题模型且仍启用则优先使用，否则平滑回退到
     * 会话当前模型（或全局默认模型）。思考等级未显式指定时默认取最低可用档位以节省 Token。
     */
    suspend fun titleGeneratorConfig(
        conversationModelId: String? = null,
    ): AgentModelClient.ModelConfig? {
        val settings = SettingsDataStore.conversationTitleSettings()
        if (!settings.enabled) return null
        val customModelId = settings.modelId?.takeIf { it.isNotBlank() }
        val base = if (customModelId != null) {
            ProviderRepository.ensureBuiltInsMerged()
            ProviderRepository.allProviders().enabledModel(customModelId)?.let { (provider, model) ->
                buildRuntimeConfig(provider, model)
            }
        } else {
            null
        } ?: runtimeConfigFor(conversationModelId) ?: return null
        return applyTitleGenerationSettings(base, settings)
    }

    internal fun applyTitleGenerationSettings(
        base: AgentModelClient.ModelConfig,
        settings: ConversationTitleSettings,
    ): AgentModelClient.ModelConfig {
        val explicitEffort = settings.resolvedReasoningEffort
        val capabilities = base.reasoningCapabilities
        val effort = when {
            explicitEffort != null -> when {
                capabilities != null -> capabilities.normalize(explicitEffort)
                explicitEffort == ReasoningEffort.OFF -> ReasoningEffort.OFF
                else -> base.effectiveReasoningEffort
            }
            capabilities != null -> {
                val selectable = capabilities.selectableEfforts
                if (ReasoningEffort.OFF in selectable) {
                    ReasoningEffort.OFF
                } else {
                    selectable.filter { it != ReasoningEffort.DEFAULT }
                        .minByOrNull(ReasoningEffort::rank)
                        ?: ReasoningEffort.DEFAULT
                }
            }
            else -> base.effectiveReasoningEffort
        }
        return base.copy(
            terminalTools = false,
            browserTools = false,
            deviceDirectTools = false,
            deviceSensitiveReadTools = false,
            deviceSensitiveActionTools = false,
            subagentTools = false,
            hostedWebSearchEnabled = false,
            autoCompactionEnabled = false,
            supportsSearchSuggestions = false,
            thinkingEnabled = effort.enablesReasoning,
            reasoningEffort = effort,
        )
    }

    /**
     * 解析 Subagent 实际运行所用的模型配置。
     *
     * 指定了独立子模型且该模型仍处于启用状态时优先使用子模型，否则平滑回退到主模型；
     * 工具开关继承父请求，并强制关闭 `subagentTools` 防止递归派发。
     */
    suspend fun subagentRuntimeConfig(
        primaryConfig: AgentModelClient.ModelConfig,
    ): AgentModelClient.ModelConfig {
        val settings = SettingsDataStore.subagentSettings()
        val secondaryModelId = settings.modelId?.takeIf { it.isNotBlank() }
        val resolvedSecondary = if (secondaryModelId != null) {
            ProviderRepository.ensureBuiltInsMerged()
            ProviderRepository.allProviders().enabledModel(secondaryModelId)
        } else {
            null
        }
        val base = resolvedSecondary?.let { (provider, model) ->
            buildRuntimeConfig(provider, model)
        } ?: primaryConfig
        val explicitEffort = settings.resolvedReasoningEffort
        val effort = when {
            explicitEffort != null -> {
                val capabilities = base.reasoningCapabilities
                when {
                    capabilities != null -> capabilities.normalize(explicitEffort)
                    explicitEffort == ReasoningEffort.OFF -> ReasoningEffort.OFF
                    else -> base.effectiveReasoningEffort
                }
            }
            resolvedSecondary != null -> base.effectiveReasoningEffort
            else -> primaryConfig.effectiveReasoningEffort
        }
        return base.copy(
            terminalTools = primaryConfig.terminalTools,
            browserTools = primaryConfig.browserTools,
            deviceDirectTools = primaryConfig.deviceDirectTools,
            deviceSensitiveReadTools = primaryConfig.deviceSensitiveReadTools,
            deviceSensitiveActionTools = primaryConfig.deviceSensitiveActionTools,
            subagentTools = false,
            thinkingEnabled = effort.enablesReasoning,
            reasoningEffort = effort,
            autoCompactionEnabled = primaryConfig.autoCompactionEnabled,
            supportsSearchSuggestions = false,
        )
    }

    suspend fun selectedProvider(): ProviderSetting? {
        val settings = ProviderRepository.repairSelection()
        return settings.selectedProviderId?.let { ProviderRepository.providerById(it) }
    }

    suspend fun setSelectedProviderId(id: String?) {
        val settings = SettingsDataStore.settings()
        val provider = id?.let { ProviderRepository.providerById(it) }
            ?.takeIf { it.isEnabled }
        val activeModel = provider
            ?.takeIf { it.id == settings.selectedProviderId }
            ?.models
            ?.firstOrNull { it.id == settings.selectedModelId && it.isEnabled }
        val rememberedModelId = provider?.let {
            SettingsDataStore.selectedModelIdForProvider(it.id)
        }
        val model = activeModel ?: provider?.selectedOrFirstModel(rememberedModelId)
        SettingsDataStore.setSelection(
            providerId = provider?.id,
            modelId = model?.id,
        )
        ProviderRepository.repairSelection()
    }

    suspend fun setSelectedModelId(id: String?) {
        val provider = id?.let { ProviderRepository.providerByModelId(it) }
            ?.takeIf { it.isEnabled }
        val model = provider?.models?.firstOrNull { it.id == id && it.isEnabled }
        SettingsDataStore.setSelection(
            providerId = provider?.id,
            modelId = model?.id,
        )
        ProviderRepository.repairSelection()
    }

    suspend fun currentRuntimeConfig(): AgentModelClient.ModelConfig? {
        ProviderRepository.ensureBuiltInsMerged()
        val settings = ProviderRepository.repairSelection()
        val provider = settings.selectedProviderId?.let { ProviderRepository.providerById(it) } ?: return null
        val model = provider.selectedOrFirstModel(settings.selectedModelId) ?: return null
        return buildRuntimeConfig(provider, model)
    }

    /** 会话绑定的模型仍可用时使用它，否则回落到默认模型。 */
    suspend fun runtimeConfigFor(modelId: String?): AgentModelClient.ModelConfig? {
        ProviderRepository.ensureBuiltInsMerged()
        ProviderRepository.allProviders().enabledModel(modelId)?.let { (provider, model) ->
            return buildRuntimeConfig(provider, model)
        }
        return currentRuntimeConfig()
    }

    suspend fun oauthRuntimeConfigFor(
        providerId: String,
        authMode: String,
        modelName: String,
    ): AgentModelClient.ModelConfig? {
        if (!ProviderAuthModes.isOAuth(authMode)) return null
        val provider = ProviderRepository.providerById(providerId)
            ?.takeIf { it.isEnabled && it.authMode == authMode } ?: return null
        val model = provider.models.firstOrNull { it.isEnabled && it.modelId == modelName } ?: return null
        return buildRuntimeConfig(provider, model)
    }

    suspend fun syncToRemotePreferences(service: XposedService?): Boolean {
        val prefs = Prefs.remotePreferencesForUi(service) ?: return false
        val config = currentRuntimeConfig() ?: return clearRuntimeConfig(prefs)
        return writeRuntimeConfig(prefs, config)
    }

    suspend fun ensureDefaults(service: XposedService?) {
        ProviderRepository.ensureBuiltInsMerged()
        ProviderRepository.repairSelection()
        syncToRemotePreferences(service)
    }

    fun runtimeConfigJson(config: AgentModelClient.ModelConfig): String =
        json.encodeToString(if (config.authMode == ProviderAuthModes.API_KEY) config else config.copy(apiKey = ""))

    fun buildRuntimeConfig(provider: ProviderSetting, model: Model): AgentModelClient.ModelConfig {
        val systemPrompt = provider.systemPrompt.orEmpty().trim()
        val sourceType = ProviderSourceRegistry.resolve(provider)
        val profile = ProviderConnectionProfile.forAuthMode(provider.authMode)
        val endpointMode = profile?.endpointMode ?: provider.endpointMode()
        val reasoningCapabilities = reasoningCapabilities(provider, model)
        return AgentModelClient.ModelConfig(
            providerId = provider.id,
            providerName = provider.name,
            providerType = profile?.providerType ?: provider.runtimeProviderType,
            providerSourceType = sourceType,
            baseUrl = profile?.baseUrl ?: provider.baseUrl.trim(),
            apiKey = provider.apiKey.trim().takeIf { provider.authMode == ProviderAuthModes.API_KEY }.orEmpty(),
            authMode = provider.authMode,
            model = model.modelId.trim(),
            modelDisplayName = model.displayName.trim(),
            contextWindow = model.effectiveContextWindow,
            autoCompactionEnabled = Prefs.isEnabled(Prefs.Keys.AGENT_AUTO_COMPACTION_ENABLED),
            subagentTools = Prefs.isEnabled(Prefs.Keys.AGENT_SUBAGENT_ENABLED),
            systemPrompt = systemPrompt,
            anthropicVersion = (provider as? AnthropicProviderSetting)?.anthropicVersion
                ?: AnthropicProviderSetting.DEFAULT_ANTHROPIC_VERSION,
            openAiEndpointMode = endpointMode,
            hostedWebSearchEnabled = provider.hostedWebSearchEnabled,
            thinkingEnabled = reasoningCapabilities != null,
            reasoningEffort = reasoningCapabilities?.let { ReasoningEffort.DEFAULT }
                ?: ReasoningEffort.OFF,
            reasoningCapabilities = reasoningCapabilities,
            customHeaders = provider.customHeaders + model.customHeaders,
            customBody = provider.customBody + model.customBody,
        )
    }

    fun reasoningCapabilities(provider: ProviderSetting, model: Model): ModelReasoningCapabilities? {
        if (provider is GeminiProviderSetting) {
            return GeminiModelCapabilities.resolveReasoning(model)
        }
        val sourceType = ProviderSourceRegistry.resolve(provider)
        val inferOpenAiCatalog = sourceType == io.github.mangi.eta.data.model.ProviderSourceTypes.CUSTOM &&
            provider.endpointMode() == OpenAiEndpointMode.RESPONSES
        return ReasoningCapabilityResolver.resolve(
            sourceType = if (inferOpenAiCatalog) {
                io.github.mangi.eta.data.model.ProviderSourceTypes.OPENAI
            } else {
                sourceType
            },
            model = model,
            inferExactCatalogModel = inferOpenAiCatalog,
        )
    }

    private fun ProviderSetting.endpointMode(): String = when (this) {
        is OpenAiCompatibleProviderSetting -> endpointMode
        is CustomProviderSetting -> endpointMode
        is AnthropicProviderSetting, is GeminiProviderSetting -> ""
    }

    private fun writeRuntimeConfig(
        prefs: SharedPreferences,
        config: AgentModelClient.ModelConfig,
    ): Boolean =
        runCatching {
            prefs.edit()
                .putString(Prefs.Keys.AGENT_RUNTIME_CONFIG_JSON, runtimeConfigJson(config))
                .commit()
        }.getOrDefault(false)

    private fun clearRuntimeConfig(prefs: SharedPreferences): Boolean =
        runCatching {
            prefs.edit()
                .remove(Prefs.Keys.AGENT_RUNTIME_CONFIG_JSON)
                .commit()
        }.getOrDefault(false)
}
