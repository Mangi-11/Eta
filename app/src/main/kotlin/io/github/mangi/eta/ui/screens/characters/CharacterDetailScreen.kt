package io.github.mangi.eta.ui.screens.characters
import io.github.mangi.eta.R
import androidx.compose.ui.res.stringResource

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.agent.roleplay.CharacterCardFormat
import io.github.mangi.eta.agent.roleplay.RoleplayBinding
import io.github.mangi.eta.ui.app.CharacterLibraryStore
import io.github.mangi.eta.ui.components.EtaArrowPreference
import io.github.mangi.eta.ui.components.EtaCard
import io.github.mangi.eta.ui.components.EtaPreferenceDivider
import io.github.mangi.eta.ui.components.EtaPreferenceGroup
import io.github.mangi.eta.ui.components.EtaPreferenceGroupTitle
import io.github.mangi.eta.ui.components.EtaRadioButtonPreference
import io.github.mangi.eta.ui.components.EtaSwitchPreference
import io.github.mangi.eta.ui.components.EtaTextButton
import io.github.mangi.eta.ui.components.EtaWindowDialog
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.navigation.AppRoute
import top.yukonga.miuix.kmp.basic.BasicComponentColors
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

private const val DescriptionPreviewChars = 220
private const val GreetingPreviewChars = 240

private data class CharacterTextPreview(val title: String, val text: String)

@Composable
internal fun CharacterDetailScreen(
    id: String,
    store: CharacterLibraryStore,
    onNavigate: (AppRoute) -> Unit,
    onBack: () -> Unit,
    onStart: (RoleplayBinding, String) -> Unit,
) {
    var showCompatibility by rememberSaveable(id) { mutableStateOf(false) }
    var showDeleteConfirm by rememberSaveable(id) { mutableStateOf(false) }
    var preview by remember(id) { mutableStateOf<CharacterTextPreview?>(null) }
    val pngExporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) {
        if (it != null) store.export(id, CharacterCardFormat.PNG, it)
    }
    val jsonExporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) {
        if (it != null) store.export(id, CharacterCardFormat.JSON, it)
    }
    val profile = store.selected?.takeIf { it.id == id }
    MiuixScaffoldPage(title = profile?.card?.name ?: stringResource(R.string.character_details), onBack = onBack) {
        if (profile == null) {
            item { CharacterPageMessage(if (store.busy) stringResource(R.string.character_reading) else stringResource(R.string.character_load_failed_go_back)) }
            return@MiuixScaffoldPage
        }
        item(key = "profile") {
            EtaCard(
                modifier = Modifier.padding(horizontal = CharacterCardPadding, vertical = 8.dp),
                insideMargin = PaddingValues(16.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(profile.card.name, style = MiuixTheme.textStyles.title2)
                    if (profile.card.tags.isNotEmpty()) {
                        Text(
                            text = profile.card.tags.joinToString(" · "),
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (profile.card.description.isNotBlank()) {
                    Text(
                        text = profile.card.description,
                        style = MiuixTheme.textStyles.body2,
                        modifier = Modifier.padding(top = 14.dp),
                        maxLines = 6,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (profile.card.description.length > DescriptionPreviewChars) {
                        Text(
                            text = stringResource(R.string.character_read_full_text),
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.primary,
                            modifier = Modifier
                                .padding(top = 6.dp)
                                .clickable {
                                    preview = CharacterTextPreview(stringResource(R.string.character_definition), profile.card.description)
                                },
                        )
                    }
                }
            }
        }
        item(key = "start") {
            EtaTextButton(
                text = stringResource(R.string.character_start_new_chat),
                enabled = !store.busy,
                onClick = { store.startConversation(id, onStart) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = CharacterCardPadding, vertical = 6.dp),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
        item(key = "persona") {
            EtaPreferenceGroup(
                modifier = Modifier.padding(horizontal = CharacterCardPadding, vertical = 8.dp),
            ) {
                EtaSwitchPreference(
                    title = stringResource(R.string.character_use_my_persona),
                    summary = store.persona.name.ifBlank { stringResource(R.string.character_call_name_not_set) },
                    checked = store.usePersona,
                    onCheckedChange = { store.usePersona = it },
                )
                EtaPreferenceDivider(hasLeading = false)
                EtaArrowPreference(title = stringResource(R.string.character_edit_user_persona), onClick = { onNavigate(AppRoute.CharacterPersona) })
            }
        }
        item(key = "greetings-title") { EtaPreferenceGroupTitle(stringResource(R.string.character_greeting)) }
        item(key = "greetings") {
            EtaPreferenceGroup(modifier = Modifier.padding(horizontal = CharacterCardPadding)) {
                val greetings = listOf(profile.card.firstMessage) + profile.card.alternateGreetings
                greetings.forEachIndexed { index, greeting ->
                    if (index > 0) EtaPreferenceDivider(hasLeading = false)
                    EtaRadioButtonPreference(
                        title = if (index == 0) stringResource(R.string.character_default_greeting) else stringResource(R.string.character_indexed_greeting, index + 1),
                        summary = greeting.take(GreetingPreviewChars)
                            .let { if (greeting.length > GreetingPreviewChars) "$it…" else it }
                            .ifBlank { stringResource(R.string.character_greeting_none_you_start) },
                        selected = store.greetingIndex == index,
                        onClick = { store.greetingIndex = index },
                        bottomAction = if (greeting.length > GreetingPreviewChars) {
                            {
                                Text(
                                    text = stringResource(R.string.character_read_full_text),
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = MiuixTheme.colorScheme.primary,
                                    modifier = Modifier
                                        .padding(start = 16.dp, bottom = 10.dp)
                                        .clickable {
                                            preview = CharacterTextPreview(
                                                if (index == 0) stringResource(R.string.character_default_greeting) else stringResource(R.string.character_indexed_greeting, index + 1),
                                                greeting,
                                            )
                                        },
                                )
                            }
                        } else {
                            null
                        },
                    )
                }
            }
        }
        item(key = "management-title") { EtaPreferenceGroupTitle(stringResource(R.string.character_manage)) }
        item(key = "management") {
            EtaPreferenceGroup(modifier = Modifier.padding(horizontal = CharacterCardPadding)) {
                EtaArrowPreference(title = stringResource(R.string.character_edit), enabled = !store.busy, onClick = {
                    store.discardEditor()
                    onNavigate(AppRoute.CharacterEditor(id))
                })
                EtaPreferenceDivider(hasLeading = false)
                EtaArrowPreference(title = stringResource(R.string.character_plot_memory), summary = stringResource(R.string.character_shared_story_and_relationship_notes), onClick = {
                    onNavigate(AppRoute.CharacterMemory(id))
                })
                EtaPreferenceDivider(hasLeading = false)
                EtaArrowPreference(title = stringResource(R.string.character_duplicate), enabled = !store.busy, onClick = {
                    store.duplicate(id) { onNavigate(AppRoute.CharacterDetail(it)) }
                })
                EtaPreferenceDivider(hasLeading = false)
                EtaArrowPreference(
                    title = stringResource(R.string.character_delete_title),
                    titleColor = BasicComponentColors(
                        color = MiuixTheme.colorScheme.error,
                        disabledColor = MiuixTheme.colorScheme.disabledOnSurface,
                    ),
                    enabled = !store.busy,
                    onClick = { showDeleteConfirm = true },
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    EtaTextButton(
                        text = stringResource(R.string.character_export_png),
                        onClick = { pngExporter.launch(characterExportName(profile.card.name, "png")) },
                        enabled = !store.busy,
                        modifier = Modifier.weight(1f),
                    )
                    EtaTextButton(
                        text = stringResource(R.string.character_export_json),
                        onClick = { jsonExporter.launch(characterExportName(profile.card.name, "json")) },
                        enabled = !store.busy,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        if (store.compatibilityWarnings.isNotEmpty()) {
            item(key = "compatibility") {
                EtaPreferenceGroup(
                    modifier = Modifier
                        .padding(horizontal = CharacterCardPadding)
                        .padding(top = 12.dp),
                ) {
                    EtaArrowPreference(
                        title = stringResource(R.string.catalog_compatibility_notes),
                        summary = stringResource(R.string.character_compatibility_warning_count, store.compatibilityWarnings.size),
                        onClick = { showCompatibility = !showCompatibility },
                    )
                    if (showCompatibility) {
                        store.compatibilityWarnings.forEach { warning ->
                            Text(
                                text = warning,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }
                }
            }
        }
    }

    if (showDeleteConfirm && profile != null) {
        EtaWindowDialog(
            show = true,
            title = stringResource(R.string.character_delete_title),
            summary = stringResource(R.string.character_delete_confirm_desc, profile.card.name),
            onDismissRequest = { showDeleteConfirm = false },
        ) {
            MiuixDialogActions(
                confirmText = stringResource(R.string.character_delete),
                destructive = true,
                confirmEnabled = !store.busy,
                onCancel = { showDeleteConfirm = false },
                onConfirm = {
                    showDeleteConfirm = false
                    store.delete(id) { onBack() }
                },
            )
        }
    }

    preview?.let { current ->
        EtaWindowDialog(
            show = true,
            title = current.title,
            onDismissRequest = { preview = null },
        ) {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(current.text, style = MiuixTheme.textStyles.body2)
            }
            EtaTextButton(
                text = stringResource(R.string.character_close),
                onClick = { preview = null },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}

private fun characterExportName(name: String, extension: String): String {
    val basename = name.map { if (it.isISOControl() || it in "\\/:*?\"<>|") '_' else it }
        .joinToString("").trim().trimEnd('.').take(64).ifBlank { stringResource(R.string.character_label) }
    return "$basename.$extension"
}
