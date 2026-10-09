package io.github.mangi.eta.validation

import android.app.ActivityOptions
import android.app.Instrumentation
import android.app.UiAutomation
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.room.Room
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.display.VirtualScreenViewerActivity
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.db.LearningProposalEntity
import io.github.mangi.eta.data.model.AppearanceSettings
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.data.provider.BuiltinProviders
import io.github.mangi.eta.data.repository.AgentMemoryStore
import io.github.mangi.eta.data.repository.LearningProposalRepository
import io.github.mangi.eta.ui.app.AgentAppTheme
import io.github.mangi.eta.ui.app.CharacterLibraryStore
import io.github.mangi.eta.ui.app.NotificationCenterStore
import io.github.mangi.eta.ui.components.AgentChatInputBar
import io.github.mangi.eta.ui.model.AgentContextUsageUi
import io.github.mangi.eta.ui.model.AgentModelPickerUiState
import io.github.mangi.eta.ui.screens.characters.DefaultAssistantPromptScreen
import io.github.mangi.eta.ui.screens.notifications.LearningProposalDetailScreen
import io.github.mangi.eta.ui.screens.notifications.NotificationCenterScreen
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/** All learning writes and screenshots use isolated fixtures, never the user's memory or skills. */
internal class DeviceFeatureUiValidation(
    private val instrumentation: Instrumentation,
    private val automation: UiAutomation,
    private val record: (String) -> Unit,
) {
    private val context get() = instrumentation.targetContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun run() {
        val original = runBlocking { SettingsDataStore.settings() }
        automation.adoptShellPermissionIdentity("android.permission.QUERY_ALL_PACKAGES", "android.permission.REORDER_TASKS")
        val activity = instrumentation.startActivitySync(Intent(context, VirtualScreenViewerActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), ActivityOptions.makeBasic().setLaunchDisplayId(0).toBundle()) as VirtualScreenViewerActivity
        try {
            instrumentation.runOnMainSync {
                activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                activity.getSystemService(android.app.ActivityManager::class.java).moveTaskToFront(activity.taskId, 0)
            }
            verify("feature UI uses primary display", activity.display?.displayId == 0)
            eventually("feature UI has primary focus") { activity.hasWindowFocus() }
            validateDefaultPrompt(activity)
            validateInterjectionInput(activity)
            validateInbox(activity)
        } catch (error: Throwable) {
            runCatching { screenshot(activity, "feature-ui-failure.png") }
            throw error
        } finally {
            scope.cancel()
            runBlocking { SettingsDataStore.updateSettings { original } }
            runBlocking { io.github.mangi.eta.data.repository.RuntimeConfigRepository.syncToRemotePreferences(io.github.mangi.eta.EtaApp.serviceInstance) }
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    private fun validateInterjectionInput(activity: VirtualScreenViewerActivity) {
        var restoredDraft by mutableStateOf("")
        val submitted = AtomicReference<String?>()
        val stopped = AtomicInteger()
        instrumentation.runOnMainSync {
            activity.setContent {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = true) {
                    Box(Modifier.fillMaxSize()) {
                        AgentChatInputBar(
                            input = restoredDraft,
                            modelPickerState = AgentModelPickerUiState(),
                            isCompacting = false,
                            contextUsage = AgentContextUsageUi(null, null),
                            showContextUsage = false,
                            isStreaming = true,
                            reasoningEffort = ReasoningEffort.DEFAULT,
                            availableReasoningEfforts = emptyList(),
                            pendingImages = emptyList(),
                            pendingFileReferences = emptyList(),
                            isEditingMessage = false,
                            editHasLaterTurns = false,
                            preserveFollowingMessages = false,
                            onReasoningEffortChange = {},
                            onCompactContext = {},
                            canCompactContext = false,
                            onModelSelected = {},
                            onSubmit = submitted::set,
                            onStop = { stopped.incrementAndGet() },
                            onAttachImage = {},
                            onRemoveImage = {},
                            onAttachFiles = {},
                            onAttachFolder = {},
                            onAttachFilePath = {},
                            onRemoveFileReference = {},
                            onCancelMessageEdit = {},
                            modifier = Modifier.align(Alignment.BottomCenter).padding(14.dp),
                        )
                    }
                }
            }
        }
        val stop = context.getString(R.string.chat_stop)
        val interject = context.getString(R.string.overlay_supplement)
        eventually("running input is editable") { node { it.isEditable } != null }
        verify("empty running input keeps stop available", node { it.contentDescription?.contains(stop) == true } != null)
        val supplement = "先确认页面状态，再执行下一步。"
        setInput(supplement)
        eventually("typed running input exposes interjection action") { node { it.contentDescription?.contains(interject) == true } != null }
        verify("typed running input also keeps stop available", node { it.contentDescription?.contains(stop) == true } != null)
        screenshot(activity, "chat-interjection.png")
        clickNode(checkNotNull(node { it.contentDescription?.contains(interject) == true }), interject)
        eventually("interjection submits exact typed text") { submitted.get() == supplement }
        eventually("interjection clears submitted draft") { node { it.isEditable && it.text.isNullOrEmpty() } != null }
        verify("interjection does not stop the task", stopped.get() == 0)
        val newer = "下一条尚未发送的草稿"
        setInput(newer)
        instrumentation.runOnMainSync { restoredDraft = "未确认收到的旧插话" }
        instrumentation.waitForIdleSync()
        eventually("delayed rejection preserves newer typed draft") { node { it.isEditable && it.text?.toString() == newer } != null }
        clickNode(checkNotNull(node { it.contentDescription?.contains(stop) == true }), stop)
        eventually("separate stop action stops exactly once") { stopped.get() == 1 }
        verify("stop does not resubmit current draft", submitted.get() == supplement)
    }

    private fun setInput(text: String) {
        verify("edit running input", checkNotNull(node { it.isEditable }).performAction(
            AccessibilityNodeInfo.ACTION_SET_TEXT,
            Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) },
        ))
    }

    private fun validateDefaultPrompt(activity: VirtualScreenViewerActivity) {
        val text = "你是 Eta。回答要清晰，操作后确认结果。UI validation fixture."
        runBlocking { SettingsDataStore.updateSettings { it.copy(defaultAssistantSystemPrompt = text) } }
        val store = CharacterLibraryStore(context, scope)
        instrumentation.runOnMainSync {
            store.loadDefaultPrompt()
            activity.setContent { AgentAppTheme(AppearanceSettings(), applyInterfaceScale = true) { DefaultAssistantPromptScreen(store, {}) } }
        }
        eventually("default prompt loads into editor") { node { it.isEditable && it.text?.contains(text) == true } != null }
        screenshot(activity, "default-system-prompt.png")
        val edited = "$text\n新增规则：优先使用已确认的界面状态。"
        verify("default prompt accepts editing", node { it.isEditable }!!.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,
            Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, edited) }))
        instrumentation.runOnMainSync {
            activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                .hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
        }
        eventually("save prompt button visible") { node { it.text?.toString() == context.getString(R.string.default_prompt_save) } != null }
        click(context.getString(R.string.default_prompt_save))
        eventually("edited prompt persists") { runBlocking { SettingsDataStore.settings() }.defaultAssistantSystemPrompt == edited }
        click(context.getString(R.string.default_prompt_reset))
        eventually("reset restores built-in draft") { store.defaultPromptDraft == BuiltinProviders.DEFAULT_SYSTEM_PROMPT }
        click(context.getString(R.string.default_prompt_save))
        eventually("reset persists default marker") { runBlocking { SettingsDataStore.settings() }.defaultAssistantSystemPrompt.isEmpty() }
        val reloaded = CharacterLibraryStore(context, scope)
        instrumentation.runOnMainSync { reloaded.loadDefaultPrompt() }
        eventually("new editor reloads built-in default") { reloaded.defaultPromptDraft == BuiltinProviders.DEFAULT_SYSTEM_PROMPT }
    }

    private fun validateInbox(activity: VirtualScreenViewerActivity) {
        val db = Room.inMemoryDatabaseBuilder(context, EtaDatabase::class.java).build()
        val directory = File(context.cacheDir, "validation-learning-${System.nanoTime()}").apply { mkdirs() }
        val memory = AgentMemoryStore(directory)
        memory.replaceAll("# Fixture memory\nOriginal")
        val repo = LearningProposalRepository(context, db.learningProposalDao(), memory::preview, memory::mutate,
            permitted = { _, _ -> true }, memoryUpdated = {})
        try {
            val id = runBlocking(Dispatchers.IO) {
                repo.stage("memory_write", JSONObject().put("mode", "append").put("revision", memory.snapshot().revision)
                    .put("content", "# Confirmed fixture preference\nPrefer concise answers."), "fixture", "fixture-run")
                    .getString("proposal_id")
            }
            val rejected = runBlocking(Dispatchers.IO) {
                repo.stage("memory_write", JSONObject().put("mode", "append").put("revision", memory.snapshot().revision)
                    .put("content", "Rejected fixture proposal"), "fixture", "reject-run").getString("proposal_id")
            }
            val skill = "fixture-skill"
            runBlocking(Dispatchers.IO) {
                db.learningProposalDao().insert(LearningProposalEntity(skill, "skill", "fixture-check-results",
                    "# Check results\n1. Observe the screen.\n2. Act once.\n3. Verify the result.",
                    JSONObject().put("action", "create").put("skillId", "fixture-check-results")
                        .put("description", "Use when verifying UI actions").put("bodyMarkdown", "# Check results").toString(),
                    "fixture", "skill-run", false, createdAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis()))
            }
            val store = NotificationCenterStore(context, scope, repo)
            var selected by mutableStateOf<String?>(null)
            val refinement = AtomicReference<String?>()
            instrumentation.runOnMainSync {
                activity.setContent {
                    AgentAppTheme(AppearanceSettings(), applyInterfaceScale = true) {
                        val detail = selected
                        if (detail == null) NotificationCenterScreen(store, { selected = it }, {})
                        else LearningProposalDetailScreen(detail, store, { refinement.set(it) }, { selected = null })
                    }
                }
            }
            eventually("notification center shows three pending proposals") { store.pendingCount == 3 && node { it.text?.contains("fixture-check-results") == true } != null }
            screenshot(activity, "notification-center.png")
            verify("staging leaves isolated memory unchanged", memory.snapshot().content == "# Fixture memory\nOriginal")
            click("Confirmed fixture preference")
            eventually("proposal detail opens") { selected == id && node { it.text?.contains(context.getString(R.string.inbox_approve)) == true } != null }
            screenshot(activity, "learning-proposal-detail.png")
            click(context.getString(R.string.inbox_approve))
            eventually("UI approval applies exactly one write") { runBlocking(Dispatchers.IO) { repo.get(id)?.status == "approved" } }
            verify("approved fixture content is present", memory.snapshot().content.endsWith("Prefer concise answers."))
            eventually("approved proposal cannot be approved twice") { node { it.text?.contains(context.getString(R.string.inbox_approve)) == true && clickableAncestor(it) != null } == null }
            instrumentation.runOnMainSync { selected = rejected }
            eventually("second proposal detail ready") { node { it.text?.contains(context.getString(R.string.inbox_reject)) == true && clickableAncestor(it) != null } != null }
            val beforeReject = memory.snapshot()
            click(context.getString(R.string.inbox_reject))
            eventually("UI rejection persists") { runBlocking(Dispatchers.IO) { repo.get(rejected)?.status == "rejected" } }
            verify("UI rejection does not write memory", memory.snapshot() == beforeReject)
            instrumentation.runOnMainSync { selected = skill }
            eventually("skill detail shown") { node { it.text?.contains("Check results") == true } != null }
            click(context.getString(R.string.inbox_refine))
            eventually("refine callback receives original proposal") { refinement.get()?.contains("fixture-check-results") == true }
            eventually("refined proposal is superseded") { runBlocking(Dispatchers.IO) { repo.get(skill)?.status == "refining" } }
        } finally { scope.cancel(); db.close(); directory.deleteRecursively() }
    }

    private fun node(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        automation.clearCache()
        val queue = java.util.ArrayDeque<AccessibilityNodeInfo>()
        automation.windowsOnAllDisplays.get(0).orEmpty().mapNotNull { it.root }
            .filter { it.packageName?.toString() == context.packageName }.forEach(queue::add)
        var visited = 0
        while (queue.isNotEmpty() && visited++ < 1_500) {
            val next = queue.removeFirst()
            if (predicate(next)) return next
            for (index in 0 until next.childCount) next.getChild(index)?.let(queue::add)
        }
        return null
    }

    private fun click(text: String) {
        var found: AccessibilityNodeInfo? = null
        for (attempt in 0 until 8) {
            found = node { it.text?.contains(text) == true && it.isVisibleToUser && clickableAncestor(it) != null }
            if (found != null) break
            node { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            SystemClock.sleep(200)
        }
        clickNode(checkNotNull(found) { "Button unavailable: $text" }, text)
    }

    private fun clickNode(target: AccessibilityNodeInfo, text: String) {
        var current = target
        repeat(6) {
            if (current.isClickable) {
                val bounds = android.graphics.Rect().also(current::getBoundsInScreen)
                val start = SystemClock.uptimeMillis()
                val down = android.view.MotionEvent.obtain(start, start, android.view.MotionEvent.ACTION_DOWN,
                    bounds.centerX().toFloat(), bounds.centerY().toFloat(), 0)
                val up = android.view.MotionEvent.obtain(start, start + 50, android.view.MotionEvent.ACTION_UP,
                    bounds.centerX().toFloat(), bounds.centerY().toFloat(), 0)
                try { verify("click $text", automation.injectInputEvent(down, true) && automation.injectInputEvent(up, true)) }
                finally { down.recycle(); up.recycle() }
                return
            }
            current = current.parent ?: return@repeat
        }
        error("Button not clickable: $text")
    }

    private fun clickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        repeat(6) {
            if (current?.isClickable == true) return current
            current = current?.parent
        }
        return null
    }

    private fun eventually(label: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 8_000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
        verify(label, condition())
    }

    private fun verify(label: String, success: Boolean) { check(success) { label }; record("PASS: $label") }

    private fun screenshot(activity: VirtualScreenViewerActivity, name: String) {
        val image = Bitmap.createBitmap(activity.window.decorView.width, activity.window.decorView.height, Bitmap.Config.ARGB_8888)
        val ready = java.util.concurrent.CountDownLatch(1)
        var result = -1
        android.view.PixelCopy.request(activity.window, image, { result = it; ready.countDown() }, android.os.Handler(android.os.Looper.getMainLooper()))
        check(ready.await(3, java.util.concurrent.TimeUnit.SECONDS) && result == android.view.PixelCopy.SUCCESS) { "FIXTURE_SCREENSHOT_FAILED" }
        try {
            val directory = File(context.getExternalFilesDir(null), "validation").apply { mkdirs() }
            File(directory, name).outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { image.recycle() }
    }
}
