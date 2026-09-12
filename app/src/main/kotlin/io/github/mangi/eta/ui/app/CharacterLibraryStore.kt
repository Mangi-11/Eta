package io.github.mangi.eta.ui.app

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.mangi.eta.agent.roleplay.CharacterCard
import io.github.mangi.eta.agent.roleplay.CharacterCardCodec
import io.github.mangi.eta.agent.roleplay.CharacterCardFormat
import io.github.mangi.eta.agent.roleplay.CharacterCardException
import io.github.mangi.eta.agent.roleplay.CharacterCardCompatibility
import io.github.mangi.eta.agent.roleplay.CharacterBookDraft
import io.github.mangi.eta.agent.roleplay.CharacterProfile
import io.github.mangi.eta.agent.roleplay.RoleplayBinding
import io.github.mangi.eta.agent.roleplay.UserPersona
import io.github.mangi.eta.core.AndroidAgentLogger
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
    var showArchived by mutableStateOf(false)
        private set
    var selected by mutableStateOf<CharacterProfile?>(null)
        private set
    var compatibilityWarnings by mutableStateOf<List<String>>(emptyList())
        private set
    var draft by mutableStateOf<CharacterCard?>(null)
        private set
    var draftName by mutableStateOf("")
        private set
    var avatarDraft by mutableStateOf<ByteArray?>(null)
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

    val filteredCharacters: List<CharacterProfile>
        get() {
            val term = query.trim()
            return characters.filter {
                (showArchived || !it.archived) && (term.isEmpty() ||
                    it.card.name.contains(term, ignoreCase = true) ||
                    it.card.tags.any { tag -> tag.contains(term, ignoreCase = true) })
            }
        }

    fun loadLibrary() = runOperation("角色库读取失败，请重试", queueIfBusy = true) {
        characters = io { CharacterRepository.list(includeArchived = true) }
    }

    fun toggleArchived() { showArchived = !showArchived }
    fun dismissNotice() { notice = null }

    fun loadDetail(id: String) = runOperation("角色读取失败，请返回角色库重试", queueIfBusy = true) {
        val profile = io { CharacterRepository.get(id) } ?: error("CHARACTER_NOT_FOUND")
        val warnings = io { CharacterCardCompatibility.warnings(profile.card) }
        if (selected?.id != id) greetingIndex = 0
        selected = profile
        compatibilityWarnings = warnings
        persona = io { CharacterRepository.persona() }
    }

    fun loadEditor(id: String?) {
        if (editorLoaded && editorKey == id) return
        runOperation("角色读取失败，请重试", queueIfBusy = true) {
            val profile = id?.let { io { CharacterRepository.get(it) } ?: error("CHARACTER_NOT_FOUND") }
            selected = profile
            draft = profile?.card ?: CharacterCardCodec.create("新角色")
            draftName = profile?.card?.name.orEmpty()
            avatarDraft = null
            editorKey = id
            editorLoaded = true
        }
    }

    fun discardEditor() {
        editorLoaded = false
        editorKey = null
        draft = null
        avatarDraft = null
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
            notice = "世界书设置无效，请检查扫描深度、预算与条目位置"
        }
    }

    fun importCard(uri: Uri, onImported: (String) -> Unit) = runOperation(
        "导入失败，请确认文件是完整的 PNG 或 JSON 角色卡，且未超过大小限制",
        diagnoseCardImport = true,
    ) {
        val profile = io {
            context.contentResolver.openInputStream(uri)?.use { CharacterRepository.import(it) }
                ?: error("CHARACTER_INPUT_UNAVAILABLE")
        }
        selected = profile
        compatibilityWarnings = emptyList()
        characters = io { CharacterRepository.list(includeArchived = true) }
        onImported(profile.id)
    }

    fun importAvatar(uri: Uri) = runOperation("头像读取失败，请选择大小不超过 32 MiB 的图片") {
        avatarDraft = io {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val bytes = stream.readNBytes(CharacterRepository.MAX_FILE_BYTES + 1)
                require(bytes.size <= CharacterRepository.MAX_FILE_BYTES) { "CHARACTER_AVATAR_TOO_LARGE" }
                bytes
            } ?: error("CHARACTER_AVATAR_UNAVAILABLE")
        }
    }

    fun saveEditor(onSaved: (String) -> Unit) {
        val originalDraft = draft ?: return
        if (draftName.isBlank()) {
            notice = "请填写角色名称"
            return
        }
        val card = originalDraft.withEdits(name = draftName)
        val original = selected
        val avatar = avatarDraft
        runOperation("角色保存失败，请重试") {
            val profile = io {
                if (original == null) CharacterRepository.create(card, avatar)
                else CharacterRepository.save(original.copy(card = card), avatar)
            }
            selected = profile
            compatibilityWarnings = emptyList()
            discardEditor()
            characters = io { CharacterRepository.list(includeArchived = true) }
            onSaved(profile.id)
        }
    }

    fun duplicate(id: String, onDuplicated: (String) -> Unit) = runOperation("角色复制失败，请重试") {
        val profile = io { CharacterRepository.duplicate(id) }
        characters = io { CharacterRepository.list(includeArchived = true) }
        onDuplicated(profile.id)
    }

    fun archive(id: String, archived: Boolean) = runOperation("角色归档状态保存失败，请重试") {
        io { CharacterRepository.archive(id, archived) }
        selected = io { CharacterRepository.get(id) }
        characters = io { CharacterRepository.list(includeArchived = true) }
    }

    fun export(id: String, format: CharacterCardFormat, uri: Uri) = runOperation("角色卡导出失败，请重试") {
        io {
            context.contentResolver.openOutputStream(uri, "wt")?.use {
                CharacterRepository.export(id, format, it)
            } ?: error("CHARACTER_OUTPUT_UNAVAILABLE")
        }
        notice = "角色卡已导出"
    }

    fun startConversation(id: String, onReady: (RoleplayBinding, String) -> Unit) = runOperation(
        "新对话创建失败，请重试",
    ) {
        val profile = io { CharacterRepository.get(id) } ?: error("CHARACTER_NOT_FOUND")
        val storedBinding = io { CharacterRepository.binding(id) }
        val binding = if (usePersona) storedBinding else storedBinding.copy(userName = "用户", userDescription = "")
        val greetings = listOf(profile.card.firstMessage) + profile.card.alternateGreetings
        onReady(binding, greetings.getOrElse(greetingIndex) { profile.card.firstMessage })
    }

    fun loadPersona() {
        if (personaLoaded && personaDraft != persona) return
        runOperation("用户人设读取失败，请重试", queueIfBusy = true) {
            persona = io { CharacterRepository.persona() }
            personaDraft = persona
            personaLoaded = true
        }
    }

    fun updatePersona(name: String = personaDraft.name, description: String = personaDraft.description) {
        if (!busy) personaDraft = UserPersona(name, description)
    }

    fun savePersona(onSaved: () -> Unit) = runOperation("用户人设保存失败，请重试") {
        val normalized = personaDraft.copy(name = personaDraft.name.trim().ifBlank { "用户" })
        io { CharacterRepository.savePersona(normalized) }
        persona = normalized
        personaDraft = normalized
        onSaved()
    }

    fun loadMemory(id: String, force: Boolean = false) {
        if (!force && memoryCharacterId == id && memorySnapshot != null && memoryDraft != memorySnapshot?.content) return
        runOperation("剧情记忆读取失败，请重试", queueIfBusy = true) {
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
        runOperation("剧情记忆保存失败，请检查内容长度后重试") {
            when (val result = io {
                CharacterMemoryRepository.replaceAllIfRevision(context, id, original.revision, content)
            }) {
                is AgentMemoryWriteResult.Success -> {
                    memorySnapshot = result.snapshot
                    notice = "剧情记忆已保存"
                }
                is AgentMemoryWriteResult.Conflict -> {
                    notice = "剧情记忆已被对话更新。当前草稿仍保留，请复制需要的内容后重新载入，再合并保存。"
                }
            }
        }
    }

    private fun runOperation(
        failure: String,
        queueIfBusy: Boolean = false,
        diagnoseCardImport: Boolean = false,
        block: suspend () -> Unit,
    ) {
        if (operation?.isActive == true) {
            if (queueIfBusy) pendingLoad = { runOperation(failure, queueIfBusy = true, block = block) }
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
                notice = if (diagnoseCardImport) {
                    characterCardImportMessage((error as? CharacterCardException)?.code) ?: failure
                } else failure
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
