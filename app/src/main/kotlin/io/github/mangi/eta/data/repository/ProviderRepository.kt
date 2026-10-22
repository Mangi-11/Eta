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
import io.github.mangi.eta.data.model.ProviderAuthModes
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.data.model.Settings
import io.github.mangi.eta.data.model.selectedOrFirstModel
import io.github.mangi.eta.data.model.withApiKey
import io.github.mangi.eta.data.model.withModels
import io.github.mangi.eta.data.model.withSortOrder
import io.github.mangi.eta.data.provider.BuiltinProviders
import io.github.mangi.eta.data.provider.OfficialModelCatalog
import io.github.mangi.eta.data.provider.ProviderSourceRegistry
import io.github.mangi.eta.data.model.ProviderSourceTypes
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal object ProviderRepository {
    /** 配置与模型共用修改锁，避免旧连接的目录在认证方式切换后落库。 */
    internal val mutationMutex = Mutex()

    @Volatile
    private lateinit var applicationContext: Context

    fun init(context: Context) {
        applicationContext = context.applicationContext
        ProviderCredentialRepository.init(context)
    }

    fun providersFlow(): Flow<List<ProviderSetting>> =
        dao().providersFlow().map { providers ->
            providers
                .map { it.toDomain() }
                .sortedBy(ProviderSetting::sortOrder)
        }

    fun settingsFlow(): Flow<Settings> =
        SettingsDataStore.settingsFlow()

    suspend fun settings(): Settings =
        SettingsDataStore.settings()

    suspend fun allProviders(): List<ProviderSetting> =
        dao().providers()
            .map { it.toDomain() }
            .sortedBy(ProviderSetting::sortOrder)

    suspend fun providerById(id: String): ProviderSetting? =
        dao().providerById(id)?.toDomain()

    suspend fun providerByModelId(modelId: String): ProviderSetting? =
        dao().providerByModelId(modelId)?.toDomain()

    suspend fun addProvider(provider: ProviderSetting): ProviderSetting {
        val added = mutationMutex.withLock {
            val nextOrder = (allProviders().maxOfOrNull { it.sortOrder } ?: -1) + 1
            provider.withSortOrder(nextOrder).also { replaceProvider(it) }
        }
        repairSelection()
        return added
    }

    suspend fun updateProvider(provider: ProviderSetting) {
        mutationMutex.withLock {
            val previous = requireNotNull(providerById(provider.id)) { "Provider 不存在" }
            if (previous.authMode != provider.authMode) {
                forgetCredentials(provider.id)
            }
            require(dao().updateProvider(provider.toEntity()) == 1) { "Provider 不存在" }
        }
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
        val deleted = mutationMutex.withLock {
            val provider = providerById(id) ?: return@withLock false
            if (provider.isBuiltIn) return@withLock false
            forgetCredentials(id)
            dao().deleteProvider(id)
            true
        }
        if (!deleted) return
        SettingsDataStore.clearSelectedModelIdForProvider(id)
        repairSelection()
    }

    suspend fun copyProvider(id: String): ProviderSetting? {
        val copy = mutationMutex.withLock {
            val source = providerById(id) ?: return@withLock null
            val nextOrder = (allProviders().maxOfOrNull { it.sortOrder } ?: -1) + 1
            source.deepCopy(
                id = newId(),
                name = "${source.name} 副本",
                sortOrder = nextOrder,
                builtIn = false,
            ).also { replaceProvider(it) }
        } ?: return null
        repairSelection()
        return copy
    }

    suspend fun resetBuiltIn(id: String) {
        val builtIn = BuiltinProviders.providerById(id) ?: return
        mutationMutex.withLock {
            val current = providerById(id)
            forgetCredentials(id)
            val restored = seedOfficialModelsIfEmpty(
                current
                    ?.let { existing ->
                        builtIn.withApiKey(existing.apiKey.takeIf { existing.authMode == ProviderAuthModes.API_KEY }.orEmpty())
                            .withSortOrder(existing.sortOrder)
                    }
                    ?: builtIn
            )
            replaceProvider(restored)
        }
        repairSelection()
    }

    suspend fun ensureBuiltInsMerged() {
        mutationMutex.withLock {
            val current = allProviders()
            if (current.isEmpty()) {
                insertProviders(BuiltinProviders.PROVIDERS.map(::seedOfficialModelsIfEmpty))
                SettingsDataStore.setOfficialModelCatalogRevision(OfficialModelCatalog.CURRENT_REVISION)
                return@withLock
            }

            val existingIds = current.mapTo(mutableSetOf()) { it.id }
            val missing = BuiltinProviders.PROVIDERS.filterNot { it.id in existingIds }
            if (missing.isNotEmpty()) {
                insertProviders(missing.map(::seedOfficialModelsIfEmpty))
            }
            val appliedRevision = SettingsDataStore.officialModelCatalogRevision()
            if (appliedRevision < OfficialModelCatalog.CURRENT_REVISION) {
                if (appliedRevision < 2) {
                    current.filter { ProviderSourceRegistry.resolve(it) == ProviderSourceTypes.OPENAI }
                        .forEach { refreshOpenAiPresets(it, appliedRevision) }
                }
                current.filter {
                    it.isBuiltIn && it.authMode == ProviderAuthModes.API_KEY && BuiltinProviders.providerById(it.id) != null
                }.forEach { provider ->
                    val additions = if (ProviderSourceRegistry.resolve(provider) == ProviderSourceTypes.OPENAI) {
                        emptyList()
                    } else OfficialModelCatalog.modelsAddedSince(provider, appliedRevision)
                    dao().appendModelsIfAbsent(
                        providerId = provider.id,
                        candidates = provider.withModels(additions).toModelEntities(),
                    )
                    if (appliedRevision < 1) {
                        refreshLegacyCatalogReasoningCapabilities(provider)
                    }
                }
                if (appliedRevision < 3) {
                    current.filter { ProviderSourceRegistry.resolve(it) == ProviderSourceTypes.OPENAI }.forEach { provider ->
                        fillOpenAiContextWindows(requireNotNull(providerById(provider.id)))
                    }
                }
                SettingsDataStore.setOfficialModelCatalogRevision(OfficialModelCatalog.CURRENT_REVISION)
            }
        }
        repairSelection()
    }

    private suspend fun refreshOpenAiPresets(provider: ProviderSetting, revision: Int) {
        val currentPresets = OfficialModelCatalog.modelsForProvider(provider)
        val currentIds = currentPresets.mapTo(mutableSetOf()) { it.modelId.normalizedModelId() }
        val retained = provider.models.filter { it.source != ModelSource.CATALOG || it.modelId.normalizedModelId() in currentIds }
        // 已有远端目录或用户清空的连接不能再由预设填充。
        val additions = if (provider.models.any { it.source == ModelSource.CATALOG } &&
            provider.models.none { it.source == ModelSource.REMOTE }
        ) OfficialModelCatalog.modelsAddedSince(provider, revision) else emptyList()
        val existingIds = retained.mapTo(mutableSetOf()) { it.modelId.normalizedModelId() }
        val nextOrder = (retained.maxOfOrNull { it.sortOrder } ?: -1) + 1
        val updated = retained + additions.filterNot { it.modelId.normalizedModelId() in existingIds }
            .mapIndexed { index, model -> model.copy(id = newId(), sortOrder = nextOrder + index) }
        if (updated != provider.models) replaceModels(provider.id, updated)
    }

    private suspend fun fillOpenAiContextWindows(provider: ProviderSetting) {
        val presets = OfficialModelCatalog.modelsForProvider(provider).associateBy { it.modelId.normalizedModelId() }
        val updated = provider.models.map { model ->
            if (model.contextWindowOverride != null || model.source == ModelSource.MANUAL) return@map model
            val preset = presets[model.modelId.normalizedModelId()] ?: return@map model
            val window = model.contextWindow
                ?: preset.contextWindow.takeIf { model.source == ModelSource.CATALOG }
                ?: return@map model
            model.copy(contextWindow = window, contextWindowOverride = window)
        }
        if (updated != provider.models) replaceModels(provider.id, updated)
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

    private suspend fun forgetCredentials(providerId: String) = withContext(Dispatchers.IO) {
        ProviderCredentialRepository.forget(providerId)
    }

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
        if (provider.models.isNotEmpty() || ProviderAuthModes.isOAuth(provider.authMode)) return provider
        val seededModels = OfficialModelCatalog.modelsForProvider(provider)
        return if (seededModels.isEmpty()) provider else provider.withModels(seededModels)
    }

    private suspend fun refreshLegacyCatalogReasoningCapabilities(provider: ProviderSetting) {
        val legacyCapabilities = when (provider.id) {
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
