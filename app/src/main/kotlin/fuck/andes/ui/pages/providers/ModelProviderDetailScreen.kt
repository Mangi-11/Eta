package fuck.andes.ui.pages.providers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.R as LucideR
import fuck.andes.FuckAndesApp
import fuck.andes.data.model.AnthropicProviderSetting
import fuck.andes.data.model.CustomProviderSetting
import fuck.andes.data.model.Model
import fuck.andes.data.model.OpenAiCompatibleProviderSetting
import fuck.andes.data.model.OpenAiEndpointMode
import fuck.andes.data.model.ProviderSetting
import fuck.andes.data.model.typeLabel
import fuck.andes.data.repository.ModelRepository
import fuck.andes.data.repository.ProviderRepository
import fuck.andes.data.repository.RemoteModelFetcher
import fuck.andes.data.repository.RuntimeConfigRepository
import fuck.andes.ui.components.StatusError
import fuck.andes.ui.components.StatusSuccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun ModelProviderDetailScreen(
    providerId: String,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val providers by ProviderRepository.providersFlow().collectAsState(initial = emptyList())
    val provider = remember(providers, providerId) { providers.firstOrNull { it.id == providerId } }
    var currentTab by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        RuntimeConfigRepository.ensureDefaults(FuckAndesApp.serviceInstance)
    }

    if (provider == null) {
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

    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(
            tabs = listOf("配置", "模型"),
            selectedTabIndex = currentTab,
            onTabSelected = { currentTab = it },
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
        when (currentTab) {
            0 -> ProviderConfigTab(provider = provider, scope = scope, onDeleted = onBack)
            1 -> ProviderModelsTab(provider = provider, scope = scope)
        }
    }
}

@Composable
private fun ProviderConfigTab(
    provider: ProviderSetting,
    scope: CoroutineScope,
    onDeleted: () -> Unit,
) {
    var name by remember(provider.id) { mutableStateOf(provider.name) }
    var baseUrl by remember(provider.id) { mutableStateOf(provider.baseUrl) }
    var apiKey by remember(provider.id) { mutableStateOf(provider.apiKey) }
    var systemPrompt by remember(provider.id) { mutableStateOf(provider.systemPrompt.orEmpty()) }
    var isEnabled by remember(provider.id) { mutableStateOf(provider.isEnabled) }
    var anthropicVersion by remember(provider.id) {
        mutableStateOf((provider as? AnthropicProviderSetting)?.anthropicVersion ?: AnthropicProviderSetting.DEFAULT_ANTHROPIC_VERSION)
    }
    var endpointMode by remember(provider.id) {
        mutableStateOf(
            when (provider) {
                is OpenAiCompatibleProviderSetting -> provider.endpointMode
                is CustomProviderSetting -> provider.endpointMode
                is AnthropicProviderSetting -> ""
            }
        )
    }
    var apiKeyVisible by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item(key = "type") {
            SmallTitle("协议", modifier = Modifier.padding(horizontal = 26.dp, vertical = 8.dp))
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                    Text(provider.typeLabel, style = MiuixTheme.textStyles.headline1)
                    Text(
                        text = if (provider.isBuiltIn) "内置 Provider，可复制或重置，不直接删除" else "自定义 Provider",
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }

        item(key = "basic") {
            SmallTitle("基础配置", modifier = Modifier.padding(horizontal = 26.dp, vertical = 8.dp))
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    TextField(value = name, onValueChange = { name = it }, label = "名称", singleLine = true)
                    TextField(
                        value = baseUrl,
                        onValueChange = { baseUrl = it },
                        label = "Base URL",
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                        singleLine = true,
                    )
                    TextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        label = "API Key",
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
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
                    )
                }
            }
        }

        item(key = "protocol") {
            SmallTitle("协议参数", modifier = Modifier.padding(horizontal = 26.dp, vertical = 8.dp))
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    if (provider is AnthropicProviderSetting) {
                        TextField(
                            value = anthropicVersion,
                            onValueChange = { anthropicVersion = it },
                            label = "anthropic-version",
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                    } else {
                        Text("Endpoint", style = MiuixTheme.textStyles.body2)
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            TextButton(
                                text = "Chat Completions",
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.textButtonColorsPrimary(),
                                onClick = { endpointMode = OpenAiEndpointMode.CHAT_COMPLETIONS },
                            )
                            TextButton(
                                text = "Responses（预留）",
                                modifier = Modifier.weight(1f),
                                enabled = false,
                                onClick = { endpointMode = OpenAiEndpointMode.RESPONSES },
                            )
                        }
                    }
                }
            }
        }

        item(key = "prompt") {
            SmallTitle("系统提示词", modifier = Modifier.padding(horizontal = 26.dp, vertical = 8.dp))
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                TextField(
                    value = systemPrompt,
                    onValueChange = { systemPrompt = it },
                    label = "留空使用默认手机 Agent 提示词",
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).height(120.dp),
                    singleLine = false,
                )
            }
        }

        item(key = "enabled") {
            SmallTitle("状态", modifier = Modifier.padding(horizontal = 26.dp, vertical = 8.dp))
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("启用此 Provider")
                    Switch(checked = isEnabled, onCheckedChange = { isEnabled = it })
                }
            }
        }

        item(key = "actions") {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(top = 12.dp, bottom = 24.dp),
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            text = "保存",
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.textButtonColorsPrimary(),
                            onClick = {
                                scope.launch {
                                    val updatedProvider = buildUpdatedProvider(
                                        source = provider,
                                        name = name,
                                        baseUrl = baseUrl,
                                        apiKey = apiKey,
                                        systemPrompt = systemPrompt,
                                        isEnabled = isEnabled,
                                        endpointMode = endpointMode,
                                        anthropicVersion = anthropicVersion,
                                    )
                                    ProviderRepository.updateProvider(updatedProvider)
                                    if (updatedProvider.isEnabled) {
                                        RuntimeConfigRepository.setSelectedProviderId(updatedProvider.id)
                                    }
                                    val ok = RuntimeConfigRepository.syncToRemotePreferences(FuckAndesApp.serviceInstance)
                                    status = when {
                                        !updatedProvider.isEnabled -> "已保存，Provider 未启用"
                                        ok -> "已保存、设为当前并同步"
                                        else -> "已保存并设为当前，LSPosed 服务未连接"
                                    }
                                }
                            },
                        )
                        TextButton(
                            text = "测试连接",
                            modifier = Modifier.weight(1f),
                            onClick = {
                                scope.launch {
                                    status = "测试中..."
                                    status = testConnection(
                                        buildUpdatedProvider(
                                            source = provider,
                                            name = name,
                                            baseUrl = baseUrl,
                                            apiKey = apiKey,
                                            systemPrompt = systemPrompt,
                                            isEnabled = isEnabled,
                                            endpointMode = endpointMode,
                                            anthropicVersion = anthropicVersion,
                                        )
                                    )
                                }
                            },
                        )
                    }
                    Row(
                        modifier = Modifier.padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (provider.isBuiltIn) {
                            TextButton(
                                text = "重置内置配置",
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    scope.launch {
                                        ProviderRepository.resetBuiltIn(provider.id)
                                        RuntimeConfigRepository.syncToRemotePreferences(FuckAndesApp.serviceInstance)
                                        status = "已重置"
                                    }
                                },
                            )
                            TextButton(
                                text = "复制为自定义",
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    scope.launch {
                                        ProviderRepository.copyProvider(provider.id)
                                        status = "已复制"
                                    }
                                },
                            )
                        } else {
                            TextButton(
                                text = "删除",
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.textButtonColorsPrimary(),
                                onClick = { showDeleteDialog = true },
                            )
                        }
                    }
                    status?.let { message ->
                        Text(
                            text = message,
                            style = MiuixTheme.textStyles.footnote2,
                            color = if (message.startsWith("失败")) StatusError else StatusSuccess,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
        }
    }

    if (showDeleteDialog) {
        OverlayDialog(show = true, title = "删除 Provider", onDismissRequest = { showDeleteDialog = false }) {
            Text("确定删除「${provider.name}」吗？此操作不可恢复。")
            Row(modifier = Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.End) {
                TextButton(text = "取消", onClick = { showDeleteDialog = false })
                Spacer(modifier = Modifier.width(8.dp))
                TextButton(
                    text = "删除",
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    onClick = {
                        scope.launch {
                            ProviderRepository.deleteProvider(provider.id)
                            RuntimeConfigRepository.syncToRemotePreferences(FuckAndesApp.serviceInstance)
                            showDeleteDialog = false
                            onDeleted()
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun ProviderModelsTab(provider: ProviderSetting, scope: CoroutineScope) {
    val selectedModelId by RuntimeConfigRepository.selectedModelIdFlow().collectAsState(initial = null)
    var isFetching by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item(key = "actions") {
            SmallTitle("模型列表", modifier = Modifier.padding(horizontal = 26.dp, vertical = 8.dp))
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(
                    text = if (isFetching) "拉取中..." else "从远端拉取",
                    enabled = !isFetching,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        scope.launch {
                            isFetching = true
                            message = null
                            RemoteModelFetcher.fetch(provider)
                                .onSuccess { models ->
                                    ModelRepository.replaceModelsForProvider(provider.id, models)
                                    RuntimeConfigRepository.syncToRemotePreferences(FuckAndesApp.serviceInstance)
                                    message = "已拉取 ${models.size} 个模型"
                                }
                                .onFailure { throwable ->
                                    message = "失败：${throwable.message ?: throwable.javaClass.simpleName}"
                                }
                            isFetching = false
                        }
                    },
                )
                TextButton(
                    text = "添加模型",
                    modifier = Modifier.weight(1f),
                    onClick = {
                        scope.launch {
                            ModelRepository.addModel(
                                provider.id,
                                Model(
                                    id = ModelRepository.newId(),
                                    modelId = "model-id",
                                    displayName = "自定义模型",
                                    supportsTools = true,
                                ),
                            )
                            RuntimeConfigRepository.syncToRemotePreferences(FuckAndesApp.serviceInstance)
                        }
                    },
                )
            }
            message?.let {
                Text(
                    text = it,
                    style = MiuixTheme.textStyles.footnote2,
                    color = if (it.startsWith("失败")) StatusError else StatusSuccess,
                    modifier = Modifier.padding(horizontal = 26.dp, vertical = 6.dp),
                )
            }
        }

        items(
            items = provider.models.sortedBy { it.sortOrder },
            key = { it.id },
        ) { model ->
            ModelListItem(
                model = model,
                isSelected = model.id == selectedModelId,
                onUpdate = { updated ->
                    scope.launch {
                        ModelRepository.updateModel(provider.id, updated)
                        RuntimeConfigRepository.syncToRemotePreferences(FuckAndesApp.serviceInstance)
                    }
                },
                onDelete = {
                    scope.launch {
                        ModelRepository.deleteModel(provider.id, model.id)
                        RuntimeConfigRepository.syncToRemotePreferences(FuckAndesApp.serviceInstance)
                    }
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

@Composable
private fun ModelListItem(
    model: Model,
    isSelected: Boolean,
    onUpdate: (Model) -> Unit,
    onDelete: () -> Unit,
    onSetCurrent: () -> Unit,
) {
    var showEditDialog by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        onClick = { showEditDialog = true },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(model.displayName, style = MiuixTheme.textStyles.headline1)
                Text(
                    text = model.modelId,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 2.dp),
                )
                Text(
                    text = buildCapabilityLabel(model) + if (isSelected) " · 当前" else "",
                    style = MiuixTheme.textStyles.footnote2,
                    color = if (isSelected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantActions,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            IconButton(onClick = onSetCurrent) {
                Icon(
                    painter = painterResource(if (isSelected) LucideR.drawable.lucide_ic_check else LucideR.drawable.lucide_ic_circle),
                    contentDescription = if (isSelected) "当前模型" else "设为当前",
                )
            }
        }
    }

    if (showEditDialog) {
        ModelEditDialog(
                model = model,
                onDismiss = { showEditDialog = false },
            onUpdate = onUpdate,
            onDelete = onDelete,
            onSetCurrent = onSetCurrent,
        )
    }
}

@Composable
private fun ModelEditDialog(
    model: Model,
    onDismiss: () -> Unit,
    onUpdate: (Model) -> Unit,
    onDelete: () -> Unit,
    onSetCurrent: () -> Unit,
) {
    var displayName by remember { mutableStateOf(model.displayName) }
    var modelId by remember { mutableStateOf(model.modelId) }
    var supportsVision by remember { mutableStateOf(model.supportsVision) }
    var supportsTools by remember { mutableStateOf(model.supportsTools) }
    var supportsReasoning by remember { mutableStateOf(model.supportsReasoning) }

    fun updated(): Model = model.copy(
        displayName = displayName.trim(),
        modelId = modelId.trim(),
        supportsVision = supportsVision,
        supportsTools = supportsTools,
        supportsReasoning = supportsReasoning,
    )

    OverlayDialog(show = true, title = "编辑模型", onDismissRequest = onDismiss) {
        Column {
            TextField(value = displayName, onValueChange = { displayName = it }, label = "展示名称", singleLine = true)
            TextField(
                value = modelId,
                onValueChange = { modelId = it },
                label = "Model ID",
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                singleLine = true,
            )
            CapabilitySwitch("Vision", supportsVision) { supportsVision = it }
            CapabilitySwitch("Tools", supportsTools) { supportsTools = it }
            CapabilitySwitch("Reasoning", supportsReasoning) { supportsReasoning = it }
        }
        Row(modifier = Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.End) {
            TextButton(text = "取消", onClick = onDismiss)
            Spacer(modifier = Modifier.width(8.dp))
            TextButton(
                text = "设为当前",
                onClick = {
                    onUpdate(updated())
                    onSetCurrent()
                    onDismiss()
                },
            )
            Spacer(modifier = Modifier.width(8.dp))
            TextButton(
                text = "删除",
                colors = ButtonDefaults.textButtonColorsPrimary(),
                onClick = {
                    onDelete()
                    onDismiss()
                },
            )
            Spacer(modifier = Modifier.width(8.dp))
            TextButton(
                text = "保存",
                colors = ButtonDefaults.textButtonColorsPrimary(),
                onClick = {
                    onUpdate(updated())
                    onDismiss()
                },
            )
        }
    }
}

@Composable
private fun CapabilitySwitch(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
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

private fun buildCapabilityLabel(model: Model): String {
    val parts = buildList {
        if (model.supportsVision) add("Vision")
        if (model.supportsTools) add("Tools")
        if (model.supportsReasoning) add("Reasoning")
        model.contextWindow?.let { add("${it / 1000}K context") }
    }
    return parts.joinToString(" · ").ifBlank { "基础文本" }
}

private suspend fun testConnection(provider: ProviderSetting): String =
    RemoteModelFetcher.fetch(provider)
        .map { "成功，拉取到 ${it.size} 个模型" }
        .getOrElse { throwable -> "失败：${throwable.message ?: throwable.javaClass.simpleName}" }
