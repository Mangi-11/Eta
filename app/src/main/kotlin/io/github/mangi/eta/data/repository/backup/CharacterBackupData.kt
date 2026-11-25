package io.github.mangi.eta.data.repository.backup

import android.content.Context
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.roleplay.CharacterCardCodec
import io.github.mangi.eta.agent.roleplay.CharacterCardPng
import io.github.mangi.eta.agent.roleplay.RoleplayBinding
import io.github.mangi.eta.agent.roleplay.RoleplayMessageState
import io.github.mangi.eta.core.AppStrings
import io.github.mangi.eta.data.db.CharacterEntity
import io.github.mangi.eta.data.db.ConversationEntity
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.db.UserPersonaEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Base64
import java.io.File
import java.io.IOException
import io.github.mangi.eta.data.repository.CharacterMemoryRepository
import io.github.mangi.eta.data.repository.CharacterRepository

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

    suspend fun snapshot(context: Context, boundAvatars: Map<String, String?>): CharacterBackupData {
        CharacterRepository.initialize(context)
        val dao = EtaDatabase.get(context).characterDao()
        val characters = dao.characters()
        val ids = characters.map { it.id }.toSet() + boundAvatars.keys
        return CharacterBackupData(
            characters = characters,
            persona = dao.persona(),
            assets = ids.map { id ->
                val avatar = characters.firstOrNull { it.id == id }?.avatarPath
                    ?: boundAvatars[id]
                CharacterBackupAsset(
                    characterId = id,
                    avatarBase64 = CharacterRepository.avatarBytes(avatar)?.let { Base64.getEncoder().encodeToString(it) },
                    memoryMd = CharacterMemoryRepository.snapshot(context, id).content,
                )
            },
        )
    }

    fun avatarReference(row: ConversationEntity): Pair<String, String?>? =
        binding(row)?.let { it.characterId to it.avatarPath }

    fun validate(data: CharacterBackupData, bindingIds: Set<String>) {
        val ids = data.characters.map { it.id }
        require(ids.size == ids.toSet().size && ids.all(::validId)) {
            AppStrings.get(R.string.character_backup_invalid_id, "备份中的角色 ID 重复或无效")
        }
        data.characters.forEach { character ->
            val card = CharacterCardCodec.decodeJson(character.cardJson)
            require(card.name.length <= 512) {
                AppStrings.get(R.string.character_backup_name_too_long, "备份中的角色名称过长")
            }
        }
        val assets = data.assets.map { it.characterId }
        require(assets.size == assets.toSet().size && assets.all { it in ids || it in bindingIds }) {
            AppStrings.get(R.string.character_backup_invalid_assets, "备份中的角色资源无效")
        }
        data.assets.forEach { asset ->
            require(asset.memoryMd.toByteArray(Charsets.UTF_8).size <= 1024 * 1024) {
                AppStrings.get(R.string.character_backup_memory_too_large, "角色记忆超过 1 MiB 限制")
            }
            asset.avatarBase64?.let {
                val bytes = Base64.getDecoder().decode(it)
                require(bytes.size <= CharacterRepository.MAX_FILE_BYTES && CharacterCardPng.isPng(bytes)) {
                    AppStrings.get(R.string.character_backup_invalid_avatar, "备份中的角色图片无效")
                }
                CharacterCardPng.validate(bytes)
            }
        }
        data.persona?.let {
            require(it.id == "main" && it.name.isNotBlank() && it.name.length <= 256) {
                AppStrings.get(R.string.character_backup_invalid_persona, "备份中的用户人设无效")
            }
            require(it.description.toByteArray(Charsets.UTF_8).size <= 1024 * 1024) {
                AppStrings.get(R.string.character_repo_persona_too_large, "用户设定超过 1 MiB 限制")
            }
        }
    }

    fun validateBindings(conversations: List<ConversationEntity>): List<RoleplayBinding> =
        conversations.mapNotNull { binding(it) }.onEach { binding ->
            require(validId(binding.characterId)) {
                AppStrings.get(R.string.character_backup_invalid_binding_id, "备份中的角色会话 ID 无效")
            }
            CharacterCardCodec.decodeJson(binding.cardSnapshotJson)
            require(binding.userName.length <= 256 && binding.userDescription.toByteArray(Charsets.UTF_8).size <= 1024 * 1024) {
                AppStrings.get(R.string.character_backup_binding_persona_too_large, "备份中的角色用户设定过大")
            }
        }

    fun validateRevisions(row: ConversationEntity, messageTypes: Map<String, String>) {
        if (row.revisionsJson.isBlank()) return
        val state = json.decodeFromString<RoleplayMessageState>(row.revisionsJson)
        state.links.forEach { (id, link) ->
            require(messageTypes[id] in setOf("user", "assistant") && link.transcriptMessageId.isNotBlank() && link.blockOrder >= 0) {
                AppStrings.get(R.string.character_backup_invalid_link, "备份中的正文关联缺少有效消息")
            }
        }
        state.revisions.forEach { (id, revision) ->
            require(id in state.links && revision.candidates.isNotEmpty() && revision.selected in revision.candidates.indices) {
                AppStrings.get(R.string.character_backup_invalid_revision, "备份中的正文候选或选中版本无效")
            }
        }
        state.pendingRewrites.forEach { (runId, target) ->
            require(runId.isNotBlank() && target in state.links && messageTypes[target] == "assistant") {
                AppStrings.get(R.string.character_backup_invalid_rewrite_target, "备份中的重新生成目标无效")
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
