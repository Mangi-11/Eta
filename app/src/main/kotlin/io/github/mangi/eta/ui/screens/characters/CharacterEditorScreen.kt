package io.github.mangi.eta.ui.screens.characters

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
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
import com.itos.coui.layout.CouiPageDefaults
import com.itos.coui.layout.Group
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.ui.app.CharacterLibraryStore
import io.github.mangi.eta.ui.app.LocalUiStyle
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.components.coui.CouiButton
import io.github.mangi.eta.ui.components.coui.CouiButtonRole
import io.github.mangi.eta.ui.components.coui.CouiLazyScaffoldPage
import io.github.mangi.eta.ui.components.coui.CouiPreference
import io.github.mangi.eta.ui.components.coui.CouiSectionTitle
import io.github.mangi.eta.ui.components.coui.CouiTextField
import io.github.mangi.eta.ui.components.coui.CouiTopAppBarIconButton
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
    val card = store.draft
    val pageTitle = if (id == null) "创建角色" else "编辑角色"
    val canSave = !store.busy && store.draftName.isNotBlank()
    val uiStyle = LocalUiStyle.current

    val listContent: LazyListScope.() -> Unit = {
        if (card == null) {
            item { CharacterPageMessage(if (store.busy) "正在读取…" else "无法读取角色，请返回重试") }
        } else {
            item(key = "name") { CharacterTextField("名称", store.draftName, store::updateName, !store.busy, singleLine = true) }
            item(key = "description-title") { CharacterSectionHeader("角色设定") }
            item(key = "description") { CharacterTextField("外貌、性格与经历", card.description, { value -> store.updateDraft { it.withEdits(description = value) } }, !store.busy, minLines = 5) }
            item(key = "greeting-title") { CharacterSectionHeader("开场白") }
            item(key = "greeting") { CharacterTextField("默认开场白", card.firstMessage, { value -> store.updateDraft { it.withEdits(firstMessage = value) } }, !store.busy) }
            itemsIndexed(card.alternateGreetings, key = { index, _ -> "alternate-$index" }) { index, value ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        CharacterTextField(
                            label = "开场白 ${index + 2}",
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
                            contentDescription = "移除此开场白",
                            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            }
            item(key = "add-greeting") {
                val onAddGreeting: () -> Unit = {
                    store.updateDraft { it.withEdits(alternateGreetings = it.alternateGreetings + "") }
                }
                if (uiStyle == AppearanceUiStyle.COUI) {
                    CouiButton(
                        text = "添加备用开场白",
                        onClick = onAddGreeting,
                        enabled = !store.busy,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = CouiPageDefaults.horizontalMargin, vertical = 6.dp),
                    )
                } else {
                    TextButton(
                        text = "添加备用开场白",
                        onClick = onAddGreeting,
                        enabled = !store.busy,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
            item(key = "advanced") {
                if (uiStyle == AppearanceUiStyle.COUI) {
                    Group {
                        CouiPreference(
                            title = "高级设置",
                            summary = "性格、背景、示例对话、提示词、作者信息与世界书",
                            onClick = { advanced = !advanced },
                        )
                    }
                } else {
                    Card(
                        modifier = Modifier
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        ArrowPreference(
                            title = "高级设置",
                            summary = "性格、背景、示例对话、提示词、作者信息与世界书",
                            onClick = { advanced = !advanced },
                        )
                    }
                }
            }
            if (advanced) {
                item(key = "personality") { CharacterTextField("性格与说话风格", card.personality, { value -> store.updateDraft { it.withEdits(personality = value) } }, !store.busy) }
                item(key = "scenario") { CharacterTextField("故事背景", card.scenario, { value -> store.updateDraft { it.withEdits(scenario = value) } }, !store.busy) }
                item(key = "examples") { CharacterTextField("示例对话", card.exampleMessages, { value -> store.updateDraft { it.withEdits(exampleMessages = value) } }, !store.busy, minLines = 4) }
                item(key = "prompts-title") { CharacterSectionHeader("提示词") }
                item(key = "system") { CharacterTextField("系统提示词", card.systemPrompt, { value -> store.updateDraft { it.withEdits(systemPrompt = value) } }, !store.busy) }
                item(key = "post-history") { CharacterTextField("对话后置指令", card.postHistoryInstructions, { value -> store.updateDraft { it.withEdits(postHistoryInstructions = value) } }, !store.busy) }
                item(key = "credits-title") { CharacterSectionHeader("作者信息") }
                item(key = "creator-notes") { CharacterTextField("作者备注", card.creatorNotes, { value -> store.updateDraft { it.withEdits(creatorNotes = value) } }, !store.busy) }
                item(key = "tags") { CharacterTextField("标签（每行一个）", card.tags.joinToString("\n"), { value -> store.updateDraft { it.withEdits(tags = value.lines()) } }, !store.busy) }
                item(key = "creator") { CharacterTextField("作者", card.creator, { value -> store.updateDraft { it.withEdits(creator = value) } }, !store.busy, singleLine = true) }
                item(key = "version") { CharacterTextField("角色版本", card.version, { value -> store.updateDraft { it.withEdits(version = value) } }, !store.busy, singleLine = true) }
                characterWorldbookEditor(
                    store = store,
                    expanded = worldbookExpanded,
                    expandedEntry = worldbookEntry,
                    onToggleExpanded = { worldbookExpanded = !worldbookExpanded },
                    onExpandEntry = { worldbookEntry = it },
                )
                item(key = "compatibility") { CharacterPageMessage("角色卡中的第三方脚本与扩展界面不会执行，相关数据会保留在导出的角色卡中。") }
            }
            item(key = "save") {
                val saveText = if (store.busy) "正在处理…" else "保存角色"
                if (uiStyle == AppearanceUiStyle.COUI) {
                    CouiButton(
                        text = saveText,
                        onClick = { store.saveEditor(onSaved) },
                        enabled = canSave,
                        role = CouiButtonRole.Primary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = CouiPageDefaults.horizontalMargin, vertical = 12.dp),
                    )
                } else {
                    TextButton(
                        text = saveText,
                        onClick = { store.saveEditor(onSaved) },
                        enabled = canSave,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )
                }
            }
        }
    }

    when (uiStyle) {
        AppearanceUiStyle.COUI -> {
            CouiLazyScaffoldPage(
                title = pageTitle,
                onBack = onBack,
                actions = {
                    CouiTopAppBarIconButton(
                        icon = Icons.Rounded.Check,
                        contentDescription = "保存角色",
                        enabled = canSave,
                        onClick = { store.saveEditor(onSaved) },
                    )
                },
                content = listContent,
            )
        }
        AppearanceUiStyle.MIUIX -> {
            MiuixScaffoldPage(
                title = pageTitle,
                onBack = onBack,
                modifier = Modifier.imePadding(),
                actions = {
                    IconButton(
                        onClick = { store.saveEditor(onSaved) },
                        enabled = canSave,
                    ) {
                        Icon(Icons.Rounded.Check, contentDescription = "保存角色")
                    }
                },
                content = listContent,
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
    inGroup: Boolean = false,
) {
    when (LocalUiStyle.current) {
        AppearanceUiStyle.COUI -> {
            val fieldContent: @Composable () -> Unit = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = CouiPageDefaults.rowPadding, vertical = 8.dp),
                ) {
                    CouiTextField(
                        value = value,
                        onValueChange = onChange,
                        label = label,
                        enabled = enabled,
                        singleLine = singleLine,
                        minLines = if (singleLine) 1 else minLines,
                        maxLines = if (singleLine) 1 else Int.MAX_VALUE,
                    )
                }
            }
            if (inGroup) {
                fieldContent()
            } else {
                Group {
                    fieldContent()
                }
            }
        }
        AppearanceUiStyle.MIUIX -> {
            TextField(
                value = value,
                onValueChange = onChange,
                label = label,
                useLabelAsPlaceholder = !singleLine,
                enabled = enabled,
                singleLine = singleLine,
                minLines = if (singleLine) 1 else minLines,
                maxLines = if (singleLine) 1 else Int.MAX_VALUE,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
internal fun CharacterSectionHeader(text: String) {
    if (LocalUiStyle.current == AppearanceUiStyle.COUI) {
        CouiSectionTitle(text = text)
    } else {
        SmallTitle(text)
    }
}

@Composable
internal fun CharacterFieldGroupLabel(text: String) {
    if (LocalUiStyle.current == AppearanceUiStyle.COUI) {
        CouiSectionTitle(text = text)
    } else {
        Text(
            text = text,
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(horizontal = 28.dp, vertical = 6.dp),
        )
    }
}
