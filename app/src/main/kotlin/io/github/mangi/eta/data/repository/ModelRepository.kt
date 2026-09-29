package io.github.mangi.eta.data.repository

import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.model.ModelSource
import io.github.mangi.eta.data.provider.BuiltinProviders
import java.util.UUID
import java.util.Locale
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
        require(draft.contextWindowOverride == null || draft.contextWindowOverride > 0) {
            "上下文长度必须是正整数"
        }
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
        if (providerId == BuiltinProviders.OPENAI_CODEX_ID) {
            SettingsDataStore.unhideRemoteModelId(providerId, modelId.normalizedModelId())
        }
        saved
    }

    suspend fun deleteModel(providerId: String, modelId: String) = mutationMutex.withLock {
        val models = currentModels(providerId)
        hideDeletedCodexRemoteModels(providerId, models.filter { it.id == modelId })
        ProviderRepository.replaceModels(
            providerId,
            models.filterNot { it.id == modelId },
        )
        ProviderRepository.repairSelection()
    }

    suspend fun deleteModels(providerId: String, modelIds: Set<String>) {
        if (modelIds.isEmpty()) return
        mutationMutex.withLock {
            val models = currentModels(providerId)
            hideDeletedCodexRemoteModels(providerId, models.filter { it.id in modelIds })
            ProviderRepository.replaceModels(
                providerId,
                models.filterNot { it.id in modelIds },
            )
            ProviderRepository.repairSelection()
        }
    }

    suspend fun syncRemoteModels(
        providerId: String,
        fetched: List<Model>,
        preserveCatalogModels: Boolean = true,
    ): RemoteModelSyncResult =
        mutationMutex.withLock {
            val fetchedByKey = fetched
                .asSequence()
                .filter { it.modelId.isNotBlank() }
                .distinctBy { it.modelId.normalizedModelId() }
                .associateBy { it.modelId.normalizedModelId() }
            if (fetchedByKey.isEmpty()) {
                return@withLock RemoteModelSyncResult(applied = false)
            }
            val hiddenIds = if (providerId == BuiltinProviders.OPENAI_CODEX_ID) {
                SettingsDataStore.hiddenRemoteModelIds(providerId)
            } else {
                emptySet()
            }
            val remoteByKey = fetchedByKey.filterKeys { it !in hiddenIds }

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
                                    isBuiltIn = if (!preserveCatalogModels && stored.source == ModelSource.CATALOG) {
                                        false
                                    } else {
                                        stored.isBuiltIn || remote.isBuiltIn
                                    },
                                    customHeaders = stored.customHeaders,
                                    customBody = stored.customBody,
                                    contextWindowOverride = stored.contextWindowOverride,
                                    reasoningOverride = stored.reasoningOverride,
                                    reasoningCapabilitiesOverride = stored.reasoningCapabilitiesOverride,
                                    source = if (!preserveCatalogModels && stored.source == ModelSource.CATALOG) {
                                        ModelSource.REMOTE
                                    } else {
                                        stored.source
                                    },
                                    createdAt = stored.createdAt,
                                )
                            )
                        }
                        stored.source != ModelSource.REMOTE &&
                            (preserveCatalogModels || stored.source != ModelSource.CATALOG) -> add(stored)
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
                fetchedCount = fetchedByKey.size,
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

    private suspend fun hideDeletedCodexRemoteModels(providerId: String, models: List<Model>) {
        if (providerId != BuiltinProviders.OPENAI_CODEX_ID) return
        SettingsDataStore.hideRemoteModelIds(
            providerId,
            models.filter { it.source == ModelSource.REMOTE }
                .mapTo(mutableSetOf()) { it.modelId.normalizedModelId() },
        )
    }

    private fun String.normalizedModelId(): String = trim().lowercase(Locale.ROOT)
}

internal data class RemoteModelSyncResult(
    val applied: Boolean,
    val fetchedCount: Int = 0,
    val addedCount: Int = 0,
    val removedCount: Int = 0,
)
