package io.github.mangi.eta.ui.screens.characters

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.util.lerp
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.state.CharacterLibraryStore
import io.github.mangi.eta.ui.components.ScaffoldPage
import io.github.mangi.eta.ui.components.TopBarActionButton
import io.github.mangi.eta.ui.components.rememberDraftTextFieldState
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.ChevronForward
import top.yukonga.miuix.kmp.icon.glass.Ok
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
    val pageTitle = stringResource(if (id == null) R.string.character_action_create else R.string.character_action_edit)
    val saveActionDesc = stringResource(R.string.character_action_save)
    val canSave = !store.busy && store.draftName.isNotBlank()

    ScaffoldPage(
        title = pageTitle,
        onBack = onBack,
        modifier = Modifier.imePadding(),
        actions = {
            TopBarActionButton(
                icon = MiuixIcons.Glass.Ok,
                contentDescription = saveActionDesc,
                enabled = canSave,
                onClick = { store.saveEditor(onSaved) },
            )
        },
    ) {
        if (card == null) {
            item {
                CharacterPageMessage(
                    stringResource(if (store.isLoadingEditor) R.string.character_editor_loading else R.string.character_editor_load_failed),
                )
            }
        } else {
            item(key = "name") { CharacterTextField(stringResource(R.string.character_field_name), store.draftName, store::updateName, !store.busy, singleLine = true) }
            item(key = "description-title") { CharacterSectionHeader(stringResource(R.string.character_section_description)) }
            item(key = "description") { CharacterTextField(stringResource(R.string.character_field_description_label), card.description, { value -> store.updateDraft { it.withEdits(description = value) } }, !store.busy, minLines = 5) }
            item(key = "greeting-title") { CharacterSectionHeader(stringResource(R.string.character_section_greetings)) }
            item(key = "greeting") { CharacterTextField(stringResource(R.string.character_greeting_default), card.firstMessage, { value -> store.updateDraft { it.withEdits(firstMessage = value) } }, !store.busy) }
            itemsIndexed(card.alternateGreetings, key = { index, _ -> "alternate-$index" }) { index, value ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        CharacterTextField(
                            label = stringResource(R.string.character_greeting_numbered, index + 2),
                            value = value,
                            onChange = { updated ->
                                store.updateDraft { it.withEdits(alternateGreetings = it.alternateGreetings.toMutableList().apply { this[index] = updated }) }
                            },
                            enabled = !store.busy,
                            minLines = 2,
                        )
                    }
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
                            contentDescription = stringResource(R.string.character_greeting_remove),
                            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            }
            item(key = "add-greeting") {
                val addGreetingText = stringResource(R.string.character_greeting_add_alternate)
                val onAddGreeting: () -> Unit = {
                    store.updateDraft { it.withEdits(alternateGreetings = it.alternateGreetings + "") }
                }
                TextButton(
                    text = addGreetingText,
                    onClick = onAddGreeting,
                    enabled = !store.busy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp),
                )
            }
            item(key = "advanced") {
                val advancedTitle = stringResource(R.string.character_editor_advanced_title)
                val advancedSummary = stringResource(R.string.character_editor_advanced_summary)
                ExpandableSectionCard(
                    title = advancedTitle,
                    summary = advancedSummary,
                    expanded = advanced,
                    onToggle = { advanced = !advanced },
                )
            }
            item(key = "advanced-content") {
                AnimatedVisibility(
                    visible = advanced,
                    enter = expandVertically(tween(300)) + fadeIn(tween(300)),
                    exit = shrinkVertically(tween(300)) + fadeOut(tween(300)),
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        CharacterTextField(stringResource(R.string.character_field_personality), card.personality, { value -> store.updateDraft { it.withEdits(personality = value) } }, !store.busy)
                        CharacterTextField(stringResource(R.string.character_field_scenario), card.scenario, { value -> store.updateDraft { it.withEdits(scenario = value) } }, !store.busy)
                        CharacterTextField(stringResource(R.string.character_field_example_messages), card.exampleMessages, { value -> store.updateDraft { it.withEdits(exampleMessages = value) } }, !store.busy, minLines = 4)
                        CharacterSectionHeader(stringResource(R.string.character_section_prompts))
                        CharacterTextField(stringResource(R.string.character_field_system_prompt), card.systemPrompt, { value -> store.updateDraft { it.withEdits(systemPrompt = value) } }, !store.busy)
                        CharacterTextField(stringResource(R.string.character_field_post_history_instructions), card.postHistoryInstructions, { value -> store.updateDraft { it.withEdits(postHistoryInstructions = value) } }, !store.busy)
                        CharacterSectionHeader(stringResource(R.string.character_section_credits))
                        CharacterTextField(stringResource(R.string.character_field_creator_notes), card.creatorNotes, { value -> store.updateDraft { it.withEdits(creatorNotes = value) } }, !store.busy)
                        CharacterTextField(stringResource(R.string.character_field_tags), card.tags.joinToString("\n"), { value -> store.updateDraft { it.withEdits(tags = value.lines()) } }, !store.busy)
                        CharacterTextField(stringResource(R.string.character_field_creator), card.creator, { value -> store.updateDraft { it.withEdits(creator = value) } }, !store.busy, singleLine = true)
                        CharacterTextField(stringResource(R.string.character_field_version), card.version, { value -> store.updateDraft { it.withEdits(version = value) } }, !store.busy, singleLine = true)
                        CharacterWorldbookSection(
                            store = store,
                            expanded = worldbookExpanded,
                            expandedEntry = worldbookEntry,
                            onToggleExpanded = { worldbookExpanded = !worldbookExpanded },
                            onExpandEntry = { worldbookEntry = it },
                        )
                        CharacterPageMessage(stringResource(R.string.character_editor_compatibility_note))
                    }
                }
            }
            item(key = "save") {
                val saveText = stringResource(if (store.busy) R.string.character_editor_processing else R.string.character_action_save)
                TextButton(
                    text = saveText,
                    onClick = { store.saveEditor(onSaved) },
                    enabled = canSave,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
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
    val fieldState = rememberDraftTextFieldState(value, onChange)
    TextField(
        state = fieldState,
        lineLimits = if (singleLine) TextFieldLineLimits.SingleLine else TextFieldLineLimits.MultiLine(minHeightInLines = minLines),
        label = label,
        useLabelAsPlaceholder = !singleLine,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp),
    )
}

@Composable
internal fun CharacterSectionHeader(text: String) {
    SmallTitle(text)
}

@Composable
internal fun CharacterFieldGroupLabel(text: String) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.footnote1,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

@Composable
internal fun ExpandableSectionCard(
    title: String,
    summary: String,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val layoutDirection = LocalLayoutDirection.current
    val expandProgress = animateFloatAsState(
        targetValue = if (expanded) 1f else 0f,
        animationSpec = tween(300),
        label = "expand_progress",
    )
    Card(
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onToggle()
                }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MiuixTheme.textStyles.headline1,
                    fontWeight = FontWeight.Medium,
                    color = MiuixTheme.colorScheme.onSurface,
                )
                Text(
                    text = summary,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            Spacer(Modifier.width(8.dp))
            Image(
                imageVector = MiuixIcons.Glass.ChevronForward,
                contentDescription = null,
                modifier = Modifier
                    .size(15.dp)
                    .graphicsLayer {
                        scaleX = if (layoutDirection == LayoutDirection.Rtl) -1f else 1f
                        rotationZ = lerp(0f, 90f, expandProgress.value)
                    },
                colorFilter = ColorFilter.tint(MiuixTheme.colorScheme.onSurfaceVariantSummary),
            )
        }
    }
}
