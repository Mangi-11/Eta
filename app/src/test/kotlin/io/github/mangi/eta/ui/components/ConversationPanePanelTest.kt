package io.github.mangi.eta.ui.components

import android.app.Application
import android.content.res.Configuration
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import java.util.Locale
import java.util.concurrent.TimeUnit
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.AppearanceSettings
import io.github.mangi.eta.data.model.AppearanceThemeMode
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.ui.app.AgentAppTheme
import io.github.mangi.eta.ui.model.ConversationModeUi
import io.github.mangi.eta.ui.model.ConversationPaneUiState
import io.github.mangi.eta.ui.model.ConversationSummaryUi
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
    fun sidePaneComposesAcrossStylesAndThemeModesAndHandlesNavigationActions() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val activity = controller.get()

        var appearance by mutableStateOf(
            AppearanceSettings(
                uiStyle = AppearanceUiStyle.COUI,
                themeMode = AppearanceThemeMode.LIGHT,
            ),
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
            activity.setContent {
                AgentAppTheme(
                    appearance = appearance,
                    applyInterfaceScale = true,
                ) {
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

            for (style in listOf(AppearanceUiStyle.COUI, AppearanceUiStyle.MIUIX)) {
                for (theme in listOf(AppearanceThemeMode.LIGHT, AppearanceThemeMode.DARK)) {
                    appearance = AppearanceSettings(uiStyle = style, themeMode = theme)
                    pumpFrames(activity)

                    val nodes = collectSemanticsNodes(activity.window.decorView)
                    val allTexts = nodes.flatMap { node ->
                        (node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }) +
                            node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
                    }
                    assertTrue(
                        "Expected workbench models entry for $style / $theme",
                        allTexts.any { it.contains("claude-sonnet-4") },
                    )
                    assertTrue(
                        "Expected conversation title for $style / $theme",
                        allTexts.any { it == "你好，你是谁呀" },
                    )
                }
            }

            // 切回 COUI 浅色验证工作台、底栏与新建按钮交互
            appearance = AppearanceSettings(uiStyle = AppearanceUiStyle.COUI, themeMode = AppearanceThemeMode.LIGHT)
            pumpFrames(activity)

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
            pumpFrames(activity)

            assertEquals(false, composedAttention)
            assertEquals("咖啡", composedQuery)

            val searchNodes = collectSemanticsNodes(activity.window.decorView)
            val searchTexts = searchNodes.flatMap { node ->
                (node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }) +
                    node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
            }
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
            pumpFrames(activity)
            assertEquals("", paneState.searchQuery)
            val restoredTexts = collectAllTexts(activity)
            assertTrue(
                "Workbench should reappear after clearing search",
                restoredTexts.any { it == "claude-sonnet-4" },
            )

            // 在 MIUIX 模式下也验证新建对话、工作台入口与底栏圆形按钮点击
            appearance = AppearanceSettings(uiStyle = AppearanceUiStyle.MIUIX, themeMode = AppearanceThemeMode.DARK)
            pumpFrames(activity)
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
            org.robolectric.shadows.ShadowLooper.idleMainLooper(100, java.util.concurrent.TimeUnit.MILLISECONDS)
            controller.pause().stop().destroy()
            org.robolectric.shadows.ShadowLooper.idleMainLooper(100, java.util.concurrent.TimeUnit.MILLISECONDS)
        }
    }

    @Test
    fun sidePaneShowsEmptyStateWhenNoConversationsExist() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val activity = controller.get()
        var style by mutableStateOf(AppearanceUiStyle.COUI)
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
            activity.setContent {
                AgentAppTheme(
                    appearance = AppearanceSettings(
                        uiStyle = style,
                        themeMode = AppearanceThemeMode.LIGHT,
                    ),
                    applyInterfaceScale = true,
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
            pumpFrames(activity)
            assertTrue(collectAllTexts(activity).any { it == "临时对话" })

            conversations = emptyList()
            pumpFrames(activity)
            val couiEmptyTexts = collectAllTexts(activity)
            assertTrue(
                "COUI empty state title should be shown: $couiEmptyTexts",
                couiEmptyTexts.any { it == activity.getString(R.string.conversation_empty) },
            )

            style = AppearanceUiStyle.MIUIX
            pumpFrames(activity)
            val miuixEmptyTexts = collectAllTexts(activity)
            assertTrue(
                "MIUIX empty state title should be shown: $miuixEmptyTexts",
                miuixEmptyTexts.any { it == activity.getString(R.string.conversation_empty) },
            )
            assertTrue(
                "Empty state hint should be shown: $miuixEmptyTexts",
                miuixEmptyTexts.any { it == activity.getString(R.string.conversation_empty_hint) },
            )
        } finally {
            org.robolectric.shadows.ShadowLooper.idleMainLooper(100, java.util.concurrent.TimeUnit.MILLISECONDS)
            controller.pause().stop().destroy()
            org.robolectric.shadows.ShadowLooper.idleMainLooper(100, java.util.concurrent.TimeUnit.MILLISECONDS)
        }
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

    private fun pumpFrames(activity: ComponentActivity) {
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        val looper = shadowOf(Looper.getMainLooper())
        repeat(3) {
            looper.idle()
            ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS)
            measureAndLayoutActivity(activity)
            looper.idle()
            ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS)
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

    private fun collectSemanticsNodes(root: View): List<SemanticsNode> {
        val result = mutableListOf<SemanticsNode>()
        fun visitView(view: View) {
            if (view is AbstractComposeView) {
                val child = view.getChildAt(0)
                if (child != null) {
                    runCatching {
                        val ownerField = child.javaClass.getMethod("getSemanticsOwner")
                        val semanticsOwner = ownerField.invoke(child)
                        val mergedRoot = semanticsOwner.javaClass
                            .getMethod("getRootSemanticsNode")
                            .invoke(semanticsOwner) as SemanticsNode
                        fun collect(node: SemanticsNode) {
                            result.add(node)
                            node.children.forEach(::collect)
                        }
                        collect(mergedRoot)
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
