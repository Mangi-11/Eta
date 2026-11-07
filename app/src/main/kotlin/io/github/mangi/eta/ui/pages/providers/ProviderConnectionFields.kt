@file:android.annotation.SuppressLint("LocalContextGetResourceValueCall")

package io.github.mangi.eta.ui.pages.providers

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.itos.coui.layout.CouiPageDefaults
import com.itos.coui.layout.Divider
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.AnthropicProviderSetting
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.data.model.GeminiProviderSetting
import io.github.mangi.eta.data.model.OpenAiEndpointMode
import io.github.mangi.eta.data.model.ProviderAuthModes
import io.github.mangi.eta.data.model.ProviderSetting
import io.github.mangi.eta.ui.app.LocalUiStyle
import io.github.mangi.eta.ui.components.coui.CouiDropdownPreference
import io.github.mangi.eta.ui.components.coui.CouiIcon
import io.github.mangi.eta.ui.components.coui.CouiSwitchPreference
import io.github.mangi.eta.ui.components.coui.CouiTextField
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.preference.WindowSpinnerPreference

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
    val context = LocalContext.current
    var apiKeyVisible by remember { mutableStateOf(false) }
    val oauth = ProviderAuthModes.isOAuth(draft.authMode)
    val accountMode = providerAccountAuthMode(provider)
    val showWebSearchSwitch =
        provider is GeminiProviderSetting ||
            (draft.endpointMode == OpenAiEndpointMode.RESPONSES && provider !is AnthropicProviderSetting)

    when (LocalUiStyle.current) {
        AppearanceUiStyle.COUI -> {
            val colors = CouiPageDefaults.themeColors()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = CouiPageDefaults.rowPadding, vertical = 12.dp),
            ) {
                CouiTextField(
                    value = draft.name,
                    onValueChange = { onDraftChange(draft.copy(name = it)) },
                    label = stringResource(R.string.ui_name_1be7ae),
                    singleLine = true,
                    enabled = enabled,
                )
            }
            if (accountMode != null) {
                Divider()
                CouiDropdownPreference(
                    title = "认证方式",
                    items = listOf("API Key", "账号登录"),
                    selectedIndex = if (oauth) 1 else 0,
                    enabled = enabled,
                    onSelectedIndexChange = { index ->
                        onDraftChange(draft.withAuthMode(if (index == 0) ProviderAuthModes.API_KEY else accountMode, provider))
                    },
                )
            }
            if (oauth) {
                Divider()
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
                Divider()
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = CouiPageDefaults.rowPadding, vertical = 12.dp),
                ) {
                    CouiTextField(
                        value = draft.baseUrl,
                        onValueChange = { onDraftChange(draft.copy(baseUrl = it)) },
                        label = "Base URL",
                        singleLine = true,
                        enabled = enabled,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    CouiTextField(
                        value = draft.apiKey,
                        onValueChange = { onDraftChange(draft.copy(apiKey = it)) },
                        label = "API Key",
                        singleLine = true,
                        enabled = enabled,
                        visualTransformation = if (apiKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
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
                                    contentDescription = context.getString(
                                        if (apiKeyVisible) R.string.page_hide_bb0e7e else R.string.page_show_71b677,
                                    ),
                                    modifier = Modifier.size(20.dp),
                                    tint = colors.summary,
                                )
                            }
                        },
                    )
                    if (provider is AnthropicProviderSetting) {
                        Spacer(modifier = Modifier.height(12.dp))
                        CouiTextField(
                            value = draft.anthropicVersion,
                            onValueChange = { onDraftChange(draft.copy(anthropicVersion = it)) },
                            label = "anthropic-version",
                            singleLine = true,
                            enabled = enabled,
                        )
                    }
                }
                if (provider !is AnthropicProviderSetting && provider !is GeminiProviderSetting) {
                    Divider()
                    CouiDropdownPreference(
                        title = stringResource(R.string.ui_endpoint_mode_3c8546),
                        items = listOf("Chat Completions API", "Responses API"),
                        selectedIndex = if (draft.endpointMode == OpenAiEndpointMode.RESPONSES) 1 else 0,
                        enabled = enabled,
                        summary = context.getString(
                            if (draft.endpointMode == OpenAiEndpointMode.RESPONSES) {
                                R.string.page_using_typed_items_with_semantic_streaming_events_f9c906
                            } else {
                                R.string.page_use_standard_chat_completions_ee4b1a
                            },
                        ),
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
                Divider()
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
                    value = draft.name,
                    onValueChange = { onDraftChange(draft.copy(name = it)) },
                    label = stringResource(R.string.ui_name_1be7ae),
                    singleLine = true,
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (accountMode != null) {
                WindowSpinnerPreference(
                    title = "认证方式",
                    items = listOf(DropdownItem(text = "API Key"), DropdownItem(text = "账号登录")),
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
                        value = draft.baseUrl,
                        onValueChange = { onDraftChange(draft.copy(baseUrl = it)) },
                        label = "Base URL",
                        singleLine = true,
                        enabled = enabled,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    TextField(
                        value = draft.apiKey,
                        onValueChange = { onDraftChange(draft.copy(apiKey = it)) },
                        label = "API Key",
                        singleLine = true,
                        enabled = enabled,
                        visualTransformation = if (apiKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { apiKeyVisible = !apiKeyVisible }) {
                                Icon(
                                    imageVector = if (apiKeyVisible) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,
                                    contentDescription = context.getString(if (apiKeyVisible) R.string.page_hide_bb0e7e else R.string.page_show_71b677),
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (provider is AnthropicProviderSetting) {
                        Spacer(modifier = Modifier.height(12.dp))
                        TextField(
                            value = draft.anthropicVersion,
                            onValueChange = { onDraftChange(draft.copy(anthropicVersion = it)) },
                            label = "anthropic-version",
                            singleLine = true,
                            enabled = enabled,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                if (provider !is AnthropicProviderSetting && provider !is GeminiProviderSetting) {
                    WindowSpinnerPreference(
                        title = stringResource(R.string.ui_endpoint_mode_3c8546),
                        items = listOf(DropdownItem(text = "Chat Completions API"), DropdownItem(text = "Responses API")),
                        selectedIndex = if (draft.endpointMode == OpenAiEndpointMode.RESPONSES) 1 else 0,
                        enabled = enabled,
                        summary = context.getString(
                            if (draft.endpointMode == OpenAiEndpointMode.RESPONSES) R.string.page_using_typed_items_with_semantic_streaming_events_f9c906
                            else R.string.page_use_standard_chat_completions_ee4b1a,
                        ),
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
