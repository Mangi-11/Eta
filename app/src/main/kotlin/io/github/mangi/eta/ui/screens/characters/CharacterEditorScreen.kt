package io.github.mangi.eta.ui.screens.characters

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
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
import io.github.mangi.eta.ui.components.CharacterAvatar
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.ArrowPreference
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
    val avatarPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) store.importAvatar(uri)
    }
    val card = store.draft
    MiuixScaffoldPage(
        title = if (id == null) "创建角色" else "编辑角色",
        onBack = onBack,
        modifier = Modifier.imePadding(),
        actions = {
            IconButton(
                onClick = { store.saveEditor(onSaved) },
                enabled = !store.busy && store.draftName.isNotBlank(),
            ) {
                Icon(Icons.Rounded.Check, contentDescription = "保存角色")
            }
        },
    ) {
        if (card == null) {
            item { CharacterPageMessage(if (store.busy) "正在读取…" else "无法读取角色，请返回重试") }
            return@MiuixScaffoldPage
        }
        item(key = "avatar") {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CharacterAvatar(store.draftName, store.selected?.avatarPath, bytes = store.avatarDraft, size = 64.dp)
                TextButton("选择头像", onClick = { avatarPicker.launch(arrayOf("image/*")) }, enabled = !store.busy)
            }
        }
        item(key = "name") { CharacterTextField("名称", store.draftName, store::updateName, !store.busy, singleLine = true) }
        item(key = "description-title") { SmallTitle("角色设定") }
        item(key = "description") { CharacterTextField("外貌、性格与经历", card.description, { value -> store.updateDraft { it.withEdits(description = value) } }, !store.busy, minLines = 5) }
        item(key = "greeting-title") { SmallTitle("开场白") }
        item(key = "greeting") { CharacterTextField("默认开场白", card.firstMessage, { value -> store.updateDraft { it.withEdits(firstMessage = value) } }, !store.busy) }
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
                    label = "开场白 ${index + 2}",
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
                        contentDescription = "移除此开场白",
                        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        }
        item(key = "add-greeting") {
            TextButton(
                "添加备用开场白",
                onClick = { store.updateDraft { it.withEdits(alternateGreetings = it.alternateGreetings + "") } },
                enabled = !store.busy,
                modifier = Modifier.padding(start = 16.dp, top = 2.dp),
            )
        }
        item(key = "advanced") {
            Card(modifier = Modifier.padding(horizontal = CharacterCardPadding, vertical = 8.dp)) {
                ArrowPreference(
                    title = "高级设置",
                    summary = "性格、背景、示例对话、提示词、作者信息与世界书",
                    onClick = { advanced = !advanced },
                )
            }
        }
        if (advanced) {
            item(key = "personality") { CharacterTextField("性格与说话风格", card.personality, { value -> store.updateDraft { it.withEdits(personality = value) } }, !store.busy) }
            item(key = "scenario") { CharacterTextField("故事背景", card.scenario, { value -> store.updateDraft { it.withEdits(scenario = value) } }, !store.busy) }
            item(key = "examples") { CharacterTextField("示例对话", card.exampleMessages, { value -> store.updateDraft { it.withEdits(exampleMessages = value) } }, !store.busy, minLines = 4) }
            item(key = "prompts-title") { SmallTitle("提示词") }
            item(key = "system") { CharacterTextField("系统提示词", card.systemPrompt, { value -> store.updateDraft { it.withEdits(systemPrompt = value) } }, !store.busy) }
            item(key = "post-history") { CharacterTextField("对话后置指令", card.postHistoryInstructions, { value -> store.updateDraft { it.withEdits(postHistoryInstructions = value) } }, !store.busy) }
            item(key = "credits-title") { SmallTitle("作者信息") }
            item(key = "creator-notes") { CharacterTextField("作者备注", card.creatorNotes, { value -> store.updateDraft { it.withEdits(creatorNotes = value) } }, !store.busy) }
            item(key = "tags") { CharacterTextField("标签（每行一个）", card.tags.joinToString("\n"), { value -> store.updateDraft { it.withEdits(tags = value.lines()) } }, !store.busy) }
            item(key = "creator") { CharacterTextField("作者", card.creator, { value -> store.updateDraft { it.withEdits(creator = value) } }, !store.busy, singleLine = true) }
            item(key = "version") { CharacterTextField("角色版本", card.version, { value -> store.updateDraft { it.withEdits(version = value) } }, !store.busy, singleLine = true) }
            characterWorldbookEditor(
                store = store, expanded = worldbookExpanded, expandedEntry = worldbookEntry,
                onToggleExpanded = { worldbookExpanded = !worldbookExpanded },
                onExpandEntry = { worldbookEntry = it },
            )
            item(key = "compatibility") { CharacterPageMessage("角色卡中的第三方脚本与扩展界面不会执行，相关数据会保留在导出的角色卡中。") }
        }
        item(key = "save") {
            TextButton(
                text = if (store.busy) "正在处理…" else "保存角色",
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
