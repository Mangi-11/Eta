package io.github.mangi.eta.data.repository

import io.github.mangi.eta.data.db.ConversationStateEntity
import io.github.mangi.eta.data.model.ProviderAuthModes

/** 校验只保留标识与角色关联所需的消息类型，正文和检查点逐条读取后释放。 */
internal object EtaBackupValidation {
    suspend fun validate(staged: EtaBackupJsonStreams.StagedDocument): EtaBackupSummary {
        val header = staged.header
        validateHeader(header)
        val conversationIds = mutableSetOf<String>()
        val revisionConversationIds = mutableSetOf<String>()
        val bindingIds = mutableSetOf<String>()
        try {
            staged.conversations { row ->
                if (row.id.isBlank() || !conversationIds.add(row.id)) {
                    throw EtaBackupException("备份中的会话存在重复或无效 ID")
                }
                if (row.revisionsJson.isNotBlank()) revisionConversationIds += row.id
                CharacterBackupTransfer.validateBindings(listOf(row)).forEach { bindingIds += it.characterId }
            }
            if (header.conversationState != null && header.conversationState.selectedConversationId !in conversationIds) {
                throw EtaBackupException("备份中的当前会话不存在")
            }
            val messageIds = mutableSetOf<String>()
            val messagePositions = mutableSetOf<Pair<String, Int>>()
            val revisionMessageTypes = mutableMapOf<String, MutableMap<String, String>>()
            var messageCount = 0
            staged.messages { row ->
                if (row.conversationId !in conversationIds) throw EtaBackupException("备份中的消息缺少所属会话")
                if (row.id.isBlank() || !messageIds.add(row.id)) throw EtaBackupException("备份中的消息 ID 重复")
                if (!messagePositions.add(row.conversationId to row.sortIndex)) throw EtaBackupException("备份中的消息顺序重复")
                if (row.conversationId in revisionConversationIds) {
                    revisionMessageTypes.getOrPut(row.conversationId) { mutableMapOf() }[row.id] = row.type
                }
                messageCount++
            }
            staged.conversations { row ->
                CharacterBackupTransfer.validateRevisions(row, revisionMessageTypes[row.id].orEmpty())
            }
            val checkpointIds = mutableSetOf<String>()
            staged.checkpoints { row ->
                if (row.conversationId !in conversationIds) throw EtaBackupException("备份中的上下文检查点缺少所属会话")
                if (!checkpointIds.add(row.conversationId)) throw EtaBackupException("备份中的上下文检查点重复")
            }
            header.roleplay?.let { CharacterBackupTransfer.validate(it, bindingIds) }
            return EtaBackupSummary(
                providerCount = header.providers.size,
                modelCount = header.providers.sumOf { it.models.size },
                conversationCount = conversationIds.size,
                messageCount = messageCount,
                memoryBytes = header.memoryMd.toByteArray(Charsets.UTF_8).size,
                characterCount = header.roleplay?.characters?.size ?: 0,
            )
        } catch (failure: IllegalArgumentException) {
            if (failure is EtaBackupException) throw failure
            throw EtaBackupException("备份中的记录或角色数据无效", failure)
        }
    }

    private fun validateHeader(document: EtaBackupDocument) {
        if (document.format != EtaBackupDocument.FORMAT) throw EtaBackupException("这不是 Eta 备份文件")
        if (document.schemaVersion !in 1..EtaBackupDocument.SCHEMA_VERSION) {
            throw EtaBackupException("不支持的 Eta 备份版本：${document.schemaVersion}")
        }
        if (document.catalogRevision < 0) throw EtaBackupException("备份中的模型目录版本无效")
        val providerIds = document.providers.map { it.provider.id }
        if (providerIds.size != providerIds.toSet().size || providerIds.any(String::isBlank)) {
            throw EtaBackupException("备份中的模型提供商存在重复或无效 ID")
        }
        val modelIds = document.providers.flatMap { provider ->
            if (provider.provider.authMode != ProviderAuthModes.API_KEY &&
                !ProviderAuthModes.isOAuth(provider.provider.authMode)
            ) throw EtaBackupException("备份中的服务商认证方式不受支持")
            val ids = provider.models.map { it.id }
            if (ids.size != ids.toSet().size || ids.any(String::isBlank)) throw EtaBackupException("备份中的模型存在重复或无效 ID")
            if (provider.models.any { it.providerId != provider.provider.id }) throw EtaBackupException("备份中的模型与提供商不匹配")
            provider.models.map { it.id to provider.provider.id }
        }
        if (modelIds.size != modelIds.map { it.first }.toSet().size) throw EtaBackupException("备份中的模型 ID 重复")
        if (document.selectedProviderId != null && document.selectedProviderId !in providerIds) {
            throw EtaBackupException("备份中的当前提供商不存在")
        }
        val selectedModel = document.selectedModelId?.let { id -> modelIds.firstOrNull { it.first == id } }
        if (document.selectedModelId != null && selectedModel == null) throw EtaBackupException("备份中的当前模型不存在")
        if (selectedModel != null && selectedModel.second != document.selectedProviderId) {
            throw EtaBackupException("备份中的当前模型与提供商不匹配")
        }
        if (document.conversationState != null && document.conversationState.id != ConversationStateEntity.SINGLETON_ID) {
            throw EtaBackupException("备份中的会话状态无效")
        }
        if (document.memoryMd.toByteArray(Charsets.UTF_8).size > 1024 * 1024) throw EtaBackupException("MEMORY.md 超过 1 MiB 限制")
    }
}
