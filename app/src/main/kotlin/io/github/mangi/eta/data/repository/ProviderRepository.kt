package io.github.mangi.eta.data.repository

import android.content.Context
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.db.ProviderWithModelsSeed
import io.github.mangi.eta.data.db.toDomain
import io.github.mangi.eta.data.db.toEntity
import io.github.mangi.eta.data.db.toModelEntities
import io.github.mangi.eta.data.model.AnthropicProviderSetting
import io.github.mangi.eta.data.model.CustomProviderSetting
import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.model.ModelReasoningCapabilities
import io.github.mangi.eta.data.model.ModelSource
import io.github.mangi.eta.data.model.OpenAiCompatibleProviderSetting
import io.github.mangi.eta.data.model.ProviderSetting
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.data.model.Settings
import io.github.mangi.eta.data.model.selectedOrFirstModel
import io.github.mangi.eta.data.model.withApiKey
import io.github.mangi.eta.data.model.withModels
import io.github.mangi.eta.data.model.withSortOrder
import io.github.mangi.eta.data.provider.BuiltinProviders
import io.github.mangi.eta.data.provider.OfficialModelCatalog
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal object ProviderRepository {
    private val builtInMergeMutex = Mutex()

    @Volatile
    private lateinit var applicationContext: Context

    fun init(context: Context) {
        applicationContext = context.applicationContext
    }

    fun providersFlow(): Flow<List<ProviderSetting>> =
        dao().providersFlow().map { providers ->
            providers
                .map { it.toDomain().withContextWindows() }
                .sortedBy(ProviderSetting::sortOrder)
        }

    fun settingsFlow(): Flow<Settings> =
        SettingsDataStore.settingsFlow()

    suspend fun settings(): Settings =
        SettingsDataStore.settings()

    suspend fun allProviders(): List<ProviderSetting> =
        dao().providers()
            .map { it.toDomain().withContextWindows() }
            .sortedBy(ProviderSetting::sortOrder)

    suspend fun providerById(id: String): ProviderSetting? =
        dao().providerById(id)?.toDomain()?.withContextWindows()

    suspend fun providerByModelId(modelId: String): ProviderSetting? =
        dao().providerByModelId(modelId)?.toDomain()?.withContextWindows()

    // 读取出口统一补齐已知窗口，UI 与 Runtime 只消费解析后的领域模型。
    private fun ProviderSetting.withContextWindows(): ProviderSetting =
        withModels(models.map { OfficialModelCatalog.withContextWindow(this, it) })

    suspend fun addProvider(provider: ProviderSetting): ProviderSetting {
        val nextOrder = (allProviders().maxOfOrNull { it.sortOrder } ?: -1) + 1
        val added = provider.withSortOrder(nextOrder)
        replaceProvider(added)
        repairSelection()
        return added
    }

    suspend fun updateProvider(provider: ProviderSetting) {
        require(dao().updateProvider(provider.toEntity()) == 1) { "Provider 不存在" }
        repairSelection()
    }

    internal suspend fun replaceModels(providerId: String, models: List<Model>) {
        val provider = requireNotNull(providerById(providerId)) { "Provider 不存在" }
        dao().replaceModels(
            providerId = providerId,
            models = provider.withModels(models).toModelEntities(),
        )
    }

    suspend fun deleteProvider(id: String) {
        val provider = providerById(id) ?: return
        if (provider.isBuiltIn) return
        dao().deleteProvider(id)
        SettingsDataStore.clearSelectedModelIdForProvider(id)
        repairSelection()
    }

    suspend fun copyProvider(id: String): ProviderSetting? {
        val source = providerById(id) ?: return null
        val nextOrder = (allProviders().maxOfOrNull { it.sortOrder } ?: -1) + 1
        val copy = source.deepCopy(
            id = newId(),
            name = "${source.name} 副本",
            sortOrder = nextOrder,
            builtIn = false,
        )
        replaceProvider(copy)
        repairSelection()
        return copy
    }

    suspend fun resetBuiltIn(id: String) {
        val builtIn = BuiltinProviders.providerById(id) ?: return
        val current = providerById(id)
        val restored = seedOfficialModelsIfEmpty(
            current
            ?.let { builtIn.withApiKey(it.apiKey).withSortOrder(it.sortOrder) }
            ?: builtIn
        )
        replaceProvider(restored)
        if (id == BuiltinProviders.OPENAI_CODEX_ID) {
            SettingsDataStore.clearHiddenRemoteModels(id)
        }
        repairSelection()
    }

    suspend fun ensureBuiltInsMerged(): Unit = builtInMergeMutex.withLock {
        val current = allProviders()
        if (current.isEmpty()) {
            insertProviders(BuiltinProviders.PROVIDERS.map(::seedOfficialModelsIfEmpty))
            SettingsDataStore.setOfficialModelCatalogRevision(OfficialModelCatalog.CURRENT_REVISION)
            repairSelection()
            return@withLock
        }

        val existingIds = current.mapTo(mutableSetOf()) { it.id }
        val missing = BuiltinProviders.PROVIDERS.filterNot { it.id in existingIds }
        if (missing.isNotEmpty()) {
            insertProviders(missing.map(::seedOfficialModelsIfEmpty))
        }
        val appliedRevision = SettingsDataStore.officialModelCatalogRevision()
        if (appliedRevision < OfficialModelCatalog.CURRENT_REVISION) {
            current.filter { it.isBuiltIn && BuiltinProviders.providerById(it.id) != null }.forEach { provider ->
                val additions = OfficialModelCatalog.modelsAddedSince(provider, appliedRevision)
                dao().appendModelsIfAbsent(
                    providerId = provider.id,
                    candidates = provider.withModels(additions).toModelEntities(),
                )
                if (appliedRevision < 1) {
                    refreshLegacyCatalogReasoningCapabilities(provider)
                }
            }
            SettingsDataStore.setOfficialModelCatalogRevision(OfficialModelCatalog.CURRENT_REVISION)
        }
        providerById(BuiltinProviders.OPENAI_CODEX_ID)?.let { existing ->
            val onlineOnly = existing.models.filterNot { it.source == ModelSource.CATALOG }
            if (onlineOnly.size != existing.models.size) replaceModels(existing.id, onlineOnly)
        }
        repairSelection()
    }

    suspend fun repairSelection(): Settings {
        val providers = allProviders()
        val settings = SettingsDataStore.settings()
        val selectedProvider = providers.firstOrNull { it.id == settings.selectedProviderId && it.isEnabled }
            ?: providers.firstOrNull { it.isEnabled }
        val activeModel = selectedProvider
            ?.takeIf { it.id == settings.selectedProviderId }
            ?.models
            ?.firstOrNull { it.id == settings.selectedModelId && it.isEnabled }
        val rememberedModelId = selectedProvider?.let {
            SettingsDataStore.selectedModelIdForProvider(it.id)
        }
        val selectedModel = activeModel ?: selectedProvider?.selectedOrFirstModel(rememberedModelId)
        val repaired = settings.copy(
            selectedProviderId = selectedProvider?.id,
            selectedModelId = selectedModel?.id,
        )
        SettingsDataStore.setSelection(repaired.selectedProviderId, repaired.selectedModelId)
        return repaired
    }

    fun newId(): String = UUID.randomUUID().toString()

    private fun dao() =
        EtaDatabase.get(appContext()).providerDao()

    private fun appContext(): Context {
        check(::applicationContext.isInitialized) {
            "ProviderRepository.init(context) must be called in Application.onCreate()"
        }
        return applicationContext
    }

    private suspend fun replaceProvider(provider: ProviderSetting) {
        dao().replaceProvider(
            provider = provider.toEntity(),
            models = provider.toModelEntities(),
        )
    }

    private suspend fun insertProviders(providers: List<ProviderSetting>) {
        dao().insertProvidersWithModels(
            providers.map { provider ->
                ProviderWithModelsSeed(
                    provider = provider.toEntity(),
                    models = provider.toModelEntities(),
                )
            }
        )
    }

    private fun seedOfficialModelsIfEmpty(provider: ProviderSetting): ProviderSetting {
        if (provider.id == BuiltinProviders.OPENAI_CODEX_ID) return provider
        if (provider.models.isNotEmpty()) return provider
        val seededModels = OfficialModelCatalog.modelsForProvider(provider)
        return if (seededModels.isEmpty()) provider else provider.withModels(seededModels)
    }

    private suspend fun refreshLegacyCatalogReasoningCapabilities(provider: ProviderSetting) {
        val legacyCapabilities = when (provider.id) {
            BuiltinProviders.OPENAI_ID -> listOf(
                "gpt-5.6-sol",
                "gpt-5.6-terra",
                "gpt-5.6-luna",
                "gpt-5.5",
            ).associateWith {
                ModelReasoningCapabilities(
                    supportedEfforts = listOf(
                        ReasoningEffort.MINIMAL,
                        ReasoningEffort.LOW,
                        ReasoningEffort.MEDIUM,
                        ReasoningEffort.HIGH,
                        ReasoningEffort.XHIGH,
                    ),
                    defaultEffort = ReasoningEffort.MEDIUM,
                    defaultEnabled = true,
                    canDisable = true,
                )
            }
            BuiltinProviders.ANTHROPIC_ID -> mapOf("claude-fable-5" to ModelReasoningCapabilities(
                supportedEfforts = listOf(
                    ReasoningEffort.LOW,
                    ReasoningEffort.MEDIUM,
                    ReasoningEffort.HIGH,
                    ReasoningEffort.XHIGH,
                    ReasoningEffort.MAX,
                ),
                defaultEffort = ReasoningEffort.HIGH,
                defaultEnabled = true,
                canDisable = true,
            ))
            BuiltinProviders.STEPFUN_ID -> mapOf("step-3.7-flash" to ModelReasoningCapabilities(
                defaultEnabled = true,
                mandatory = true,
            ))
            else -> return
        }
        val updatedJsonByModelId = provider.withModels(OfficialModelCatalog.modelsForProvider(provider))
            .toModelEntities()
            .associate { it.modelId to it.reasoningCapabilitiesJson }
        val storedRowsById = dao().models(provider.id).associateBy { it.id }
        provider.models.forEach { storedModel ->
            val previousCapabilities = legacyCapabilities[storedModel.modelId] ?: return@forEach
            if (storedModel.source != ModelSource.CATALOG ||
                storedModel.reasoningCapabilities != previousCapabilities
            ) return@forEach
            val updatedJson = updatedJsonByModelId[storedModel.modelId] ?: return@forEach
            val storedRow = storedRowsById[storedModel.id] ?: return@forEach
            if (updatedJson == storedRow.reasoningCapabilitiesJson) return@forEach
            dao().updateCatalogReasoningCapabilitiesIfUnchanged(
                providerId = provider.id,
                modelId = storedModel.id,
                apiModelId = storedModel.modelId,
                previousJson = storedRow.reasoningCapabilitiesJson,
                updatedJson = updatedJson,
            )
        }
    }

    private fun ProviderSetting.deepCopy(
        id: String,
        name: String,
        sortOrder: Int,
        builtIn: Boolean,
    ): ProviderSetting {
        val copiedModels = models.mapIndexed { index, model ->
            model.copy(id = newId(), isBuiltIn = builtIn, sortOrder = index)
        }
        return when (this) {
            is OpenAiCompatibleProviderSetting -> copy(
                id = id,
                name = name,
                sortOrder = sortOrder,
                isBuiltIn = builtIn,
                models = copiedModels,
            )

            is AnthropicProviderSetting -> copy(
                id = id,
                name = name,
                sortOrder = sortOrder,
                isBuiltIn = builtIn,
                models = copiedModels,
            )

            is CustomProviderSetting -> copy(
                id = id,
                name = name,
                sortOrder = sortOrder,
                isBuiltIn = builtIn,
                models = copiedModels,
            )
        }
    }
}
