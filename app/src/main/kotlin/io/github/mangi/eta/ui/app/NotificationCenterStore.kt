package io.github.mangi.eta.ui.app

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.mangi.eta.R
import io.github.mangi.eta.data.db.LearningProposalEntity
import io.github.mangi.eta.data.repository.LearningProposalRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class NotificationCenterStore(
    private val context: Context,
    private val scope: CoroutineScope,
    private val repository: LearningProposalRepository = LearningProposalRepository(context),
) {
    var proposals by mutableStateOf<List<LearningProposalEntity>>(emptyList())
        private set
    var loaded by mutableStateOf(false)
        private set
    var busy by mutableStateOf(false)
        private set
    var notice by mutableStateOf<String?>(null)
        private set
    val pendingCount get() = proposals.count { it.status == "pending" }
    val unreadCount get() = proposals.count { !it.read }

    init {
        scope.launch { repository.observe().collect { proposals = it; loaded = true } }
    }

    fun markRead(id: String) { scope.launch(Dispatchers.IO) { repository.markRead(id) } }
    suspend fun detail(id: String) = withContext(Dispatchers.IO) { repository.get(id) }
    fun dismissNotice() { notice = null }
    fun approve(id: String) = operate { withContext(Dispatchers.IO) { repository.approve(id) }; Unit }
    fun reject(id: String) = operate { withContext(Dispatchers.IO) { repository.reject(id) }; Unit }

    fun refine(id: String, onReady: (String) -> Unit) = operate {
        val prompt = withContext(Dispatchers.IO) { repository.refinementPrompt(id) } ?: return@operate
        onReady(prompt)
        withContext(Dispatchers.IO) { repository.markRefining(id) }
    }

    private fun operate(block: suspend () -> Unit) {
        if (busy) return
        scope.launch {
            busy = true
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { notice = context.getString(R.string.inbox_operation_failed) }
            finally { busy = false }
        }
    }
}
