package io.github.mangi.eta.ui.app

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ShortText
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.itos.coui.icons.CouiIcons
import com.itos.coui.layout.CouiPageDefaults
import com.itos.coui.layout.Scaffold as CouiLayoutScaffold
import com.itos.coui.menu.CouiMenuItem
import com.itos.coui.menu.Menu
import com.itos.coui.menu.couiMenuAnchor
import com.itos.coui.menu.rememberCouiMenuAnchor
import com.itos.coui.theme.CouiTheme
import com.itos.coui.theme.LocalCouiTextStyle
import com.itos.coui.topappbar.CouiTopAppBarDefaults
import com.itos.coui.topappbar.TopAppBar as CouiTopAppBar
import com.itos.coui.topappbar.TopAppBarButtonGroup
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.data.model.TtsProvider
import io.github.mangi.eta.data.repository.SpeechSettingsRepository
import io.github.mangi.eta.ui.components.AdaptiveTopAppBar
import io.github.mangi.eta.ui.components.AgentTopBarChatHeader
import io.github.mangi.eta.ui.components.ChatActionIcons
import io.github.mangi.eta.ui.components.ChatImagePreviewHost
import io.github.mangi.eta.ui.components.ConversationSidePaneScaffold
import io.github.mangi.eta.ui.components.MiuixBackButton
import io.github.mangi.eta.ui.components.RoleplayChatUi
import io.github.mangi.eta.ui.components.TopBarBackdrop
import io.github.mangi.eta.ui.components.captureForTopBar
import io.github.mangi.eta.ui.components.coui.CouiBackButton
import io.github.mangi.eta.ui.components.coui.CouiTopAppBarIconButton
import io.github.mangi.eta.ui.components.rememberTopBarBackdrop
import io.github.mangi.eta.ui.components.topBarContainerColor
import io.github.mangi.eta.ui.model.AgentModelPickerUiState
import io.github.mangi.eta.ui.model.ConversationPaneUiState
import io.github.mangi.eta.ui.model.ConversationSummaryUi
import io.github.mangi.eta.ui.navigation.AppRoute
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBarDefaults
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowListPopup

/**
 * Agent App 统一壳层。
 *
 * - 负责全局 Scaffold、状态栏/横向安全边距、顶层工具栏。
 * - 首页工具栏只保留历史入口与溢出菜单，保持聊天舞台干净。
 * - 非首页子路由统一提供返回按钮与标题，避免每个页面各自像独立设置页。
 * - Settings 由标准二级页骨架自己提供 TopAppBar，壳层在此路由不重复绘制。
 */
@Composable
internal fun AgentAppShell(
    currentRoute: AppRoute?,
    isCurrentRoute: Boolean,
    conversationPaneState: ConversationPaneUiState?,
    isConversationPaneOpen: Boolean,
    onBack: () -> Unit,
    onOpenConversationPane: () -> Unit,
    onDismissConversationPane: () -> Unit,
    onSearchConversations: (String) -> Unit,
    onNewConversation: () -> Unit,
    onOpenTerminal: () -> Unit,
    onLaunchKimiWeb: () -> Unit,
    kimiWebLabel: String,
    canStopKimiWeb: Boolean,
    onStopKimiWeb: () -> Unit,
    onRefreshKimiWeb: () -> Unit,
    onOpenBrowser: () -> Unit,
    onSelectConversation: (String) -> Unit,
    onConversationTogglePin: (ConversationSummaryUi) -> Unit,
    onConversationRename: (ConversationSummaryUi) -> Unit,
    onConversationExport: (ConversationSummaryUi) -> Unit,
    onConversationDelete: (ConversationSummaryUi) -> Unit,
    onOpenTools: () -> Unit,
    onOpenSkills: () -> Unit,
    onOpenCharacters: () -> Unit,
    onOpenPermissions: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenModelProviders: () -> Unit,
    modifier: Modifier = Modifier,
    homeTitle: String? = null,
    homeCharacter: RoleplayChatUi? = null,
    homeModelPickerState: AgentModelPickerUiState? = null,
    isHomeStreaming: Boolean = false,
    onHomeModelSelected: (String) -> Unit = {},
    onOpenCharacter: () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    val uiStyle = LocalUiStyle.current
    val pageContent: @Composable () -> Unit = {
        when (uiStyle) {
            AppearanceUiStyle.COUI -> {
                val dark = CouiTheme.isDark
                val colors = CouiPageDefaults.themeColors(dark)
                val isHome = currentRoute is AppRoute.Home
                CouiLayoutScaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = MiuixTheme.colorScheme.surface,
                    windowInsets = WindowInsets.safeDrawing.only(
                        WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
                    ),
                    topBar = {
                        if (currentRoute !is AppRoute.Settings) {
                            CouiTopAppBar(
                                title = {
                                    if (isHome && homeModelPickerState != null) {
                                        Box(
                                            modifier = Modifier.fillMaxWidth(),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            AgentTopBarChatHeader(
                                                conversationTitle = homeTitle,
                                                character = homeCharacter,
                                                state = homeModelPickerState,
                                                isStreaming = isHomeStreaming,
                                                onModelSelected = onHomeModelSelected,
                                                onOpenCharacter = onOpenCharacter,
                                            )
                                        }
                                    } else {
                                        BasicText(
                                            text = titleForRoute(currentRoute),
                                            style = LocalCouiTextStyle.current,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                },
                                navigationIcon = {
                                    if (isHome) {
                                        CouiTopAppBarIconButton(
                                            icon = Icons.AutoMirrored.Outlined.ShortText,
                                            contentDescription = stringResource(R.string.action_conversation_history),
                                            onClick = onOpenConversationPane,
                                            dark = dark,
                                            colors = colors,
                                        )
                                    } else {
                                        CouiBackButton(onClick = onBack, dark = dark, colors = colors)
                                    }
                                },
                                actions = {
                                    if (isHome) {
                                        TopAppBarButtonGroup {
                                            CouiTopBarOverflowMenu(
                                                dark = dark,
                                                colors = colors,
                                                onNewConversation = onNewConversation,
                                                onOpenTerminal = onOpenTerminal,
                                                onLaunchKimiWeb = onLaunchKimiWeb,
                                                kimiWebLabel = kimiWebLabel,
                                                canStopKimiWeb = canStopKimiWeb,
                                                onStopKimiWeb = onStopKimiWeb,
                                                onRefreshKimiWeb = onRefreshKimiWeb,
                                                onOpenBrowser = onOpenBrowser,
                                            )
                                        }
                                    }
                                },
                                dark = dark,
                                colors = CouiTopAppBarDefaults.colors(
                                    backgroundColor = MiuixTheme.colorScheme.surface,
                                    titleColor = colors.title,
                                    dark = dark,
                                ),
                            )
                        }
                    },
                ) { padding ->
                    Box(modifier = Modifier.fillMaxSize()) {
                        content(padding)
                    }
                }
            }
            AppearanceUiStyle.MIUIX -> {
                // 首页顶栏高度固定，不参与折叠；未绑定顶栏的滚动行为会吞掉聊天列表的单向滑动。
                val scrollBehavior = if (currentRoute is AppRoute.Home) null else MiuixScrollBehavior()
                val backdrop = rememberTopBarBackdrop()
                val topBarColor = topBarContainerColor(backdrop)
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    contentWindowInsets = WindowInsets.safeDrawing.only(
                        WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
                    ),
                    topBar = {
                        if (currentRoute !is AppRoute.Settings) {
                            TopBarBackdrop(backdrop) {
                                AgentTopBar(
                                    route = currentRoute,
                                    scrollBehavior = scrollBehavior,
                                    color = topBarColor,
                                    onBack = onBack,
                                    onOpenConversationPane = onOpenConversationPane,
                                    onNewConversation = onNewConversation,
                                    onOpenTerminal = onOpenTerminal,
                                    onLaunchKimiWeb = onLaunchKimiWeb,
                                    kimiWebLabel = kimiWebLabel,
                                    canStopKimiWeb = canStopKimiWeb,
                                    onStopKimiWeb = onStopKimiWeb,
                                    onRefreshKimiWeb = onRefreshKimiWeb,
                                    onOpenBrowser = onOpenBrowser,
                                    homeTitle = homeTitle,
                                    homeCharacter = homeCharacter,
                                    homeModelPickerState = homeModelPickerState,
                                    isHomeStreaming = isHomeStreaming,
                                    onHomeModelSelected = onHomeModelSelected,
                                    onOpenCharacter = onOpenCharacter,
                                )
                            }
                        }
                    },
                ) { padding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .captureForTopBar(backdrop)
                            .then(
                                if (scrollBehavior != null) Modifier.nestedScroll(scrollBehavior.nestedScrollConnection)
                                else Modifier,
                            ),
                    ) {
                        content(padding)
                    }
                }
            }
        }
    }

    ChatImagePreviewHost(modifier = modifier) {
        if (conversationPaneState != null && currentRoute is AppRoute.Home) {
            ConversationSidePaneScaffold(
                state = conversationPaneState,
                visible = isConversationPaneOpen,
                backHandlerEnabled = isCurrentRoute,
                onOpen = onOpenConversationPane,
                onDismiss = onDismissConversationPane,
                onSearchChange = onSearchConversations,
                onConversationSelected = onSelectConversation,
                onConversationTogglePin = onConversationTogglePin,
                onConversationRename = onConversationRename,
                onConversationExport = onConversationExport,
                onConversationDelete = onConversationDelete,
                onOpenSettings = onOpenSettings,
                onOpenModelProviders = onOpenModelProviders,
                onOpenTools = onOpenTools,
                onOpenSkills = onOpenSkills,
                onOpenCharacters = onOpenCharacters,
                onOpenPermissions = onOpenPermissions,
            ) {
                pageContent()
            }
        } else {
            pageContent()
        }
    }
}

@Composable
private fun CouiTopBarOverflowMenu(
    dark: Boolean,
    colors: com.itos.coui.layout.CouiPageColors,
    onNewConversation: () -> Unit,
    onOpenTerminal: () -> Unit,
    onLaunchKimiWeb: () -> Unit,
    kimiWebLabel: String,
    canStopKimiWeb: Boolean,
    onStopKimiWeb: () -> Unit,
    onRefreshKimiWeb: () -> Unit,
    onOpenBrowser: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val speechSettings by remember { SpeechSettingsRepository.settingsFlow() }.collectAsState(initial = null)
    val hasSpeechPlayback = speechSettings?.let { it.tts != TtsProvider.NONE } == true
    val autoReadEnabled = speechSettings?.chatAutoSpeak == true
    val anchor = rememberCouiMenuAnchor()

    val newConversationText = stringResource(R.string.action_new_conversation)
    val openTerminalText = stringResource(R.string.action_open_terminal)
    val stopKimiWebText = stringResource(R.string.capability_kimi_stop)
    val openBrowserText = stringResource(R.string.action_open_browser)
    val autoReadText = stringResource(
        if (autoReadEnabled) R.string.speech_disable_auto_read else R.string.speech_enable_auto_read,
    )
    val menuItems = remember(
        newConversationText,
        openTerminalText,
        kimiWebLabel,
        openBrowserText,
        stopKimiWebText,
        canStopKimiWeb,
        autoReadText,
        hasSpeechPlayback,
        autoReadEnabled,
    ) {
        buildList {
            add(CouiMenuItem(value = 0, text = newConversationText, icon = ChatActionIcons.NewChat))
            add(CouiMenuItem(value = 1, text = openTerminalText, icon = Icons.Rounded.Terminal))
            add(CouiMenuItem(value = 2, text = kimiWebLabel, icon = Icons.Rounded.Code))
            add(CouiMenuItem(value = 3, text = openBrowserText, icon = Icons.Rounded.Language))
            if (canStopKimiWeb) {
                add(CouiMenuItem(value = 4, text = stopKimiWebText, icon = Icons.Rounded.Stop))
            }
            if (hasSpeechPlayback) {
                add(
                    CouiMenuItem(
                        value = 5,
                        text = autoReadText,
                        icon = if (autoReadEnabled) Icons.AutoMirrored.Rounded.VolumeUp else Icons.AutoMirrored.Rounded.VolumeOff,
                    ),
                )
            }
        }
    }

    Box {
        CouiTopAppBarIconButton(
            icon = CouiIcons.More,
            contentDescription = stringResource(R.string.action_more),
            modifier = Modifier.couiMenuAnchor(anchor),
            dark = dark,
            colors = colors,
            onClick = {
                onRefreshKimiWeb()
                anchor.open { showMenu = true }
            },
        )
        Menu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false },
            items = menuItems,
            onItemClick = { item ->
                showMenu = false
                when (item.value) {
                    0 -> onNewConversation()
                    1 -> onOpenTerminal()
                    2 -> onLaunchKimiWeb()
                    3 -> onOpenBrowser()
                    4 -> onStopKimiWeb()
                    5 -> speechSettings?.let { current ->
                        scope.launch {
                            SpeechSettingsRepository.save(current.copy(chatAutoSpeak = !current.chatAutoSpeak))
                        }
                    }
                }
            },
            anchor = anchor,
        )
    }
}

@Composable
private fun AgentTopBar(
    route: AppRoute?,
    scrollBehavior: ScrollBehavior?,
    color: Color,
    onBack: () -> Unit,
    onOpenConversationPane: () -> Unit,
    onNewConversation: () -> Unit,
    onOpenTerminal: () -> Unit,
    onLaunchKimiWeb: () -> Unit,
    kimiWebLabel: String,
    canStopKimiWeb: Boolean,
    onStopKimiWeb: () -> Unit,
    onRefreshKimiWeb: () -> Unit,
    onOpenBrowser: () -> Unit,
    homeTitle: String?,
    homeCharacter: RoleplayChatUi?,
    homeModelPickerState: AgentModelPickerUiState?,
    isHomeStreaming: Boolean,
    onHomeModelSelected: (String) -> Unit,
    onOpenCharacter: () -> Unit,
) {
    val isHome = route is AppRoute.Home
    if (isHome && homeModelPickerState != null) {
        HomeMiuixTopBar(
            color = color,
            onOpenConversationPane = onOpenConversationPane,
            onNewConversation = onNewConversation,
            onOpenTerminal = onOpenTerminal,
            onLaunchKimiWeb = onLaunchKimiWeb,
            kimiWebLabel = kimiWebLabel,
            canStopKimiWeb = canStopKimiWeb,
            onStopKimiWeb = onStopKimiWeb,
            onRefreshKimiWeb = onRefreshKimiWeb,
            onOpenBrowser = onOpenBrowser,
        ) {
            AgentTopBarChatHeader(
                conversationTitle = homeTitle,
                character = homeCharacter,
                state = homeModelPickerState,
                isStreaming = isHomeStreaming,
                onModelSelected = onHomeModelSelected,
                onOpenCharacter = onOpenCharacter,
            )
        }
        return
    }

    val navigationIcon: @Composable () -> Unit = {
        MiuixBackButton(onClick = onBack)
    }
    val actions: @Composable RowScope.() -> Unit = {}

    if (route is AppRoute.Terminal) {
        SmallTopAppBar(
            title = titleForRoute(route),
            color = color,
            scrollBehavior = scrollBehavior,
            navigationIcon = navigationIcon,
            actions = actions,
        )
    } else {
        AdaptiveTopAppBar(
            title = titleForRoute(route),
            color = color,
            scrollBehavior = scrollBehavior,
            navigationIcon = navigationIcon,
            actions = actions,
        )
    }
}

@Composable
private fun HomeMiuixTopBar(
    color: Color,
    onOpenConversationPane: () -> Unit,
    onNewConversation: () -> Unit,
    onOpenTerminal: () -> Unit,
    onLaunchKimiWeb: () -> Unit,
    kimiWebLabel: String,
    canStopKimiWeb: Boolean,
    onStopKimiWeb: () -> Unit,
    onRefreshKimiWeb: () -> Unit,
    onOpenBrowser: () -> Unit,
    centerContent: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(color)
            .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))
            .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Horizontal))
            .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Top))
            .height(56.dp)
            .clipToBounds()
            .pointerInput(Unit) {
                detectTapGestures { }
            }
            .padding(
                start = TopAppBarDefaults.NavigationIconPadding,
                end = TopAppBarDefaults.ActionIconPadding,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onOpenConversationPane) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.ShortText,
                modifier = Modifier.size(24.dp),
                contentDescription = stringResource(R.string.action_conversation_history),
            )
        }
        Box(
            modifier = Modifier.weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            centerContent()
        }
        TopBarOverflowMenu(
            onNewConversation = onNewConversation,
            onOpenTerminal = onOpenTerminal,
            onLaunchKimiWeb = onLaunchKimiWeb,
            kimiWebLabel = kimiWebLabel,
            canStopKimiWeb = canStopKimiWeb,
            onStopKimiWeb = onStopKimiWeb,
            onRefreshKimiWeb = onRefreshKimiWeb,
            onOpenBrowser = onOpenBrowser,
        )
    }
}

private val TopBarMenuIconSize = 20.dp

@Composable
private fun TopBarOverflowMenu(
    onNewConversation: () -> Unit,
    onOpenTerminal: () -> Unit,
    onLaunchKimiWeb: () -> Unit,
    kimiWebLabel: String,
    canStopKimiWeb: Boolean,
    onStopKimiWeb: () -> Unit,
    onRefreshKimiWeb: () -> Unit,
    onOpenBrowser: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val speechSettings by remember { SpeechSettingsRepository.settingsFlow() }.collectAsState(initial = null)
    val hasSpeechPlayback = speechSettings?.let { it.tts != TtsProvider.NONE } == true
    val autoReadEnabled = speechSettings?.chatAutoSpeak == true
    Box {
        IconButton(onClick = { onRefreshKimiWeb(); showMenu = true }) {
            Icon(
                imageVector = Icons.Outlined.MoreHoriz,
                modifier = Modifier.size(24.dp),
                contentDescription = stringResource(R.string.action_more),
            )
        }
        WindowListPopup(
            show = showMenu,
            alignment = PopupPositionProvider.Align.End,
            onDismissRequest = { showMenu = false },
        ) {
            val newConversationText = stringResource(R.string.action_new_conversation)
            val openTerminalText = stringResource(R.string.action_open_terminal)
            val launchKimiWebText = kimiWebLabel
            val stopKimiWebText = stringResource(R.string.capability_kimi_stop)
            val openBrowserText = stringResource(R.string.action_open_browser)
            val autoReadText = stringResource(
                if (autoReadEnabled) R.string.speech_disable_auto_read else R.string.speech_enable_auto_read,
            )
            val menuItems = remember(
                newConversationText,
                openTerminalText,
                launchKimiWebText,
                openBrowserText,
                stopKimiWebText,
                canStopKimiWeb,
                autoReadText,
                hasSpeechPlayback,
                autoReadEnabled,
            ) {
                listOf(
                    DropdownItem(
                        text = newConversationText,
                        icon = { modifier ->
                            Icon(
                                imageVector = ChatActionIcons.NewChat,
                                contentDescription = null,
                                modifier = modifier.size(TopBarMenuIconSize),
                            )
                        },
                    ),
                    DropdownItem(
                        text = openTerminalText,
                        icon = { modifier ->
                            Icon(
                                imageVector = Icons.Rounded.Terminal,
                                contentDescription = null,
                                modifier = modifier.size(TopBarMenuIconSize),
                            )
                        },
                    ),
                    DropdownItem(
                        text = launchKimiWebText,
                        icon = { modifier ->
                            Icon(
                                painter = painterResource(R.drawable.ic_kimi_code),
                                contentDescription = null,
                                modifier = modifier.size(TopBarMenuIconSize),
                            )
                        },
                    ),
                    DropdownItem(
                        text = openBrowserText,
                        icon = { modifier ->
                            Icon(
                                imageVector = Icons.Rounded.Language,
                                contentDescription = null,
                                modifier = modifier.size(TopBarMenuIconSize),
                            )
                        },
                    ),
                ) + (if (canStopKimiWeb) {
                    listOf(
                        DropdownItem(
                            text = stopKimiWebText,
                            icon = { modifier ->
                                Icon(
                                    imageVector = Icons.Rounded.Stop,
                                    contentDescription = null,
                                    modifier = modifier.size(TopBarMenuIconSize),
                                )
                            },
                        ),
                    )
                } else {
                    emptyList()
                }) + if (hasSpeechPlayback) {
                    listOf(
                        DropdownItem(
                            text = autoReadText,
                            icon = { modifier ->
                                Icon(
                                    imageVector = if (autoReadEnabled) Icons.AutoMirrored.Rounded.VolumeUp
                                    else Icons.AutoMirrored.Rounded.VolumeOff,
                                    contentDescription = null,
                                    modifier = modifier.size(TopBarMenuIconSize),
                                )
                            },
                        ),
                    )
                } else emptyList()
            }
            ListPopupColumn {
                menuItems.forEachIndexed { index, item ->
                    DropdownImpl(
                        item = item,
                        optionSize = menuItems.size,
                        isSelected = false,
                        index = index,
                        onSelectedIndexChange = {
                            showMenu = false
                            if (hasSpeechPlayback && index == menuItems.lastIndex) {
                                speechSettings?.let { current ->
                                    scope.launch {
                                        SpeechSettingsRepository.save(current.copy(chatAutoSpeak = !current.chatAutoSpeak))
                                    }
                                }
                            } else when (index) {
                                0 -> onNewConversation()
                                1 -> onOpenTerminal()
                                2 -> onLaunchKimiWeb()
                                3 -> onOpenBrowser()
                                4 -> onStopKimiWeb()
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun titleForRoute(route: AppRoute?): String = when (route) {
    is AppRoute.Home, is AppRoute.Browser -> ""
    is AppRoute.Terminal -> stringResource(R.string.route_terminal)
    is AppRoute.Tools -> stringResource(R.string.route_tools)
    is AppRoute.WebToolsSettings -> stringResource(R.string.web_tools_settings_title)
    is AppRoute.Skills -> stringResource(R.string.route_skills)
    is AppRoute.Subagents -> "Subagent"
    is AppRoute.Characters -> "角色"
    is AppRoute.CharacterDetail -> "角色详情"
    is AppRoute.CharacterEditor -> "编辑角色"
    is AppRoute.CharacterPersona -> "我的人设"
    is AppRoute.CharacterMemory -> "剧情记忆"
    is AppRoute.Permissions -> stringResource(R.string.route_permissions)
    is AppRoute.SystemEnhance -> stringResource(R.string.route_system_enhancements)
    is AppRoute.Settings -> stringResource(R.string.route_settings)
    is AppRoute.SpeechSettings -> stringResource(R.string.speech_settings_title)
    is AppRoute.SpeechRecognition -> stringResource(R.string.speech_recognition_title)
    is AppRoute.SpeechSynthesis -> stringResource(R.string.speech_synthesis_title)
    is AppRoute.SpeechOss -> stringResource(R.string.speech_oss_title)
    is AppRoute.AppearanceSettings -> stringResource(R.string.appearance_title)
    is AppRoute.DataBackup -> stringResource(R.string.data_backup_title)
    is AppRoute.Memory -> stringResource(R.string.route_memory)
    is AppRoute.LinuxEnvironment -> stringResource(R.string.route_linux_environment)
    is AppRoute.Workspace -> stringResource(R.string.capability_workspace)
    is AppRoute.SharedFolders -> stringResource(R.string.route_shared_folders)
    is AppRoute.LinuxFiles -> stringResource(R.string.route_linux_files)
    is AppRoute.ModelProviders -> stringResource(R.string.route_model_providers)
    is AppRoute.CommunityCatalog -> "从 models.dev 添加"
    is AppRoute.CommunityCatalogProvider -> "选择模型"
    is AppRoute.McpServers -> stringResource(R.string.route_mcp_servers)
    is AppRoute.McpServerDetail -> stringResource(R.string.route_mcp_server_detail)
    is AppRoute.ModelProviderDetail -> stringResource(R.string.route_provider_details)
    is AppRoute.ModelProviderNew -> stringResource(R.string.route_new_provider)
    null -> stringResource(R.string.app_name)
}
