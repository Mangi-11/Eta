package io.github.mangi.eta.ui.screens.characters
import io.github.mangi.eta.R
import androidx.compose.ui.res.stringResource

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.ui.app.CharacterLibraryStore
import io.github.mangi.eta.ui.components.EtaArrowPreference
import io.github.mangi.eta.ui.components.EtaCard
import io.github.mangi.eta.ui.components.EtaPreferenceGroup
import io.github.mangi.eta.ui.components.EtaPreferenceGroupTitle
import io.github.mangi.eta.ui.components.EtaTextButton
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun CharacterEditorScreen(
    id: String?,
    store: CharacterLibraryStore,
    onBack: () -> Unit,
    onSaved: (String) -> Unit,
) {
    var advanced by rememberSaveable { mutableStateOf(false) }
    var worldbookExpanded by rememberSaveable { mutableStateOf(false) }
    var worldbookEntry by rememberSaveable { mutableStateOf<Int?>(null) }
    val card = store.draft
    MiuixScaffoldPage(
        title = if (id == null) stringResource(R.string.character_create) else stringResource(R.string.character_edit),
        onBack = onBack,
        modifier = Modifier.imePadding(),
        actions = {
            IconButton(
                onClick = { store.saveEditor(onSaved) },
                enabled = !store.busy && store.draftName.isNotBlank(),
            ) {
                Icon(Icons.Rounded.Check, contentDescription = stringResource(R.string.character_save_character))
            }
        },
    ) {
        if (card == null) {
            item { CharacterPageMessage(if (store.busy) stringResource(R.string.catalog_reading) else stringResource(R.string.character_load_failed_retry)) }
            return@MiuixScaffoldPage
        }
        item(key = "name") { CharacterTextField(stringResource(R.string.character_name), store.draftName, store::updateName, !store.busy, singleLine = true) }
        item(key = "description-title") { EtaPreferenceGroupTitle(stringResource(R.string.character_definition)) }
        item(key = "description") { CharacterTextField(stringResource(R.string.character_appearance_personality_background), card.description, { value -> store.updateDraft { it.withEdits(description = value) } }, !store.busy, minLines = 5) }
        item(key = "greeting-title") { EtaPreferenceGroupTitle(stringResource(R.string.character_greeting)) }
        item(key = "greeting") { CharacterTextField(stringResource(R.string.character_default_greeting), card.firstMessage, { value -> store.updateDraft { it.withEdits(firstMessage = value) } }, !store.busy) }
        itemsIndexed(card.alternateGreetings, key = { index, _ -> "alternate-$index" }) { index, value ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextField(
                    value = value,
                    onValueChange = { updated ->
                        store.updateDraft { it.withEdits(alternateGreetings = it.alternateGreetings.toMutableList().apply { this[index] = updated }) }
                    },
                    label = stringResource(R.string.character_indexed_greeting, index + 2),
                    enabled = !store.busy,
                    minLines = 2,
                    maxLines = 14,
                    modifier = Modifier.weight(1f).padding(vertical = 4.dp),
                )
                IconButton(
                    onClick = {
                        store.updateDraft { it.withEdits(alternateGreetings = it.alternateGreetings.filterIndexed { i, _ -> i != index }) }
                    },
                    enabled = !store.busy,
                    minWidth = 36.dp,
                    minHeight = 36.dp,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.character_remove_greeting),
                        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        }
        item(key = "add-greeting") {
            EtaTextButton(
                stringResource(R.string.character_add_alternate_greeting),
                onClick = { store.updateDraft { it.withEdits(alternateGreetings = it.alternateGreetings + "") } },
                enabled = !store.busy,
                modifier = Modifier.padding(start = 16.dp, top = 2.dp),
            )
        }
        item(key = "advanced") {
            EtaPreferenceGroup(modifier = Modifier.padding(horizontal = CharacterCardPadding, vertical = 8.dp)) {
                EtaArrowPreference(
                    title = stringResource(R.string.character_advanced_settings),
                    summary = stringResource(R.string.character_attributes_description),
                    onClick = { advanced = !advanced },
                )
            }
        }
        if (advanced) {
            item(key = "personality") { CharacterTextField(stringResource(R.string.character_personality_and_speaking_style), card.personality, { value -> store.updateDraft { it.withEdits(personality = value) } }, !store.busy) }
            item(key = "scenario") { CharacterTextField(stringResource(R.string.character_story_background), card.scenario, { value -> store.updateDraft { it.withEdits(scenario = value) } }, !store.busy) }
            item(key = "examples") { CharacterTextField(stringResource(R.string.character_example_dialogue), card.exampleMessages, { value -> store.updateDraft { it.withEdits(exampleMessages = value) } }, !store.busy, minLines = 4) }
            item(key = "prompts-title") { EtaPreferenceGroupTitle(stringResource(R.string.character_prompt)) }
            item(key = "system") { CharacterTextField(stringResource(R.string.character_system_prompt), card.systemPrompt, { value -> store.updateDraft { it.withEdits(systemPrompt = value) } }, !store.busy) }
            item(key = "post-history") { CharacterTextField(stringResource(R.string.character_post_history_instructions), card.postHistoryInstructions, { value -> store.updateDraft { it.withEdits(postHistoryInstructions = value) } }, !store.busy) }
            item(key = "credits-title") { EtaPreferenceGroupTitle(stringResource(R.string.character_author_info)) }
            item(key = "creator-notes") { CharacterTextField(stringResource(R.string.character_author_notes), card.creatorNotes, { value -> store.updateDraft { it.withEdits(creatorNotes = value) } }, !store.busy) }
            item(key = "tags") { CharacterTextField(stringResource(R.string.worldbook_tags_per_line), card.tags.joinToString("\n"), { value -> store.updateDraft { it.withEdits(tags = value.lines()) } }, !store.busy) }
            item(key = "creator") { CharacterTextField(stringResource(R.string.character_author), card.creator, { value -> store.updateDraft { it.withEdits(creator = value) } }, !store.busy, singleLine = true) }
            item(key = "version") { CharacterTextField(stringResource(R.string.character_version), card.version, { value -> store.updateDraft { it.withEdits(version = value) } }, !store.busy, singleLine = true) }
            characterWorldbookEditor(
                store = store, expanded = worldbookExpanded, expandedEntry = worldbookEntry,
                onToggleExpanded = { worldbookExpanded = !worldbookExpanded },
                onExpandEntry = { worldbookEntry = it },
            )
            item(key = "compatibility") { CharacterPageMessage(stringResource(R.string.character_card_script_extensions_notice)) }
        }
        item(key = "save") {
            EtaTextButton(
                text = if (store.busy) stringResource(R.string.character_processing) else stringResource(R.string.character_save_character),
                onClick = { store.saveEditor(onSaved) },
                enabled = !store.busy && store.draftName.isNotBlank(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = CharacterCardPadding, vertical = 12.dp),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}

@Composable
internal fun CharacterTextField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    enabled: Boolean,
    singleLine: Boolean = false,
    minLines: Int = 2,
) {
    TextField(
        value = value, onValueChange = onChange, label = label, enabled = enabled,
        singleLine = singleLine, minLines = if (singleLine) 1 else minLines,
        maxLines = if (singleLine) 1 else 14,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

@Composable
internal fun CharacterFieldGroupLabel(text: String) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.footnote1,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
    )
}
