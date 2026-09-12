package io.github.mangi.eta.data.repository

import android.content.Context
import io.github.mangi.eta.agent.roleplay.CharacterCardCodec
import io.github.mangi.eta.agent.roleplay.CharacterCardPng
import io.github.mangi.eta.agent.roleplay.RoleplayBinding
import io.github.mangi.eta.agent.roleplay.RoleplayMessageState
import io.github.mangi.eta.data.db.CharacterEntity
import io.github.mangi.eta.data.db.ConversationEntity
import io.github.mangi.eta.data.db.ConversationMessageEntity
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.db.UserPersonaEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Base64
import java.io.File
import java.io.IOException

@Serializable
internal data class CharacterBackupData(
    val characters: List<CharacterEntity> = emptyList(),
    val persona: UserPersonaEntity? = null,
    val assets: List<CharacterBackupAsset> = emptyList(),
)

@Serializable
internal data class CharacterBackupAsset(
    val characterId: String,
    val avatarBase64: String? = null,
    val memoryMd: String = "",
)

/** 备份内只传文件内容，恢复时重新生成当前设备的私有路径。 */
internal object CharacterBackupTransfer {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    suspend fun snapshot(context: Context, conversations: List<ConversationEntity>): CharacterBackupData {
        CharacterRepository.initialize(context)
        val dao = EtaDatabase.get(context).characterDao()
        val characters = dao.characters()
        val bindings = conversations.mapNotNull { binding(it) }
        val ids = characters.map { it.id }.toSet() + bindings.map { it.characterId }
        return CharacterBackupData(
            characters = characters,
            persona = dao.persona(),
            assets = ids.map { id ->
                val avatar = characters.firstOrNull { it.id == id }?.avatarPath
                    ?: bindings.firstOrNull { it.characterId == id }?.avatarPath
                CharacterBackupAsset(
                    characterId = id,
                    avatarBase64 = CharacterRepository.avatarBytes(avatar)?.let { Base64.getEncoder().encodeToString(it) },
                    memoryMd = CharacterMemoryRepository.snapshot(context, id).content,
                )
            },
        )
    }

    fun validate(data: CharacterBackupData, conversations: List<ConversationEntity>) {
        val ids = data.characters.map { it.id }
        require(ids.size == ids.toSet().size && ids.all(::validId)) { "备份中的角色 ID 重复或无效" }
        data.characters.forEach { character ->
            val card = CharacterCardCodec.decodeJson(character.cardJson)
            require(card.name.length <= 512) { "备份中的角色名称过长" }
        }
        val bindingIds = validateBindings(conversations).map { it.characterId }
        val assets = data.assets.map { it.characterId }
        require(assets.size == assets.toSet().size && assets.all { it in ids || it in bindingIds }) { "备份中的角色资源无效" }
        data.assets.forEach { asset ->
            require(asset.memoryMd.toByteArray(Charsets.UTF_8).size <= 1024 * 1024) { "角色记忆超过 1 MiB 限制" }
            asset.avatarBase64?.let {
                val bytes = Base64.getDecoder().decode(it)
                require(bytes.size <= CharacterRepository.MAX_FILE_BYTES && CharacterCardPng.isPng(bytes)) { "备份中的角色图片无效" }
                CharacterCardPng.validate(bytes)
            }
        }
        data.persona?.let {
            require(it.id == "main" && it.name.isNotBlank() && it.name.length <= 256) { "备份中的用户人设无效" }
            require(it.description.toByteArray(Charsets.UTF_8).size <= 1024 * 1024) { "用户设定超过 1 MiB 限制" }
        }
    }

    fun validateBindings(conversations: List<ConversationEntity>): List<RoleplayBinding> =
        conversations.mapNotNull { binding(it) }.onEach { binding ->
            require(validId(binding.characterId)) { "备份中的角色会话 ID 无效" }
            CharacterCardCodec.decodeJson(binding.cardSnapshotJson)
            require(binding.userName.length <= 256 && binding.userDescription.toByteArray(Charsets.UTF_8).size <= 1024 * 1024) {
                "备份中的角色用户设定过大"
            }
        }

    fun validateRevisions(conversations: List<ConversationEntity>, messages: List<ConversationMessageEntity>) {
        val byConversation = messages.groupBy { it.conversationId }
        conversations.forEach { row ->
            if (row.revisionsJson.isBlank()) return@forEach
            val state = json.decodeFromString<RoleplayMessageState>(row.revisionsJson)
            val localMessages = byConversation[row.id].orEmpty().associateBy { it.id }
            state.links.forEach { (id, link) ->
                require(localMessages[id]?.type in setOf("user", "assistant") && link.transcriptMessageId.isNotBlank() && link.blockOrder >= 0) {
                    "备份中的正文关联缺少有效消息"
                }
            }
            state.revisions.forEach { (id, revision) ->
                require(id in state.links && revision.candidates.isNotEmpty() && revision.selected in revision.candidates.indices) {
                    "备份中的正文候选或选中版本无效"
                }
            }
            state.pendingRewrites.forEach { (runId, target) ->
                require(runId.isNotBlank() && target in state.links && localMessages[target]?.type == "assistant") {
                    "备份中的重新生成目标无效"
                }
            }
        }
    }

    fun restoreAssets(context: Context, data: CharacterBackupData): Map<String, String> {
        CharacterRepository.initialize(context)
        val paths = mutableMapOf<String, String>()
        try {
            data.assets.forEach { asset ->
                asset.avatarBase64?.let { encoded ->
                    paths[asset.characterId] = CharacterRepository.writeAvatar(
                        asset.characterId, Base64.getDecoder().decode(encoded), uniqueName = true,
                    )
                }
            }
            return paths
        } catch (failure: Throwable) {
            discardAssets(paths, failure)
            throw failure
        }
    }

    fun discardAssets(paths: Map<String, String>, failure: Throwable) {
        paths.values.forEach { path ->
            val file = File(path)
            if (file.exists() && !file.delete()) failure.addSuppressed(IOException("Unable to remove staged character avatar"))
        }
    }

    fun restoreMemories(context: Context, data: CharacterBackupData) {
        data.assets.forEach { CharacterMemoryRepository.replaceAll(context, it.characterId, it.memoryMd) }
    }

    fun remapConversation(row: ConversationEntity, avatarPaths: Map<String, String>): ConversationEntity {
        val binding = binding(row) ?: return row
        return row.copy(roleplayJson = json.encodeToString(binding.copy(avatarPath = avatarPaths[binding.characterId])))
    }

    private fun binding(row: ConversationEntity): RoleplayBinding? = row.roleplayJson.takeIf(String::isNotBlank)?.let {
        json.decodeFromString<RoleplayBinding>(it)
    }

    private fun validId(id: String): Boolean = id.matches(Regex("[A-Za-z0-9_-]{1,128}"))
}
