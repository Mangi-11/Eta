package io.github.mangi.eta.ui.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.mangi.eta.EtaApp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.device.BoundedRootCommandExecutor
import io.github.mangi.eta.agent.device.DeviceLocationProvider
import io.github.mangi.eta.agent.device.RootAccess
import io.github.mangi.eta.ui.screens.scheduled.ScheduledTaskDetailScreen
import io.github.mangi.eta.ui.screens.scheduled.ScheduledTaskEditorScreen
import io.github.mangi.eta.ui.screens.scheduled.ScheduledTasksScreen
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import io.github.mangi.eta.ui.screens.settings.AppearanceSettingsScreen
import io.github.mangi.eta.ui.IncomingShareRequest
import io.github.mangi.eta.ui.screens.settings.SettingsScreen
import io.github.mangi.eta.ui.components.ChatImagePreviewHost
import io.github.mangi.eta.ui.components.DialogActions
import io.github.mangi.eta.ui.components.LocalChatImagePreviewer
import io.github.mangi.eta.ui.components.currentGlassOverlayBackdrop
import io.github.mangi.eta.ui.model.AgentHomeAction
import io.github.mangi.eta.ui.model.AgentMemoryAction
import io.github.mangi.eta.ui.model.AgentSkillsAction
import io.github.mangi.eta.ui.model.AgentSystemEnhanceAction
import io.github.mangi.eta.ui.model.AgentToolsAction
import io.github.mangi.eta.ui.model.ConversationSummaryUi
import io.github.mangi.eta.ui.model.LOCAL_NETWORK_PERMISSION_ITEM_ID
import io.github.mangi.eta.ui.model.PermissionHealthAction
import io.github.mangi.eta.ui.model.PermissionStatusUi
import io.github.mangi.eta.ui.model.roleplayChatUi
import io.github.mangi.eta.ui.navigation.AgentNavigator
import io.github.mangi.eta.ui.navigation.AppRoute
import io.github.mangi.eta.ui.screens.providers.CommunityCatalogProviderScreen
import io.github.mangi.eta.ui.screens.providers.CommunityCatalogScreen
import io.github.mangi.eta.ui.screens.providers.ModelProviderDetailScreen
import io.github.mangi.eta.ui.screens.providers.ModelProviderListScreen
import io.github.mangi.eta.ui.screens.backup.DataBackupScreen
import io.github.mangi.eta.ui.screens.browser.AgentBrowserActivity
import io.github.mangi.eta.agent.browser.BrowserSessionRegistry
import io.github.mangi.eta.ui.screens.characters.CharacterDetailScreen
import io.github.mangi.eta.ui.screens.characters.CharacterEditorScreen
import io.github.mangi.eta.ui.screens.characters.CharacterLibraryScreen
import io.github.mangi.eta.ui.screens.characters.CharacterMemoryScreen
import io.github.mangi.eta.ui.screens.characters.CharacterPersonaScreen
import io.github.mangi.eta.ui.screens.enhance.SystemEnhanceScreen
import io.github.mangi.eta.ui.screens.home.AgentHomeScreen
import io.github.mangi.eta.ui.screens.mcp.McpServerDetailScreen
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
import io.github.mangi.eta.ui.screens.terminal.TerminalEntryScreen
import io.github.mangi.eta.ui.screens.terminal.WorkspaceScreen
import io.github.mangi.eta.ui.screens.imagegen.ImageGenGalleryScreen
import io.github.mangi.eta.ui.screens.imagegen.ImageGenSettingsScreen
import io.github.mangi.eta.ui.screens.tools.AgentToolsScreen
import io.github.mangi.eta.ui.screens.tools.WebToolsSettingsScreen
import io.github.mangi.eta.ui.screens.voice.SpeechOssScreen
import io.github.mangi.eta.ui.screens.voice.SpeechRecognitionScreen
import io.github.mangi.eta.ui.screens.voice.SpeechSettingsScreen
import io.github.mangi.eta.ui.screens.voice.SpeechSynthesisScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.glass.GlassDialog
import top.yukonga.miuix.kmp.glass.GlassDialogButton
import top.yukonga.miuix.kmp.glass.GlassDialogButtonRole
import top.yukonga.miuix.kmp.glass.GlassTextField
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.core.NavDisplayEffects
import top.yukonga.miuix.kmp.nav.core.rememberNavBackStack
import top.yukonga.miuix.kmp.nav.core.rememberNavSystemCornerRadius
import top.yukonga.miuix.kmp.nav.transition.NavSwipeDirection
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import io.github.mangi.eta.ui.state.AgentAppState
import io.github.mangi.eta.ui.state.ArchivedConversationRequest
import io.github.mangi.eta.ui.state.CharacterLibraryViewModel
import io.github.mangi.eta.ui.state.CommunityCatalogViewModel
import io.github.mangi.eta.ui.state.ConversationMarkdownExporter
import io.github.mangi.eta.ui.state.buildToolsState

/**
 * Agent App 根组件：持有本地导航栈，并把 Screen actions 交给 [AgentAppState]。
 */
@Composable
fun AgentAppRoot(
    assistantConversationKey: String? = null,
    requestedConversationId: String? = null,
    onRequestedConversationOpened: () -> Unit = {},
    requestedArchivedConversation: ArchivedConversationRequest? = null,
    onArchivedConversationOpened: () -> Unit = {},
    requestedScheduledTaskId: String? = null,
    onScheduledTaskOpened: () -> Unit = {},
    sharedContent: IncomingShareRequest? = null,
    onSharedContentAccepted: () -> Unit = {},
    openSpeechSettings: Boolean = false,
    openTerminal: Boolean = false,
    onTerminalOpened: () -> Unit = {},
    onSpeechSettingsOpened: () -> Unit = {},
    onAssistantConversationOpened: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val uiScope = rememberCoroutineScope()
    val backStack = rememberNavBackStack<AppRoute>(AppRoute.Home)
    LaunchedEffect(openSpeechSettings) {
        if (openSpeechSettings) {
            if (backStack.lastOrNull() != AppRoute.SpeechSettings) backStack.add(AppRoute.SpeechSettings)
            onSpeechSettingsOpened()
        }
    }
    LaunchedEffect(openTerminal) {
        if (openTerminal) {
            if (backStack.lastOrNull() != AppRoute.Terminal) backStack.add(AppRoute.Terminal)
            onTerminalOpened()
        }
    }
    val navigator = remember(backStack) { AgentNavigator(backStack) }
    var navigationResetKey by rememberSaveable { mutableIntStateOf(0) }
    val appViewModel = viewModel<AgentAppViewModel>()
    val agentState = appViewModel.state
    val skills = appViewModel.skills
    val memory = appViewModel.memory
    val permissionHealth = appViewModel.permissionHealth
    val configuration = LocalConfiguration.current
    val toolsState = remember(configuration) { buildToolsState(context) }
    val characterStore = viewModel<CharacterLibraryViewModel>().store
    val communityCatalogStore = viewModel<CommunityCatalogViewModel>().store
    val requestExecutionNotifications = rememberExecutionNotificationRequest()
    val requestLocalNetworkPermission = rememberLocalNetworkPermissionRequest(permissionHealth::refreshPermissionHealth)
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        permissionHealth.refreshPermissionHealth()
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                RootAccess.refresh(context)
                permissionHealth.refreshPermissionHealth()
                agentState.refreshRuntimeResults()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var conversationPaneOpen by rememberSaveable { mutableStateOf(false) }
    var conversationRenameTarget by remember { mutableStateOf<ConversationSummaryUi?>(null) }
    var conversationDeleteTarget by remember { mutableStateOf<ConversationSummaryUi?>(null) }
    var conversationExportTarget by remember { mutableStateOf<ConversationSummaryUi?>(null) }
    var messageDeleteTarget by remember { mutableStateOf<MessageMutationTarget?>(null) }
    var messageRegenerateTarget by remember { mutableStateOf<MessageMutationTarget?>(null) }
    val conversationExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/markdown"),
    ) { uri ->
        val target = conversationExportTarget
        conversationExportTarget = null
        if (uri == null || target == null) return@rememberLauncherForActivityResult
        uiScope.launch {
            try {
                val markdown = agentState.exportConversationMarkdown(target.id)
                    ?: error(resources.getString(R.string.conversation_export_failed))
                val output = context.contentResolver.openOutputStream(uri)
                    ?: error(resources.getString(R.string.conversation_export_failed))
                withContext(Dispatchers.IO) {
                    output.use { it.write(markdown.toByteArray(Charsets.UTF_8)) }
                }
                Toast.makeText(
                    context,
                    resources.getString(R.string.conversation_exported),
                    Toast.LENGTH_SHORT,
                ).show()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                Toast.makeText(
                    context,
                    resources.getString(R.string.conversation_export_failed),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }
    val focusManager = LocalFocusManager.current

    LaunchedEffect(Unit) {
        RuntimeConfigRepository.ensureDefaults(EtaApp.serviceInstance)
    }

    LaunchedEffect(requestedConversationId) {
        val conversationId = requestedConversationId ?: return@LaunchedEffect
        agentState.selectConversation(conversationId)
        conversationPaneOpen = false
        navigator.popToHome()
        onRequestedConversationOpened()
    }

    LaunchedEffect(requestedArchivedConversation) {
        val request = requestedArchivedConversation ?: return@LaunchedEffect
        // 外部入口的结果要先导入归档才有会话；找不到时停在当前界面，不切到无关的会话。
        if (agentState.openArchivedConversation(request.source, request.conversationKey)) {
            conversationPaneOpen = false
            navigator.popToHome()
        }
        onArchivedConversationOpened()
    }

    LaunchedEffect(requestedScheduledTaskId) {
        val taskId = requestedScheduledTaskId ?: return@LaunchedEffect
        conversationPaneOpen = false
        navigator.push(AppRoute.ScheduledTaskDetail(taskId))
        onScheduledTaskOpened()
    }

    LaunchedEffect(assistantConversationKey) {
        val conversationKey = assistantConversationKey ?: return@LaunchedEffect
        val opened = agentState.openAssistantConversation(conversationKey)
        if (opened) {
            conversationPaneOpen = false
            // 接管落到主聊天舞台：与主界面同一页面、同一侧边对话列表，不再开独立对话页。
            navigator.popToHome()
        }
        onAssistantConversationOpened(opened)
    }

    LaunchedEffect(sharedContent) {
        val request = sharedContent ?: return@LaunchedEffect
        focusManager.clearFocus()
        conversationPaneOpen = false
        if (backStack.lastOrNull() != AppRoute.Home) {
            navigator.popToHome()
            navigationResetKey++
        }
        agentState.acceptSharedContent(request)
        onSharedContentAccepted()
    }

    fun pushRoute(
        route: AppRoute,
        restoreConversationPaneOnBack: Boolean = conversationPaneOpen,
    ) {
        conversationPaneOpen = restoreConversationPaneOnBack
        navigator.push(route)
    }

    fun popRoute() {
        if (!navigator.pop()) {
            (context as? Activity)?.finish()
        }
    }

    fun selectConversation(conversationId: String) {
        focusManager.clearFocus()
        agentState.selectConversation(conversationId)
        navigator.popToHome()
        conversationPaneOpen = false
    }

    fun createConversation() {
        focusManager.clearFocus()
        agentState.createConversation()
        navigator.popToHome()
        conversationPaneOpen = false
    }

    fun openConversationBrowser() {
        focusManager.clearFocus()
        val sessionKey = agentState.conversationPaneState.selectedConversationId
            ?: BrowserSessionRegistry.MANUAL_SESSION_KEY
        context.startActivity(AgentBrowserActivity.createIntent(context, sessionKey))
    }

    @Composable
    fun RoutedShell(
        route: AppRoute,
        applyShellPadding: Boolean = true,
        content: @Composable (androidx.compose.foundation.layout.PaddingValues) -> Unit,
    ) {
        val isHome = route is AppRoute.Home
        val hasPermissionAttention = permissionHealth.permissionHealthState.items.any { item ->
            item.id in CorePermissionAttentionIds &&
                (item.status == PermissionStatusUi.Missing || item.status == PermissionStatusUi.Warning)
        }
        AgentAppShell(
            currentRoute = route,
            isCurrentRoute = backStack.lastOrNull() == route,
            conversationPaneState = agentState.conversationPaneState,
            isConversationPaneOpen = conversationPaneOpen,
            onBack = { popRoute() },
            onOpenConversationPane = { conversationPaneOpen = true },
            onDismissConversationPane = { conversationPaneOpen = false },
            onSearchConversations = { query -> agentState.updateSearchQuery(query) },
            onNewConversation = { createConversation() },
            onOpenTerminal = { pushRoute(AppRoute.Terminal) },
            onOpenBrowser = { openConversationBrowser() },
            onSelectConversation = { conversationId -> selectConversation(conversationId) },
            onConversationTogglePin = { conversation ->
                agentState.toggleConversationPin(conversation.id)
            },
            onConversationRename = { conversation ->
                conversationRenameTarget = conversation
            },
            onConversationExport = { conversation ->
                conversationExportTarget = conversation
                conversationExportLauncher.launch(
                    ConversationMarkdownExporter.defaultFileName(
                        title = conversation.title.ifBlank { conversation.preview },
                        fallback = resources.getString(R.string.conversation_export_default_name),
                    ),
                )
            },
            onConversationDelete = { conversation ->
                conversationDeleteTarget = conversation
            },
            onOpenTools = { pushRoute(AppRoute.Tools) },
            onOpenSkills = { pushRoute(AppRoute.Skills) },
            onOpenScheduledTasks = { pushRoute(AppRoute.ScheduledTasks) },
            onOpenCharacters = { pushRoute(AppRoute.Characters) },
            onOpenPermissions = { pushRoute(AppRoute.Permissions) },
            onOpenSettings = { pushRoute(AppRoute.Settings) },
            onOpenModelProviders = { pushRoute(AppRoute.ModelProviders) },
            homeTitle = if (isHome) agentState.currentConversationTitle else null,
            homeCharacter = if (isHome) agentState.homeState.roleplayChatUi() else null,
            homeModelPickerState = if (isHome) agentState.modelPickerState else null,
            isHomeStreaming = isHome && agentState.homeState.isStreaming,
            hasPermissionAttention = isHome && hasPermissionAttention,
            onHomeModelSelected = agentState::selectModel,
            onOpenCharacter = {
                agentState.homeState.roleplay?.let { pushRoute(AppRoute.CharacterDetail(it.characterId)) }
            },
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (applyShellPadding) Modifier.padding(padding) else Modifier)
            ) {
                content(padding)
            }
        }
    }

    val swipeBackDirection = if (LocalLayoutDirection.current == LayoutDirection.Rtl) {
        NavSwipeDirection.RightToLeft
    } else {
        NavSwipeDirection.LeftToRight
    }
    ChatImagePreviewHost {
        val previewer = LocalChatImagePreviewer.current
        LaunchedEffect(previewer, navigationResetKey) {
            snapshotFlow { backStack.lastOrNull() }.collect {
                if (previewer?.request != null) {
                    previewer.closeImmediately()
                }
            }
        }
        key(navigationResetKey) {
            val activeSwipeDismiss = LocalAppearanceSettings.current.swipeDismissEnabled
                .let { enabled -> swipeBackDirection.takeIf { enabled } }
            NavDisplay(
                backStack = backStack,
                onBack = { popRoute() },
                effects = NavDisplayEffects(
                    cornerClipRadius = rememberNavSystemCornerRadius(),
                ),
            ) {
                    entry<AppRoute.Home>(swipeDismiss = activeSwipeDismiss) {
                        RoutedShell(route = AppRoute.Home, applyShellPadding = false) { shellPadding ->
                            AgentHomeScreen(
                                state = agentState.homeState,
                                modelPickerState = agentState.modelPickerState,
                                isBranching = agentState.isBranchingCurrentConversation,
                                conversationKey = agentState.conversationPaneState.selectedConversationId,
                                topInset = shellPadding.calculateTopPadding(),
                                onAction = { action ->
                                    when (action) {
                                        is AgentHomeAction.ReasoningEffortChanged ->
                                            agentState.updateReasoningEffort(action.effort)
                                        AgentHomeAction.CompactContext -> agentState.compactCurrentContext()
                                        AgentHomeAction.LoadEarlierMessages -> agentState.loadEarlierMessages()
                                        is AgentHomeAction.HistoryViewportChanged -> agentState.updateHistoryViewport(action.atLatest)
                                        AgentHomeAction.RetryConversationLoad -> agentState.retryConversationLoad()
                                        is AgentHomeAction.ModelSelected -> agentState.selectModel(action.modelId)
                                        is AgentHomeAction.SubmitMessage -> { requestExecutionNotifications(); agentState.sendCurrentMessage(action.text) }
                                        is AgentHomeAction.InterruptAndSubmitMessage -> { requestExecutionNotifications(); agentState.interruptAndSendCurrentMessage(action.text) }
                                        is AgentHomeAction.SteerQueuedMessage -> { requestExecutionNotifications(); agentState.steerQueuedMessage(action.id) }
                                        is AgentHomeAction.RecallQueuedMessage -> agentState.recallQueuedMessage(action.id, action.currentDraftText)
                                        is AgentHomeAction.RemoveQueuedMessage -> agentState.removeQueuedMessage(action.id)
                                        AgentHomeAction.StopRun -> agentState.stopCurrentRun()
                                        is AgentHomeAction.ImageAttached -> agentState.attachImage(action.uri)
                                        is AgentHomeAction.RemoveImage -> agentState.removePendingImage(action.id)
                                        is AgentHomeAction.FilesAttached -> agentState.attachFiles(action.uris)
                                        is AgentHomeAction.FolderAttached -> agentState.attachFolder(action.uri)
                                        is AgentHomeAction.FilePathAttached -> agentState.attachFilePath(action.path)
                                        is AgentHomeAction.RemoveFileReference ->
                                            agentState.removePendingFileReference(action.id)
                                        is AgentHomeAction.EditMessage -> agentState.beginMessageEdit(action.id)
                                        AgentHomeAction.CancelMessageEdit -> agentState.cancelMessageEdit()
                                        is AgentHomeAction.DeleteMessage -> {
                                            agentState.messageRevisionImpact(action.id)?.let { impact ->
                                                messageDeleteTarget = MessageMutationTarget(action.id, impact.laterTurnCount)
                                            }
                                        }
                                        is AgentHomeAction.RegenerateMessage -> {
                                            val impact = agentState.messageRevisionImpact(action.id)
                                            if (agentState.homeState.roleplay != null || impact?.laterTurnCount == 0) {
                                                agentState.regenerateMessage(action.id)
                                            } else if (impact != null) {
                                                messageRegenerateTarget = MessageMutationTarget(action.id, impact.laterTurnCount)
                                            }
                                        }
                                        is AgentHomeAction.BranchConversation ->
                                            agentState.branchConversation(action.conversationId, action.messageId)
                                        is AgentHomeAction.SelectReplyCandidate -> agentState.selectReplyCandidate(action.id, action.index)
                                        AgentHomeAction.OpenTools -> pushRoute(AppRoute.Tools)
                                        AgentHomeAction.OpenSkills -> pushRoute(AppRoute.Skills)
                                        AgentHomeAction.OpenPermissions -> pushRoute(AppRoute.Permissions)
                                        AgentHomeAction.OpenSystemEnhance -> pushRoute(AppRoute.SystemEnhance)
                                        AgentHomeAction.OpenSettings -> pushRoute(AppRoute.Settings)
                                        AgentHomeAction.OpenBrowser -> openConversationBrowser()
                                        AgentHomeAction.ExpandRunTrace -> Unit
                                    }
                                },
                                isLoadingConversation = agentState.isLoadingConversation,
                                conversationLoadFailed = agentState.conversationLoadFailed,
                                historyLoadState = agentState.historyLoadState,
                                isDrawerOpen = conversationPaneOpen,
                            )
                        }
                    }
                    entry<AppRoute.Browser> { route ->
                        // 保留已保存路由的恢复入口；新打开操作直接进入原生浏览器窗口。
                        LaunchedEffect(route) {
                            context.startActivity(AgentBrowserActivity.createIntent(context, route.sessionKey))
                            backStack.remove(route)
                            if (backStack.isEmpty()) backStack.add(AppRoute.Home)
                        }
                    }
                    entry<AppRoute.Terminal>(swipeDismiss = activeSwipeDismiss) {
                        LaunchedEffect(Unit) { requestExecutionNotifications() }
                        RoutedShell(route = AppRoute.Terminal) {
                            TerminalEntryScreen(
                                terminalStore = appViewModel.terminalStore,
                                consoleStore = appViewModel.consoleStore,
                                onOpenEnvironment = { pushRoute(AppRoute.LinuxEnvironment) },
                            )
                        }
                    }
                    entry<AppRoute.Tools>(swipeDismiss = activeSwipeDismiss) {
                        AgentToolsScreen(
                            state = toolsState,
                            onAction = { action ->
                                when (action) {
                                    AgentToolsAction.NavigateBack -> popRoute()
                                    AgentToolsAction.OpenBrowser -> openConversationBrowser()
                                    AgentToolsAction.OpenWebToolsSettings -> pushRoute(AppRoute.WebToolsSettings)
                                    AgentToolsAction.OpenImageGenSettings -> pushRoute(AppRoute.ImageGenSettings)
                                    AgentToolsAction.OpenEnhancements -> pushRoute(AppRoute.SystemEnhance)
                                    AgentToolsAction.OpenPermissions -> pushRoute(AppRoute.Permissions)
                                }
                            },
                        )
                    }
                    entry<AppRoute.WebToolsSettings>(swipeDismiss = activeSwipeDismiss) {
                        WebToolsSettingsScreen(onBack = ::popRoute)
                    }
                    entry<AppRoute.ImageGenSettings>(swipeDismiss = activeSwipeDismiss) {
                        ImageGenSettingsScreen(
                            onBack = ::popRoute,
                            onOpenGallery = { pushRoute(AppRoute.ImageGenGallery) },
                        )
                    }
                    entry<AppRoute.ImageGenGallery>(swipeDismiss = activeSwipeDismiss) {
                        ImageGenGalleryScreen(onBack = ::popRoute)
                    }
                    entry<AppRoute.Subagents>(swipeDismiss = activeSwipeDismiss) {
                        SubagentSettingsScreen(onBack = ::popRoute)
                    }
                    entry<AppRoute.ConversationTitleSettings>(swipeDismiss = activeSwipeDismiss) {
                        ConversationTitleSettingsScreen(onBack = ::popRoute)
                    }
                    entry<AppRoute.AssistantOverlaySettings>(swipeDismiss = activeSwipeDismiss) {
                        AssistantOverlaySettingsScreen(onBack = ::popRoute)
                    }
                    entry<AppRoute.Skills>(swipeDismiss = activeSwipeDismiss) {
                        LaunchedEffect(Unit) {
                            skills.refreshSkills()
                        }
                        AgentSkillsScreen(
                            state = skills.skillsState,
                            onAction = { action ->
                                when (action) {
                                    AgentSkillsAction.NavigateBack -> popRoute()
                                    is AgentSkillsAction.ImportZip -> skills.importSkillZip(action.uri)
                                    AgentSkillsAction.ConfirmZipReplacement -> skills.confirmSkillZipReplacement()
                                    AgentSkillsAction.CancelZipReplacement -> skills.cancelSkillZipReplacement()
                                    AgentSkillsAction.DismissNotice -> skills.dismissSkillNotice()
                                    is AgentSkillsAction.ToggleSkill -> skills.toggleSkill(action.skillId, action.enabled)
                                    is AgentSkillsAction.DeleteSkill -> skills.deleteSkill(action.skillId)
                                    is AgentSkillsAction.ReinstallBuiltin -> skills.reinstallBuiltin(action.skillId)
                                }
                            },
                        )
                    }
                    entry<AppRoute.Characters>(swipeDismiss = activeSwipeDismiss) {
                        LaunchedEffect(Unit) {
                            characterStore.loadLibrary()
                        }
                        CharacterLibraryScreen(characterStore, { if (navigator.current() == AppRoute.Characters) pushRoute(it) }, ::popRoute)
                    }
                    entry<AppRoute.CharacterDetail>(swipeDismiss = activeSwipeDismiss) { route ->
                        LaunchedEffect(route.characterId) {
                            characterStore.loadDetail(route.characterId)
                        }
                        CharacterDetailScreen(route.characterId, characterStore, { if (navigator.current() == route) pushRoute(it) }, ::popRoute) { binding, greeting ->
                            if (navigator.current() == route) {
                                agentState.startCharacterConversation(binding, greeting)
                                conversationPaneOpen = false
                                navigator.popToHome()
                                // 开始新故事直接呈现首页；重置导航呈现态，避免多层退栈扫过角色列表。
                                navigationResetKey++
                            }
                        }
                    }
                    entry<AppRoute.CharacterEditor>(swipeDismiss = activeSwipeDismiss) { route ->
                        LaunchedEffect(route.characterId) {
                            characterStore.loadEditor(route.characterId)
                        }
                        CharacterEditorScreen(route.characterId, characterStore, ::popRoute) { id ->
                            if (navigator.current() == route) navigator.replace(AppRoute.CharacterDetail(id))
                        }
                    }
                    entry<AppRoute.CharacterPersona>(swipeDismiss = activeSwipeDismiss) {
                        LaunchedEffect(Unit) {
                            characterStore.loadPersona()
                        }
                        CharacterPersonaScreen(characterStore) {
                            if (navigator.current() == AppRoute.CharacterPersona) popRoute()
                        }
                    }
                    entry<AppRoute.CharacterMemory>(swipeDismiss = activeSwipeDismiss) { route ->
                        LaunchedEffect(route.characterId) {
                            characterStore.loadMemory(route.characterId)
                        }
                        CharacterMemoryScreen(route.characterId, characterStore, ::popRoute)
                    }
                    entry<AppRoute.Permissions>(swipeDismiss = activeSwipeDismiss) {
                        LaunchedEffect(Unit) {
                            permissionHealth.refreshPermissionHealth()
                        }
                        PermissionHealthScreen(
                            state = permissionHealth.permissionHealthState,
                            onAction = { action ->
                                when (action) {
                                    PermissionHealthAction.NavigateBack -> popRoute()
                                    is PermissionHealthAction.OpenItemAction -> {
                                        when (action.itemId) {
                                            LOCAL_NETWORK_PERMISSION_ITEM_ID -> requestLocalNetworkPermission()
                                            "calendar" -> locationPermissionLauncher.launch(io.github.mangi.eta.agent.device.CalendarPermissions.requested)
                                            "notification_policy" -> context.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
                                            "accessibility" -> {
                                                runCatching {
                                                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                                                }
                                            }
                                            "overlay" -> {
                                                runCatching {
                                                    context.startActivity(
                                                        Intent(
                                                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                                            Uri.parse("package:${context.packageName}")
                                                        )
                                                    )
                                                }
                                            }
                                            "background" -> {
                                                if (RootAccess.isGranted && Build.MANUFACTURER.lowercase() in setOf("oppo", "realme", "oneplus")) {
                                                    uiScope.launch(Dispatchers.IO) {
                                                        BoundedRootCommandExecutor(AndroidAgentLogger).use {
                                                            it.execute(
                                                                "am start --user current -n " +
                                                                    "com.oplus.battery/com.oplus.powermanager.fuelgaue.PowerControlActivity " +
                                                                    "--es title Eta --es pkgName io.github.mangi.eta --es drainType APP",
                                                            )
                                                        }
                                                    }
                                                } else {
                                                    runCatching {
                                                        context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                                                    }
                                                }
                                            }
                                            "app_list" -> {
                                                runCatching {
                                                    context.startActivity(
                                                        Intent(
                                                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                                            Uri.parse("package:${context.packageName}")
                                                        )
                                                    )
                                                }
                                            }
                                            "location" -> {
                                                when (DeviceLocationProvider.accessState(context)) {
                                                    DeviceLocationProvider.AccessState.DENIED -> {
                                                        locationPermissionLauncher.launch(
                                                            arrayOf(
                                                                Manifest.permission.ACCESS_COARSE_LOCATION,
                                                                Manifest.permission.ACCESS_FINE_LOCATION,
                                                            )
                                                        )
                                                    }
                                                    DeviceLocationProvider.AccessState.FOREGROUND_ONLY -> {
                                                        runCatching {
                                                            context.startActivity(
                                                                Intent(
                                                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                                                    Uri.parse("package:${context.packageName}")
                                                                )
                                                            )
                                                        }
                                                    }
                                                    DeviceLocationProvider.AccessState.DISABLED -> {
                                                        runCatching {
                                                            context.startActivity(
                                                                Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                                                            )
                                                        }
                                                    }
                                                    DeviceLocationProvider.AccessState.AVAILABLE -> {
                                                        permissionHealth.refreshPermissionHealth()
                                                    }
                                                }
                                            }
                                            "notification_history" -> {
                                                runCatching {
                                                    context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                                                }
                                            }
                                            "usage_access" -> {
                                                runCatching {
                                                    context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                                                }
                                            }
                                            "notifications" -> {
                                                context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                                            }
                                            "root" -> pushRoute(AppRoute.SystemEnhance)
                                        }
                                    }
                                }
                            },
                        )
                    }
                    entry<AppRoute.SystemEnhance>(swipeDismiss = activeSwipeDismiss) {
                        SystemEnhanceScreen(
                            onAction = { action ->
                                when (action) {
                                    AgentSystemEnhanceAction.NavigateBack -> popRoute()
                                    AgentSystemEnhanceAction.RequestRoot -> { RootAccess.request(context) }
                                    AgentSystemEnhanceAction.RefreshRoot -> { RootAccess.refresh(context) }
                                }
                            },
                        )
                    }
                    entry<AppRoute.Workspace>(swipeDismiss = activeSwipeDismiss) {
                        WorkspaceScreen(onBack = ::popRoute)
                    }
                    entry<AppRoute.Settings>(swipeDismiss = activeSwipeDismiss) {
                        SettingsScreen(
                            context = context,
                            onNavigate = { route -> pushRoute(route) },
                            onBack = ::popRoute
                        )
                    }
                    entry<AppRoute.SpeechSettings>(swipeDismiss = activeSwipeDismiss) {
                        SpeechSettingsScreen(
                            onBack = ::popRoute,
                            onNavigate = { route -> pushRoute(route) },
                        )
                    }
                    entry<AppRoute.SpeechRecognition>(swipeDismiss = activeSwipeDismiss) {
                        SpeechRecognitionScreen(
                            onBack = ::popRoute,
                            onOpenOss = { pushRoute(AppRoute.SpeechOss) },
                        )
                    }
                    entry<AppRoute.SpeechSynthesis>(swipeDismiss = activeSwipeDismiss) {
                        SpeechSynthesisScreen(onBack = ::popRoute)
                    }
                    entry<AppRoute.SpeechOss>(swipeDismiss = activeSwipeDismiss) {
                        SpeechOssScreen(onBack = ::popRoute)
                    }
                    entry<AppRoute.AppearanceSettings>(swipeDismiss = activeSwipeDismiss) {
                        AppearanceSettingsScreen(onBack = ::popRoute)
                    }
                    entry<AppRoute.DataBackup>(swipeDismiss = activeSwipeDismiss) {
                        DataBackupScreen(
                            context = context,
                            onBack = ::popRoute,
                            onExport = agentState::exportBackup,
                            onImport = agentState::importBackup,
                        )
                    }
                    entry<AppRoute.Memory>(swipeDismiss = activeSwipeDismiss) {
                        LaunchedEffect(Unit) {
                            memory.refreshMemory()
                        }
                        AgentMemoryScreen(
                            state = memory.memoryState,
                            onAction = { action ->
                                when (action) {
                                    AgentMemoryAction.NavigateBack -> popRoute()
                                    is AgentMemoryAction.ToggleEnabled -> memory.setMemoryEnabled(action.enabled)
                                    is AgentMemoryAction.DraftChanged -> memory.updateMemoryDraft(action.content)
                                    AgentMemoryAction.Save -> memory.saveMemory()
                                    AgentMemoryAction.Clear -> memory.clearMemory()
                                    AgentMemoryAction.DismissNotice -> memory.dismissMemoryNotice()
                                }
                            },
                        )
                    }
                    entry<AppRoute.LinuxEnvironment>(swipeDismiss = activeSwipeDismiss) {
                        LinuxEnvironmentScreen(
                            context = context,
                            onNavigate = { route -> pushRoute(route) },
                            onBack = ::popRoute,
                        )
                    }
                    entry<AppRoute.SharedFolders>(swipeDismiss = activeSwipeDismiss) {
                        SharedFoldersScreen(
                            context = context,
                            onBack = ::popRoute,
                        )
                    }
                    entry<AppRoute.LinuxFiles>(swipeDismiss = activeSwipeDismiss) { route ->
                        LinuxFilesScreen(
                            context = context,
                            distribution = route.distribution,
                            onBack = ::popRoute,
                        )
                    }
                    entry<AppRoute.ModelProviders>(swipeDismiss = activeSwipeDismiss) {
                        ModelProviderListScreen(
                            onNavigate = { route -> pushRoute(route) },
                            onBack = ::popRoute
                        )
                    }
                    entry<AppRoute.CommunityCatalog>(swipeDismiss = activeSwipeDismiss) {
                        CommunityCatalogScreen(
                            store = communityCatalogStore,
                            onNavigate = { route -> pushRoute(route) },
                            onBack = ::popRoute,
                        )
                    }
                    entry<AppRoute.CommunityCatalogProvider>(swipeDismiss = activeSwipeDismiss) { route ->
                        CommunityCatalogProviderScreen(
                            catalogId = route.catalogId,
                            store = communityCatalogStore,
                            onImported = { providerId -> navigator.replace(AppRoute.ModelProviderDetail(providerId)) },
                            onBack = ::popRoute,
                        )
                    }
                    entry<AppRoute.McpServers>(swipeDismiss = activeSwipeDismiss) {
                        McpServersScreen(
                            onNavigate = { route -> pushRoute(route) },
                            onBack = ::popRoute,
                        )
                    }
                    entry<AppRoute.McpServerDetail>(swipeDismiss = activeSwipeDismiss) { route ->
                        McpServerDetailScreen(
                            serverId = route.serverId,
                            onBack = ::popRoute,
                        )
                    }
                    entry<AppRoute.ScheduledTasks>(swipeDismiss = activeSwipeDismiss) {
                        ScheduledTasksScreen(
                            onNavigate = { route -> pushRoute(route) },
                            onBack = ::popRoute,
                        )
                    }
                    entry<AppRoute.ScheduledTaskDetail>(swipeDismiss = activeSwipeDismiss) { route ->
                        ScheduledTaskDetailScreen(
                            taskId = route.taskId,
                            onNavigate = { target -> pushRoute(target) },
                            onOpenRun = { run, task, isLive ->
                                val archiveKey = run.archiveConversationKey?.takeIf { it.isNotBlank() }
                                    ?: return@ScheduledTaskDetailScreen
                                uiScope.launch {
                                    val opened = agentState.openScheduledTaskRun(
                                        runId = run.id,
                                        archiveConversationKey = archiveKey,
                                        taskTitle = task.name,
                                        taskPrompt = task.prompt,
                                        isRunning = isLive,
                                    )
                                    if (opened) {
                                        focusManager.clearFocus()
                                        conversationPaneOpen = false
                                        navigator.popToHome()
                                        navigationResetKey++
                                    } else {
                                        Toast.makeText(
                                            context,
                                            resources.getString(R.string.ui_misc_scheduled_run_no_conversation),
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    }
                                }
                            },
                            onBack = ::popRoute,
                        )
                    }
                    entry<AppRoute.ScheduledTaskEditor>(swipeDismiss = activeSwipeDismiss) { route ->
                        ScheduledTaskEditorScreen(
                            taskId = route.taskId,
                            presetId = route.presetId,
                            onSaved = { savedId ->
                                if (route.taskId.isNullOrBlank()) {
                                    navigator.replace(AppRoute.ScheduledTaskDetail(savedId))
                                } else {
                                    popRoute()
                                }
                            },
                            onBack = ::popRoute,
                        )
                    }
                    entry<AppRoute.ModelProviderDetail>(swipeDismiss = activeSwipeDismiss) { route ->
                        ModelProviderDetailScreen(
                            providerId = route.providerId,
                            onBack = ::popRoute
                        )
                    }
                    entry<AppRoute.ModelProviderNew>(swipeDismiss = activeSwipeDismiss) { route ->
                        ModelProviderDetailScreen(
                            newType = route.providerType,
                            onBack = ::popRoute
                        )
                    }
            }
        }
    }

    val sceneBackdrop = currentGlassOverlayBackdrop()
    var retainedNotice by remember { mutableStateOf<String?>(null) }
    characterStore.notice?.let { retainedNotice = it }
    GlassDialog(
        show = characterStore.notice != null,
        backdrop = sceneBackdrop,
        title = stringResource(R.string.conversation_dock_characters),
        summary = retainedNotice,
        onDismissRequest = characterStore::dismissNotice,
        onDismissFinished = { if (characterStore.notice == null) retainedNotice = null },
    ) {
        GlassDialogButton(
            onClick = characterStore::dismissNotice,
            role = GlassDialogButtonRole.Normal,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = stringResource(R.string.ui_misc_got_it),
                style = MiuixTheme.textStyles.button,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }

    var retainedRename by remember { mutableStateOf<ConversationSummaryUi?>(null) }
    conversationRenameTarget?.let { retainedRename = it }
    val renameInputState = remember(retainedRename?.id) {
        TextFieldState(retainedRename?.title.orEmpty())
    }
    GlassDialog(
        show = conversationRenameTarget != null,
        backdrop = sceneBackdrop,
        title = stringResource(R.string.conversation_rename_title),
        onDismissRequest = { conversationRenameTarget = null },
        onDismissFinished = { if (conversationRenameTarget == null) retainedRename = null },
    ) {
        Column {
            GlassTextField(
                state = renameInputState,
                lineLimits = TextFieldLineLimits.SingleLine,
                label = stringResource(R.string.conversation_rename_hint),
                modifier = Modifier.fillMaxWidth(),
            )
            DialogActions(
                confirmText = stringResource(R.string.action_save),
                confirmEnabled = renameInputState.text.isNotBlank() && retainedRename != null,
                onCancel = { conversationRenameTarget = null },
                onConfirm = {
                    retainedRename?.let { conversation ->
                        agentState.renameConversation(conversation.id, renameInputState.text.toString())
                    }
                    conversationRenameTarget = null
                },
                modifier = Modifier.padding(top = 16.dp),
            )
        }
    }

    var retainedDelete by remember { mutableStateOf<ConversationSummaryUi?>(null) }
    conversationDeleteTarget?.let { retainedDelete = it }
    val deleteMessage = stringResource(
        if (retainedDelete?.isActiveRun == true) {
            R.string.conversation_stop_and_delete_message
        } else {
            R.string.conversation_delete_message
        },
    )
    val deleteConfirmText = stringResource(
        if (retainedDelete?.isActiveRun == true) {
            R.string.conversation_stop_and_delete
        } else {
            R.string.action_delete
        },
    )
    GlassDialog(
        show = conversationDeleteTarget != null,
        backdrop = sceneBackdrop,
        title = stringResource(R.string.conversation_delete_title),
        summary = deleteMessage,
        onDismissRequest = { conversationDeleteTarget = null },
        onDismissFinished = { if (conversationDeleteTarget == null) retainedDelete = null },
    ) {
        DialogActions(
            confirmText = deleteConfirmText,
            destructive = true,
            onCancel = { conversationDeleteTarget = null },
            onConfirm = {
                retainedDelete?.let { conversation ->
                    if ((backStack.lastOrNull() as? AppRoute.Browser)?.sessionKey == conversation.id) {
                        navigator.popToHome()
                    }
                    agentState.deleteConversation(conversation.id)
                }
                conversationDeleteTarget = null
            },
        )
    }

    var retainedDeleteMessage by remember { mutableStateOf<MessageMutationTarget?>(null) }
    messageDeleteTarget?.let { retainedDeleteMessage = it }
    val deleteMessageSummary = retainedDeleteMessage?.let { target ->
        if (target.laterTurnCount == 0) {
            stringResource(R.string.conversation_delete_message_body)
        } else {
            pluralStringResource(
                R.plurals.conversation_delete_later_turns,
                target.laterTurnCount,
                target.laterTurnCount,
            )
        }
    }
    GlassDialog(
        show = messageDeleteTarget != null,
        backdrop = sceneBackdrop,
        title = stringResource(R.string.conversation_delete_message_title),
        summary = deleteMessageSummary,
        onDismissRequest = { messageDeleteTarget = null },
        onDismissFinished = { if (messageDeleteTarget == null) retainedDeleteMessage = null },
    ) {
        DialogActions(
            confirmText = stringResource(R.string.action_delete),
            destructive = true,
            onCancel = { messageDeleteTarget = null },
            onConfirm = {
                retainedDeleteMessage?.let { target ->
                    agentState.deleteMessageTurn(target.messageId)
                }
                messageDeleteTarget = null
            },
        )
    }

    var retainedRegenerate by remember { mutableStateOf<MessageMutationTarget?>(null) }
    messageRegenerateTarget?.let { retainedRegenerate = it }
    val regenerateSummary = retainedRegenerate?.let { target ->
        if (target.laterTurnCount == 0) {
            stringResource(R.string.conversation_regenerate_current_turn)
        } else {
            pluralStringResource(
                R.plurals.conversation_regenerate_later_turns,
                target.laterTurnCount,
                target.laterTurnCount,
            )
        }
    }
    GlassDialog(
        show = messageRegenerateTarget != null,
        backdrop = sceneBackdrop,
        title = stringResource(R.string.conversation_regenerate_title),
        summary = regenerateSummary,
        onDismissRequest = { messageRegenerateTarget = null },
        onDismissFinished = { if (messageRegenerateTarget == null) retainedRegenerate = null },
    ) {
        DialogActions(
            confirmText = stringResource(R.string.action_regenerate),
            destructive = true,
            onCancel = { messageRegenerateTarget = null },
            onConfirm = {
                retainedRegenerate?.let { target ->
                    agentState.regenerateMessage(target.messageId)
                }
                messageRegenerateTarget = null
            },
        )
    }
}

private val CorePermissionAttentionIds = setOf(
    "background",
    "overlay",
    "accessibility",
    "notifications",
)

private data class MessageMutationTarget(
    val messageId: String,
    val laterTurnCount: Int,
)
