@file:android.annotation.SuppressLint("LocalContextGetResourceValueCall")

package io.github.mangi.eta.ui.screens.providers

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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.EtaApp
import io.github.mangi.eta.R
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.model.AnthropicProviderSetting
import io.github.mangi.eta.data.model.CustomProviderSetting
import io.github.mangi.eta.data.model.GeminiEndpointMode
import io.github.mangi.eta.data.model.GeminiProviderSetting
import io.github.mangi.eta.data.model.OpenAiEndpointMode
import io.github.mangi.eta.data.model.ProviderSetting
import io.github.mangi.eta.data.model.withId
import io.github.mangi.eta.data.model.withModels
import io.github.mangi.eta.data.provider.BuiltinProviders
import io.github.mangi.eta.data.provider.OfficialModelCatalog
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.data.repository.catalog.RemoteModelFetcher
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import io.github.mangi.eta.ui.components.DialogActions
import io.github.mangi.eta.ui.components.PageBottomSpacer
import io.github.mangi.eta.ui.components.PageScaffold
import io.github.mangi.eta.ui.components.ScaffoldPage
import io.github.mangi.eta.ui.components.StatusError
import io.github.mangi.eta.ui.components.StatusSuccess
import io.github.mangi.eta.ui.components.currentGlassOverlayBackdrop
import io.github.mangi.eta.ui.components.rememberDraftTextFieldState
import io.github.mangi.eta.ui.layout.horizontalCutoutPadding
import io.github.mangi.eta.ui.navigation.NewProviderType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.glass.GlassDialog
import top.yukonga.miuix.kmp.glass.GlassSegmentedTabRow
import top.yukonga.miuix.kmp.glass.GlassTopAppBarDefaults
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import top.yukonga.miuix.kmp.utils.springAnimateToPage
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

@Composable
internal fun ModelProviderDetailScreen(
    providerId: String? = null,
    newType: NewProviderType? = null,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val providers by ProviderRepository.providersFlow().collectAsStateWithLifecycle(initialValue = emptyList())
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
                endpointMode = GeminiEndpointMode.AI_STUDIO,
                baseUrl = GeminiProviderSetting.DEFAULT_BASE_URL,
            )
            null -> null
        }
    }

    LaunchedEffect(Unit) {
        RuntimeConfigRepository.ensureDefaults(EtaApp.serviceInstance)
    }

    if (provider == null && draft == null) {
        ScaffoldPage(
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
        return
    }

    val initial = provider ?: draft!!
    val isNew = provider == null
    val providerDisplayName = BuiltinProviders.defaultNameRes(initial.id, initial.name)?.let { stringResource(it) } ?: initial.name
    var currentTab by remember { mutableIntStateOf(0) }
    var configDraft by rememberSaveable(
        initial.id,
        stateSaver = ProviderConfigDraftSaver,
    ) {
        mutableStateOf(
            ProviderConfigDraft.from(initial).let { base ->
                if (isNew) base else base.copy(name = providerDisplayName)
            },
        )
    }
    val title = if (isNew) stringResource(R.string.page_create_new_provider_36cab9) else providerDisplayName
    val tabs = listOf(
        stringResource(R.string.page_configuration_d7d7ce),
        stringResource(R.string.page_model_98fd0c),
    )

    val pagerState = rememberPagerState(initialPage = currentTab, pageCount = { if (isNew) 1 else tabs.size })
    val currentPage by remember { derivedStateOf { pagerState.currentPage } }
    val configListState = rememberLazyListState()
    val modelsListState = rememberLazyListState()
    val configTopBarState = rememberTopAppBarState()
    val modelsTopBarState = rememberTopAppBarState()
    val configScrollBehavior = MiuixScrollBehavior(state = configTopBarState)
    val modelsScrollBehavior = MiuixScrollBehavior(state = modelsTopBarState)
    val activeListState = if (currentPage == 0) configListState else modelsListState
    val scrollBehavior = if (currentPage == 0) configScrollBehavior else modelsScrollBehavior
    val isContentScrolled by remember(activeListState) {
        derivedStateOf { activeListState.canScrollBackward }
    }
    val contentUnderTopBar by remember(activeListState, scrollBehavior) {
        derivedStateOf {
            activeListState.canScrollBackward ||
                scrollBehavior.state.overscrollOffset < -1f
        }
    }
    PageScaffold(
        title = title,
        onBack = onBack,
        scrollBehavior = scrollBehavior,
        isContentScrolled = isContentScrolled,
        topBarBottomContent = { backdrop ->
            val fallbackBackdrop = rememberLayerBackdrop()
            if (!isNew) {
                GlassSegmentedTabRow(
                    tabs = tabs,
                    selectedIndex = pagerState.targetPage,
                    onSelect = { index ->
                        currentTab = index
                        scope.launch { pagerState.springAnimateToPage(index) }
                    },
                    backdrop = backdrop ?: fallbackBackdrop,
                    surfaceAlpha = if (backdrop != null && contentUnderTopBar) 1f else 0f,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = GlassTopAppBarDefaults.HorizontalPadding)
                        .padding(top = 6.dp, bottom = 12.dp),
                )
            }
        },
    ) { paddingValues, _, sidePadding ->
        val topPadding = paddingValues.calculateTopPadding()
        Box(
            modifier = Modifier
                .fillMaxSize()
                .horizontalCutoutPadding(),
        ) {
            if (isNew) {
                ProviderConfigTab(
                    provider = initial,
                    draft = configDraft,
                    onDraftChange = { configDraft = it },
                    scope = scope,
                    isNew = true,
                    scrollBehavior = configScrollBehavior,
                    contentSidePadding = sidePadding,
                    contentTopPadding = topPadding,
                    listState = configListState,
                    onCreated = { id -> createdId = id },
                    onDeleted = onBack,
                )
            } else {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                    userScrollEnabled = false,
                    beyondViewportPageCount = 1,
                    key = { it },
                ) { page ->
                    when (page) {
                        0 -> ProviderConfigTab(
                            provider = initial,
                            draft = configDraft,
                            onDraftChange = { configDraft = it },
                            scope = scope,
                            isNew = false,
                            scrollBehavior = configScrollBehavior,
                            contentSidePadding = sidePadding,
                            contentTopPadding = topPadding,
                            listState = configListState,
                            onCreated = { id -> createdId = id },
                            onDeleted = onBack,
                        )
                        1 -> ProviderModelsTab(
                            provider = initial,
                            scope = scope,
                            scrollBehavior = modelsScrollBehavior,
                            contentSidePadding = sidePadding,
                            contentTopPadding = topPadding,
                            listState = modelsListState,
                        )
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
    contentSidePadding: Dp,
    contentTopPadding: Dp = 0.dp,
    listState: LazyListState = rememberLazyListState(),
    onCreated: (String) -> Unit,
    onDeleted: () -> Unit,
) {
    val context = LocalContext.current
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
    val loginState by loginStore.state.collectAsStateWithLifecycle()
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
                        val seeded = if (built is GeminiProviderSetting && built.isVertexAi && built.models.isEmpty()) {
                            val presets = OfficialModelCatalog.modelsForProvider(built).map { model ->
                                model.copy(id = ProviderRepository.newId(), isBuiltIn = false)
                            }
                            built.withModels(presets)
                        } else {
                            built
                        }
                        val added = ProviderRepository.addProvider(
                            seeded.withId(ProviderRepository.newId()),
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

    val scrollState = scrollBehavior?.state
    val nestedScrollConnection = scrollBehavior?.nestedScrollConnection
    val onOverscrollChanged: (Float) -> Unit = remember(scrollState) {
        { scrollState?.overscrollOffset = it }
    }
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .scrollEndHaptic()
            .overScrollVertical(onOffsetChanged = onOverscrollChanged)
            .then(if (nestedScrollConnection != null) Modifier.nestedScroll(nestedScrollConnection) else Modifier),
        contentPadding = PaddingValues(
            start = contentSidePadding,
            top = contentTopPadding,
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
                BasicComponent(
                    title = stringResource(R.string.ui_test_connection_10b7d8),
                    summary = testStatus,
                    enabled = !isWorking && !loginState.isBusy,
                    onClick = onTestConnection,
                )
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
                SwitchPreference(
                    title = stringResource(R.string.ui_enable_this_provider_683a76),
                    checked = draft.isEnabled,
                    onCheckedChange = { onDraftChange(draft.copy(isEnabled = it)) },
                )
            }
            SmallTitle(text = stringResource(R.string.ui_system_prompt_word_193981))
            TextField(
                state = rememberDraftTextFieldState(draft.systemPrompt) { onDraftChange(draft.copy(systemPrompt = it)) },
                lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = 4),
                label = stringResource(R.string.ui_leave_blank_to_use_the_default_mobile_agent_prompt_w_21e7c8),
                useLabelAsPlaceholder = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            )
        }

        item(key = "actions") {
            val saveText = stringResource(
                when {
                    isWorking -> R.string.page_saving_d70d42
                    creationCommitted -> R.string.page_created_62cfc5
                    isNew -> R.string.page_create_fcbd09
                    else -> R.string.page_save_configuration_817af1
                },
            )
            val removeText = stringResource(
                if (provider.isBuiltIn) {
                    R.string.page_reset_built_in_configuration_35b6ec
                } else {
                    R.string.page_remove_provider_9f848f
                },
            )
            val failPrefix = stringResource(R.string.page_fail_3e3c80)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(top = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
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
                status?.let { message ->
                    Text(
                        text = message,
                        style = MiuixTheme.textStyles.footnote2,
                        color = if (message.startsWith(failPrefix)) StatusError else StatusSuccess,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }

        item(key = "bottom_spacer") {
            PageBottomSpacer()
        }
    }

    val providerDisplayName = BuiltinProviders.defaultNameRes(provider.id, provider.name)?.let { stringResource(it) } ?: provider.name
    val deleteConfirmText = stringResource(if (isWorking) R.string.page_deleting_6f941d else R.string.page_delete_3755f5)
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
    val resetConfirmText = stringResource(if (isWorking) R.string.page_resetting_616090 else R.string.page_reset_3d8134)
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

    val overlayBackdrop = currentGlassOverlayBackdrop()
    GlassDialog(
        show = showDeleteDialog,
        backdrop = overlayBackdrop,
        title = stringResource(R.string.ui_remove_provider_9f848f),
        summary = stringResource(R.string.provider_delete_summary, providerDisplayName),
        onDismissRequest = { if (!isWorking) showDeleteDialog = false },
    ) {
        DialogActions(
            confirmText = deleteConfirmText,
            cancelEnabled = !isWorking,
            confirmEnabled = !isWorking,
            destructive = true,
            onCancel = { showDeleteDialog = false },
            onConfirm = onConfirmDelete,
        )
    }
    GlassDialog(
        show = showResetDialog,
        backdrop = overlayBackdrop,
        title = stringResource(R.string.ui_reset_built_in_configuration_35b6ec),
        summary = stringResource(R.string.provider_reset_summary, providerDisplayName),
        onDismissRequest = { if (!isWorking) showResetDialog = false },
    ) {
        DialogActions(
            confirmText = resetConfirmText,
            cancelEnabled = !isWorking,
            confirmEnabled = !isWorking,
            destructive = true,
            onCancel = { showResetDialog = false },
            onConfirm = onConfirmReset,
        )
    }
}

private suspend fun testConnection(
    context: android.content.Context,
    provider: ProviderSetting,
): String {
    if (provider is GeminiProviderSetting && provider.isVertexAi) {
        val selectedModelId = RuntimeConfigRepository.cachedSelectedModelId()
            ?.takeIf { selected -> provider.models.any { it.id == selected && it.isEnabled } }
            ?: provider.id.takeIf(String::isNotBlank)?.let { SettingsDataStore.selectedModelIdForProvider(it) }
        return RemoteModelFetcher.testVertexConnection(provider, preferredModelId = selectedModelId)
            .map { modelId -> context.getString(R.string.provider_i18n_vertex_test_ok, modelId) }
            .getOrElse { throwable ->
                context.getString(
                    R.string.provider_error,
                    throwable.message ?: throwable.javaClass.simpleName,
                )
            }
    }
    return RemoteModelFetcher.fetch(provider)
        .map { context.resources.getQuantityString(R.plurals.provider_models_fetched, it.size, it.size) }
        .getOrElse { throwable ->
            context.getString(
                R.string.provider_error,
                throwable.message ?: throwable.javaClass.simpleName,
            )
        }
}
