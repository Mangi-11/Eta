package fuck.andes.ui.screens.browser

import android.content.Intent
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import com.composables.icons.lucide.R as LucideR
import fuck.andes.agent.browser.AgentBrowserSession
import fuck.andes.agent.browser.BrowserSessionSnapshot
import fuck.andes.ui.components.StatusError
import fuck.andes.ui.components.StatusWarning
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * Agent 与用户共享的浏览器会话。
 *
 * 浏览器通常在后台由模型驱动；进入本页后挂载的是同一个 WebView，用户可以直接接管，
 * 不会新建一份与 Agent 状态脱节的预览。
 */
@Composable
internal fun AgentBrowserScreen(
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val snapshot by AgentBrowserSession.snapshots.collectAsState()
    var address by remember { mutableStateOf("") }
    var addressFocused by remember { mutableStateOf(false) }
    var actionPending by remember { mutableStateOf(false) }
    var showResetDialog by remember { mutableStateOf(false) }

    LaunchedEffect(context.applicationContext) {
        AgentBrowserSession.initialize(context.applicationContext)
    }
    LaunchedEffect(snapshot.displayUrl, addressFocused) {
        if (!addressFocused) {
            address = snapshot.displayUrl
        }
    }

    fun launchBrowserAction(action: () -> Unit) {
        if (actionPending) return
        actionPending = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) { action() }
            } finally {
                actionPending = false
            }
        }
    }

    fun navigate() {
        if (actionPending) return
        val target = if (address == snapshot.displayUrl) {
            snapshot.url
        } else {
            address.trim()
        }
        if (target.isBlank()) return
        focusManager.clearFocus()
        keyboard?.hide()
        launchBrowserAction {
            AgentBrowserSession.navigateFromUser(context.applicationContext, target)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MiuixTheme.colorScheme.surface)
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp)
            .imePadding()
            .navigationBarsPadding(),
    ) {
        TextField(
            value = address,
            onValueChange = {
                address = it
            },
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { state -> addressFocused = state.isFocused },
            label = "网址或域名",
            useLabelAsPlaceholder = true,
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Go,
            ),
            keyboardActions = KeyboardActions(onGo = { navigate() }),
            leadingIcon = {
                Icon(
                    painter = painterResource(
                        if (snapshot.url.startsWith("https://")) {
                            LucideR.drawable.lucide_ic_lock
                        } else {
                            LucideR.drawable.lucide_ic_globe
                        }
                    ),
                    contentDescription = null,
                    modifier = Modifier.padding(start = 12.dp).size(18.dp),
                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            },
            trailingIcon = {
                IconButton(
                    onClick = ::navigate,
                    enabled = address.isNotBlank() && !actionPending,
                    modifier = Modifier
                        .padding(end = 6.dp)
                        .alpha(if (address.isNotBlank() && !actionPending) 1f else 0.34f),
                ) {
                    Icon(
                        painter = painterResource(LucideR.drawable.lucide_ic_arrow_right),
                        contentDescription = "访问",
                        modifier = Modifier.size(19.dp),
                        tint = MiuixTheme.colorScheme.onSurface,
                    )
                }
            },
        )

        Spacer(modifier = Modifier.height(10.dp))
        BrowserControlBar(
            snapshot = snapshot,
            actionPending = actionPending,
            onBack = { launchBrowserAction { AgentBrowserSession.goBackFromUser() } },
            onForward = { launchBrowserAction { AgentBrowserSession.goForwardFromUser() } },
            onRefresh = {
                if (snapshot.isLoading) {
                    scope.launch(Dispatchers.IO) {
                        AgentBrowserSession.stopFromUser()
                    }
                } else {
                    launchBrowserAction {
                        AgentBrowserSession.reloadFromUser()
                    }
                }
            },
            onOpenExternal = {
                val currentUrl = snapshot.url.takeIf { it.startsWith("http://") || it.startsWith("https://") }
                if (currentUrl != null) {
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, currentUrl.toUri()))
                    }.onFailure {
                        Toast.makeText(context, "没有应用可以打开当前网页", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onReset = { showResetDialog = true },
        )

        if (snapshot.isLoading) {
            LinearProgressIndicator(
                progress = snapshot.progress.coerceIn(0, 100) / 100f,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                height = 3.dp,
            )
        } else {
            Spacer(modifier = Modifier.height(8.dp))
        }

        BrowserStatusBanner(snapshot)

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            insideMargin = PaddingValues(0.dp),
            colors = CardDefaults.defaultColors(
                color = MiuixTheme.colorScheme.surfaceContainer,
                contentColor = MiuixTheme.colorScheme.onSurface,
            ),
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                BrowserWebViewHost(modifier = Modifier.fillMaxSize())
                if (!snapshot.available) {
                    BrowserEmptyState(modifier = Modifier.fillMaxSize())
                } else if (snapshot.isLoading || (!snapshot.isPageVisible && snapshot.error == null)) {
                    BrowserLoadingState(
                        host = snapshot.host,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else if (!snapshot.isPageVisible) {
                    BrowserFailedState(modifier = Modifier.fillMaxSize())
                }
            }
        }
    }

    if (showResetDialog) {
        WindowDialog(
            show = true,
            title = "重置浏览器会话",
            onDismissRequest = { showResetDialog = false },
        ) {
            Text(
                text = "将关闭当前页面并清除 Eta 浏览器的 Cookie 与站点数据。外部浏览器不会受到影响。",
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 18.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(
                    text = "取消",
                    onClick = { showResetDialog = false },
                )
                Spacer(modifier = Modifier.width(8.dp))
                TextButton(
                    text = "重置",
                    onClick = {
                        showResetDialog = false
                        address = ""
                        launchBrowserAction { AgentBrowserSession.resetFromUser() }
                    },
                    enabled = !actionPending,
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }
    }
}

@Composable
private fun BrowserControlBar(
    snapshot: BrowserSessionSnapshot,
    actionPending: Boolean,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onRefresh: () -> Unit,
    onOpenExternal: () -> Unit,
    onReset: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        insideMargin = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
        colors = CardDefaults.defaultColors(
            color = MiuixTheme.colorScheme.surfaceContainer,
            contentColor = MiuixTheme.colorScheme.onSurface,
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BrowserControlButton(
                icon = LucideR.drawable.lucide_ic_arrow_left,
                description = "后退",
                enabled = snapshot.canGoBack && !actionPending,
                onClick = onBack,
            )
            BrowserControlButton(
                icon = LucideR.drawable.lucide_ic_arrow_right,
                description = "前进",
                enabled = snapshot.canGoForward && !actionPending,
                onClick = onForward,
            )
            BrowserControlButton(
                icon = if (snapshot.isLoading) {
                    LucideR.drawable.lucide_ic_x
                } else {
                    LucideR.drawable.lucide_ic_refresh_cw
                },
                description = if (snapshot.isLoading) "停止加载" else "刷新",
                enabled = snapshot.available && (snapshot.isLoading || !actionPending),
                onClick = onRefresh,
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp),
            ) {
                Text(
                    text = snapshot.title.ifBlank { "Agent 浏览器" },
                    style = MiuixTheme.textStyles.body2,
                    fontWeight = FontWeight.Medium,
                    color = MiuixTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
                if (snapshot.host.isNotBlank()) {
                    Text(
                        text = snapshot.host,
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 1,
                    )
                }
            }

            BrowserControlButton(
                icon = LucideR.drawable.lucide_ic_external_link,
                description = "用外部应用打开",
                enabled = snapshot.available,
                onClick = onOpenExternal,
            )
            BrowserControlButton(
                icon = LucideR.drawable.lucide_ic_trash_2,
                description = "重置会话",
                enabled = snapshot.available && !actionPending,
                onClick = onReset,
            )
        }
    }
}

@Composable
private fun BrowserControlButton(
    icon: Int,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .alpha(if (enabled) 1f else 0.34f)
            .semantics(mergeDescendants = true) {
                contentDescription = description
                if (!enabled) disabled()
            },
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MiuixTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun BrowserStatusBanner(snapshot: BrowserSessionSnapshot) {
    val risk = snapshot.riskChallengeKind
    val message = when {
        risk != null -> "页面需要人工验证，Agent 已停止自动点击和输入。你可以在下方手动接管。"
        snapshot.error != null -> snapshot.error
        snapshot.isUserControlling && snapshot.available ->
            "你正在接管当前会话，Agent 的网页操作已停止；返回后可让 Agent 继续。"
        else -> null
    } ?: return
    val color = when {
        risk != null -> StatusWarning
        snapshot.error != null -> StatusError
        else -> MiuixTheme.colorScheme.primary
    }
    val icon = if (risk != null || snapshot.error != null) {
        LucideR.drawable.lucide_ic_shield_alert
    } else {
        LucideR.drawable.lucide_ic_mouse_pointer_click
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
        insideMargin = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
        colors = CardDefaults.defaultColors(
            color = color.copy(alpha = 0.10f),
            contentColor = MiuixTheme.colorScheme.onSurface,
        ),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                modifier = Modifier.size(17.dp),
                tint = color,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = message,
                modifier = Modifier.weight(1f),
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun BrowserEmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Initial).changes.forEach { change ->
                            change.consume()
                        }
                    }
                }
            }
            .background(MiuixTheme.colorScheme.surfaceContainer)
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            painter = painterResource(LucideR.drawable.lucide_ic_globe),
            contentDescription = null,
            modifier = Modifier.size(38.dp),
            tint = MiuixTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.height(14.dp))
        Text(
            text = "浏览器尚未打开网页",
            style = MiuixTheme.textStyles.body1,
            fontWeight = FontWeight.Medium,
            color = MiuixTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "在地址栏输入网址，或让 Agent 帮你查阅网页。会话只在 Eta 内使用。",
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

@Composable
private fun BrowserLoadingState(
    host: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Initial).changes.forEach { change ->
                            change.consume()
                        }
                    }
                }
            }
            .background(MiuixTheme.colorScheme.surfaceContainer)
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            painter = painterResource(LucideR.drawable.lucide_ic_globe),
            contentDescription = null,
            modifier = Modifier.size(30.dp),
            tint = MiuixTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = if (host.isBlank()) "正在打开网页" else "正在打开 $host",
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            maxLines = 1,
        )
    }
}

@Composable
private fun BrowserFailedState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Initial).changes.forEach { change ->
                            change.consume()
                        }
                    }
                }
            }
            .background(MiuixTheme.colorScheme.surfaceContainer)
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            painter = painterResource(LucideR.drawable.lucide_ic_shield_alert),
            contentDescription = null,
            modifier = Modifier.size(30.dp),
            tint = StatusError,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "网页未能打开",
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

@Composable
private fun BrowserWebViewHost(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val backgroundColor = MiuixTheme.colorScheme.surfaceContainer.toArgb()
    val container = remember(context) {
        FrameLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            setBackgroundColor(backgroundColor)
        }
    }
    DisposableEffect(container, context) {
        AgentBrowserSession.attachTo(container, context)
        onDispose { AgentBrowserSession.detachFrom(container) }
    }
    AndroidView(
        factory = { container },
        update = { view -> view.setBackgroundColor(backgroundColor) },
        modifier = modifier,
    )
}
