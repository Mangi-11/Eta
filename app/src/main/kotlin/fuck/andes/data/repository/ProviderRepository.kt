package fuck.andes.data.repository

import android.content.Context
import fuck.andes.data.datastore.SettingsDataStore
import fuck.andes.data.db.FuckAndesDatabase
import fuck.andes.data.db.ProviderWithModelsSeed
import fuck.andes.data.db.toDomain
import fuck.andes.data.db.toEntity
import fuck.andes.data.db.toModelEntities
import fuck.andes.data.model.AnthropicProviderSetting
import fuck.andes.data.model.CustomProviderSetting
import fuck.andes.data.model.Model
import fuck.andes.data.model.OpenAiCompatibleProviderSetting
import fuck.andes.data.model.ProviderSetting
import fuck.andes.data.model.Settings
import fuck.andes.data.model.selectedOrFirstModel
import fuck.andes.data.model.withApiKey
import fuck.andes.data.model.withModels
import fuck.andes.data.model.withSortOrder
import fuck.andes.data.provider.BuiltinProviders
import fuck.andes.data.provider.OfficialModelCatalog
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

internal object ProviderRepository {
    @Volatile
    private lateinit var applicationContext: Context

    fun init(context: Context) {
        if (!::applicationContext.isInitialized) {
            applicationContext = context.applicationContext
        }
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
        repairSelection()
    }

    suspend fun ensureBuiltInsMerged() {
        val current = allProviders()
        if (current.isEmpty()) {
            insertProviders(BuiltinProviders.PROVIDERS.map(::seedOfficialModelsIfEmpty))
            repairSelection()
            return
        }

        val existingIds = current.mapTo(mutableSetOf()) { it.id }
        val missing = BuiltinProviders.PROVIDERS.filterNot { it.id in existingIds }
        if (missing.isNotEmpty()) {
            insertProviders(missing.map(::seedOfficialModelsIfEmpty))
            repairSelection()
        } else {
            repairSelection()
        }
    }

    suspend fun repairSelection(): Settings {
        val providers = allProviders()
        val settings = SettingsDataStore.settings()
        val selectedProvider = providers.firstOrNull { it.id == settings.selectedProviderId && it.isEnabled }
            ?: providers.firstOrNull { it.isEnabled }
        val selectedModel = selectedProvider?.selectedOrFirstModel(settings.selectedModelId)
        val repaired = settings.copy(
            selectedProviderId = selectedProvider?.id,
            selectedModelId = selectedModel?.id,
        )
        SettingsDataStore.setSelection(repaired.selectedProviderId, repaired.selectedModelId)
        return repaired
    }

    fun newId(): String = UUID.randomUUID().toString()

    private fun dao() =
        FuckAndesDatabase.get(appContext()).providerDao()

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
        if (provider.models.isNotEmpty()) return provider
        val seededModels = OfficialModelCatalog.modelsForProvider(provider)
        return if (seededModels.isEmpty()) provider else provider.withModels(seededModels)
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
