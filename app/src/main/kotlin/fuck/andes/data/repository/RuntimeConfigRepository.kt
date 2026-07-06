package fuck.andes.data.repository

import android.content.SharedPreferences
import fuck.andes.agent.model.AgentModelClient
import fuck.andes.config.Prefs
import fuck.andes.data.datastore.SettingsDataStore
import fuck.andes.data.model.AnthropicProviderSetting
import fuck.andes.data.model.CustomProviderSetting
import fuck.andes.data.model.Model
import fuck.andes.data.model.OpenAiCompatibleProviderSetting
import fuck.andes.data.model.ProviderSetting
import fuck.andes.data.model.runtimeProviderType
import fuck.andes.data.model.selectedOrFirstModel
import fuck.andes.data.provider.BuiltinProviders
import fuck.andes.data.provider.ProviderSourceRegistry
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

    suspend fun selectedProvider(): ProviderSetting? {
        val settings = ProviderRepository.repairSelection()
        return settings.selectedProviderId?.let { ProviderRepository.providerById(it) }
    }

    suspend fun setSelectedProviderId(id: String?) {
        val provider = id?.let { ProviderRepository.providerById(it) }
            ?.takeIf { it.isEnabled }
        val model = provider?.selectedOrFirstModel(null)
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
        json.encodeToString(config)

    fun buildRuntimeConfig(provider: ProviderSetting, model: Model): AgentModelClient.ModelConfig {
        val systemPrompt = provider.systemPrompt
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: BuiltinProviders.DEFAULT_SYSTEM_PROMPT
        return AgentModelClient.ModelConfig(
            providerId = provider.id,
            providerName = provider.name,
            providerType = provider.runtimeProviderType,
            providerSourceType = ProviderSourceRegistry.resolve(provider),
            baseUrl = provider.baseUrl.trim(),
            apiKey = provider.apiKey.trim(),
            model = model.modelId.trim(),
            modelDisplayName = model.displayName.trim(),
            systemPrompt = systemPrompt,
            anthropicVersion = (provider as? AnthropicProviderSetting)?.anthropicVersion
                ?: AnthropicProviderSetting.DEFAULT_ANTHROPIC_VERSION,
            openAiEndpointMode = when (provider) {
                is OpenAiCompatibleProviderSetting -> provider.endpointMode
                is CustomProviderSetting -> provider.endpointMode
                is AnthropicProviderSetting -> ""
            },
            customHeaders = provider.customHeaders + model.customHeaders,
            customBody = provider.customBody + model.customBody,
        )
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
