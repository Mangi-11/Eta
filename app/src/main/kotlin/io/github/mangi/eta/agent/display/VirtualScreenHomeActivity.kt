package io.github.mangi.eta.agent.display

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.lifecycleScope
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.AppearanceSettings
import io.github.mangi.eta.data.repository.AppearanceSettingsRepository
import io.github.mangi.eta.ui.app.AgentAppTheme
import io.github.mangi.eta.ui.components.EtaCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** A navigator for this display's existing tasks; it never opens the phone's launcher or global Recents. */
internal class VirtualScreenHomeActivity : ComponentActivity() {
    private data class Task(val id: Int, val label: String, val icon: android.graphics.Bitmap?)
    private var tasks by mutableStateOf<List<Task>>(emptyList())
    private var mode by mutableStateOf("HOME")
    private var failed by mutableStateOf(false)
    private var sessionId = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (!acceptIntent(intent)) { finish(); return }
        setContent {
            val appearance by AppearanceSettingsRepository.settingsFlow().collectAsState(initial = AppearanceSettings())
            AgentAppTheme(appearance, applyInterfaceScale = true) {
                val state by VirtualScreenSession.state.collectAsState()
                androidx.compose.runtime.LaunchedEffect(state.display?.sessionId) {
                    if (state.display?.sessionId != sessionId) finish()
                }
                Column(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.background).padding(top = 48.dp, start = 20.dp, end = 20.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    Text(stringResource(if (mode == "MENU") R.string.virtual_screen_menu else R.string.virtual_screen_home), style = MiuixTheme.textStyles.headline1, color = MiuixTheme.colorScheme.onBackground)
                    if (tasks.isEmpty()) Text(stringResource(R.string.virtual_screen_home_empty), color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    if (failed) Text(stringResource(R.string.virtual_screen_input_failed), color = MiuixTheme.colorScheme.error)
                    LazyVerticalGrid(columns = GridCells.Adaptive(if (mode == "MENU") 260.dp else 130.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(tasks, key = { it.id }) { task ->
                            EtaCard(onClick = { select(task) }, insideMargin = PaddingValues(16.dp), modifier = Modifier.fillMaxWidth()) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    task.icon?.let { Image(it.asImageBitmap(), contentDescription = null, modifier = Modifier.size(32.dp)) }
                                    Text(task.label, style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurface)
                                }
                            }
                        }
                    }
                }
            }
        }
        loadTasks()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (!acceptIntent(intent)) { finish(); return }
        loadTasks()
    }

    private fun acceptIntent(next: Intent): Boolean {
        val info = VirtualScreenSession.state.value.display ?: return false
        if (info.displayId != display?.displayId || next.getStringExtra("sessionId") != info.sessionId) return false
        sessionId = info.sessionId
        mode = next.getStringExtra("mode") ?: "HOME"
        return true
    }

    private fun loadTasks() {
        failed = false
        lifecycleScope.launch {
            val next = withContext(Dispatchers.IO) {
                val result = JSONObject(VirtualScreenSession.tasksForViewer(this@VirtualScreenHomeActivity, sessionId).content)
                val array = result.optJSONArray("tasks") ?: return@withContext emptyList()
                (0 until array.length()).map { index ->
                    val task = array.getJSONObject(index)
                    val pkg = task.getString("packageName")
                    val app = runCatching { packageManager.getApplicationInfo(pkg, 0) }.getOrNull()
                    Task(task.getInt("taskId"), app?.let { packageManager.getApplicationLabel(it).toString() } ?: pkg,
                        app?.let { runCatching { packageManager.getApplicationIcon(it).toBitmap(64, 64) }.getOrNull() })
                }
            }
            tasks = next
        }
    }

    private fun select(task: Task) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                VirtualScreenSession.inputForViewer(this@VirtualScreenHomeActivity, sessionId,
                    JSONObject().put("action", "switch_task").put("taskId", task.id))
            }
            failed = !JSONObject(result.content).optBoolean("ok")
        }
    }
}
