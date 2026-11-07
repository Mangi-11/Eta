@file:android.annotation.SuppressLint("LocalContextGetResourceValueCall")

package io.github.mangi.eta.ui.pages.providers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.itos.coui.layout.CouiPageDefaults
import com.itos.coui.layout.Divider
import com.itos.coui.layout.Group
import com.itos.coui.text.CouiTextKind
import com.itos.coui.text.Text as CouiText
import io.github.mangi.eta.EtaApp
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.AnthropicProviderSetting
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.data.model.CustomProviderSetting
import io.github.mangi.eta.data.model.GeminiProviderSetting
import io.github.mangi.eta.data.model.OpenAiEndpointMode
import io.github.mangi.eta.data.model.ProviderSetting
import io.github.mangi.eta.data.model.withId
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.data.repository.RemoteModelFetcher
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import io.github.mangi.eta.ui.app.LocalUiStyle
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.MiuixPageBottomSpacer
import io.github.mangi.eta.ui.components.MiuixScaffold
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.components.StatusError
import io.github.mangi.eta.ui.components.StatusSuccess
import io.github.mangi.eta.ui.components.coui.CouiButton
import io.github.mangi.eta.ui.components.coui.CouiButtonRole
import io.github.mangi.eta.ui.components.coui.CouiChipTabs
import io.github.mangi.eta.ui.components.coui.CouiConfirmDialog
import io.github.mangi.eta.ui.components.coui.CouiPageBottomSpacer
import io.github.mangi.eta.ui.components.coui.CouiPreference
import io.github.mangi.eta.ui.components.coui.CouiScaffold
import io.github.mangi.eta.ui.components.coui.CouiScaffoldPage
import io.github.mangi.eta.ui.components.coui.CouiSwitchPreference
import io.github.mangi.eta.ui.components.coui.CouiTextField
import io.github.mangi.eta.ui.layout.horizontalCutoutPadding
import io.github.mangi.eta.ui.navigation.NewProviderType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

@Composable
internal fun ModelProviderDetailScreen(
    providerId: String? = null,
    newType: NewProviderType? = null,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
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
            NewProviderType.Gemini -> GeminiProviderSetting(
                id = "",
                name = "",
            )
            null -> null
        }
    }

    LaunchedEffect(Unit) {
        RuntimeConfigRepository.ensureDefaults(EtaApp.serviceInstance)
    }

    if (provider == null && draft == null) {
        when (LocalUiStyle.current) {
            AppearanceUiStyle.COUI -> {
                CouiScaffoldPage(
                    title = stringResource(R.string.route_provider_details),
                    onBack = onBack,
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(stringResource(R.string.ui_provider_does_not_exist_83cee6))
                        Spacer(modifier = Modifier.height(12.dp))
                        CouiButton(
                            text = stringResource(R.string.ui_return_11d024),
                            onClick = onBack,
                            role = CouiButtonRole.Primary,
                        )
                    }
                }
            }
            AppearanceUiStyle.MIUIX -> {
                MiuixScaffoldPage(
                    title = stringResource(R.string.route_provider_details),
                    onBack = onBack,
                ) {
                    item(key = "missing_provider") {
                        Column(
                            modifier = Modifier.fillParentMaxSize().padding(24.dp),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(stringResource(R.string.ui_provider_does_not_exist_83cee6))
                            Spacer(modifier = Modifier.height(12.dp))
                            TextButton(text = stringResource(R.string.ui_return_11d024), onClick = onBack)
                        }
                    }
                }
            }
        }
        return
    }

    val initial = provider ?: draft!!
    val isNew = provider == null
    var currentTab by remember { mutableIntStateOf(0) }
    var configDraft by rememberSaveable(
        initial.id,
        stateSaver = ProviderConfigDraftSaver,
    ) {
        mutableStateOf(ProviderConfigDraft.from(initial))
    }
    val title = if (isNew) context.getString(R.string.page_create_new_provider_36cab9) else initial.name
    val tabs = listOf(
        context.getString(R.string.page_configuration_d7d7ce),
        context.getString(R.string.page_model_98fd0c),
    )

    when (LocalUiStyle.current) {
        AppearanceUiStyle.COUI -> {
            CouiScaffold(title = title, onBack = onBack, compactTitle = true) { contentPadding, _, nestedScrollConnection, sidePadding ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .horizontalCutoutPadding()
                        .padding(top = contentPadding.calculateTopPadding()),
                ) {
                    if (!isNew) {
                        CouiChipTabs(
                            items = tabs.mapIndexed { index, label -> index to label },
                            selectedKey = currentTab,
                            onSelectedKeyChange = { currentTab = it },
                            modifier = Modifier.padding(horizontal = sidePadding),
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
                                nestedScrollConnection = nestedScrollConnection,
                                contentSidePadding = sidePadding,
                                onCreated = { id -> createdId = id },
                                onDeleted = onBack,
                            )
                            1 -> if (!isNew) {
                                ProviderModelsTab(
                                    provider = initial,
                                    scope = scope,
                                    nestedScrollConnection = nestedScrollConnection,
                                    contentSidePadding = sidePadding,
                                )
                            }
                        }
                    }
                }
            }
        }
        AppearanceUiStyle.MIUIX -> {
            MiuixScaffold(title = title, onBack = onBack) { paddingValues, scrollBehavior, sidePadding ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .horizontalCutoutPadding()
                        .padding(top = paddingValues.calculateTopPadding()),
                ) {
                    if (!isNew) {
                        TabRow(
                            tabs = tabs,
                            selectedTabIndex = currentTab,
                            onTabSelected = { currentTab = it },
                            modifier = Modifier.padding(
                                horizontal = sidePadding + 12.dp,
                                vertical = 8.dp,
                            ),
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
                                contentSidePadding = sidePadding,
                                onCreated = { id -> createdId = id },
                                onDeleted = onBack,
                            )
                            1 -> if (!isNew) {
                                ProviderModelsTab(
                                    provider = initial,
                                    scope = scope,
                                    scrollBehavior = scrollBehavior,
                                    contentSidePadding = sidePadding,
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
private fun ProviderConfigTab(
    provider: ProviderSetting,
    draft: ProviderConfigDraft,
    onDraftChange: (ProviderConfigDraft) -> Unit,
    scope: CoroutineScope,
    isNew: Boolean,
    scrollBehavior: ScrollBehavior? = null,
    nestedScrollConnection: NestedScrollConnection? = scrollBehavior?.nestedScrollConnection,
    contentSidePadding: Dp,
    onCreated: (String) -> Unit,
    onDeleted: () -> Unit,
) {
    val context = LocalContext.current
    val uiStyle = LocalUiStyle.current
    var headersExpanded by rememberSaveable { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var testStatus by remember { mutableStateOf<String?>(null) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showResetDialog by remember { mutableStateOf(false) }
    var isWorking by remember { mutableStateOf(false) }
    var creationCommitted by remember { mutableStateOf(false) }
    var loginInProgress by rememberSaveable(provider.id) { mutableStateOf(false) }
    var pendingInterruption by remember(provider.id) { mutableStateOf(loginInProgress) }
    val loginStore = remember(provider.id, scope) { ProviderLoginStore(provider.id, scope) }
    val loginState by loginStore.state.collectAsState()
    var browserError by remember(loginState.prompt) { mutableStateOf<String?>(null) }
    val openBrowser: () -> Unit = {
        loginState.prompt?.let { browserError = openProviderAccountLink(context, it.authorizationUrl) }
    }
    DisposableEffect(loginStore) {
        onDispose { loginStore.close() }
    }
    LaunchedEffect(loginStore, draft.authMode, provider.authMode) {
        loginStore.load(draft.authMode, interrupted = pendingInterruption)
        pendingInterruption = false
    }
    LaunchedEffect(loginState.phase) {
        loginInProgress = loginState.phase == ProviderLoginPhase.Saving || loginState.phase == ProviderLoginPhase.Authorizing
    }
    LaunchedEffect(loginState.prompt) {
        val prompt = loginState.prompt
        if (prompt != null && prompt.userCode == null) openBrowser()
    }
    val onTestConnection: () -> Unit = {
        val validationError = validateProviderDraft(context, draft)
        if (validationError != null) {
            testStatus = context.getString(R.string.provider_error, validationError)
        } else {
            scope.launch {
                isWorking = true
                testStatus = context.getString(R.string.page_testing_f43705)
                try {
                    testStatus = testConnection(
                        context,
                        draft.toProvider(provider),
                    )
                } finally {
                    isWorking = false
                }
            }
        }
    }
    val onSaveOrCreate: () -> Unit = {
        val validationError = validateProviderDraft(context, draft)
        if (validationError != null) {
            status = context.getString(R.string.provider_error, validationError)
        } else {
            scope.launch {
                isWorking = true
                val built = draft.toProvider(provider)
                try {
                    if (isNew) {
                        val added = ProviderRepository.addProvider(
                            built.withId(ProviderRepository.newId()),
                        )
                        if (added.isEnabled) {
                            RuntimeConfigRepository.setSelectedProviderId(added.id)
                        }
                        RuntimeConfigRepository.syncToRemotePreferences(
                            EtaApp.serviceInstance,
                        )
                        status = context.getString(R.string.capability_provider_created)
                        creationCommitted = true
                        onCreated(added.id)
                    } else {
                        ProviderRepository.updateProvider(built)
                        if (built.isEnabled) {
                            RuntimeConfigRepository.setSelectedProviderId(built.id)
                        }
                        RuntimeConfigRepository.syncToRemotePreferences(
                            EtaApp.serviceInstance,
                        )
                        status = when {
                            !built.isEnabled -> context.getString(R.string.page_saved_provider_not_enabled_7afa54)
                            else -> context.getString(R.string.capability_provider_saved)
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (throwable: Throwable) {
                    status = context.getString(
                        R.string.provider_error,
                        throwable.message ?: context.getString(R.string.provider_save_failed),
                    )
                } finally {
                    isWorking = false
                }
            }
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .scrollEndHaptic()
            .overScrollVertical()
            .then(if (nestedScrollConnection != null) Modifier.nestedScroll(nestedScrollConnection) else Modifier),
        contentPadding = PaddingValues(
            start = contentSidePadding,
            end = contentSidePadding,
        ),
        overscrollEffect = null,
    ) {
        item(key = "connection") {
            ProviderSection(title = stringResource(R.string.ui_connection_configuration_7d057b)) {
                ProviderConnectionFields(
                    provider = provider,
                    draft = draft,
                    onDraftChange = onDraftChange,
                    loginState = loginState,
                    enabled = !isWorking && !loginState.isBusy,
                    browserError = browserError,
                    onOpenBrowser = openBrowser,
                    onManageUsage = { browserError = openProviderAccountLink(context, "https://chatgpt.com/codex/settings/usage") },
                    onLogin = {
                        val validationError = validateProviderDraft(context, draft)
                        if (validationError != null) {
                            status = context.getString(R.string.provider_error, validationError)
                        } else {
                            loginStore.login(draft.toProvider(provider))
                        }
                    },
                    onDisconnect = loginStore::disconnect,
                    onCancel = loginStore::cancel,
                )
                if (uiStyle == AppearanceUiStyle.COUI) {
                    Divider()
                    CouiPreference(
                        title = stringResource(R.string.ui_test_connection_10b7d8),
                        summary = testStatus,
                        enabled = !isWorking && !loginState.isBusy,
                        onClick = onTestConnection,
                    )
                } else {
                    BasicComponent(
                        title = stringResource(R.string.ui_test_connection_10b7d8),
                        summary = testStatus,
                        enabled = !isWorking && !loginState.isBusy,
                        onClick = onTestConnection,
                    )
                }
            }
        }

        providerHeadersEditor(
            headers = draft.headers,
            expanded = headersExpanded,
            onExpandedChange = { headersExpanded = it },
            onHeadersChange = { onDraftChange(draft.copy(headers = it)) },
        )

        item(key = "preferences_and_prompt") {
            ProviderSection(title = stringResource(R.string.ui_preferences_and_strategies_2abd3c)) {
                if (uiStyle == AppearanceUiStyle.COUI) {
                    CouiSwitchPreference(
                        title = stringResource(R.string.ui_enable_this_provider_683a76),
                        checked = draft.isEnabled,
                        onCheckedChange = { onDraftChange(draft.copy(isEnabled = it)) },
                    )
                } else {
                    SwitchPreference(
                        title = stringResource(R.string.ui_enable_this_provider_683a76),
                        checked = draft.isEnabled,
                        onCheckedChange = { onDraftChange(draft.copy(isEnabled = it)) },
                    )
                }
            }
            if (uiStyle == AppearanceUiStyle.COUI) {
                Group(title = stringResource(R.string.ui_system_prompt_word_193981)) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = CouiPageDefaults.rowPadding, vertical = 12.dp),
                    ) {
                        CouiTextField(
                            value = draft.systemPrompt,
                            onValueChange = { onDraftChange(draft.copy(systemPrompt = it)) },
                            placeholder = stringResource(R.string.ui_leave_blank_to_use_the_default_mobile_agent_prompt_w_21e7c8),
                            singleLine = false,
                            minLines = 4,
                        )
                    }
                }
            } else {
                SmallTitle(text = stringResource(R.string.ui_system_prompt_word_193981))
                TextField(
                    value = draft.systemPrompt,
                    onValueChange = { onDraftChange(draft.copy(systemPrompt = it)) },
                    label = stringResource(R.string.ui_leave_blank_to_use_the_default_mobile_agent_prompt_w_21e7c8),
                    useLabelAsPlaceholder = true,
                    singleLine = false,
                    minLines = 4,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp),
                )
            }
        }

        item(key = "actions") {
            val saveText = when {
                isWorking -> context.getString(R.string.page_saving_d70d42)
                creationCommitted -> context.getString(R.string.page_created_62cfc5)
                isNew -> context.getString(R.string.page_create_fcbd09)
                else -> context.getString(R.string.page_save_configuration_817af1)
            }
            val removeText = if (provider.isBuiltIn) {
                context.getString(R.string.page_reset_built_in_configuration_35b6ec)
            } else {
                context.getString(R.string.page_remove_provider_9f848f)
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = if (uiStyle == AppearanceUiStyle.COUI) CouiPageDefaults.horizontalMargin else 12.dp)
                    .padding(top = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (uiStyle == AppearanceUiStyle.COUI) {
                    CouiButton(
                        text = saveText,
                        enabled = !isWorking && !loginState.isBusy && !creationCommitted,
                        role = CouiButtonRole.Primary,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onSaveOrCreate,
                    )
                    if (!isNew) {
                        Spacer(modifier = Modifier.height(12.dp))
                        CouiButton(
                            text = removeText,
                            enabled = !isWorking && !loginState.isBusy,
                            role = CouiButtonRole.Destructive,
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                if (provider.isBuiltIn) showResetDialog = true else showDeleteDialog = true
                            },
                        )
                    }
                } else {
                    TextButton(
                        text = saveText,
                        enabled = !isWorking && !loginState.isBusy && !creationCommitted,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                        onClick = onSaveOrCreate,
                    )
                    if (!isNew) {
                        Spacer(modifier = Modifier.height(12.dp))
                        TextButton(
                            text = removeText,
                            enabled = !isWorking && !loginState.isBusy,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.textButtonColors(
                                textColor = MiuixTheme.colorScheme.error,
                            ),
                            onClick = {
                                if (provider.isBuiltIn) showResetDialog = true else showDeleteDialog = true
                            },
                        )
                    }
                }
                status?.let { message ->
                    Text(
                        text = message,
                        style = MiuixTheme.textStyles.footnote2,
                        color = if (message.startsWith(context.getString(R.string.page_fail_3e3c80))) StatusError else StatusSuccess,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }

        item(key = "bottom_spacer") {
            if (uiStyle == AppearanceUiStyle.COUI) {
                CouiPageBottomSpacer()
            } else {
                MiuixPageBottomSpacer()
            }
        }
    }

    if (showDeleteDialog) {
        val deleteConfirmText = if (isWorking) context.getString(R.string.page_deleting_6f941d) else context.getString(R.string.page_delete_3755f5)
        val onConfirmDelete: () -> Unit = {
            scope.launch {
                isWorking = true
                try {
                    ProviderRepository.deleteProvider(provider.id)
                    RuntimeConfigRepository.syncToRemotePreferences(EtaApp.serviceInstance)
                    showDeleteDialog = false
                    onDeleted()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (throwable: Throwable) {
                    status = context.getString(
                        R.string.provider_error,
                        throwable.message ?: context.getString(R.string.provider_delete_failed),
                    )
                    showDeleteDialog = false
                } finally {
                    isWorking = false
                }
            }
        }
        when (uiStyle) {
            AppearanceUiStyle.COUI -> {
                CouiConfirmDialog(
                    visible = true,
                    title = stringResource(R.string.ui_remove_provider_9f848f),
                    message = stringResource(R.string.provider_delete_summary, provider.name),
                    confirmText = deleteConfirmText,
                    confirmEnabled = !isWorking,
                    cancelEnabled = !isWorking,
                    destructive = true,
                    onConfirm = onConfirmDelete,
                    onDismissRequest = { if (!isWorking) showDeleteDialog = false },
                )
            }
            AppearanceUiStyle.MIUIX -> {
                OverlayDialog(
                    show = true,
                    title = stringResource(R.string.ui_remove_provider_9f848f),
                    summary = stringResource(R.string.provider_delete_summary, provider.name),
                    onDismissRequest = { if (!isWorking) showDeleteDialog = false },
                ) {
                    MiuixDialogActions(
                        confirmText = deleteConfirmText,
                        cancelEnabled = !isWorking,
                        confirmEnabled = !isWorking,
                        destructive = true,
                        onCancel = { showDeleteDialog = false },
                        onConfirm = onConfirmDelete,
                    )
                }
            }
        }
    }

    if (showResetDialog) {
        val resetConfirmText = if (isWorking) context.getString(R.string.page_resetting_616090) else context.getString(R.string.page_reset_3d8134)
        val onConfirmReset: () -> Unit = {
            scope.launch {
                isWorking = true
                try {
                    ProviderRepository.resetBuiltIn(provider.id)
                    RuntimeConfigRepository.syncToRemotePreferences(EtaApp.serviceInstance)
                    status = context.getString(R.string.page_reset_a0cc65)
                    showResetDialog = false
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (throwable: Throwable) {
                    status = context.getString(
                        R.string.provider_error,
                        throwable.message ?: context.getString(R.string.provider_reset_failed),
                    )
                    showResetDialog = false
                } finally {
                    isWorking = false
                }
            }
        }
        when (uiStyle) {
            AppearanceUiStyle.COUI -> {
                CouiConfirmDialog(
                    visible = true,
                    title = stringResource(R.string.ui_reset_built_in_configuration_35b6ec),
                    message = stringResource(R.string.provider_reset_summary, provider.name),
                    confirmText = resetConfirmText,
                    confirmEnabled = !isWorking,
                    cancelEnabled = !isWorking,
                    destructive = true,
                    onConfirm = onConfirmReset,
                    onDismissRequest = { if (!isWorking) showResetDialog = false },
                )
            }
            AppearanceUiStyle.MIUIX -> {
                OverlayDialog(
                    show = true,
                    title = stringResource(R.string.ui_reset_built_in_configuration_35b6ec),
                    summary = stringResource(R.string.provider_reset_summary, provider.name),
                    onDismissRequest = { if (!isWorking) showResetDialog = false },
                ) {
                    MiuixDialogActions(
                        confirmText = resetConfirmText,
                        cancelEnabled = !isWorking,
                        confirmEnabled = !isWorking,
                        destructive = true,
                        onCancel = { showResetDialog = false },
                        onConfirm = onConfirmReset,
                    )
                }
            }
        }
    }
}

private suspend fun testConnection(
    context: android.content.Context,
    provider: ProviderSetting,
): String =
    RemoteModelFetcher.fetch(provider)
        .map { context.resources.getQuantityString(R.plurals.provider_models_fetched, it.size, it.size) }
        .getOrElse { throwable ->
            context.getString(
                R.string.provider_error,
                throwable.message ?: throwable.javaClass.simpleName,
            )
        }
