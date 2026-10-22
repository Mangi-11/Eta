package io.github.mangi.eta.ui.screens.tools

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.WebHttpMethod
import io.github.mangi.eta.data.model.WebToolBackend
import io.github.mangi.eta.ui.components.EtaOverlayDropdownPreference
import io.github.mangi.eta.ui.components.EtaPreference
import io.github.mangi.eta.ui.components.EtaPreferenceDefaults
import io.github.mangi.eta.ui.components.EtaPreferenceDivider
import io.github.mangi.eta.ui.components.EtaPreferenceGroup
import io.github.mangi.eta.ui.components.EtaPreferenceGroupTitle
import io.github.mangi.eta.ui.components.EtaTextButton
import io.github.mangi.eta.ui.components.ListEmptyState
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.components.StatusError
import io.github.mangi.eta.ui.components.StatusSuccess
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun WebToolsSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { WebToolsSettingsStore(context, scope) }
    val settings = store.settings
    val usesDeepSeek = settings.searchBackend == WebToolBackend.DEEPSEEK ||
        settings.fetchBackend == WebToolBackend.DEEPSEEK
    val usesRemote = settings.searchBackend != WebToolBackend.BUILTIN ||
        settings.fetchBackend != WebToolBackend.BUILTIN

    MiuixScaffoldPage(
        title = stringResource(R.string.web_tools_settings_title),
        onBack = onBack,
        modifier = Modifier.imePadding(),
    ) {
        if (!store.loaded) {
            item(key = "loading") { WebToolsLoadingState(store.message) }
            return@MiuixScaffoldPage
        }
        item(key = "service_title") {
            EtaPreferenceGroupTitle(stringResource(R.string.web_tools_group_service))
        }
        item(key = "service") {
            val searchBackendLabels = WebToolBackend.entries.map { it.searchLabel() }
            val fetchBackendLabels = WebToolBackend.entries.map { it.fetchLabel() }
            Column {
                EtaPreferenceGroup {
                    EtaOverlayDropdownPreference(
                        title = stringResource(R.string.web_tools_search_service),
                        items = searchBackendLabels,
                        selectedIndex = settings.searchBackend.ordinal,
                        onSelectedIndexChange = { index ->
                            WebToolBackend.entries.getOrNull(index)?.let {
                                store.edit(settings.copy(searchBackend = it))
                            }
                        },
                    )
                    EtaPreferenceDivider(hasLeading = false)
                    EtaOverlayDropdownPreference(
                        title = stringResource(R.string.web_tools_fetch_service),
                        items = fetchBackendLabels,
                        selectedIndex = settings.fetchBackend.ordinal,
                        onSelectedIndexChange = { index ->
                            WebToolBackend.entries.getOrNull(index)?.let {
                                store.edit(settings.copy(fetchBackend = it))
                            }
                        },
                    )
                }
                WebToolsNote(
                    stringResource(
                        if (usesRemote) R.string.web_tools_overview_remote_note
                        else R.string.web_tools_overview_builtin_note,
                    ),
                )
            }
        }
        if (usesDeepSeek) {
            item(key = "deepseek") {
                DeepSeekWebSection(store)
            }
        }
        if (settings.searchBackend == WebToolBackend.CUSTOM) {
            item(key = "custom_search") {
                CustomSearchSection(store)
            }
        }
        if (settings.fetchBackend == WebToolBackend.CUSTOM) {
            item(key = "custom_fetch") {
                CustomFetchSection(store)
            }
        }
        item(key = "save") {
            WebToolsSaveBlock(store)
        }
    }
}

@Composable
private fun DeepSeekWebSection(store: WebToolsSettingsStore) {
    val settings = store.settings
    val credentials = store.credentials
    var advanced by rememberSaveable { mutableStateOf(false) }
    val chevronRotation by animateFloatAsState(if (advanced) 180f else 0f)
    val linked = store.hasProviderDeepSeekKey && credentials.deepSeekApiKey.isBlank()

    Column {
        EtaPreferenceGroupTitle(stringResource(R.string.web_tools_backend_deepseek))
        EtaPreferenceGroup {
            WebToolsFieldColumn {
                WebToolsField(
                    label = if (store.hasProviderDeepSeekKey) {
                        stringResource(R.string.web_tools_api_key_optional)
                    } else {
                        "API Key"
                    },
                    value = credentials.deepSeekApiKey,
                    secret = true,
                    onChange = { store.editCredentials(credentials.copy(deepSeekApiKey = it.trim())) },
                )
            }
            EtaPreferenceDivider(hasLeading = false)
            EtaPreference(
                title = stringResource(R.string.speech_advanced),
                endActions = {
                    Icon(
                        imageVector = Icons.Rounded.ExpandMore,
                        contentDescription = stringResource(
                            if (advanced) R.string.speech_collapse else R.string.speech_expand,
                        ),
                        tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
                        modifier = Modifier.rotate(chevronRotation),
                    )
                },
                onClick = { advanced = !advanced },
            )
            AnimatedVisibility(visible = advanced) {
                Column {
                    EtaPreferenceDivider(hasLeading = false)
                    WebToolsFieldColumn {
                        WebToolsField(
                            label = "Base URL",
                            value = settings.deepSeek.baseUrl,
                            onChange = {
                                store.edit(settings.copy(deepSeek = settings.deepSeek.copy(baseUrl = it.trim())))
                            },
                        )
                    }
                }
            }
        }
        WebToolsNote(
            stringResource(
                if (linked) R.string.web_tools_deepseek_linked_note
                else R.string.web_tools_deepseek_note,
            ),
        )
    }
}

@Composable
private fun CustomSearchSection(store: WebToolsSettingsStore) {
    val settings = store.settings
    val config = settings.customSearch
    val credentials = store.credentials
    var advanced by rememberSaveable { mutableStateOf(false) }
    val chevronRotation by animateFloatAsState(if (advanced) 180f else 0f)

    Column {
        EtaPreferenceGroupTitle(stringResource(R.string.web_tools_group_custom_search))
        EtaPreferenceGroup {
            EtaOverlayDropdownPreference(
                title = stringResource(R.string.web_tools_http_method),
                items = WebHttpMethod.entries.map { it.name },
                selectedIndex = config.method.ordinal,
                onSelectedIndexChange = { index ->
                    WebHttpMethod.entries.getOrNull(index)?.let {
                        store.edit(settings.copy(customSearch = config.copy(method = it)))
                    }
                },
            )
            EtaPreferenceDivider(hasLeading = false)
            WebToolsFieldColumn {
                WebToolsField(
                    label = stringResource(R.string.web_tools_endpoint_url),
                    value = config.url,
                    onChange = { store.edit(settings.copy(customSearch = config.copy(url = it.trim()))) },
                )
                WebToolsField(
                    label = stringResource(R.string.web_tools_api_key_optional),
                    value = credentials.customSearchApiKey,
                    secret = true,
                    onChange = { store.editCredentials(credentials.copy(customSearchApiKey = it.trim())) },
                )
            }
            EtaPreferenceDivider(hasLeading = false)
            EtaPreference(
                title = stringResource(R.string.web_tools_mapping_and_template),
                endActions = {
                    Icon(
                        imageVector = Icons.Rounded.ExpandMore,
                        contentDescription = stringResource(
                            if (advanced) R.string.speech_collapse else R.string.speech_expand,
                        ),
                        tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
                        modifier = Modifier.rotate(chevronRotation),
                    )
                },
                onClick = { advanced = !advanced },
            )
            AnimatedVisibility(visible = advanced) {
                Column {
                    EtaPreferenceDivider(hasLeading = false)
                    WebToolsFieldColumn {
                        if (config.method == WebHttpMethod.POST) {
                            WebToolsField(
                                label = stringResource(R.string.web_tools_body_template),
                                value = config.bodyTemplate,
                                singleLine = false,
                                onChange = {
                                    store.edit(settings.copy(customSearch = config.copy(bodyTemplate = it)))
                                },
                            )
                        }
                        WebToolsField(
                            label = stringResource(R.string.web_tools_results_path),
                            value = config.resultsPath,
                            onChange = {
                                store.edit(settings.copy(customSearch = config.copy(resultsPath = it.trim())))
                            },
                        )
                        WebToolsField(
                            label = stringResource(R.string.web_tools_title_field),
                            value = config.titleField,
                            onChange = {
                                store.edit(settings.copy(customSearch = config.copy(titleField = it.trim())))
                            },
                        )
                        WebToolsField(
                            label = stringResource(R.string.web_tools_url_field),
                            value = config.urlField,
                            onChange = {
                                store.edit(settings.copy(customSearch = config.copy(urlField = it.trim())))
                            },
                        )
                        WebToolsField(
                            label = stringResource(R.string.web_tools_snippet_field),
                            value = config.snippetField,
                            onChange = {
                                store.edit(settings.copy(customSearch = config.copy(snippetField = it.trim())))
                            },
                        )
                        WebToolsField(
                            label = stringResource(R.string.web_tools_custom_headers),
                            value = config.headersText,
                            singleLine = false,
                            onChange = {
                                store.edit(settings.copy(customSearch = config.copy(headersText = it)))
                            },
                        )
                    }
                }
            }
        }
        WebToolsNote(stringResource(R.string.web_tools_custom_search_note))
    }
}

@Composable
private fun CustomFetchSection(store: WebToolsSettingsStore) {
    val settings = store.settings
    val config = settings.customFetch
    val credentials = store.credentials
    var advanced by rememberSaveable { mutableStateOf(false) }
    val chevronRotation by animateFloatAsState(if (advanced) 180f else 0f)

    Column {
        EtaPreferenceGroupTitle(stringResource(R.string.web_tools_group_custom_fetch))
        EtaPreferenceGroup {
            EtaOverlayDropdownPreference(
                title = stringResource(R.string.web_tools_http_method),
                items = WebHttpMethod.entries.map { it.name },
                selectedIndex = config.method.ordinal,
                onSelectedIndexChange = { index ->
                    WebHttpMethod.entries.getOrNull(index)?.let {
                        store.edit(settings.copy(customFetch = config.copy(method = it)))
                    }
                },
            )
            EtaPreferenceDivider(hasLeading = false)
            WebToolsFieldColumn {
                WebToolsField(
                    label = stringResource(R.string.web_tools_endpoint_url),
                    value = config.url,
                    onChange = { store.edit(settings.copy(customFetch = config.copy(url = it.trim()))) },
                )
                WebToolsField(
                    label = stringResource(R.string.web_tools_api_key_optional),
                    value = credentials.customFetchApiKey,
                    secret = true,
                    onChange = { store.editCredentials(credentials.copy(customFetchApiKey = it.trim())) },
                )
            }
            EtaPreferenceDivider(hasLeading = false)
            EtaPreference(
                title = stringResource(R.string.web_tools_mapping_and_template),
                endActions = {
                    Icon(
                        imageVector = Icons.Rounded.ExpandMore,
                        contentDescription = stringResource(
                            if (advanced) R.string.speech_collapse else R.string.speech_expand,
                        ),
                        tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
                        modifier = Modifier.rotate(chevronRotation),
                    )
                },
                onClick = { advanced = !advanced },
            )
            AnimatedVisibility(visible = advanced) {
                Column {
                    EtaPreferenceDivider(hasLeading = false)
                    WebToolsFieldColumn {
                        if (config.method == WebHttpMethod.POST) {
                            WebToolsField(
                                label = stringResource(R.string.web_tools_body_template),
                                value = config.bodyTemplate,
                                singleLine = false,
                                onChange = {
                                    store.edit(settings.copy(customFetch = config.copy(bodyTemplate = it)))
                                },
                            )
                        }
                        WebToolsField(
                            label = stringResource(R.string.web_tools_content_field),
                            value = config.contentField,
                            onChange = {
                                store.edit(settings.copy(customFetch = config.copy(contentField = it.trim())))
                            },
                        )
                        WebToolsField(
                            label = stringResource(R.string.web_tools_title_field),
                            value = config.titleField,
                            onChange = {
                                store.edit(settings.copy(customFetch = config.copy(titleField = it.trim())))
                            },
                        )
                        WebToolsField(
                            label = stringResource(R.string.web_tools_custom_headers),
                            value = config.headersText,
                            singleLine = false,
                            onChange = {
                                store.edit(settings.copy(customFetch = config.copy(headersText = it)))
                            },
                        )
                    }
                }
            }
        }
        WebToolsNote(stringResource(R.string.web_tools_custom_fetch_note))
    }
}

@Composable
private fun WebToolBackend.searchLabel(): String = when (this) {
    WebToolBackend.BUILTIN -> "DuckDuckGo"
    WebToolBackend.DEEPSEEK -> stringResource(R.string.web_tools_backend_deepseek)
    WebToolBackend.CUSTOM -> stringResource(R.string.web_tools_backend_custom)
}

@Composable
private fun WebToolBackend.fetchLabel(): String = stringResource(
    when (this) {
        WebToolBackend.BUILTIN -> R.string.web_tools_backend_builtin
        WebToolBackend.DEEPSEEK -> R.string.web_tools_backend_deepseek
        WebToolBackend.CUSTOM -> R.string.web_tools_backend_custom
    },
)

@Composable
private fun WebToolsNote(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.footnote2,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = modifier.padding(horizontal = 32.dp, vertical = 8.dp),
    )
}

@Composable
private fun WebToolsLoadingState(failure: String?) {
    if (failure != null) {
        ListEmptyState(title = failure, icon = Icons.Rounded.ErrorOutline)
    } else {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 48.dp),
            contentAlignment = Alignment.Center,
        ) {
            InfiniteProgressIndicator(size = 24.dp)
        }
    }
}

@Composable
private fun WebToolsFieldColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
private fun WebToolsField(
    label: String,
    value: String,
    secret: Boolean = false,
    singleLine: Boolean = true,
    onChange: (String) -> Unit,
) {
    var visible by rememberSaveable(label) { mutableStateOf(false) }
    TextField(
        value = value,
        onValueChange = onChange,
        label = label,
        singleLine = singleLine,
        maxLines = if (singleLine) 1 else 5,
        modifier = Modifier.fillMaxWidth(),
        visualTransformation = if (secret && !visible) PasswordVisualTransformation() else VisualTransformation.None,
        trailingIcon = if (secret) {
            {
                IconButton(onClick = { visible = !visible }) {
                    Icon(
                        imageVector = if (visible) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,
                        contentDescription = stringResource(
                            if (visible) R.string.page_hide_bb0e7e else R.string.page_show_71b677,
                        ),
                    )
                }
            }
        } else {
            null
        },
    )
}

@Composable
private fun WebToolsSaveBlock(store: WebToolsSettingsStore) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = EtaPreferenceDefaults.SidePadding)
            .padding(top = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        EtaTextButton(
            text = stringResource(if (store.saving) R.string.speech_saving else R.string.speech_save),
            enabled = store.loaded && !store.saving,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.textButtonColorsPrimary(),
            onClick = store::save,
        )
        store.message?.let { message ->
            Text(
                text = message,
                style = MiuixTheme.textStyles.footnote2,
                color = if (store.messageIsError) StatusError else StatusSuccess,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}
