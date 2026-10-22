package io.github.mangi.eta.data.repository

import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.model.ModelSource
import io.github.mangi.eta.data.model.ProviderAuthModes

internal data class RemoteModelReconciliation(
    val models: List<Model>,
    val result: RemoteModelSyncResult,
)

/** 同一模型保留本地身份与用户覆盖；账号目录可删除旧自动目录，手工配置始终保留。 */
internal fun reconcileRemoteModels(
    existing: List<Model>,
    fetched: List<Model>,
    adoptedModelIds: Set<String>,
    authMode: String,
): RemoteModelReconciliation {
    val accountCatalog = ProviderAuthModes.isOAuth(authMode)
    val adopted = adoptedModelIds.mapTo(mutableSetOf()) { it.normalizedModelId() }
    val remoteByKey = fetched.asSequence()
        .filter { it.modelId.isNotBlank() }
        .distinctBy { it.modelId.normalizedModelId() }
        .associateBy { it.modelId.normalizedModelId() }
    if (remoteByKey.isEmpty() && !accountCatalog) {
        return RemoteModelReconciliation(existing, RemoteModelSyncResult(applied = false))
    }
    val consumed = mutableSetOf<String>()
    val merged = buildList {
        existing.forEach { stored ->
            val key = stored.modelId.normalizedModelId()
            val remote = remoteByKey[key]
            when {
                remote != null -> {
                    consumed += key
                    // Codex 账号目录可以声明不同于公共 API 预设的窗口；预填值不能覆盖它。
                    val configuredWindow = stored.contextWindowOverride.takeUnless {
                        authMode == ProviderAuthModes.OPENAI_CHATGPT && stored.source == ModelSource.CATALOG &&
                            it == stored.contextWindow
                    }
                    add(remote.copy(
                        id = stored.id,
                        modelId = remote.modelId.trim(),
                        displayName = remote.displayName.trim().ifBlank { remote.modelId.trim() },
                        isEnabled = stored.isEnabled,
                        isBuiltIn = stored.isBuiltIn || remote.isBuiltIn,
                        customHeaders = stored.customHeaders,
                        customBody = stored.customBody,
                        // 运行窗口只读覆盖值；用户未填写时才采用远端明确给出的窗口。
                        contextWindowOverride = configuredWindow ?: remote.contextWindow,
                        reasoningOverride = stored.reasoningOverride,
                        reasoningCapabilitiesOverride = stored.reasoningCapabilitiesOverride,
                        source = if (stored.source == ModelSource.MANUAL) ModelSource.MANUAL else ModelSource.REMOTE,
                        createdAt = stored.createdAt,
                    ))
                }
                stored.source == ModelSource.MANUAL || (!accountCatalog && stored.source != ModelSource.REMOTE) -> add(stored)
            }
        }
        remoteByKey.forEach { (key, remote) ->
            if (key !in consumed && key in adopted) {
                require(remote.id.isNotBlank()) { "远端模型缺少本地标识" }
                add(remote.copy(
                    modelId = remote.modelId.trim(),
                    displayName = remote.displayName.trim().ifBlank { remote.modelId.trim() },
                    isBuiltIn = false,
                    contextWindowOverride = remote.contextWindow,
                    source = ModelSource.REMOTE,
                ))
            }
        }
    }.mapIndexed { index, model -> model.copy(sortOrder = index) }
    val existingIds = existing.mapTo(mutableSetOf()) { it.id }
    val mergedIds = merged.mapTo(mutableSetOf()) { it.id }
    return RemoteModelReconciliation(merged, RemoteModelSyncResult(
        applied = true,
        fetchedCount = remoteByKey.size,
        addedCount = merged.count { it.id !in existingIds },
        removedCount = existing.count { it.id !in mergedIds },
    ))
}

internal fun String.normalizedModelId(): String = trim().lowercase()
