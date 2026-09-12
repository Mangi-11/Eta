package io.github.mangi.eta.ui.screens.characters

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
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.navigation.AppRoute
import top.yukonga.miuix.kmp.basic.BasicComponentColors
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.RadioButtonPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

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
    MiuixScaffoldPage(title = profile?.card?.name ?: "角色详情", onBack = onBack) {
        if (profile == null) {
            item { CharacterPageMessage(if (store.busy) "正在读取角色…" else "无法读取角色，请返回后重试") }
            return@MiuixScaffoldPage
        }
        item(key = "profile") {
            Card(
                modifier = Modifier.padding(horizontal = CharacterCardPadding, vertical = 6.dp),
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
                            text = "阅读全文",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.primary,
                            modifier = Modifier
                                .padding(top = 6.dp)
                                .clickable {
                                    preview = CharacterTextPreview("角色设定", profile.card.description)
                                },
                        )
                    }
                }
            }
        }
        item(key = "start") {
            TextButton(
                text = "开始新对话",
                enabled = !store.busy,
                onClick = { store.startConversation(id, onStart) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = CharacterCardPadding, vertical = 6.dp),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
        item(key = "persona") {
            Card(
                modifier = Modifier.padding(horizontal = CharacterCardPadding, vertical = 6.dp),
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
        item(key = "greetings-title") { SmallTitle("开场白") }
        item(key = "greetings") {
            Card(modifier = Modifier.padding(horizontal = CharacterCardPadding)) {
                val greetings = listOf(profile.card.firstMessage) + profile.card.alternateGreetings
                greetings.forEachIndexed { index, greeting ->
                    RadioButtonPreference(
                        title = if (index == 0) "默认开场白" else "开场白 ${index + 1}",
                        summary = greeting.take(GreetingPreviewChars)
                            .let { if (greeting.length > GreetingPreviewChars) "$it…" else it }
                            .ifBlank { "没有预设开场白，由你先开口" },
                        selected = store.greetingIndex == index,
                        onClick = { store.greetingIndex = index },
                        bottomAction = if (greeting.length > GreetingPreviewChars) {
                            {
                                Text(
                                    text = "阅读全文",
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = MiuixTheme.colorScheme.primary,
                                    modifier = Modifier
                                        .padding(start = 16.dp, bottom = 10.dp)
                                        .clickable {
                                            preview = CharacterTextPreview(
                                                if (index == 0) "默认开场白" else "开场白 ${index + 1}",
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
        item(key = "management-title") { SmallTitle("管理") }
        item(key = "management") {
            Card(modifier = Modifier.padding(horizontal = CharacterCardPadding)) {
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
                    title = "删除角色",
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
                    TextButton(
                        text = "导出 PNG",
                        onClick = { pngExporter.launch(characterExportName(profile.card.name, "png")) },
                        enabled = !store.busy,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        text = "导出 JSON",
                        onClick = { jsonExporter.launch(characterExportName(profile.card.name, "json")) },
                        enabled = !store.busy,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        if (store.compatibilityWarnings.isNotEmpty()) {
            item(key = "compatibility") {
                Card(
                    modifier = Modifier
                        .padding(horizontal = CharacterCardPadding)
                        .padding(top = 12.dp),
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
    }

    if (showDeleteConfirm && profile != null) {
        WindowDialog(
            show = true,
            title = "删除角色",
            summary = "「${profile.card.name}」将从角色库移除，角色图片与剧情记忆一并删除；已有对话保留。此操作无法撤销。",
            onDismissRequest = { showDeleteConfirm = false },
        ) {
            MiuixDialogActions(
                confirmText = "删除",
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
        WindowDialog(
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
            TextButton(
                text = "关闭",
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
        .joinToString("").trim().trimEnd('.').take(64).ifBlank { "角色" }
    return "$basename.$extension"
}
