package io.github.mangi.eta.ui.screens.characters

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.roleplay.CharacterBookEntryDraft
import io.github.mangi.eta.agent.roleplay.CharacterWorldbook
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.ui.app.CharacterLibraryStore
import io.github.mangi.eta.ui.app.LocalUiStyle
import io.github.mangi.eta.ui.components.coui.CouiButton
import io.github.mangi.eta.ui.components.coui.CouiButtonRole
import io.github.mangi.eta.ui.components.coui.CouiPageDefaults
import io.github.mangi.eta.ui.components.coui.CouiPreference
import io.github.mangi.eta.ui.components.coui.CouiPreferenceGroup
import io.github.mangi.eta.ui.components.coui.CouiSwitchPreference
import io.github.mangi.eta.ui.components.coui.CouiCardDivider as CouiDivider
import io.github.suqi8.coui.kmp.basic.Switch as CouiSwitch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal fun LazyListScope.characterWorldbookEditor(
    store: CharacterLibraryStore,
    expanded: Boolean,
    expandedEntry: Int?,
    onToggleExpanded: () -> Unit,
    onExpandEntry: (Int?) -> Unit,
) {
    val card = store.draft ?: return
    val book = card.worldbookDraft()
    val enabled = !store.busy
    item(key = "worldbook-title") { CharacterSectionHeader(stringResource(R.string.character_worldbook_section)) }
    item(key = "worldbook-header") {
        val countText = pluralStringResource(R.plurals.character_worldbook_entry_count, book.entries.size, book.entries.size)
        val toggleText = stringResource(if (expanded) R.string.character_worldbook_collapse else R.string.character_worldbook_expand_edit)
        val summary = stringResource(R.string.character_worldbook_summary_format, countText, toggleText)
        val embeddedTitle = stringResource(R.string.character_worldbook_embedded)
        if (LocalUiStyle.current == AppearanceUiStyle.COUI) {
            CouiPreferenceGroup {
                item {
                    CouiPreference(
                        title = embeddedTitle,
                        summary = summary,
                        onClick = onToggleExpanded,
                    )
                }
            }
        } else {
            Card(
                modifier = Modifier
                    .padding(horizontal = CharacterCardPadding)
                    .padding(bottom = 12.dp),
            ) {
                ArrowPreference(
                    title = embeddedTitle,
                    summary = summary,
                    onClick = onToggleExpanded,
                )
            }
        }
    }
    if (!expanded) return
    item(key = "worldbook-name") {
        CharacterTextField(stringResource(R.string.character_worldbook_name_label), book.name, { value -> store.updateWorldbook { it.copy(name = value) } }, enabled, singleLine = true)
    }
    item(key = "worldbook-depth") {
        CharacterTextField(stringResource(R.string.character_worldbook_scan_depth_label), book.scanDepth?.toString().orEmpty(), { value ->
            optionalNonNegativeInt(value) { number -> store.updateWorldbook { it.copy(scanDepth = number) } }
        }, enabled, singleLine = true)
    }
    item(key = "worldbook-budget") {
        CharacterTextField(stringResource(R.string.character_worldbook_token_budget_label), book.tokenBudget?.toString().orEmpty(), { value ->
            optionalNonNegativeInt(value) { number -> store.updateWorldbook { it.copy(tokenBudget = number) } }
        }, enabled, singleLine = true)
    }
    item(key = "worldbook-recursive") {
        val recursiveTitle = stringResource(R.string.character_worldbook_recursive_title)
        val recursiveSummary = stringResource(R.string.character_worldbook_recursive_summary)
        if (LocalUiStyle.current == AppearanceUiStyle.COUI) {
            CouiPreferenceGroup {
                item {
                    CouiSwitchPreference(
                        title = recursiveTitle,
                        summary = recursiveSummary,
                        checked = book.recursiveScanning == true,
                        enabled = enabled,
                        onCheckedChange = { value -> store.updateWorldbook { it.copy(recursiveScanning = value) } },
                    )
                }
            }
        } else {
            Card(
                modifier = Modifier
                    .padding(horizontal = CharacterCardPadding, vertical = 6.dp),
            ) {
                SwitchPreference(
                    title = recursiveTitle,
                    summary = recursiveSummary,
                    checked = book.recursiveScanning == true,
                    enabled = enabled,
                    onCheckedChange = { value -> store.updateWorldbook { it.copy(recursiveScanning = value) } },
                )
            }
        }
    }
    item(key = "worldbook-entries-title") { CharacterSectionHeader(stringResource(R.string.character_worldbook_entries_section)) }
    val unsupported = CharacterWorldbook.unsupportedEntries(card).associate { it.index to it.reasons }
    itemsIndexed(book.entries, key = { index, _ -> "worldbook-entry-$index" }) { index, entry ->
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
        if (LocalUiStyle.current == AppearanceUiStyle.COUI) {
            CouiPreferenceGroup {
                item {
                    CouiPreference(
                        title = entryTitle,
                        summary = entrySummary,
                        onClick = { onExpandEntry(if (expandedEntry == index) null else index) },
                        trailing = {
                            CouiSwitch(
                                checked = entry.enabled,
                                enabled = enabled,
                                onCheckedChange = onToggleEntry,
                            )
                        },
                    )
                }
                if (expandedEntry == index) {
                    item {
                        CharacterWorldbookEntryEditor(
                            entry = entry,
                            enabled = enabled,
                            unsupported = unsupported[index].orEmpty(),
                            onChange = { replacement ->
                                store.updateWorldbook { current -> current.copy(entries = current.entries.toMutableList().apply { this[index] = replacement }) }
                            },
                            onDelete = {
                                onExpandEntry(null)
                                store.updateWorldbook { current -> current.copy(entries = current.entries.filterIndexed { i, _ -> i != index }) }
                            },
                        )
                    }
                }
            }
        } else {
            Card(
                modifier = Modifier
                    .padding(horizontal = CharacterCardPadding, vertical = 6.dp),
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
                if (expandedEntry == index) {
                    CharacterWorldbookEntryEditor(
                        entry = entry,
                        enabled = enabled,
                        unsupported = unsupported[index].orEmpty(),
                        onChange = { replacement ->
                            store.updateWorldbook { current -> current.copy(entries = current.entries.toMutableList().apply { this[index] = replacement }) }
                        },
                        onDelete = {
                            onExpandEntry(null)
                            store.updateWorldbook { current -> current.copy(entries = current.entries.filterIndexed { i, _ -> i != index }) }
                        },
                    )
                }
            }
        }
    }
    item(key = "worldbook-add-entry") {
        val addEntryText = stringResource(R.string.character_worldbook_add_entry)
        val onAddEntry: () -> Unit = {
            onExpandEntry(book.entries.size)
            store.updateWorldbook { it.copy(entries = it.entries + CharacterBookEntryDraft(insertionOrder = it.entries.size)) }
        }
        if (LocalUiStyle.current == AppearanceUiStyle.COUI) {
            CouiButton(
                text = addEntryText,
                enabled = enabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = CouiPageDefaults.horizontalMargin, vertical = 6.dp),
                onClick = onAddEntry,
            )
        } else {
            TextButton(
                text = addEntryText,
                enabled = enabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = CharacterCardPadding, vertical = 6.dp),
                onClick = onAddEntry,
            )
        }
    }
}

@Composable
private fun CharacterWorldbookEntryEditor(
    entry: CharacterBookEntryDraft,
    enabled: Boolean,
    unsupported: List<String>,
    onChange: (CharacterBookEntryDraft) -> Unit,
    onDelete: () -> Unit,
) {
    val uiStyle = LocalUiStyle.current
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
    CharacterTextField(stringResource(R.string.character_worldbook_entry_title_label), entry.name, { onChange(entry.copy(name = it)) }, enabled, singleLine = true, inGroup = true)
    CharacterTextField(contentLabel, entry.content, { onChange(entry.copy(content = it)) }, enabled, minLines = 4, inGroup = true)
    CharacterFieldGroupLabel(stringResource(R.string.character_worldbook_group_trigger))
    CharacterTextField(stringResource(R.string.character_worldbook_primary_keys_label), entry.keys.joinToString("\n"), { onChange(entry.copy(keys = it.lines())) }, enabled, inGroup = true)
    CharacterTextField(stringResource(R.string.character_worldbook_secondary_keys_label), entry.secondaryKeys.joinToString("\n"), { onChange(entry.copy(secondaryKeys = it.lines())) }, enabled, inGroup = true)
    val selectiveTitle = stringResource(R.string.character_worldbook_selective_title)
    if (uiStyle == AppearanceUiStyle.COUI) {
        CouiSwitchPreference(
            title = selectiveTitle,
            checked = entry.selective,
            enabled = enabled,
            onCheckedChange = { onChange(entry.copy(selective = it)) },
        )
    } else {
        SwitchPreference(
            title = selectiveTitle,
            checked = entry.selective,
            enabled = enabled,
            onCheckedChange = { onChange(entry.copy(selective = it)) },
        )
    }
    CharacterFieldGroupLabel(stringResource(R.string.character_worldbook_group_insertion))
    val constantTitle = stringResource(R.string.character_worldbook_constant_title)
    val beforeCharTitle = stringResource(R.string.character_worldbook_before_char_title)
    val beforeCharSummary = stringResource(R.string.character_worldbook_before_char_summary)
    if (uiStyle == AppearanceUiStyle.COUI) {
        CouiSwitchPreference(
            title = constantTitle,
            checked = entry.constant,
            enabled = enabled,
            onCheckedChange = { onChange(entry.copy(constant = it)) },
        )
        CouiDivider()
        CouiSwitchPreference(
            title = beforeCharTitle,
            summary = beforeCharSummary,
            checked = entry.position == "before_char",
            enabled = enabled,
            onCheckedChange = { onChange(entry.copy(position = if (it) "before_char" else "after_char")) },
        )
    } else {
        SwitchPreference(
            title = constantTitle,
            checked = entry.constant,
            enabled = enabled,
            onCheckedChange = { onChange(entry.copy(constant = it)) },
        )
        SwitchPreference(
            title = beforeCharTitle,
            summary = beforeCharSummary,
            checked = entry.position == "before_char",
            enabled = enabled,
            onCheckedChange = { onChange(entry.copy(position = if (it) "before_char" else "after_char")) },
        )
    }
    CharacterTextField(stringResource(R.string.character_worldbook_insertion_order_label), entry.insertionOrder.toString(), { value ->
        value.toIntOrNull()?.takeIf { it >= 0 }?.let { onChange(entry.copy(insertionOrder = it)) }
    }, enabled, singleLine = true, inGroup = true)
    val removeEntryText = stringResource(R.string.character_worldbook_remove_entry)
    if (uiStyle == AppearanceUiStyle.COUI) {
        CouiButton(
            text = removeEntryText,
            enabled = enabled,
            role = CouiButtonRole.Destructive,
            onClick = onDelete,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
        )
    } else {
        TextButton(
            text = removeEntryText,
            enabled = enabled,
            onClick = onDelete,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
}

private fun optionalNonNegativeInt(value: String, onValue: (Int?) -> Unit) {
    if (value.isBlank()) onValue(null)
    else value.toIntOrNull()?.takeIf { it >= 0 }?.let(onValue)
}
