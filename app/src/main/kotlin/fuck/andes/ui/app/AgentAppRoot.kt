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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import fuck.andes.ui.model.ConversationSummaryUi
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog
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
import fuck.andes.ui.screens.browser.AgentBrowserScreen
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
    var conversationRenameTarget by remember { mutableStateOf<ConversationSummaryUi?>(null) }
    var conversationDeleteTarget by remember { mutableStateOf<ConversationSummaryUi?>(null) }
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
            onConversationRename = { conversation ->
                conversationRenameTarget = conversation
            },
            onConversationDelete = { conversation ->
                conversationDeleteTarget = conversation
            },
            onOpenTools = { pushRoute(AppRoute.Tools) },
            onOpenSkills = { pushRoute(AppRoute.Skills) },
            onOpenPermissions = { pushRoute(AppRoute.Permissions) },
            onOpenSettings = { pushRoute(AppRoute.Settings) },
            onOpenModelProviders = { pushRoute(AppRoute.ModelProviders) },
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
                                AgentHomeAction.OpenBrowser -> pushRoute(AppRoute.Browser)
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
                                AgentChatAction.OpenBrowser -> pushRoute(AppRoute.Browser)
                                is AgentChatAction.ImageAttached -> agentState.attachImage(action.uri)
                                is AgentChatAction.RemoveImage -> agentState.removePendingImage(action.id)
                            }
                        },
                    )
                }
            }
            entry<AppRoute.Browser> {
                RoutedShell(route = AppRoute.Browser) {
                    AgentBrowserScreen()
                }
            }
            entry<AppRoute.Tools> {
                AgentToolsScreen(
                    state = agentState.toolsState,
                    onAction = { action ->
                        when (action) {
                            AgentToolsAction.NavigateBack -> popRoute()
                            AgentToolsAction.OpenBrowser -> pushRoute(AppRoute.Browser)
                        }
                    },
                )
            }
            entry<AppRoute.Skills> {
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
            entry<AppRoute.Permissions> {
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
            entry<AppRoute.SystemEnhance> {
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
            entry<AppRoute.Settings> {
                SettingsScreen(
                    context = context,
                    onNavigate = { route -> pushRoute(route) },
                    onBack = ::popRoute
                )
            }
            entry<AppRoute.ModelProviders> {
                ModelProviderListScreen(
                    onNavigate = { route -> pushRoute(route) },
                    onBack = ::popRoute
                )
            }
            entry<AppRoute.ModelProviderDetail> { route ->
                ModelProviderDetailScreen(
                    providerId = route.providerId,
                    onBack = ::popRoute
                )
            }
            entry<AppRoute.ModelProviderNew> { route ->
                ModelProviderDetailScreen(
                    newType = route.type,
                    onBack = ::popRoute
                )
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

    conversationRenameTarget?.let { conversation ->
        var renameInput by remember(conversation.id) { mutableStateOf(conversation.title) }
        WindowDialog(
            show = true,
            title = "重命名对话",
            onDismissRequest = { conversationRenameTarget = null },
        ) {
            Column {
                TextField(
                    value = renameInput,
                    onValueChange = { renameInput = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(text = "取消", onClick = { conversationRenameTarget = null })
                    Spacer(modifier = Modifier.width(8.dp))
                    TextButton(
                        text = "确定",
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                        onClick = {
                            agentState.renameConversation(conversation.id, renameInput)
                            conversationRenameTarget = null
                        },
                    )
                }
            }
        }
    }

    conversationDeleteTarget?.let { conversation ->
        WindowDialog(
            show = true,
            title = "删除后，该对话将不可恢复",
            onDismissRequest = { conversationDeleteTarget = null },
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = {
                        agentState.deleteConversation(conversation.id)
                        conversationDeleteTarget = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColorsPrimary(
                        color = MiuixTheme.colorScheme.error,
                        contentColor = MiuixTheme.colorScheme.onError,
                    ),
                ) {
                    Text("删除该对话")
                }
                TextButton(
                    text = "取消",
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { conversationDeleteTarget = null },
                )
            }
        }
    }
}
