package io.github.mangi.eta.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.ImportContacts
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.JoinLeft
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Palette
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
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import io.github.mangi.eta.agent.voice.AssistantRoleStatus
import io.github.mangi.eta.agent.voice.DefaultAssistantOwner
import io.github.mangi.eta.config.PowerAssistantTarget
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.core.ModuleConfig
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.data.model.ImageGenSettings
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.data.model.enabledModel
import io.github.mangi.eta.data.provider.BuiltinProviders
import io.github.mangi.eta.data.repository.ImageGenSettingsRepository
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import io.github.mangi.eta.data.update.AppLatestRelease
import io.github.mangi.eta.data.update.AppUpdateChecker
import io.github.mangi.eta.ui.app.EnhancementSettingsHistory
import io.github.mangi.eta.ui.app.LocalUiStyle
import io.github.mangi.eta.ui.components.LanguagePreference
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.components.StatusSuccess
import io.github.mangi.eta.ui.components.StatusWarning
import io.github.mangi.eta.ui.components.coui.CouiConfirmDialog
import io.github.mangi.eta.ui.components.coui.CouiDropdownPreference
import io.github.mangi.eta.ui.components.coui.CouiPageBottomSpacer
import io.github.mangi.eta.ui.components.coui.CouiPageDefaults
import io.github.mangi.eta.ui.components.coui.CouiPreference
import io.github.mangi.eta.ui.components.coui.CouiPreferenceGroup
import io.github.mangi.eta.ui.components.coui.CouiScaffoldPage
import io.github.mangi.eta.ui.components.coui.CouiSettingsIcon
import io.github.mangi.eta.ui.components.coui.CouiSwitchPreference
import io.github.mangi.eta.ui.components.currentGlassOverlayBackdrop
import io.github.mangi.eta.ui.model.AgentModelPickerProjector
import io.github.mangi.eta.ui.navigation.AppRoute
import io.github.mangi.eta.ui.screens.imagegen.shortName
import io.github.suqi8.coui.kmp.theme.COUITheme as CouiTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.glass.GlassDialog
import top.yukonga.miuix.kmp.glass.GlassDropdownPreference
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

private object SettingsIconColors {
    val Blue = Color(0xFF4285F4)
    val Green = StatusSuccess
    val Orange = Color(0xFFFA7B17)
    val Yellow = StatusWarning
}

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
    val overlayGranted: Boolean = false,
    val accessibilityGranted: Boolean = false,
    val accessibilityProtectionEnabled: Boolean = false,
    val assistantRoleStatus: AssistantRoleStatus = AssistantRoleStatus(isDefaultAssistant = false),
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

    // 悬浮窗与无障碍状态走异步查询，避免首帧进入设置页时在主线程触发同步 Binder IPC。
    var systemStatus by remember {
        mutableStateOf(cachedSettingsSystemStatus ?: SettingsSystemStatus())
    }
    val overlayGranted = systemStatus.overlayGranted
    val accessibilityGranted = systemStatus.accessibilityGranted
    val accessibilityProtectionEnabled = systemStatus.accessibilityProtectionEnabled
    val assistantRoleStatus = systemStatus.assistantRoleStatus
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
    val providers by ProviderRepository.providersFlow().collectAsState(initial = initialProviders)
    val selectedProviderId by RuntimeConfigRepository.selectedProviderIdFlow()
        .collectAsState(initial = initialProviderId)
    val selectedModelId by RuntimeConfigRepository.selectedModelIdFlow()
        .collectAsState(initial = initialModelId)
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
    var assistantRefreshJob by remember { mutableStateOf<Job?>(null) }
    val scheduleAssistantRoleRefresh: () -> Unit = {
        assistantRefreshJob?.cancel()
        assistantRefreshJob = coroutineScope.launch {
            val status = withContext(Dispatchers.IO) { AssistantRoleHelper.queryStatus(context) }
            val next = systemStatus.copy(assistantRoleStatus = status)
            cachedSettingsSystemStatus = next
            systemStatus = next
            delay(450L)
            val delayedStatus = withContext(Dispatchers.IO) { AssistantRoleHelper.queryStatus(context) }
            val settled = systemStatus.copy(assistantRoleStatus = delayedStatus)
            cachedSettingsSystemStatus = settled
            systemStatus = settled
        }
    }
    LaunchedEffect(context) {
        val resolver = context.contentResolver
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                scheduleAssistantRoleRefresh()
            }
        }
        val registered = withContext(Dispatchers.IO) {
            runCatching {
                resolver.registerContentObserver(
                    Settings.Secure.getUriFor(ModuleConfig.SECURE_ASSISTANT),
                    false,
                    observer,
                )
                resolver.registerContentObserver(
                    Settings.Secure.getUriFor(ModuleConfig.SECURE_VOICE_INTERACTION_SERVICE),
                    false,
                    observer,
                )
                true
            }.getOrDefault(false)
        }
        try {
            awaitCancellation()
        } finally {
            assistantRefreshJob?.cancel()
            if (registered) {
                withContext(NonCancellable + Dispatchers.IO) {
                    runCatching { resolver.unregisterContentObserver(observer) }
                }
            }
        }
    }
    val syncPowerTargetAfterManualAssistantChange: (AssistantRoleStatus) -> Unit = { updated ->
        val targetPrefs = prefs
        val autoConfigDefault = Prefs.Keys.BOOLEAN_DEFAULTS.getValue(Prefs.Keys.ASSISTANT_AUTO_CONFIG)
        if (targetPrefs != null &&
            targetPrefs.getBoolean(Prefs.Keys.ASSISTANT_AUTO_CONFIG, autoConfigDefault)
        ) {
            val mappedTarget = when (updated.owner) {
                DefaultAssistantOwner.ETA -> PowerAssistantTarget.ETA
                DefaultAssistantOwner.GEMINI -> PowerAssistantTarget.GEMINI
                DefaultAssistantOwner.OTHER,
                DefaultAssistantOwner.NONE -> PowerAssistantTarget.OEM
            }
            if (powerAssistantTarget != mappedTarget &&
                putStringSync(
                    prefs = targetPrefs,
                    key = Prefs.Keys.POWER_KEY_ASSISTANT_TARGET,
                    value = mappedTarget.persistedValue,
                )
            ) {
                powerAssistantTarget = mappedTarget
                enhancementHistory.recordCommittedTarget(mappedTarget)
            }
        }
    }
    var ownerBeforeAssistantSettings by remember { mutableStateOf<DefaultAssistantOwner?>(null) }
    val openAssistantSettings: () -> Unit = {
        val currentStatus = AssistantRoleHelper.queryStatus(context)
        val next = systemStatus.copy(assistantRoleStatus = currentStatus)
        cachedSettingsSystemStatus = next
        systemStatus = next
        if (AssistantRoleHelper.openSystemSettings(context)) {
            ownerBeforeAssistantSettings = currentStatus.owner
        } else {
            Toast.makeText(context, context.getString(R.string.settings_open_assistant_failed), Toast.LENGTH_SHORT).show()
        }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    val refreshSystemStatuses: (Boolean) -> Unit = { checkAssistantTransition ->
        coroutineScope.launch {
            val snapshot = withContext(Dispatchers.IO) {
                SettingsSystemStatus(
                    overlayGranted = android.provider.Settings.canDrawOverlays(context),
                    accessibilityGranted = isAgentAccessibilityEnabled(context),
                    accessibilityProtectionEnabled = AccessibilityProtectionClient.isEnabled(context),
                    assistantRoleStatus = AssistantRoleHelper.queryStatus(context),
                )
            }
            cachedSettingsSystemStatus = snapshot
            systemStatus = snapshot
            if (checkAssistantTransition) {
                val previousOwner = ownerBeforeAssistantSettings
                if (previousOwner != null) {
                    ownerBeforeAssistantSettings = null
                    if (snapshot.assistantRoleStatus.owner != previousOwner) {
                        syncPowerTargetAfterManualAssistantChange(snapshot.assistantRoleStatus)
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) {
        refreshSystemStatuses(false)
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshSystemStatuses(true)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    DisposableEffect(prefs) {
        val targetPrefs = prefs ?: return@DisposableEffect onDispose {}
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { changedPrefs, key ->
            when (key) {
                Prefs.Keys.POWER_KEY_ASSISTANT_TARGET,
                Prefs.Keys.POWER_KEY_TAKEOVER -> {
                    powerAssistantTarget = Prefs.powerAssistantTarget(changedPrefs)
                    scheduleAssistantRoleRefresh()
                }
                Prefs.Keys.ASSISTANT_AUTO_CONFIG -> {
                    scheduleAssistantRoleRefresh()
                }
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
                scheduleAssistantRoleRefresh()
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

    when (LocalUiStyle.current) {
        AppearanceUiStyle.COUI -> {
            val pageColors = CouiPageDefaults.themeColors()

            CouiScaffoldPage(
                title = stringResource(R.string.ui_set_up_7debf9),
                onBack = onBack,
                overlay = {
                    val activeUpdate = availableUpdate ?: retainedUpdate
                    CouiConfirmDialog(
                        visible = availableUpdate != null,
                        title = stringResource(R.string.ui_update_available_title),
                        message = activeUpdate?.let { update ->
                            stringResource(R.string.ui_update_available_message, update.version, appVersionName)
                        },
                        confirmText = stringResource(R.string.ui_update_go_download),
                        confirmEnabled = activeUpdate != null,
                        onConfirm = {
                            val target = activeUpdate
                            availableUpdate = null
                            if (target != null) openUrl(target.url)
                        },
                        onDismissRequest = { availableUpdate = null },
                        onDismissFinished = { if (availableUpdate == null) retainedUpdate = null },
                    )
                },
            ) {
                CouiPreferenceGroup(title = stringResource(R.string.settings_llm_providers)) {
                    item {
                        CouiPreference(
                            title = stringResource(R.string.ui_model_provider_e8c7f5),
                            summary = providerSummary,
                            icon = { CouiSettingsIcon(Icons.Rounded.Cloud, SettingsIconColors.Blue) },
                            onClick = { onNavigate(AppRoute.ModelProviders) },
                        )
                    }
                    item {
                        SwitchPref(
                            context = context,
                            prefs = agentPrefs,
                            title = stringResource(R.string.ui_deep_thinking_enabled_by_default_c032d6),
                            key = Prefs.Keys.AGENT_THINKING_ENABLED,
                            icon = Icons.Rounded.Psychology,
                            iconTint = SettingsIconColors.Blue,
                        )
                    }
                    if (effortOptions.size > 1) {
                        item {
                            CouiDropdownPreference(
                                title = defaultEffortTitle,
                                items = effortLabels,
                                selectedIndex = selectedEffortIndex,
                                onSelectedIndexChange = onDefaultEffortSelect,
                                icon = {
                                    CouiSettingsIcon(
                                        icon = Icons.Rounded.Tune,
                                        tint = SettingsIconColors.Blue,
                                        enabled = defaultEffortEnabled,
                                    )
                                },
                                enabled = defaultEffortEnabled,
                            )
                        }
                    } else {
                        item {
                            CouiPreference(
                                title = defaultEffortTitle,
                                summary = defaultEffortUnsupported,
                                icon = {
                                    CouiSettingsIcon(
                                        icon = Icons.Rounded.Tune,
                                        tint = SettingsIconColors.Blue,
                                        enabled = false,
                                    )
                                },
                                enabled = false,
                                showChevron = false,
                            )
                        }
                    }
                }

                CouiPreferenceGroup(title = stringResource(R.string.settings_context_extensions)) {
                    item {
                        SwitchPref(
                            context = context,
                            prefs = agentPrefs,
                            title = stringResource(R.string.settings_auto_compaction),
                            key = Prefs.Keys.AGENT_AUTO_COMPACTION_ENABLED,
                            icon = Icons.Rounded.Layers,
                            iconTint = SettingsIconColors.Blue,
                        )
                    }
                    item {
                        CouiPreference(
                            title = stringResource(R.string.conversation_title_settings_title),
                            icon = { CouiSettingsIcon(Icons.Rounded.AutoAwesome, SettingsIconColors.Blue) },
                            onClick = { onNavigate(AppRoute.ConversationTitleSettings) },
                        )
                    }
                    item {
                        CouiPreference(
                            title = stringResource(R.string.ui_memory_b55ff5),
                            icon = {
                                CouiSettingsIcon(
                                    Icons.AutoMirrored.Rounded.MenuBook,
                                    SettingsIconColors.Orange,
                                )
                            },
                            onClick = { onNavigate(AppRoute.Memory) },
                        )
                    }
                    item {
                        CouiPreference(
                            title = stringResource(R.string.ui_misc_route_subagents),
                            icon = { CouiSettingsIcon(Icons.Rounded.JoinLeft, SettingsIconColors.Blue) },
                            onClick = { onNavigate(AppRoute.Subagents) },
                        )
                    }
                    item {
                        CouiPreference(
                            title = stringResource(R.string.route_skills),
                            icon = {
                                CouiSettingsIcon(
                                    Icons.Rounded.ImportContacts,
                                    SettingsIconColors.Green,
                                )
                            },
                            onClick = { onNavigate(AppRoute.Skills) },
                        )
                    }
                    item {
                        CouiPreference(
                            title = stringResource(R.string.route_mcp_servers),
                            icon = {
                                CouiSettingsIcon(
                                    Icons.Rounded.AccountTree,
                                    SettingsIconColors.Blue,
                                )
                            },
                            onClick = { onNavigate(AppRoute.McpServers) },
                        )
                    }
                    item {
                        CouiPreference(
                            title = stringResource(R.string.route_scheduled_tasks),
                            icon = {
                                CouiSettingsIcon(
                                    Icons.Rounded.Schedule,
                                    SettingsIconColors.Blue,
                                )
                            },
                            onClick = { onNavigate(AppRoute.ScheduledTasks) },
                        )
                    }
                    item {
                        CouiPreference(
                            title = stringResource(R.string.conversation_dock_characters),
                            icon = {
                                CouiSettingsIcon(
                                    Icons.Rounded.SportsBar,
                                    SettingsIconColors.Orange,
                                )
                            },
                            onClick = { onNavigate(AppRoute.Characters) },
                        )
                    }
                }

                CouiPreferenceGroup(title = stringResource(R.string.ui_tool_a72ef1)) {
                    item {
                        CouiPreference(
                            title = stringResource(R.string.settings_tools_list),
                            icon = { CouiSettingsIcon(Icons.Rounded.Dashboard, SettingsIconColors.Green) },
                            onClick = { onNavigate(AppRoute.Tools) },
                        )
                    }
                    item {
                        CouiPreference(
                            title = stringResource(R.string.web_tools_settings_title),
                            icon = {
                                CouiSettingsIcon(
                                    Icons.Rounded.TravelExplore,
                                    SettingsIconColors.Blue,
                                )
                            },
                            onClick = { onNavigate(AppRoute.WebToolsSettings) },
                        )
                    }
                    item {
                        val imageGenSettings by remember { ImageGenSettingsRepository.settingsFlow() }
                            .collectAsState(initial = ImageGenSettings())
                        CouiPreference(
                            title = stringResource(R.string.image_gen_settings_title),
                            value = if (imageGenSettings.enabled) imageGenSettings.backend.shortName() else stringResource(R.string.ui_misc_not_enabled),
                            icon = {
                                CouiSettingsIcon(
                                    Icons.Rounded.Image,
                                    SettingsIconColors.Orange,
                                )
                            },
                            onClick = { onNavigate(AppRoute.ImageGenSettings) },
                        )
                    }
                    item {
                        SwitchPref(
                            context = context,
                            prefs = agentPrefs,
                            title = stringResource(R.string.settings_virtual_display_title),
                            key = Prefs.Keys.AGENT_VIRTUAL_DISPLAY_ENABLED,
                            icon = Icons.Rounded.Layers,
                            iconTint = SettingsIconColors.Blue,
                        )
                    }
                    item {
                        CouiPreference(
                            title = stringResource(R.string.ui_linux_tool_environment_314d22),
                            icon = {
                                CouiSettingsIcon(
                                    Icons.Rounded.Inventory2,
                                    SettingsIconColors.Orange,
                                )
                            },
                            onClick = { onNavigate(AppRoute.LinuxEnvironment) },
                        )
                    }
                }

                CouiPreferenceGroup {
                    item {
                        CouiPreference(
                            title = stringResource(R.string.capability_enhancements),
                            icon = { CouiSettingsIcon(Icons.Rounded.Security, SettingsIconColors.Blue) },
                            onClick = { onNavigate(AppRoute.SystemEnhance) },
                        )
                    }
                }

                CouiPreferenceGroup(title = stringResource(R.string.ui_system_assistant_takes_over_f46043)) {
                    item {
                        CouiPreference(
                            title = stringResource(R.string.ui_eta_system_assistant_003e9b),
                            value = assistantRoleStatus.displayValue(),
                            valueColor = if (assistantRoleStatus.isDefaultAssistant) {
                                StatusSuccess
                            } else {
                                pageColors.summary
                            },
                            icon = {
                                CouiSettingsIcon(
                                    Icons.Rounded.SupportAgent,
                                    SettingsIconColors.Green,
                                )
                            },
                            onClick = openAssistantSettings,
                        )
                    }
                    item {
                        CouiPreference(
                            title = stringResource(R.string.assistant_overlay_settings_title),
                            icon = {
                                CouiSettingsIcon(
                                    Icons.Rounded.ChatBubble,
                                    SettingsIconColors.Blue,
                                )
                            },
                            onClick = { onNavigate(AppRoute.AssistantOverlaySettings) },
                        )
                    }
                    if (prefs != null || hasConnectedFramework) {
                        item {
                            CouiDropdownPreference(
                                title = stringResource(R.string.ui_long_press_the_power_button_1958d0),
                                items = powerAssistantItems,
                                selectedIndex = powerAssistantTargets.indexOf(powerAssistantTarget),
                                onSelectedIndexChange = onPowerTargetSelect,
                                icon = {
                                    CouiSettingsIcon(
                                        icon = Icons.Rounded.PowerSettingsNew,
                                        tint = SettingsIconColors.Yellow,
                                        enabled = prefs != null,
                                    )
                                },
                                enabled = prefs != null,
                            )
                        }
                        item {
                            SwitchPref(
                                context = context,
                                prefs = prefs,
                                title = stringResource(R.string.ui_automatically_set_default_assistant_f86963),
                                key = Prefs.Keys.ASSISTANT_AUTO_CONFIG,
                                icon = Icons.Rounded.Settings,
                                iconTint = SettingsIconColors.Green,
                            )
                        }
                    }
                }

                if (prefs != null || hasConnectedFramework) {
                    CouiPreferenceGroup(title = stringResource(R.string.ui_search_in_one_turn_179584)) {
                        item {
                            SwitchPref(
                                context = context,
                                prefs = prefs,
                                title = stringResource(R.string.ui_long_press_on_the_gesture_bar_triggers_a_circle_to_s_b80117),
                                key = Prefs.Keys.GESTURE_BAR_CIRCLE_TO_SEARCH,
                                icon = Icons.Rounded.SwipeUp,
                                iconTint = SettingsIconColors.Blue,
                            )
                        }
                        item {
                            SwitchPref(
                                context = context,
                                prefs = prefs,
                                title = stringResource(R.string.ui_long_press_with_two_fingers_to_trigger_a_circle_sear_ab597a),
                                key = Prefs.Keys.DOUBLE_FINGER_CIRCLE_TO_SEARCH,
                                icon = Icons.Rounded.TouchApp,
                                iconTint = SettingsIconColors.Blue,
                            )
                        }
                    }
                }

                CouiPreferenceGroup(title = stringResource(R.string.settings_general)) {
                    item {
                        SwitchPref(
                            context = context,
                            prefs = agentPrefs,
                            title = stringResource(R.string.settings_keep_screen_on),
                            key = Prefs.Keys.AGENT_KEEP_SCREEN_ON_ENABLED,
                            icon = Icons.Rounded.BrightnessHigh,
                            iconTint = SettingsIconColors.Orange,
                        )
                    }
                    item {
                        CouiPreference(
                            title = stringResource(R.string.appearance_title),
                            icon = { CouiSettingsIcon(Icons.Rounded.Palette, SettingsIconColors.Orange) },
                            onClick = { onNavigate(AppRoute.AppearanceSettings) },
                        )
                    }
                    item {
                        CouiPreference(
                            title = stringResource(R.string.speech_settings_title),
                            icon = { CouiSettingsIcon(Icons.Rounded.Mic, SettingsIconColors.Blue) },
                            onClick = { onNavigate(AppRoute.SpeechSettings) },
                        )
                    }
                    item {
                        LanguagePreference(
                            iconTint = SettingsIconColors.Blue,
                            startAction = {
                                CouiSettingsIcon(Icons.Rounded.Language, SettingsIconColors.Blue)
                            },
                        )
                    }
                    item {
                        CouiPreference(
                            title = stringResource(R.string.data_backup_title),
                            icon = {
                                CouiSettingsIcon(
                                    Icons.Rounded.Description,
                                    SettingsIconColors.Blue,
                                )
                            },
                            onClick = { onNavigate(AppRoute.DataBackup) },
                        )
                    }
                }

                CouiPreferenceGroup(title = stringResource(R.string.ui_permissions_560165)) {
                    item {
                        CouiPreference(
                            title = stringResource(R.string.ui_floating_window_permissions_076b77),
                            value = stringResource(
                                if (overlayGranted) R.string.status_authorized else R.string.status_unauthorized,
                            ),
                            valueColor = if (overlayGranted) pageColors.summary else CouiTheme.colorScheme.error,
                            icon = { CouiSettingsIcon(Icons.Rounded.Layers, SettingsIconColors.Blue) },
                            onClick = {
                                if (!overlayGranted) {
                                    runCatching {
                                        context.startActivity(
                                            Intent(
                                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                                Uri.parse("package:${context.packageName}"),
                                            ),
                                        )
                                    }
                                }
                            },
                        )
                    }
                    val accEnabled = accessibilityGranted || AgentAccessibilityService.isAvailable()
                    item {
                        CouiPreference(
                            title = stringResource(R.string.ui_accessibility_enhancement_tools_8fd257),
                            value = stringResource(
                                if (accEnabled) R.string.status_enabled else R.string.status_disabled,
                            ),
                            valueColor = if (accEnabled) pageColors.summary else pageColors.accent,
                            icon = {
                                CouiSettingsIcon(
                                    Icons.Rounded.AccessibilityNew,
                                    SettingsIconColors.Blue,
                                )
                            },
                            onClick = {
                                runCatching {
                                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                                }
                            },
                        )
                    }
                    if (prefs != null || hasConnectedFramework) {
                        item {
                            CouiSwitchPreference(
                                title = stringResource(R.string.ui_enforce_accessibility_55e838),
                                checked = accessibilityProtectionEnabled,
                                onCheckedChange = onAccessibilityProtectionToggle,
                                icon = {
                                    CouiSettingsIcon(
                                        icon = Icons.Rounded.VerifiedUser,
                                        tint = SettingsIconColors.Blue,
                                        enabled = prefs != null && !accessibilityProtectionPending,
                                    )
                                },
                                enabled = prefs != null && !accessibilityProtectionPending,
                            )
                        }
                    }
                }

                CouiPreferenceGroup(title = stringResource(R.string.ui_about_bed172)) {
                    item {
                        CouiPreference(
                            title = stringResource(R.string.ui_about_version_title),
                            summary = appVersionSummary,
                            icon = { CouiSettingsIcon(Icons.Rounded.Info, SettingsIconColors.Blue) },
                            showChevron = false,
                        )
                    }
                    item {
                        CouiPreference(
                            title = stringResource(R.string.ui_about_update_title),
                            summary = if (checkingUpdate) {
                                stringResource(R.string.ui_about_update_checking)
                            } else {
                                null
                            },
                            icon = {
                                CouiSettingsIcon(
                                    icon = Icons.Rounded.SystemUpdate,
                                    tint = SettingsIconColors.Green,
                                    enabled = !checkingUpdate,
                                )
                            },
                            enabled = !checkingUpdate,
                            onClick = checkForUpdate,
                        )
                    }
                    item {
                        CouiPreference(
                            title = stringResource(R.string.ui_about_feedback_title),
                            icon = {
                                CouiSettingsIcon(
                                    Icons.Rounded.BugReport,
                                    SettingsIconColors.Orange,
                                )
                            },
                            onClick = { openUrl("https://github.com/Mangi-11/Eta/issues") },
                        )
                    }
                    item {
                        CouiPreference(
                            title = stringResource(R.string.ui_about_github_star_title),
                            summary = stringResource(R.string.ui_about_github_star_hint),
                            icon = { CouiSettingsIcon(Icons.Rounded.Code, SettingsIconColors.Blue) },
                            onClick = { openUrl("https://github.com/Mangi-11/Eta") },
                        )
                    }
                }

                CouiPageBottomSpacer()
            }
        }

        AppearanceUiStyle.MIUIX -> {
            MiuixScaffoldPage(
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
                        MiuixDialogActions(
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

                item(key = "section_context_extensions_title") {
                    SmallTitle(stringResource(R.string.settings_context_extensions))
                }
                item(key = "section_context_extensions") {
                    SettingsGroupCard {
                        SwitchPref(
                            context = context,
                            prefs = agentPrefs,
                            title = stringResource(R.string.settings_auto_compaction),
                            key = Prefs.Keys.AGENT_AUTO_COMPACTION_ENABLED,
                            icon = Icons.Rounded.Layers,
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
                        ArrowPreference(
                            title = stringResource(R.string.ui_misc_route_subagents),
                            startAction = { SettingsIcon(Icons.Rounded.JoinLeft) },
                            onClick = { onNavigate(AppRoute.Subagents) },
                        )
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
                            title = stringResource(R.string.route_scheduled_tasks),
                            startAction = { SettingsIcon(Icons.Rounded.Schedule) },
                            onClick = { onNavigate(AppRoute.ScheduledTasks) },
                        )
                        ArrowPreference(
                            title = stringResource(R.string.conversation_dock_characters),
                            startAction = { SettingsIcon(Icons.Rounded.SportsBar) },
                            onClick = { onNavigate(AppRoute.Characters) },
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
                        val imageGenSettings by remember { ImageGenSettingsRepository.settingsFlow() }
                            .collectAsState(initial = ImageGenSettings())
                        ArrowPreference(
                            title = stringResource(R.string.image_gen_settings_title),
                            summary = if (imageGenSettings.enabled) {
                                stringResource(R.string.ui_misc_enabled_with_value, imageGenSettings.backend.shortName())
                            } else {
                                stringResource(R.string.ui_misc_not_enabled)
                            },
                            startAction = { SettingsIcon(Icons.Rounded.Image) },
                            onClick = { onNavigate(AppRoute.ImageGenSettings) },
                        )
                        SwitchPref(
                            context = context,
                            prefs = agentPrefs,
                            title = stringResource(R.string.settings_virtual_display_title),
                            key = Prefs.Keys.AGENT_VIRTUAL_DISPLAY_ENABLED,
                            icon = Icons.Rounded.Layers,
                            history = enhancementHistory,
                        )
                        ArrowPreference(
                            title = stringResource(R.string.ui_linux_tool_environment_314d22),
                            startAction = { SettingsIcon(Icons.Rounded.Inventory2) },
                            onClick = { onNavigate(AppRoute.LinuxEnvironment) },
                        )
                    }
                }

                item(key = "system_enhancements") {
                    SettingsGroupCard {
                        ArrowPreference(
                            title = stringResource(R.string.capability_enhancements),
                            startAction = { SettingsIcon(Icons.Rounded.Security) },
                            onClick = { onNavigate(AppRoute.SystemEnhance) },
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
                            endActions = {
                                Text(
                                    text = assistantRoleStatus.displayValue(),
                                    fontSize = MiuixTheme.textStyles.body2.fontSize,
                                    color = if (assistantRoleStatus.isDefaultAssistant) {
                                        StatusSuccess
                                    } else {
                                        MiuixTheme.colorScheme.onSurfaceVariantActions
                                    },
                                )
                            },
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

                item(key = "section_general_title") {
                    SmallTitle(stringResource(R.string.settings_general))
                }
                item(key = "section_general") {
                    SettingsGroupCard {
                        SwitchPref(
                            context = context,
                            prefs = agentPrefs,
                            title = stringResource(R.string.settings_keep_screen_on),
                            key = Prefs.Keys.AGENT_KEEP_SCREEN_ON_ENABLED,
                            icon = Icons.Rounded.BrightnessHigh,
                            history = enhancementHistory,
                        )
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
                        ArrowPreference(
                            title = stringResource(R.string.data_backup_title),
                            startAction = { SettingsIcon(Icons.Rounded.Description) },
                            onClick = { onNavigate(AppRoute.DataBackup) },
                        )
                    }
                }

                item(key = "section_permissions_title") {
                    SmallTitle(stringResource(R.string.ui_permissions_560165))
                }
                item(key = "section_permissions") {
                    SettingsGroupCard {
                        ArrowPreference(
                            title = stringResource(R.string.ui_floating_window_permissions_076b77),
                            startAction = { SettingsIcon(Icons.Rounded.Layers) },
                            endActions = {
                                Text(
                                    text = stringResource(
                                        if (overlayGranted) R.string.status_authorized else R.string.status_unauthorized,
                                    ),
                                    fontSize = MiuixTheme.textStyles.body2.fontSize,
                                    color = if (overlayGranted) {
                                        MiuixTheme.colorScheme.onSurfaceVariantActions
                                    } else {
                                        MiuixTheme.colorScheme.error
                                    },
                                )
                            },
                            onClick = {
                                if (!overlayGranted) {
                                    runCatching {
                                        context.startActivity(
                                            Intent(
                                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                                Uri.parse("package:${context.packageName}"),
                                            ),
                                        )
                                    }
                                }
                            },
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
                                        icon = Icons.Rounded.VerifiedUser,
                                        enabled = prefs != null && !accessibilityProtectionPending,
                                    )
                                },
                                enabled = prefs != null && !accessibilityProtectionPending,
                            )
                        }
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
    }
}

@Composable
private fun AssistantRoleStatus.displayValue(): String = when (owner) {
    DefaultAssistantOwner.ETA -> "Eta"
    DefaultAssistantOwner.GEMINI -> "Gemini"
    DefaultAssistantOwner.OTHER -> ownerLabel ?: stringResource(R.string.assistant_role_status_other)
    DefaultAssistantOwner.NONE -> stringResource(R.string.assistant_role_status_unset)
}

@Composable
private fun SwitchPref(
    context: Context,
    prefs: SharedPreferences?,
    title: String,
    summary: String? = null,
    key: String,
    icon: ImageVector,
    iconTint: Color = Color.Unspecified,
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

    when (LocalUiStyle.current) {
        AppearanceUiStyle.COUI -> {
            CouiSwitchPreference(
                title = title,
                summary = summary,
                checked = checked,
                onCheckedChange = onToggle,
                icon = { CouiSettingsIcon(icon = icon, tint = iconTint, enabled = enabled) },
                enabled = enabled,
            )
        }
        AppearanceUiStyle.MIUIX -> {
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
    }
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
