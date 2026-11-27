package io.github.mangi.eta.ui.screens.providers

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
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
import io.github.mangi.eta.data.model.ProviderAuthModes
import io.github.mangi.eta.data.model.typeLabel
import io.github.mangi.eta.data.provider.BuiltinProviders
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import io.github.mangi.eta.ui.components.DialogActions
import io.github.mangi.eta.ui.app.LocalGlassPageBackdrop
import io.github.mangi.eta.ui.components.ScaffoldPage
import io.github.mangi.eta.ui.components.currentGlassOverlayBackdrop
import io.github.mangi.eta.ui.layout.WidePageContent
import io.github.mangi.eta.ui.layout.horizontalCutoutPadding
import io.github.mangi.eta.ui.layout.rememberFloatingBottomBarInsets
import io.github.mangi.eta.ui.navigation.AppRoute
import io.github.mangi.eta.ui.navigation.NewProviderType
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.glass.GlassDialog
import top.yukonga.miuix.kmp.glass.GlassFloatingActionButton
import top.yukonga.miuix.kmp.glass.GlassFloatingActionButtonDefaults
import top.yukonga.miuix.kmp.glass.GlassPopupItem
import top.yukonga.miuix.kmp.glass.GlassTransformPopup
import top.yukonga.miuix.kmp.glass.glassPopupAnchor
import top.yukonga.miuix.kmp.glass.glassPopupAnchorContent
import top.yukonga.miuix.kmp.glass.rememberGlassPopupAnchor
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.Add
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

@Composable
internal fun ModelProviderListScreen(
    onNavigate: (AppRoute) -> Unit,
    onBack: () -> Unit,
    builtInOnly: Boolean = false,
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

    val visibleProviders = remember(providers, builtInOnly) {
        if (builtInOnly) {
            BuiltinProviders.PROVIDERS
        } else {
            providers.filterNot(BuiltinProviders::isUnconfiguredPreset)
        }
    }
    val displayNames = visibleProviders.associate { provider ->
        val nameRes = BuiltinProviders.defaultNameRes(provider.id, provider.name)
        provider.id to (if (nameRes != null) stringResource(nameRes) else provider.name)
    }
    val filteredProviders = remember(visibleProviders, searchQuery, displayNames) {
        val query = searchQuery.trim()
        visibleProviders.filter { provider ->
            val localizedName = displayNames[provider.id] ?: provider.name
            query.isBlank() ||
                provider.name.contains(query, ignoreCase = true) ||
                localizedName.contains(query, ignoreCase = true) ||
                (!ProviderAuthModes.isChatGpt(provider.authMode) && provider.baseUrl.contains(query, ignoreCase = true)) ||
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

    ScaffoldPage(
        title = stringResource(
            if (builtInOnly) R.string.provider_more_title else R.string.ui_model_provider_e8c7f5,
        ),
        onBack = onBack,
        overlay = { if (!builtInOnly) AddProviderMenu(onNavigate) },
    ) {
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

        item(key = "list_section") {
            val title = if (builtInOnly) {
                stringResource(R.string.provider_presets_title)
            } else {
                pluralStringResource(R.plurals.provider_configured_count, filteredProviders.size, filteredProviders.size)
            }
            ProviderSection(title = title) {
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
                        val displayName = displayNames[provider.id] ?: provider.name
                        if (builtInOnly) {
                            ArrowPreference(
                                title = displayName,
                                summary = provider.baseUrl,
                                startAction = { ProviderIcon(provider) },
                                onClick = { onNavigate(AppRoute.ModelProviderNewFromPreset(provider.id)) },
                            )
                            return@forEach
                        }
                        ProviderListItem(
                            provider = provider,
                            displayName = displayName,
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
        if (builtInOnly) {
            item(key = "community_catalog") {
                ProviderSection(title = null) {
                    ArrowPreference(
                        title = stringResource(R.string.catalog_i18n_title),
                        summary = stringResource(R.string.catalog_i18n_entry_summary),
                        startAction = {
                            Icon(
                                imageVector = Icons.Rounded.TravelExplore,
                                contentDescription = null,
                                modifier = Modifier.padding(end = 8.dp).size(24.dp),
                                tint = MiuixTheme.colorScheme.primary,
                            )
                        },
                        onClick = { onNavigate(AppRoute.CommunityCatalog) },
                    )
                }
            }
        } else {
            item(key = "add_menu_clearance") {
                Spacer(Modifier.height(GlassFloatingActionButtonDefaults.Size))
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

@Composable
private fun AddProviderMenu(onNavigate: (AppRoute) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val anchor = rememberGlassPopupAnchor()
    val backdrop = LocalGlassPageBackdrop.current
    val label = stringResource(R.string.ui_add_new_provider_74df54)
    val actions = listOf(
        R.string.provider_add_chatgpt to AppRoute.ModelProviderNew(NewProviderType.ChatGpt),
        R.string.provider_add_openai_compatible to AppRoute.ModelProviderNew(NewProviderType.OpenAiCompatible),
        R.string.provider_add_anthropic to AppRoute.ModelProviderNew(NewProviderType.Anthropic),
        R.string.provider_add_gemini to AppRoute.ModelProviderNew(NewProviderType.Gemini),
        R.string.provider_more_title to AppRoute.MoreModelProviders,
    )

    Box(Modifier.fillMaxSize()) {
        WidePageContent { sidePadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .horizontalCutoutPadding()
                    .imePadding()
                    .windowInsetsPadding(rememberFloatingBottomBarInsets(fixedBottomPadding = 16.dp))
                    .padding(end = sidePadding + 16.dp, bottom = 16.dp),
                contentAlignment = Alignment.BottomEnd,
            ) {
                GlassFloatingActionButton(
                    onClick = { expanded = true },
                    backdrop = backdrop,
                    modifier = Modifier.glassPopupAnchor(
                        anchor = anchor,
                        cornerRadius = GlassFloatingActionButtonDefaults.Size / 2,
                        floating = true,
                    ),
                ) {
                    Icon(
                        imageVector = MiuixIcons.Glass.Add,
                        contentDescription = label,
                        tint = MiuixTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(28.dp).glassPopupAnchorContent(anchor),
                    )
                }
            }
        }

        // Keep the popup in the outgoing page's navigation layer.
        GlassTransformPopup(
            show = expanded,
            onDismissRequest = { expanded = false },
            anchor = anchor,
            backdrop = backdrop,
            horizontalAlignment = Alignment.End,
            anchorContent = {
                Icon(
                    imageVector = MiuixIcons.Glass.Add,
                    contentDescription = null,
                    tint = MiuixTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(28.dp),
                )
            },
        ) {
            actions.forEach { (labelRes, route) ->
                GlassPopupItem(
                    text = stringResource(labelRes),
                    onClick = {
                        if (expanded) {
                            expanded = false
                            onNavigate(route)
                        }
                    },
                )
            }
        }
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
        if (!ProviderAuthModes.isChatGpt(provider.authMode)) {
            Text(
                text = provider.baseUrl,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Text(
            text = subtitle,
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
