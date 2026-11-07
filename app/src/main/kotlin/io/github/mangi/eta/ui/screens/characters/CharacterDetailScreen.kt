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
import androidx.compose.ui.unit.dp
import com.itos.coui.dialog.CouiDialogDefaults
import com.itos.coui.layout.CouiPageDefaults
import com.itos.coui.layout.Divider
import com.itos.coui.layout.Group
import com.itos.coui.text.CouiTextKind
import com.itos.coui.text.Text as CouiText
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
import io.github.mangi.eta.ui.navigation.AppRoute
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

internal fun greetingTitle(index: Int) = if (index == 0) "默认开场白" else "开场白 ${index + 1}"

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
    val avatarPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) store.changeAvatar(id, uri)
    }
    val profile = store.selected?.takeIf { it.id == id }
    val userName = if (store.usePersona) store.persona.name.ifBlank { "用户" } else "用户"

    when (LocalUiStyle.current) {
        AppearanceUiStyle.COUI -> {
            val pageColors = CouiPageDefaults.themeColors()
            val dialogColors = CouiDialogDefaults.themeColors()
            CouiScaffoldPage(title = "", onBack = onBack, compactTitle = true) {
                if (profile == null) {
                    CharacterPageMessage(if (store.busy) "正在读取角色…" else "无法读取角色，请返回后重试")
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
                            "角色设定",
                            CharacterMacros.expand(profile.card.description, profile.card, userName),
                        )
                    },
                    userName = userName,
                )
                Group {
                    CouiSwitchPreference(
                        title = "使用我的人设",
                        summary = store.persona.name.ifBlank { "未设置称呼" },
                        checked = store.usePersona,
                        onCheckedChange = { store.usePersona = it },
                    )
                    Divider()
                    CouiPreference(
                        title = "编辑我的人设",
                        onClick = { onNavigate(AppRoute.CharacterPersona) },
                    )
                }
                CouiSectionTitle(text = "开场白")
                val greetings = remember(profile.card, userName) {
                    (listOf(profile.card.firstMessage) + profile.card.alternateGreetings)
                        .map { CharacterMacros.expand(it, profile.card, userName) }
                }
                GreetingPager(
                    greetings = greetings,
                    selected = store.greetingIndex,
                    onSelect = { store.greetingIndex = it },
                    onRead = { index -> preview = CharacterTextPreview(greetingTitle(index), greetings[index]) },
                )
                Group(title = "管理") {
                    CouiPreference(
                        title = "编辑角色",
                        enabled = !store.busy,
                        onClick = {
                            store.discardEditor()
                            onNavigate(AppRoute.CharacterEditor(id))
                        },
                    )
                    Divider()
                    CouiPreference(
                        title = "剧情记忆",
                        summary = "此角色各次对话共享的故事与关系记录",
                        onClick = { onNavigate(AppRoute.CharacterMemory(id)) },
                    )
                    Divider()
                    CouiPreference(
                        title = "复制角色",
                        enabled = !store.busy,
                        onClick = { store.duplicate(id) { onNavigate(AppRoute.CharacterDetail(it)) } },
                    )
                    Divider()
                    CouiPreference(
                        title = "导出 PNG 角色卡",
                        enabled = !store.busy,
                        onClick = { pngExporter.launch(characterExportName(profile.card.name, "png")) },
                    )
                    Divider()
                    CouiPreference(
                        title = "导出 JSON 角色卡",
                        enabled = !store.busy,
                        onClick = { jsonExporter.launch(characterExportName(profile.card.name, "json")) },
                    )
                }
                if (store.compatibilityWarnings.isNotEmpty()) {
                    Group {
                        CouiPreference(
                            title = "兼容说明",
                            summary = "${store.compatibilityWarnings.size} 项内容按兼容范围处理",
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
                        title = "删除角色",
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
            MiuixScaffoldPage(title = "", onBack = onBack, compactTitle = true) {
                if (profile == null) {
                    item { CharacterPageMessage(if (store.busy) "正在读取角色…" else "无法读取角色，请返回后重试") }
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
                                "角色设定",
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
                            title = "使用我的人设",
                            summary = store.persona.name.ifBlank { "未设置称呼" },
                            checked = store.usePersona,
                            onCheckedChange = { store.usePersona = it },
                        )
                        ArrowPreference(title = "编辑我的人设", onClick = { onNavigate(AppRoute.CharacterPersona) })
                    }
                }
                item(key = "greetings") {
                    SmallTitle("开场白")
                    val greetings = remember(profile.card, userName) {
                        (listOf(profile.card.firstMessage) + profile.card.alternateGreetings)
                            .map { CharacterMacros.expand(it, profile.card, userName) }
                    }
                    GreetingPager(
                        greetings = greetings,
                        selected = store.greetingIndex,
                        onSelect = { store.greetingIndex = it },
                        onRead = { index -> preview = CharacterTextPreview(greetingTitle(index), greetings[index]) },
                    )
                }
                item(key = "management") {
                    SmallTitle("管理")
                    Card(
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 12.dp),
                    ) {
                        ArrowPreference(title = "编辑角色", enabled = !store.busy, onClick = {
                            store.discardEditor()
                            onNavigate(AppRoute.CharacterEditor(id))
                        })
                        ArrowPreference(title = "剧情记忆", summary = "此角色各次对话共享的故事与关系记录", onClick = {
                            onNavigate(AppRoute.CharacterMemory(id))
                        })
                        ArrowPreference(title = "复制角色", enabled = !store.busy, onClick = {
                            store.duplicate(id) { onNavigate(AppRoute.CharacterDetail(it)) }
                        })
                        ArrowPreference(
                            title = "导出 PNG 角色卡",
                            enabled = !store.busy,
                            onClick = { pngExporter.launch(characterExportName(profile.card.name, "png")) },
                        )
                        ArrowPreference(
                            title = "导出 JSON 角色卡",
                            enabled = !store.busy,
                            onClick = { jsonExporter.launch(characterExportName(profile.card.name, "json")) },
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
                                title = "兼容说明",
                                summary = "${store.compatibilityWarnings.size} 项内容按兼容范围处理",
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
                            title = "删除角色",
                            titleColor = BasicComponentDefaults.titleColor(color = MiuixTheme.colorScheme.error),
                            enabled = !store.busy,
                            onClick = { showDeleteConfirm = true },
                        )
                    }
                }
            }
        }
    }

    if (showDeleteConfirm && profile != null) {
        val message = "「${profile.card.name}」将从角色库移除，角色图片与剧情记忆一并删除；已有对话保留。此操作无法撤销。"
        val onConfirmDelete: () -> Unit = {
            showDeleteConfirm = false
            store.delete(id) { onBack() }
        }
        when (LocalUiStyle.current) {
            AppearanceUiStyle.COUI -> {
                CouiConfirmDialog(
                    visible = true,
                    title = "删除角色",
                    message = message,
                    confirmText = "删除",
                    confirmEnabled = !store.busy,
                    destructive = true,
                    onConfirm = onConfirmDelete,
                    onDismissRequest = { showDeleteConfirm = false },
                )
            }
            AppearanceUiStyle.MIUIX -> {
                WindowDialog(
                    show = true,
                    title = "删除角色",
                    summary = message,
                    onDismissRequest = { showDeleteConfirm = false },
                ) {
                    MiuixDialogActions(
                        confirmText = "删除",
                        destructive = true,
                        confirmEnabled = !store.busy,
                        onCancel = { showDeleteConfirm = false },
                        onConfirm = onConfirmDelete,
                    )
                }
            }
        }
    }

    preview?.let { current ->
        ItemDescriptionDialog(
            title = current.title,
            description = current.text,
            onDismiss = { preview = null },
        )
    }
}

private fun characterExportName(name: String, extension: String): String {
    val basename = name.map { if (it.isISOControl() || it in "\\/:*?\"<>|") '_' else it }
        .joinToString("").trim().trimEnd('.').take(64).ifBlank { "角色" }
    return "$basename.$extension"
}
