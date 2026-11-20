package io.github.mangi.eta.ui.app

import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import io.github.mangi.eta.EtaApp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.browser.BrowserSessionRegistry
import io.github.mangi.eta.agent.overlay.AgentOverlayPhase
import io.github.mangi.eta.agent.overlay.AgentOverlayState
import io.github.mangi.eta.agent.overlay.AgentOverlayStatus
import io.github.mangi.eta.agent.overlay.AgentResultCard
import io.github.mangi.eta.data.model.AppearanceSettings
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.ui.AppearanceSettingsScreen
import io.github.mangi.eta.ui.SettingsScreen
import io.github.mangi.eta.ui.components.coui.CouiBottomSheet
import io.github.mangi.eta.ui.components.coui.CouiConfirmDialog
import io.github.suqi8.coui.kmp.basic.Text
import io.github.mangi.eta.ui.model.AgentMemoryUiState
import io.github.mangi.eta.ui.model.AgentSkillsUiState
import io.github.mangi.eta.ui.model.AgentToolsUiState
import io.github.mangi.eta.ui.model.PermissionHealthItemUi
import io.github.mangi.eta.ui.model.PermissionHealthUiState
import io.github.mangi.eta.ui.model.PermissionStatusUi
import io.github.mangi.eta.ui.model.SkillItemUi
import io.github.mangi.eta.ui.model.ToolGroupUi
import io.github.mangi.eta.ui.model.ToolItemUi
import io.github.mangi.eta.ui.pages.providers.CommunityCatalogScreen
import io.github.mangi.eta.ui.pages.providers.ModelProviderDetailScreen
import io.github.mangi.eta.ui.pages.providers.ModelProviderListScreen
import io.github.mangi.eta.ui.screens.backup.DataBackupScreen
import io.github.mangi.eta.ui.screens.browser.AgentBrowserScreen
import io.github.mangi.eta.ui.screens.characters.CharacterLibraryScreen
import io.github.mangi.eta.ui.screens.characters.CharacterMemoryScreen
import io.github.mangi.eta.ui.screens.characters.CharacterPersonaScreen
import io.github.mangi.eta.ui.screens.enhance.SystemEnhanceScreen
import io.github.mangi.eta.ui.screens.mcp.McpServersScreen
import io.github.mangi.eta.ui.screens.memory.AgentMemoryScreen
import io.github.mangi.eta.ui.screens.permissions.PermissionHealthScreen
import io.github.mangi.eta.ui.screens.settings.AssistantOverlaySettingsScreen
import io.github.mangi.eta.ui.screens.settings.ConversationTitleSettingsScreen
import io.github.mangi.eta.ui.screens.skills.AgentSkillsScreen
import io.github.mangi.eta.ui.screens.subagent.SubagentSettingsScreen
import io.github.mangi.eta.ui.screens.terminal.LinuxEnvironmentScreen
import io.github.mangi.eta.ui.screens.terminal.LinuxFilesScreen
import io.github.mangi.eta.ui.screens.terminal.SharedFoldersScreen
import io.github.mangi.eta.ui.screens.terminal.WorkspaceScreen
import io.github.mangi.eta.ui.screens.tools.AgentToolsScreen
import io.github.mangi.eta.ui.screens.tools.WebToolsSettingsScreen
import io.github.mangi.eta.ui.voice.SpeechOssScreen
import io.github.mangi.eta.ui.voice.SpeechRecognitionScreen
import io.github.mangi.eta.ui.voice.SpeechSettingsScreen
import io.github.mangi.eta.ui.voice.SpeechSynthesisScreen
import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(application = EtaApp::class, sdk = [36])
@LooperMode(LooperMode.Mode.PAUSED)
class UiStyleThemeAndScreensTest {

    @Test
    fun switchingOverlayUiStylePreservesStateAndUsesSystemDensity() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val activity = controller.get()
        val looper = shadowOf(Looper.getMainLooper())
        val baseDensity = activity.resources.displayMetrics.density

        var appearance by mutableStateOf(AppearanceSettings(uiStyle = AppearanceUiStyle.COUI))
        var observedStyle by mutableStateOf<AppearanceUiStyle?>(null)
        var pageDensity by mutableStateOf(0f)
        var dialogDensity by mutableStateOf(0f)
        var mountCount = 0
        var latestDraft = ""

        val frameClock = BroadcastFrameClock()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + frameClock)
        val recomposer = Recomposer(scope.coroutineContext)

        fun settle() {
            repeat(6) {
                Snapshot.sendApplyNotifications()
                looper.idleFor(Duration.ofMillis(16))
                frameClock.sendFrame(SystemClock.uptimeMillis() * 1_000_000L)
                forceLayoutTree(activity.window.decorView)
                measureAndLayoutActivity(activity)
                looper.idle()
            }
        }

        try {
            scope.launch { recomposer.runRecomposeAndApplyChanges() }
            val view = ComposeView(activity)
            view.setParentCompositionContext(recomposer)
            view.setContent {
                AgentAppTheme(
                    appearance = appearance,
                    systemOverlay = true,
                ) {
                    var draftText by rememberSaveable { mutableStateOf("initial-draft") }
                    var counter by remember { mutableIntStateOf(1) }
                    LaunchedEffect(Unit) {
                        mountCount += 1
                        draftText = "edited-draft-$counter"
                    }
                    latestDraft = draftText
                    observedStyle = LocalUiStyle.current
                    pageDensity = LocalDensity.current.density
                    Box(Modifier.size(100.dp))
                    Dialog(onDismissRequest = {}) {
                        dialogDensity = LocalDensity.current.density
                        Box(Modifier.size(40.dp))
                    }
                }
            }
            activity.setContentView(view)
            settle()

            assertEquals(AppearanceUiStyle.COUI, observedStyle)
            assertEquals(1, mountCount)
            assertEquals("edited-draft-1", latestDraft)
            assertEquals(baseDensity, pageDensity, 0.001f)
            assertEquals(baseDensity, dialogDensity, 0.001f)

            appearance = appearance.copy(uiStyle = AppearanceUiStyle.MIUIX)
            settle()

            assertEquals(AppearanceUiStyle.MIUIX, observedStyle)
            assertEquals(1, mountCount)
            assertEquals("edited-draft-1", latestDraft)
            assertEquals(baseDensity, pageDensity, 0.001f)
            assertEquals(baseDensity, activity.resources.displayMetrics.density, 0.001f)

            // 再切回 COUI：状态依然保持不变
            appearance = appearance.copy(uiStyle = AppearanceUiStyle.COUI)
            settle()

            assertEquals(AppearanceUiStyle.COUI, observedStyle)
            assertEquals(1, mountCount)
            assertEquals("edited-draft-1", latestDraft)
        } finally {
            recomposer.cancel()
            scope.cancel()
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun primaryAndSecondaryScreensComposeAndInteractAcrossCouiAndMiuix() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val activity = controller.get()
        val looper = shadowOf(Looper.getMainLooper())
        val appInfo = activity.application.applicationInfo
        val previousLocaleConfig = ReflectionHelpers.getField<Int>(appInfo, "localeConfigRes")

        var uiStyle by mutableStateOf(AppearanceUiStyle.COUI)
        var screenIndex by mutableIntStateOf(0)
        var backPressedCount = 0
        val baseDensity = activity.resources.displayMetrics.density
        var pageDensity = 0f

        val toolsState = AgentToolsUiState(
            groups = listOf(
                ToolGroupUi(
                    id = "device",
                    title = "设备控制",
                    tools = listOf(
                        ToolItemUi(id = "terminal", title = "终端", summary = "执行命令"),
                    ),
                ),
            ),
        )
        val skillsState = AgentSkillsUiState(
            isLoading = false,
            skills = listOf(
                SkillItemUi(
                    id = "demo-skill",
                    name = "Demo Skill",
                    description = "测试技能描述",
                    source = "builtin",
                    installed = true,
                    enabled = true,
                    capabilities = emptyList(),
                ),
            ),
        )
        val permissionsState = PermissionHealthUiState(
            items = listOf(
                PermissionHealthItemUi(
                    id = "overlay",
                    title = "悬浮窗权限",
                    summary = "用于显示运行胶囊",
                    status = PermissionStatusUi.Available,
                    primaryActionLabel = "已授权",
                ),
            ),
        )
        val memoryState = AgentMemoryUiState(
            isLoading = false,
            enabled = true,
            savedContent = "# Memory",
            draft = "# Memory",
        )
        val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val characterStore = CharacterLibraryStore(activity, storeScope)
        val catalogStore = CommunityCatalogStore(activity, storeScope)
        val browserSession = BrowserSessionRegistry.get("test-session")

        try {
            // Robolectric 的 ApplicationInfo 未载入 Manifest 引用的语言资源。
            ReflectionHelpers.setField(appInfo, "localeConfigRes", R.xml.locales_config)
            activity.setContent {
                key(screenIndex, uiStyle) {
                    AgentAppTheme(
                        appearance = AppearanceSettings(uiStyle = uiStyle),
                    ) {
                        pageDensity = LocalDensity.current.density
                        when (screenIndex) {
                            0 -> AppearanceSettingsScreen(onBack = { backPressedCount++ })
                            1 -> SettingsScreen(context = activity, onNavigate = {}, onBack = { backPressedCount++ })
                            2 -> ModelProviderListScreen(onNavigate = {}, onBack = { backPressedCount++ })
                            3 -> ModelProviderDetailScreen(
                                newType = io.github.mangi.eta.ui.navigation.NewProviderType.OpenAiCompatible,
                                onBack = { backPressedCount++ },
                            )
                            4 -> AgentToolsScreen(state = toolsState, onAction = {})
                            5 -> WebToolsSettingsScreen(onBack = { backPressedCount++ })
                            6 -> AgentSkillsScreen(state = skillsState, onAction = {})
                            7 -> SubagentSettingsScreen(onBack = { backPressedCount++ })
                            8 -> McpServersScreen(onNavigate = {}, onBack = { backPressedCount++ })
                            9 -> PermissionHealthScreen(state = permissionsState, onAction = {})
                            10 -> SystemEnhanceScreen(onAction = {})
                            11 -> DataBackupScreen(
                                context = activity,
                                onBack = { backPressedCount++ },
                                onExport = { error("unused") },
                                onImport = { error("unused") },
                            )
                            12 -> AgentMemoryScreen(state = memoryState, onAction = {})
                            13 -> SpeechSettingsScreen(onBack = { backPressedCount++ }, onNavigate = {})
                            14 -> SpeechRecognitionScreen(onBack = { backPressedCount++ }, onOpenOss = {})
                            15 -> SpeechSynthesisScreen(onBack = { backPressedCount++ })
                            16 -> SpeechOssScreen(onBack = { backPressedCount++ })
                            17 -> CommunityCatalogScreen(store = catalogStore, onNavigate = {}, onBack = { backPressedCount++ })
                            18 -> CharacterLibraryScreen(store = characterStore, onNavigate = {}, onBack = { backPressedCount++ })
                            19 -> CharacterPersonaScreen(store = characterStore, onBack = { backPressedCount++ })
                            20 -> CharacterMemoryScreen(id = "char-1", store = characterStore, onBack = { backPressedCount++ })
                            21 -> LinuxEnvironmentScreen(context = activity, onNavigate = {}, onBack = { backPressedCount++ })
                            22 -> LinuxFilesScreen(context = activity, distribution = "alpine", onBack = { backPressedCount++ })
                            23 -> SharedFoldersScreen(context = activity, onBack = { backPressedCount++ })
                            24 -> WorkspaceScreen(onBack = { backPressedCount++ })
                            25 -> AgentBrowserScreen(session = browserSession, onBack = { backPressedCount++ })
                            26 -> AgentResultCard(
                                state = AgentOverlayState(
                                    phase = AgentOverlayPhase.FINISHED,
                                    status = AgentOverlayStatus.ResultReady,
                                    detailText = "Task completed",
                                ),
                                onClose = {},
                                onOpenEta = {},
                            )
                            27 -> AgentAppRoot()
                            28 -> ConversationTitleSettingsScreen(onBack = { backPressedCount++ })
                            29 -> AssistantOverlaySettingsScreen(onBack = { backPressedCount++ })
                        }
                    }
                }
            }

            for (index in 0..29) {
                screenIndex = index
                for (style in listOf(AppearanceUiStyle.COUI, AppearanceUiStyle.MIUIX)) {
                    uiStyle = style
                    looper.idle()
                    measureAndLayoutActivity(activity)
                    looper.idle()
                    assertEquals(baseDensity, pageDensity, 0.001f)
                    val nodes = collectSemanticsNodes(activity.window.decorView)
                    assertTrue(
                        "Expected non-empty semantics tree for screenIndex=$index, style=$style",
                        nodes.isNotEmpty(),
                    )
                }
            }

            // 验证 AppearanceSettingsScreen 在 COUI 模式下的返回按钮语义点击可触发 onBack
            screenIndex = 0
            uiStyle = AppearanceUiStyle.COUI
            looper.idle()
            measureAndLayoutActivity(activity)
            looper.idle()
            val backButtons = collectSemanticsNodes(activity.window.decorView).filter { node ->
                node.config.contains(SemanticsActions.OnClick) &&
                    node.config.getOrNull(SemanticsProperties.ContentDescription)?.any { it.isNotBlank() } == true
            }
            assertTrue(backButtons.isNotEmpty())
            val clicked = backButtons.first().config[SemanticsActions.OnClick].action?.invoke()
            assertEquals(true, clicked)
            assertEquals(1, backPressedCount)
        } finally {
            controller.pause().stop().destroy()
            ReflectionHelpers.setField(appInfo, "localeConfigRes", previousLocaleConfig)
        }
    }

    @Test
    fun couiDialogAndBottomSheetConsumeBackBeforePageAndCanReopen() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val activity = controller.get()
        val looper = shadowOf(Looper.getMainLooper())
        val frameClock = BroadcastFrameClock()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + frameClock)
        val recomposer = Recomposer(scope.coroutineContext)
        val windowInfo = object : WindowInfo {
            override val isWindowFocused = true
            override val containerDpSize = DpSize(400.dp, 800.dp)
        }
        var visible by mutableStateOf(false)
        var useBottomSheet by mutableStateOf(false)
        val sheetListState = LazyListState()
        var dismissals = 0
        var pageBacks = 0

        fun settle() {
            repeat(28) {
                Snapshot.sendApplyNotifications()
                looper.idleFor(Duration.ofMillis(16))
                frameClock.sendFrame(SystemClock.uptimeMillis() * 1_000_000L)
                forceLayoutTree(activity.window.decorView)
                measureAndLayoutActivity(activity)
                looper.idle()
            }
        }

        try {
            scope.launch { recomposer.runRecomposeAndApplyChanges() }
            val view = ComposeView(activity)
            view.setParentCompositionContext(recomposer)
            view.setContent {
                CompositionLocalProvider(LocalWindowInfo provides windowInfo) {
                    AgentAppTheme(
                        appearance = AppearanceSettings(uiStyle = AppearanceUiStyle.COUI, blurEnabled = false),
                    ) {
                        NavigationBackHandler(
                            state = rememberNavigationEventState(NavigationEventInfo.None),
                            onBackCompleted = { pageBacks++ },
                        )
                        Box(Modifier.fillMaxSize())
                        val dismiss: () -> Unit = {
                            dismissals++
                            visible = false
                        }
                        if (useBottomSheet) {
                            CouiBottomSheet(show = visible, title = "测试底部面板", onDismissRequest = dismiss) {
                                LazyColumn(Modifier.fillMaxWidth().height(160.dp), state = sheetListState) {
                                    items(20) { Text("列表条目 ${it + 1}") }
                                }
                            }
                        } else {
                            CouiConfirmDialog(
                                visible = visible,
                                title = "测试弹窗",
                                message = null,
                                confirmText = "确认",
                                onConfirm = {},
                                onDismissRequest = dismiss,
                            )
                        }
                    }
                }
            }
            activity.setContentView(view)
            settle()
            fun showsText(text: String): Boolean =
                collectSemanticsNodes(activity.window.decorView).any { node ->
                    node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true
                }

            listOf(false, true).forEach { bottomSheet ->
                useBottomSheet = bottomSheet
                val title = if (bottomSheet) "测试底部面板" else "测试弹窗"
                repeat(2) {
                    visible = true
                    settle()
                    assertTrue("应显示$title", showsText(title))
                    if (bottomSheet) {
                        val layout = sheetListState.layoutInfo
                        assertTrue(
                            "面板列表应有可见条目，实际视口为 ${layout.viewportSize}",
                            layout.visibleItemsInfo.any { it.index == 0 },
                        )
                    }
                    val previousDismissals = dismissals
                    activity.onBackPressedDispatcher.onBackPressed()
                    settle()
                    assertEquals(previousDismissals + 1, dismissals)
                    assertEquals(0, pageBacks)
                    assertTrue("关闭后应隐藏$title", !showsText(title))
                }
            }
            activity.onBackPressedDispatcher.onBackPressed()
            assertEquals(1, pageBacks)
        } finally {
            recomposer.cancel()
            scope.cancel()
            controller.pause().stop().destroy()
        }
    }

    private fun forceLayoutTree(view: View) {
        view.forceLayout()
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) forceLayoutTree(view.getChildAt(index))
        }
    }

    private fun measureAndLayoutActivity(activity: ComponentActivity) {
        val decor = activity.window.decorView
        val widthSpec = View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY)
        decor.measure(widthSpec, heightSpec)
        decor.layout(0, 0, 1080, 2400)
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
                        val unmergedRoot = semanticsOwner.javaClass
                            .getMethod("getUnmergedRootSemanticsNode")
                            .invoke(semanticsOwner) as SemanticsNode
                        fun collect(node: SemanticsNode) {
                            result.add(node)
                            node.children.forEach(::collect)
                        }
                        collect(unmergedRoot)
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
