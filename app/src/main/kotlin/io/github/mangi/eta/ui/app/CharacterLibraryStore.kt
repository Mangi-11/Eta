package io.github.mangi.eta.ui.app

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.roleplay.CharacterBookDraft
import io.github.mangi.eta.agent.roleplay.CharacterCard
import io.github.mangi.eta.agent.roleplay.CharacterCardCodec
import io.github.mangi.eta.agent.roleplay.CharacterCardCompatibility
import io.github.mangi.eta.agent.roleplay.CharacterCardException
import io.github.mangi.eta.agent.roleplay.CharacterCardFormat
import io.github.mangi.eta.agent.roleplay.CharacterProfile
import io.github.mangi.eta.agent.roleplay.RoleplayBinding
import io.github.mangi.eta.agent.roleplay.UserPersona
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.AppStrings
import io.github.mangi.eta.data.repository.AgentMemorySnapshot
import io.github.mangi.eta.data.repository.AgentMemoryWriteResult
import io.github.mangi.eta.data.repository.CharacterMemoryRepository
import io.github.mangi.eta.data.repository.CharacterRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class CharacterLibraryViewModel(application: Application) : AndroidViewModel(application) {
    val store = CharacterLibraryStore(application, viewModelScope)
}

/** 角色页面才加载资料；配置变更保留编辑草稿，文件操作均在后台完成。 */
internal class CharacterLibraryStore(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    var characters by mutableStateOf<List<CharacterProfile>>(emptyList())
        private set
    var query by mutableStateOf("")
    var selected by mutableStateOf<CharacterProfile?>(null)
        private set
    var compatibilityWarnings by mutableStateOf<List<String>>(emptyList())
        private set
    var draft by mutableStateOf<CharacterCard?>(null)
        private set
    var draftName by mutableStateOf("")
        private set
    var persona by mutableStateOf(UserPersona())
        private set
    var personaDraft by mutableStateOf(UserPersona())
        private set
    var usePersona by mutableStateOf(true)
    var greetingIndex by mutableStateOf(0)
    var memoryDraft by mutableStateOf("")
        private set
    var busy by mutableStateOf(false)
        private set
    var notice by mutableStateOf<String?>(null)
        private set
    private var operation: Job? = null
    private var pendingLoad: (() -> Unit)? = null
    private var editorKey: String? = null
    private var editorLoaded = false
    private var personaLoaded = false
    private var memoryCharacterId: String? = null
    private var memorySnapshot: AgentMemorySnapshot? = null

    /** 收藏置顶，其余按最近更新排列；搜索同时匹配名称与标签。 */
    val filteredCharacters: List<CharacterProfile>
        get() {
            val term = query.trim()
            return characters.filter {
                term.isEmpty() ||
                    it.card.name.contains(term, ignoreCase = true) ||
                    it.card.tags.any { tag -> tag.contains(term, ignoreCase = true) }
            }.sortedWith(compareByDescending<CharacterProfile> { it.card.favorite }.thenByDescending { it.updatedAt })
        }

    fun loadLibrary() = runOperation(
        R.string.character_error_load_library,
        "角色库读取失败，请重试",
        queueIfBusy = true,
    ) {
        io { CharacterRepository.ensureDefaultCharacter() }
        characters = io { CharacterRepository.list() }
    }

    fun dismissNotice() { notice = null }

    fun loadDetail(id: String) = runOperation(
        R.string.character_error_load_detail,
        "角色读取失败，请返回角色库重试",
        queueIfBusy = true,
    ) {
        val profile = io { CharacterRepository.get(id) } ?: error("CHARACTER_NOT_FOUND")
        val warnings = io { CharacterCardCompatibility.warnings(profile.card) }
        if (selected?.id != id) greetingIndex = 0
        selected = profile
        compatibilityWarnings = warnings
        persona = io { CharacterRepository.persona() }
    }

    fun loadEditor(id: String?) {
        if (editorLoaded && editorKey == id) return
        runOperation(
            R.string.character_error_load_editor,
            "角色读取失败，请重试",
            queueIfBusy = true,
        ) {
            val profile = id?.let { io { CharacterRepository.get(it) } ?: error("CHARACTER_NOT_FOUND") }
            selected = profile
            draft = profile?.card ?: CharacterCardCodec.create(
                AppStrings.get(R.string.character_draft_default_name, "新角色"),
            )
            draftName = profile?.card?.name.orEmpty()
            editorKey = id
            editorLoaded = true
        }
    }

    fun discardEditor() {
        editorLoaded = false
        editorKey = null
        draft = null
    }

    fun updateDraft(update: (CharacterCard) -> CharacterCard) {
        if (!busy) draft = draft?.let(update)
    }

    fun updateName(name: String) { if (!busy) draftName = name }

    fun updateWorldbook(update: (CharacterBookDraft) -> CharacterBookDraft) {
        if (busy) return
        try {
            draft = draft?.let { it.withWorldbook(update(it.worldbookDraft())) }
        } catch (_: IllegalArgumentException) {
            notice = AppStrings.get(
                R.string.character_error_invalid_worldbook,
                "世界书设置无效，请检查扫描深度、预算与条目位置",
            )
        }
    }

    fun importCard(uri: Uri, onImported: (String) -> Unit) = runOperation(
        R.string.character_error_import_generic,
        "导入失败，请确认文件是完整的 PNG 或 JSON 角色卡，且未超过大小限制",
        diagnoseCardImport = true,
    ) {
        val profile = io {
            context.contentResolver.openInputStream(uri)?.use { CharacterRepository.import(it) }
                ?: error("CHARACTER_INPUT_UNAVAILABLE")
        }
        selected = profile
        compatibilityWarnings = emptyList()
        characters = io { CharacterRepository.list() }
        onImported(profile.id)
    }

    fun saveEditor(onSaved: (String) -> Unit) {
        val originalDraft = draft ?: return
        if (draftName.isBlank()) {
            notice = AppStrings.get(R.string.character_error_name_required, "请填写角色名称")
            return
        }
        val card = originalDraft.withEdits(name = draftName)
        val original = selected
        runOperation(R.string.character_error_save, "角色保存失败，请重试") {
            val profile = io {
                if (original == null) CharacterRepository.create(card)
                else CharacterRepository.save(original.copy(card = card))
            }
            selected = profile
            compatibilityWarnings = emptyList()
            discardEditor()
            characters = io { CharacterRepository.list() }
            onSaved(profile.id)
        }
    }

    fun duplicate(id: String, onDuplicated: (String) -> Unit) = runOperation(
        R.string.character_error_duplicate,
        "角色复制失败，请重试",
    ) {
        val profile = io { CharacterRepository.duplicate(id) }
        characters = io { CharacterRepository.list() }
        onDuplicated(profile.id)
    }

    fun toggleFavorite(id: String) = runOperation(
        R.string.character_error_favorite,
        "收藏状态保存失败，请重试",
    ) {
        val profile = io { CharacterRepository.get(id) } ?: error("CHARACTER_NOT_FOUND")
        val saved = io { CharacterRepository.save(profile.copy(card = profile.card.withFavorite(!profile.card.favorite))) }
        if (selected?.id == id) selected = saved
        characters = io { CharacterRepository.list() }
    }

    /** 更换头像：图片统一转为 PNG 存入角色目录，导出 PNG 角色卡时即使用新头像。 */
    fun changeAvatar(id: String, uri: Uri) = runOperation(
        R.string.character_error_change_avatar,
        "头像更换失败，请换一张图片重试",
    ) {
        val saved = io {
            context.contentResolver.openInputStream(uri)?.use { CharacterRepository.replaceAvatar(id, it) }
                ?: error("AVATAR_INPUT_UNAVAILABLE")
        }
        if (selected?.id == id) selected = saved
        characters = io { CharacterRepository.list() }
    }

    fun restoreDefaultCharacter() = runOperation(
        R.string.character_error_restore_default,
        "默认角色恢复失败，请重试",
    ) {
        io { CharacterRepository.createDefaultCharacter() }
        characters = io { CharacterRepository.list() }
    }

    fun delete(id: String, onDeleted: () -> Unit) = runOperation(
        R.string.character_error_delete,
        "角色删除失败，请重试",
    ) {
        io { CharacterRepository.delete(id) }
        if (selected?.id == id) selected = null
        characters = io { CharacterRepository.list() }
        onDeleted()
    }

    fun export(id: String, format: CharacterCardFormat, uri: Uri) = runOperation(
        R.string.character_error_export,
        "角色卡导出失败，请重试",
    ) {
        io {
            context.contentResolver.openOutputStream(uri, "wt")?.use {
                CharacterRepository.export(id, format, it)
            } ?: error("CHARACTER_OUTPUT_UNAVAILABLE")
        }
        notice = AppStrings.get(R.string.character_notice_exported, "角色卡已导出")
    }

    fun startConversation(id: String, onReady: (RoleplayBinding, String) -> Unit) = runOperation(
        R.string.character_error_start_conversation,
        "新对话创建失败，请重试",
    ) {
        val profile = io { CharacterRepository.get(id) } ?: error("CHARACTER_NOT_FOUND")
        val storedBinding = io { CharacterRepository.binding(id) }
        val binding = if (usePersona) storedBinding else storedBinding.copy(
            userName = AppStrings.get(R.string.character_default_user_name, "用户"),
            userDescription = "",
        )
        val greetings = listOf(profile.card.firstMessage) + profile.card.alternateGreetings
        onReady(binding, greetings.getOrElse(greetingIndex) { profile.card.firstMessage })
    }

    fun loadPersona() {
        if (personaLoaded && personaDraft != persona) return
        runOperation(
            R.string.character_error_load_persona,
            "用户人设读取失败，请重试",
            queueIfBusy = true,
        ) {
            persona = io { CharacterRepository.persona() }
            personaDraft = persona
            personaLoaded = true
        }
    }

    fun updatePersona(name: String = personaDraft.name, description: String = personaDraft.description) {
        if (!busy) personaDraft = UserPersona(name, description)
    }

    fun savePersona(onSaved: () -> Unit) = runOperation(
        R.string.character_error_save_persona,
        "用户人设保存失败，请重试",
    ) {
        val normalized = personaDraft.copy(
            name = personaDraft.name.trim().ifBlank { AppStrings.get(R.string.character_default_user_name, "用户") },
        )
        io { CharacterRepository.savePersona(normalized) }
        persona = normalized
        personaDraft = normalized
        onSaved()
    }

    fun loadMemory(id: String, force: Boolean = false) {
        if (!force && memoryCharacterId == id && memorySnapshot != null && memoryDraft != memorySnapshot?.content) return
        runOperation(
            R.string.character_error_load_memory,
            "剧情记忆读取失败，请重试",
            queueIfBusy = true,
        ) {
            val snapshot = io { CharacterMemoryRepository.snapshot(context, id) }
            memoryCharacterId = id
            memorySnapshot = snapshot
            memoryDraft = snapshot.content
        }
    }

    fun updateMemory(content: String) { if (!busy) memoryDraft = content }

    fun saveMemory(id: String) {
        val original = memorySnapshot ?: return
        val content = memoryDraft
        runOperation(
            R.string.character_error_save_memory,
            "剧情记忆保存失败，请检查内容长度后重试",
        ) {
            when (val result = io {
                CharacterMemoryRepository.replaceAllIfRevision(context, id, original.revision, content)
            }) {
                is AgentMemoryWriteResult.Success -> {
                    memorySnapshot = result.snapshot
                    notice = AppStrings.get(R.string.character_notice_memory_saved, "剧情记忆已保存")
                }
                is AgentMemoryWriteResult.Conflict -> {
                    notice = AppStrings.get(
                        R.string.character_notice_memory_conflict,
                        "剧情记忆已被对话更新。当前草稿仍保留，请复制需要的内容后重新载入，再合并保存。",
                    )
                }
            }
        }
    }

    private fun runOperation(
        @StringRes failureRes: Int,
        failureFallback: String,
        queueIfBusy: Boolean = false,
        diagnoseCardImport: Boolean = false,
        block: suspend () -> Unit,
    ) {
        if (operation?.isActive == true) {
            if (queueIfBusy) {
                pendingLoad = {
                    runOperation(
                        failureRes = failureRes,
                        failureFallback = failureFallback,
                        queueIfBusy = true,
                        block = block,
                    )
                }
            }
            return
        }
        operation = scope.launch {
            busy = true
            try {
                io { CharacterRepository.initialize(context) }
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                AndroidAgentLogger.warn("CharacterLibrary operation_failed type=${error.javaClass.simpleName}")
                val failureMessage = AppStrings.get(failureRes, failureFallback)
                notice = if (diagnoseCardImport) {
                    characterCardImportMessage((error as? CharacterCardException)?.code) ?: failureMessage
                } else failureMessage
            } finally {
                busy = false
                operation = null
                val next = pendingLoad
                pendingLoad = null
                next?.invoke()
            }
        }
    }

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }
}
