package io.github.mangi.eta.ui.components.pane

import android.app.Application
import android.content.res.Configuration
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.AppearanceSettings
import io.github.mangi.eta.data.model.AppearanceThemeMode
import io.github.mangi.eta.ui.app.AgentAppTheme
import io.github.mangi.eta.ui.model.ConversationModeUi
import io.github.mangi.eta.ui.model.ConversationPaneUiState
import io.github.mangi.eta.ui.model.ConversationSummaryUi
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowLooper
import io.github.mangi.eta.ui.components.label
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36])
@LooperMode(LooperMode.Mode.PAUSED)
class ConversationPanePanelTest {

    @Suppress("DEPRECATION")
    @Before
    fun resetLocaleForEnglishAssertions() {
        val resources = RuntimeEnvironment.getApplication().resources
        val configuration = Configuration(resources.configuration).apply {
            setLocale(Locale.US)
        }
        resources.updateConfiguration(configuration, resources.displayMetrics)
    }

    @Test
    fun sidePaneComposesAcrossThemeModesAndHandlesNavigationActions() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val activity = controller.get()
        val frameClock = BroadcastFrameClock()
        val recomposeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + frameClock)
        val recomposer = Recomposer(recomposeScope.coroutineContext)

        var appearance by mutableStateOf(
            AppearanceSettings(themeMode = AppearanceThemeMode.LIGHT),
        )
        var paneState by mutableStateOf(
            ConversationPaneUiState(
                conversations = listOf(
                    ConversationSummaryUi(
                        id = "conv-pinned",
                        title = "置顶系统调试",
                        preview = "检查权限",
                        timeLabel = "今天",
                        updatedAtMillis = System.currentTimeMillis(),
                        mode = ConversationModeUi.Chat,
                        isPinned = true,
                    ),
                    ConversationSummaryUi(
                        id = "conv-today",
                        title = "你好，你是谁呀",
                        preview = "我是 Eta",
                        timeLabel = "今天",
                        updatedAtMillis = System.currentTimeMillis(),
                        mode = ConversationModeUi.Chat,
                        isActiveRun = true,
                        runStatus = "思考中",
                    ),
                    ConversationSummaryUi(
                        id = "conv-role",
                        title = "清晨咖啡馆",
                        preview = "早安",
                        timeLabel = "昨天",
                        updatedAtMillis = 1_000L,
                        mode = ConversationModeUi.Chat,
                        characterName = "林岚",
                    ),
                ),
                selectedConversationId = "conv-today",
                searchQuery = "",
            ),
        )
        var hasPermissionAttention by mutableStateOf(true)
        var composedQuery by mutableStateOf("")
        var composedAttention by mutableStateOf(true)

        var newChatCount = 0
        var selectedConversation: String? = null
        var modelsClicks = 0
        var charactersClicks = 0
        var toolsClicks = 0
        var skillsClicks = 0
        var permissionsClicks = 0
        var settingsClicks = 0

        try {
            mountWithDeterministicRecomposer(activity, recomposeScope, recomposer) {
                AgentAppTheme(appearance = appearance) {
                    composedQuery = paneState.searchQuery
                    composedAttention = hasPermissionAttention
                    ConversationSidePaneScaffold(
                        state = paneState,
                        visible = true,
                        backHandlerEnabled = true,
                        onOpen = {},
                        onDismiss = {},
                        onSearchChange = { query ->
                            paneState = paneState.copy(searchQuery = query)
                        },
                        onNewConversation = { newChatCount++ },
                        onConversationSelected = { selectedConversation = it },
                        onConversationTogglePin = {},
                        onConversationRename = {},
                        onConversationExport = {},
                        onConversationDelete = {},
                        onOpenSettings = { settingsClicks++ },
                        onOpenModelProviders = { modelsClicks++ },
                        onOpenTools = { toolsClicks++ },
                        onOpenSkills = { skillsClicks++ },
                        onOpenCharacters = { charactersClicks++ },
                        onOpenPermissions = { permissionsClicks++ },
                        activeModelLabel = "claude-sonnet-4",
                        hasPermissionAttention = hasPermissionAttention,
                    ) {
                        Box(Modifier.fillMaxSize())
                    }
                }
            }

            for (theme in listOf(AppearanceThemeMode.LIGHT, AppearanceThemeMode.DARK)) {
                appearance = AppearanceSettings(themeMode = theme)
                pumpFrames(activity, frameClock)

                val allTexts = collectAllTexts(activity)
                assertTrue(
                    "Expected workbench models entry for $theme",
                    allTexts.any { it.contains("claude-sonnet-4") },
                )
                assertTrue(
                    "Expected conversation title for $theme",
                    allTexts.any { it == "你好，你是谁呀" },
                )
            }

            // 切回浅色验证工作台、底栏与新建按钮交互
            appearance = AppearanceSettings(themeMode = AppearanceThemeMode.LIGHT)
            pumpFrames(activity, frameClock)

            clickByLabel(activity, activity.getString(R.string.action_new_conversation))
            clickByLabel(activity, activity.getString(R.string.conversation_dock_models))
            clickByLabel(activity, activity.getString(R.string.conversation_dock_characters))
            clickByLabel(activity, activity.getString(R.string.route_tools))
            clickByLabel(activity, activity.getString(R.string.route_skills))
            clickByLabel(activity, activity.getString(R.string.conversation_permission_attention))
            clickByLabel(activity, activity.getString(R.string.route_settings))
            clickByLabel(activity, "置顶系统调试")

            assertEquals(1, newChatCount)
            assertEquals(1, modelsClicks)
            assertEquals(1, charactersClicks)
            assertEquals(1, toolsClicks)
            assertEquals(1, skillsClicks)
            assertEquals(1, permissionsClicks)
            assertEquals(1, settingsClicks)
            assertEquals("conv-pinned", selectedConversation)

            // 权限全部就绪后描述切换为正常“权限”，搜索时工作台收起以聚焦结果
            hasPermissionAttention = false
            paneState = paneState.copy(searchQuery = "咖啡")
            pumpFrames(activity, frameClock)

            assertEquals(false, composedAttention)
            assertEquals("咖啡", composedQuery)

            val searchTexts = collectAllTexts(activity)
            assertFalse(
                "Workbench should hide while searching: $searchTexts",
                searchTexts.any { it == "claude-sonnet-4" },
            )
            assertTrue(
                "Healthy permission button should use default label",
                searchTexts.any { it == activity.getString(R.string.route_permissions) },
            )

            // 点击清空按钮恢复工作台展示
            clickByLabel(activity, activity.getString(R.string.page_clear_84fcd7))
            pumpFrames(activity, frameClock)
            assertEquals("", paneState.searchQuery)
            val restoredTexts = collectAllTexts(activity)
            assertTrue(
                "Workbench should reappear after clearing search",
                restoredTexts.any { it == "claude-sonnet-4" },
            )

            // 深色模式下同样验证新建对话、工作台入口与底栏圆形按钮点击
            appearance = AppearanceSettings(themeMode = AppearanceThemeMode.DARK)
            pumpFrames(activity, frameClock)
            clickByLabel(activity, activity.getString(R.string.action_new_conversation))
            clickByLabel(activity, activity.getString(R.string.conversation_dock_models))
            clickByLabel(activity, activity.getString(R.string.conversation_dock_characters))
            clickByLabel(activity, activity.getString(R.string.route_tools))
            clickByLabel(activity, activity.getString(R.string.route_skills))
            clickByLabel(activity, activity.getString(R.string.route_permissions))
            clickByLabel(activity, activity.getString(R.string.route_settings))
            assertEquals(2, newChatCount)
            assertEquals(2, modelsClicks)
            assertEquals(2, charactersClicks)
            assertEquals(2, toolsClicks)
            assertEquals(2, skillsClicks)
            assertEquals(2, permissionsClicks)
            assertEquals(2, settingsClicks)
        } finally {
            recomposer.cancel()
            recomposeScope.cancel()
            ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS)
            controller.pause().stop().destroy()
            ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS)
        }
    }

    @Test
    fun sidePaneShowsEmptyStateWhenNoConversationsExist() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val activity = controller.get()
        val frameClock = BroadcastFrameClock()
        val recomposeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + frameClock)
        val recomposer = Recomposer(recomposeScope.coroutineContext)
        var conversations by mutableStateOf(
            listOf(
                ConversationSummaryUi(
                    id = "conv-1",
                    title = "临时对话",
                    preview = "测试",
                    timeLabel = "今天",
                    updatedAtMillis = System.currentTimeMillis(),
                    mode = ConversationModeUi.Chat,
                ),
            ),
        )

        try {
            mountWithDeterministicRecomposer(activity, recomposeScope, recomposer) {
                AgentAppTheme(
                    appearance = AppearanceSettings(themeMode = AppearanceThemeMode.LIGHT),
                ) {
                    ConversationSidePaneScaffold(
                        state = ConversationPaneUiState(
                            conversations = conversations,
                            selectedConversationId = conversations.firstOrNull()?.id,
                            searchQuery = "",
                        ),
                        visible = true,
                        backHandlerEnabled = true,
                        onOpen = {},
                        onDismiss = {},
                        onSearchChange = {},
                        onNewConversation = {},
                        onConversationSelected = {},
                        onConversationTogglePin = {},
                        onConversationRename = {},
                        onConversationExport = {},
                        onConversationDelete = {},
                        onOpenSettings = {},
                        onOpenModelProviders = {},
                        onOpenTools = {},
                        onOpenSkills = {},
                        onOpenCharacters = {},
                        onOpenPermissions = {},
                    ) {
                        Box(Modifier.fillMaxSize())
                    }
                }
            }
            pumpFrames(activity, frameClock)
            assertTrue(collectAllTexts(activity).any { it == "临时对话" })

            conversations = emptyList()
            pumpFrames(activity, frameClock)
            val emptyTexts = collectAllTexts(activity)
            assertTrue(
                "Empty state title should be shown: $emptyTexts",
                emptyTexts.any { it == activity.getString(R.string.conversation_empty) },
            )
            assertTrue(
                "Empty state hint should be shown: $emptyTexts",
                emptyTexts.any { it == activity.getString(R.string.conversation_empty_hint) },
            )
        } finally {
            recomposer.cancel()
            recomposeScope.cancel()
            ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS)
            controller.pause().stop().destroy()
            ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS)
        }
    }

    @Test
    fun roleplayConversationRowRendersNonZeroAvatarInSelectedAndUnselectedStates() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val activity = controller.get()
        val frameClock = BroadcastFrameClock()
        val recomposeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + frameClock)
        val recomposer = Recomposer(recomposeScope.coroutineContext)
        var selectedId by mutableStateOf<String?>("conv-other")
        val conversations = listOf(
            ConversationSummaryUi(
                id = "conv-other",
                title = "普通会话",
                preview = "你好",
                timeLabel = "今天",
                updatedAtMillis = System.currentTimeMillis(),
                mode = ConversationModeUi.Chat,
            ),
            ConversationSummaryUi(
                id = "conv-role",
                title = "清晨咖啡馆",
                preview = "早安",
                timeLabel = "今天",
                updatedAtMillis = System.currentTimeMillis() - 1_000L,
                mode = ConversationModeUi.Chat,
                characterName = "林岚",
            ),
        )

        try {
            mountWithDeterministicRecomposer(activity, recomposeScope, recomposer) {
                AgentAppTheme(
                    appearance = AppearanceSettings(themeMode = AppearanceThemeMode.LIGHT),
                ) {
                    ConversationSidePaneScaffold(
                        state = ConversationPaneUiState(
                            conversations = conversations,
                            selectedConversationId = selectedId,
                            searchQuery = "",
                        ),
                        visible = true,
                        backHandlerEnabled = true,
                        onOpen = {},
                        onDismiss = {},
                        onSearchChange = {},
                        onNewConversation = {},
                        onConversationSelected = {},
                        onConversationTogglePin = {},
                        onConversationRename = {},
                        onConversationExport = {},
                        onConversationDelete = {},
                        onOpenSettings = {},
                        onOpenModelProviders = {},
                        onOpenTools = {},
                        onOpenSkills = {},
                        onOpenCharacters = {},
                        onOpenPermissions = {},
                    ) {
                        Box(Modifier.fillMaxSize())
                    }
                }
            }

            for (selection in listOf("conv-other", "conv-role")) {
                selectedId = selection
                pumpFrames(activity, frameClock)
                val avatarNode = collectSemanticsNodes(activity.window.decorView, unmerged = true)
                    .firstOrNull { node ->
                        node.config.getOrNull(SemanticsProperties.Text)
                            .orEmpty()
                            .any { it.text == "林" }
                    }
                assertTrue(
                    "Expected monogram avatar node for selection=$selection",
                    avatarNode != null,
                )
                val bounds = avatarNode!!.boundsInWindow
                assertTrue(
                    "Expected non-zero avatar bounds for selection=$selection, got $bounds",
                    bounds.width > 0f && bounds.height > 0f,
                )
            }
        } finally {
            recomposer.cancel()
            recomposeScope.cancel()
            ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS)
            controller.pause().stop().destroy()
            ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS)
        }
    }

    @Test
    fun sidePaneSearchCoordinatesButtonsFocusAndBack() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val activity = controller.get()
        val frameClock = BroadcastFrameClock()
        val recomposeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + frameClock)
        val recomposer = Recomposer(recomposeScope.coroutineContext)

        var paneState by mutableStateOf(
            ConversationPaneUiState(
                conversations = listOf(
                    ConversationSummaryUi(
                        id = "conv-1",
                        title = "周末计划",
                        preview = "整理清单",
                        timeLabel = "今天",
                        updatedAtMillis = System.currentTimeMillis(),
                        mode = ConversationModeUi.Chat,
                    ),
                ),
                selectedConversationId = "conv-1",
                searchQuery = "",
            ),
        )
        var paneDismissCount = 0
        var permissionsClicks = 0
        var settingsClicks = 0

        try {
            mountWithDeterministicRecomposer(activity, recomposeScope, recomposer) {
                AgentAppTheme(appearance = AppearanceSettings(themeMode = AppearanceThemeMode.LIGHT)) {
                    ConversationSidePaneScaffold(
                        state = paneState,
                        visible = true,
                        backHandlerEnabled = true,
                        onOpen = {},
                        onDismiss = { paneDismissCount++ },
                        onSearchChange = { query -> paneState = paneState.copy(searchQuery = query) },
                        onNewConversation = {},
                        onConversationSelected = {},
                        onConversationTogglePin = {},
                        onConversationRename = {},
                        onConversationExport = {},
                        onConversationDelete = {},
                        onOpenSettings = { settingsClicks++ },
                        onOpenModelProviders = {},
                        onOpenTools = {},
                        onOpenSkills = {},
                        onOpenCharacters = {},
                        onOpenPermissions = { permissionsClicks++ },
                        activeModelLabel = "claude-sonnet-4",
                        hasPermissionAttention = false,
                    ) {
                        Box(Modifier.fillMaxSize())
                    }
                }
            }
            controller.windowFocusChanged(true)

            val permissionsLabel = activity.getString(R.string.route_permissions)
            val settingsLabel = activity.getString(R.string.route_settings)
            val cancelLabel = activity.getString(R.string.action_cancel)

            repeat(8) { pumpFrames(activity, frameClock) }

            // 收起态：权限、设置按钮可见可点，取消按钮不展示
            val collapsedTexts = collectAllTexts(activity)
            assertTrue("Expected permissions button collapsed", collapsedTexts.any { it == permissionsLabel })
            assertTrue("Expected settings button collapsed", collapsedTexts.any { it == settingsLabel })
            assertFalse("Cancel button should stay hidden when collapsed", collapsedTexts.any { it == cancelLabel })

            // 点击搜索输入框展开搜索：取消按钮出现，权限/设置按钮退出语义与交互范围
            val searchInput = collectSemanticsNodes(activity.window.decorView).first { node ->
                node.config.contains(SemanticsActions.SetText)
            }
            searchInput.config[SemanticsActions.OnClick].action?.invoke()
            repeat(12) { pumpFrames(activity, frameClock) }

            val expandedTexts = collectAllTexts(activity)
            assertTrue("Expected cancel button when search expanded: $expandedTexts", expandedTexts.any { it == cancelLabel })
            assertFalse("Permissions button should hide when search expanded", expandedTexts.any { it == permissionsLabel })
            assertFalse("Settings button should hide when search expanded", expandedTexts.any { it == settingsLabel })

            // 输入查询后点击取消：清空查询并恢复权限/设置按钮
            paneState = paneState.copy(searchQuery = "周末")
            pumpFrames(activity, frameClock)
            clickByLabel(activity, cancelLabel)
            repeat(12) { pumpFrames(activity, frameClock) }

            assertEquals("Query should clear on cancel", "", paneState.searchQuery)
            val restoredTexts = collectAllTexts(activity)
            assertTrue("Permissions button should return after cancel", restoredTexts.any { it == permissionsLabel })
            assertTrue("Settings button should return after cancel", restoredTexts.any { it == settingsLabel })

            // 再次展开并用系统返回收起：返回优先关闭搜索，不关闭侧栏
            val searchInputAgain = collectSemanticsNodes(activity.window.decorView).first { node ->
                node.config.contains(SemanticsActions.SetText)
            }
            searchInputAgain.config[SemanticsActions.OnClick].action?.invoke()
            repeat(12) { pumpFrames(activity, frameClock) }
            val previousDismiss = paneDismissCount
            activity.onBackPressedDispatcher.onBackPressed()
            repeat(12) { pumpFrames(activity, frameClock) }

            assertEquals("Back while searching should not dismiss side pane", previousDismiss, paneDismissCount)
            val afterBackTexts = collectAllTexts(activity)
            assertTrue("Permissions button should return after back", afterBackTexts.any { it == permissionsLabel })
            assertTrue("Settings button should return after back", afterBackTexts.any { it == settingsLabel })

            assertEquals(0, permissionsClicks)
            assertEquals(0, settingsClicks)
        } finally {
            recomposer.cancel()
            recomposeScope.cancel()
            ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS)
            controller.pause().stop().destroy()
            ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS)
        }
    }

    private fun mountWithDeterministicRecomposer(
        activity: ComponentActivity,
        scope: CoroutineScope,
        recomposer: Recomposer,
        content: @Composable () -> Unit,
    ) {
        scope.launch { recomposer.runRecomposeAndApplyChanges() }
        val composeView = ComposeView(activity).apply {
            setParentCompositionContext(recomposer)
            setContent(content)
        }
        activity.setContentView(composeView)
    }

    private fun collectAllTexts(activity: ComponentActivity): List<String> =
        collectSemanticsNodes(activity.window.decorView).flatMap { node ->
            (node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }) +
                node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
        }

    private fun clickByLabel(activity: ComponentActivity, label: String) {
        val nodes = collectSemanticsNodes(activity.window.decorView)
        val target = nodes.firstOrNull { node ->
            node.config.contains(SemanticsActions.OnClick) && (
                node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == label } ||
                    node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { it == label }
                )
        } ?: error("Expected clickable node with label '$label'")
        val clicked = target.config[SemanticsActions.OnClick].action?.invoke()
        assertEquals(true, clicked)
    }

    private fun pumpFrames(activity: ComponentActivity, frameClock: BroadcastFrameClock) {
        val looper = shadowOf(Looper.getMainLooper())
        repeat(4) {
            Snapshot.sendApplyNotifications()
            looper.idle()
            ShadowLooper.idleMainLooper(16, TimeUnit.MILLISECONDS)
            frameClock.sendFrame(SystemClock.uptimeMillis() * 1_000_000L)
            measureAndLayoutActivity(activity)
            looper.idle()
        }
    }

    private fun measureAndLayoutActivity(activity: ComponentActivity) {
        val decor = activity.window.decorView
        forceLayoutTree(decor)
        val widthSpec = View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY)
        decor.measure(widthSpec, heightSpec)
        decor.layout(0, 0, 1080, 2400)
    }

    private fun forceLayoutTree(view: View) {
        view.forceLayout()
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                forceLayoutTree(view.getChildAt(i))
            }
        }
    }

    private fun collectSemanticsNodes(root: View, unmerged: Boolean = false): List<SemanticsNode> {
        val result = mutableListOf<SemanticsNode>()
        fun visitView(view: View) {
            if (view is AbstractComposeView) {
                val child = view.getChildAt(0)
                if (child != null) {
                    runCatching {
                        val ownerField = child.javaClass.getMethod("getSemanticsOwner")
                        val semanticsOwner = ownerField.invoke(child)
                        val rootMethod = if (unmerged) "getUnmergedRootSemanticsNode" else "getRootSemanticsNode"
                        val rootNode = semanticsOwner.javaClass
                            .getMethod(rootMethod)
                            .invoke(semanticsOwner) as SemanticsNode
                        fun collect(node: SemanticsNode) {
                            result.add(node)
                            node.children.forEach(::collect)
                        }
                        collect(rootNode)
                    }
                }
            }
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) {
                    visitView(view.getChildAt(i))
                }
            }
        }
        visitView(root)
        return result
    }
}
