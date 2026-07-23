package fuck.andes.data.repository

import fuck.andes.data.model.Model
import fuck.andes.data.model.ModelSource
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal object ModelRepository {
    private val mutationMutex = Mutex()

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

    suspend fun saveModel(providerId: String, draft: Model): Model = mutationMutex.withLock {
        val models = currentModels(providerId)
        val modelId = draft.modelId.trim()
        val displayName = draft.displayName.trim()
        require(modelId.isNotEmpty()) { "Model ID 不能为空" }
        require(displayName.isNotEmpty()) { "展示名称不能为空" }
        require(
            models.none { existing ->
                existing.id != draft.id && existing.modelId.trim().equals(modelId, ignoreCase = true)
            }
        ) { "Model ID 已存在" }

        val existing = models.firstOrNull { it.id == draft.id }
        val saved = if (existing == null) {
            draft.copy(
                id = draft.id.ifBlank(::newId),
                modelId = modelId,
                displayName = displayName,
                isBuiltIn = false,
                sortOrder = (models.maxOfOrNull { it.sortOrder } ?: -1) + 1,
                source = ModelSource.MANUAL,
            )
        } else {
            draft.copy(
                id = existing.id,
                modelId = modelId,
                displayName = displayName,
                isBuiltIn = existing.isBuiltIn,
                sortOrder = existing.sortOrder,
                source = existing.source,
                createdAt = existing.createdAt,
            )
        }
        ProviderRepository.replaceModels(
            providerId,
            models.filterNot { it.id == saved.id } + saved,
        )
        saved
    }

    suspend fun deleteModel(providerId: String, modelId: String) = mutationMutex.withLock {
        ProviderRepository.replaceModels(
            providerId,
            currentModels(providerId).filterNot { it.id == modelId },
        )
        ProviderRepository.repairSelection()
    }

    suspend fun deleteModels(providerId: String, modelIds: Set<String>) {
        if (modelIds.isEmpty()) return
        mutationMutex.withLock {
            ProviderRepository.replaceModels(
                providerId,
                currentModels(providerId).filterNot { it.id in modelIds },
            )
            ProviderRepository.repairSelection()
        }
    }

    suspend fun syncRemoteModels(providerId: String, fetched: List<Model>): RemoteModelSyncResult =
        mutationMutex.withLock {
            val remoteByKey = fetched
                .asSequence()
                .filter { it.modelId.isNotBlank() }
                .distinctBy { it.modelId.normalizedModelId() }
                .associateBy { it.modelId.normalizedModelId() }
            if (remoteByKey.isEmpty()) {
                return@withLock RemoteModelSyncResult(applied = false)
            }

            val existing = currentModels(providerId)
            val consumed = mutableSetOf<String>()
            val merged = buildList {
                existing.forEach { stored ->
                    val key = stored.modelId.normalizedModelId()
                    val remote = remoteByKey[key]
                    when {
                        remote != null -> {
                            consumed += key
                            add(
                                remote.copy(
                                    id = stored.id,
                                    modelId = remote.modelId.trim(),
                                    displayName = remote.displayName.trim().ifBlank { remote.modelId.trim() },
                                    isEnabled = stored.isEnabled,
                                    isBuiltIn = stored.isBuiltIn || remote.isBuiltIn,
                                    customHeaders = stored.customHeaders,
                                    customBody = stored.customBody,
                                    source = stored.source,
                                    createdAt = stored.createdAt,
                                )
                            )
                        }
                        stored.source != ModelSource.REMOTE -> add(stored)
                    }
                }
                remoteByKey.forEach { (key, remote) ->
                    if (key !in consumed) {
                        add(
                            remote.copy(
                                id = remote.id.ifBlank(::newId),
                                modelId = remote.modelId.trim(),
                                displayName = remote.displayName.trim().ifBlank { remote.modelId.trim() },
                                isBuiltIn = false,
                                source = ModelSource.REMOTE,
                            )
                        )
                    }
                }
            }.mapIndexed { index, model -> model.copy(sortOrder = index) }

            ProviderRepository.replaceModels(providerId, merged)
            ProviderRepository.repairSelection()
            RemoteModelSyncResult(
                applied = true,
                fetchedCount = remoteByKey.size,
                addedCount = merged.count { model -> existing.none { it.id == model.id } },
                removedCount = existing.count { stored ->
                    stored.source == ModelSource.REMOTE &&
                        stored.modelId.normalizedModelId() !in remoteByKey
                },
            )
        }

    suspend fun reorderModels(providerId: String, ids: List<String>) {
        mutationMutex.withLock {
            val models = currentModels(providerId)
            val byId = models.associateBy { it.id }
            val orderedIds = ids.toSet()
            val reordered = ids.mapNotNull { byId[it] } + models.filterNot { it.id in orderedIds }
            ProviderRepository.replaceModels(
                providerId,
                reordered.mapIndexed { index, model -> model.copy(sortOrder = index) },
            )
        }
    }

    fun newId(): String = UUID.randomUUID().toString()

    private suspend fun currentModels(providerId: String): List<Model> =
        requireNotNull(ProviderRepository.providerById(providerId)) { "Provider 不存在" }
            .models
            .sortedBy { it.sortOrder }

    private fun String.normalizedModelId(): String = trim().lowercase()
}

internal data class RemoteModelSyncResult(
    val applied: Boolean,
    val fetchedCount: Int = 0,
    val addedCount: Int = 0,
    val removedCount: Int = 0,
)
