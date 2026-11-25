package io.github.mangi.eta.ui.screens.providers

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.TravelExplore
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.EtaApp
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.ProviderSetting
import io.github.mangi.eta.data.model.ProviderSourceTypes
import io.github.mangi.eta.data.model.typeLabel
import io.github.mangi.eta.data.provider.BuiltinProviders
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import io.github.mangi.eta.ui.components.DialogActions
import io.github.mangi.eta.ui.components.ScaffoldPage
import io.github.mangi.eta.ui.components.currentGlassOverlayBackdrop
import io.github.mangi.eta.ui.navigation.AppRoute
import io.github.mangi.eta.ui.navigation.NewProviderType
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.glass.GlassDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

@Composable
internal fun ModelProviderListScreen(
    onNavigate: (AppRoute) -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val providers by ProviderRepository.providersFlow().collectAsStateWithLifecycle(initialValue = emptyList())
    val selectedProviderId by RuntimeConfigRepository.selectedProviderIdFlow().collectAsStateWithLifecycle(initialValue = null)
    var searchQuery by remember { mutableStateOf("") }
    var providerToDelete by remember { mutableStateOf<ProviderSetting?>(null) }
    var retainedProviderToDelete by remember { mutableStateOf<ProviderSetting?>(null) }
    if (providerToDelete != null) retainedProviderToDelete = providerToDelete

    LaunchedEffect(Unit) {
        RuntimeConfigRepository.ensureDefaults(EtaApp.serviceInstance)
    }

    val displayNames = providers.associate { provider ->
        val nameRes = BuiltinProviders.defaultNameRes(provider.id, provider.name)
        provider.id to (if (nameRes != null) stringResource(nameRes) else provider.name)
    }
    val filteredProviders = remember(providers, searchQuery, displayNames) {
        val query = searchQuery.trim()
        providers.filter { provider ->
            val localizedName = displayNames[provider.id] ?: provider.name
            query.isBlank() ||
                provider.name.contains(query, ignoreCase = true) ||
                localizedName.contains(query, ignoreCase = true) ||
                provider.baseUrl.contains(query, ignoreCase = true) ||
                provider.typeLabel.contains(query, ignoreCase = true)
        }
    }

    val activeDeleteTarget = providerToDelete ?: retainedProviderToDelete
    val activeDeleteName = activeDeleteTarget?.let { target ->
        displayNames[target.id] ?: target.name
    }.orEmpty()
    val onConfirmDelete: () -> Unit = {
        scope.launch {
            activeDeleteTarget?.let { provider ->
                ProviderRepository.deleteProvider(provider.id)
                RuntimeConfigRepository.syncToRemotePreferences(EtaApp.serviceInstance)
            }
            providerToDelete = null
        }
    }

    ScaffoldPage(title = stringResource(R.string.ui_model_provider_e8c7f5), onBack = onBack) {
        item(key = "search") {
            InputField(
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                onSearch = {},
                expanded = false,
                onExpandedChange = {},
                label = stringResource(R.string.ui_search_provider_74e049),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(top = 8.dp, bottom = 4.dp),
            )
        }

        item(key = "create_section") {
            ProviderSection(title = stringResource(R.string.ui_add_new_provider_74df54)) {
                ArrowPreference(
                    title = stringResource(R.string.catalog_i18n_title),
                    summary = stringResource(R.string.catalog_i18n_entry_summary),
                    startAction = {
                        Box(
                            modifier = Modifier
                                .padding(end = 8.dp)
                                .size(24.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.TravelExplore,
                                contentDescription = null,
                                modifier = Modifier.size(22.dp),
                                tint = MiuixTheme.colorScheme.primary,
                            )
                        }
                    },
                    onClick = { onNavigate(AppRoute.CommunityCatalog) },
                )
                ArrowPreference(
                    title = stringResource(R.string.ui_added_openai_compatible_6bd471),
                    summary = stringResource(R.string.ui_support_chatgpt_deepseek_kimi_glm_qwen_etc_b31d02),
                    startAction = {
                        ProviderBrandIcon(ProviderSourceTypes.OPENAI)
                    },
                    onClick = { onNavigate(AppRoute.ModelProviderNew(NewProviderType.OpenAiCompatible)) },
                )
                ArrowPreference(
                    title = stringResource(R.string.ui_new_anthropic_db6098),
                    summary = stringResource(R.string.ui_support_anthropic_claude_official_or_compatible_api_de3f80),
                    startAction = {
                        ProviderBrandIcon(ProviderSourceTypes.ANTHROPIC)
                    },
                    onClick = { onNavigate(AppRoute.ModelProviderNew(NewProviderType.Anthropic)) },
                )
                ArrowPreference(
                    title = stringResource(R.string.provider_i18n_add_gemini),
                    summary = stringResource(R.string.provider_i18n_add_gemini_summary),
                    startAction = { ProviderBrandIcon(ProviderSourceTypes.GEMINI) },
                    onClick = { onNavigate(AppRoute.ModelProviderNew(NewProviderType.Gemini)) },
                )
            }
        }

        item(key = "list_section") {
            ProviderSection(title = pluralStringResource(R.plurals.provider_configured_count, filteredProviders.size, filteredProviders.size)) {
                if (filteredProviders.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = if (searchQuery.isBlank()) {
                                stringResource(R.string.provider_empty)
                            } else {
                                stringResource(R.string.provider_no_matches)
                            },
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                } else {
                    filteredProviders.forEach { provider ->
                        ProviderListItem(
                            provider = provider,
                            displayName = displayNames[provider.id] ?: provider.name,
                            isSelected = provider.id == selectedProviderId,
                            onOpen = { onNavigate(AppRoute.ModelProviderDetail(provider.id)) },
                            onDelete = if (!provider.isBuiltIn) {
                                { providerToDelete = provider }
                            } else {
                                null
                            },
                            onSelect = {
                                scope.launch {
                                    RuntimeConfigRepository.setSelectedProviderId(provider.id)
                                    RuntimeConfigRepository.syncToRemotePreferences(EtaApp.serviceInstance)
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    GlassDialog(
        show = providerToDelete != null,
        backdrop = currentGlassOverlayBackdrop(),
        title = stringResource(R.string.ui_remove_provider_9f848f),
        summary = stringResource(R.string.provider_delete_summary, activeDeleteName),
        onDismissRequest = { providerToDelete = null },
        onDismissFinished = { if (providerToDelete == null) retainedProviderToDelete = null },
    ) {
        DialogActions(
            confirmText = stringResource(R.string.ui_delete_3755f5),
            destructive = true,
            onCancel = { providerToDelete = null },
            onConfirm = onConfirmDelete,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProviderListItem(
    provider: ProviderSetting,
    displayName: String,
    isSelected: Boolean,
    onOpen: () -> Unit,
    onDelete: (() -> Unit)?,
    onSelect: () -> Unit,
) {
    val opacity = if (provider.isEnabled) 1f else 0.6f
    val subtitle = listOfNotNull(
        provider.typeLabel,
        pluralStringResource(R.plurals.provider_models_count, provider.models.size, provider.models.size),
        stringResource(R.string.ui_built_in_09ceea).takeIf { provider.isBuiltIn },
        stringResource(R.string.ui_disabled_0fe5a9).takeIf { !provider.isEnabled },
    ).joinToString(" · ")

    BasicComponent(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onOpen, onLongClick = onDelete)
            .graphicsLayer { alpha = opacity },
        startAction = { ProviderIcon(provider) },
        endActions = {
            Checkbox(
                state = ToggleableState(isSelected),
                onClick = if (provider.isEnabled) onSelect else null,
                enabled = provider.isEnabled,
            )
        },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = displayName,
                fontSize = MiuixTheme.textStyles.headline1.fontSize,
                fontWeight = FontWeight.Medium,
                color = if (isSelected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (isSelected) {
                TagChip(text = stringResource(R.string.ui_current_25e74d), tone = TagChipTone.Emphasized)
            }
        }
        Text(
            text = provider.baseUrl,
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp),
        )
        Text(
            text = subtitle,
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
