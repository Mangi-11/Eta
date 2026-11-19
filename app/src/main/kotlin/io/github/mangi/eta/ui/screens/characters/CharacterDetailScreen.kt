package io.github.mangi.eta.ui.screens.characters

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.itos.coui.dialog.CouiDialogDefaults
import com.itos.coui.layout.CouiPageDefaults
import com.itos.coui.layout.Divider
import com.itos.coui.layout.Group
import com.itos.coui.text.CouiTextKind
import com.itos.coui.text.Text as CouiText
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.roleplay.CharacterCardFormat
import io.github.mangi.eta.agent.roleplay.CharacterMacros
import io.github.mangi.eta.agent.roleplay.RoleplayBinding
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.ui.app.CharacterLibraryStore
import io.github.mangi.eta.ui.app.LocalUiStyle
import io.github.mangi.eta.ui.components.ItemDescriptionDialog
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.components.coui.CouiConfirmDialog
import io.github.mangi.eta.ui.components.coui.CouiPageBottomSpacer
import io.github.mangi.eta.ui.components.coui.CouiPreference
import io.github.mangi.eta.ui.components.coui.CouiScaffoldPage
import io.github.mangi.eta.ui.components.coui.CouiSectionTitle
import io.github.mangi.eta.ui.components.coui.CouiSwitchPreference
import io.github.mangi.eta.ui.components.currentGlassOverlayBackdrop
import io.github.mangi.eta.ui.navigation.AppRoute
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.glass.GlassDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun greetingTitle(index: Int): String =
    if (index == 0) stringResource(R.string.character_greeting_default)
    else stringResource(R.string.character_greeting_numbered, index + 1)

private sealed interface CharacterPreviewTitle {
    data object Description : CharacterPreviewTitle
    data class Greeting(val index: Int) : CharacterPreviewTitle
}

@Composable
private fun CharacterPreviewTitle.resolve(): String = when (this) {
    CharacterPreviewTitle.Description -> stringResource(R.string.character_section_description)
    is CharacterPreviewTitle.Greeting -> greetingTitle(index)
}

private data class CharacterTextPreview(val title: CharacterPreviewTitle, val text: String)

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
    var retainedPreview by remember(id) { mutableStateOf<CharacterTextPreview?>(null) }
    if (preview != null) retainedPreview = preview
    val pngExporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) {
        if (it != null) store.export(id, CharacterCardFormat.PNG, it)
    }
    val jsonExporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) {
        if (it != null) store.export(id, CharacterCardFormat.JSON, it)
    }
    val avatarPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) store.changeAvatar(id, uri)
    }
    val profile = store.selected?.takeIf { it.id == id }
    val defaultUserName = stringResource(R.string.character_default_user_name)
    val userName = if (store.usePersona) store.persona.name.ifBlank { defaultUserName } else defaultUserName
    val exportFallbackName = stringResource(R.string.character_export_default_basename)
    val loadStateText = stringResource(
        if (store.busy) R.string.character_detail_loading else R.string.character_detail_load_failed,
    )
    val usePersonaTitle = stringResource(R.string.character_detail_use_persona)
    val personaNameSummary = store.persona.name.ifBlank { stringResource(R.string.character_persona_name_unset) }
    val editPersonaTitle = stringResource(R.string.character_detail_edit_persona)
    val greetingsSectionTitle = stringResource(R.string.character_section_greetings)
    val manageSectionTitle = stringResource(R.string.character_detail_section_manage)
    val editCharacterTitle = stringResource(R.string.character_action_edit)
    val memoryTitle = stringResource(R.string.character_memory_title)
    val memorySummary = stringResource(R.string.character_detail_memory_summary)
    val duplicateTitle = stringResource(R.string.character_action_duplicate)
    val exportPngTitle = stringResource(R.string.character_action_export_png)
    val exportJsonTitle = stringResource(R.string.character_action_export_json)
    val compatibilityTitle = stringResource(R.string.character_detail_compatibility_title)
    val compatibilitySummary = if (store.compatibilityWarnings.isNotEmpty()) {
        pluralStringResource(
            R.plurals.character_detail_compatibility_summary,
            store.compatibilityWarnings.size,
            store.compatibilityWarnings.size,
        )
    } else ""
    val deleteCharacterTitle = stringResource(R.string.character_action_delete)
    val deleteConfirmText = stringResource(R.string.action_delete)
    val deleteMessage = profile?.let {
        stringResource(R.string.character_delete_confirm_message, it.card.name)
    }.orEmpty()
    val activePreview = preview ?: retainedPreview
    val activePreviewTitle = activePreview?.title?.resolve().orEmpty()

    when (LocalUiStyle.current) {
        AppearanceUiStyle.COUI -> {
            val pageColors = CouiPageDefaults.themeColors()
            val dialogColors = CouiDialogDefaults.themeColors()
            CouiScaffoldPage(title = "", onBack = onBack, compactTitle = true) {
                if (profile == null) {
                    CharacterPageMessage(loadStateText)
                    return@CouiScaffoldPage
                }
                CharacterHero(
                    profile = profile,
                    busy = store.busy,
                    onChangeAvatar = { avatarPicker.launch("image/*") },
                    onToggleFavorite = { store.toggleFavorite(id) },
                    onStart = { store.startConversation(id, onStart) },
                    onReadDescription = {
                        preview = CharacterTextPreview(
                            CharacterPreviewTitle.Description,
                            CharacterMacros.expand(profile.card.description, profile.card, userName),
                        )
                    },
                    userName = userName,
                )
                Group {
                    CouiSwitchPreference(
                        title = usePersonaTitle,
                        summary = personaNameSummary,
                        checked = store.usePersona,
                        onCheckedChange = { store.usePersona = it },
                    )
                    Divider()
                    CouiPreference(
                        title = editPersonaTitle,
                        onClick = { onNavigate(AppRoute.CharacterPersona) },
                    )
                }
                CouiSectionTitle(text = greetingsSectionTitle)
                val greetings = remember(profile.card, userName) {
                    (listOf(profile.card.firstMessage) + profile.card.alternateGreetings)
                        .map { CharacterMacros.expand(it, profile.card, userName) }
                }
                GreetingPager(
                    greetings = greetings,
                    selected = store.greetingIndex,
                    onSelect = { store.greetingIndex = it },
                    onRead = { index -> preview = CharacterTextPreview(CharacterPreviewTitle.Greeting(index), greetings[index]) },
                )
                Group(title = manageSectionTitle) {
                    CouiPreference(
                        title = editCharacterTitle,
                        enabled = !store.busy,
                        onClick = {
                            store.discardEditor()
                            onNavigate(AppRoute.CharacterEditor(id))
                        },
                    )
                    Divider()
                    CouiPreference(
                        title = memoryTitle,
                        summary = memorySummary,
                        onClick = { onNavigate(AppRoute.CharacterMemory(id)) },
                    )
                    Divider()
                    CouiPreference(
                        title = duplicateTitle,
                        enabled = !store.busy,
                        onClick = { store.duplicate(id) { onNavigate(AppRoute.CharacterDetail(it)) } },
                    )
                    Divider()
                    CouiPreference(
                        title = exportPngTitle,
                        enabled = !store.busy,
                        onClick = { pngExporter.launch(characterExportName(profile.card.name, "png", exportFallbackName)) },
                    )
                    Divider()
                    CouiPreference(
                        title = exportJsonTitle,
                        enabled = !store.busy,
                        onClick = { jsonExporter.launch(characterExportName(profile.card.name, "json", exportFallbackName)) },
                    )
                }
                if (store.compatibilityWarnings.isNotEmpty()) {
                    Group {
                        CouiPreference(
                            title = compatibilityTitle,
                            summary = compatibilitySummary,
                            onClick = { showCompatibility = !showCompatibility },
                        )
                        if (showCompatibility) {
                            Divider()
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
                Group {
                    CouiPreference(
                        title = deleteCharacterTitle,
                        titleColor = dialogColors.destructive,
                        enabled = !store.busy,
                        showChevron = false,
                        onClick = { showDeleteConfirm = true },
                    )
                }
                CouiPageBottomSpacer()
            }
        }

        AppearanceUiStyle.MIUIX -> {
            MiuixScaffoldPage(
                title = "",
                onBack = onBack,
                compactTitle = true,
                overlay = {
                    GlassDialog(
                        show = showDeleteConfirm && profile != null,
                        backdrop = currentGlassOverlayBackdrop(),
                        title = deleteCharacterTitle,
                        summary = deleteMessage,
                        onDismissRequest = { showDeleteConfirm = false },
                    ) {
                        MiuixDialogActions(
                            confirmText = deleteConfirmText,
                            destructive = true,
                            confirmEnabled = !store.busy && profile != null,
                            onCancel = { showDeleteConfirm = false },
                            onConfirm = {
                                showDeleteConfirm = false
                                store.delete(id) { onBack() }
                            },
                        )
                    }
                    ItemDescriptionDialog(
                        show = preview != null,
                        title = activePreviewTitle,
                        description = activePreview?.text.orEmpty(),
                        onDismiss = { preview = null },
                    )
                },
            ) {
                if (profile == null) {
                    item { CharacterPageMessage(loadStateText) }
                    return@MiuixScaffoldPage
                }
                item(key = "hero") {
                    CharacterHero(
                        profile = profile,
                        busy = store.busy,
                        onChangeAvatar = { avatarPicker.launch("image/*") },
                        onToggleFavorite = { store.toggleFavorite(id) },
                        onStart = { store.startConversation(id, onStart) },
                        onReadDescription = {
                            preview = CharacterTextPreview(
                                CharacterPreviewTitle.Description,
                                CharacterMacros.expand(profile.card.description, profile.card, userName),
                            )
                        },
                        userName = userName,
                    )
                }
                item(key = "persona") {
                    Card(
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 12.dp),
                    ) {
                        SwitchPreference(
                            title = usePersonaTitle,
                            summary = personaNameSummary,
                            checked = store.usePersona,
                            onCheckedChange = { store.usePersona = it },
                        )
                        ArrowPreference(title = editPersonaTitle, onClick = { onNavigate(AppRoute.CharacterPersona) })
                    }
                }
                item(key = "greetings") {
                    SmallTitle(greetingsSectionTitle)
                    val greetings = remember(profile.card, userName) {
                        (listOf(profile.card.firstMessage) + profile.card.alternateGreetings)
                            .map { CharacterMacros.expand(it, profile.card, userName) }
                    }
                    GreetingPager(
                        greetings = greetings,
                        selected = store.greetingIndex,
                        onSelect = { store.greetingIndex = it },
                        onRead = { index -> preview = CharacterTextPreview(CharacterPreviewTitle.Greeting(index), greetings[index]) },
                    )
                }
                item(key = "management") {
                    SmallTitle(manageSectionTitle)
                    Card(
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 12.dp),
                    ) {
                        ArrowPreference(title = editCharacterTitle, enabled = !store.busy, onClick = {
                            store.discardEditor()
                            onNavigate(AppRoute.CharacterEditor(id))
                        })
                        ArrowPreference(title = memoryTitle, summary = memorySummary, onClick = {
                            onNavigate(AppRoute.CharacterMemory(id))
                        })
                        ArrowPreference(title = duplicateTitle, enabled = !store.busy, onClick = {
                            store.duplicate(id) { onNavigate(AppRoute.CharacterDetail(it)) }
                        })
                        ArrowPreference(
                            title = exportPngTitle,
                            enabled = !store.busy,
                            onClick = { pngExporter.launch(characterExportName(profile.card.name, "png", exportFallbackName)) },
                        )
                        ArrowPreference(
                            title = exportJsonTitle,
                            enabled = !store.busy,
                            onClick = { jsonExporter.launch(characterExportName(profile.card.name, "json", exportFallbackName)) },
                        )
                    }
                }
                if (store.compatibilityWarnings.isNotEmpty()) {
                    item(key = "compatibility") {
                        Card(
                            modifier = Modifier
                                .padding(horizontal = 12.dp)
                                .padding(bottom = 12.dp),
                        ) {
                            ArrowPreference(
                                title = compatibilityTitle,
                                summary = compatibilitySummary,
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
                item(key = "danger") {
                    Card(
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 12.dp),
                    ) {
                        ArrowPreference(
                            title = deleteCharacterTitle,
                            titleColor = BasicComponentDefaults.titleColor(color = MiuixTheme.colorScheme.error),
                            enabled = !store.busy,
                            onClick = { showDeleteConfirm = true },
                        )
                    }
                }
            }
        }
    }

    val uiStyle = LocalUiStyle.current
    if (uiStyle == AppearanceUiStyle.COUI) {
        if (showDeleteConfirm && profile != null) {
            CouiConfirmDialog(
                visible = true,
                title = deleteCharacterTitle,
                message = deleteMessage,
                confirmText = deleteConfirmText,
                confirmEnabled = !store.busy,
                destructive = true,
                onConfirm = {
                    showDeleteConfirm = false
                    store.delete(id) { onBack() }
                },
                onDismissRequest = { showDeleteConfirm = false },
            )
        }
        ItemDescriptionDialog(
            show = preview != null,
            title = activePreviewTitle,
            description = activePreview?.text.orEmpty(),
            onDismiss = { preview = null },
        )
    }
}

private fun characterExportName(name: String, extension: String, fallbackBaseName: String): String {
    val basename = name.map { if (it.isISOControl() || it in "\\/:*?\"<>|") '_' else it }
        .joinToString("").trim().trimEnd('.').take(64).ifBlank { fallbackBaseName }
    return "$basename.$extension"
}
