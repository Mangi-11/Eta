package io.github.mangi.eta.ui.components

import android.app.Application
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.maxLengthTrim
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.EtaApp
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.AppearanceSettings
import io.github.mangi.eta.data.model.AppearanceThemeMode
import io.github.mangi.eta.ui.screens.settings.AppearanceSettingsScreen
import io.github.mangi.eta.ui.app.AgentAppTheme
import io.github.mangi.eta.ui.layout.TopBarBackdropLayout
import io.github.mangi.eta.ui.model.AgentToolsUiState
import io.github.mangi.eta.ui.model.ToolGroupUi
import io.github.mangi.eta.ui.model.ToolItemUi
import io.github.mangi.eta.ui.navigation.AppRoute
import io.github.mangi.eta.ui.screens.tools.AgentToolsScreen
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
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
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.Refresh
import top.yukonga.miuix.kmp.nav.core.NavBackStack
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.core.rememberNavBackStack
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36])
@LooperMode(LooperMode.Mode.PAUSED)
class GlassComponentsTest {

    @Test
    fun topBarBackdropExtendsBelowTheBarWithoutChangingItsHeight() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val activity = controller.get()
        val frameClock = BroadcastFrameClock()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + frameClock)
        val recomposer = Recomposer(scope.coroutineContext)
        var barHeight = 0
        var backdropHeight = 0

        try {
            mount(activity, scope, recomposer) {
                Box {
                    TopBarBackdropLayout(
                        backgroundHeight = { height -> height + 24.dp.roundToPx() },
                        modifier = Modifier.onSizeChanged { barHeight = it.height },
                        backgroundModifier = Modifier.onSizeChanged { backdropHeight = it.height },
                    ) {
                        Box(Modifier.fillMaxWidth().height(52.dp))
                    }
                }
            }
            pumpFrames(activity, frameClock)
            with(Density(activity.resources.displayMetrics.density)) {
                assertEquals(52.dp.roundToPx(), barHeight)
                assertEquals(barHeight + 24.dp.roundToPx(), backdropHeight)
            }
        } finally {
            recomposer.cancel()
            scope.cancel()
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun glassSpinnerPreference_displaysSummaryRespectsDisabledOptionAndUpdatesSelection() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val activity = controller.get()
        val frameClock = BroadcastFrameClock()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + frameClock)
        val recomposer = Recomposer(scope.coroutineContext)

        var selectedIndex by mutableIntStateOf(0)

        try {
            mount(activity, scope, recomposer) {
                AgentAppTheme(
                    appearance = AppearanceSettings(),
                    provideGlassHost = true,
                ) {
                    Column {
                        GlassSpinnerPreference(
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
    fun glassSpinnerPreference_worksInStandaloneScaffoldPopupLayoutFallback() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val activity = controller.get()
        val frameClock = BroadcastFrameClock()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + frameClock)
        val recomposer = Recomposer(scope.coroutineContext)

        var selectedIndex by mutableIntStateOf(0)

        try {
            mount(activity, scope, recomposer) {
                AgentAppTheme(appearance = AppearanceSettings()) {
                    Scaffold {
                        GlassSpinnerPreference(
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
    fun topBarActionButton_blocksDisabledClicksAndTriggersEnabledClicks() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val activity = controller.get()
        val frameClock = BroadcastFrameClock()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + frameClock)
        val recomposer = Recomposer(scope.coroutineContext)

        var enabled by mutableStateOf(false)
        var clickCount = 0

        try {
            mount(activity, scope, recomposer) {
                AgentAppTheme(appearance = AppearanceSettings()) {
                    TopBarActionButton(
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
    fun agentToolsScreen_glassTabRowSwitchesTabs() {
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
                AgentAppTheme(appearance = AppearanceSettings()) {
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
    fun appearanceSettingsScreen_exposesCmfPaletteAndSwitchesThemeMode() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val activity = controller.get()
        val frameClock = BroadcastFrameClock()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + frameClock)
        val recomposer = Recomposer(scope.coroutineContext)

        var appearance by mutableStateOf(
            AppearanceSettings(
                themeMode = AppearanceThemeMode.LIGHT,
                monetEnabled = true,
            ),
        )

        try {
            mount(activity, scope, recomposer) {
                AgentAppTheme(
                    appearance = appearance,
                    provideGlassHost = true,
                ) {
                    val backStack = rememberNavBackStack<AppRoute>(
                        AppRoute.Home,
                        AppRoute.AppearanceSettings,
                    )
                    NavDisplay(
                        backStack = backStack,
                        onBack = {},
                    ) {
                        entry<AppRoute.Home> {
                            Text("Home")
                        }
                        entry<AppRoute.AppearanceSettings> {
                            AppearanceSettingsScreen(onBack = {})
                        }
                    }
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
            repeat(4) { pumpFrames(activity, frameClock) }

            // 验证主题模式切换
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

    @Test
    fun draftEchoPreservesImeCompositionAndExternalReplacementDoesNotEmitAnEdit() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val activity = controller.get()
        val frameClock = BroadcastFrameClock()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + frameClock)
        val recomposer = Recomposer(scope.coroutineContext)
        var draft by mutableStateOf("14")
        val edits = mutableListOf<String>()
        lateinit var connection: InputConnection
        lateinit var editor: TextFieldState

        try {
            mount(activity, scope, recomposer) {
                editor = rememberDraftTextFieldState(draft) {
                    edits += it
                    draft = it
                }
                InterceptPlatformTextInput(interceptor = { request, _ ->
                    connection = request.createInputConnection(EditorInfo())
                    awaitCancellation()
                }) {
                    BasicTextField(state = editor, inputTransformation = DecimalDigits.maxLengthTrim(3))
                }
            }
            pumpFrames(activity, frameClock)
            fun field() = collectSemanticsNodes(activity.window.decorView).single {
                it.config.contains(SemanticsActions.SetText)
            }
            controller.windowFocusChanged(true)
            assertEquals(true, field().config[SemanticsActions.RequestFocus].action!!.invoke())
            pumpFrames(activity, frameClock)
            connection.setSelection(1, 1)
            connection.setComposingText("2", 1)
            pumpFrames(activity, frameClock)
            assertEquals("124", draft)
            assertEquals(TextRange(2), editor.selection)
            assertEquals(TextRange(1, 2), editor.composition)
            connection.finishComposingText()
            field().config[SemanticsActions.SetText].action!!.invoke(AnnotatedString("a1b2345"))
            pumpFrames(activity, frameClock)
            assertEquals("123", draft)
            draft = "85"
            pumpFrames(activity, frameClock)
            assertEquals("85", editor.text.toString())
            assertEquals(TextRange(2), editor.selection)
            assertEquals(listOf("124", "123"), edits)
        } finally {
            recomposer.cancel()
            scope.cancel()
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun secureFieldPreservesSelectionAndExposesPasswordVisibility() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val activity = controller.get()
        val frameClock = BroadcastFrameClock()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + frameClock)
        val recomposer = Recomposer(scope.coroutineContext)
        val state = TextFieldState("example", TextRange(1, 4))
        var visible by mutableStateOf(false)

        try {
            mount(activity, scope, recomposer) {
                AgentAppTheme(appearance = AppearanceSettings()) {
                    SecureTextField(state = state, label = "密钥", visible = visible)
                }
            }
            for (reveal in listOf(false, true, false)) {
                visible = reveal
                pumpFrames(activity, frameClock)
                val field = collectSemanticsNodes(activity.window.decorView).single {
                    it.config.contains(SemanticsProperties.Password)
                }
                assertEquals(!reveal, field.config[SemanticsProperties.IsPasswordObfuscated])
                assertFalse(field.config.contains(SemanticsActions.CopyText))
                assertEquals("example", state.text.toString())
                assertEquals(TextRange(1, 4), state.selection)
            }
        } finally {
            recomposer.cancel()
            scope.cancel()
            controller.pause().stop().destroy()
        }
    }

    @Test
    @Config(application = EtaApp::class, sdk = [36])
    fun globalImagePreviewHostServesNonShellRoutesAndClosesOnRouteChange() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val activity = controller.get()
        val frameClock = BroadcastFrameClock()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + frameClock)
        val recomposer = Recomposer(scope.coroutineContext)

        var galleryPreviewer: ChatImagePreviewer? = null
        lateinit var backStack: NavBackStack

        try {
            mount(activity, scope, recomposer) {
                AgentAppTheme(
                    appearance = AppearanceSettings(),
                    provideGlassHost = true,
                ) {
                    backStack = rememberNavBackStack<AppRoute>(
                        AppRoute.Home,
                        AppRoute.ImageGenGallery,
                    )
                    ChatImagePreviewHost {
                        val hostPreviewer = LocalChatImagePreviewer.current
                        val activeRoute = backStack.lastOrNull()
                        LaunchedEffect(activeRoute) {
                            if (hostPreviewer?.request != null) {
                                hostPreviewer.closeImmediately()
                            }
                        }
                        NavDisplay(
                            backStack = backStack,
                            onBack = {},
                        ) {
                            entry<AppRoute.Home> {
                                Text("Home")
                            }
                            entry<AppRoute.ImageGenGallery> {
                                galleryPreviewer = LocalChatImagePreviewer.current
                                Text("Gallery")
                            }
                        }
                    }
                }
            }
            pumpFrames(activity, frameClock)

            val previewer = requireNotNull(galleryPreviewer) {
                "Non-shell route inside global ChatImagePreviewHost must receive LocalChatImagePreviewer"
            }
            previewer.open(images = listOf("/tmp/eta_preview_test.png"))
            repeat(4) { pumpFrames(activity, frameClock) }

            val saveLabel = activity.getString(R.string.action_save)
            val shareLabel = activity.getString(R.string.ui_misc_share)
            val textsWhileOpen = collectAllTexts(activity)
            assertTrue("Expected Save pill in fullscreen image viewer: $textsWhileOpen", saveLabel in textsWhileOpen)
            assertTrue("Expected Share pill in fullscreen image viewer: $textsWhileOpen", shareLabel in textsWhileOpen)

            backStack.removeLastOrNull()
            pumpFrames(activity, frameClock)

            assertEquals(null, previewer.request)
            assertFalse(previewer.targetOpen)
            val textsAfterPop = collectAllTexts(activity)
            assertFalse("Viewer overlay should close immediately after route change", saveLabel in textsAfterPop)
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
