package io.github.mangi.eta.ui.screens.settings

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.AccessibilityNew
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BrightnessHigh
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Compress
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.ImportContacts
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.JoinLeft
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.SportsBar
import androidx.compose.material.icons.rounded.SupportAgent
import androidx.compose.material.icons.rounded.SwipeUp
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.TravelExplore
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.mangi.eta.BuildConfig
import io.github.mangi.eta.EtaApp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.accessibility.AccessibilityProtectionClient
import io.github.mangi.eta.agent.accessibility.AgentAccessibilityService
import io.github.mangi.eta.agent.voice.AssistantRoleHelper
import io.github.mangi.eta.config.PowerAssistantTarget
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.data.model.enabledModel
import io.github.mangi.eta.data.provider.BuiltinProviders
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import io.github.mangi.eta.data.update.AppLatestRelease
import io.github.mangi.eta.data.update.AppUpdateChecker
import io.github.mangi.eta.ui.state.EnhancementSettingsHistory
import io.github.mangi.eta.ui.components.DialogActions
import io.github.mangi.eta.ui.components.LanguagePreference
import io.github.mangi.eta.ui.components.ScaffoldPage
import io.github.mangi.eta.ui.components.currentGlassOverlayBackdrop
import io.github.mangi.eta.ui.model.AgentModelPickerProjector
import io.github.mangi.eta.ui.navigation.AppRoute
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.glass.GlassDialog
import top.yukonga.miuix.kmp.glass.GlassDropdownPreference
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.SecurityStatus
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.runtime.setValue

@Composable
private fun SettingsGroupCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp),
    ) {
        content()
    }
}

@Composable
private fun SettingsIcon(
    icon: ImageVector,
    enabled: Boolean = true,
) {
    Icon(
        imageVector = icon,
        contentDescription = null,
        modifier = Modifier
            .padding(end = 8.dp)
            .size(24.dp),
        tint = if (enabled) {
            MiuixTheme.colorScheme.onBackground
        } else {
            MiuixTheme.colorScheme.disabledOnSecondaryVariant
        },
    )
}

private data class SettingsSystemStatus(
    val accessibilityGranted: Boolean = false,
    val accessibilityProtectionEnabled: Boolean = false,
)

@Volatile
private var cachedSettingsSystemStatus: SettingsSystemStatus? = null

/**
 * 模块配置界面。
 *
 * 开关默认值由 [Prefs.Keys.BOOLEAN_DEFAULTS] 统一定义。Eta Runtime 自己消费的开关写入
 * App 本地配置；仅 Hook 消费的开关通过 RemotePreferences 提交到 LSPosed。
 */
@Composable
internal fun SettingsScreen(
    context: Context,
    onNavigate: (AppRoute) -> Unit,
    onBack: () -> Unit,
) {
    SettingsPageContent(context = context, onNavigate = onNavigate, onBack = onBack)
}

@Composable
private fun SettingsPageContent(
    context: Context,
    onNavigate: (AppRoute) -> Unit,
    onBack: () -> Unit,
) {
    val coroutineScope = rememberCoroutineScope()
    val enhancementHistory = remember(context.applicationContext) { EnhancementSettingsHistory(context) }
    var hasConnectedFramework by remember { mutableStateOf(enhancementHistory.hasConnected) }

    // 无障碍状态走异步查询，避免首帧进入设置页时在主线程触发同步 Binder IPC。
    var systemStatus by remember {
        mutableStateOf(cachedSettingsSystemStatus ?: SettingsSystemStatus())
    }
    val accessibilityGranted = systemStatus.accessibilityGranted
    val accessibilityProtectionEnabled = systemStatus.accessibilityProtectionEnabled
    var accessibilityProtectionPending by remember { mutableStateOf(false) }

    // 关于组：版本信息与更新检查。结果对话框在列表外渲染，状态需要页面级 owner。
    val appVersionName = BuildConfig.VERSION_NAME
    val appVersionSummary = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
    val openUrl: (String) -> Unit = { url ->
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }
    var checkingUpdate by remember { mutableStateOf(false) }
    var availableUpdate by remember { mutableStateOf<AppLatestRelease?>(null) }
    var retainedUpdate by remember { mutableStateOf<AppLatestRelease?>(null) }
    if (availableUpdate != null) retainedUpdate = availableUpdate
    val checkForUpdate: () -> Unit = {
        if (!checkingUpdate) {
            checkingUpdate = true
            coroutineScope.launch {
                val release = runCatching {
                    withContext(Dispatchers.IO) { AppUpdateChecker.fetchLatest() }
                }.getOrNull()
                checkingUpdate = false
                when {
                    release == null -> Toast.makeText(
                        context.applicationContext,
                        context.getString(R.string.ui_update_check_failed),
                        Toast.LENGTH_SHORT,
                    ).show()
                    AppUpdateChecker.isNewer(release.version, appVersionName) -> availableUpdate = release
                    else -> Toast.makeText(
                        context.applicationContext,
                        context.getString(R.string.ui_update_already_latest),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }
    }

    // Provider / Model 选中状态展示
    val initialProviders = remember { ProviderRepository.cachedProviders() }
    val initialProviderId = remember { RuntimeConfigRepository.cachedSelectedProviderId() }
    val initialModelId = remember { RuntimeConfigRepository.cachedSelectedModelId() }
    val providers by ProviderRepository.providersFlow().collectAsStateWithLifecycle(initialValue = initialProviders)
    val selectedProviderId by RuntimeConfigRepository.selectedProviderIdFlow()
        .collectAsStateWithLifecycle(initialValue = initialProviderId)
    val selectedModelId by RuntimeConfigRepository.selectedModelIdFlow()
        .collectAsStateWithLifecycle(initialValue = initialModelId)
    val selectedProvider = remember(providers, selectedProviderId) {
        providers.find { it.id == selectedProviderId }
    }
    val selectedModel = remember(selectedProvider, selectedModelId) {
        selectedProvider?.models?.find { it.id == selectedModelId }
    }
    val providerSummary = selectedProvider?.let { provider ->
        val providerDisplayName = BuiltinProviders.defaultNameRes(provider.id, provider.name)?.let { stringResource(it) } ?: provider.name
        "$providerDisplayName / ${selectedModel?.displayName ?: stringResource(R.string.settings_model_not_selected)}"
    } ?: stringResource(R.string.settings_not_configured)

    // prefs 绑定到 XposedService：service 到达时切换到 RemotePreferences（跨进程提交到
    // LSPosed 数据库）；未就绪时保持 null，UI 禁止修改。
    var prefs by remember {
        mutableStateOf(Prefs.cachedRemotePreferencesForUi(EtaApp.serviceInstance))
    }
    val agentPrefs = remember { Prefs.localAgentPreferences() }
    val pickerState = remember(providers, selectedProviderId, selectedModelId) {
        AgentModelPickerProjector.project(providers, selectedProviderId, selectedModelId)
    }
    val effectivePrimary = remember(providers, pickerState.selectedModel?.id) {
        providers.enabledModel(pickerState.selectedModel?.id)
    }
    val effectiveCapabilities = remember(effectivePrimary) {
        effectivePrimary?.let { (provider, model) ->
            RuntimeConfigRepository.reasoningCapabilities(provider, model)
        }
    }
    val effortOptions = remember(effectiveCapabilities) {
        effectiveCapabilities?.selectableEfforts
            ?.filter { it != ReasoningEffort.OFF }
            .orEmpty()
    }
    val providerDefaultLabel = stringResource(R.string.conversation_title_settings_reasoning_provider_default)
    val effortLabels = remember(effortOptions, providerDefaultLabel) {
        effortOptions.map { effort ->
            when (effort) {
                ReasoningEffort.DEFAULT -> providerDefaultLabel
                else -> effort.displayName
            }
        }
    }
    val defaultThinkingKey = Prefs.Keys.AGENT_THINKING_ENABLED
    val defaultThinkingFallback = Prefs.Keys.BOOLEAN_DEFAULTS.getValue(defaultThinkingKey)
    var defaultThinkingEnabled by remember(agentPrefs) {
        mutableStateOf(
            agentPrefs?.getBoolean(defaultThinkingKey, defaultThinkingFallback)
                ?: enhancementHistory.checked(defaultThinkingKey, defaultThinkingFallback),
        )
    }
    var savedDefaultEffort by remember(agentPrefs) {
        mutableStateOf(Prefs.defaultReasoningEffort(agentPrefs))
    }
    DisposableEffect(agentPrefs) {
        val targetPrefs = agentPrefs ?: return@DisposableEffect onDispose {}
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { changedPrefs, key ->
            when (key) {
                defaultThinkingKey -> {
                    defaultThinkingEnabled = changedPrefs.getBoolean(defaultThinkingKey, defaultThinkingFallback)
                }
                Prefs.Keys.AGENT_DEFAULT_REASONING_EFFORT -> {
                    savedDefaultEffort = Prefs.defaultReasoningEffort(changedPrefs)
                }
            }
        }
        targetPrefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { targetPrefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val effectiveSelectedEffort = remember(effectiveCapabilities, savedDefaultEffort) {
        effectiveCapabilities?.normalize(savedDefaultEffort) ?: ReasoningEffort.DEFAULT
    }
    val selectedEffortIndex = remember(effortOptions, effectiveSelectedEffort) {
        effortOptions.indexOf(effectiveSelectedEffort).takeIf { it >= 0 } ?: 0
    }
    val onDefaultEffortSelect: (Int) -> Unit = { index ->
        val chosen = effortOptions.getOrNull(index)
        val targetPrefs = agentPrefs
        if (chosen != null && targetPrefs != null) {
            if (putStringSync(targetPrefs, Prefs.Keys.AGENT_DEFAULT_REASONING_EFFORT, chosen.wireValue)) {
                savedDefaultEffort = chosen
            } else {
                Toast.makeText(
                    context.applicationContext,
                    context.getString(R.string.settings_write_failed),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }
    val defaultEffortTitle = stringResource(R.string.settings_default_reasoning_effort)
    val defaultEffortUnsupported = stringResource(R.string.conversation_title_settings_reasoning_unsupported)
    val defaultEffortEnabled = agentPrefs != null && defaultThinkingEnabled && effortOptions.size > 1
    var powerAssistantTarget by remember(prefs) {
        mutableStateOf(prefs?.let(Prefs::powerAssistantTarget) ?: enhancementHistory.powerTarget())
    }
    val openAssistantSettings: () -> Unit = {
        if (!AssistantRoleHelper.openSystemSettings(context)) {
            Toast.makeText(context, context.getString(R.string.settings_open_assistant_failed), Toast.LENGTH_SHORT).show()
        }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    val refreshSystemStatuses: () -> Unit = {
        coroutineScope.launch {
            val snapshot = withContext(Dispatchers.IO) {
                SettingsSystemStatus(
                    accessibilityGranted = isAgentAccessibilityEnabled(context),
                    accessibilityProtectionEnabled = AccessibilityProtectionClient.isEnabled(context),
                )
            }
            cachedSettingsSystemStatus = snapshot
            systemStatus = snapshot
        }
    }
    LaunchedEffect(Unit) {
        refreshSystemStatuses()
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshSystemStatuses()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    DisposableEffect(prefs) {
        val targetPrefs = prefs ?: return@DisposableEffect onDispose {}
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { changedPrefs, key ->
            if (key == Prefs.Keys.POWER_KEY_ASSISTANT_TARGET || key == Prefs.Keys.POWER_KEY_TAKEOVER) {
                powerAssistantTarget = Prefs.powerAssistantTarget(changedPrefs)
            }
        }
        targetPrefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { targetPrefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    DisposableEffect(Unit) {
        var lastService = EtaApp.serviceInstance
        val initialCached = Prefs.cachedRemotePreferencesForUi(lastService)
        if (initialCached != null) {
            prefs = initialCached
            hasConnectedFramework = true
        }
        val listener = object : EtaApp.ServiceStateListener {
            override fun onServiceStateChanged(service: io.github.libxposed.service.XposedService?) {
                if (service === lastService && prefs != null) return
                lastService = service
                coroutineScope.launch {
                    val resolvedPrefs = withContext(Dispatchers.IO) {
                        Prefs.remotePreferencesForUi(service).also { connected ->
                            if (connected != null) {
                                enhancementHistory.captureConnected(connected)
                            }
                            Prefs.reconcileAgentPreferences(service)
                            RuntimeConfigRepository.ensureDefaults(service)
                        }
                    }
                    prefs = resolvedPrefs
                    if (resolvedPrefs != null) {
                        hasConnectedFramework = true
                    }
                }
            }
        }
        EtaApp.addServiceStateListener(listener, notifyImmediately = initialCached == null)
        onDispose { EtaApp.removeServiceStateListener(listener) }
    }
    val powerAssistantTargets = PowerAssistantTarget.entries
    val powerAssistantItems = powerAssistantTargets.map { target -> target.displayName() }
    val onPowerTargetSelect: (Int) -> Unit = { index ->
        val target = powerAssistantTargets.getOrNull(index)
        val targetPrefs = prefs
        if (target != null && targetPrefs != null) {
            if (putStringSync(
                    prefs = targetPrefs,
                    key = Prefs.Keys.POWER_KEY_ASSISTANT_TARGET,
                    value = target.persistedValue,
                )
            ) {
                powerAssistantTarget = target
                enhancementHistory.recordCommittedTarget(target)
            } else {
                Toast.makeText(
                    context.applicationContext,
                    context.getString(R.string.settings_write_failed),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }
    val onAccessibilityProtectionToggle: (Boolean) -> Unit = { enabled ->
        if (!accessibilityProtectionPending) {
            accessibilityProtectionPending = true
            AccessibilityProtectionClient.setEnabled(
                context = context,
                enabled = enabled,
            ) { result ->
                accessibilityProtectionPending = false
                val updated = systemStatus.copy(
                    accessibilityProtectionEnabled = result.enabled,
                    accessibilityGranted = isAgentAccessibilityEnabled(context),
                )
                cachedSettingsSystemStatus = updated
                systemStatus = updated
                val failureMessage = when (result.status) {
                    AccessibilityProtectionClient.ControlStatus.APPLIED -> null
                    AccessibilityProtectionClient.ControlStatus.UNAVAILABLE ->
                        context.getString(R.string.accessibility_protection_unavailable)
                    AccessibilityProtectionClient.ControlStatus.REJECTED ->
                        context.getString(R.string.accessibility_protection_rejected)
                }
                if (failureMessage != null) {
                    Toast.makeText(
                        context.applicationContext,
                        failureMessage,
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }
    }

    ScaffoldPage(
        title = stringResource(R.string.ui_set_up_7debf9),
        onBack = onBack,
        overlay = {
            val activeUpdate = availableUpdate ?: retainedUpdate
            GlassDialog(
                show = availableUpdate != null,
                backdrop = currentGlassOverlayBackdrop(),
                title = stringResource(R.string.ui_update_available_title),
                summary = activeUpdate?.let { update ->
                    stringResource(R.string.ui_update_available_message, update.version, appVersionName)
                },
                onDismissRequest = { availableUpdate = null },
                onDismissFinished = { if (availableUpdate == null) retainedUpdate = null },
            ) {
                DialogActions(
                    confirmText = stringResource(R.string.ui_update_go_download),
                    confirmEnabled = activeUpdate != null,
                    onCancel = { availableUpdate = null },
                    onConfirm = {
                        val target = activeUpdate
                        availableUpdate = null
                        if (target != null) openUrl(target.url)
                    },
                )
            }
        },
    ) {
        item(key = "section_agent_title") {
            SmallTitle(stringResource(R.string.settings_llm_providers))
        }
        item(key = "section_agent") {
            SettingsGroupCard {
                ArrowPreference(
                    title = stringResource(R.string.ui_model_provider_e8c7f5),
                    summary = providerSummary,
                    startAction = { SettingsIcon(Icons.Rounded.Cloud) },
                    onClick = { onNavigate(AppRoute.ModelProviders) },
                )
                SwitchPref(
                    context = context,
                    prefs = agentPrefs,
                    title = stringResource(R.string.ui_deep_thinking_enabled_by_default_c032d6),
                    key = Prefs.Keys.AGENT_THINKING_ENABLED,
                    icon = Icons.Rounded.Psychology,
                    history = enhancementHistory,
                )
                if (effortOptions.size > 1) {
                    GlassDropdownPreference(
                        title = defaultEffortTitle,
                        items = effortLabels,
                        selectedIndex = selectedEffortIndex,
                        backdrop = currentGlassOverlayBackdrop(),
                        enabled = defaultEffortEnabled,
                        onSelectedIndexChange = onDefaultEffortSelect,
                        startAction = {
                            SettingsIcon(
                                icon = Icons.Rounded.Tune,
                                enabled = defaultEffortEnabled,
                            )
                        },
                    )
                } else {
                    BasicComponent(
                        title = defaultEffortTitle,
                        summary = defaultEffortUnsupported,
                        startAction = {
                            SettingsIcon(
                                icon = Icons.Rounded.Tune,
                                enabled = false,
                            )
                        },
                        enabled = false,
                    )
                }
            }
        }

        item(key = "section_conversation_memory_title") {
            SmallTitle(stringResource(R.string.settings_conversation_memory))
        }
        item(key = "section_conversation_memory") {
            SettingsGroupCard {
                SwitchPref(
                    context = context,
                    prefs = agentPrefs,
                    title = stringResource(R.string.settings_auto_compaction),
                    key = Prefs.Keys.AGENT_AUTO_COMPACTION_ENABLED,
                    icon = Icons.Rounded.Compress,
                    history = enhancementHistory,
                )
                ArrowPreference(
                    title = stringResource(R.string.conversation_title_settings_title),
                    startAction = { SettingsIcon(Icons.Rounded.AutoAwesome) },
                    onClick = { onNavigate(AppRoute.ConversationTitleSettings) },
                )
                ArrowPreference(
                    title = stringResource(R.string.ui_memory_b55ff5),
                    startAction = { SettingsIcon(Icons.AutoMirrored.Rounded.MenuBook) },
                    onClick = { onNavigate(AppRoute.Memory) },
                )
            }
        }

        item(key = "section_extensions_title") {
            SmallTitle(stringResource(R.string.settings_extensions))
        }
        item(key = "section_extensions") {
            SettingsGroupCard {
                ArrowPreference(
                    title = stringResource(R.string.route_skills),
                    startAction = { SettingsIcon(Icons.Rounded.ImportContacts) },
                    onClick = { onNavigate(AppRoute.Skills) },
                )
                ArrowPreference(
                    title = stringResource(R.string.route_mcp_servers),
                    startAction = { SettingsIcon(Icons.Rounded.AccountTree) },
                    onClick = { onNavigate(AppRoute.McpServers) },
                )
                ArrowPreference(
                    title = stringResource(R.string.ui_misc_route_subagents),
                    startAction = { SettingsIcon(Icons.Rounded.JoinLeft) },
                    onClick = { onNavigate(AppRoute.Subagents) },
                )
                ArrowPreference(
                    title = stringResource(R.string.conversation_dock_characters),
                    startAction = { SettingsIcon(Icons.Rounded.SportsBar) },
                    onClick = { onNavigate(AppRoute.Characters) },
                )
                ArrowPreference(
                    title = stringResource(R.string.route_scheduled_tasks),
                    startAction = { SettingsIcon(Icons.Rounded.Schedule) },
                    onClick = { onNavigate(AppRoute.ScheduledTasks) },
                )
            }
        }

        item(key = "section_tools_title") {
            SmallTitle(stringResource(R.string.ui_tool_a72ef1))
        }
        item(key = "section_tools") {
            SettingsGroupCard {
                ArrowPreference(
                    title = stringResource(R.string.settings_tools_list),
                    startAction = { SettingsIcon(Icons.Rounded.Dashboard) },
                    onClick = { onNavigate(AppRoute.Tools) },
                )
                ArrowPreference(
                    title = stringResource(R.string.web_tools_settings_title),
                    startAction = { SettingsIcon(Icons.Rounded.TravelExplore) },
                    onClick = { onNavigate(AppRoute.WebToolsSettings) },
                )
                ArrowPreference(
                    title = stringResource(R.string.image_gen_settings_title),
                    startAction = { SettingsIcon(Icons.Rounded.Image) },
                    onClick = { onNavigate(AppRoute.ImageGenSettings) },
                )
                SwitchPref(
                    context = context,
                    prefs = agentPrefs,
                    title = stringResource(R.string.settings_virtual_display_title),
                    key = Prefs.Keys.AGENT_VIRTUAL_DISPLAY_ENABLED,
                    icon = Icons.Rounded.PictureInPictureAlt,
                    history = enhancementHistory,
                )
                ArrowPreference(
                    title = stringResource(R.string.ui_linux_tool_environment_314d22),
                    startAction = { SettingsIcon(Icons.Rounded.Inventory2) },
                    onClick = { onNavigate(AppRoute.LinuxEnvironment) },
                )
            }
        }

        item(key = "section_general_title") {
            SmallTitle(stringResource(R.string.settings_general))
        }
        item(key = "section_general") {
            SettingsGroupCard {
                ArrowPreference(
                    title = stringResource(R.string.appearance_title),
                    startAction = { SettingsIcon(Icons.Rounded.Palette) },
                    onClick = { onNavigate(AppRoute.AppearanceSettings) },
                )
                ArrowPreference(
                    title = stringResource(R.string.speech_settings_title),
                    startAction = { SettingsIcon(Icons.Rounded.Mic) },
                    onClick = { onNavigate(AppRoute.SpeechSettings) },
                )
                LanguagePreference(
                    startAction = { SettingsIcon(Icons.Rounded.Language) },
                )
                SwitchPref(
                    context = context,
                    prefs = agentPrefs,
                    title = stringResource(R.string.settings_keep_screen_on),
                    key = Prefs.Keys.AGENT_KEEP_SCREEN_ON_ENABLED,
                    icon = Icons.Rounded.BrightnessHigh,
                    history = enhancementHistory,
                )
                ArrowPreference(
                    title = stringResource(R.string.data_backup_title),
                    startAction = { SettingsIcon(Icons.Rounded.Description) },
                    onClick = { onNavigate(AppRoute.DataBackup) },
                )
            }
        }

        item(key = "section_assistant_takeover_title") {
            SmallTitle(stringResource(R.string.ui_system_assistant_takes_over_f46043))
        }
        item(key = "section_assistant_takeover") {
            SettingsGroupCard {
                ArrowPreference(
                    title = stringResource(R.string.ui_eta_system_assistant_003e9b),
                    startAction = { SettingsIcon(Icons.Rounded.SupportAgent) },
                    onClick = openAssistantSettings,
                )
                ArrowPreference(
                    title = stringResource(R.string.assistant_overlay_settings_title),
                    startAction = { SettingsIcon(Icons.Rounded.ChatBubble) },
                    onClick = { onNavigate(AppRoute.AssistantOverlaySettings) },
                )
                if (prefs != null || hasConnectedFramework) {
                    GlassDropdownPreference(
                        title = stringResource(R.string.ui_long_press_the_power_button_1958d0),
                        items = powerAssistantItems,
                        selectedIndex = powerAssistantTargets.indexOf(powerAssistantTarget),
                        backdrop = currentGlassOverlayBackdrop(),
                        onSelectedIndexChange = onPowerTargetSelect,
                        startAction = {
                            SettingsIcon(
                                icon = Icons.Rounded.PowerSettingsNew,
                                enabled = prefs != null,
                            )
                        },
                        enabled = prefs != null,
                    )
                    SwitchPref(
                        context = context,
                        prefs = prefs,
                        title = stringResource(R.string.ui_automatically_set_default_assistant_f86963),
                        key = Prefs.Keys.ASSISTANT_AUTO_CONFIG,
                        icon = Icons.Rounded.Settings,
                    )
                }
            }
        }

        if (prefs != null || hasConnectedFramework) {
            item(key = "section_circle_to_search_title") {
                SmallTitle(stringResource(R.string.ui_search_in_one_turn_179584))
            }
            item(key = "section_circle_to_search") {
                SettingsGroupCard {
                    SwitchPref(
                        context = context,
                        prefs = prefs,
                        title = stringResource(R.string.ui_long_press_on_the_gesture_bar_triggers_a_circle_to_s_b80117),
                        key = Prefs.Keys.GESTURE_BAR_CIRCLE_TO_SEARCH,
                        icon = Icons.Rounded.SwipeUp,
                    )
                    SwitchPref(
                        context = context,
                        prefs = prefs,
                        title = stringResource(R.string.ui_long_press_with_two_fingers_to_trigger_a_circle_sear_ab597a),
                        key = Prefs.Keys.DOUBLE_FINGER_CIRCLE_TO_SEARCH,
                        icon = Icons.Rounded.TouchApp,
                    )
                }
            }
        }

        item(key = "section_permissions_title") {
            SmallTitle(stringResource(R.string.settings_permissions_enhancements))
        }
        item(key = "section_permissions") {
            SettingsGroupCard {
                ArrowPreference(
                    title = stringResource(R.string.ui_permission_health_3048bb),
                    startAction = { SettingsIcon(MiuixIcons.Glass.SecurityStatus) },
                    onClick = { onNavigate(AppRoute.Permissions) },
                )
                ArrowPreference(
                    title = stringResource(R.string.ui_accessibility_enhancement_tools_8fd257),
                    startAction = { SettingsIcon(Icons.Rounded.AccessibilityNew) },
                    endActions = {
                        val enabled = accessibilityGranted || AgentAccessibilityService.isAvailable()
                        Text(
                            text = stringResource(
                                if (enabled) R.string.status_enabled else R.string.status_disabled,
                            ),
                            fontSize = MiuixTheme.textStyles.body2.fontSize,
                            color = if (enabled) {
                                MiuixTheme.colorScheme.onSurfaceVariantActions
                            } else {
                                MiuixTheme.colorScheme.primary
                            },
                        )
                    },
                    onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
                            )
                        }
                    },
                )
                if (prefs != null || hasConnectedFramework) {
                    SwitchPreference(
                        title = stringResource(R.string.ui_enforce_accessibility_55e838),
                        checked = accessibilityProtectionEnabled,
                        onCheckedChange = onAccessibilityProtectionToggle,
                        startAction = {
                            SettingsIcon(
                                icon = Icons.Rounded.Lock,
                                enabled = prefs != null && !accessibilityProtectionPending,
                            )
                        },
                        enabled = prefs != null && !accessibilityProtectionPending,
                    )
                }
                ArrowPreference(
                    title = stringResource(R.string.capability_enhancements),
                    startAction = { SettingsIcon(Icons.Rounded.Security) },
                    onClick = { onNavigate(AppRoute.SystemEnhance) },
                )
            }
        }

        item(key = "section_about_title") {
            SmallTitle(stringResource(R.string.ui_about_bed172))
        }
        item(key = "section_about") {
            SettingsGroupCard {
                BasicComponent(
                    title = stringResource(R.string.ui_about_version_title),
                    summary = appVersionSummary,
                    startAction = { SettingsIcon(Icons.Rounded.Info) },
                )
                ArrowPreference(
                    title = stringResource(R.string.ui_about_update_title),
                    summary = if (checkingUpdate) {
                        stringResource(R.string.ui_about_update_checking)
                    } else {
                        null
                    },
                    startAction = {
                        SettingsIcon(
                            icon = Icons.Rounded.SystemUpdate,
                            enabled = !checkingUpdate,
                        )
                    },
                    enabled = !checkingUpdate,
                    onClick = checkForUpdate,
                )
                ArrowPreference(
                    title = stringResource(R.string.ui_about_feedback_title),
                    startAction = { SettingsIcon(Icons.Rounded.BugReport) },
                    onClick = { openUrl("https://github.com/Mangi-11/Eta/issues") },
                )
                ArrowPreference(
                    title = stringResource(R.string.ui_about_github_star_title),
                    summary = stringResource(R.string.ui_about_github_star_hint),
                    startAction = { SettingsIcon(Icons.Rounded.Code) },
                    onClick = { openUrl("https://github.com/Mangi-11/Eta") },
                )
            }
        }
    }
}

@Composable
private fun SwitchPref(
    context: Context,
    prefs: SharedPreferences?,
    title: String,
    summary: String? = null,
    key: String,
    icon: ImageVector,
    history: EnhancementSettingsHistory = remember(context.applicationContext) {
        EnhancementSettingsHistory(context)
    },
) {
    val enabled = prefs != null
    val default = Prefs.Keys.BOOLEAN_DEFAULTS[key] ?: true
    var checked by remember(prefs, key) {
        mutableStateOf(prefs?.getBoolean(key, default) ?: history.checked(key, default))
    }
    DisposableEffect(prefs, key) {
        val targetPrefs = prefs ?: return@DisposableEffect onDispose {}
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { changedPrefs, changedKey ->
            if (changedKey == key) {
                checked = changedPrefs.getBoolean(key, default)
            }
        }
        targetPrefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { targetPrefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val onToggle: (Boolean) -> Unit = { value ->
        val targetPrefs = prefs
        if (targetPrefs != null) {
            if (putBooleanSync(targetPrefs, key, value)) {
                checked = value
                history.recordCommittedBoolean(key, value)
                if (key in Prefs.Keys.LOCAL_AGENT_KEYS) {
                    Prefs.reconcileAgentPreferences(EtaApp.serviceInstance)
                }
            } else {
                Toast.makeText(
                    context.applicationContext,
                    context.getString(R.string.settings_write_failed),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    SwitchPreference(
        title = title,
        summary = summary,
        checked = checked,
        onCheckedChange = onToggle,
        startAction = {
            SettingsIcon(icon = icon, enabled = enabled)
        },
        enabled = enabled,
    )
}

private fun putBooleanSync(
    prefs: SharedPreferences,
    key: String,
    value: Boolean,
): Boolean =
    runCatching { prefs.edit().putBoolean(key, value).commit() }.getOrDefault(false)

private fun putStringSync(
    prefs: SharedPreferences,
    key: String,
    value: String,
): Boolean =
    runCatching { prefs.edit().putString(key, value).commit() }.getOrDefault(false)

@Composable
private fun PowerAssistantTarget.displayName(): String =
    when (this) {
        PowerAssistantTarget.OEM -> stringResource(R.string.power_assistant_system_default)
        PowerAssistantTarget.GEMINI -> "Gemini"
        PowerAssistantTarget.ETA -> "Eta"
    }

private fun isAgentAccessibilityEnabled(context: Context): Boolean {
    val expected = ComponentName(
        context,
        AgentAccessibilityService::class.java,
    ).flattenToString()
    val enabledServices = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
    ).orEmpty()
    return enabledServices.split(':').any { it.equals(expected, ignoreCase = true) }
}
