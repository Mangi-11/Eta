package fuck.andes.ui.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberDecoratedNavEntries
import androidx.navigation3.ui.NavDisplay
import fuck.andes.ui.SettingsScreen
import fuck.andes.ui.model.AgentChatAction
import fuck.andes.ui.model.AgentHomeAction
import fuck.andes.ui.model.AgentRunDetailAction
import fuck.andes.ui.model.AgentRunsAction
import fuck.andes.ui.model.AgentSystemEnhanceAction
import fuck.andes.ui.model.AgentToolsAction
import fuck.andes.ui.model.PermissionHealthAction
import fuck.andes.ui.navigation.AgentNavigator
import fuck.andes.ui.navigation.AppRoute
import fuck.andes.ui.screens.chat.AgentChatScreen
import fuck.andes.ui.screens.enhance.SystemEnhanceScreen
import fuck.andes.ui.screens.home.AgentHomeScreen
import fuck.andes.ui.screens.permissions.PermissionHealthScreen
import fuck.andes.ui.screens.rundetail.AgentRunDetailScreen
import fuck.andes.ui.screens.runs.AgentRunsScreen
import fuck.andes.ui.screens.tools.AgentToolsScreen

/**
 * Agent App 根组件：持有本地导航栈，并把 Screen actions 交给 [AgentAppState]。
 */
@Composable
fun AgentAppRoot() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val backStack = remember { mutableStateListOf<NavKey>(AppRoute.Home) }
    val navigator = remember { AgentNavigator(backStack) }
    val agentState = remember(context.applicationContext) {
        AgentAppState(
            context = context.applicationContext,
            scope = coroutineScope,
        )
    }

    var conversationPaneOpen by remember { mutableStateOf(false) }

    fun pushRoute(
        route: AppRoute,
        restoreConversationPaneOnBack: Boolean = conversationPaneOpen,
    ) {
        conversationPaneOpen = restoreConversationPaneOnBack
        navigator.push(route)
    }

    fun popRoute() {
        navigator.pop()
    }

    fun selectConversation(conversationId: String) {
        agentState.selectConversation(conversationId)
        conversationPaneOpen = false
    }

    fun createConversation() {
        agentState.createConversation()
        conversationPaneOpen = false
    }

    @Composable
    fun RoutedShell(
        route: AppRoute,
        content: @Composable () -> Unit,
    ) {
        AgentAppShell(
            currentRoute = route,
            conversationPaneState = agentState.conversationPaneState,
            isConversationPaneOpen = conversationPaneOpen,
            onBack = { popRoute() },
            onOpenConversationPane = { conversationPaneOpen = true },
            onDismissConversationPane = { conversationPaneOpen = false },
            onSearchConversations = { query -> agentState.updateSearchQuery(query) },
            onNewConversation = { createConversation() },
            onSelectConversation = { conversationId -> selectConversation(conversationId) },
            onOpenTools = { pushRoute(AppRoute.Tools) },
            onOpenRuns = { pushRoute(AppRoute.Runs) },
            onOpenPermissions = { pushRoute(AppRoute.Permissions) },
            onOpenSettings = { pushRoute(AppRoute.Settings) },
        ) { padding ->
            Box(modifier = Modifier.padding(padding)) {
                content()
            }
        }
    }

    val entryProvider = remember(backStack) {
        entryProvider<NavKey> {
            entry<AppRoute.Home> {
                RoutedShell(route = AppRoute.Home) {
                    AgentHomeScreen(
                        state = agentState.homeState,
                        onAction = { action ->
                            when (action) {
                                is AgentHomeAction.InputChanged -> agentState.updateInput(action.text)
                                AgentHomeAction.SendMessage -> agentState.sendCurrentMessage()
                                AgentHomeAction.AttachScreenContext -> Unit
                                AgentHomeAction.OpenAttachment -> Unit
                                AgentHomeAction.StartVoice -> Unit
                                AgentHomeAction.OpenTools -> pushRoute(AppRoute.Tools)
                                AgentHomeAction.OpenRuns -> pushRoute(AppRoute.Runs)
                                AgentHomeAction.OpenPermissions -> pushRoute(AppRoute.Permissions)
                                AgentHomeAction.OpenSystemEnhance -> pushRoute(AppRoute.SystemEnhance)
                                AgentHomeAction.OpenSettings -> pushRoute(AppRoute.Settings)
                                AgentHomeAction.ExpandRunTrace -> Unit
                            }
                        },
                    )
                }
            }
            entry<AppRoute.Chat> {
                RoutedShell(route = AppRoute.Chat) {
                    AgentChatScreen(
                        state = agentState.homeState,
                        onAction = { action ->
                            when (action) {
                                AgentChatAction.NavigateBack -> popRoute()
                                is AgentChatAction.InputChanged -> agentState.updateInput(action.text)
                                AgentChatAction.SendMessage -> agentState.sendCurrentMessage()
                                AgentChatAction.AttachScreenContext -> Unit
                            }
                        },
                    )
                }
            }
            entry<AppRoute.Runs> {
                RoutedShell(route = AppRoute.Runs) {
                    AgentRunsScreen(
                        state = agentState.runsState,
                        onAction = { action ->
                            when (action) {
                                AgentRunsAction.NavigateBack -> popRoute()
                                is AgentRunsAction.OpenRun -> pushRoute(AppRoute.RunDetail(action.runId))
                            }
                        },
                    )
                }
            }
            entry<AppRoute.RunDetail> { route ->
                RoutedShell(route = route) {
                    AgentRunDetailScreen(
                        state = agentState.detailState(route.runId),
                        onAction = { action ->
                            when (action) {
                                AgentRunDetailAction.NavigateBack -> popRoute()
                                is AgentRunDetailAction.RetryRun -> Unit
                            }
                        },
                    )
                }
            }
            entry<AppRoute.Tools> {
                RoutedShell(route = AppRoute.Tools) {
                    AgentToolsScreen(
                        state = agentState.toolsState,
                        onAction = { action ->
                            when (action) {
                                AgentToolsAction.NavigateBack -> popRoute()
                            }
                        },
                    )
                }
            }
            entry<AppRoute.Permissions> {
                RoutedShell(route = AppRoute.Permissions) {
                    LaunchedEffect(Unit) {
                        agentState.refreshPermissionHealth()
                    }
                    PermissionHealthScreen(
                        state = agentState.permissionHealthState,
                        onAction = { action ->
                            when (action) {
                                PermissionHealthAction.NavigateBack -> popRoute()
                                is PermissionHealthAction.OpenItemAction -> Unit
                            }
                        },
                    )
                }
            }
            entry<AppRoute.SystemEnhance> {
                RoutedShell(route = AppRoute.SystemEnhance) {
                    SystemEnhanceScreen(
                        state = agentState.systemEnhanceState,
                        onAction = { action ->
                            when (action) {
                                AgentSystemEnhanceAction.NavigateBack -> popRoute()
                                is AgentSystemEnhanceAction.ToggleItem -> Unit
                            }
                        },
                    )
                }
            }
            entry<AppRoute.Settings> {
                SettingsScreen(context = context)
            }
        }
    }
    val entries = rememberDecoratedNavEntries(
        backStack = backStack,
        entryProvider = entryProvider,
    )

    NavDisplay(
        entries = entries,
        onBack = { popRoute() },
    )
}
