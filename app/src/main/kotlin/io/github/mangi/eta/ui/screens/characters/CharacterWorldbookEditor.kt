package io.github.mangi.eta.ui.screens.characters

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.roleplay.CharacterBookEntryDraft
import io.github.mangi.eta.agent.roleplay.CharacterWorldbook
import io.github.mangi.eta.ui.state.CharacterLibraryStore
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
private fun CharacterWorldbookEntryEditor(
    entry: CharacterBookEntryDraft,
    enabled: Boolean,
    unsupported: List<String>,
    onChange: (CharacterBookEntryDraft) -> Unit,
    onDelete: () -> Unit,
) {
    if (unsupported.isNotEmpty()) {
        val semicolonSep = stringResource(R.string.character_list_separator_semicolon)
        Text(
            stringResource(R.string.character_worldbook_entry_unsupported_notice, unsupported.joinToString(semicolonSep)),
            style = MiuixTheme.textStyles.body2,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
    val contentLabel = stringResource(R.string.character_worldbook_field_content)
    CharacterFieldGroupLabel(contentLabel)
    CharacterTextField(stringResource(R.string.character_worldbook_entry_title_label), entry.name, { onChange(entry.copy(name = it)) }, enabled, singleLine = true)
    CharacterTextField(contentLabel, entry.content, { onChange(entry.copy(content = it)) }, enabled, minLines = 4)
    CharacterFieldGroupLabel(stringResource(R.string.character_worldbook_group_trigger))
    CharacterTextField(stringResource(R.string.character_worldbook_primary_keys_label), entry.keys.joinToString("\n"), { onChange(entry.copy(keys = it.lines())) }, enabled)
    CharacterTextField(stringResource(R.string.character_worldbook_secondary_keys_label), entry.secondaryKeys.joinToString("\n"), { onChange(entry.copy(secondaryKeys = it.lines())) }, enabled)
    SwitchPreference(
        title = stringResource(R.string.character_worldbook_selective_title),
        checked = entry.selective,
        enabled = enabled,
        onCheckedChange = { onChange(entry.copy(selective = it)) },
    )
    CharacterFieldGroupLabel(stringResource(R.string.character_worldbook_group_insertion))
    SwitchPreference(
        title = stringResource(R.string.character_worldbook_constant_title),
        checked = entry.constant,
        enabled = enabled,
        onCheckedChange = { onChange(entry.copy(constant = it)) },
    )
    SwitchPreference(
        title = stringResource(R.string.character_worldbook_before_char_title),
        summary = stringResource(R.string.character_worldbook_before_char_summary),
        checked = entry.position == "before_char",
        enabled = enabled,
        onCheckedChange = { onChange(entry.copy(position = if (it) "before_char" else "after_char")) },
    )
    CharacterTextField(stringResource(R.string.character_worldbook_insertion_order_label), entry.insertionOrder.toString(), { value ->
        value.toIntOrNull()?.takeIf { it >= 0 }?.let { onChange(entry.copy(insertionOrder = it)) }
    }, enabled, singleLine = true)
    TextButton(
        text = stringResource(R.string.character_worldbook_remove_entry),
        enabled = enabled,
        onClick = onDelete,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
    )
}

private fun optionalNonNegativeInt(value: String, onValue: (Int?) -> Unit) {
    if (value.isBlank()) onValue(null)
    else value.toIntOrNull()?.takeIf { it >= 0 }?.let(onValue)
}

@Composable
internal fun CharacterWorldbookSection(
    store: CharacterLibraryStore,
    expanded: Boolean,
    expandedEntry: Int?,
    onToggleExpanded: () -> Unit,
    onExpandEntry: (Int?) -> Unit,
) {
    val card = store.draft ?: return
    val book = remember(card) { card.worldbookDraft() }
    val enabled = !store.busy
    CharacterSectionHeader(stringResource(R.string.character_worldbook_section))
    val countText = pluralStringResource(R.plurals.character_worldbook_entry_count, book.entries.size, book.entries.size)
    val toggleText = stringResource(if (expanded) R.string.character_worldbook_collapse else R.string.character_worldbook_expand_edit)
    ExpandableSectionCard(
        title = stringResource(R.string.character_worldbook_embedded),
        summary = stringResource(R.string.character_worldbook_summary_format, countText, toggleText),
        expanded = expanded,
        onToggle = onToggleExpanded,
    )
    AnimatedVisibility(
        visible = expanded,
        enter = expandVertically(tween(300)) + fadeIn(tween(300)),
        exit = shrinkVertically(tween(300)) + fadeOut(tween(300)),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            CharacterTextField(stringResource(R.string.character_worldbook_name_label), book.name, { value -> store.updateWorldbook { it.copy(name = value) } }, enabled, singleLine = true)
            CharacterTextField(stringResource(R.string.character_worldbook_scan_depth_label), book.scanDepth?.toString().orEmpty(), { value ->
                optionalNonNegativeInt(value) { number -> store.updateWorldbook { it.copy(scanDepth = number) } }
            }, enabled, singleLine = true)
            CharacterTextField(stringResource(R.string.character_worldbook_token_budget_label), book.tokenBudget?.toString().orEmpty(), { value ->
                optionalNonNegativeInt(value) { number -> store.updateWorldbook { it.copy(tokenBudget = number) } }
            }, enabled, singleLine = true)
            Card(
                modifier = Modifier
                    .padding(horizontal = CharacterCardPadding)
                    .padding(bottom = 12.dp),
            ) {
                SwitchPreference(
                    title = stringResource(R.string.character_worldbook_recursive_title),
                    summary = stringResource(R.string.character_worldbook_recursive_summary),
                    checked = book.recursiveScanning == true,
                    enabled = enabled,
                    onCheckedChange = { value -> store.updateWorldbook { it.copy(recursiveScanning = value) } },
                )
            }
            CharacterSectionHeader(stringResource(R.string.character_worldbook_entries_section))
            val unsupported = remember(card) {
                CharacterWorldbook.unsupportedEntries(card).associate { it.index to it.reasons }
            }
            book.entries.forEachIndexed { index, entry ->
                key(index) {
                val semicolonSep = stringResource(R.string.character_list_separator_semicolon)
                val commaSep = stringResource(R.string.character_list_separator_comma)
                val entryTitle = entry.name.take(120).ifBlank { stringResource(R.string.character_worldbook_entry_default_title, index + 1) }
                val entrySummary = when {
                    !entry.enabled -> stringResource(R.string.character_worldbook_entry_disabled)
                    !unsupported[index].isNullOrEmpty() -> stringResource(
                        R.string.character_worldbook_entry_skipped,
                        unsupported[index].orEmpty().joinToString(semicolonSep),
                    )
                    entry.constant -> stringResource(R.string.character_worldbook_entry_always_in_context)
                    else -> entry.keys.take(4).joinToString(commaSep).take(160)
                        .ifBlank { stringResource(R.string.character_worldbook_entry_no_keys) }
                }
                val onToggleEntry: (Boolean) -> Unit = { value ->
                    store.updateWorldbook { current ->
                        current.copy(entries = current.entries.toMutableList().apply { this[index] = entry.copy(enabled = value) })
                    }
                }
                Card(
                    modifier = Modifier
                        .padding(horizontal = CharacterCardPadding)
                        .padding(bottom = 12.dp),
                ) {
                    ArrowPreference(
                        title = entryTitle,
                        summary = entrySummary,
                        onClick = { onExpandEntry(if (expandedEntry == index) null else index) },
                        endActions = {
                            Switch(
                                checked = entry.enabled,
                                enabled = enabled,
                                onCheckedChange = onToggleEntry,
                            )
                        },
                    )
                    AnimatedVisibility(
                        visible = expandedEntry == index,
                        enter = expandVertically(tween(300)) + fadeIn(tween(300)),
                        exit = shrinkVertically(tween(300)) + fadeOut(tween(300)),
                    ) {
                        CharacterWorldbookEntryEditor(
                            entry = entry,
                            enabled = enabled,
                            unsupported = unsupported[index].orEmpty(),
                            onChange = { updated ->
                                store.updateWorldbook { current ->
                                    current.copy(entries = current.entries.toMutableList().apply { this[index] = updated })
                                }
                            },
                            onDelete = {
                                onExpandEntry(null)
                                store.updateWorldbook { current ->
                                    current.copy(entries = current.entries.filterIndexed { i, _ -> i != index })
                                }
                            },
                        )
                    }
                }
                }
            }
            TextButton(
                text = stringResource(R.string.character_worldbook_add_entry),
                onClick = {
                    val newIndex = book.entries.size
                    store.updateWorldbook { it.copy(entries = it.entries + CharacterBookEntryDraft()) }
                    onExpandEntry(newIndex)
                },
                enabled = enabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = CharacterCardPadding)
                    .padding(bottom = 12.dp),
            )
        }
    }
}
