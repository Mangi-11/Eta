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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.itos.coui.layout.CouiPageDefaults
import com.itos.coui.layout.Divider
import com.itos.coui.text.CouiTextKind
import com.itos.coui.text.Text as CouiText
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.data.provider.CommunityCatalog
import io.github.mangi.eta.data.provider.CommunityCatalogProvider
import io.github.mangi.eta.data.provider.CommunityCatalogSource
import io.github.mangi.eta.ui.app.CommunityCatalogStore
import io.github.mangi.eta.ui.app.LocalUiStyle
import io.github.mangi.eta.ui.components.EtaPreferenceGroupItem
import io.github.mangi.eta.ui.components.ListEmptyState
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
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
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
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
                title = "从 models.dev 添加",
                onBack = onBack,
                actions = {
                    CouiTopAppBarIconButton(
                        icon = Icons.Rounded.Refresh,
                        contentDescription = "刷新模型目录",
                        enabled = !store.loading && !store.refreshing,
                        onClick = store::refresh,
                    )
                },
            ) {
                item(key = "search") {
                    CouiSearchBarBox(
                        query = query,
                        onQueryChange = { query = it },
                        placeholder = "搜索提供商",
                    )
                }

                item(key = "catalog_status") {
                    ProviderSection(title = "目录状态") {
                        CouiPreference(
                            title = "models.dev 社区目录",
                            summary = "读取目录不会上传 Eta 的 API Key",
                            showChevron = false,
                        )
                        Divider()
                        CouiPreference(
                            title = "模型是社区候选条目",
                            summary = "能否调用以服务商账号权限和实际接口为准",
                            showChevron = false,
                        )
                        Divider()
                        CouiPreference(
                            title = catalog?.sourceLabel() ?: if (store.loading) "正在读取本地目录" else "目录暂不可用",
                            summary = catalog?.let(::catalogSummary),
                            showChevron = false,
                        )
                        if (store.refreshing) {
                            Divider()
                            CouiPreference(title = "正在更新在线目录", showChevron = false)
                        }
                        store.refreshError?.let { error ->
                            Divider()
                            CouiPreference(
                                title = "在线更新失败",
                                summary = if (catalog != null) "$error；仍可使用当前目录" else error,
                                showChevron = false,
                            )
                        }
                        if (store.loadError && catalog == null) {
                            Divider()
                            CouiPreference(title = "本地目录读取失败", summary = "请重试或检查应用安装包", showChevron = false)
                        }
                    }
                }

                item(key = "results_title") {
                    CouiSectionTitle(text = "可导入提供商（${providers.size}）")
                }
                if (catalog == null && store.loading) {
                    item(key = "loading") { CatalogLoadingState() }
                } else if (providers.isEmpty()) {
                    item(key = "empty") {
                        ListEmptyState(
                            title = if (catalog == null) "没有可用的目录" else "没有找到匹配的提供商",
                            summary = if (catalog == null) "点击右上角刷新后重试" else "试试其他名称或地址",
                            icon = if (catalog == null) Icons.Rounded.CloudOff else Icons.Rounded.SearchOff,
                        )
                    }
                } else {
                    itemsIndexed(providers, key = { _, provider -> "provider:${provider.id}" }, contentType = { _, _ -> "provider" }) { index, provider ->
                        EtaPreferenceGroupItem(
                            isFirst = index == 0,
                            isLast = index == providers.lastIndex,
                        ) {
                            CouiPreference(
                                title = provider.name,
                                summary = "${provider.baseUrl}\n${provider.models.size} 个可导入模型",
                                onClick = { onNavigate(AppRoute.CommunityCatalogProvider(provider.id)) },
                            )
                        }
                    }
                }
            }
        }

        AppearanceUiStyle.MIUIX -> {
            MiuixScaffoldPage(
                title = "从 models.dev 添加",
                onBack = onBack,
                actions = {
                    IconButton(
                        onClick = store::refresh,
                        enabled = !store.loading && !store.refreshing,
                    ) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "刷新模型目录")
                    }
                },
            ) {
                item(key = "search") {
                    InputField(
                        query = query,
                        onQueryChange = { query = it },
                        onSearch = {},
                        expanded = false,
                        onExpandedChange = {},
                        label = "搜索提供商",
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }

                item(key = "catalog_status") {
                    ProviderSection(title = "目录状态") {
                        BasicComponent(
                            title = "models.dev 社区目录",
                            summary = "读取目录不会上传 Eta 的 API Key",
                        )
                        BasicComponent(
                            title = "模型是社区候选条目",
                            summary = "能否调用以服务商账号权限和实际接口为准",
                        )
                        BasicComponent(
                            title = catalog?.sourceLabel() ?: if (store.loading) "正在读取本地目录" else "目录暂不可用",
                            summary = catalog?.let(::catalogSummary),
                        )
                        if (store.refreshing) {
                            BasicComponent(title = "正在更新在线目录")
                        }
                        store.refreshError?.let { error ->
                            BasicComponent(
                                title = "在线更新失败",
                                summary = if (catalog != null) "$error；仍可使用当前目录" else error,
                            )
                        }
                        if (store.loadError && catalog == null) {
                            BasicComponent(title = "本地目录读取失败", summary = "请重试或检查应用安装包")
                        }
                    }
                }

                item(key = "results_title") {
                    SmallTitle("可导入提供商（${providers.size}）")
                }
                if (catalog == null && store.loading) {
                    item(key = "loading") { CatalogLoadingState() }
                } else if (providers.isEmpty()) {
                    item(key = "empty") {
                        ListEmptyState(
                            title = if (catalog == null) "没有可用的目录" else "没有找到匹配的提供商",
                            summary = if (catalog == null) "点击右上角刷新后重试" else "试试其他名称或地址",
                            icon = if (catalog == null) Icons.Rounded.CloudOff else Icons.Rounded.SearchOff,
                        )
                    }
                } else {
                    itemsIndexed(providers, key = { _, provider -> "provider:${provider.id}" }, contentType = { _, _ -> "provider" }) { index, provider ->
                        EtaPreferenceGroupItem(
                            isFirst = index == 0,
                            isLast = index == providers.lastIndex,
                        ) {
                            ArrowPreference(
                                title = provider.name,
                                summary = "${provider.baseUrl}\n${provider.models.size} 个可导入模型",
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
    var importError by remember { mutableStateOf<String?>(null) }
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
            importError = null
            scope.launch {
                try {
                    val addedId = store.importProvider(chosen, selectedIds.toSet())
                    onImported(addedId)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: IllegalArgumentException) {
                    importError = "模型选择已失效，请重新选择"
                } catch (_: Throwable) {
                    importError = "保存提供商失败，请重试"
                } finally {
                    importInFlight = false
                }
            }
        }
    }

    when (LocalUiStyle.current) {
        AppearanceUiStyle.COUI -> {
            CouiLazyScaffoldPage(
                title = provider?.name ?: "选择模型",
                onBack = onBack,
                actions = {
                    CouiTopAppBarTextButton(
                        text = "导入（${selectedIds.size}）",
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
                                title = "目录中没有这个提供商",
                                summary = "目录可能已更新，请返回列表重新选择",
                            )
                        }
                    }
                    return@CouiLazyScaffoldPage
                }

                item(key = "provider") {
                    ProviderSection(title = "提供商") {
                        CouiPreference(title = "名称", summary = provider.name, showChevron = false)
                        Divider()
                        CouiPreference(title = "Base URL", summary = provider.baseUrl, showChevron = false)
                    }
                }
                importError?.let { error ->
                    item(key = "import_error") {
                        Text(
                            text = error,
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
                        placeholder = "搜索模型名称或 ID",
                    )
                }
                item(key = "models_title") {
                    val visibleIds = remember(filteredModels) { filteredModels.mapTo(mutableSetOf()) { it.modelId } }
                    val allVisibleSelected = visibleIds.isNotEmpty() && selectedIds.containsAll(visibleIds)
                    val titleText = if (selectedIds.isEmpty()) {
                        "模型（${filteredModels.size}）"
                    } else {
                        "模型（已选 ${selectedIds.size} / ${provider.models.size}）"
                    }
                    CouiSectionTitle(text = titleText) {
                        if (filteredModels.isNotEmpty()) {
                            Text(
                                text = if (allVisibleSelected) "全不选" else "全选",
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
                        ListEmptyState(title = "没有找到匹配的模型")
                    }
                } else {
                    itemsIndexed(filteredModels, key = { _, model -> "model:${model.modelId}" }, contentType = { _, _ -> "model" }) { index, model ->
                        EtaPreferenceGroupItem(
                            isFirst = index == 0,
                            isLast = index == filteredModels.lastIndex,
                        ) {
                            CouiCheckboxPreference(
                                title = model.displayName,
                                summary = buildString {
                                    append(model.modelId)
                                    append(" · ")
                                    append(formatCompactTokenCount(model.contextWindow))
                                    append(" tokens 上下文")
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
                title = provider?.name ?: "选择模型",
                onBack = onBack,
                actions = {
                    TextButton(
                        text = "导入（${selectedIds.size}）",
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
                                title = "目录中没有这个提供商",
                                summary = "目录可能已更新，请返回列表重新选择",
                            )
                        }
                    }
                    return@MiuixScaffoldPage
                }

                item(key = "provider") {
                    ProviderSection(title = "提供商") {
                        BasicComponent(title = "名称", summary = provider.name)
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
                importError?.let { error ->
                    item(key = "import_error") {
                        Text(
                            text = error,
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
                        label = "搜索模型名称或 ID",
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
                                "模型（${filteredModels.size}）"
                            } else {
                                "模型（已选 ${selectedIds.size} / ${provider.models.size}）"
                            },
                            modifier = Modifier.weight(1f),
                        )
                        if (filteredModels.isNotEmpty()) {
                            Text(
                                text = if (allVisibleSelected) "全不选" else "全选",
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
                        ListEmptyState(title = "没有找到匹配的模型")
                    }
                } else {
                    itemsIndexed(filteredModels, key = { _, model -> "model:${model.modelId}" }, contentType = { _, _ -> "model" }) { index, model ->
                        EtaPreferenceGroupItem(
                            isFirst = index == 0,
                            isLast = index == filteredModels.lastIndex,
                        ) {
                            CheckboxPreference(
                                title = model.displayName,
                                summary = buildString {
                                    append(model.modelId)
                                    append(" · ")
                                    append(formatCompactTokenCount(model.contextWindow))
                                    append(" tokens 上下文")
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

private fun CommunityCatalog.sourceLabel(): String = when (source) {
    CommunityCatalogSource.SNAPSHOT -> "内置目录快照"
    CommunityCatalogSource.CACHE -> "本地缓存目录"
    CommunityCatalogSource.ONLINE -> "在线目录"
}

private fun catalogSummary(catalog: CommunityCatalog): String {
    val updated = catalog.fetchedAt?.let { timestamp ->
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(timestamp))
    }
    return listOfNotNull("${catalog.providers.size} 家可导入提供商", updated?.let { "更新于 $it" })
        .joinToString(" · ")
}
