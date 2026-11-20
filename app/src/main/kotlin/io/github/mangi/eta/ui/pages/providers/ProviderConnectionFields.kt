package io.github.mangi.eta.ui.pages.providers

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.AnthropicProviderSetting
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.data.model.GeminiEndpointMode
import io.github.mangi.eta.data.model.GeminiProviderSetting
import io.github.mangi.eta.data.model.OpenAiEndpointMode
import io.github.mangi.eta.data.model.ProviderAuthModes
import io.github.mangi.eta.data.model.ProviderSetting
import io.github.mangi.eta.ui.app.LocalUiStyle
import io.github.mangi.eta.ui.components.EtaGlassSpinnerPreference
import io.github.mangi.eta.ui.components.MiuixSecureTextField
import io.github.mangi.eta.ui.components.coui.CouiDropdownPreference
import io.github.mangi.eta.ui.components.coui.CouiIcon
import io.github.mangi.eta.ui.components.coui.CouiPageDefaults
import io.github.mangi.eta.ui.components.coui.CouiSwitchPreference
import io.github.mangi.eta.ui.components.coui.CouiTextField
import io.github.mangi.eta.ui.components.rememberDraftTextFieldState
import io.github.mangi.eta.ui.components.coui.CouiCardDivider as CouiDivider
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.SwitchPreference

@Composable
internal fun ProviderConnectionFields(
    provider: ProviderSetting,
    draft: ProviderConfigDraft,
    onDraftChange: (ProviderConfigDraft) -> Unit,
    loginState: ProviderLoginState,
    enabled: Boolean,
    browserError: String?,
    onOpenBrowser: () -> Unit,
    onManageUsage: () -> Unit,
    onLogin: () -> Unit,
    onDisconnect: () -> Unit,
    onCancel: () -> Unit,
) {
    val nameState = rememberDraftTextFieldState(draft.name) { onDraftChange(draft.copy(name = it)) }
    val baseUrlState = rememberDraftTextFieldState(draft.baseUrl) { onDraftChange(draft.copy(baseUrl = it)) }
    val apiKeyState = rememberDraftTextFieldState(draft.apiKey) { onDraftChange(draft.copy(apiKey = it)) }
    val vertexProjectState = rememberDraftTextFieldState(draft.vertexProject) { onDraftChange(draft.copy(vertexProject = it)) }
    val vertexLocationState = rememberDraftTextFieldState(draft.vertexLocation) { onDraftChange(draft.copy(vertexLocation = it)) }
    val anthropicVersionState = rememberDraftTextFieldState(draft.anthropicVersion) { onDraftChange(draft.copy(anthropicVersion = it)) }
    var apiKeyVisible by remember(provider.id) { mutableStateOf(false) }
    val oauth = ProviderAuthModes.isOAuth(draft.authMode)
    val accountMode = providerAccountAuthMode(provider)
    val isGeminiVertex = provider is GeminiProviderSetting &&
        GeminiEndpointMode.normalize(draft.endpointMode) == GeminiEndpointMode.VERTEX_AI
    val showWebSearchSwitch =
        (provider is GeminiProviderSetting && !isGeminiVertex) ||
            (draft.endpointMode == OpenAiEndpointMode.RESPONSES && provider !is AnthropicProviderSetting)
    val geminiPlatformTitle = stringResource(R.string.provider_i18n_gemini_platform)
    val geminiPlatformSummary = stringResource(
        if (isGeminiVertex) R.string.provider_i18n_gemini_vertex_summary else R.string.provider_i18n_gemini_ai_studio_summary,
    )
    val authMethodTitle = stringResource(R.string.provider_i18n_auth_method)
    val accountLoginLabel = stringResource(R.string.provider_i18n_auth_account_login)
    val apiKeyToggleDescription = stringResource(
        if (apiKeyVisible) R.string.page_hide_bb0e7e else R.string.page_show_71b677,
    )
    val endpointModeSummary = stringResource(
        if (draft.endpointMode == OpenAiEndpointMode.RESPONSES) {
            R.string.page_using_typed_items_with_semantic_streaming_events_f9c906
        } else {
            R.string.page_use_standard_chat_completions_ee4b1a
        },
    )

    when (LocalUiStyle.current) {
        AppearanceUiStyle.COUI -> {
            val colors = CouiPageDefaults.themeColors()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = CouiPageDefaults.rowPadding, vertical = 12.dp),
            ) {
                CouiTextField(
                    state = nameState,
                    label = stringResource(R.string.ui_name_1be7ae),
                    singleLine = true,
                    enabled = enabled,
                )
            }
            if (provider is GeminiProviderSetting) {
                CouiDivider()
                CouiDropdownPreference(
                    title = geminiPlatformTitle,
                    items = listOf("Google AI Studio", "Google Cloud Vertex AI"),
                    selectedIndex = if (isGeminiVertex) 1 else 0,
                    enabled = enabled,
                    summary = geminiPlatformSummary,
                    onSelectedIndexChange = { index ->
                        onDraftChange(
                            draft.withGeminiEndpointMode(
                                if (index == 1) GeminiEndpointMode.VERTEX_AI else GeminiEndpointMode.AI_STUDIO,
                            ),
                        )
                    },
                )
            }
            if (accountMode != null) {
                CouiDivider()
                CouiDropdownPreference(
                    title = authMethodTitle,
                    items = listOf("API Key", accountLoginLabel),
                    selectedIndex = if (oauth) 1 else 0,
                    enabled = enabled,
                    onSelectedIndexChange = { index ->
                        onDraftChange(draft.withAuthMode(if (index == 0) ProviderAuthModes.API_KEY else accountMode, provider))
                    },
                )
            }
            if (oauth) {
                CouiDivider()
                ProviderAccountControls(
                    state = loginState,
                    authMode = draft.authMode,
                    enabled = enabled,
                    browserError = browserError,
                    onOpenBrowser = onOpenBrowser,
                    onManageUsage = onManageUsage,
                    onLogin = onLogin,
                    onDisconnect = onDisconnect,
                    onCancel = onCancel,
                )
            } else {
                CouiDivider()
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = CouiPageDefaults.rowPadding, vertical = 12.dp),
                ) {
                    CouiTextField(
                        state = baseUrlState,
                        label = "Base URL",
                        singleLine = true,
                        enabled = enabled,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    CouiTextField(
                        state = apiKeyState,
                        label = "API Key",
                        singleLine = true,
                        enabled = enabled,
                        secretVisible = apiKeyVisible,
                        trailingIcon = {
                            Box(
                                modifier = Modifier
                                    .padding(start = 8.dp)
                                    .size(28.dp)
                                    .clickable(role = Role.Button) { apiKeyVisible = !apiKeyVisible },
                                contentAlignment = Alignment.Center,
                            ) {
                                CouiIcon(
                                    imageVector = if (apiKeyVisible) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,
                                    contentDescription = apiKeyToggleDescription,
                                    modifier = Modifier.size(20.dp),
                                    tint = colors.summary,
                                )
                            }
                        },
                    )
                    if (isGeminiVertex) {
                        Spacer(modifier = Modifier.height(12.dp))
                        CouiTextField(
                            state = vertexProjectState,
                            label = "Project ID / Number",
                            placeholder = stringResource(R.string.provider_i18n_vertex_project_placeholder),
                            singleLine = true,
                            enabled = enabled,
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        CouiTextField(
                            state = vertexLocationState,
                            label = "Location",
                            placeholder = GeminiProviderSetting.DEFAULT_VERTEX_LOCATION,
                            singleLine = true,
                            enabled = enabled,
                        )
                    }
                    if (provider is AnthropicProviderSetting) {
                        Spacer(modifier = Modifier.height(12.dp))
                        CouiTextField(
                            state = anthropicVersionState,
                            label = "anthropic-version",
                            singleLine = true,
                            enabled = enabled,
                        )
                    }
                }
                if (provider !is AnthropicProviderSetting && provider !is GeminiProviderSetting) {
                    CouiDivider()
                    CouiDropdownPreference(
                        title = stringResource(R.string.ui_endpoint_mode_3c8546),
                        items = listOf("Chat Completions API", "Responses API"),
                        selectedIndex = if (draft.endpointMode == OpenAiEndpointMode.RESPONSES) 1 else 0,
                        enabled = enabled,
                        summary = endpointModeSummary,
                        onSelectedIndexChange = { index ->
                            onDraftChange(
                                draft.copy(
                                    endpointMode = if (index == 1) OpenAiEndpointMode.RESPONSES else OpenAiEndpointMode.CHAT_COMPLETIONS,
                                ),
                            )
                        },
                    )
                }
            }
            if (showWebSearchSwitch) {
                CouiDivider()
                CouiSwitchPreference(
                    title = if (provider is GeminiProviderSetting) "Google Search" else stringResource(R.string.ui_server_side_web_search_ddb8e0),
                    summary = if (provider is GeminiProviderSetting) {
                        null
                    } else {
                        stringResource(R.string.ui_allows_the_model_to_call_web_searches_provided_by_th_2f752f)
                    },
                    checked = draft.hostedWebSearchEnabled,
                    enabled = enabled,
                    onCheckedChange = { onDraftChange(draft.copy(hostedWebSearchEnabled = it)) },
                )
            }
        }

        AppearanceUiStyle.MIUIX -> {
            Column(modifier = Modifier.padding(16.dp)) {
                TextField(
                    state = nameState,
                    lineLimits = TextFieldLineLimits.SingleLine,
                    label = stringResource(R.string.ui_name_1be7ae),
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (provider is GeminiProviderSetting) {
                EtaGlassSpinnerPreference(
                    title = geminiPlatformTitle,
                    items = listOf(
                        DropdownItem(text = "Google AI Studio"),
                        DropdownItem(text = "Google Cloud Vertex AI"),
                    ),
                    selectedIndex = if (isGeminiVertex) 1 else 0,
                    enabled = enabled,
                    summary = geminiPlatformSummary,
                    onSelectedIndexChange = { index ->
                        onDraftChange(
                            draft.withGeminiEndpointMode(
                                if (index == 1) GeminiEndpointMode.VERTEX_AI else GeminiEndpointMode.AI_STUDIO,
                            ),
                        )
                    },
                )
            }
            if (accountMode != null) {
                EtaGlassSpinnerPreference(
                    title = authMethodTitle,
                    items = listOf(DropdownItem(text = "API Key"), DropdownItem(text = accountLoginLabel)),
                    selectedIndex = if (oauth) 1 else 0,
                    enabled = enabled,
                    onSelectedIndexChange = { index ->
                        onDraftChange(draft.withAuthMode(if (index == 0) ProviderAuthModes.API_KEY else accountMode, provider))
                    },
                )
            }
            if (oauth) {
                ProviderAccountControls(
                    state = loginState,
                    authMode = draft.authMode,
                    enabled = enabled,
                    browserError = browserError,
                    onOpenBrowser = onOpenBrowser,
                    onManageUsage = onManageUsage,
                    onLogin = onLogin,
                    onDisconnect = onDisconnect,
                    onCancel = onCancel,
                )
            } else {
                Column(modifier = Modifier.padding(16.dp)) {
                    TextField(
                        state = baseUrlState,
                        lineLimits = TextFieldLineLimits.SingleLine,
                        label = "Base URL",
                        enabled = enabled,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    MiuixSecureTextField(
                        state = apiKeyState,
                        label = "API Key",
                        enabled = enabled,
                        visible = apiKeyVisible,
                        trailingIcon = {
                            IconButton(onClick = { apiKeyVisible = !apiKeyVisible }) {
                                Icon(
                                    imageVector = if (apiKeyVisible) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,
                                    contentDescription = apiKeyToggleDescription,
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (isGeminiVertex) {
                        Spacer(modifier = Modifier.height(12.dp))
                        TextField(
                            state = vertexProjectState,
                            lineLimits = TextFieldLineLimits.SingleLine,
                            label = "Project ID / Number",
                            enabled = enabled,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        TextField(
                            state = vertexLocationState,
                            lineLimits = TextFieldLineLimits.SingleLine,
                            label = "Location",
                            enabled = enabled,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (provider is AnthropicProviderSetting) {
                        Spacer(modifier = Modifier.height(12.dp))
                        TextField(
                            state = anthropicVersionState,
                            lineLimits = TextFieldLineLimits.SingleLine,
                            label = "anthropic-version",
                            enabled = enabled,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                if (provider !is AnthropicProviderSetting && provider !is GeminiProviderSetting) {
                    EtaGlassSpinnerPreference(
                        title = stringResource(R.string.ui_endpoint_mode_3c8546),
                        items = listOf(DropdownItem(text = "Chat Completions API"), DropdownItem(text = "Responses API")),
                        selectedIndex = if (draft.endpointMode == OpenAiEndpointMode.RESPONSES) 1 else 0,
                        enabled = enabled,
                        summary = endpointModeSummary,
                        onSelectedIndexChange = { index ->
                            onDraftChange(draft.copy(endpointMode = if (index == 1) OpenAiEndpointMode.RESPONSES else OpenAiEndpointMode.CHAT_COMPLETIONS))
                        },
                    )
                }
            }
            if (showWebSearchSwitch) {
                SwitchPreference(
                    title = if (provider is GeminiProviderSetting) "Google Search" else stringResource(R.string.ui_server_side_web_search_ddb8e0),
                    summary = if (provider is GeminiProviderSetting) null
                    else stringResource(R.string.ui_allows_the_model_to_call_web_searches_provided_by_th_2f752f),
                    checked = draft.hostedWebSearchEnabled,
                    enabled = enabled,
                    onCheckedChange = { onDraftChange(draft.copy(hostedWebSearchEnabled = it)) },
                )
            }
        }
    }
}
