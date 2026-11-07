package io.github.mangi.eta.ui.screens.subagent

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.itos.coui.dialog.CouiDialogAction
import com.itos.coui.dialog.CouiDialogDefaults
import com.itos.coui.dialog.CouiDialogPlacement
import com.itos.coui.dialog.Dialog
import com.itos.coui.icons.CouiIcons
import com.itos.coui.layout.Divider
import com.itos.coui.layout.Group
import com.itos.coui.menu.rememberCouiWindowBackdrop
import com.itos.coui.text.CouiTextKind
import com.itos.coui.text.Text as CouiText
import com.itos.coui.theme.CouiTheme
import io.github.mangi.eta.EtaApp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.subagent.SubagentProfile
import io.github.mangi.eta.agent.subagent.SubagentProfileCatalog
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.data.model.SubagentSettings
import io.github.mangi.eta.data.model.enabledModel
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import io.github.mangi.eta.ui.app.LocalUiStyle
import io.github.mangi.eta.ui.components.EtaChoiceChip
import io.github.mangi.eta.ui.components.ListEmptyState
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.components.ModelBrandMark
import io.github.mangi.eta.ui.components.StatusError
import io.github.mangi.eta.ui.components.coui.CouiButton
import io.github.mangi.eta.ui.components.coui.CouiButtonRole
import io.github.mangi.eta.ui.components.coui.CouiChipTabs
import io.github.mangi.eta.ui.components.coui.CouiConfirmDialog
import io.github.mangi.eta.ui.components.coui.CouiDropdownPreference
import io.github.mangi.eta.ui.components.coui.CouiPageBottomSpacer
import io.github.mangi.eta.ui.components.coui.CouiPreference
import io.github.mangi.eta.ui.components.coui.CouiScaffoldPage
import io.github.mangi.eta.ui.components.coui.CouiSearchBarBox
import io.github.mangi.eta.ui.components.coui.CouiSwitchPreference
import io.github.mangi.eta.ui.components.coui.CouiTextField
import io.github.mangi.eta.ui.components.coui.CouiTopAppBarIconButton
import io.github.mangi.eta.ui.components.coui.rememberCouiDialogMaterial
import io.github.mangi.eta.ui.model.AgentModelOptionUi
import io.github.mangi.eta.ui.model.AgentModelPickerProjector
import io.github.mangi.eta.ui.model.filterGroups
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

private data class CustomSubagentDraft(
    val originalName: String? = null,
    val name: String = "",
    val description: String = "",
    val toolsText: String = "",
    val prompt: String = "",
)

@Composable
internal fun SubagentSettingsScreen(
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val agentPrefs = remember { Prefs.localAgentPreferences() }
    val defaultEnabled = Prefs.Keys.BOOLEAN_DEFAULTS.getValue(Prefs.Keys.AGENT_SUBAGENT_ENABLED)
    var subagentEnabled by remember(agentPrefs) {
        mutableStateOf(agentPrefs?.getBoolean(Prefs.Keys.AGENT_SUBAGENT_ENABLED, defaultEnabled) ?: defaultEnabled)
    }

    val providers by ProviderRepository.providersFlow().collectAsState(initial = emptyList())
    val primaryProviderId by RuntimeConfigRepository.selectedProviderIdFlow().collectAsState(initial = null)
    val primaryModelId by RuntimeConfigRepository.selectedModelIdFlow().collectAsState(initial = null)
    val subagentSettings by RuntimeConfigRepository.subagentSettingsFlow().collectAsState(initial = SubagentSettings())

    var customProfiles by remember { mutableStateOf<List<SubagentProfile>>(emptyList()) }
    fun reloadCustomProfiles() {
        scope.launch {
            customProfiles = withContext(Dispatchers.IO) {
                SubagentProfileCatalog.listCustom()
            }
        }
    }
    LaunchedEffect(Unit) {
        RuntimeConfigRepository.ensureDefaults(EtaApp.serviceInstance)
        reloadCustomProfiles()
    }

    val pickerState = remember(providers, primaryProviderId, primaryModelId) {
        AgentModelPickerProjector.project(providers, primaryProviderId, primaryModelId)
    }
    val resolvedSecondary = remember(providers, subagentSettings.modelId) {
        subagentSettings.modelId?.takeIf { it.isNotBlank() }?.let { id ->
            providers.enabledModel(id)
        }
    }
    val primaryModelName = pickerState.selectedModel?.displayName
    val secondarySummary = when {
        resolvedSecondary != null -> "${resolvedSecondary.provider.name} / ${resolvedSecondary.model.displayName}"
        !primaryModelName.isNullOrBlank() -> "跟随主模型 ($primaryModelName)"
        else -> "跟随主模型"
    }

    val effectiveCapabilities = remember(providers, resolvedSecondary, primaryProviderId, primaryModelId) {
        val target = resolvedSecondary ?: providers.enabledModel(primaryModelId)
        target?.let { (provider, model) ->
            RuntimeConfigRepository.reasoningCapabilities(provider, model)
        }
    }
    val effortOptions = remember(effectiveCapabilities) {
        val efforts = effectiveCapabilities?.selectableEfforts.orEmpty()
        listOf<ReasoningEffort?>(null) + efforts
    }
    val effortLabels = remember(effortOptions) {
        effortOptions.map { effort ->
            when (effort) {
                null -> "跟随模型默认"
                ReasoningEffort.DEFAULT -> "Auto"
                else -> effort.displayName
            }
        }
    }
    val selectedEffortIndex = remember(effortOptions, subagentSettings.reasoningEffort) {
        val current = subagentSettings.resolvedReasoningEffort
        effortOptions.indexOf(current).takeIf { it >= 0 } ?: 0
    }

    var showModelSheet by remember { mutableStateOf(false) }
    var inspectedProfile by remember { mutableStateOf<SubagentProfile?>(null) }
    var editorDraft by remember { mutableStateOf<CustomSubagentDraft?>(null) }
    var editorError by remember { mutableStateOf<String?>(null) }

    val onToggleSubagent: (Boolean) -> Unit = { checked ->
        val targetPrefs = agentPrefs
        if (targetPrefs != null && targetPrefs.edit().putBoolean(Prefs.Keys.AGENT_SUBAGENT_ENABLED, checked).commit()) {
            subagentEnabled = checked
            Prefs.reconcileAgentPreferences(EtaApp.serviceInstance)
            scope.launch {
                RuntimeConfigRepository.syncToRemotePreferences(EtaApp.serviceInstance)
            }
        }
    }
    val onEffortChange: (Int) -> Unit = { index ->
        val chosen = effortOptions.getOrNull(index)
        scope.launch {
            RuntimeConfigRepository.setSubagentSettings(
                subagentSettings.copy(reasoningEffort = chosen?.wireValue),
            )
        }
    }
    val onSaveDraft: (CustomSubagentDraft) -> Unit = { activeDraft ->
        scope.launch {
            val tools = activeDraft.toolsText
                .split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val saved = SubagentProfileCatalog.saveCustomProfile(
                        name = activeDraft.name,
                        description = activeDraft.description,
                        tools = tools,
                        prompt = activeDraft.prompt,
                    )
                    val oldName = activeDraft.originalName
                    if (oldName != null && oldName != saved.name) {
                        SubagentProfileCatalog.deleteCustomProfile(oldName)
                    }
                    saved
                }
            }
            result.fold(
                onSuccess = {
                    editorDraft = null
                    editorError = null
                    reloadCustomProfiles()
                },
                onFailure = { error ->
                    editorError = error.message ?: "保存失败"
                },
            )
        }
    }

    when (LocalUiStyle.current) {
        AppearanceUiStyle.COUI -> {
            CouiScaffoldPage(
                title = "Subagent",
                onBack = onBack,
                actions = {
                    CouiTopAppBarIconButton(
                        icon = CouiIcons.Add,
                        contentDescription = "新建 Subagent",
                        onClick = {
                            editorError = null
                            editorDraft = CustomSubagentDraft()
                        },
                    )
                },
                overlay = {
                    val dark = CouiTheme.isDark
                    Dialog(
                        visible = showModelSheet,
                        onDismissRequest = { showModelSheet = false },
                        dark = dark,
                        title = "Subagent 模型",
                        confirmAction = CouiDialogAction(
                            text = stringResource(R.string.action_close),
                            onClick = { showModelSheet = false },
                        ),
                        placement = CouiDialogPlacement.Bottom,
                        material = rememberCouiDialogMaterial(dark),
                        backdrop = rememberCouiWindowBackdrop(),
                    ) {
                        SubagentModelPickerContent(
                            pickerState = pickerState,
                            selectedSecondaryModelId = resolvedSecondary?.model?.id,
                            primaryModelName = primaryModelName,
                            onSelectModel = { modelId ->
                                showModelSheet = false
                                scope.launch {
                                    RuntimeConfigRepository.setSubagentSettings(
                                        subagentSettings.copy(modelId = modelId),
                                    )
                                }
                            },
                        )
                    }

                    val currentProfile = inspectedProfile
                    Dialog(
                        visible = currentProfile != null,
                        onDismissRequest = { inspectedProfile = null },
                        dark = dark,
                        title = currentProfile?.name.orEmpty(),
                        confirmAction = CouiDialogAction(
                            text = stringResource(R.string.action_close),
                            onClick = { inspectedProfile = null },
                        ),
                        placement = CouiDialogPlacement.Bottom,
                        material = rememberCouiDialogMaterial(dark),
                        backdrop = rememberCouiWindowBackdrop(),
                    ) {
                        if (currentProfile != null) {
                            SubagentProfileDetailContent(
                                profile = currentProfile,
                                onEdit = if (!currentProfile.builtin) {
                                    {
                                        val toolsText = currentProfile.explicitTools
                                            ?.takeUnless { it == setOf("*") }
                                            ?.joinToString(", ")
                                            .orEmpty()
                                        inspectedProfile = null
                                        editorError = null
                                        editorDraft = CustomSubagentDraft(
                                            originalName = currentProfile.name,
                                            name = currentProfile.name,
                                            description = currentProfile.summary,
                                            toolsText = toolsText,
                                            prompt = currentProfile.roleInstruction,
                                        )
                                    }
                                } else {
                                    null
                                },
                                onDelete = if (!currentProfile.builtin) {
                                    {
                                        val targetName = currentProfile.name
                                        inspectedProfile = null
                                        scope.launch {
                                            withContext(Dispatchers.IO) {
                                                SubagentProfileCatalog.deleteCustomProfile(targetName)
                                            }
                                            reloadCustomProfiles()
                                        }
                                    }
                                } else {
                                    null
                                },
                            )
                        }
                    }

                    val activeDraft = editorDraft
                    CouiConfirmDialog(
                        visible = activeDraft != null,
                        title = if (activeDraft?.originalName != null) "编辑 Subagent" else "新建 Subagent",
                        message = null,
                        confirmText = "保存",
                        onConfirm = { if (activeDraft != null) onSaveDraft(activeDraft) },
                        onDismissRequest = { editorDraft = null },
                    ) {
                        if (activeDraft != null) {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                CouiTextField(
                                    value = activeDraft.name,
                                    onValueChange = { editorDraft = activeDraft.copy(name = it) },
                                    label = "名称 (kebab-case，如 bill-analyzer)",
                                    singleLine = true,
                                )
                                CouiTextField(
                                    value = activeDraft.description,
                                    onValueChange = { editorDraft = activeDraft.copy(description = it) },
                                    label = "简介 (Description)",
                                    singleLine = true,
                                )
                                CouiTextField(
                                    value = activeDraft.toolsText,
                                    onValueChange = { editorDraft = activeDraft.copy(toolsText = it) },
                                    label = "可用工具 (逗号分隔，留空默认允许全部非 GUI 工具)",
                                    singleLine = true,
                                )
                                CouiTextField(
                                    value = activeDraft.prompt,
                                    onValueChange = { editorDraft = activeDraft.copy(prompt = it) },
                                    label = "角色指令 (System Prompt)",
                                    singleLine = false,
                                    minLines = 4,
                                    maxLines = 8,
                                )
                                editorError?.let { err ->
                                    Text(
                                        text = err,
                                        style = MiuixTheme.textStyles.footnote1,
                                        color = StatusError,
                                    )
                                }
                            }
                        }
                    }
                },
            ) {
                Group(title = "通用与模型") {
                    CouiSwitchPreference(
                        title = "启用 Subagent",
                        checked = subagentEnabled,
                        enabled = agentPrefs != null,
                        onCheckedChange = onToggleSubagent,
                    )
                    Divider()
                    CouiPreference(
                        title = "Subagent 模型",
                        summary = secondarySummary,
                        enabled = subagentEnabled,
                        onClick = { showModelSheet = true },
                    )
                    if (effortOptions.size > 1) {
                        Divider()
                        CouiDropdownPreference(
                            title = "思考强度",
                            items = effortLabels,
                            selectedIndex = selectedEffortIndex,
                            enabled = subagentEnabled,
                            onSelectedIndexChange = onEffortChange,
                        )
                    }
                }

                Group(title = "Built-in Subagents") {
                    SubagentProfile.BUILTIN_PROFILES.forEachIndexed { index, profile ->
                        CouiPreference(
                            title = profile.name,
                            summary = profile.summary,
                            onClick = { inspectedProfile = profile },
                        )
                        if (index < SubagentProfile.BUILTIN_PROFILES.lastIndex) {
                            Divider()
                        }
                    }
                }

                Group(title = "Custom Subagents") {
                    if (customProfiles.isEmpty()) {
                        ListEmptyState(
                            title = "暂无自定义 Subagent",
                            summary = "可在对话中直接让 Eta 创建专属 Subagent（保存在 agents/<name>.md），或点击右上角 + 手动新建",
                            icon = Icons.Rounded.Extension,
                            action = {
                                CouiButton(
                                    text = "新建 Subagent",
                                    role = CouiButtonRole.Primary,
                                    onClick = {
                                        editorError = null
                                        editorDraft = CustomSubagentDraft()
                                    },
                                )
                            },
                        )
                    } else {
                        customProfiles.forEachIndexed { index, profile ->
                            CouiPreference(
                                title = profile.name,
                                summary = profile.summary,
                                onClick = { inspectedProfile = profile },
                            )
                            if (index < customProfiles.lastIndex) {
                                Divider()
                            }
                        }
                    }
                }

                CouiPageBottomSpacer()
            }
        }

        AppearanceUiStyle.MIUIX -> {
            MiuixScaffoldPage(
                title = "Subagent",
                onBack = onBack,
                actions = {
                    IconButton(
                        onClick = {
                            editorError = null
                            editorDraft = CustomSubagentDraft()
                        },
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Add,
                            contentDescription = "新建 Subagent",
                        )
                    }
                },
                overlay = {
                    OverlayBottomSheet(
                        show = showModelSheet,
                        title = "Subagent 模型",
                        onDismissRequest = { showModelSheet = false },
                        insideMargin = DpSize(0.dp, 0.dp),
                    ) {
                        SubagentModelPickerContent(
                            pickerState = pickerState,
                            selectedSecondaryModelId = resolvedSecondary?.model?.id,
                            primaryModelName = primaryModelName,
                            onSelectModel = { modelId ->
                                showModelSheet = false
                                scope.launch {
                                    RuntimeConfigRepository.setSubagentSettings(
                                        subagentSettings.copy(modelId = modelId),
                                    )
                                }
                            },
                        )
                    }

                    val currentProfile = inspectedProfile
                    OverlayBottomSheet(
                        show = currentProfile != null,
                        title = currentProfile?.name.orEmpty(),
                        onDismissRequest = { inspectedProfile = null },
                    ) {
                        if (currentProfile != null) {
                            SubagentProfileDetailContent(
                                profile = currentProfile,
                                onEdit = if (!currentProfile.builtin) {
                                    {
                                        val toolsText = currentProfile.explicitTools
                                            ?.takeUnless { it == setOf("*") }
                                            ?.joinToString(", ")
                                            .orEmpty()
                                        inspectedProfile = null
                                        editorError = null
                                        editorDraft = CustomSubagentDraft(
                                            originalName = currentProfile.name,
                                            name = currentProfile.name,
                                            description = currentProfile.summary,
                                            toolsText = toolsText,
                                            prompt = currentProfile.roleInstruction,
                                        )
                                    }
                                } else {
                                    null
                                },
                                onDelete = if (!currentProfile.builtin) {
                                    {
                                        val targetName = currentProfile.name
                                        inspectedProfile = null
                                        scope.launch {
                                            withContext(Dispatchers.IO) {
                                                SubagentProfileCatalog.deleteCustomProfile(targetName)
                                            }
                                            reloadCustomProfiles()
                                        }
                                    }
                                } else {
                                    null
                                },
                            )
                        }
                    }

                    val activeDraft = editorDraft
                    WindowDialog(
                        show = activeDraft != null,
                        title = if (activeDraft?.originalName != null) "编辑 Subagent" else "新建 Subagent",
                        onDismissRequest = { editorDraft = null },
                    ) {
                        if (activeDraft != null) {
                            Column(modifier = Modifier.fillMaxWidth().heightIn(max = 540.dp)) {
                                Column(
                                    verticalArrangement = Arrangement.spacedBy(12.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f, fill = false)
                                        .verticalScroll(rememberScrollState()),
                                ) {
                                    TextField(
                                        value = activeDraft.name,
                                        onValueChange = { editorDraft = activeDraft.copy(name = it) },
                                        label = "名称 (kebab-case，如 bill-analyzer)",
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    TextField(
                                        value = activeDraft.description,
                                        onValueChange = { editorDraft = activeDraft.copy(description = it) },
                                        label = "简介 (Description)",
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    TextField(
                                        value = activeDraft.toolsText,
                                        onValueChange = { editorDraft = activeDraft.copy(toolsText = it) },
                                        label = "可用工具 (逗号分隔，留空默认允许全部非 GUI 工具)",
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    TextField(
                                        value = activeDraft.prompt,
                                        onValueChange = { editorDraft = activeDraft.copy(prompt = it) },
                                        label = "角色指令 (System Prompt)",
                                        minLines = 4,
                                        maxLines = 8,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    editorError?.let { err ->
                                        Text(
                                            text = err,
                                            style = MiuixTheme.textStyles.footnote1,
                                            color = StatusError,
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(16.dp))
                                MiuixDialogActions(
                                    confirmText = "保存",
                                    onCancel = { editorDraft = null },
                                    onConfirm = { onSaveDraft(activeDraft) },
                                )
                            }
                        }
                    }
                },
            ) {
                item(key = "config_group") {
                    SmallTitle("通用与模型")
                    Card(
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 12.dp),
                    ) {
                        SwitchPreference(
                            title = "启用 Subagent",
                            checked = subagentEnabled,
                            enabled = agentPrefs != null,
                            onCheckedChange = onToggleSubagent,
                        )
                        ArrowPreference(
                            title = "Subagent 模型",
                            summary = secondarySummary,
                            enabled = subagentEnabled,
                            onClick = { showModelSheet = true },
                        )
                        if (effortOptions.size > 1) {
                            OverlayDropdownPreference(
                                title = "思考强度",
                                items = effortLabels,
                                selectedIndex = selectedEffortIndex,
                                enabled = subagentEnabled,
                                onSelectedIndexChange = onEffortChange,
                            )
                        }
                    }
                }

                item(key = "builtin_group") {
                    SmallTitle("Built-in Subagents")
                    Card(
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 12.dp),
                    ) {
                        SubagentProfile.BUILTIN_PROFILES.forEach { profile ->
                            ArrowPreference(
                                title = profile.name,
                                summary = profile.summary,
                                onClick = { inspectedProfile = profile },
                            )
                        }
                    }
                }

                item(key = "custom_group") {
                    SmallTitle("Custom Subagents")
                    Card(
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 12.dp),
                    ) {
                        if (customProfiles.isEmpty()) {
                            ListEmptyState(
                                title = "暂无自定义 Subagent",
                                summary = "可在对话中直接让 Eta 创建专属 Subagent（保存在 agents/<name>.md），或点击右上角 + 手动新建",
                                icon = Icons.Rounded.Extension,
                                action = {
                                    TextButton(
                                        text = "新建 Subagent",
                                        onClick = {
                                            editorError = null
                                            editorDraft = CustomSubagentDraft()
                                        },
                                    )
                                },
                            )
                        } else {
                            customProfiles.forEach { profile ->
                                ArrowPreference(
                                    title = profile.name,
                                    summary = profile.summary,
                                    onClick = { inspectedProfile = profile },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SubagentModelPickerContent(
    pickerState: io.github.mangi.eta.ui.model.AgentModelPickerUiState,
    selectedSecondaryModelId: String?,
    primaryModelName: String?,
    onSelectModel: (String?) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var providerFilter by remember { mutableStateOf<String?>(null) }
    val filteredGroups = remember(pickerState.providerGroups, query, providerFilter) {
        pickerState.filterGroups(query)
            .filter { providerFilter == null || it.providerId == providerFilter }
    }
    val density = LocalDensity.current
    val sheetHeight = with(density) { LocalWindowInfo.current.containerSize.height.toDp() } * 0.68f
    val isCoui = LocalUiStyle.current == AppearanceUiStyle.COUI

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(sheetHeight)
            .then(if (isCoui) Modifier else Modifier.navigationBarsPadding()),
    ) {
        when (LocalUiStyle.current) {
            AppearanceUiStyle.COUI -> {
                CouiSearchBarBox(
                    query = query,
                    onQueryChange = { query = it },
                    placeholder = "搜索模型或服务商",
                    backgroundColor = Color.Transparent,
                )
                if (pickerState.providerGroups.size > 1) {
                    val tabItems = remember(pickerState.providerGroups) {
                        listOf("" to "全部") + pickerState.providerGroups.map { it.providerId to it.providerName }
                    }
                    CouiChipTabs(
                        items = tabItems,
                        selectedKey = providerFilter.orEmpty(),
                        onSelectedKeyChange = { key -> providerFilter = key.takeIf { it.isNotEmpty() } },
                        backgroundColor = Color.Transparent,
                    )
                }
            }
            AppearanceUiStyle.MIUIX -> {
                InputField(
                    query = query,
                    onQueryChange = { query = it },
                    onSearch = {},
                    expanded = false,
                    onExpandedChange = {},
                    label = "搜索模型或服务商",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
                if (pickerState.providerGroups.size > 1) {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        item(key = "filter:all") {
                            EtaChoiceChip(
                                text = "全部",
                                selected = providerFilter == null,
                                onClick = { providerFilter = null },
                            )
                        }
                        items(
                            items = pickerState.providerGroups,
                            key = { "filter:${it.providerId}" },
                        ) { group ->
                            EtaChoiceChip(
                                text = group.providerName,
                                selected = providerFilter == group.providerId,
                                onClick = {
                                    providerFilter = if (providerFilter == group.providerId) null else group.providerId
                                },
                            )
                        }
                    }
                }
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            if (query.isBlank() && providerFilter == null) {
                item(key = "inherit_primary") {
                    SubagentModelRow(
                        title = "跟随主模型",
                        subtitle = primaryModelName?.let { "当前：$it" } ?: "默认继承会话正在使用的主模型",
                        modelId = pickerState.selectedModel?.modelId,
                        sourceType = pickerState.selectedModel?.providerSourceType,
                        selected = selectedSecondaryModelId == null,
                        onClick = { onSelectModel(null) },
                    )
                }
            }
            filteredGroups.forEach { group ->
                if (providerFilter == null) {
                    item(key = "header:${group.providerId}") {
                        Text(
                            text = group.providerName,
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 4.dp),
                        )
                    }
                }
                items(
                    items = group.models,
                    key = { model -> "model:${model.providerId}:${model.id}" },
                ) { model ->
                    SubagentModelOptionRow(
                        model = model,
                        selected = model.id == selectedSecondaryModelId,
                        onClick = { onSelectModel(model.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SubagentModelOptionRow(
    model: AgentModelOptionUi,
    selected: Boolean,
    onClick: () -> Unit,
) {
    SubagentModelRow(
        title = model.displayName,
        subtitle = model.modelId.takeIf { !it.equals(model.displayName, ignoreCase = true) },
        modelId = model.modelId,
        sourceType = model.providerSourceType,
        selected = selected,
        onClick = onClick,
    )
}

@Composable
private fun SubagentModelRow(
    title: String,
    subtitle: String?,
    modelId: String?,
    sourceType: String?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .squircleSurface(
                color = if (selected) {
                    MiuixTheme.colorScheme.tertiaryContainer
                } else {
                    MiuixTheme.colorScheme.surface.copy(alpha = 0f)
                },
                cornerRadius = 14.dp,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ModelBrandMark(
            modelId = modelId,
            sourceType = sourceType,
            size = 20.dp,
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MiuixTheme.textStyles.body1,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) MiuixTheme.colorScheme.onTertiaryContainer else MiuixTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (selected) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = null,
                tint = MiuixTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SubagentProfileDetailContent(
    profile: SubagentProfile,
    onEdit: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
) {
    val uiStyle = LocalUiStyle.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (uiStyle == AppearanceUiStyle.COUI) {
                    Modifier.padding(horizontal = CouiDialogDefaults.contentPadding, vertical = 8.dp)
                } else {
                    Modifier
                        .verticalScroll(rememberScrollState())
                        .navigationBarsPadding()
                        .padding(bottom = 24.dp)
                },
            ),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = profile.summary,
            style = MiuixTheme.textStyles.body1,
            fontWeight = FontWeight.Medium,
            color = MiuixTheme.colorScheme.onSurface,
        )
        Text(
            text = if (profile.builtin) profile.whenToUse else profile.roleInstruction,
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Tools",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                profile.toolHighlights.forEach { toolName ->
                    Box(
                        modifier = Modifier
                            .squircleSurface(
                                color = MiuixTheme.colorScheme.surfaceContainer,
                                cornerRadius = 8.dp,
                            )
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                    ) {
                        Text(
                            text = toolName,
                            style = MiuixTheme.textStyles.footnote2.copy(fontFamily = FontFamily.Monospace),
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            }
        }
        if (onEdit != null || onDelete != null) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (onDelete != null) {
                    if (uiStyle == AppearanceUiStyle.COUI) {
                        CouiButton(
                            text = "删除",
                            onClick = onDelete,
                            role = CouiButtonRole.Destructive,
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        TextButton(
                            text = "删除",
                            onClick = onDelete,
                            colors = ButtonDefaults.textButtonColors(
                                textColor = StatusError,
                            ),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                if (onEdit != null) {
                    if (uiStyle == AppearanceUiStyle.COUI) {
                        CouiButton(
                            text = "编辑",
                            onClick = onEdit,
                            role = CouiButtonRole.Primary,
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        TextButton(
                            text = "编辑",
                            onClick = onEdit,
                            colors = ButtonDefaults.textButtonColorsPrimary(),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}
