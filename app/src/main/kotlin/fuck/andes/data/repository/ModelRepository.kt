package fuck.andes.data.repository

import fuck.andes.data.model.Model
import fuck.andes.data.model.withModels
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

internal object ModelRepository {
    fun modelsByProviderFlow(providerId: String): Flow<List<Model>> =
        allModelsByProviderFlow(providerId).map { models ->
            models.filter { it.isEnabled }.sortedBy { it.sortOrder }
        }

    fun allModelsByProviderFlow(providerId: String): Flow<List<Model>> =
        ProviderRepository.providersFlow().map { providers ->
            providers.firstOrNull { it.id == providerId }
                ?.models
                ?.sortedBy { it.sortOrder }
                .orEmpty()
        }

    suspend fun modelsByProvider(providerId: String): List<Model> =
        ProviderRepository.providerById(providerId)
            ?.models
            ?.sortedBy { it.sortOrder }
            .orEmpty()

    suspend fun addModel(providerId: String, model: Model) {
        updateProviderModels(providerId) { models ->
            val nextOrder = (models.maxOfOrNull { it.sortOrder } ?: -1) + 1
            models + model.copy(sortOrder = nextOrder)
        }
    }

    suspend fun updateModel(providerId: String, model: Model) {
        updateProviderModels(providerId) { models ->
            models.map { if (it.id == model.id) model else it }
        }
    }

    suspend fun deleteModel(providerId: String, modelId: String) {
        updateProviderModels(providerId) { models ->
            models.filterNot { it.id == modelId }
        }
    }

    suspend fun deleteModels(providerId: String, modelIds: Set<String>) {
        if (modelIds.isEmpty()) return
        updateProviderModels(providerId) { models ->
            models.filterNot { it.id in modelIds }
        }
    }

    suspend fun replaceModelsForProvider(providerId: String, newModels: List<Model>) {
        updateProviderModels(providerId) { existingModels ->
            val existingByModelId = existingModels.associateBy { it.modelId.trim().lowercase() }
            newModels.mapIndexed { index, model ->
                val existing = existingByModelId[model.modelId.trim().lowercase()]
                if (existing == null) {
                    model.copy(sortOrder = index)
                } else {
                    model.copy(
                        id = existing.id,
                        isEnabled = existing.isEnabled,
                        isBuiltIn = existing.isBuiltIn || model.isBuiltIn,
                        sortOrder = index,
                        customHeaders = existing.customHeaders,
                        customBody = existing.customBody,
                        createdAt = existing.createdAt,
                    )
                }
            }
        }
    }

    suspend fun reorderModels(providerId: String, ids: List<String>) {
        updateProviderModels(providerId) { models ->
            val byId = models.associateBy { it.id }
            val reordered = ids.mapNotNull { byId[it] }
                .mapIndexed { index, model -> model.copy(sortOrder = index) }
            val rest = models.filterNot { it.id in ids }
            reordered + rest
        }
    }

    fun newId(): String = UUID.randomUUID().toString()

    private suspend fun updateProviderModels(
        providerId: String,
        transform: (List<Model>) -> List<Model>,
    ) {
        val provider = ProviderRepository.providerById(providerId) ?: return
        ProviderRepository.updateProvider(
            provider.withModels(transform(provider.models).sortedBy { it.sortOrder })
        )
    }
}
