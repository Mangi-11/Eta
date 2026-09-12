package io.github.mangi.eta.ui.screens.characters

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.ui.app.CharacterLibraryStore
import io.github.mangi.eta.ui.components.CharacterAvatar
import io.github.mangi.eta.ui.components.ListEmptyState
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.navigation.AppRoute
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.SearchBar
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

internal val CharacterCardPadding = 12.dp

@Composable
internal fun CharacterLibraryScreen(
    store: CharacterLibraryStore,
    onNavigate: (AppRoute) -> Unit,
    onBack: () -> Unit,
) {
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) store.importCard(uri) { onNavigate(AppRoute.CharacterDetail(it)) }
    }
    val importCard = {
        importer.launch(arrayOf("image/png", "application/json", "text/plain", "application/octet-stream"))
    }
    val createCharacter = {
        store.discardEditor()
        onNavigate(AppRoute.CharacterEditor())
    }
    MiuixScaffoldPage(
        title = "角色",
        onBack = onBack,
        actions = {
            IconButton(onClick = importCard, enabled = !store.busy) {
                Icon(Icons.Rounded.FileUpload, contentDescription = "导入角色卡")
            }
            IconButton(onClick = createCharacter, enabled = !store.busy) {
                Icon(Icons.Rounded.Add, contentDescription = "创建角色")
            }
        },
    ) {
        item(key = "search") {
            SearchBar(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = CharacterCardPadding, vertical = 4.dp),
                expanded = false,
                onExpandedChange = {},
                inputField = {
                    InputField(
                        query = store.query,
                        onQueryChange = { store.query = it },
                        onSearch = { store.query = it },
                        expanded = false,
                        onExpandedChange = {},
                        label = "搜索名称或标签",
                    )
                },
                content = {},
            )
        }
        if (store.characters.isNotEmpty()) {
            item(key = "list-header") {
                val hasArchived = store.characters.any { it.archived }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 8.dp, top = 10.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (store.showArchived && hasArchived) "全部 ${store.filteredCharacters.size} 个" else "${store.filteredCharacters.size} 个角色",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.weight(1f),
                    )
                    // 没有已归档角色时不引入"归档"概念；归档过角色后开关才出现。
                    if (hasArchived) {
                        TextButton(
                            text = if (store.showArchived) "隐藏已归档" else "显示已归档",
                            onClick = store::toggleArchived,
                            minHeight = 32.dp,
                        )
                    }
                }
            }
        }
        when {
            store.busy && store.characters.isEmpty() -> {
                item(key = "loading") {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        InfiniteProgressIndicator(size = 28.dp)
                    }
                }
            }
            store.characters.isEmpty() -> {
                item(key = "empty-library") {
                    ListEmptyState(
                        title = "还没有角色",
                        summary = "创建一个角色，或导入 PNG、JSON 角色卡开始对话",
                        action = {
                            TextButton(
                                text = "创建角色",
                                onClick = createCharacter,
                                colors = ButtonDefaults.textButtonColorsPrimary(),
                            )
                        },
                    )
                }
            }
            store.filteredCharacters.isEmpty() && store.query.isNotBlank() -> {
                item(key = "empty-search") {
                    ListEmptyState(
                        title = "没有找到匹配的角色",
                        summary = "换个关键词试试，搜索会匹配名称与标签",
                    )
                }
            }
            store.filteredCharacters.isEmpty() -> {
                item(key = "empty-archived") {
                    ListEmptyState(
                        title = "所有角色都已归档",
                        action = {
                            TextButton(
                                text = "显示已归档",
                                onClick = store::toggleArchived,
                            )
                        },
                    )
                }
            }
        }
        items(store.filteredCharacters, key = { it.id }) { profile ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = CharacterCardPadding, vertical = 6.dp),
                insideMargin = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                pressFeedbackType = PressFeedbackType.Sink,
                onClick = { if (!store.busy) onNavigate(AppRoute.CharacterDetail(profile.id)) },
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CharacterAvatar(profile.card.name, profile.avatarPath, size = 48.dp, revision = profile.updatedAt)
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Text(
                            text = profile.card.name,
                            style = MiuixTheme.textStyles.body1,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = when {
                                profile.archived -> "已归档"
                                profile.card.description.isNotBlank() -> profile.card.description
                                else -> profile.card.tags.joinToString(" · ")
                            },
                            style = MiuixTheme.textStyles.body2,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            }
        }
        item(key = "persona-title") { SmallTitle("我的") }
        item(key = "persona") {
            Card(
                modifier = Modifier
                    .padding(horizontal = CharacterCardPadding)
                    .padding(bottom = 12.dp),
            ) {
                ArrowPreference(
                    title = "我的人设",
                    summary = "设置角色如何称呼你，以及你在故事中的身份",
                    onClick = { onNavigate(AppRoute.CharacterPersona) },
                )
            }
        }
    }
}

@Composable
internal fun CharacterPageMessage(text: String) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.body2,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
    )
}
