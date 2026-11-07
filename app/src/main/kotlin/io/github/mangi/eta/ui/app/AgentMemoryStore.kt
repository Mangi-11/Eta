package io.github.mangi.eta.ui.app

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.memory.AgentMemoryContextBuilder
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.data.repository.AgentMemoryRepository
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import io.github.mangi.eta.ui.model.AgentMemoryUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 记忆页的读取、编辑与保存状态；与会话和 Agent run 无共享状态。 */
internal class AgentMemoryStore(
    context: Context,
    private val scope: CoroutineScope,
) {
    private val appContext = context.applicationContext

    private var hasLoadedOnce = false

    var memoryState by mutableStateOf(AgentMemoryUiState())
        private set

    fun refreshMemory() {
        if (!hasLoadedOnce) {
            memoryState = memoryState.copy(isLoading = true, notice = null)
        }
        scope.launch(Dispatchers.IO) {
            runCatching {
                val snapshot = AgentMemoryRepository.snapshot()
                val enabled = AgentMemoryRepository.isEnabled()
                val contextWindow = RuntimeConfigRepository.currentRuntimeConfig()?.contextWindow
                Triple(snapshot, enabled, AgentMemoryContextBuilder.coreBudgetChars(contextWindow))
            }.fold(
                onSuccess = { (snapshot, enabled, coreBudget) ->
                    withContext(Dispatchers.Main) {
                        val keepDraft = hasLoadedOnce && memoryState.hasUnsavedChanges
                        val nextDraft = if (keepDraft) memoryState.draft else snapshot.content
                        val nextBytes = if (keepDraft) memoryState.draftBytes else snapshot.byteSize
                        hasLoadedOnce = true
                        memoryState = AgentMemoryUiState(
                            enabled = enabled,
                            isLoading = false,
                            draft = nextDraft,
                            savedContent = snapshot.content,
                            draftBytes = nextBytes,
                            coreBudgetChars = coreBudget,
                            notice = memoryState.notice,
                        )
                    }
                },
                onFailure = { throwable ->
                    AndroidAgentLogger.warnThrottled("agent_memory_ui_load_failed") {
                        "Agent memory UI load failed: type=${throwable.safeLogType()}"
                    }
                    withContext(Dispatchers.Main) {
                        memoryState = memoryState.copy(
                            isLoading = false,
                            notice = appContext.getString(R.string.state_ui_failed_to_read_memory_please_try_again_later_caeaa6),
                        )
                    }
                },
            )
        }
    }

    fun updateMemoryDraft(content: String) {
        memoryState = memoryState.copy(
            draft = content,
            draftBytes = content.toByteArray(Charsets.UTF_8).size,
            notice = null,
        )
    }

    fun setMemoryEnabled(enabled: Boolean) {
        scope.launch(Dispatchers.IO) {
            runCatching { AgentMemoryRepository.setEnabled(enabled) }
                .fold(
                    onSuccess = {
                        withContext(Dispatchers.Main) {
                            memoryState = memoryState.copy(enabled = enabled, notice = null)
                        }
                    },
                    onFailure = { throwable ->
                        AndroidAgentLogger.warnThrottled("agent_memory_toggle_failed") {
                            "Agent memory setting update failed: type=${throwable.safeLogType()}"
                        }
                        withContext(Dispatchers.Main) {
                            memoryState = memoryState.copy(notice = appContext.getString(R.string.state_ui_memory_switch_failed_to_save_83b5d6))
                        }
                    },
                )
        }
    }

    fun saveMemory() {
        if (!memoryState.canSave) return
        val target = memoryState.draft
        memoryState = memoryState.copy(isSaving = true, notice = null)
        scope.launch(Dispatchers.IO) {
            runCatching { AgentMemoryRepository.replaceAll(target) }
                .fold(
                    onSuccess = { snapshot ->
                        withContext(Dispatchers.Main) {
                            memoryState = memoryState.copy(
                                isSaving = false,
                                savedContent = snapshot.content,
                                draft = if (memoryState.draft == target) {
                                    snapshot.content
                                } else {
                                    memoryState.draft
                                },
                                draftBytes = memoryState.draft.toByteArray(Charsets.UTF_8).size,
                                notice = appContext.getString(R.string.state_ui_memory_saved_a2c61c),
                            )
                        }
                    },
                    onFailure = { throwable ->
                        AndroidAgentLogger.warnThrottled("agent_memory_ui_save_failed") {
                            "Agent memory UI save failed: type=${throwable.safeLogType()}"
                        }
                        withContext(Dispatchers.Main) {
                            memoryState = memoryState.copy(
                                isSaving = false,
                                notice = throwable.message ?: appContext.getString(R.string.state_ui_memory_save_failed_1f501e),
                            )
                        }
                    },
                )
        }
    }

    fun clearMemory() {
        if (memoryState.isSaving) return
        memoryState = memoryState.copy(isSaving = true, notice = null)
        scope.launch(Dispatchers.IO) {
            runCatching { AgentMemoryRepository.replaceAll("") }
                .fold(
                    onSuccess = {
                        withContext(Dispatchers.Main) {
                            memoryState = memoryState.copy(
                                isSaving = false,
                                draft = "",
                                savedContent = "",
                                draftBytes = 0,
                                notice = appContext.getString(R.string.state_ui_memory_cleared_b415bb),
                            )
                        }
                    },
                    onFailure = { throwable ->
                        AndroidAgentLogger.warnThrottled("agent_memory_ui_clear_failed") {
                            "Agent memory UI clear failed: type=${throwable.safeLogType()}"
                        }
                        withContext(Dispatchers.Main) {
                            memoryState = memoryState.copy(
                                isSaving = false,
                                notice = throwable.message ?: appContext.getString(R.string.state_ui_memory_clearing_failed_7f0aba),
                            )
                        }
                    },
                )
        }
    }

    fun dismissMemoryNotice() {
        memoryState = memoryState.copy(notice = null)
    }
}
