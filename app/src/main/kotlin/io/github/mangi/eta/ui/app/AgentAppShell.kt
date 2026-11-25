package io.github.mangi.eta.ui.app

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.TtsProvider
import io.github.mangi.eta.data.repository.SpeechSettingsRepository
import io.github.mangi.eta.ui.components.AdaptiveTopAppBar
import io.github.mangi.eta.ui.components.AgentTopBarChatHeader
import io.github.mangi.eta.ui.components.ChatImagePreviewHost
import io.github.mangi.eta.ui.components.pane.ConversationSidePaneScaffold
import io.github.mangi.eta.ui.components.LocalChatImagePreviewer
import io.github.mangi.eta.ui.components.RoleplayChatUi
import io.github.mangi.eta.ui.components.TopBarBackButton
import io.github.mangi.eta.ui.components.TopBarBackdrop
import io.github.mangi.eta.ui.components.TopLayer
import io.github.mangi.eta.ui.components.captureGlassBackdrop
import io.github.mangi.eta.ui.components.currentGlassOverlayBackdrop
import io.github.mangi.eta.ui.components.progressiveTopBarBackdrop
import io.github.mangi.eta.ui.components.rememberGlassBackdrop
import io.github.mangi.eta.ui.layout.rememberIsWideScreen
import io.github.mangi.eta.ui.model.AgentModelPickerUiState
import io.github.mangi.eta.ui.model.ConversationPaneUiState
import io.github.mangi.eta.ui.model.ConversationSummaryUi
import io.github.mangi.eta.ui.navigation.AppRoute
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.glass.GlassIconButton
import top.yukonga.miuix.kmp.glass.GlassPopupItem
import top.yukonga.miuix.kmp.glass.GlassTopAppBarDefaults
import top.yukonga.miuix.kmp.glass.GlassTransformPopup
import top.yukonga.miuix.kmp.glass.glassPopupAnchor
import top.yukonga.miuix.kmp.glass.glassPopupAnchorContent
import top.yukonga.miuix.kmp.glass.rememberGlassPopupAnchor
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.Computer
import top.yukonga.miuix.kmp.icon.glass.GlobalVpn
import top.yukonga.miuix.kmp.icon.glass.More
import top.yukonga.miuix.kmp.icon.glass.NewMessage
import top.yukonga.miuix.kmp.icon.glass.Sidebar
import top.yukonga.miuix.kmp.icon.glass.VolumeOff
import top.yukonga.miuix.kmp.icon.glass.VolumeUp
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import io.github.mangi.eta.ui.state.displayName

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
    onOpenBrowser: () -> Unit,
    onSelectConversation: (String) -> Unit,
    onConversationTogglePin: (ConversationSummaryUi) -> Unit,
    onConversationRename: (ConversationSummaryUi) -> Unit,
    onConversationExport: (ConversationSummaryUi) -> Unit,
    onConversationDelete: (ConversationSummaryUi) -> Unit,
    onOpenTools: () -> Unit,
    onOpenSkills: () -> Unit,
    onOpenScheduledTasks: () -> Unit = {},
    onOpenCharacters: () -> Unit,
    onOpenPermissions: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenModelProviders: () -> Unit,
    modifier: Modifier = Modifier,
    homeTitle: String? = null,
    homeCharacter: RoleplayChatUi? = null,
    homeModelPickerState: AgentModelPickerUiState? = null,
    isHomeStreaming: Boolean = false,
    hasPermissionAttention: Boolean = false,
    onHomeModelSelected: (String) -> Unit = {},
    onOpenCharacter: () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    val pageContent: @Composable () -> Unit = {
        // 首页顶栏高度固定，不参与折叠；未绑定顶栏的滚动行为会吞掉聊天列表的单向滑动。
        val scrollBehavior = if (currentRoute is AppRoute.Home) null else MiuixScrollBehavior()
        val pageBackdrop = rememberGlassBackdrop()
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            contentWindowInsets = WindowInsets.safeDrawing.only(
                WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
            ),
            topBar = {
                if (currentRoute !is AppRoute.Settings) {
                    AgentTopBar(
                        route = currentRoute,
                        isCurrentRoute = isCurrentRoute,
                        scrollBehavior = scrollBehavior,
                        backdrop = pageBackdrop,
                        onBack = onBack,
                        onOpenConversationPane = onOpenConversationPane,
                        onNewConversation = onNewConversation,
                        onOpenTerminal = onOpenTerminal,
                        onOpenBrowser = onOpenBrowser,
                        homeTitle = homeTitle,
                        homeCharacter = homeCharacter,
                        homeModelPickerState = homeModelPickerState,
                        isHomeStreaming = isHomeStreaming,
                        onHomeModelSelected = onHomeModelSelected,
                        onOpenCharacter = onOpenCharacter,
                    )
                }
            },
        ) { padding ->
            CompositionLocalProvider(LocalGlassPageBackdrop provides pageBackdrop) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .captureGlassBackdrop(pageBackdrop)
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

    val shellContent: @Composable () -> Unit = {
        if (conversationPaneState != null && currentRoute is AppRoute.Home) {
            ConversationSidePaneScaffold(
                state = conversationPaneState,
                visible = isConversationPaneOpen,
                backHandlerEnabled = isCurrentRoute,
                onOpen = onOpenConversationPane,
                onDismiss = onDismissConversationPane,
                onSearchChange = onSearchConversations,
                onNewConversation = onNewConversation,
                onConversationSelected = onSelectConversation,
                onConversationTogglePin = onConversationTogglePin,
                onConversationRename = onConversationRename,
                onConversationExport = onConversationExport,
                onConversationDelete = onConversationDelete,
                onOpenSettings = onOpenSettings,
                onOpenModelProviders = onOpenModelProviders,
                onOpenTools = onOpenTools,
                onOpenSkills = onOpenSkills,
                onOpenScheduledTasks = onOpenScheduledTasks,
                onOpenCharacters = onOpenCharacters,
                onOpenPermissions = onOpenPermissions,
                activeModelLabel = homeModelPickerState?.selectedModel?.displayName,
                hasPermissionAttention = hasPermissionAttention,
            ) {
                pageContent()
            }
        } else {
            pageContent()
        }
    }

    if (LocalChatImagePreviewer.current != null) {
        Box(modifier = modifier.fillMaxSize()) {
            shellContent()
        }
    } else {
        ChatImagePreviewHost(modifier = modifier) {
            shellContent()
        }
    }
}

@Composable
private fun AgentTopBar(
    route: AppRoute?,
    isCurrentRoute: Boolean,
    scrollBehavior: ScrollBehavior?,
    backdrop: Backdrop?,
    onBack: () -> Unit,
    onOpenConversationPane: () -> Unit,
    onNewConversation: () -> Unit,
    onOpenTerminal: () -> Unit,
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
        HomeTopBar(
            backdrop = backdrop,
            isCurrentRoute = isCurrentRoute,
            onOpenConversationPane = onOpenConversationPane,
            onNewConversation = onNewConversation,
            onOpenTerminal = onOpenTerminal,
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

    AdaptiveTopAppBar(
        title = titleForRoute(route),
        backdrop = backdrop,
        expandable = route !is AppRoute.Terminal && !rememberIsWideScreen(),
        scrollBehavior = scrollBehavior,
        navigationIcon = { TopBarBackButton(onClick = onBack) },
        actions = {},
    )
}

@Composable
private fun HomeTopBar(
    backdrop: Backdrop?,
    isCurrentRoute: Boolean,
    onOpenConversationPane: () -> Unit,
    onNewConversation: () -> Unit,
    onOpenTerminal: () -> Unit,
    onOpenBrowser: () -> Unit,
    centerContent: @Composable () -> Unit,
) {
    val surfaceColor = MiuixTheme.colorScheme.surface
    val progressiveSource = progressiveTopBarBackdrop(backdrop)
    val bandBrush = remember(surfaceColor) { GlassTopAppBarDefaults.bandBrush(surfaceColor) }
    var backgroundCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val progressiveBackdrop = if (progressiveSource != null) {
        rememberLayerBackdrop {
            val coordinates = backgroundCoordinates?.takeIf { it.isAttached }
            with(progressiveSource) { drawBackdrop(this@rememberLayerBackdrop, coordinates) }
            drawContent()
        }
    } else {
        null
    }
    val buttonBackdrop = progressiveBackdrop ?: backdrop
    val captureModifier = if (progressiveBackdrop != null) {
        Modifier
            .onGloballyPositioned { backgroundCoordinates = it }
            .layerBackdrop(progressiveBackdrop)
    } else {
        Modifier
    }

    TopBarBackdrop(
        progressiveBackdrop = progressiveSource,
        backgroundColor = surfaceColor,
        fallbackBrush = bandBrush,
        captureModifier = captureModifier,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))
                .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Horizontal))
                .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Top))
                .height(60.dp)
                .pointerInput(Unit) {
                    detectTapGestures { }
                }
                .padding(horizontal = GlassTopAppBarDefaults.HorizontalPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlassIconButton(
                onClick = onOpenConversationPane,
                backdrop = buttonBackdrop,
            ) {
                Icon(
                    imageVector = MiuixIcons.Glass.Sidebar,
                    modifier = Modifier.size(28.dp),
                    tint = MiuixTheme.colorScheme.onSurface,
                    contentDescription = stringResource(R.string.action_conversation_history),
                )
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                centerContent()
            }
            TopBarOverflowMenu(
                backdrop = buttonBackdrop,
                isCurrentRoute = isCurrentRoute,
                onNewConversation = onNewConversation,
                onOpenTerminal = onOpenTerminal,
                onOpenBrowser = onOpenBrowser,
            )
        }
    }
}

@Composable
private fun TopBarOverflowMenu(
    backdrop: Backdrop?,
    isCurrentRoute: Boolean,
    onNewConversation: () -> Unit,
    onOpenTerminal: () -> Unit,
    onOpenBrowser: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }
    LaunchedEffect(isCurrentRoute) {
        if (!isCurrentRoute) showMenu = false
    }
    val moreAnchor = rememberGlassPopupAnchor()
    val scope = rememberCoroutineScope()
    val speechSettings by remember { SpeechSettingsRepository.settingsFlow() }.collectAsStateWithLifecycle(initialValue = null)
    val hasSpeechPlayback = speechSettings?.let { it.tts != TtsProvider.NONE } == true
    val autoReadEnabled = speechSettings?.chatAutoSpeak == true
    val newConversationText = stringResource(R.string.action_new_conversation)
    val openTerminalText = stringResource(R.string.action_open_terminal)
    val openBrowserText = stringResource(R.string.action_open_browser)
    val autoReadText = stringResource(
        if (autoReadEnabled) R.string.speech_disable_auto_read else R.string.speech_enable_auto_read,
    )
    val overlayBackdrop = currentGlassOverlayBackdrop() ?: backdrop

    GlassIconButton(
        onClick = { showMenu = true },
        backdrop = backdrop,
        modifier = Modifier.glassPopupAnchor(
            anchor = moreAnchor,
            cornerRadius = GlassTopAppBarDefaults.ButtonSize / 2,
            floating = true,
        ),
    ) {
        Icon(
            imageVector = MiuixIcons.Glass.More,
            modifier = Modifier
                .size(28.dp)
                .glassPopupAnchorContent(moreAnchor),
            tint = MiuixTheme.colorScheme.onSurface,
            contentDescription = stringResource(R.string.action_more),
        )
    }

    if (isCurrentRoute) {
        TopLayer {
            GlassTransformPopup(
                show = showMenu,
                onDismissRequest = { showMenu = false },
                anchor = moreAnchor,
                backdrop = overlayBackdrop,
                anchorContent = {
                    Icon(
                        imageVector = MiuixIcons.Glass.More,
                        contentDescription = null,
                        modifier = Modifier.size(28.dp),
                        tint = MiuixTheme.colorScheme.onSurface,
                    )
                },
            ) {
                GlassPopupItem(
                    text = newConversationText,
                    icon = MiuixIcons.Glass.NewMessage,
                    onClick = {
                        showMenu = false
                        onNewConversation()
                    },
                )
                GlassPopupItem(
                    text = openTerminalText,
                    icon = MiuixIcons.Glass.Computer,
                    onClick = {
                        showMenu = false
                        onOpenTerminal()
                    },
                )
                GlassPopupItem(
                    text = openBrowserText,
                    icon = MiuixIcons.Glass.GlobalVpn,
                    onClick = {
                        showMenu = false
                        onOpenBrowser()
                    },
                )
                if (hasSpeechPlayback) {
                    GlassPopupItem(
                        text = autoReadText,
                        icon = if (autoReadEnabled) MiuixIcons.Glass.VolumeUp else MiuixIcons.Glass.VolumeOff,
                        onClick = {
                            showMenu = false
                            speechSettings?.let { current ->
                                scope.launch {
                                    SpeechSettingsRepository.save(current.copy(chatAutoSpeak = !current.chatAutoSpeak))
                                }
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
    is AppRoute.ImageGenSettings -> stringResource(R.string.image_gen_settings_title)
    is AppRoute.ImageGenGallery -> stringResource(R.string.image_gen_gallery_title)
    is AppRoute.Skills -> stringResource(R.string.route_skills)
    is AppRoute.ScheduledTasks -> stringResource(R.string.route_scheduled_tasks)
    is AppRoute.ScheduledTaskDetail -> stringResource(R.string.route_scheduled_task_detail)
    is AppRoute.ScheduledTaskEditor -> stringResource(
        if (route.taskId.isNullOrBlank()) R.string.route_scheduled_task_new else R.string.route_scheduled_task_edit,
    )
    is AppRoute.Subagents -> stringResource(R.string.ui_misc_route_subagents)
    is AppRoute.ConversationTitleSettings -> stringResource(R.string.conversation_title_settings_title)
    is AppRoute.AssistantOverlaySettings -> stringResource(R.string.assistant_overlay_settings_title)
    is AppRoute.Characters -> stringResource(R.string.conversation_dock_characters)
    is AppRoute.CharacterDetail -> stringResource(R.string.ui_misc_route_character_detail)
    is AppRoute.CharacterEditor -> stringResource(R.string.ui_misc_route_character_editor)
    is AppRoute.CharacterPersona -> stringResource(R.string.ui_misc_route_character_persona)
    is AppRoute.CharacterMemory -> stringResource(R.string.ui_misc_route_character_memory)
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
    is AppRoute.CommunityCatalog -> stringResource(R.string.catalog_i18n_title)
    is AppRoute.CommunityCatalogProvider -> stringResource(R.string.catalog_i18n_select_models)
    is AppRoute.McpServers -> stringResource(R.string.route_mcp_servers)
    is AppRoute.McpServerDetail -> stringResource(R.string.route_mcp_server_detail)
    is AppRoute.ModelProviderDetail -> stringResource(R.string.route_provider_details)
    is AppRoute.ModelProviderNew -> stringResource(R.string.route_new_provider)
    null -> stringResource(R.string.app_name)
}
