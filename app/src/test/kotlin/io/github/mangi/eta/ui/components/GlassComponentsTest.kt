package io.github.mangi.eta.ui.components

import android.app.Application
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import io.github.mangi.eta.EtaApp
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.AppearanceSettings
import io.github.mangi.eta.data.model.AppearanceThemeMode
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.ui.AppearanceSettingsScreen
import io.github.mangi.eta.ui.app.AgentAppTheme
import io.github.mangi.eta.ui.model.AgentToolsUiState
import io.github.mangi.eta.ui.model.ToolGroupUi
import io.github.mangi.eta.ui.model.ToolItemUi
import io.github.mangi.eta.ui.screens.tools.AgentToolsScreen
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowLooper
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.Refresh

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36])
@LooperMode(LooperMode.Mode.PAUSED)
class GlassComponentsTest {

    @Test
    fun etaGlassSpinnerPreference_displaysSummaryRespectsDisabledOptionAndUpdatesSelection() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val activity = controller.get()
        val frameClock = BroadcastFrameClock()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + frameClock)
        val recomposer = Recomposer(scope.coroutineContext)

        var selectedIndex by mutableIntStateOf(0)

        try {
            mount(activity, scope, recomposer) {
                AgentAppTheme(
                    appearance = AppearanceSettings(uiStyle = AppearanceUiStyle.MIUIX),
                    applyInterfaceScale = false,
                    provideGlassHost = true,
                ) {
                    Column {
                        EtaGlassSpinnerPreference(
                            title = "运行方式",
                            items = listOf(
                                DropdownItem(text = "PRoot", summary = "无需 Root", enabled = true),
                                DropdownItem(text = "Chroot", summary = "需要 Root", enabled = false),
                                DropdownItem(text = "Direct", summary = "原生终端", enabled = true),
                            ),
                            selectedIndex = selectedIndex,
                            onSelectedIndexChange = { selectedIndex = it },
                        )
                    }
                }
            }
            pumpFrames(activity, frameClock)

            // 点击 Preference 行打开 GlassDropdownPopup
            clickByText(activity, "运行方式")
            pumpFrames(activity, frameClock)

            val texts = collectAllTexts(activity)
            assertTrue("Expected summary in dropdown: $texts", texts.any { it == "无需 Root" })
            assertTrue("Expected disabled option summary: $texts", texts.any { it == "需要 Root" })

            // 禁用项不可选中（不会修改 selectedIndex）
            val disabledNode = findClickableNodeByText(activity, "Chroot")
            assertTrue(disabledNode == null || disabledNode.config.contains(SemanticsProperties.Disabled))
            assertEquals(0, selectedIndex)

            // 点击可用项更新选中索引
            clickByText(activity, "Direct")
            pumpFrames(activity, frameClock)
            assertEquals(2, selectedIndex)
        } finally {
            recomposer.cancel()
            scope.cancel()
            ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS)
            controller.pause().stop().destroy()
            ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS)
        }
    }

    @Test
    fun etaGlassSpinnerPreference_worksInStandaloneScaffoldPopupLayoutFallback() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val activity = controller.get()
        val frameClock = BroadcastFrameClock()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + frameClock)
        val recomposer = Recomposer(scope.coroutineContext)

        var selectedIndex by mutableIntStateOf(0)

        try {
            mount(activity, scope, recomposer) {
                AgentAppTheme(
                    appearance = AppearanceSettings(uiStyle = AppearanceUiStyle.MIUIX),
                    applyInterfaceScale = false,
                ) {
                    Scaffold {
                        EtaGlassSpinnerPreference(
                            title = "音色",
                            items = listOf(
                                DropdownItem(text = "Cherry", summary = "清澈女声"),
                                DropdownItem(text = "Ethan", summary = "沉稳男声"),
                            ),
                            selectedIndex = selectedIndex,
                            onSelectedIndexChange = { selectedIndex = it },
                        )
                    }
                }
            }
            pumpFrames(activity, frameClock)

            clickByText(activity, "音色")
            pumpFrames(activity, frameClock)
            assertTrue(collectAllTexts(activity).any { it == "沉稳男声" })

            clickByText(activity, "Ethan")
            pumpFrames(activity, frameClock)
            assertEquals(1, selectedIndex)
        } finally {
            recomposer.cancel()
            scope.cancel()
            ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS)
            controller.pause().stop().destroy()
            ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS)
        }
    }

    @Test
    fun miuixTopBarActionButton_blocksDisabledClicksAndTriggersEnabledClicks() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val activity = controller.get()
        val frameClock = BroadcastFrameClock()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + frameClock)
        val recomposer = Recomposer(scope.coroutineContext)

        var enabled by mutableStateOf(false)
        var clickCount = 0

        try {
            mount(activity, scope, recomposer) {
                AgentAppTheme(
                    appearance = AppearanceUiStyle.MIUIX.let { AppearanceSettings(uiStyle = it) },
                    applyInterfaceScale = false,
                ) {
                    MiuixTopBarActionButton(
                        icon = MiuixIcons.Glass.Refresh,
                        contentDescription = "刷新",
                        enabled = enabled,
                        onClick = { clickCount++ },
                    )
                }
            }
            pumpFrames(activity, frameClock)

            val disabledNode = collectSemanticsNodes(activity.window.decorView).first { node ->
                node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { it == "刷新" } &&
                    node.config.contains(SemanticsActions.OnClick)
            }
            assertTrue(disabledNode.config.contains(SemanticsProperties.Disabled))
            disabledNode.config[SemanticsActions.OnClick].action?.invoke()
            assertEquals(0, clickCount)

            enabled = true
            pumpFrames(activity, frameClock)

            val enabledNode = collectSemanticsNodes(activity.window.decorView).first { node ->
                node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { it == "刷新" } &&
                    node.config.contains(SemanticsActions.OnClick)
            }
            assertFalse(enabledNode.config.contains(SemanticsProperties.Disabled))
            enabledNode.config[SemanticsActions.OnClick].action?.invoke()
            assertEquals(1, clickCount)
        } finally {
            recomposer.cancel()
            scope.cancel()
            ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS)
            controller.pause().stop().destroy()
            ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS)
        }
    }

    @Test
    fun agentToolsScreen_miuixGlassTabRowSwitchesTabs() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val activity = controller.get()
        val frameClock = BroadcastFrameClock()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + frameClock)
        val recomposer = Recomposer(scope.coroutineContext)

        val toolsState = AgentToolsUiState(
            groups = listOf(
                ToolGroupUi(
                    id = "root",
                    title = "高级",
                    tools = listOf(
                        ToolItemUi(id = "get_logcat", title = "日志检查", summary = "需要 Root"),
                    ),
                ),
            ),
        )

        try {
            mount(activity, scope, recomposer) {
                AgentAppTheme(
                    appearance = AppearanceSettings(uiStyle = AppearanceUiStyle.MIUIX),
                    applyInterfaceScale = false,
                ) {
                    AgentToolsScreen(state = toolsState, onAction = {})
                }
            }
            pumpFrames(activity, frameClock)

            val allTabLabel = activity.getString(R.string.capability_all)
            clickByText(activity, allTabLabel)
            pumpFrames(activity, frameClock)

            assertTrue(
                "Switching to All tab should expose root-gated tools",
                collectAllTexts(activity).any { it == "日志检查" },
            )
        } finally {
            recomposer.cancel()
            scope.cancel()
            ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS)
            controller.pause().stop().destroy()
            ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS)
        }
    }

    @Test
    @Config(application = EtaApp::class, sdk = [36])
    fun appearanceSettingsScreen_miuixExposesCmfPaletteAndScaleGlassDialog() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val activity = controller.get()
        val frameClock = BroadcastFrameClock()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + frameClock)
        val recomposer = Recomposer(scope.coroutineContext)

        try {
            mount(activity, scope, recomposer) {
                AgentAppTheme(
                    appearance = AppearanceSettings(
                        uiStyle = AppearanceUiStyle.MIUIX,
                        monetEnabled = true,
                    ),
                    applyInterfaceScale = false,
                ) {
                    AppearanceSettingsScreen(onBack = {})
                }
            }
            pumpFrames(activity, frameClock)

            // 打开配色风格菜单验证 CMF 选项可用
            clickByText(activity, activity.getString(R.string.appearance_palette_style))
            pumpFrames(activity, frameClock)
            assertTrue(
                "Expected CMF palette label in dropdown",
                collectAllTexts(activity).any { it == activity.getString(R.string.appearance_palette_cmf) },
            )
            clickByText(activity, activity.getString(R.string.appearance_palette_cmf))
            pumpFrames(activity, frameClock)

            // 点击界面缩放打开 GlassDialog
            clickByText(activity, activity.getString(R.string.appearance_interface_scale))
            pumpFrames(activity, frameClock)
            assertTrue(
                "Expected scale GlassDialog title",
                collectAllTexts(activity).any { it == activity.getString(R.string.appearance_interface_scale_dialog_title) },
            )
        } finally {
            recomposer.cancel()
            scope.cancel()
            ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS)
            controller.pause().stop().destroy()
            ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS)
        }
    }

    @Test
    @Config(application = EtaApp::class, sdk = [36])
    fun switchingUiStyleInsideNavDisplayAndSceneHostWorksCleanly() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val activity = controller.get()
        val frameClock = BroadcastFrameClock()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + frameClock)
        val recomposer = Recomposer(scope.coroutineContext)

        var appearance by mutableStateOf(
            AppearanceSettings(
                uiStyle = AppearanceUiStyle.MIUIX,
                themeMode = AppearanceThemeMode.LIGHT,
            ),
        )

        try {
            mount(activity, scope, recomposer) {
                AgentAppTheme(
                    appearance = appearance,
                    applyInterfaceScale = true,
                    provideGlassHost = true,
                ) {
                    val backStack = top.yukonga.miuix.kmp.nav.core.rememberNavBackStack<io.github.mangi.eta.ui.navigation.AppRoute>(
                        io.github.mangi.eta.ui.navigation.AppRoute.Home,
                        io.github.mangi.eta.ui.navigation.AppRoute.AppearanceSettings,
                    )
                    top.yukonga.miuix.kmp.nav.core.NavDisplay(
                        backStack = backStack,
                        onBack = {},
                    ) {
                        entry<io.github.mangi.eta.ui.navigation.AppRoute.Home> {
                            top.yukonga.miuix.kmp.basic.Text("Home")
                        }
                        entry<io.github.mangi.eta.ui.navigation.AppRoute.AppearanceSettings> {
                            AppearanceSettingsScreen(onBack = {})
                        }
                    }
                }
            }
            pumpFrames(activity, frameClock)

            // 打开界面样式下拉并点击 COUI
            clickByText(activity, activity.getString(R.string.appearance_ui_style))
            pumpFrames(activity, frameClock)
            clickByText(activity, activity.getString(R.string.appearance_ui_style_coui))
            appearance = appearance.copy(uiStyle = AppearanceUiStyle.COUI)
            pumpFrames(activity, frameClock)
            assertTrue(collectAllTexts(activity).any { it == activity.getString(R.string.appearance_title) })

            // 切回 MIUIX 并等待动画与布局稳定
            appearance = appearance.copy(uiStyle = AppearanceUiStyle.MIUIX)
            repeat(4) { pumpFrames(activity, frameClock) }
            assertTrue(collectAllTexts(activity).any { it == activity.getString(R.string.appearance_title) })

            clickByText(activity, activity.getString(R.string.appearance_theme_mode))
            repeat(4) { pumpFrames(activity, frameClock) }
            val afterClickTexts = collectAllTexts(activity)
            assertTrue(
                "Expected Dark after clicking Theme mode. Available: $afterClickTexts",
                afterClickTexts.any { it == activity.getString(R.string.appearance_theme_dark) },
            )
            appearance = appearance.copy(themeMode = AppearanceThemeMode.DARK)
            pumpFrames(activity, frameClock)
            assertTrue(collectAllTexts(activity).any { it == activity.getString(R.string.appearance_title) })
        } finally {
            recomposer.cancel()
            scope.cancel()
            ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS)
            controller.pause().stop().destroy()
            ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS)
        }
    }

    private fun mount(
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

    private fun findClickableNodeByText(activity: ComponentActivity, text: String): SemanticsNode? =
        collectSemanticsNodes(activity.window.decorView).firstOrNull { node ->
            node.config.contains(SemanticsActions.OnClick) &&
                node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == text }
        }

    private fun clickByText(activity: ComponentActivity, text: String) {
        val node = findClickableNodeByText(activity, text)
            ?: error("Expected clickable node with text '$text'. Available: ${collectAllTexts(activity)}")
        val invoked = node.config[SemanticsActions.OnClick].action?.invoke()
        assertEquals(true, invoked)
    }

    private fun collectAllTexts(activity: ComponentActivity): List<String> =
        collectSemanticsNodes(activity.window.decorView).flatMap { node ->
            node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
                node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
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
