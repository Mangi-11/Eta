package io.github.mangi.eta.ui.screens.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.terminal.TerminalKey
import io.github.mangi.eta.agent.terminal.TerminalModifiers
import io.github.mangi.eta.ui.app.ConsoleStore
import io.github.mangi.eta.ui.app.ConsoleUiState
import io.github.mangi.eta.ui.app.UserTerminalStore
import io.github.mangi.eta.ui.components.EtaMonospace
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 终端入口：PTY 可用时默认进入交互式控制台，块式终端作为“跑一条命令看结果”的简洁模式。 */
@Composable
internal fun TerminalEntryScreen(
    terminalStore: UserTerminalStore,
    consoleStore: ConsoleStore,
    onOpenEnvironment: () -> Unit,
) {
    val consoleState by consoleStore.uiState.collectAsState()
    var consoleMode by rememberSaveable { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        terminalStore.refreshLinuxReady()
        consoleStore.refreshLinuxEnvironment()
        consoleStore.probePtySupport()
    }
    when {
        // 探测只检查随包组件，结果几乎立即返回；期间留白，避免先闪出块式终端再切换。
        consoleState.ptySupported == null -> Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MiuixTheme.colorScheme.surface),
        )
        consoleMode && consoleState.ptySupported == true -> ConsoleScreen(
            store = consoleStore,
            terminalStore = terminalStore,
            onExitConsole = { consoleMode = false },
            onOpenEnvironment = onOpenEnvironment,
        )
        else -> UserTerminalScreen(
            store = terminalStore,
            onOpenEnvironment = onOpenEnvironment,
            onOpenConsole = if (consoleState.ptySupported == true) {
                { consoleMode = true }
            } else {
                null
            },
        )
    }
}

/**
 * 控制台：PTY 全屏视图，面向 TUI 与交互式 CLI。
 * 网格区域本身是输入目标，输入法与硬件键盘的输入直接编码为终端字节；键条补齐软键盘没有的按键。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ConsoleScreen(
    store: ConsoleStore,
    terminalStore: UserTerminalStore,
    onExitConsole: () -> Unit,
    onOpenEnvironment: () -> Unit,
) {
    val state by store.uiState.collectAsState()
    val terminalState by terminalStore.uiState.collectAsState()
    var showTasks by remember { mutableStateOf(false) }
    var showSessions by remember { mutableStateOf(false) }
    var composing by remember { mutableStateOf("") }
    val modifierKeys = remember { ConsoleModifierKeys() }
    val keyboard = remember { ConsoleKeyboardController() }
    val keyboardVisible = WindowInsets.isImeVisible
    val clipboard = LocalClipboard.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val inputSink = remember(store, modifierKeys) {
        object : ConsoleInputSink {
            override fun onText(text: String, modifiers: TerminalModifiers) {
                store.sendText(text, modifierKeys.consume(modifiers))
            }

            override fun onKey(key: TerminalKey, modifiers: TerminalModifiers) {
                store.sendKey(key, modifierKeys.consume(modifiers))
            }

            override fun onComposingChange(text: String) {
                composing = text
            }
        }
    }
    LaunchedEffect(keyboard) { keyboard.show() }
    val themeForeground = MiuixTheme.colorScheme.onSurface
    val themeBackground = MiuixTheme.colorScheme.surface
    LaunchedEffect(themeForeground, themeBackground) {
        store.setThemeColors(themeForeground.toArgb().toLong() and 0xFFFFFFFFL, themeBackground.toArgb().toLong() and 0xFFFFFFFFL)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MiuixTheme.colorScheme.surface)
            .imePadding()
            .navigationBarsPadding(),
    ) {
        TerminalToolbar(
            environment = state.environment,
            linuxEnvironment = state.linuxEnvironment,
            onSwitchEnvironment = store::switchEnvironment,
            sessionCount = state.sessions.size,
            daemonRunning = terminalState.daemonTasks.any { it.running },
            onOpenSessions = { showSessions = true },
            onOpenTasks = {
                terminalStore.refreshDaemonTasks()
                showTasks = true
            },
            modeTarget = TerminalModeTarget.BLOCKS,
            onSwitchMode = onExitConsole,
        )
        ConsoleGrid(
            state = state,
            composing = composing,
            onGridSize = { cols, rows -> store.open(state.environment, cols, rows) },
            onReconnect = store::reconnect,
            onOpenEnvironment = onOpenEnvironment,
            modifier = Modifier
                .weight(1f)
                .consoleTextInput(inputSink, keyboard)
                .pointerInput(keyboard) { detectTapGestures { keyboard.show() } },
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(0.5.dp)
                .background(MiuixTheme.colorScheme.dividerLine),
        )
        ConsoleKeyBar(
            modifierKeys = modifierKeys,
            keyboardVisible = keyboardVisible,
            onKey = { inputSink.onKey(it, TerminalModifiers.NONE) },
            onText = { inputSink.onText(it, TerminalModifiers.NONE) },
            onPaste = {
                scope.launch {
                    val clip = clipboard.getClipEntry()?.clipData ?: return@launch
                    val text = (0 until clip.itemCount)
                        .joinToString("") { clip.getItemAt(it).coerceToText(context) }
                    if (text.isNotEmpty()) store.paste(text)
                }
            },
            onToggleKeyboard = { if (keyboardVisible) keyboard.hide() else keyboard.show() },
        )
    }

    DaemonTasksDialog(
        show = showTasks,
        tasks = terminalState.daemonTasks,
        onDismiss = { showTasks = false },
        onStop = terminalStore::stopDaemonTask,
        onLoadLogs = terminalStore::daemonLogs,
    )

    SessionListDialog(
        show = showSessions,
        rows = state.sessions.map { session ->
            SessionDialogRow(
                id = session.id,
                environment = session.environment,
                subtitle = "",
                active = session.id == state.activeSessionId,
                running = !session.exited,
                alive = !session.exited,
            )
        },
        onDismiss = { showSessions = false },
        onSelect = store::switchSession,
        onRestart = store::restartSession,
        onClose = store::closeSession,
        onNew = store::newSession,
    )
}

private val GRID_HORIZONTAL_PADDING = 8.dp

/** 软键盘动画期间网格高度逐帧变化；停稳后再同步尺寸，避免程序被连续的 SIGWINCH 反复重绘。 */
private const val RESIZE_SETTLE_MS = 120L

@Composable
private fun ConsoleGrid(
    state: ConsoleUiState,
    composing: String,
    onGridSize: (cols: Int, rows: Int) -> Unit,
    onReconnect: () -> Unit,
    onOpenEnvironment: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val cellStyle = MiuixTheme.textStyles.footnote1.copy(fontFamily = EtaMonospace)
    val glyphs = remember(textMeasurer, cellStyle) { ConsoleGlyphCache(textMeasurer, cellStyle) }
    val metrics = remember(glyphs) { glyphs.metrics() }
    val colors = MiuixTheme.colorScheme
    val palette = remember(colors.onSurface, colors.surface, colors.primary) {
        ConsolePalette(
            foreground = colors.onSurface,
            background = colors.surface,
            composing = colors.primary,
        )
    }
    val currentOnGridSize by rememberUpdatedState(onGridSize)
    val hasSession by rememberUpdatedState(state.activeSessionId != null)

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val cols = with(density) { ((maxWidth - GRID_HORIZONTAL_PADDING * 2).toPx() / metrics.width).toInt() }
        val rows = with(density) { (maxHeight.toPx() / metrics.height).toInt() }
        LaunchedEffect(cols, rows) {
            if (cols <= 0 || rows <= 0) return@LaunchedEffect
            if (hasSession) delay(RESIZE_SETTLE_MS)
            currentOnGridSize(cols, rows)
        }

        val frame = state.frame
        val listState = rememberLazyListState()
        val atBottom by remember {
            derivedStateOf {
                val layoutInfo = listState.layoutInfo
                val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: Int.MAX_VALUE
                lastVisible >= layoutInfo.totalItemsCount - 1
            }
        }
        LaunchedEffect(frame) {
            if (atBottom && frame.lines.isNotEmpty()) {
                listState.scrollToItem(frame.lines.lastIndex)
            }
        }
        val cursorLineIndex = frame.lines.size - frame.screenRows + frame.cursorRow
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = GRID_HORIZONTAL_PADDING),
        ) {
            itemsIndexed(
                items = frame.lines,
                key = { _, line -> line.id },
                contentType = { _, _ -> "console-line" },
            ) { index, line ->
                val onCursorLine = state.connected && index == cursorLineIndex
                ConsoleGridLine(
                    line = line,
                    cursorCol = if (onCursorLine && frame.cursorVisible) frame.cursorCol else null,
                    composing = if (onCursorLine) composing else "",
                    composingCol = frame.cursorCol,
                    palette = palette,
                    metrics = metrics,
                    glyphs = glyphs,
                )
            }
        }

        if (state.exited || state.failMessage != null) {
            TerminalPlaceholder(
                icon = Icons.Rounded.Terminal,
                message = state.failMessage ?: stringResource(R.string.terminal_session_closed),
                actionText = stringResource(R.string.terminal_reconnect),
                onAction = onReconnect,
                secondaryActionText = state.failMessage?.let { stringResource(R.string.terminal_open_environment) },
                onSecondaryAction = onOpenEnvironment,
                modifier = Modifier.background(colors.surface),
            )
        }
    }
}
