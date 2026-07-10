package fuck.andes.ui.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import com.composables.icons.lucide.R as LucideR
import fuck.andes.ui.components.ConversationSidePaneScaffold
import fuck.andes.ui.navigation.AppRoute
import fuck.andes.ui.model.ConversationPaneUiState
import fuck.andes.ui.model.ConversationSummaryUi
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Agent App 统一壳层。
 *
 * - 负责全局 Scaffold、状态栏/横向安全边距、顶层工具栏。
 * - 首页工具栏只保留历史入口与新建对话，保持聊天舞台干净。
 * - 非首页子路由统一提供返回按钮与标题，避免每个页面各自像独立设置页。
 * - Settings 保留旧 SettingsScreen 自己的 TopAppBar，壳层在此路由不显示顶部工具栏。
 */
@Composable
fun AgentAppShell(
    currentRoute: AppRoute?,
    conversationPaneState: ConversationPaneUiState?,
    isConversationPaneOpen: Boolean,
    onBack: () -> Unit,
    onOpenConversationPane: () -> Unit,
    onDismissConversationPane: () -> Unit,
    onSearchConversations: (String) -> Unit,
    onNewConversation: () -> Unit,
    onSelectConversation: (String) -> Unit,
    onConversationRename: (ConversationSummaryUi) -> Unit,
    onConversationDelete: (ConversationSummaryUi) -> Unit,
    onOpenTools: () -> Unit,
    onOpenSkills: () -> Unit,
    onOpenPermissions: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenModelProviders: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (PaddingValues) -> Unit,
) {
    val pageContent: @Composable () -> Unit = {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            contentWindowInsets = WindowInsets.safeDrawing.only(
                WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
            ),
            topBar = {
                if (currentRoute !is AppRoute.Settings) {
                    AgentTopBar(
                        route = currentRoute,
                        onBack = onBack,
                        onOpenConversationPane = onOpenConversationPane,
                        onNewConversation = onNewConversation,
                    )
                }
            },
        ) { padding ->
            content(padding)
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (conversationPaneState != null && currentRoute is AppRoute.Home) {
            ConversationSidePaneScaffold(
                state = conversationPaneState,
                visible = isConversationPaneOpen,
                onOpen = onOpenConversationPane,
                onDismiss = onDismissConversationPane,
                onSearchChange = onSearchConversations,
                onConversationSelected = onSelectConversation,
                onConversationRename = onConversationRename,
                onConversationDelete = onConversationDelete,
                onOpenSettings = onOpenSettings,
                onOpenModelProviders = onOpenModelProviders,
                onOpenTools = onOpenTools,
                onOpenSkills = onOpenSkills,
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
private fun AgentTopBar(
    route: AppRoute?,
    onBack: () -> Unit,
    onOpenConversationPane: () -> Unit,
    onNewConversation: () -> Unit,
) {
    val isHome = route is AppRoute.Home
    SmallTopAppBar(
        title = titleForRoute(route),
        color = if (route is AppRoute.Tools) Color.Transparent else MiuixTheme.colorScheme.surface,
        navigationIcon = {
            if (isHome) {
                IconButton(onClick = onOpenConversationPane) {
                    Icon(
                        painter = painterResource(LucideR.drawable.lucide_ic_menu),
                        contentDescription = "会话历史",
                    )
                }
            } else {
                IconButton(onClick = onBack) {
                    Icon(
                        painter = painterResource(LucideR.drawable.lucide_ic_chevron_left),
                        contentDescription = "返回",
                    )
                }
            }
        },
        actions = {
            if (isHome) {
                IconButton(onClick = onNewConversation) {
                    Icon(
                        painter = painterResource(LucideR.drawable.lucide_ic_message_circle_plus),
                        contentDescription = "新建对话",
                    )
                }
            }
        },
    )
}

@Composable
private fun titleForRoute(route: AppRoute?): String = when (route) {
    is AppRoute.Home -> ""
    is AppRoute.Chat -> "对话"
    is AppRoute.Browser -> "Agent 浏览器"
    is AppRoute.Tools -> "工具能力"
    is AppRoute.Skills -> "技能"
    is AppRoute.Permissions -> "权限健康"
    is AppRoute.SystemEnhance -> "系统增强"
    is AppRoute.Settings -> "设置"
    is AppRoute.ModelProviders -> "模型提供商"
    is AppRoute.ModelProviderDetail -> route.providerId.let { "Provider 详情" }
    is AppRoute.ModelProviderNew -> "新建提供商"
    null -> "Eta"
}
