package io.github.mangi.eta.ui.screens.characters
import io.github.mangi.eta.R
import androidx.compose.ui.res.stringResource

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
import io.github.mangi.eta.ui.components.EtaArrowPreference
import io.github.mangi.eta.ui.components.EtaCard
import io.github.mangi.eta.ui.components.EtaPreferenceGroup
import io.github.mangi.eta.ui.components.EtaPreferenceGroupTitle
import io.github.mangi.eta.ui.components.EtaTextButton
import io.github.mangi.eta.ui.components.ListEmptyState
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.navigation.AppRoute
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.SearchBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

internal val CharacterCardPadding = 16.dp

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
        title = stringResource(R.string.character_label),
        onBack = onBack,
        actions = {
            IconButton(onClick = importCard, enabled = !store.busy) {
                Icon(Icons.Rounded.FileUpload, contentDescription = stringResource(R.string.character_import_card))
            }
            IconButton(onClick = createCharacter, enabled = !store.busy) {
                Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.character_create))
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
                        label = stringResource(R.string.catalog_search_name_or_tag),
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
                        title = stringResource(R.string.character_list_empty),
                        summary = stringResource(R.string.character_empty_state_desc),
                        action = {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                EtaTextButton(
                                    text = stringResource(R.string.character_restore_default),
                                    onClick = { store.restoreDefaultCharacter() },
                                    enabled = !store.busy,
                                )
                                EtaTextButton(
                                    text = stringResource(R.string.character_create),
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
                        title = stringResource(R.string.character_search_no_matches_found),
                        summary = stringResource(R.string.catalog_search_empty_hint),
                    )
                }
            }
        }
        items(store.filteredCharacters, key = { it.id }) { profile ->
            EtaCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = CharacterCardPadding, vertical = 8.dp),
                insideMargin = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                pressFeedbackType = PressFeedbackType.Sink,
                onClick = { if (!store.busy) onNavigate(AppRoute.CharacterDetail(profile.id)) },
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        text = profile.card.name,
                        style = MiuixTheme.textStyles.body1,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = profile.card.description.ifBlank {
                            profile.card.tags.joinToString(" · ")
                        },
                        style = MiuixTheme.textStyles.body2,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        }
        item(key = "persona-title") { EtaPreferenceGroupTitle(stringResource(R.string.character_tab_mine)) }
        item(key = "persona") {
            EtaPreferenceGroup(
                modifier = Modifier
                    .padding(horizontal = CharacterCardPadding)
                    .padding(bottom = 16.dp),
            ) {
                EtaArrowPreference(
                    title = stringResource(R.string.character_user_persona),
                    summary = stringResource(R.string.character_user_identity_desc),
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
