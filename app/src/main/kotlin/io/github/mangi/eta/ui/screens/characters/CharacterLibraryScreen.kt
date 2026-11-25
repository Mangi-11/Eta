package io.github.mangi.eta.ui.screens.characters

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.SportsBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.roleplay.CharacterProfile
import io.github.mangi.eta.ui.state.CharacterLibraryStore
import io.github.mangi.eta.ui.components.ListEmptyState
import io.github.mangi.eta.ui.components.ScaffoldPage
import io.github.mangi.eta.ui.components.TopBarActionButton
import io.github.mangi.eta.ui.navigation.AppRoute
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.SearchBar
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.Add
import top.yukonga.miuix.kmp.icon.glass.Import
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
        store.prepareEditor(null)
        onNavigate(AppRoute.CharacterEditor())
    }
    val rows = remember(store.characters, store.query) {
        val showAddTile = store.query.isBlank() && store.characters.isNotEmpty()
        val tiles: List<CharacterProfile?> = store.filteredCharacters + if (showAddTile) listOf(null) else emptyList()
        tiles.chunked(2)
    }

    val libraryTitle = stringResource(R.string.character_library_title)
    val importCardDesc = stringResource(R.string.character_action_import_card)
    val createCharacterText = stringResource(R.string.character_action_create)
    val searchHint = stringResource(R.string.character_library_search_hint)
    val emptyTitle = stringResource(R.string.character_library_empty_title)
    val emptySummary = stringResource(R.string.character_library_empty_summary)
    val restoreDefaultText = stringResource(R.string.character_action_restore_default)
    val noResultsTitle = stringResource(R.string.character_library_no_results_title)
    val noResultsSummary = stringResource(R.string.character_library_no_results_summary)
    val mineSectionTitle = stringResource(R.string.character_library_section_mine)
    val personaTitle = stringResource(R.string.character_persona_title)
    val personaSummary = stringResource(R.string.character_persona_entry_summary)

    ScaffoldPage(
        title = libraryTitle,
        onBack = onBack,
        actions = {
            TopBarActionButton(
                icon = MiuixIcons.Glass.Import,
                contentDescription = importCardDesc,
                enabled = !store.busy,
                onClick = importCard,
            )
            Spacer(Modifier.width(8.dp))
            TopBarActionButton(
                icon = MiuixIcons.Glass.Add,
                contentDescription = createCharacterText,
                enabled = !store.busy,
                onClick = createCharacter,
            )
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
                        label = searchHint,
                    )
                },
                content = {},
            )
        }
        when {
            (store.isLoadingLibrary || store.busy) && store.characters.isEmpty() -> {
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
                        title = emptyTitle,
                        summary = emptySummary,
                        icon = Icons.Rounded.SportsBar,
                        action = {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                TextButton(
                                    text = restoreDefaultText,
                                    onClick = { store.restoreDefaultCharacter() },
                                    enabled = !store.busy,
                                )
                                TextButton(
                                    text = createCharacterText,
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
                        title = noResultsTitle,
                        summary = noResultsSummary,
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
            SmallTitle(mineSectionTitle)
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                ArrowPreference(
                    title = personaTitle,
                    summary = personaSummary,
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
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 12.dp),
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
    )
}
