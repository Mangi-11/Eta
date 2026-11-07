package io.github.mangi.eta.ui.screens.characters

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.SportsBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.itos.coui.icons.CouiIcons
import com.itos.coui.layout.Footer
import com.itos.coui.layout.Group
import com.itos.coui.text.CouiTextKind
import com.itos.coui.text.Text as CouiText
import io.github.mangi.eta.agent.roleplay.CharacterProfile
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.ui.app.CharacterLibraryStore
import io.github.mangi.eta.ui.app.LocalUiStyle
import io.github.mangi.eta.ui.components.ListEmptyState
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.components.coui.CouiButton
import io.github.mangi.eta.ui.components.coui.CouiButtonRole
import io.github.mangi.eta.ui.components.coui.CouiLazyScaffoldPage
import io.github.mangi.eta.ui.components.coui.CouiPreference
import io.github.mangi.eta.ui.components.coui.CouiSearchBarBox
import io.github.mangi.eta.ui.components.coui.CouiTopAppBarIconButton
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

internal val CharacterCardPadding = 12.dp
private val CharacterGridSpacing = 12.dp

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
    val showAddTile = store.query.isBlank() && store.characters.isNotEmpty()
    val tiles: List<CharacterProfile?> = store.filteredCharacters + if (showAddTile) listOf(null) else emptyList()
    val rows = tiles.chunked(2)

    when (LocalUiStyle.current) {
        AppearanceUiStyle.COUI -> {
            CouiLazyScaffoldPage(
                title = "角色",
                onBack = onBack,
                actions = {
                    CouiTopAppBarIconButton(
                        icon = Icons.Rounded.FileUpload,
                        contentDescription = "导入角色卡",
                        enabled = !store.busy,
                        onClick = importCard,
                    )
                    CouiTopAppBarIconButton(
                        icon = CouiIcons.Add,
                        contentDescription = "创建角色",
                        enabled = !store.busy,
                        onClick = createCharacter,
                    )
                },
            ) {
                item(key = "search") {
                    CouiSearchBarBox(
                        query = store.query,
                        onQueryChange = { store.query = it },
                        placeholder = "搜索名称或标签",
                    )
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
                                icon = Icons.Rounded.SportsBar,
                                action = {
                                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        CouiButton(
                                            text = "恢复默认角色",
                                            onClick = { store.restoreDefaultCharacter() },
                                            enabled = !store.busy,
                                        )
                                        CouiButton(
                                            text = "创建角色",
                                            onClick = createCharacter,
                                            role = CouiButtonRole.Primary,
                                        )
                                    }
                                },
                            )
                        }
                    }
                    store.filteredCharacters.isEmpty() -> {
                        item(key = "empty-search") {
                            ListEmptyState(
                                title = "没有找到匹配的角色",
                                summary = "换个关键词试试，搜索会匹配名称与标签",
                                icon = Icons.Rounded.SearchOff,
                            )
                        }
                    }
                }
                items(rows, key = { row -> row.joinToString("|") { it?.id ?: "add" } }) { row ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .padding(bottom = CharacterGridSpacing),
                        horizontalArrangement = Arrangement.spacedBy(CharacterGridSpacing),
                    ) {
                        row.forEach { profile ->
                            if (profile == null) {
                                CharacterAddTile(
                                    enabled = !store.busy,
                                    onCreate = createCharacter,
                                    onImport = importCard,
                                    modifier = Modifier.weight(1f),
                                )
                            } else {
                                CharacterPortraitCard(
                                    profile = profile,
                                    onClick = { if (!store.busy) onNavigate(AppRoute.CharacterDetail(profile.id)) },
                                    onToggleFavorite = { store.toggleFavorite(profile.id) },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
                item(key = "persona") {
                    Group(title = "我的") {
                        CouiPreference(
                            title = "我的人设",
                            summary = "角色对你的称呼与你在故事中的身份",
                            onClick = { onNavigate(AppRoute.CharacterPersona) },
                        )
                    }
                }
            }
        }

        AppearanceUiStyle.MIUIX -> {
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
                            .padding(horizontal = CharacterCardPadding)
                            .padding(top = 4.dp, bottom = 12.dp),
                        insideMargin = DpSize(0.dp, 0.dp),
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
                                icon = Icons.Rounded.SportsBar,
                                action = {
                                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        TextButton(
                                            text = "恢复默认角色",
                                            onClick = { store.restoreDefaultCharacter() },
                                            enabled = !store.busy,
                                        )
                                        TextButton(
                                            text = "创建角色",
                                            onClick = createCharacter,
                                            colors = ButtonDefaults.textButtonColorsPrimary(),
                                        )
                                    }
                                },
                            )
                        }
                    }
                    store.filteredCharacters.isEmpty() -> {
                        item(key = "empty-search") {
                            ListEmptyState(
                                title = "没有找到匹配的角色",
                                summary = "换个关键词试试，搜索会匹配名称与标签",
                                icon = Icons.Rounded.SearchOff,
                            )
                        }
                    }
                }
                items(rows, key = { row -> row.joinToString("|") { it?.id ?: "add" } }) { row ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = CharacterCardPadding)
                            .padding(bottom = CharacterGridSpacing),
                        horizontalArrangement = Arrangement.spacedBy(CharacterGridSpacing),
                    ) {
                        row.forEach { profile ->
                            if (profile == null) {
                                CharacterAddTile(
                                    enabled = !store.busy,
                                    onCreate = createCharacter,
                                    onImport = importCard,
                                    modifier = Modifier.weight(1f),
                                )
                            } else {
                                CharacterPortraitCard(
                                    profile = profile,
                                    onClick = { if (!store.busy) onNavigate(AppRoute.CharacterDetail(profile.id)) },
                                    onToggleFavorite = { store.toggleFavorite(profile.id) },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
                item(key = "persona") {
                    SmallTitle("我的")
                    Card(
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 12.dp),
                    ) {
                        ArrowPreference(
                            title = "我的人设",
                            summary = "角色对你的称呼与你在故事中的身份",
                            onClick = { onNavigate(AppRoute.CharacterPersona) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun CharacterPageMessage(text: String) {
    when (LocalUiStyle.current) {
        AppearanceUiStyle.COUI -> {
            Footer(text = text)
        }
        AppearanceUiStyle.MIUIX -> {
            Text(
                text = text,
                style = MiuixTheme.textStyles.body2,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 8.dp),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}
