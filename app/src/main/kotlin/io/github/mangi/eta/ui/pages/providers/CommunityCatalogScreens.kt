package io.github.mangi.eta.ui.pages.providers

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.itos.coui.layout.CouiPageDefaults
import com.itos.coui.layout.Divider
import com.itos.coui.text.CouiTextKind
import com.itos.coui.text.Text as CouiText
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.data.provider.CommunityCatalog
import io.github.mangi.eta.data.provider.CommunityCatalogProvider
import io.github.mangi.eta.data.provider.CommunityCatalogSource
import io.github.mangi.eta.ui.app.CommunityCatalogStore
import io.github.mangi.eta.ui.app.LocalUiStyle
import io.github.mangi.eta.ui.components.EtaPreferenceGroupItem
import io.github.mangi.eta.ui.components.ListEmptyState
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.components.MiuixTopBarActionButton
import io.github.mangi.eta.ui.components.coui.CouiCheckboxPreference
import io.github.mangi.eta.ui.components.coui.CouiLazyScaffoldPage
import io.github.mangi.eta.ui.components.coui.CouiPreference
import io.github.mangi.eta.ui.components.coui.CouiSearchBarBox
import io.github.mangi.eta.ui.components.coui.CouiSectionTitle
import io.github.mangi.eta.ui.components.coui.CouiTopAppBarIconButton
import io.github.mangi.eta.ui.components.coui.CouiTopAppBarTextButton
import io.github.mangi.eta.ui.model.formatCompactTokenCount
import io.github.mangi.eta.ui.navigation.AppRoute
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.Refresh
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.CheckboxLocation
import top.yukonga.miuix.kmp.preference.CheckboxPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun CommunityCatalogScreen(
    store: CommunityCatalogStore,
    onNavigate: (AppRoute) -> Unit,
    onBack: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val catalog = store.catalog
    val providers = remember(catalog, query) {
        val term = query.trim()
        catalog?.providers.orEmpty().filter { provider ->
            term.isEmpty() ||
                provider.name.contains(term, ignoreCase = true) ||
                provider.id.contains(term, ignoreCase = true) ||
                provider.baseUrl.contains(term, ignoreCase = true)
        }
    }

    LaunchedEffect(Unit) { store.openCatalog() }

    when (LocalUiStyle.current) {
        AppearanceUiStyle.COUI -> {
            val colors = CouiPageDefaults.themeColors()
            CouiLazyScaffoldPage(
                title = stringResource(R.string.catalog_i18n_title),
                onBack = onBack,
                actions = {
                    CouiTopAppBarIconButton(
                        icon = Icons.Rounded.Refresh,
                        contentDescription = stringResource(R.string.catalog_i18n_refresh),
                        enabled = !store.loading && !store.refreshing,
                        onClick = store::refresh,
                    )
                },
            ) {
                item(key = "search") {
                    CouiSearchBarBox(
                        query = query,
                        onQueryChange = { query = it },
                        placeholder = stringResource(R.string.catalog_i18n_search_providers),
                    )
                }

                item(key = "catalog_status") {
                    ProviderSection(title = stringResource(R.string.catalog_i18n_status_section)) {
                        CouiPreference(
                            title = stringResource(R.string.catalog_i18n_community_title),
                            summary = stringResource(R.string.catalog_i18n_community_privacy),
                            showChevron = false,
                        )
                        Divider()
                        CouiPreference(
                            title = stringResource(R.string.catalog_i18n_candidate_title),
                            summary = stringResource(R.string.catalog_i18n_candidate_summary),
                            showChevron = false,
                        )
                        Divider()
                        CouiPreference(
                            title = catalog?.sourceLabel() ?: stringResource(
                                if (store.loading) R.string.catalog_i18n_loading_local else R.string.catalog_i18n_unavailable,
                            ),
                            summary = catalog?.let { catalogSummary(it) },
                            showChevron = false,
                        )
                        if (store.refreshing) {
                            Divider()
                            CouiPreference(title = stringResource(R.string.catalog_i18n_updating_online), showChevron = false)
                        }
                        store.refreshError?.let { error ->
                            Divider()
                            CouiPreference(
                                title = stringResource(R.string.catalog_i18n_update_failed),
                                summary = if (catalog != null) {
                                    stringResource(R.string.catalog_i18n_update_failed_fallback, error)
                                } else {
                                    error
                                },
                                showChevron = false,
                            )
                        }
                        if (store.loadError && catalog == null) {
                            Divider()
                            CouiPreference(
                                title = stringResource(R.string.catalog_i18n_local_load_failed),
                                summary = stringResource(R.string.catalog_i18n_local_load_failed_summary),
                                showChevron = false,
                            )
                        }
                    }
                }

                item(key = "results_title") {
                    CouiSectionTitle(text = stringResource(R.string.catalog_i18n_importable_providers, providers.size))
                }
                if (catalog == null && store.loading) {
                    item(key = "loading") { CatalogLoadingState() }
                } else if (providers.isEmpty()) {
                    item(key = "empty") {
                        ListEmptyState(
                            title = stringResource(
                                if (catalog == null) R.string.catalog_i18n_empty_no_catalog else R.string.catalog_i18n_empty_no_match,
                            ),
                            summary = stringResource(
                                if (catalog == null) R.string.catalog_i18n_empty_no_catalog_summary else R.string.catalog_i18n_empty_no_match_summary,
                            ),
                            icon = if (catalog == null) Icons.Rounded.CloudOff else Icons.Rounded.SearchOff,
                        )
                    }
                } else {
                    itemsIndexed(providers, key = { _, provider -> "provider:${provider.id}" }, contentType = { _, _ -> "provider" }) { index, provider ->
                        EtaPreferenceGroupItem(
                            isFirst = index == 0,
                            isLast = index == providers.lastIndex,
                        ) {
                            val modelsCountText = pluralStringResource(
                                R.plurals.catalog_i18n_importable_models_count,
                                provider.models.size,
                                provider.models.size,
                            )
                            CouiPreference(
                                title = provider.name,
                                summary = "${provider.baseUrl}\n$modelsCountText",
                                onClick = { onNavigate(AppRoute.CommunityCatalogProvider(provider.id)) },
                            )
                        }
                    }
                }
            }
        }

        AppearanceUiStyle.MIUIX -> {
            MiuixScaffoldPage(
                title = stringResource(R.string.catalog_i18n_title),
                onBack = onBack,
                actions = {
                    MiuixTopBarActionButton(
                        icon = MiuixIcons.Glass.Refresh,
                        contentDescription = stringResource(R.string.catalog_i18n_refresh),
                        enabled = !store.loading && !store.refreshing,
                        onClick = store::refresh,
                    )
                },
            ) {
                item(key = "search") {
                    InputField(
                        query = query,
                        onQueryChange = { query = it },
                        onSearch = {},
                        expanded = false,
                        onExpandedChange = {},
                        label = stringResource(R.string.catalog_i18n_search_providers),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }

                item(key = "catalog_status") {
                    ProviderSection(title = stringResource(R.string.catalog_i18n_status_section)) {
                        BasicComponent(
                            title = stringResource(R.string.catalog_i18n_community_title),
                            summary = stringResource(R.string.catalog_i18n_community_privacy),
                        )
                        BasicComponent(
                            title = stringResource(R.string.catalog_i18n_candidate_title),
                            summary = stringResource(R.string.catalog_i18n_candidate_summary),
                        )
                        BasicComponent(
                            title = catalog?.sourceLabel() ?: stringResource(
                                if (store.loading) R.string.catalog_i18n_loading_local else R.string.catalog_i18n_unavailable,
                            ),
                            summary = catalog?.let { catalogSummary(it) },
                        )
                        if (store.refreshing) {
                            BasicComponent(title = stringResource(R.string.catalog_i18n_updating_online))
                        }
                        store.refreshError?.let { error ->
                            BasicComponent(
                                title = stringResource(R.string.catalog_i18n_update_failed),
                                summary = if (catalog != null) {
                                    stringResource(R.string.catalog_i18n_update_failed_fallback, error)
                                } else {
                                    error
                                },
                            )
                        }
                        if (store.loadError && catalog == null) {
                            BasicComponent(
                                title = stringResource(R.string.catalog_i18n_local_load_failed),
                                summary = stringResource(R.string.catalog_i18n_local_load_failed_summary),
                            )
                        }
                    }
                }

                item(key = "results_title") {
                    SmallTitle(stringResource(R.string.catalog_i18n_importable_providers, providers.size))
                }
                if (catalog == null && store.loading) {
                    item(key = "loading") { CatalogLoadingState() }
                } else if (providers.isEmpty()) {
                    item(key = "empty") {
                        ListEmptyState(
                            title = stringResource(
                                if (catalog == null) R.string.catalog_i18n_empty_no_catalog else R.string.catalog_i18n_empty_no_match,
                            ),
                            summary = stringResource(
                                if (catalog == null) R.string.catalog_i18n_empty_no_catalog_summary else R.string.catalog_i18n_empty_no_match_summary,
                            ),
                            icon = if (catalog == null) Icons.Rounded.CloudOff else Icons.Rounded.SearchOff,
                        )
                    }
                } else {
                    itemsIndexed(providers, key = { _, provider -> "provider:${provider.id}" }, contentType = { _, _ -> "provider" }) { index, provider ->
                        EtaPreferenceGroupItem(
                            isFirst = index == 0,
                            isLast = index == providers.lastIndex,
                        ) {
                            val modelsCountText = pluralStringResource(
                                R.plurals.catalog_i18n_importable_models_count,
                                provider.models.size,
                                provider.models.size,
                            )
                            ArrowPreference(
                                title = provider.name,
                                summary = "${provider.baseUrl}\n$modelsCountText",
                                onClick = { onNavigate(AppRoute.CommunityCatalogProvider(provider.id)) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun CommunityCatalogProviderScreen(
    catalogId: String,
    store: CommunityCatalogStore,
    onImported: (String) -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var query by rememberSaveable(catalogId) { mutableStateOf("") }
    var selectedIds by rememberSaveable(catalogId) { mutableStateOf(arrayListOf<String>()) }
    var importErrorRes by remember { mutableStateOf<Int?>(null) }
    var importInFlight by remember { mutableStateOf(false) }
    var selection by remember(catalogId) { mutableStateOf<CommunityCatalogProvider?>(null) }
    val provider = selection ?: store.catalog?.providers?.firstOrNull { it.id == catalogId }
    val filteredModels = remember(provider, query) {
        val term = query.trim()
        provider?.models.orEmpty().filter { model ->
            term.isEmpty() ||
                model.displayName.contains(term, ignoreCase = true) ||
                model.modelId.contains(term, ignoreCase = true)
        }
    }

    LaunchedEffect(catalogId) { store.ensureLoaded() }
    LaunchedEffect(provider) {
        if (selection == null && provider != null) selection = provider
    }
    val canImport = provider != null && selectedIds.isNotEmpty() && !importInFlight && !store.importing
    val onImport: () -> Unit = {
        val chosen = provider
        if (chosen != null && !importInFlight) {
            importInFlight = true
            importErrorRes = null
            scope.launch {
                try {
                    val addedId = store.importProvider(chosen, selectedIds.toSet())
                    onImported(addedId)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: IllegalArgumentException) {
                    importErrorRes = R.string.catalog_i18n_selection_expired
                } catch (_: Throwable) {
                    importErrorRes = R.string.catalog_i18n_save_provider_failed
                } finally {
                    importInFlight = false
                }
            }
        }
    }

    when (LocalUiStyle.current) {
        AppearanceUiStyle.COUI -> {
            CouiLazyScaffoldPage(
                title = provider?.name ?: stringResource(R.string.catalog_i18n_select_models),
                onBack = onBack,
                actions = {
                    CouiTopAppBarTextButton(
                        text = stringResource(R.string.catalog_i18n_import_count, selectedIds.size),
                        enabled = canImport,
                        onClick = onImport,
                    )
                },
            ) {
                if (provider == null) {
                    item(key = "missing") {
                        if (store.loading) {
                            CatalogLoadingState()
                        } else {
                            ListEmptyState(
                                title = stringResource(R.string.catalog_i18n_provider_not_found),
                                summary = stringResource(R.string.catalog_i18n_provider_not_found_summary),
                            )
                        }
                    }
                    return@CouiLazyScaffoldPage
                }

                item(key = "provider") {
                    ProviderSection(title = stringResource(R.string.catalog_i18n_provider_section)) {
                        CouiPreference(title = stringResource(R.string.catalog_i18n_field_name), summary = provider.name, showChevron = false)
                        Divider()
                        CouiPreference(title = "Base URL", summary = provider.baseUrl, showChevron = false)
                    }
                }
                importErrorRes?.let { errorRes ->
                    item(key = "import_error") {
                        Text(
                            text = stringResource(errorRes),
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 28.dp, vertical = 4.dp),
                        )
                    }
                }
                item(key = "model_search") {
                    CouiSearchBarBox(
                        query = query,
                        onQueryChange = { query = it },
                        placeholder = stringResource(R.string.catalog_i18n_search_models),
                    )
                }
                item(key = "models_title") {
                    val visibleIds = remember(filteredModels) { filteredModels.mapTo(mutableSetOf()) { it.modelId } }
                    val allVisibleSelected = visibleIds.isNotEmpty() && selectedIds.containsAll(visibleIds)
                    val titleText = if (selectedIds.isEmpty()) {
                        stringResource(R.string.catalog_i18n_models_count, filteredModels.size)
                    } else {
                        stringResource(R.string.catalog_i18n_models_selected, selectedIds.size, provider.models.size)
                    }
                    CouiSectionTitle(text = titleText) {
                        if (filteredModels.isNotEmpty()) {
                            Text(
                                text = stringResource(
                                    if (allVisibleSelected) R.string.page_select_none_ba20eb else R.string.page_select_all_3e44b2,
                                ),
                                style = MiuixTheme.textStyles.subtitle,
                                color = if (!store.importing) {
                                    MiuixTheme.colorScheme.primary
                                } else {
                                    MiuixTheme.colorScheme.disabledOnSecondaryVariant
                                },
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable(enabled = !store.importing) {
                                        selectedIds = if (allVisibleSelected) {
                                            ArrayList(selectedIds.filterNot { it in visibleIds })
                                        } else {
                                            ArrayList((selectedIds + visibleIds).distinct())
                                        }
                                    }
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                            )
                        }
                    }
                }
                if (filteredModels.isEmpty()) {
                    item(key = "empty_models") {
                        ListEmptyState(title = stringResource(R.string.catalog_i18n_no_matching_models))
                    }
                } else {
                    itemsIndexed(filteredModels, key = { _, model -> "model:${model.modelId}" }, contentType = { _, _ -> "model" }) { index, model ->
                        EtaPreferenceGroupItem(
                            isFirst = index == 0,
                            isLast = index == filteredModels.lastIndex,
                        ) {
                            val contextLabel = stringResource(
                                R.string.catalog_i18n_context_tokens,
                                formatCompactTokenCount(model.contextWindow),
                            )
                            CouiCheckboxPreference(
                                title = model.displayName,
                                summary = buildString {
                                    append(model.modelId)
                                    append(" · ")
                                    append(contextLabel)
                                    model.releaseDate?.let { append(" · "); append(it) }
                                },
                                checked = model.modelId in selectedIds,
                                enabled = !store.importing,
                                onCheckedChange = { checked ->
                                    selectedIds = if (checked) {
                                        ArrayList(selectedIds + model.modelId)
                                    } else {
                                        ArrayList(selectedIds.filterNot { it == model.modelId })
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }

        AppearanceUiStyle.MIUIX -> {
            MiuixScaffoldPage(
                title = provider?.name ?: stringResource(R.string.catalog_i18n_select_models),
                onBack = onBack,
                actions = {
                    TextButton(
                        text = stringResource(R.string.catalog_i18n_import_count, selectedIds.size),
                        enabled = canImport,
                        onClick = onImport,
                        minWidth = 0.dp,
                        minHeight = 32.dp,
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                        insideMargin = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    )
                },
            ) {
                if (provider == null) {
                    item(key = "missing") {
                        if (store.loading) {
                            CatalogLoadingState()
                        } else {
                            ListEmptyState(
                                title = stringResource(R.string.catalog_i18n_provider_not_found),
                                summary = stringResource(R.string.catalog_i18n_provider_not_found_summary),
                            )
                        }
                    }
                    return@MiuixScaffoldPage
                }

                item(key = "provider") {
                    ProviderSection(title = stringResource(R.string.catalog_i18n_provider_section)) {
                        BasicComponent(title = stringResource(R.string.catalog_i18n_field_name), summary = provider.name)
                        BasicComponent {
                            Text(
                                text = "Base URL",
                                fontSize = MiuixTheme.textStyles.headline1.fontSize,
                                fontWeight = FontWeight.Medium,
                            )
                            SelectionContainer {
                                Text(
                                    text = provider.baseUrl,
                                    style = MiuixTheme.textStyles.body2,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    softWrap = true,
                                )
                            }
                        }
                    }
                }
                importErrorRes?.let { errorRes ->
                    item(key = "import_error") {
                        Text(
                            text = stringResource(errorRes),
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 28.dp, vertical = 4.dp),
                        )
                    }
                }
                item(key = "model_search") {
                    InputField(
                        query = query,
                        onQueryChange = { query = it },
                        onSearch = {},
                        expanded = false,
                        onExpandedChange = {},
                        label = stringResource(R.string.catalog_i18n_search_models),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp)
                            .padding(top = 4.dp, bottom = 8.dp),
                    )
                }
                item(key = "models_title") {
                    val visibleIds = remember(filteredModels) { filteredModels.mapTo(mutableSetOf()) { it.modelId } }
                    val allVisibleSelected = visibleIds.isNotEmpty() && selectedIds.containsAll(visibleIds)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(end = 20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        SmallTitle(
                            text = if (selectedIds.isEmpty()) {
                                stringResource(R.string.catalog_i18n_models_count, filteredModels.size)
                            } else {
                                stringResource(R.string.catalog_i18n_models_selected, selectedIds.size, provider.models.size)
                            },
                            modifier = Modifier.weight(1f),
                        )
                        if (filteredModels.isNotEmpty()) {
                            Text(
                                text = stringResource(
                                    if (allVisibleSelected) R.string.page_select_none_ba20eb else R.string.page_select_all_3e44b2,
                                ),
                                style = MiuixTheme.textStyles.subtitle,
                                color = if (!store.importing) {
                                    MiuixTheme.colorScheme.primary
                                } else {
                                    MiuixTheme.colorScheme.disabledOnSecondaryVariant
                                },
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable(enabled = !store.importing) {
                                        selectedIds = if (allVisibleSelected) {
                                            ArrayList(selectedIds.filterNot { it in visibleIds })
                                        } else {
                                            ArrayList((selectedIds + visibleIds).distinct())
                                        }
                                    }
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                            )
                        }
                    }
                }
                if (filteredModels.isEmpty()) {
                    item(key = "empty_models") {
                        ListEmptyState(title = stringResource(R.string.catalog_i18n_no_matching_models))
                    }
                } else {
                    itemsIndexed(filteredModels, key = { _, model -> "model:${model.modelId}" }, contentType = { _, _ -> "model" }) { index, model ->
                        EtaPreferenceGroupItem(
                            isFirst = index == 0,
                            isLast = index == filteredModels.lastIndex,
                        ) {
                            val contextLabel = stringResource(
                                R.string.catalog_i18n_context_tokens,
                                formatCompactTokenCount(model.contextWindow),
                            )
                            CheckboxPreference(
                                title = model.displayName,
                                summary = buildString {
                                    append(model.modelId)
                                    append(" · ")
                                    append(contextLabel)
                                    model.releaseDate?.let { append(" · "); append(it) }
                                },
                                checked = model.modelId in selectedIds,
                                enabled = !store.importing,
                                checkboxLocation = CheckboxLocation.End,
                                onCheckedChange = { checked ->
                                    selectedIds = if (checked) {
                                        ArrayList(selectedIds + model.modelId)
                                    } else {
                                        ArrayList(selectedIds.filterNot { it == model.modelId })
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CatalogLoadingState() {
    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
        contentAlignment = Alignment.Center,
    ) {
        InfiniteProgressIndicator(size = 28.dp)
    }
}

@Composable
private fun CommunityCatalog.sourceLabel(): String = stringResource(
    when (source) {
        CommunityCatalogSource.SNAPSHOT -> R.string.catalog_i18n_source_snapshot
        CommunityCatalogSource.CACHE -> R.string.catalog_i18n_source_cache
        CommunityCatalogSource.ONLINE -> R.string.catalog_i18n_source_online
    },
)

@Composable
private fun catalogSummary(catalog: CommunityCatalog): String {
    val updated = catalog.fetchedAt?.let { timestamp ->
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(timestamp))
    }
    val providersCount = pluralStringResource(
        R.plurals.catalog_i18n_providers_count,
        catalog.providers.size,
        catalog.providers.size,
    )
    val updatedLabel = updated?.let { stringResource(R.string.catalog_i18n_updated_at, it) }
    return listOfNotNull(providersCount, updatedLabel).joinToString(" · ")
}
