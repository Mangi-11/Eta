package fuck.andes.ui.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.navigation3.runtime.NavKey
import fuck.andes.FuckAndesApp
import fuck.andes.data.repository.RuntimeConfigRepository
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberDecoratedNavEntries
import androidx.navigation3.ui.NavDisplay
import fuck.andes.ui.SettingsScreen
import fuck.andes.ui.pages.providers.ModelProviderDetailScreen
import fuck.andes.ui.pages.providers.ModelProviderListScreen
import fuck.andes.ui.model.AgentChatAction
import fuck.andes.ui.model.AgentHomeAction
import fuck.andes.ui.model.AgentSkillsAction
import fuck.andes.ui.model.AgentSystemEnhanceAction
import fuck.andes.ui.model.AgentToolsAction
import fuck.andes.ui.model.PermissionHealthAction
import fuck.andes.ui.navigation.AgentNavigator
import fuck.andes.ui.navigation.AppRoute
import fuck.andes.ui.screens.chat.AgentChatScreen
import fuck.andes.ui.screens.enhance.SystemEnhanceScreen
import fuck.andes.ui.screens.home.AgentHomeScreen
import fuck.andes.ui.screens.permissions.PermissionHealthScreen
import fuck.andes.ui.screens.skills.AgentSkillsScreen
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
    val focusManager = LocalFocusManager.current

    LaunchedEffect(Unit) {
        RuntimeConfigRepository.ensureDefaults(FuckAndesApp.serviceInstance)
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
        conversationPaneOpen = false
    }

    fun createConversation() {
        focusManager.clearFocus()
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
            onOpenSkills = { pushRoute(AppRoute.Skills) },
            onOpenPermissions = { pushRoute(AppRoute.Permissions) },
            onOpenSettings = { pushRoute(AppRoute.Settings) },
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
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
                                is AgentHomeAction.ThinkingToggled -> agentState.updateThinkingEnabled(action.enabled)
                                AgentHomeAction.SendMessage -> agentState.sendCurrentMessage()
                                AgentHomeAction.StopRun -> agentState.stopCurrentRun()
                                is AgentHomeAction.ImageAttached -> agentState.attachImage(action.uri)
                                is AgentHomeAction.RemoveImage -> agentState.removePendingImage(action.id)
                                AgentHomeAction.OpenTools -> pushRoute(AppRoute.Tools)
                                AgentHomeAction.OpenSkills -> pushRoute(AppRoute.Skills)
                                AgentHomeAction.OpenPermissions -> pushRoute(AppRoute.Permissions)
                                AgentHomeAction.OpenSystemEnhance -> pushRoute(AppRoute.SystemEnhance)
                                AgentHomeAction.OpenSettings -> pushRoute(AppRoute.Settings)
                                AgentHomeAction.ExpandRunTrace -> Unit
                            }
                        },
                        isDrawerOpen = conversationPaneOpen,
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
                                is AgentChatAction.ThinkingToggled -> agentState.updateThinkingEnabled(action.enabled)
                                AgentChatAction.SendMessage -> agentState.sendCurrentMessage()
                                AgentChatAction.StopRun -> agentState.stopCurrentRun()
                                is AgentChatAction.ImageAttached -> agentState.attachImage(action.uri)
                                is AgentChatAction.RemoveImage -> agentState.removePendingImage(action.id)
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
            entry<AppRoute.Skills> {
                RoutedShell(route = AppRoute.Skills) {
                    LaunchedEffect(Unit) {
                        agentState.refreshSkills()
                    }
                    AgentSkillsScreen(
                        state = agentState.skillsState,
                        onAction = { action ->
                            when (action) {
                                AgentSkillsAction.NavigateBack -> popRoute()
                                is AgentSkillsAction.ToggleSkill -> agentState.toggleSkill(action.skillId, action.enabled)
                                is AgentSkillsAction.DeleteSkill -> agentState.deleteSkill(action.skillId)
                                is AgentSkillsAction.ReinstallBuiltin -> agentState.reinstallBuiltin(action.skillId)
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
                                is PermissionHealthAction.OpenItemAction -> {
                                    when (action.itemId) {
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
                                            runCatching {
                                                context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
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
                                        "root" -> {
                                            coroutineScope.launch {
                                                try {
                                                    val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
                                                    process.waitFor()
                                                } catch (e: Exception) {
                                                    // no-op
                                                }
                                                agentState.refreshPermissionHealth()
                                            }
                                        }
                                    }
                                }
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
                SettingsScreen(
                    context = context,
                    onNavigate = { route -> pushRoute(route) }
                )
            }
            entry<AppRoute.ModelProviders> {
                RoutedShell(route = AppRoute.ModelProviders) {
                    ModelProviderListScreen(
                        onNavigate = { route -> pushRoute(route) },
                        onBack = ::popRoute
                    )
                }
            }
            entry<AppRoute.ModelProviderDetail> { route ->
                RoutedShell(route = route) {
                    ModelProviderDetailScreen(
                        providerId = route.providerId,
                        onBack = ::popRoute
                    )
                }
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
