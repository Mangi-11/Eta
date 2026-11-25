package io.github.mangi.eta.ui.screens.subagent

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.JoinLeft
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.EtaApp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.subagent.SubagentProfile
import io.github.mangi.eta.agent.subagent.SubagentProfileCatalog
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.data.model.SubagentSettings
import io.github.mangi.eta.data.model.enabledModel
import io.github.mangi.eta.data.provider.BuiltinProviders
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import io.github.mangi.eta.ui.app.LocalAppearanceSettings
import io.github.mangi.eta.ui.components.DialogActions
import io.github.mangi.eta.ui.components.ListEmptyState
import io.github.mangi.eta.ui.components.OptionalModelPickerContent
import io.github.mangi.eta.ui.components.ScaffoldPage
import io.github.mangi.eta.ui.components.StatusError
import io.github.mangi.eta.ui.components.TopBarActionButton
import io.github.mangi.eta.ui.components.currentGlassOverlayBackdrop
import io.github.mangi.eta.ui.model.AgentModelPickerProjector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.glass.GlassBottomSheet
import top.yukonga.miuix.kmp.glass.GlassDialog
import top.yukonga.miuix.kmp.glass.GlassDropdownPreference
import top.yukonga.miuix.kmp.glass.GlassTextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.Add
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme

private class CustomSubagentDraft(
    val originalName: String? = null,
    name: String = "",
    description: String = "",
    whenToUse: String = "",
    toolsText: String = "",
    val disallowedTools: List<String> = emptyList(),
    val overrideBuiltin: Boolean = false,
    prompt: String = "",
) {
    val name = TextFieldState(name)
    val description = TextFieldState(description)
    val whenToUse = TextFieldState(whenToUse)
    val toolsText = TextFieldState(toolsText)
    val prompt = TextFieldState(prompt)
}

@Composable
internal fun SubagentSettingsScreen(
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val resources = LocalResources.current
    val agentPrefs = remember { Prefs.localAgentPreferences() }
    val defaultEnabled = Prefs.Keys.BOOLEAN_DEFAULTS.getValue(Prefs.Keys.AGENT_SUBAGENT_ENABLED)
    var subagentEnabled by remember(agentPrefs) {
        mutableStateOf(agentPrefs?.getBoolean(Prefs.Keys.AGENT_SUBAGENT_ENABLED, defaultEnabled) ?: defaultEnabled)
    }

    val providers by ProviderRepository.providersFlow().collectAsStateWithLifecycle(initialValue = emptyList())
    val primaryProviderId by RuntimeConfigRepository.selectedProviderIdFlow().collectAsStateWithLifecycle(initialValue = null)
    val primaryModelId by RuntimeConfigRepository.selectedModelIdFlow().collectAsStateWithLifecycle(initialValue = null)
    val subagentSettings by RuntimeConfigRepository.subagentSettingsFlow().collectAsStateWithLifecycle(initialValue = SubagentSettings())

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
        resolvedSecondary != null -> {
            val providerDisplayName = BuiltinProviders.defaultNameRes(
                resolvedSecondary.provider.id,
                resolvedSecondary.provider.name,
            )?.let { stringResource(it) } ?: resolvedSecondary.provider.name
            "$providerDisplayName / ${resolvedSecondary.model.displayName}"
        }
        !primaryModelName.isNullOrBlank() -> stringResource(R.string.subagent_settings_follow_primary_named, primaryModelName)
        else -> stringResource(R.string.subagent_settings_follow_primary)
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
    val followDefaultEffortLabel = stringResource(R.string.subagent_settings_reasoning_follow_default)
    val autoEffortLabel = stringResource(R.string.subagent_i18n_reasoning_auto)
    val effortLabels = remember(effortOptions, followDefaultEffortLabel, autoEffortLabel) {
        effortOptions.map { effort ->
            when (effort) {
                null -> followDefaultEffortLabel
                ReasoningEffort.DEFAULT -> autoEffortLabel
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
    var retainedProfile by remember { mutableStateOf<SubagentProfile?>(null) }
    if (inspectedProfile != null) retainedProfile = inspectedProfile
    var editorDraft by remember { mutableStateOf<CustomSubagentDraft?>(null) }
    var retainedDraft by remember { mutableStateOf<CustomSubagentDraft?>(null) }
    if (editorDraft != null) retainedDraft = editorDraft
    var editorError by remember { mutableStateOf<String?>(null) }
    var deleteTargetProfile by remember { mutableStateOf<SubagentProfile?>(null) }
    var retainedDeleteTarget by remember { mutableStateOf<SubagentProfile?>(null) }
    if (deleteTargetProfile != null) retainedDeleteTarget = deleteTargetProfile

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
    val onSelectModel: (String?) -> Unit = { modelId ->
        showModelSheet = false
        val target = (modelId?.takeIf { it.isNotBlank() }?.let { providers.enabledModel(it) })
            ?: providers.enabledModel(primaryModelId)
        val newCapabilities = target?.let { (provider, model) ->
            RuntimeConfigRepository.reasoningCapabilities(provider, model)
        }
        val currentEffort = subagentSettings.resolvedReasoningEffort
        val compatibleEffort = if (
            currentEffort != null &&
            newCapabilities?.selectableEfforts?.contains(currentEffort) == true
        ) {
            currentEffort.wireValue
        } else {
            null
        }
        scope.launch {
            RuntimeConfigRepository.setSubagentSettings(
                subagentSettings.copy(
                    modelId = modelId,
                    reasoningEffort = compatibleEffort,
                ),
            )
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
    val onStartEditProfile: (SubagentProfile) -> Unit = { profile ->
        val toolsText = profile.explicitTools
            ?.takeUnless { it == setOf("*") }
            ?.joinToString(", ")
            .orEmpty()
        inspectedProfile = null
        editorError = null
        editorDraft = CustomSubagentDraft(
            originalName = profile.name,
            name = profile.name,
            description = profile.summary,
            whenToUse = profile.whenToUse.takeUnless { it == profile.summary }.orEmpty(),
            toolsText = toolsText,
            disallowedTools = profile.disallowedTools.toList(),
            overrideBuiltin = profile.override,
            prompt = profile.roleInstruction,
        )
    }
    val onRequestDeleteProfile: (SubagentProfile) -> Unit = { profile ->
        inspectedProfile = null
        deleteTargetProfile = profile
    }
    val onConfirmDeleteProfile: (SubagentProfile) -> Unit = { profile ->
        deleteTargetProfile = null
        scope.launch {
            withContext(Dispatchers.IO) {
                SubagentProfileCatalog.deleteCustomProfile(profile.name)
            }
            reloadCustomProfiles()
        }
    }
    val onSaveDraft: (CustomSubagentDraft) -> Unit = { activeDraft ->
        val name = activeDraft.name.text.toString()
        val description = activeDraft.description.text.toString()
        val whenToUse = activeDraft.whenToUse.text.toString()
        val prompt = activeDraft.prompt.text.toString()
        val tools = activeDraft.toolsText.text.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val normalizedNewName = name.trim().lowercase()
                    val oldName = activeDraft.originalName
                    if (
                        normalizedNewName != oldName &&
                        SubagentProfileCatalog.existsCustomProfile(normalizedNewName)
                    ) {
                        throw IllegalArgumentException(
                            resources.getString(R.string.subagent_settings_name_exists, normalizedNewName),
                        )
                    }
                    val saved = SubagentProfileCatalog.saveCustomProfile(
                        name = name,
                        description = description,
                        whenToUse = whenToUse,
                        tools = tools,
                        disallowedTools = activeDraft.disallowedTools,
                        overrideBuiltin = activeDraft.overrideBuiltin,
                        prompt = prompt,
                    )
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
                    editorError = error.message ?: resources.getString(R.string.subagent_settings_save_failed)
                },
            )
        }
    }

    val followPrimaryTitle = stringResource(R.string.subagent_settings_follow_primary)
    val followPrimarySubtitle = primaryModelName?.let {
        stringResource(R.string.subagent_settings_follow_primary_current, it)
    } ?: stringResource(R.string.subagent_settings_follow_primary_hint)

    ScaffoldPage(
        title = stringResource(R.string.ui_misc_route_subagents),
        onBack = onBack,
        actions = {
            TopBarActionButton(
                icon = MiuixIcons.Glass.Add,
                contentDescription = stringResource(R.string.subagent_settings_create),
                onClick = {
                    editorError = null
                    editorDraft = CustomSubagentDraft()
                },
            )
        },
        overlay = {
            val overlayBackdrop = currentGlassOverlayBackdrop()
            GlassBottomSheet(
                show = showModelSheet,
                backdrop = overlayBackdrop,
                progressiveBlur = false,
                title = stringResource(R.string.subagent_settings_model),
                onDismissRequest = { showModelSheet = false },
                insideMargin = DpSize(0.dp, 0.dp),
            ) { _ ->
                OptionalModelPickerContent(
                    pickerState = pickerState,
                    selectedSecondaryModelId = resolvedSecondary?.model?.id,
                    followTitle = followPrimaryTitle,
                    followSubtitle = followPrimarySubtitle,
                    onSelectModel = onSelectModel,
                )
            }

            val currentProfile = inspectedProfile ?: retainedProfile
            GlassBottomSheet(
                show = inspectedProfile != null,
                backdrop = overlayBackdrop,
                progressiveBlur = LocalAppearanceSettings.current.progressiveBlurEnabled,
                title = currentProfile?.name.orEmpty(),
                onDismissRequest = { inspectedProfile = null },
                onDismissFinished = { if (inspectedProfile == null) retainedProfile = null },
            ) { innerPadding ->
                if (currentProfile != null) {
                    SubagentProfileDetailContent(
                        profile = currentProfile,
                        contentPadding = innerPadding,
                        onEdit = if (!currentProfile.builtin) {
                            { onStartEditProfile(currentProfile) }
                        } else {
                            null
                        },
                        onDelete = if (!currentProfile.builtin) {
                            { onRequestDeleteProfile(currentProfile) }
                        } else {
                            null
                        },
                    )
                }
            }

            val activeDraft = editorDraft ?: retainedDraft
            GlassDialog(
                show = editorDraft != null,
                backdrop = overlayBackdrop,
                title = stringResource(
                    if (activeDraft?.originalName != null) {
                        R.string.subagent_settings_edit
                    } else {
                        R.string.subagent_settings_create
                    },
                ),
                onDismissRequest = { editorDraft = null },
                onDismissFinished = { if (editorDraft == null) retainedDraft = null },
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
                            GlassTextField(
                                state = activeDraft.name,
                                lineLimits = TextFieldLineLimits.SingleLine,
                                label = stringResource(R.string.subagent_settings_field_name),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            GlassTextField(
                                state = activeDraft.description,
                                lineLimits = TextFieldLineLimits.SingleLine,
                                label = stringResource(R.string.subagent_settings_field_description),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            GlassTextField(
                                state = activeDraft.whenToUse,
                                lineLimits = TextFieldLineLimits.SingleLine,
                                label = stringResource(R.string.subagent_settings_field_when_to_use),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            GlassTextField(
                                state = activeDraft.toolsText,
                                lineLimits = TextFieldLineLimits.SingleLine,
                                label = stringResource(R.string.subagent_settings_field_tools),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            GlassTextField(
                                state = activeDraft.prompt,
                                lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = 4, maxHeightInLines = 8),
                                label = stringResource(R.string.subagent_settings_field_prompt),
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
                        DialogActions(
                            confirmText = stringResource(R.string.action_save),
                            onCancel = { editorDraft = null },
                            onConfirm = { editorDraft?.let(onSaveDraft) },
                        )
                    }
                }
            }

            val pendingDelete = deleteTargetProfile ?: retainedDeleteTarget
            GlassDialog(
                show = deleteTargetProfile != null,
                backdrop = overlayBackdrop,
                title = stringResource(R.string.subagent_settings_delete_title),
                onDismissRequest = { deleteTargetProfile = null },
                onDismissFinished = { if (deleteTargetProfile == null) retainedDeleteTarget = null },
            ) {
                if (pendingDelete != null) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = stringResource(
                                R.string.subagent_settings_delete_confirm,
                                pendingDelete.name,
                            ),
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        Spacer(modifier = Modifier.height(20.dp))
                        DialogActions(
                            confirmText = stringResource(R.string.action_delete),
                            destructive = true,
                            onCancel = { deleteTargetProfile = null },
                            onConfirm = { deleteTargetProfile?.let(onConfirmDeleteProfile) },
                        )
                    }
                }
            }
        },
    ) {
        item(key = "config_group") {
            SmallTitle(stringResource(R.string.subagent_settings_group_general))
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                SwitchPreference(
                    title = stringResource(R.string.subagent_settings_enable),
                    checked = subagentEnabled,
                    enabled = agentPrefs != null,
                    onCheckedChange = onToggleSubagent,
                )
                ArrowPreference(
                    title = stringResource(R.string.subagent_settings_model),
                    summary = secondarySummary,
                    enabled = subagentEnabled,
                    onClick = { showModelSheet = true },
                )
                if (effortOptions.size > 1) {
                    GlassDropdownPreference(
                        title = stringResource(R.string.subagent_settings_reasoning),
                        items = effortLabels,
                        selectedIndex = selectedEffortIndex,
                        backdrop = currentGlassOverlayBackdrop(),
                        enabled = subagentEnabled,
                        onSelectedIndexChange = onEffortChange,
                    )
                }
            }
        }

        item(key = "builtin_group") {
            SmallTitle(stringResource(R.string.subagent_settings_group_builtin))
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                SubagentProfile.BUILTIN_PROFILES.forEach { profile ->
                    ArrowPreference(
                        title = profile.name,
                        summary = profile.summaryRes?.let { stringResource(it) } ?: profile.summary,
                        onClick = { inspectedProfile = profile },
                    )
                }
            }
        }

        item(key = "custom_group") {
            SmallTitle(stringResource(R.string.subagent_settings_group_custom))
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                if (customProfiles.isEmpty()) {
                    ListEmptyState(
                        title = stringResource(R.string.subagent_settings_empty_title),
                        summary = stringResource(R.string.subagent_settings_empty_summary),
                        icon = Icons.Rounded.JoinLeft,
                        action = {
                            TextButton(
                                text = stringResource(R.string.subagent_settings_create),
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
                            summary = profile.summaryRes?.let { stringResource(it) } ?: profile.summary,
                            onClick = { inspectedProfile = profile },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SubagentProfileDetailContent(
    profile: SubagentProfile,
    contentPadding: PaddingValues,
    onEdit: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(contentPadding)
            .navigationBarsPadding()
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = profile.summaryRes?.let { stringResource(it) } ?: profile.summary,
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
                text = stringResource(R.string.subagent_settings_tools_label),
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
                    TextButton(
                        text = stringResource(R.string.action_delete),
                        onClick = onDelete,
                        colors = ButtonDefaults.textButtonColors(
                            textColor = StatusError,
                        ),
                        modifier = Modifier.weight(1f),
                    )
                }
                if (onEdit != null) {
                    TextButton(
                        text = stringResource(R.string.action_edit),
                        onClick = onEdit,
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}
