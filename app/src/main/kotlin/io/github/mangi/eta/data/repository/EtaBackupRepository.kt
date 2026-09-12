package io.github.mangi.eta.data.repository

import android.content.Context
import androidx.room.withTransaction
import io.github.mangi.eta.data.db.ConversationContextCheckpointEntity
import io.github.mangi.eta.data.db.ConversationEntity
import io.github.mangi.eta.data.db.ConversationMessageEntity
import io.github.mangi.eta.data.db.ConversationStateEntity
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.db.ProviderEntity
import io.github.mangi.eta.data.db.ProviderModelEntity
import io.github.mangi.eta.data.db.ProviderWithModelsSeed
import io.github.mangi.eta.data.datastore.SettingsDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import java.io.InputStream
import java.io.OutputStream

/** Eta 用户数据备份；旧备份没有角色字段时保留设备上的角色库。 */
@Serializable
internal data class EtaBackupDocument(
    val format: String = FORMAT,
    val schemaVersion: Int = SCHEMA_VERSION,
    val exportedAt: Long,
    val providers: List<EtaBackupProvider> = emptyList(),
    val selectedProviderId: String? = null,
    val selectedModelId: String? = null,
    val conversations: List<ConversationEntity> = emptyList(),
    val messages: List<ConversationMessageEntity> = emptyList(),
    val contextCheckpoints: List<ConversationContextCheckpointEntity> = emptyList(),
    val conversationState: ConversationStateEntity? = null,
    val memoryMd: String = "",
    val roleplay: CharacterBackupData? = null,
) {
    companion object {
        const val FORMAT = "eta-backup"
        const val SCHEMA_VERSION = 2
    }
}

@Serializable
internal data class EtaBackupProvider(
    val provider: ProviderEntity,
    val models: List<ProviderModelEntity> = emptyList(),
)

internal data class EtaBackupSummary(
    val providerCount: Int,
    val modelCount: Int,
    val conversationCount: Int,
    val messageCount: Int,
    val memoryBytes: Int,
    val characterCount: Int = 0,
)

internal class EtaBackupException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)

internal object EtaBackupRepository {
    private const val MAX_BACKUP_BYTES = 64L * 1024L * 1024L

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = true
        prettyPrint = true
    }

    suspend fun export(context: Context, output: OutputStream): EtaBackupSummary =
        withContext(Dispatchers.IO) {
            val document = snapshot(context.applicationContext)
            val bytes = json.encodeToString(document).toByteArray(Charsets.UTF_8)
            if (bytes.size > MAX_BACKUP_BYTES) {
                throw EtaBackupException("备份文件超过 64 MiB 限制")
            }
            output.write(bytes)
            output.flush()
            document.summary()
        }

    suspend fun import(context: Context, input: InputStream): EtaBackupSummary =
        withContext(Dispatchers.IO) {
            val document = readDocument(input)
            validate(document)
            val appContext = context.applicationContext
            val database = EtaDatabase.get(appContext)
            val previousMemory = AgentMemoryRepository.snapshot().content
            val previousRoleMemories = document.roleplay?.assets.orEmpty().associate {
                it.characterId to CharacterMemoryRepository.snapshot(appContext, it.characterId).content
            }
            val avatarPaths = document.roleplay?.let { CharacterBackupTransfer.restoreAssets(appContext, it) }.orEmpty()
            var memoryWriteStarted = false
            try {
                database.withTransaction {
                    database.providerDao().replaceAll(
                        document.providers.map { provider ->
                            ProviderWithModelsSeed(provider = provider.provider, models = provider.models)
                        },
                    )
                    database.conversationDao().replaceAll(
                        conversations = document.conversations.map { CharacterBackupTransfer.remapConversation(it, avatarPaths) },
                        messages = document.messages,
                        contextCheckpoints = document.contextCheckpoints,
                        state = document.conversationState,
                    )
                    document.roleplay?.let { roleplay ->
                        database.characterDao().replaceAll(
                            roleplay.characters.map { it.copy(avatarPath = avatarPaths[it.id]) },
                            roleplay.persona,
                        )
                    }
                    // 文件写入失败使数据库事务回滚，再补偿已经替换的记忆文件。
                    memoryWriteStarted = true
                    AgentMemoryRepository.replaceAll(document.memoryMd)
                    document.roleplay?.let { CharacterBackupTransfer.restoreMemories(appContext, it) }
                }
            } catch (failure: Throwable) {
                if (memoryWriteStarted) {
                    try { AgentMemoryRepository.replaceAll(previousMemory) } catch (restoreFailure: Throwable) {
                        failure.addSuppressed(restoreFailure)
                    }
                    previousRoleMemories.forEach { (id, content) ->
                        try { CharacterMemoryRepository.replaceAll(appContext, id, content) } catch (restoreFailure: Throwable) {
                            failure.addSuppressed(restoreFailure)
                        }
                    }
                }
                CharacterBackupTransfer.discardAssets(avatarPaths, failure)
                throw failure
            }
            SettingsDataStore.setSelection(
                providerId = document.selectedProviderId,
                modelId = document.selectedModelId,
            )
            ProviderRepository.ensureBuiltInsMerged()
            ProviderRepository.repairSelection()
            document.summary()
        }

    suspend fun inspect(input: InputStream): EtaBackupSummary = withContext(Dispatchers.IO) {
        val document = readDocument(input)
        validate(document)
        document.summary()
    }

    private suspend fun snapshot(context: Context): EtaBackupDocument {
        val appContext = context.applicationContext
        val database = EtaDatabase.get(appContext)
        val providers = database.providerDao().providers().map { provider ->
            EtaBackupProvider(
                provider = provider.provider,
                models = provider.models,
            )
        }
        val conversations = database.conversationDao()
        val settings = SettingsDataStore.settings()
        val conversationRows = conversations.conversationEntities()
        return EtaBackupDocument(
            exportedAt = System.currentTimeMillis(),
            providers = providers,
            selectedProviderId = settings.selectedProviderId,
            selectedModelId = settings.selectedModelId,
            conversations = conversationRows,
            messages = conversations.messages(),
            contextCheckpoints = conversations.contextCheckpoints(),
            conversationState = conversations.state(),
            memoryMd = AgentMemoryRepository.snapshot().content,
            roleplay = CharacterBackupTransfer.snapshot(appContext, conversationRows),
        )
    }

    private fun readDocument(input: InputStream): EtaBackupDocument {
        val bytes = input.readBytesLimited(MAX_BACKUP_BYTES)
        if (bytes.isEmpty()) throw EtaBackupException("备份文件为空")
        return runCatching {
            json.decodeFromString<EtaBackupDocument>(bytes.toString(Charsets.UTF_8))
        }.getOrElse { failure ->
            throw EtaBackupException("备份文件格式无效", failure)
        }
    }

    private fun validate(document: EtaBackupDocument) {
        if (document.format != EtaBackupDocument.FORMAT) {
            throw EtaBackupException("这不是 Eta 备份文件")
        }
        if (document.schemaVersion !in 1..EtaBackupDocument.SCHEMA_VERSION) {
            throw EtaBackupException("不支持的 Eta 备份版本：${document.schemaVersion}")
        }

        val providerIds = document.providers.map { it.provider.id }
        if (providerIds.size != providerIds.toSet().size || providerIds.any(String::isBlank)) {
            throw EtaBackupException("备份中的模型提供商存在重复或无效 ID")
        }
        val modelIds = document.providers.flatMap { provider ->
            val ids = provider.models.map { it.id }
            if (ids.size != ids.toSet().size || ids.any(String::isBlank)) {
                throw EtaBackupException("备份中的模型存在重复或无效 ID")
            }
            if (provider.models.any { it.providerId != provider.provider.id }) {
                throw EtaBackupException("备份中的模型与提供商不匹配")
            }
            provider.models.map { model -> model.id to provider.provider.id }
        }
        if (modelIds.size != modelIds.map { it.first }.toSet().size) {
            throw EtaBackupException("备份中的模型 ID 重复")
        }
        if (document.selectedProviderId != null && document.selectedProviderId !in providerIds) {
            throw EtaBackupException("备份中的当前提供商不存在")
        }
        val selectedModel = document.selectedModelId?.let { selectedId ->
            modelIds.firstOrNull { it.first == selectedId }
        }
        if (document.selectedModelId != null && selectedModel == null) {
            throw EtaBackupException("备份中的当前模型不存在")
        }
        if (selectedModel != null && selectedModel.second != document.selectedProviderId) {
            throw EtaBackupException("备份中的当前模型与提供商不匹配")
        }

        val conversationIds = document.conversations.map { it.id }
        if (conversationIds.size != conversationIds.toSet().size || conversationIds.any(String::isBlank)) {
            throw EtaBackupException("备份中的会话存在重复或无效 ID")
        }
        if (document.messages.any { it.conversationId !in conversationIds }) {
            throw EtaBackupException("备份中的消息缺少所属会话")
        }
        val messageIds = document.messages.map { it.id }
        if (messageIds.any(String::isBlank) || messageIds.size != messageIds.toSet().size) {
            throw EtaBackupException("备份中的消息 ID 重复")
        }
        val messagePositions = document.messages.map { it.conversationId to it.sortIndex }
        if (messagePositions.size != messagePositions.toSet().size) {
            throw EtaBackupException("备份中的消息顺序重复")
        }
        val checkpointIds = document.contextCheckpoints.map { it.conversationId }
        if (checkpointIds.size != checkpointIds.toSet().size) {
            throw EtaBackupException("备份中的上下文检查点重复")
        }
        if (document.contextCheckpoints.any { it.conversationId !in conversationIds }) {
            throw EtaBackupException("备份中的上下文检查点缺少所属会话")
        }
        if (document.conversationState != null &&
            document.conversationState.id != ConversationStateEntity.SINGLETON_ID
        ) {
            throw EtaBackupException("备份中的会话状态无效")
        }
        if (document.conversationState?.selectedConversationId !in conversationIds &&
            document.conversationState != null
        ) {
            throw EtaBackupException("备份中的当前会话不存在")
        }
        if (document.memoryMd.toByteArray(Charsets.UTF_8).size > 1024 * 1024) {
            throw EtaBackupException("MEMORY.md 超过 1 MiB 限制")
        }
        try {
            CharacterBackupTransfer.validateRevisions(document.conversations, document.messages)
            val roleplay = document.roleplay
            if (roleplay != null) CharacterBackupTransfer.validate(roleplay, document.conversations)
            else CharacterBackupTransfer.validateBindings(document.conversations)
        } catch (failure: IllegalArgumentException) {
            throw EtaBackupException("备份中的角色数据无效", failure)
        }
    }

    private fun EtaBackupDocument.summary(): EtaBackupSummary = EtaBackupSummary(
        providerCount = providers.size,
        modelCount = providers.sumOf { it.models.size },
        conversationCount = conversations.size,
        messageCount = messages.size,
        memoryBytes = memoryMd.toByteArray(Charsets.UTF_8).size,
        characterCount = roleplay?.characters?.size ?: 0,
    )
}

private fun InputStream.readBytesLimited(maxBytes: Long): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0L
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        total += count
        if (total > maxBytes) {
            throw EtaBackupException("备份文件超过 64 MiB 限制")
        }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
