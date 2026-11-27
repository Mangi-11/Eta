package io.github.mangi.eta.ui.screens.providers

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.ProviderAuthModes
import io.github.mangi.eta.ui.components.ScaffoldPage
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun ChatGptProviderLoginScreen(
    onBack: () -> Unit,
    onProviderAdded: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val store = remember(scope) { ProviderLoginStore(providerId = null, parentScope = scope) }
    val state by store.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val fallbackName = stringResource(R.string.provider_i18n_account_title, "ChatGPT")
    val codexFallbackName = stringResource(R.string.provider_i18n_account_title, "Codex")
    var browserError by remember(state.prompt) { mutableStateOf<String?>(null) }
    val openBrowser: () -> Unit = {
        state.prompt?.let { browserError = openProviderAccountLink(context, it.authorizationUrl) }
    }

    DisposableEffect(store) {
        onDispose { store.close() }
    }
    LaunchedEffect(state.prompt) {
        if (state.prompt != null) openBrowser()
    }

    val addedProviderId = state.addedProviderId
    LaunchedEffect(addedProviderId, state.isBusy, state.modelSyncFailed) {
        if (addedProviderId != null && !state.isBusy && !state.modelSyncFailed) onProviderAdded(addedProviderId)
    }

    ScaffoldPage(title = "ChatGPT", onBack = onBack) {
        item(key = "account") {
            ProviderSection(title = null) {
                if (!state.isBusy) {
                    state.message?.let { message ->
                        Text(
                            text = message,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
                if (!state.isBusy && addedProviderId == null) {
                    BasicComponent(
                        title = stringResource(R.string.provider_i18n_sign_in_with, "ChatGPT"),
                        onClick = { store.addChatGptAccount(ProviderAuthModes.CHATGPT_PLAN, fallbackName) },
                    )
                    BasicComponent(
                        title = stringResource(R.string.provider_i18n_sign_in_with, "Codex"),
                        onClick = {
                            store.addChatGptAccount(
                                ProviderAuthModes.OPENAI_CHATGPT,
                                codexFallbackName,
                            )
                        },
                    )
                } else if (!state.isBusy && addedProviderId != null && state.modelSyncFailed) {
                    BasicComponent(
                        title = stringResource(R.string.route_provider_details),
                        onClick = { onProviderAdded(addedProviderId) },
                    )
                } else if (state.isBusy) {
                    ProviderAccountControls(
                        state = state,
                        authMode = state.authMode,
                        enabled = !state.isBusy,
                        browserError = browserError,
                        onOpenBrowser = openBrowser,
                        onManageUsage = {},
                        onLogin = {},
                        onDisconnect = {},
                        onCancel = store::cancel,
                    )
                }
            }
        }
    }
}
