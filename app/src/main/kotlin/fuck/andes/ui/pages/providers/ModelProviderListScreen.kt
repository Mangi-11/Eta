package fuck.andes.ui.pages.providers

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.R as LucideR
import fuck.andes.FuckAndesApp
import fuck.andes.data.model.ProviderSetting
import fuck.andes.data.model.ProviderSourceTypes
import fuck.andes.data.model.typeLabel
import fuck.andes.data.repository.ProviderRepository
import fuck.andes.data.repository.RuntimeConfigRepository
import fuck.andes.ui.components.MiuixScaffoldPage
import fuck.andes.ui.navigation.AppRoute
import fuck.andes.ui.navigation.NewProviderType
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun ModelProviderListScreen(
    onNavigate: (AppRoute) -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val providers by ProviderRepository.providersFlow().collectAsState(initial = emptyList())
    val selectedProviderId by RuntimeConfigRepository.selectedProviderIdFlow().collectAsState(initial = null)
    var searchQuery by remember { mutableStateOf("") }
    var providerToDelete by remember { mutableStateOf<ProviderSetting?>(null) }

    LaunchedEffect(Unit) {
        RuntimeConfigRepository.ensureDefaults(FuckAndesApp.serviceInstance)
    }

    val filteredProviders = remember(providers, searchQuery) {
        val query = searchQuery.trim()
        providers.filter { provider ->
            query.isBlank() ||
                provider.name.contains(query, ignoreCase = true) ||
                provider.baseUrl.contains(query, ignoreCase = true) ||
                provider.typeLabel.contains(query, ignoreCase = true)
        }
    }

    MiuixScaffoldPage(title = "模型提供商", onBack = onBack) {
        item(key = "search") {
            InputField(
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                onSearch = {},
                expanded = false,
                onExpandedChange = {},
                label = "搜索提供商",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(top = 12.dp, bottom = 8.dp),
            )
        }

        item(key = "create_section") {
            ProviderSection(title = "新增提供商") {
                ArrowPreference(
                    title = "新增 OpenAI-compatible",
                    summary = "支持 ChatGPT, DeepSeek, Kimi, GLM, Qwen等",
                    startAction = {
                        ProviderBrandIcon(ProviderSourceTypes.OPENAI)
                    },
                    onClick = { onNavigate(AppRoute.ModelProviderNew(NewProviderType.OpenAiCompatible)) },
                )
                ProviderDivider()
                ArrowPreference(
                    title = "新增 Anthropic",
                    summary = "支持 Anthropic Claude 官方或兼容 API",
                    startAction = {
                        ProviderBrandIcon(ProviderSourceTypes.ANTHROPIC)
                    },
                    onClick = { onNavigate(AppRoute.ModelProviderNew(NewProviderType.Anthropic)) },
                )
            }
        }

        item(key = "list_section") {
            ProviderSection(title = "已配置提供商 (共 ${filteredProviders.size} 个)") {
                if (filteredProviders.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = if (searchQuery.isBlank()) "暂无模型提供商，请新增" else "未找到匹配的提供商",
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                } else {
                    filteredProviders.forEachIndexed { index, provider ->
                        if (index > 0) {
                            ProviderDivider()
                        }
                        ProviderListItem(
                            provider = provider,
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
                                    RuntimeConfigRepository.syncToRemotePreferences(FuckAndesApp.serviceInstance)
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    if (providerToDelete != null) {
        OverlayDialog(
            show = true,
            title = "删除提供商",
            onDismissRequest = { providerToDelete = null }
        ) {
            Text("确定删除「${providerToDelete?.name}」吗？此操作不可恢复。")
            Row(modifier = Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.End) {
                TextButton(text = "取消", onClick = { providerToDelete = null })
                Spacer(modifier = Modifier.width(8.dp))
                TextButton(
                    text = "删除",
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    onClick = {
                        scope.launch {
                            providerToDelete?.let { p ->
                                ProviderRepository.deleteProvider(p.id)
                                RuntimeConfigRepository.syncToRemotePreferences(FuckAndesApp.serviceInstance)
                            }
                            providerToDelete = null
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
    isSelected: Boolean,
    onOpen: () -> Unit,
    onDelete: (() -> Unit)?,
    onSelect: () -> Unit,
) {
    val opacity = if (provider.isEnabled) 1f else 0.6f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onOpen,
                onLongClick = onDelete
            )
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .graphicsLayer { alpha = opacity },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProviderIcon(provider)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = provider.name,
                style = MiuixTheme.textStyles.headline1,
                color = MiuixTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = provider.baseUrl,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(top = 6.dp),
            ) {
                TagChip(text = provider.typeLabel)
                TagChip(text = "${provider.models.size} 个模型")
                if (provider.isBuiltIn) {
                    TagChip(text = "内置")
                }
                if (!provider.isEnabled) {
                    TagChip(text = "已禁用", tone = TagChipTone.Warning)
                }
                if (isSelected) {
                    TagChip(text = "当前", tone = TagChipTone.Emphasized)
                }
            }
        }
        IconButton(onClick = onSelect) {
            Icon(
                painter = painterResource(
                    if (isSelected) LucideR.drawable.lucide_ic_check else LucideR.drawable.lucide_ic_circle,
                ),
                contentDescription = if (isSelected) "已选中" else "设为当前",
                tint = if (isSelected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantActions,
            )
        }
    }
}
