package fuck.andes.ui.pages.providers

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.R as LucideR
import fuck.andes.FuckAndesApp
import fuck.andes.data.model.AnthropicProviderSetting
import fuck.andes.data.model.CustomProviderSetting
import fuck.andes.data.model.Model
import fuck.andes.data.model.OpenAiCompatibleProviderSetting
import fuck.andes.data.model.OpenAiEndpointMode
import fuck.andes.data.model.ProviderSetting
import fuck.andes.data.model.withId
import fuck.andes.data.repository.ModelRepository
import fuck.andes.data.repository.ProviderRepository
import fuck.andes.data.repository.RemoteModelFetcher
import fuck.andes.data.repository.RuntimeConfigRepository
import fuck.andes.ui.components.MiuixScaffold
import fuck.andes.ui.components.StatusError
import fuck.andes.ui.components.StatusSuccess
import fuck.andes.ui.navigation.NewProviderType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

private val DeleteButtonBg = Color(0xFFFFEBEE)
private val DeleteButtonFg = Color(0xFFD32F2F)

private data class ProviderConfigDraft(
    val name: String,
    val baseUrl: String,
    val apiKey: String,
    val systemPrompt: String,
    val isEnabled: Boolean,
    val endpointMode: String,
    val anthropicVersion: String,
) {
    companion object {
        fun from(provider: ProviderSetting): ProviderConfigDraft = ProviderConfigDraft(
            name = provider.name,
            baseUrl = provider.baseUrl,
            apiKey = provider.apiKey,
            systemPrompt = provider.systemPrompt.orEmpty(),
            isEnabled = provider.isEnabled,
            endpointMode = when (provider) {
                is OpenAiCompatibleProviderSetting -> provider.endpointMode
                is CustomProviderSetting -> provider.endpointMode
                is AnthropicProviderSetting -> ""
            },
            anthropicVersion = (provider as? AnthropicProviderSetting)?.anthropicVersion
                ?: AnthropicProviderSetting.DEFAULT_ANTHROPIC_VERSION,
        )
    }
}

@Composable
internal fun ModelProviderDetailScreen(
    providerId: String? = null,
    newType: NewProviderType? = null,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val providers by ProviderRepository.providersFlow().collectAsState(initial = emptyList())
    var createdId by remember { mutableStateOf<String?>(null) }
    val effectiveId = providerId ?: createdId
    val provider = remember(providers, effectiveId) {
        effectiveId?.let { id -> providers.firstOrNull { it.id == id } }
    }
    val draft = remember(newType) {
        when (newType) {
            NewProviderType.OpenAiCompatible -> CustomProviderSetting(
                id = "",
                name = "",
                baseUrl = "",
                endpointMode = OpenAiEndpointMode.CHAT_COMPLETIONS,
            )
            NewProviderType.Anthropic -> AnthropicProviderSetting(
                id = "",
                name = "",
                baseUrl = "https://api.anthropic.com",
            )
            null -> null
        }
    }

    LaunchedEffect(Unit) {
        RuntimeConfigRepository.ensureDefaults(FuckAndesApp.serviceInstance)
    }

    if (provider == null && draft == null) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Provider 不存在")
            Spacer(modifier = Modifier.height(12.dp))
            TextButton(text = "返回", onClick = onBack)
        }
        return
    }

    val initial = provider ?: draft!!
    val isNew = provider == null
    var currentTab by remember { mutableIntStateOf(0) }
    var configDraft by remember(initial.id) { mutableStateOf(ProviderConfigDraft.from(initial)) }
    val title = if (isNew) "新建提供商" else initial.name

    MiuixScaffold(title = title, onBack = onBack) { paddingValues, scrollBehavior ->
        Column(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            if (!isNew) {
                TabRow(
                    tabs = listOf("配置", "模型"),
                    selectedTabIndex = currentTab,
                    onTabSelected = { currentTab = it },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when (currentTab) {
                    0 -> ProviderConfigTab(
                        provider = initial,
                        draft = configDraft,
                        onDraftChange = { configDraft = it },
                        scope = scope,
                        isNew = isNew,
                        scrollBehavior = scrollBehavior,
                        onCreated = { id -> createdId = id },
                        onDeleted = onBack,
                    )
                    1 -> if (!isNew) {
                        ProviderModelsTab(provider = initial, scope = scope, scrollBehavior = scrollBehavior)
                    }
                }
            }
        }
    }
}

@Composable
private fun ProviderConfigTab(
    provider: ProviderSetting,
    draft: ProviderConfigDraft,
    onDraftChange: (ProviderConfigDraft) -> Unit,
    scope: CoroutineScope,
    isNew: Boolean,
    scrollBehavior: ScrollBehavior,
    onCreated: (String) -> Unit,
    onDeleted: () -> Unit,
) {
    var apiKeyVisible by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showResetDialog by remember { mutableStateOf(false) }
    var isWorking by remember { mutableStateOf(false) }
    var creationCommitted by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .overScrollVertical()
            .scrollEndHaptic()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        overscrollEffect = null,
    ) {
        item(key = "connection") {
            ProviderSection(title = "连接配置") {
                Column(modifier = Modifier.padding(16.dp)) {
                    TextField(
                        value = draft.name,
                        onValueChange = { onDraftChange(draft.copy(name = it)) },
                        label = "名称",
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    TextField(
                        value = draft.baseUrl,
                        onValueChange = { onDraftChange(draft.copy(baseUrl = it)) },
                        label = "Base URL",
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    TextField(
                        value = draft.apiKey,
                        onValueChange = { onDraftChange(draft.copy(apiKey = it)) },
                        label = "API Key",
                        singleLine = true,
                        visualTransformation = if (apiKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { apiKeyVisible = !apiKeyVisible }) {
                                Icon(
                                    painter = painterResource(
                                        if (apiKeyVisible) LucideR.drawable.lucide_ic_eye else LucideR.drawable.lucide_ic_eye_off,
                                    ),
                                    contentDescription = if (apiKeyVisible) "隐藏" else "显示",
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (provider is AnthropicProviderSetting) {
                        Spacer(modifier = Modifier.height(12.dp))
                        TextField(
                            value = draft.anthropicVersion,
                            onValueChange = { onDraftChange(draft.copy(anthropicVersion = it)) },
                            label = "anthropic-version",
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                if (provider !is AnthropicProviderSetting) {
                    HorizontalDivider()
                    BasicComponent(
                        title = "Endpoint 模式",
                        summary = "当前协议使用标准 Chat Completions",
                        endActions = {
                            Text(
                                text = "Chat Completions",
                                color = MiuixTheme.colorScheme.primary,
                                style = MiuixTheme.textStyles.body2,
                            )
                        },
                    )
                }
            }
        }

        item(key = "preferences_and_prompt") {
            ProviderSection(title = "偏好与策略") {
                SwitchPreference(
                    title = "启用此 Provider",
                    checked = draft.isEnabled,
                    onCheckedChange = { onDraftChange(draft.copy(isEnabled = it)) }
                )
                HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
                Column(modifier = Modifier.padding(16.dp)) {
                    TextField(
                        value = draft.systemPrompt,
                        onValueChange = { onDraftChange(draft.copy(systemPrompt = it)) },
                        label = "系统提示词",
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp),
                        singleLine = false,
                    )
                    Text(
                        text = "留空使用默认手机 Agent 提示词",
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }

        item(key = "actions") {
            ProviderSection(title = null, modifier = Modifier.padding(top = 12.dp)) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(
                        text = when {
                            isWorking -> "保存中..."
                            creationCommitted -> "已创建"
                            isNew -> "创建"
                            else -> "保存配置"
                        },
                        enabled = !isWorking && !creationCommitted,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                        onClick = {
                            val validationError = validateProviderDraft(draft)
                            if (validationError != null) {
                                status = "失败：$validationError"
                                return@TextButton
                            }
                            scope.launch {
                                isWorking = true
                                val built = buildUpdatedProvider(
                                    source = provider,
                                    name = draft.name,
                                    baseUrl = draft.baseUrl,
                                    apiKey = draft.apiKey,
                                    systemPrompt = draft.systemPrompt,
                                    isEnabled = draft.isEnabled,
                                    endpointMode = draft.endpointMode,
                                    anthropicVersion = draft.anthropicVersion,
                                )
                                try {
                                    if (isNew) {
                                        val added = ProviderRepository.addProvider(
                                            built.withId(ProviderRepository.newId())
                                        )
                                        if (added.isEnabled) {
                                            RuntimeConfigRepository.setSelectedProviderId(added.id)
                                        }
                                        val ok = RuntimeConfigRepository.syncToRemotePreferences(
                                            FuckAndesApp.serviceInstance
                                        )
                                        status = if (ok) "已创建、设为当前并同步"
                                        else "已创建并设为当前，LSPosed 服务未连接"
                                        creationCommitted = true
                                        onCreated(added.id)
                                    } else {
                                        ProviderRepository.updateProvider(built)
                                        if (built.isEnabled) {
                                            RuntimeConfigRepository.setSelectedProviderId(built.id)
                                        }
                                        val ok = RuntimeConfigRepository.syncToRemotePreferences(
                                            FuckAndesApp.serviceInstance
                                        )
                                        status = when {
                                            !built.isEnabled -> "已保存，Provider 未启用"
                                            ok -> "已保存、设为当前并同步"
                                            else -> "已保存并设为当前，LSPosed 服务未连接"
                                        }
                                    }
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (throwable: Throwable) {
                                    status = "失败：${throwable.message ?: "保存失败"}"
                                } finally {
                                    isWorking = false
                                }
                            }
                        },
                    )
                    TextButton(
                        text = if (isWorking) "处理中..." else "测试连接",
                        enabled = !isWorking,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            val validationError = validateProviderDraft(draft)
                            if (validationError != null) {
                                status = "失败：$validationError"
                                return@TextButton
                            }
                            scope.launch {
                                isWorking = true
                                status = "测试中..."
                                try {
                                    status = testConnection(
                                        buildUpdatedProvider(
                                            source = provider,
                                            name = draft.name,
                                            baseUrl = draft.baseUrl,
                                            apiKey = draft.apiKey,
                                            systemPrompt = draft.systemPrompt,
                                            isEnabled = draft.isEnabled,
                                            endpointMode = draft.endpointMode,
                                            anthropicVersion = draft.anthropicVersion,
                                        )
                                    )
                                } finally {
                                    isWorking = false
                                }
                            }
                        },
                    )
                    if (!isNew) {
                        if (provider.isBuiltIn) {
                            TextButton(
                                text = "重置内置配置",
                                enabled = !isWorking,
                                modifier = Modifier.fillMaxWidth(),
                                onClick = {
                                    showResetDialog = true
                                },
                            )
                        } else {
                            TextButton(
                                text = "删除提供商",
                                enabled = !isWorking,
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.textButtonColors(
                                    color = DeleteButtonBg,
                                    textColor = DeleteButtonFg,
                                ),
                                onClick = { showDeleteDialog = true },
                            )
                        }
                    }
                    status?.let { message ->
                        Text(
                            text = message,
                            style = MiuixTheme.textStyles.footnote2,
                            color = if (message.startsWith("失败")) StatusError else StatusSuccess,
                        )
                    }
                }
            }
        }

        item(key = "bottom_spacer") { Spacer(modifier = Modifier.navigationBarsPadding()) }
    }

    if (showDeleteDialog) {
        OverlayDialog(show = true, title = "删除 Provider", onDismissRequest = { if (!isWorking) showDeleteDialog = false }) {
            Text("确定删除「${provider.name}」吗？此操作不可恢复。")
            Row(modifier = Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.End) {
                TextButton(text = "取消", enabled = !isWorking, onClick = { showDeleteDialog = false })
                Spacer(modifier = Modifier.width(8.dp))
                TextButton(
                    text = "删除",
                    enabled = !isWorking,
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    onClick = {
                        scope.launch {
                            isWorking = true
                            try {
                                ProviderRepository.deleteProvider(provider.id)
                                RuntimeConfigRepository.syncToRemotePreferences(FuckAndesApp.serviceInstance)
                                showDeleteDialog = false
                                onDeleted()
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (throwable: Throwable) {
                                status = "失败：${throwable.message ?: "删除失败"}"
                                showDeleteDialog = false
                            } finally {
                                isWorking = false
                            }
                        }
                    },
                )
            }
        }
    }

    if (showResetDialog) {
        OverlayDialog(
            show = true,
            title = "重置内置配置",
            onDismissRequest = { if (!isWorking) showResetDialog = false },
        ) {
            Text("将恢复「${provider.name}」的默认配置和官方模型列表，API Key 会保留。确定继续吗？")
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(
                    text = "取消",
                    enabled = !isWorking,
                    onClick = { showResetDialog = false },
                )
                Spacer(modifier = Modifier.width(8.dp))
                TextButton(
                    text = if (isWorking) "重置中..." else "重置",
                    enabled = !isWorking,
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    onClick = {
                        scope.launch {
                            isWorking = true
                            try {
                                ProviderRepository.resetBuiltIn(provider.id)
                                RuntimeConfigRepository.syncToRemotePreferences(FuckAndesApp.serviceInstance)
                                status = "已重置"
                                showResetDialog = false
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (throwable: Throwable) {
                                status = "失败：${throwable.message ?: "重置失败"}"
                                showResetDialog = false
                            } finally {
                                isWorking = false
                            }
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun ProviderModelsTab(
    provider: ProviderSetting,
    scope: CoroutineScope,
    scrollBehavior: ScrollBehavior,
) {
    val selectedModelId by RuntimeConfigRepository.selectedModelIdFlow().collectAsState(initial = null)
    var isFetching by remember { mutableStateOf(false) }
    var isMutatingModel by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var editingModel by remember { mutableStateOf<Model?>(null) }
    var isCreatingModel by remember { mutableStateOf(false) }
    var editorError by remember { mutableStateOf<String?>(null) }
    var modelPendingDelete by remember { mutableStateOf<Model?>(null) }
    var selectionMode by remember(provider.id) { mutableStateOf(false) }
    var selectedModelIds by remember(provider.id) { mutableStateOf(setOf<String>()) }
    var showBatchDeleteDialog by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .overScrollVertical()
            .scrollEndHaptic()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        overscrollEffect = null,
    ) {
        item(key = "actions") {
            ProviderSection(title = "模型管理") {
                ArrowPreference(
                    title = if (isFetching) "拉取中..." else "从远端自动拉取",
                    summary = "读取 ${provider.baseUrl} 的 /models 列表",
                    enabled = !isFetching && !isMutatingModel,
                    startAction = {
                        ProviderRoundIcon(
                            icon = LucideR.drawable.lucide_ic_cloud_download,
                            tint = MiuixTheme.colorScheme.primary,
                        )
                    },
                    onClick = {
                        scope.launch {
                            isFetching = true
                            message = null
                            try {
                                val models = RemoteModelFetcher.fetch(provider).getOrElse { throwable ->
                                    message = "失败：${throwable.message ?: throwable.javaClass.simpleName}"
                                    return@launch
                                }
                                val chatModels = models.filter(RemoteModelFetcher::isChatCapableModel)
                                val sync = ModelRepository.syncRemoteModels(provider.id, chatModels)
                                if (sync.applied) {
                                    RuntimeConfigRepository.syncToRemotePreferences(FuckAndesApp.serviceInstance)
                                }
                                val filteredCount = models.size - chatModels.size
                                message = if (!sync.applied) {
                                    "远端未返回可用对话模型，已保留现有模型"
                                } else if (filteredCount > 0) {
                                    "已拉取 ${chatModels.size} 个模型，过滤 $filteredCount 个非对话模型"
                                } else {
                                    "已拉取 ${chatModels.size} 个模型"
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (throwable: Throwable) {
                                message = "失败：${throwable.message ?: "同步失败"}"
                            } finally {
                                isFetching = false
                            }
                        }
                    },
                )
                ProviderDivider()
                ArrowPreference(
                    title = "添加自定义模型",
                    summary = "手动填写展示名称与 Model ID",
                    enabled = !isFetching && !isMutatingModel,
                    startAction = {
                        ProviderRoundIcon(
                            icon = LucideR.drawable.lucide_ic_plus,
                            tint = MiuixTheme.colorScheme.primary,
                        )
                    },
                    onClick = {
                        editorError = null
                        isCreatingModel = true
                        editingModel = Model(
                            id = "",
                            modelId = "",
                            displayName = "自定义模型",
                        )
                    },
                )
                message?.let {
                    HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
                    Text(
                        text = it,
                        style = MiuixTheme.textStyles.footnote2,
                        color = if (it.startsWith("失败")) StatusError else StatusSuccess,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                }
            }
        }

        if (selectionMode) {
            item(key = "selection_bar") {
                ProviderSection(title = null, modifier = Modifier.padding(top = 12.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "已选 ${selectedModelIds.size} 个",
                            style = MiuixTheme.textStyles.body2,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            text = if (selectedModelIds.size == provider.models.size) "全不选" else "全选",
                            enabled = !isFetching && !isMutatingModel,
                            onClick = {
                                selectedModelIds = if (selectedModelIds.size == provider.models.size) {
                                    emptySet()
                                } else {
                                    provider.models.mapTo(mutableSetOf()) { it.id }
                                }
                            },
                        )
                        TextButton(
                            text = "删除",
                            enabled = selectedModelIds.isNotEmpty() && !isFetching && !isMutatingModel,
                            colors = ButtonDefaults.textButtonColors(
                                color = DeleteButtonBg,
                                textColor = DeleteButtonFg,
                            ),
                            onClick = { showBatchDeleteDialog = true },
                        )
                        IconButton(
                            enabled = !isFetching && !isMutatingModel,
                            onClick = {
                                selectionMode = false
                                selectedModelIds = emptySet()
                            },
                        ) {
                            Icon(
                                painter = painterResource(LucideR.drawable.lucide_ic_x),
                                contentDescription = "退出多选",
                                tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
                            )
                        }
                    }
                }
            }
        }

        item(key = "models_list") {
            ProviderSection(
                title = "模型列表 (共 ${provider.models.size} 个)",
                modifier = Modifier.padding(bottom = 24.dp),
            ) {
                if (provider.models.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "暂无模型，请从远端拉取或手动添加",
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                } else {
                    provider.models.sortedBy { it.sortOrder }.forEachIndexed { index, model ->
                        if (index > 0) {
                            HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
                        }
                        ModelListItem(
                            model = model,
                            enabled = !isFetching && !isMutatingModel,
                            isSelected = model.id == selectedModelId,
                            selectionMode = selectionMode,
                            checked = model.id in selectedModelIds,
                            onToggleChecked = {
                                selectedModelIds = if (model.id in selectedModelIds) {
                                    selectedModelIds - model.id
                                } else {
                                    selectedModelIds + model.id
                                }
                            },
                            onEnterSelection = {
                                selectionMode = true
                                selectedModelIds = setOf(model.id)
                            },
                            onEdit = {
                                editorError = null
                                isCreatingModel = false
                                editingModel = model
                            },
                            onSetCurrent = {
                                scope.launch {
                                    RuntimeConfigRepository.setSelectedModelId(model.id)
                                    RuntimeConfigRepository.syncToRemotePreferences(FuckAndesApp.serviceInstance)
                                }
                            },
                        )
                    }
                }
            }
        }

        item(key = "bottom_spacer") { Spacer(modifier = Modifier.navigationBarsPadding()) }
    }

    editingModel?.let { model ->
        ModelEditDialog(
            model = model,
            isNew = isCreatingModel,
            isSaving = isMutatingModel,
            error = editorError,
            onDismiss = {
                if (!isMutatingModel) editingModel = null
            },
            onSubmit = { updated, setCurrent ->
                if (isMutatingModel) return@ModelEditDialog
                scope.launch {
                    isMutatingModel = true
                    editorError = null
                    try {
                        val saved = ModelRepository.saveModel(provider.id, updated)
                        if (setCurrent) {
                            RuntimeConfigRepository.setSelectedModelId(saved.id)
                        }
                        RuntimeConfigRepository.syncToRemotePreferences(FuckAndesApp.serviceInstance)
                        editingModel = null
                        message = if (setCurrent) "已保存并设为当前：${saved.displayName}" else "已保存：${saved.displayName}"
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (throwable: Throwable) {
                        editorError = throwable.message ?: "保存失败"
                    } finally {
                        isMutatingModel = false
                    }
                }
            },
            onDelete = if (isCreatingModel) null else {
                {
                    modelPendingDelete = model
                    editingModel = null
                }
            }
        )
    }

    modelPendingDelete?.let { model ->
        OverlayDialog(
            show = true,
            title = "删除模型",
            onDismissRequest = { if (!isMutatingModel) modelPendingDelete = null },
        ) {
            Text("确定删除「${model.displayName}」吗？此操作不可恢复。")
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(
                    text = "取消",
                    enabled = !isMutatingModel,
                    onClick = { modelPendingDelete = null },
                )
                Spacer(modifier = Modifier.width(8.dp))
                TextButton(
                    text = if (isMutatingModel) "删除中..." else "删除",
                    enabled = !isMutatingModel,
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    onClick = {
                        scope.launch {
                            isMutatingModel = true
                            try {
                                ModelRepository.deleteModel(provider.id, model.id)
                                RuntimeConfigRepository.syncToRemotePreferences(FuckAndesApp.serviceInstance)
                                message = "已删除：${model.displayName}"
                                modelPendingDelete = null
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (throwable: Throwable) {
                                message = "失败：${throwable.message ?: "删除失败"}"
                                modelPendingDelete = null
                            } finally {
                                isMutatingModel = false
                            }
                        }
                    },
                )
            }
        }
    }

    if (showBatchDeleteDialog) {
        OverlayDialog(
            show = true,
            title = "删除模型",
            onDismissRequest = { if (!isMutatingModel) showBatchDeleteDialog = false },
        ) {
            Text("确定删除选中的 ${selectedModelIds.size} 个模型吗？此操作不可恢复。")
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(
                    text = "取消",
                    enabled = !isMutatingModel,
                    onClick = { showBatchDeleteDialog = false },
                )
                Spacer(modifier = Modifier.width(8.dp))
                TextButton(
                    text = if (isMutatingModel) "删除中..." else "删除",
                    enabled = !isMutatingModel,
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    onClick = {
                        scope.launch {
                            val deletedCount = selectedModelIds.size
                            isMutatingModel = true
                            try {
                                ModelRepository.deleteModels(provider.id, selectedModelIds)
                                RuntimeConfigRepository.syncToRemotePreferences(FuckAndesApp.serviceInstance)
                                message = "已删除 $deletedCount 个模型"
                                showBatchDeleteDialog = false
                                selectionMode = false
                                selectedModelIds = emptySet()
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (throwable: Throwable) {
                                message = "失败：${throwable.message ?: "删除失败"}"
                                showBatchDeleteDialog = false
                            } finally {
                                isMutatingModel = false
                            }
                        }
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ModelListItem(
    model: Model,
    enabled: Boolean,
    isSelected: Boolean,
    selectionMode: Boolean,
    checked: Boolean,
    onToggleChecked: () -> Unit,
    onEnterSelection: () -> Unit,
    onEdit: () -> Unit,
    onSetCurrent: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                enabled = enabled,
                onClick = if (selectionMode) onToggleChecked else onEdit,
                onLongClick = {
                    if (selectionMode) onToggleChecked() else onEnterSelection()
                },
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = model.displayName,
                style = MiuixTheme.textStyles.headline1,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = model.modelId,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(top = 6.dp),
            ) {
                capabilityTags(model).forEach { tag ->
                    TagChip(text = tag)
                }
                if (isSelected) {
                    TagChip(text = "当前", tone = TagChipTone.Emphasized)
                }
            }
        }
        if (selectionMode) {
            Checkbox(
                state = if (checked) ToggleableState.On else ToggleableState.Off,
                onClick = onToggleChecked,
                enabled = enabled,
            )
        } else {
            IconButton(onClick = onSetCurrent, enabled = enabled) {
                Icon(
                    painter = painterResource(if (isSelected) LucideR.drawable.lucide_ic_check else LucideR.drawable.lucide_ic_circle),
                    contentDescription = if (isSelected) "当前模型" else "设为当前",
                    tint = if (isSelected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantActions,
                )
            }
        }
    }
}

@Composable
private fun ModelEditDialog(
    model: Model,
    isNew: Boolean,
    isSaving: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onSubmit: (Model, Boolean) -> Unit,
    onDelete: (() -> Unit)?,
) {
    var displayName by remember(model.id, isNew) { mutableStateOf(model.displayName) }
    var modelId by remember(model.id, isNew) { mutableStateOf(model.modelId) }

    fun updated(): Model = model.copy(
        displayName = displayName.trim(),
        modelId = modelId.trim(),
    )

    OverlayDialog(
        show = true,
        title = if (isNew) "添加模型" else "编辑模型",
        onDismissRequest = { if (!isSaving) onDismiss() },
    ) {
        Column {
            TextField(
                value = displayName,
                onValueChange = { displayName = it },
                label = "展示名称",
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(12.dp))
            TextField(
                value = modelId,
                onValueChange = { modelId = it },
                label = "Model ID",
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "能力标签来自远端 /models 或官方 catalog：${buildCapabilityLabel(model)}",
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            error?.let { message ->
                Text(
                    text = message,
                    style = MiuixTheme.textStyles.footnote2,
                    color = StatusError,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
        Column(modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    text = "取消",
                    enabled = !isSaving,
                    modifier = Modifier.weight(1f),
                    onClick = onDismiss,
                )
                onDelete?.let { delete ->
                    TextButton(
                        text = "删除",
                        enabled = !isSaving,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColors(
                            color = DeleteButtonBg,
                            textColor = DeleteButtonFg,
                        ),
                        onClick = delete,
                    )
                }
            }
            Row(
                modifier = Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(
                    text = "保存并设为当前",
                    enabled = !isSaving,
                    modifier = Modifier.weight(1f),
                    onClick = { onSubmit(updated(), true) },
                )
                TextButton(
                    text = if (isSaving) "保存中..." else "保存",
                    enabled = !isSaving,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    onClick = { onSubmit(updated(), false) },
                )
            }
        }
    }
}

private fun buildUpdatedProvider(
    source: ProviderSetting,
    name: String,
    baseUrl: String,
    apiKey: String,
    systemPrompt: String,
    isEnabled: Boolean,
    endpointMode: String,
    anthropicVersion: String,
): ProviderSetting {
    val prompt = systemPrompt.trim().takeIf { it.isNotBlank() }
    return when (source) {
        is OpenAiCompatibleProviderSetting -> source.copy(
            name = name.trim(),
            baseUrl = baseUrl.trim(),
            apiKey = apiKey.trim(),
            systemPrompt = prompt,
            isEnabled = isEnabled,
            endpointMode = endpointMode,
        )
        is CustomProviderSetting -> source.copy(
            name = name.trim(),
            baseUrl = baseUrl.trim(),
            apiKey = apiKey.trim(),
            systemPrompt = prompt,
            isEnabled = isEnabled,
            endpointMode = endpointMode,
        )
        is AnthropicProviderSetting -> source.copy(
            name = name.trim(),
            baseUrl = baseUrl.trim(),
            apiKey = apiKey.trim(),
            systemPrompt = prompt,
            isEnabled = isEnabled,
            anthropicVersion = anthropicVersion.trim().ifBlank { AnthropicProviderSetting.DEFAULT_ANTHROPIC_VERSION },
        )
    }
}

private fun validateProviderDraft(draft: ProviderConfigDraft): String? {
    if (draft.name.isBlank()) return "名称不能为空"
    val uri = runCatching { java.net.URI(draft.baseUrl.trim()) }.getOrNull()
    if (uri == null || uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank()) {
        return "Base URL 必须是有效的 HTTP(S) 地址"
    }
    return null
}

private fun capabilityTags(model: Model): List<String> = buildList {
    if (model.supportsVision) add("Vision")
    if (model.supportsTools) add("Tools")
    if (model.supportsReasoning) add("Reasoning")
    model.contextWindow?.let { contextWindow ->
        add(
            if (contextWindow >= 1_000_000) {
                "1M context"
            } else {
                "${contextWindow / 1000}K context"
            }
        )
    }
}.ifEmpty { listOf("基础文本") }

private fun buildCapabilityLabel(model: Model): String = capabilityTags(model).joinToString(" · ")

private suspend fun testConnection(provider: ProviderSetting): String =
    RemoteModelFetcher.fetch(provider)
        .map { "成功，拉取到 ${it.size} 个模型" }
        .getOrElse { throwable -> "失败：${throwable.message ?: throwable.javaClass.simpleName}" }
