package io.github.mangi.eta.ui.pages.providers

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.data.model.ProviderAuthModes
import io.github.mangi.eta.ui.app.LocalUiStyle
import io.github.mangi.eta.ui.components.coui.CouiButton
import io.github.mangi.eta.ui.components.coui.CouiButtonRole
import io.github.mangi.eta.ui.components.coui.CouiPreference
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun ProviderAccountControls(
    state: ProviderLoginState,
    authMode: String,
    enabled: Boolean,
    browserError: String?,
    onOpenBrowser: () -> Unit,
    onManageUsage: () -> Unit,
    onLogin: () -> Unit,
    onDisconnect: () -> Unit,
    onCancel: () -> Unit,
) {
    val prompt = state.prompt
    val account = state.account
    val loggedIn = account != null && !account.needsReauthentication
    val needsLogin = !loggedIn || !account.planEnabled
    val brand = if (authMode == ProviderAuthModes.XAI_GROK) "Grok" else "Codex"
    val title = account?.email ?: account?.displayName ?: stringResource(R.string.provider_i18n_account_title, brand)
    val summary = browserError ?: state.message ?: when (state.phase) {
        ProviderLoginPhase.Loading -> stringResource(R.string.provider_i18n_login_loading)
        ProviderLoginPhase.Saving -> stringResource(R.string.provider_i18n_login_saving)
        ProviderLoginPhase.Disconnecting -> stringResource(R.string.provider_i18n_login_disconnecting)
        ProviderLoginPhase.Authorizing -> stringResource(
            if (prompt == null) R.string.provider_i18n_login_preparing else R.string.provider_i18n_login_browser_prompt,
        )
        ProviderLoginPhase.SyncingModels -> stringResource(R.string.provider_i18n_login_syncing_models)
        ProviderLoginPhase.Idle -> when {
            account == null -> stringResource(
                if (authMode == ProviderAuthModes.OPENAI_CHATGPT) {
                    R.string.provider_i18n_login_chatgpt_hint
                } else {
                    R.string.provider_i18n_login_grok_hint
                },
            )
            account.needsReauthentication -> stringResource(R.string.provider_i18n_login_reauth)
            !account.planEnabled -> stringResource(R.string.provider_i18n_login_plan_disabled)
            authMode == ProviderAuthModes.OPENAI_CHATGPT -> stringResource(R.string.provider_i18n_logged_in_codex)
            else -> stringResource(R.string.provider_i18n_logged_in_grok)
        }
    }
    val openBrowserText = stringResource(R.string.provider_i18n_open_browser)
    val cancelText = stringResource(R.string.action_cancel)
    val loginActionText = if (loggedIn) {
        stringResource(R.string.provider_i18n_reauthorize)
    } else {
        stringResource(R.string.provider_i18n_sign_in_with, brand)
    }
    val manageUsageText = stringResource(R.string.provider_i18n_manage_usage)
    val signOutText = stringResource(R.string.provider_i18n_sign_out)

    when (LocalUiStyle.current) {
        AppearanceUiStyle.COUI -> {
            CouiPreference(
                title = title,
                summary = summary,
                showChevron = false,
            )
            Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                prompt?.userCode?.let { userCode ->
                    SelectionContainer {
                        Text(
                            text = userCode,
                            style = MiuixTheme.textStyles.headline1,
                            modifier = Modifier.padding(bottom = 12.dp),
                        )
                    }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (state.isBusy) {
                        if (prompt != null) {
                            CouiButton(
                                text = openBrowserText,
                                onClick = onOpenBrowser,
                                role = CouiButtonRole.Primary,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (state.phase in setOf(ProviderLoginPhase.Saving, ProviderLoginPhase.Authorizing, ProviderLoginPhase.SyncingModels)) {
                            CouiButton(
                                text = cancelText,
                                onClick = onCancel,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    } else {
                        if (needsLogin) {
                            CouiButton(
                                text = loginActionText,
                                enabled = enabled,
                                onClick = onLogin,
                                role = CouiButtonRole.Primary,
                                modifier = Modifier.weight(1f),
                            )
                        } else if (authMode == ProviderAuthModes.OPENAI_CHATGPT) {
                            CouiButton(
                                text = manageUsageText,
                                enabled = enabled,
                                onClick = onManageUsage,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (loggedIn) {
                            CouiButton(
                                text = signOutText,
                                enabled = enabled,
                                onClick = onDisconnect,
                                role = CouiButtonRole.Destructive,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
        AppearanceUiStyle.MIUIX -> {
            BasicComponent(title = title, summary = summary)
            Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                prompt?.userCode?.let { userCode ->
                    SelectionContainer {
                        Text(
                            text = userCode,
                            style = MiuixTheme.textStyles.headline1,
                            modifier = Modifier.padding(bottom = 12.dp),
                        )
                    }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (state.isBusy) {
                        if (prompt != null) {
                            TextButton(text = openBrowserText, onClick = onOpenBrowser, modifier = Modifier.weight(1f))
                        }
                        if (state.phase in setOf(ProviderLoginPhase.Saving, ProviderLoginPhase.Authorizing, ProviderLoginPhase.SyncingModels)) {
                            TextButton(text = cancelText, onClick = onCancel, modifier = Modifier.weight(1f))
                        }
                    } else {
                        if (needsLogin) {
                            TextButton(
                                text = loginActionText,
                                enabled = enabled,
                                onClick = onLogin,
                                modifier = Modifier.weight(1f),
                            )
                        } else if (authMode == ProviderAuthModes.OPENAI_CHATGPT) {
                            TextButton(text = manageUsageText, enabled = enabled, onClick = onManageUsage, modifier = Modifier.weight(1f))
                        }
                        if (loggedIn) {
                            TextButton(text = signOutText, enabled = enabled, onClick = onDisconnect, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

internal fun openProviderAccountLink(context: Context, url: String): String? = try {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    null
} catch (_: ActivityNotFoundException) {
    context.getString(R.string.provider_i18n_no_browser)
} catch (_: SecurityException) {
    context.getString(R.string.provider_i18n_cannot_open_browser)
}
