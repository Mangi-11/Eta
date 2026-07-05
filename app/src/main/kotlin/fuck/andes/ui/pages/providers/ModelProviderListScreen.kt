package fuck.andes.ui.pages.providers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.R as LucideR
import fuck.andes.FuckAndesApp
import fuck.andes.data.model.ProviderSetting
import fuck.andes.data.model.typeLabel
import fuck.andes.data.repository.ProviderRepository
import fuck.andes.data.repository.RuntimeConfigRepository
import fuck.andes.ui.navigation.AppRoute
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
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

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
    ) {
        item(key = "search") {
            TextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(top = 12.dp, bottom = 8.dp),
                label = "搜索提供商",
                leadingIcon = {
                    Icon(
                        painter = painterResource(LucideR.drawable.lucide_ic_search),
                        contentDescription = null,
                    )
                },
                singleLine = true,
            )
        }

        item(key = "create") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(
                    text = "新增 OpenAI-compatible",
                    modifier = Modifier.weight(1f),
                    onClick = {
                        scope.launch {
                            val provider = ProviderRepository.addCustomOpenAiProvider()
                            onNavigate(AppRoute.ModelProviderDetail(provider.id))
                        }
                    },
                )
                TextButton(
                    text = "新增 Anthropic",
                    modifier = Modifier.weight(1f),
                    onClick = {
                        scope.launch {
                            val provider = ProviderRepository.addAnthropicProvider()
                            onNavigate(AppRoute.ModelProviderDetail(provider.id))
                        }
                    },
                )
            }
        }

        item(key = "count") {
            SmallTitle(
                text = "共 ${filteredProviders.size} 个提供商",
                modifier = Modifier.padding(horizontal = 26.dp, vertical = 8.dp),
            )
        }

        items(
            items = filteredProviders,
            key = { it.id },
        ) { provider ->
            ProviderListItem(
                provider = provider,
                isSelected = provider.id == selectedProviderId,
                onOpen = { onNavigate(AppRoute.ModelProviderDetail(provider.id)) },
                onCopy = {
                    scope.launch {
                        ProviderRepository.copyProvider(provider.id)?.let { copy ->
                            onNavigate(AppRoute.ModelProviderDetail(copy.id))
                        }
                    }
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

@Composable
private fun ProviderListItem(
    provider: ProviderSetting,
    isSelected: Boolean,
    onOpen: () -> Unit,
    onCopy: () -> Unit,
    onSelect: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        onClick = onOpen,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = provider.name,
                    style = MiuixTheme.textStyles.headline1,
                    color = MiuixTheme.colorScheme.onSurface,
                )
                Text(
                    text = provider.baseUrl,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 2.dp),
                )
                Text(
                    text = buildString {
                        append(provider.typeLabel)
                        append(" · ${provider.models.size} 个模型")
                        if (provider.isBuiltIn) append(" · 内置")
                        if (isSelected) append(" · 当前")
                    },
                    style = MiuixTheme.textStyles.footnote2,
                    color = if (isSelected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantActions,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                IconButton(onClick = onCopy) {
                    Icon(
                        painter = painterResource(LucideR.drawable.lucide_ic_copy),
                        contentDescription = "复制",
                    )
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
    }
}
