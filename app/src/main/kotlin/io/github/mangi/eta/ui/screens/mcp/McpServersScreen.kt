package io.github.mangi.eta.ui.screens.mcp

import android.widget.Toast
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.mcp.McpServerManager
import io.github.mangi.eta.agent.mcp.validateMcpEndpoint
import io.github.mangi.eta.data.model.McpAuthorizationType
import io.github.mangi.eta.data.model.McpDiscoveryTruncation
import io.github.mangi.eta.data.model.McpProtocolMode
import io.github.mangi.eta.data.model.McpServerSetting
import io.github.mangi.eta.data.model.McpToolDefinition
import io.github.mangi.eta.data.repository.McpServerRepository
import io.github.mangi.eta.ui.components.DialogActions
import io.github.mangi.eta.ui.components.ListEmptyState
import io.github.mangi.eta.ui.components.PreferenceGroupItem
import io.github.mangi.eta.ui.components.ScaffoldPage
import io.github.mangi.eta.ui.components.SecureTextField
import io.github.mangi.eta.ui.components.TopBarActionButton
import io.github.mangi.eta.ui.components.currentGlassOverlayBackdrop
import io.github.mangi.eta.ui.components.glassOverlayTextFieldColors
import io.github.mangi.eta.ui.markdown.MarkdownTone
import io.github.mangi.eta.ui.markdown.StaticMarkdown
import io.github.mangi.eta.ui.navigation.AppRoute
import io.github.mangi.eta.ui.screens.tools.GroupBatchTogglePill
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.glass.GlassDialog
import top.yukonga.miuix.kmp.glass.GlassTextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.Add
import top.yukonga.miuix.kmp.icon.glass.Refresh
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun McpServersScreen(
    onNavigate: (AppRoute) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val servers by McpServerRepository.serversFlow().collectAsStateWithLifecycle(initialValue = emptyList())
    var showAdd by remember { mutableStateOf(false) }
    val nameState = remember { TextFieldState() }
    val urlState = remember { TextFieldState() }
    val tokenState = remember { TextFieldState() }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val totalTools = servers.sumOf { it.tools.size }
    val totalEnabledTools = servers.sumOf { server ->
        server.tools.count { it.name in server.enabledToolNames }
    }
    val allServersToolsEnabled = totalTools > 0 && totalEnabledTools == totalTools
    val onToggleAllServersTools: () -> Unit = {
        val target = !allServersToolsEnabled
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    McpServerRepository.setAllServersToolsEnabled(target)
                }
            }.onFailure { failure ->
                Toast.makeText(
                    context,
                    failure.message ?: resources.getString(R.string.settings_write_failed),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    val onConfirmAdd: () -> Unit = {
        val normalizedName = nameState.text.toString().trim()
        val normalizedUrl = urlState.text.toString().trim()
        val token = tokenState.text.toString()
        error = when {
            normalizedName.isBlank() -> resources.getString(R.string.mcp_name_required)
            else -> runCatching { validateMcpEndpoint(normalizedUrl) }
                .exceptionOrNull()?.message
        }
        if (error == null) {
            scope.launch {
                working = true
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val draft = McpServerSetting(
                            id = "",
                            name = normalizedName,
                            url = normalizedUrl,
                            protocolMode = McpProtocolMode.AUTO,
                            authorizationType = if (token.isBlank()) {
                                McpAuthorizationType.NONE
                            } else {
                                McpAuthorizationType.BEARER
                            },
                        )
                        val discovered = McpServerManager.discover(draft, token)
                        McpServerRepository.add(discovered, token)
                    }
                }
                working = false
                result.onSuccess {
                    showAdd = false
                    nameState.clearText()
                    urlState.clearText()
                    tokenState.clearText()
                    error = null
                    val messageRes = if (it.toolsTruncationReason != null) {
                        R.string.mcp_server_added_truncated
                    } else {
                        R.string.mcp_server_added
                    }
                    Toast.makeText(
                        context,
                        resources.getString(messageRes, it.tools.size),
                        Toast.LENGTH_SHORT,
                    ).show()
                }.onFailure {
                    error = it.message ?: resources.getString(R.string.mcp_connection_failed)
                }
            }
        }
    }

    ScaffoldPage(
        title = stringResource(R.string.route_mcp_servers),
        onBack = onBack,
        actions = {
            TopBarActionButton(
                icon = MiuixIcons.Glass.Add,
                contentDescription = stringResource(R.string.mcp_add_server),
                onClick = { showAdd = true },
            )
        },
        overlay = {
            GlassDialog(
                show = showAdd,
                backdrop = currentGlassOverlayBackdrop(),
                title = stringResource(R.string.mcp_add_server),
                onDismissRequest = { if (!working) showAdd = false },
            ) {
                Column(modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        GlassTextField(
                            state = nameState,
                            lineLimits = TextFieldLineLimits.SingleLine,
                            label = stringResource(R.string.mcp_server_name),
                            enabled = !working,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        GlassTextField(
                            state = urlState,
                            lineLimits = TextFieldLineLimits.SingleLine,
                            label = stringResource(R.string.mcp_server_url),
                            enabled = !working,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        SecureTextField(
                            state = tokenState,
                            label = stringResource(R.string.mcp_bearer_optional),
                            colors = glassOverlayTextFieldColors(),
                            enabled = !working,
                            visible = false,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        error?.let {
                            Text(
                                text = it,
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.error,
                            )
                        }
                    }
                    DialogActions(
                        modifier = Modifier.padding(top = 16.dp),
                        confirmText = if (working) {
                            stringResource(R.string.mcp_connecting)
                        } else {
                            stringResource(R.string.mcp_add_server)
                        },
                        confirmEnabled = !working,
                        onCancel = { showAdd = false },
                        onConfirm = onConfirmAdd,
                    )
                }
            }
        },
    ) {
        item(key = "servers") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(end = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    SmallTitle(stringResource(R.string.mcp_configured_servers, servers.size))
                }
                if (totalTools > 0) {
                    GroupBatchTogglePill(
                        allEnabled = allServersToolsEnabled,
                        onToggleAll = onToggleAllServersTools,
                    )
                }
            }
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                if (servers.isEmpty()) {
                    ListEmptyState(
                        title = stringResource(R.string.mcp_empty_title),
                        summary = stringResource(R.string.mcp_empty_summary),
                        icon = Icons.Rounded.Hub,
                        action = {
                            TextButton(
                                text = stringResource(R.string.mcp_add_server),
                                onClick = { showAdd = true },
                            )
                        },
                    )
                } else {
                    servers.forEach { server ->
                        val summaryRes = if (server.toolsTruncationReason != null) {
                            R.string.mcp_server_row_summary_truncated
                        } else {
                            R.string.mcp_server_row_summary
                        }
                        ArrowPreference(
                            title = server.name,
                            summary = stringResource(
                                summaryRes,
                                server.activeTools.size,
                                server.tools.size,
                            ),
                            onClick = { onNavigate(AppRoute.McpServerDetail(server.id)) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun McpServerDetailScreen(
    serverId: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val servers by McpServerRepository.serversFlow().collectAsStateWithLifecycle(initialValue = emptyList())
    val server = servers.firstOrNull { it.id == serverId }
    var working by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var showToken by remember { mutableStateOf(false) }
    val tokenState = remember { TextFieldState() }

    var optimisticServerEnabled by remember(server?.id, server?.enabled) {
        mutableStateOf(server?.enabled ?: true)
    }
    var optimisticEnabledTools by remember(server?.id, server?.enabledToolNames) {
        mutableStateOf(server?.enabledToolNames.orEmpty())
    }

    val onToggleServerEnabled: (Boolean) -> Unit = { enabled ->
        val current = server
        if (current != null) {
            val previous = optimisticServerEnabled
            optimisticServerEnabled = enabled
            scope.launch {
                runCatching {
                    withContext(Dispatchers.IO) {
                        McpServerRepository.update(current.copy(enabled = enabled))
                    }
                }.onFailure { failure ->
                    optimisticServerEnabled = previous
                    Toast.makeText(
                        context,
                        failure.message ?: resources.getString(R.string.settings_write_failed),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }
    }

    val onToggleTool: (McpToolDefinition, Boolean) -> Unit = { tool, enabled ->
        val previous = optimisticEnabledTools
        optimisticEnabledTools = previous.toMutableSet().apply {
            if (enabled) add(tool.name) else remove(tool.name)
        }
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    McpServerRepository.setToolEnabled(serverId, tool.name, enabled)
                }
            }.onFailure { failure ->
                optimisticEnabledTools = previous
                Toast.makeText(
                    context,
                    failure.message ?: resources.getString(R.string.settings_write_failed),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    val onToggleAllTools: (Boolean) -> Unit = { enabled ->
        val current = server
        if (current != null) {
            val previous = optimisticEnabledTools
            optimisticEnabledTools = if (enabled) {
                current.tools.mapTo(linkedSetOf()) { it.name }
            } else {
                emptySet()
            }
            scope.launch {
                runCatching {
                    withContext(Dispatchers.IO) {
                        McpServerRepository.setAllToolsEnabled(serverId, enabled)
                    }
                }.onFailure { failure ->
                    optimisticEnabledTools = previous
                    Toast.makeText(
                        context,
                        failure.message ?: resources.getString(R.string.settings_write_failed),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }
    }

    val onRefresh: () -> Unit = {
        scope.launch {
            working = true
            val result = withContext(Dispatchers.IO) {
                runCatching { McpServerManager.refresh(serverId) }
            }
            working = false
            Toast.makeText(
                context,
                result.fold(
                    onSuccess = {
                        val messageRes = if (it.toolsTruncationReason != null) {
                            R.string.mcp_refreshed_truncated
                        } else {
                            R.string.mcp_refreshed
                        }
                        resources.getString(messageRes, it.tools.size)
                    },
                    onFailure = { it.message ?: resources.getString(R.string.mcp_refresh_failed) },
                ),
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    val saveToken: () -> Unit = {
        server?.let { current ->
            val token = tokenState.text.toString()
            scope.launch(Dispatchers.IO) {
                McpServerRepository.update(
                    current.copy(
                        authorizationType = if (token.isBlank()) {
                            McpAuthorizationType.NONE
                        } else {
                            McpAuthorizationType.BEARER
                        },
                    ),
                    bearerToken = token,
                )
            }
            tokenState.clearText()
            showToken = false
        }
    }

    ScaffoldPage(
        title = server?.name ?: stringResource(R.string.route_mcp_server_detail),
        onBack = onBack,
        actions = {
            TopBarActionButton(
                icon = MiuixIcons.Glass.Refresh,
                contentDescription = stringResource(R.string.mcp_refresh_tools),
                enabled = server != null && !working,
                onClick = onRefresh,
            )
        },
        overlay = {
            val overlayBackdrop = currentGlassOverlayBackdrop()
            GlassDialog(
                show = showToken,
                backdrop = overlayBackdrop,
                title = stringResource(R.string.mcp_update_token),
                summary = stringResource(R.string.mcp_update_token_summary),
                onDismissRequest = { showToken = false },
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    SecureTextField(
                        state = tokenState,
                        label = stringResource(R.string.mcp_bearer_token),
                        colors = glassOverlayTextFieldColors(),
                        visible = false,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    DialogActions(
                        confirmText = stringResource(R.string.action_save),
                        onCancel = { showToken = false },
                        onConfirm = saveToken,
                    )
                }
            }

            GlassDialog(
                show = showDelete,
                backdrop = overlayBackdrop,
                title = stringResource(R.string.mcp_delete_server),
                summary = stringResource(R.string.mcp_delete_confirm, server?.name.orEmpty()),
                onDismissRequest = { showDelete = false },
            ) {
                DialogActions(
                    confirmText = stringResource(R.string.ui_delete_3755f5),
                    destructive = true,
                    onCancel = { showDelete = false },
                    onConfirm = {
                        scope.launch {
                            withContext(Dispatchers.IO) { McpServerRepository.delete(serverId) }
                            onBack()
                        }
                    },
                )
            }
        },
    ) {
        if (server == null) {
            item(key = "missing") {
                ListEmptyState(title = stringResource(R.string.mcp_server_missing))
            }
            return@ScaffoldPage
        }
        item(key = "server") {
            SmallTitle(stringResource(R.string.mcp_server_settings))
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                SwitchPreference(
                    title = stringResource(R.string.mcp_enable_server),
                    summary = server.url,
                    checked = optimisticServerEnabled,
                    onCheckedChange = onToggleServerEnabled,
                )
                ArrowPreference(
                    title = stringResource(R.string.mcp_update_token),
                    summary = stringResource(
                        if (server.authorizationType == McpAuthorizationType.BEARER) {
                            R.string.mcp_token_configured
                        } else {
                            R.string.mcp_no_authentication
                        },
                    ),
                    onClick = { showToken = true },
                )
            }
        }

        val enabledCount = server.tools.count { it.name in optimisticEnabledTools }
        val allEnabled = server.tools.isNotEmpty() && enabledCount == server.tools.size
        item(key = "tools-header") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(end = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    SmallTitle(
                        stringResource(
                            R.string.ui_misc_mcp_tools_enabled_header,
                            stringResource(R.string.mcp_tools_count, server.tools.size),
                            enabledCount,
                        ),
                    )
                }
                if (server.tools.isNotEmpty()) {
                    GroupBatchTogglePill(
                        allEnabled = allEnabled,
                        onToggleAll = { onToggleAllTools(!allEnabled) },
                    )
                }
            }
        }

        server.toolsTruncationReason?.let { reason ->
            item(key = "tools-truncated") {
                Card(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp),
                ) {
                    BasicComponent(
                        title = stringResource(R.string.mcp_tools_truncated_title, server.tools.size),
                        titleColor = BasicComponentDefaults.titleColor(color = MiuixTheme.colorScheme.error),
                        summary = stringResource(reason.summaryRes()),
                    )
                }
            }
        }

        if (server.tools.isEmpty()) {
            item(key = "tools-empty") {
                Card(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp),
                ) {
                    ListEmptyState(
                        title = stringResource(R.string.mcp_no_tools),
                        summary = stringResource(R.string.mcp_refresh_tools_hint),
                        icon = Icons.Rounded.Refresh,
                    )
                }
            }
        } else {
            itemsIndexed(
                items = server.tools,
                key = { _, tool -> "mcp-tool-${tool.name}" },
            ) { index, tool ->
                PreferenceGroupItem(
                    isFirst = index == 0,
                    isLast = index == server.tools.lastIndex,
                ) {
                    McpToolSwitchRow(
                        tool = tool,
                        checked = tool.name in optimisticEnabledTools,
                        onCheckedChange = { enabled -> onToggleTool(tool, enabled) },
                    )
                }
            }
        }

        item(key = "delete") {
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(top = 4.dp, bottom = 12.dp),
            ) {
                BasicComponent(
                    title = stringResource(R.string.mcp_delete_server),
                    titleColor = BasicComponentDefaults.titleColor(color = MiuixTheme.colorScheme.error),
                    summary = stringResource(R.string.mcp_delete_server_summary),
                    onClick = { showDelete = true },
                )
            }
        }
    }
}

/**
 * MCP 单个工具行：
 * - 顶行左侧展示工具名称，右侧固定放置开关（展开长说明时开关仍保持在顶行右侧，绝不被长文挤到底部）；
 * - 说明默认折叠为 1～2 行紧凑摘要，长说明提供「展开 / 收起」并按 Markdown 排版展示全文。
 */
@Composable
private fun McpToolSwitchRow(
    tool: McpToolDefinition,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val displayTitle = tool.title.ifBlank { tool.name }
    val showRawName = tool.title.isNotBlank() && tool.title != tool.name
    val fallbackSummary = if (tool.readOnlyHint == true) {
        stringResource(R.string.mcp_read_only_tool)
    } else {
        stringResource(R.string.mcp_may_modify_data)
    }
    val rawDescription = tool.description.trim()
    val collapsedPreview = remember(rawDescription, fallbackSummary) {
        if (rawDescription.isBlank()) fallbackSummary else collapseDescriptionPreview(rawDescription)
    }
    val isinherentlyMultiLine = remember(rawDescription) {
        rawDescription.count { it == '\n' } >= 2 || rawDescription.length > 90
    }
    var hasVisualOverflow by remember(collapsedPreview) { mutableStateOf(false) }
    var expanded by rememberSaveable(tool.name) { mutableStateOf(false) }
    val canExpand = rawDescription.isNotBlank() && (isinherentlyMultiLine || hasVisualOverflow || expanded)

    val headerInteraction = remember { MutableInteractionSource() }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize()
            .padding(horizontal = 16.dp, vertical = 13.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(
                    value = checked,
                    interactionSource = headerInteraction,
                    indication = null,
                    enabled = true,
                    role = Role.Switch,
                    onValueChange = onCheckedChange,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = displayTitle,
                    fontSize = MiuixTheme.textStyles.headline1.fontSize,
                    fontWeight = FontWeight.Medium,
                    color = MiuixTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (showRawName) {
                    Text(
                        text = tool.name,
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.78f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
            )
        }

        Spacer(Modifier.height(4.dp))

        if (!expanded) {
            Text(
                text = collapsedPreview,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                onTextLayout = { result ->
                    if (result.hasVisualOverflow != hasVisualOverflow) {
                        hasVisualOverflow = result.hasVisualOverflow
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (canExpand) {
                            Modifier.clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { expanded = true }
                        } else {
                            Modifier
                        },
                    ),
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MiuixTheme.colorScheme.onSurface.copy(alpha = 0.04f))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                StaticMarkdown(
                    content = rawDescription,
                    tone = MarkdownTone.Thinking,
                )
            }
        }

        if (canExpand) {
            Row(
                modifier = Modifier
                    .padding(top = 4.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(role = Role.Button) { expanded = !expanded }
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = stringResource(
                        if (expanded) R.string.ui_misc_collapse_description else R.string.ui_misc_expand_description,
                    ),
                    style = MiuixTheme.textStyles.footnote1.copy(
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                    color = MiuixTheme.colorScheme.primary,
                )
                Icon(
                    imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(15.dp),
                    tint = MiuixTheme.colorScheme.primary,
                )
            }
        }
    }
}

/**
 * 将可能带大量换行、标题井号与列表前缀的 MCP 工具原始说明收敛为紧凑的折叠态预览文本。
 */
internal fun collapseDescriptionPreview(raw: String): String =
    raw.lineSequence()
        .map { line ->
            line.trim()
                .removePrefix("### ")
                .removePrefix("## ")
                .removePrefix("# ")
                .removePrefix("- ")
                .removePrefix("* ")
                .trim()
        }
        .filter { it.isNotEmpty() }
        .joinToString(" · ")

private fun McpDiscoveryTruncation.summaryRes(): Int = when (this) {
    McpDiscoveryTruncation.TOOL_LIMIT -> R.string.mcp_tools_truncated_tool_limit
    McpDiscoveryTruncation.PAGE_LIMIT -> R.string.mcp_tools_truncated_page_limit
    McpDiscoveryTruncation.SIZE_LIMIT -> R.string.mcp_tools_truncated_size_limit
    McpDiscoveryTruncation.CURSOR_LOOP -> R.string.mcp_tools_truncated_cursor_loop
}
