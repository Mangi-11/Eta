package io.github.mangi.eta.ui.screens.terminal

import android.content.ClipData
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.terminal.isLinux
import io.github.mangi.eta.ui.app.TerminalBlockUi
import io.github.mangi.eta.ui.app.UserTerminalStore
import io.github.mangi.eta.ui.components.EtaMonospace
import io.github.mangi.eta.ui.components.ansiPlainText
import io.github.mangi.eta.ui.components.ansiToAnnotatedString
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.squircle.squircleBackground
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowListPopup

/**
 * 用户手动终端：块式输出（命令、输出、退出码），给人用；与 AI 工具调用的任务模型分开。
 * 会话由 [UserTerminalStore] 持有，离开页面后正在运行的命令仍在常驻会话里继续。
 */
@Composable
internal fun UserTerminalScreen(
    store: UserTerminalStore,
    onOpenEnvironment: () -> Unit,
    onOpenConsole: (() -> Unit)? = null,
) {
    val state by store.uiState.collectAsState()
    var input by remember { mutableStateOf("") }
    var showTasks by remember { mutableStateOf(false) }
    var showSessions by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current

    // 从安装页返回后刷新 Linux 就绪态，引导页才会自动让位给终端；守护任务状态一并刷新。
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                store.refreshLinuxReady()
                store.refreshDaemonTasks()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val showLinuxGuide = state.environment.isLinux && !state.linuxReady

    fun submit() {
        val command = input.trim()
        if (command.isEmpty()) return
        // 运行中输入发给前台进程 stdin，否则作为新命令执行。
        if (state.running) {
            store.sendInput(command)
        } else {
            store.send(command)
        }
        input = ""
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
            daemonRunning = state.daemonTasks.any { it.running },
            onOpenSessions = { showSessions = true },
            onOpenTasks = {
                store.refreshDaemonTasks()
                showTasks = true
            },
            modeTarget = if (onOpenConsole != null) TerminalModeTarget.CONSOLE else null,
            onSwitchMode = { onOpenConsole?.invoke() },
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            when {
                showLinuxGuide -> LinuxGuide(onOpenEnvironment = onOpenEnvironment)
                state.blocks.isEmpty() -> EmptyHint(state.failMessage)
                else -> BlockList(
                    blocks = state.blocks,
                    onReinput = { input = it },
                )
            }
        }
        if (!showLinuxGuide) {
            InputPanel(
                input = input,
                cwd = state.cwd,
                running = state.running,
                onInputChange = { input = it },
                onSubmit = ::submit,
                onStop = store::stop,
            )
        }
    }

    if (showTasks) {
        DaemonTasksDialog(
            tasks = state.daemonTasks,
            onDismiss = { showTasks = false },
            onStop = store::stopDaemonTask,
            onLoadLogs = store::daemonLogs,
        )
    }

    if (showSessions) {
        SessionListDialog(
            rows = state.sessions.map { session ->
                SessionDialogRow(
                    id = session.id,
                    environment = session.environment,
                    subtitle = session.cwd,
                    active = session.id == state.activeSessionId,
                    running = session.running,
                    alive = session.alive,
                )
            },
            onDismiss = { showSessions = false },
            onSelect = store::switchSession,
            onRestart = store::restartSession,
            onClose = store::closeSession,
            onNew = store::newSession,
        )
    }
}

@Composable
private fun BlockList(
    blocks: List<TerminalBlockUi>,
    onReinput: (String) -> Unit,
) {
    val listState = rememberLazyListState()
    val atBottom by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: Int.MAX_VALUE
            lastVisible >= layoutInfo.totalItemsCount - 1
        }
    }
    // 输出增长只在用户本来就停留在底部时跟随，向上翻历史不被打断。
    LaunchedEffect(blocks.size, blocks.lastOrNull()?.output?.length) {
        if (atBottom && blocks.isNotEmpty()) {
            listState.scrollToItem(blocks.lastIndex)
        }
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        items(items = blocks, key = { it.id }) { block ->
            if (block.isSystem) {
                SystemBlock(block)
            } else {
                CommandBlock(block = block, onReinput = onReinput)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CommandBlock(
    block: TerminalBlockUi,
    onReinput: (String) -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val colors = MiuixTheme.colorScheme
    val exitCode = block.exitCode
    val failed = exitCode != null && exitCode != 0
    Box {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                    onLongClick = { showMenu = true },
                ),
        ) {
            if (block.cwdAtStart.isNotEmpty()) {
                Text(
                    text = block.cwdAtStart,
                    style = MiuixTheme.textStyles.footnote2.copy(fontFamily = EtaMonospace),
                    color = colors.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = blockHeader(block.command, promptColor = if (failed) colors.error else colors.primary),
                style = MiuixTheme.textStyles.body2.copy(fontFamily = EtaMonospace),
                color = colors.onSurface,
                modifier = Modifier.padding(top = 2.dp),
            )
            if (block.output.isNotEmpty()) {
                // 原始输出含 ANSI 序列；整段重解析保证流式截断的序列在下一次到达后恢复。
                val parsedOutput = remember(block.output) { ansiToAnnotatedString(block.output) }
                Text(
                    text = parsedOutput,
                    style = MiuixTheme.textStyles.footnote1.copy(fontFamily = EtaMonospace),
                    color = colors.onSurface.copy(alpha = 0.86f),
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            BlockFooter(block = block, failed = failed)
        }
        BlockMenu(
            show = showMenu,
            hasOutput = block.output.isNotEmpty(),
            onDismiss = { showMenu = false },
            onCopyCommand = { scope.launch { clipboard.setPlainText(block.command) } },
            onCopyOutput = { scope.launch { clipboard.setPlainText(ansiPlainText(block.output)) } },
            onReinput = { onReinput(block.command) },
        )
    }
}

@Composable
private fun BlockFooter(block: TerminalBlockUi, failed: Boolean) {
    val colors = MiuixTheme.colorScheme
    val labels = buildList {
        if (failed) add(stringResource(R.string.terminal_exit_code, block.exitCode ?: 0) to colors.error)
        if (block.truncated) add(stringResource(R.string.terminal_output_truncated) to colors.onSurfaceVariantSummary)
    }
    if (!block.running && labels.isEmpty()) return
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.padding(top = 6.dp),
    ) {
        if (block.running) {
            InfiniteProgressIndicator(size = 12.dp)
            Text(
                text = stringResource(R.string.terminal_running),
                style = MiuixTheme.textStyles.footnote2,
                color = colors.onSurfaceVariantSummary,
            )
        }
        labels.forEach { (text, color) ->
            Text(text = text, style = MiuixTheme.textStyles.footnote2, color = color)
        }
    }
}

@Composable
private fun blockHeader(command: String, promptColor: Color): AnnotatedString =
    remember(command, promptColor) {
        buildAnnotatedString {
            withStyle(SpanStyle(color = promptColor, fontWeight = FontWeight.Bold)) { append("❯ ") }
            append(command)
        }
    }

private suspend fun Clipboard.setPlainText(text: String) {
    setClipEntry(ClipEntry(ClipData.newPlainText(null, text)))
}

@Composable
private fun BlockMenu(
    show: Boolean,
    hasOutput: Boolean,
    onDismiss: () -> Unit,
    onCopyCommand: () -> Unit,
    onCopyOutput: () -> Unit,
    onReinput: () -> Unit,
) {
    WindowListPopup(
        show = show,
        alignment = PopupPositionProvider.Align.Start,
        onDismissRequest = onDismiss,
    ) {
        ListPopupColumn {
            DropdownImpl(
                text = stringResource(R.string.terminal_copy_command),
                optionSize = 3,
                isSelected = false,
                index = 0,
                onSelectedIndexChange = {
                    onDismiss()
                    onCopyCommand()
                },
            )
            DropdownImpl(
                text = stringResource(R.string.terminal_copy_output),
                optionSize = 3,
                isSelected = false,
                index = 1,
                enabled = hasOutput,
                onSelectedIndexChange = {
                    onDismiss()
                    onCopyOutput()
                },
            )
            DropdownImpl(
                text = stringResource(R.string.terminal_reinput),
                optionSize = 3,
                isSelected = false,
                index = 2,
                onSelectedIndexChange = {
                    onDismiss()
                    onReinput()
                },
            )
        }
    }
}

@Composable
private fun SystemBlock(block: TerminalBlockUi) {
    Text(
        text = block.output,
        style = MiuixTheme.textStyles.footnote2,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * 底部输入区：当前目录在上、命令输入在下。回车直接执行；
 * 运行中输入框为空时尾部按钮变为停止，有内容时发送到前台进程的 stdin。
 */
@Composable
private fun InputPanel(
    input: String,
    cwd: String,
    running: Boolean,
    onInputChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onStop: () -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    val canSend = input.isNotBlank()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 10.dp),
    ) {
        if (cwd.isNotEmpty()) {
            Text(
                text = cwd,
                style = MiuixTheme.textStyles.footnote2.copy(fontFamily = EtaMonospace),
                color = colors.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.StartEllipsis,
                modifier = Modifier.padding(start = 6.dp, end = 6.dp, bottom = 6.dp),
            )
        }
        TextField(
            value = input,
            onValueChange = onInputChange,
            modifier = Modifier.fillMaxWidth(),
            label = stringResource(
                if (running) R.string.terminal_input_running_hint else R.string.terminal_input_hint,
            ),
            useLabelAsPlaceholder = true,
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                imeAction = ImeAction.Send,
                autoCorrectEnabled = false,
            ),
            keyboardActions = KeyboardActions(onSend = { onSubmit() }),
            textStyle = MiuixTheme.textStyles.body2.copy(fontFamily = EtaMonospace),
            trailingIcon = {
                val stopMode = running && !canSend
                IconButton(
                    onClick = if (stopMode) onStop else onSubmit,
                    enabled = stopMode || canSend,
                    minWidth = 36.dp,
                    minHeight = 36.dp,
                    backgroundColor = when {
                        stopMode -> colors.error.copy(alpha = 0.12f)
                        canSend -> colors.primary
                        else -> Color.Transparent
                    },
                    cornerRadius = 18.dp,
                    modifier = Modifier.padding(end = 8.dp),
                ) {
                    Icon(
                        imageVector = if (stopMode) Icons.Rounded.Stop else Icons.Rounded.ArrowUpward,
                        contentDescription = stringResource(
                            if (stopMode) R.string.terminal_stop else R.string.terminal_send,
                        ),
                        modifier = Modifier.size(18.dp),
                        tint = when {
                            stopMode -> colors.error
                            canSend -> colors.onPrimary
                            else -> colors.onSurfaceVariantSummary.copy(alpha = 0.5f)
                        },
                    )
                }
            },
        )
    }
}

@Composable
private fun LinuxGuide(onOpenEnvironment: () -> Unit) {
    TerminalPlaceholder(
        icon = Icons.Rounded.Terminal,
        message = stringResource(R.string.terminal_linux_not_ready),
        actionText = stringResource(R.string.terminal_open_environment),
        onAction = onOpenEnvironment,
    )
}

@Composable
private fun EmptyHint(message: String? = null) {
    TerminalPlaceholder(
        icon = Icons.Rounded.Terminal,
        message = message ?: stringResource(R.string.terminal_empty_hint),
    )
}

/** 终端区域的居中占位：空态、环境未就绪与会话断开共用同一种层级与留白。 */
@Composable
internal fun TerminalPlaceholder(
    icon: ImageVector,
    message: String,
    modifier: Modifier = Modifier,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
    secondaryActionText: String? = null,
    onSecondaryAction: (() -> Unit)? = null,
) {
    val colors = MiuixTheme.colorScheme
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .squircleBackground(colors.surfaceContainer, 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(26.dp),
                tint = colors.onSurfaceVariantSummary,
            )
        }
        Text(
            text = message,
            style = MiuixTheme.textStyles.footnote1,
            color = colors.onSurfaceVariantSummary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 14.dp),
        )
        if (actionText != null && onAction != null) {
            TextButton(
                text = actionText,
                onClick = onAction,
                colors = ButtonDefaults.textButtonColorsPrimary(),
                modifier = Modifier.padding(top = 18.dp),
            )
        }
        if (secondaryActionText != null && onSecondaryAction != null) {
            TextButton(
                text = secondaryActionText,
                onClick = onSecondaryAction,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}
