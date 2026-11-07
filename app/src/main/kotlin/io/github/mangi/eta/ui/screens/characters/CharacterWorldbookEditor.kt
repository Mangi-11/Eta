package io.github.mangi.eta.ui.screens.characters

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.itos.coui.layout.CouiPageDefaults
import com.itos.coui.layout.Divider
import com.itos.coui.layout.Group
import com.itos.coui.switch.CouiSwitchDefaults
import com.itos.coui.switch.Switch as CouiSwitch
import com.itos.coui.theme.CouiTheme
import io.github.mangi.eta.agent.roleplay.CharacterBookEntryDraft
import io.github.mangi.eta.agent.roleplay.CharacterWorldbook
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.ui.app.CharacterLibraryStore
import io.github.mangi.eta.ui.app.LocalUiStyle
import io.github.mangi.eta.ui.components.coui.CouiButton
import io.github.mangi.eta.ui.components.coui.CouiButtonRole
import io.github.mangi.eta.ui.components.coui.CouiPreference
import io.github.mangi.eta.ui.components.coui.CouiSwitchPreference
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
    item(key = "worldbook-title") { CharacterSectionHeader("世界书") }
    item(key = "worldbook-header") {
        val summary = "${book.entries.size} 个条目 · ${if (expanded) "收起" else "展开编辑"}"
        if (LocalUiStyle.current == AppearanceUiStyle.COUI) {
            Group {
                CouiPreference(
                    title = "内嵌世界书",
                    summary = summary,
                    onClick = onToggleExpanded,
                )
            }
        } else {
            Card(
                modifier = Modifier
                    .padding(horizontal = CharacterCardPadding)
                    .padding(bottom = 12.dp),
            ) {
                ArrowPreference(
                    title = "内嵌世界书",
                    summary = summary,
                    onClick = onToggleExpanded,
                )
            }
        }
    }
    if (!expanded) return
    item(key = "worldbook-name") {
        CharacterTextField("世界书名称", book.name, { value -> store.updateWorldbook { it.copy(name = value) } }, enabled, singleLine = true)
    }
    item(key = "worldbook-depth") {
        CharacterTextField("扫描最近消息数（留空使用默认值）", book.scanDepth?.toString().orEmpty(), { value ->
            optionalNonNegativeInt(value) { number -> store.updateWorldbook { it.copy(scanDepth = number) } }
        }, enabled, singleLine = true)
    }
    item(key = "worldbook-budget") {
        CharacterTextField("Token 预算（留空自动分配）", book.tokenBudget?.toString().orEmpty(), { value ->
            optionalNonNegativeInt(value) { number -> store.updateWorldbook { it.copy(tokenBudget = number) } }
        }, enabled, singleLine = true)
    }
    item(key = "worldbook-recursive") {
        if (LocalUiStyle.current == AppearanceUiStyle.COUI) {
            Group {
                CouiSwitchPreference(
                    title = "递归匹配",
                    summary = "使用已匹配条目的内容继续寻找相关条目",
                    checked = book.recursiveScanning == true,
                    enabled = enabled,
                    onCheckedChange = { value -> store.updateWorldbook { it.copy(recursiveScanning = value) } },
                )
            }
        } else {
            Card(
                modifier = Modifier
                    .padding(horizontal = CharacterCardPadding, vertical = 6.dp),
            ) {
                SwitchPreference(
                    title = "递归匹配",
                    summary = "使用已匹配条目的内容继续寻找相关条目",
                    checked = book.recursiveScanning == true,
                    enabled = enabled,
                    onCheckedChange = { value -> store.updateWorldbook { it.copy(recursiveScanning = value) } },
                )
            }
        }
    }
    item(key = "worldbook-entries-title") { CharacterSectionHeader("条目") }
    val unsupported = CharacterWorldbook.unsupportedEntries(card).associate { it.index to it.reasons }
    itemsIndexed(book.entries, key = { index, _ -> "worldbook-entry-$index" }) { index, entry ->
        val entryTitle = entry.name.take(120).ifBlank { "条目 ${index + 1}" }
        val entrySummary = when {
            !entry.enabled -> "已停用"
            !unsupported[index].isNullOrEmpty() -> "已跳过：${unsupported[index].orEmpty().joinToString("；")}"
            entry.constant -> "始终参与上下文"
            else -> entry.keys.take(4).joinToString("、").take(160).ifBlank { "尚未设置触发关键词" }
        }
        val onToggleEntry: (Boolean) -> Unit = { value ->
            store.updateWorldbook { current ->
                current.copy(entries = current.entries.toMutableList().apply { this[index] = entry.copy(enabled = value) })
            }
        }
        if (LocalUiStyle.current == AppearanceUiStyle.COUI) {
            val dark = CouiTheme.isDark
            val colors = CouiPageDefaults.themeColors(dark)
            Group {
                CouiPreference(
                    title = entryTitle,
                    summary = entrySummary,
                    onClick = { onExpandEntry(if (expandedEntry == index) null else index) },
                    trailing = {
                        CouiSwitch(
                            checked = entry.enabled,
                            enabled = enabled,
                            dark = dark,
                            colors = CouiSwitchDefaults.colors(colors.accent, dark),
                            onCheckedChange = onToggleEntry,
                        )
                    },
                )
                if (expandedEntry == index) {
                    Divider()
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
        val onAddEntry: () -> Unit = {
            onExpandEntry(book.entries.size)
            store.updateWorldbook { it.copy(entries = it.entries + CharacterBookEntryDraft(insertionOrder = it.entries.size)) }
        }
        if (LocalUiStyle.current == AppearanceUiStyle.COUI) {
            CouiButton(
                text = "添加条目",
                enabled = enabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = CouiPageDefaults.horizontalMargin, vertical = 6.dp),
                onClick = onAddEntry,
            )
        } else {
            TextButton(
                text = "添加条目",
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
        Text(
            "此条目暂不参与匹配：${unsupported.joinToString("；")}",
            style = MiuixTheme.textStyles.body2,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
    CharacterFieldGroupLabel("内容")
    CharacterTextField("条目标题", entry.name, { onChange(entry.copy(name = it)) }, enabled, singleLine = true, inGroup = true)
    CharacterTextField("内容", entry.content, { onChange(entry.copy(content = it)) }, enabled, minLines = 4, inGroup = true)
    CharacterFieldGroupLabel("触发")
    CharacterTextField("主关键词（每行一个）", entry.keys.joinToString("\n"), { onChange(entry.copy(keys = it.lines())) }, enabled, inGroup = true)
    CharacterTextField("次级关键词（每行一个）", entry.secondaryKeys.joinToString("\n"), { onChange(entry.copy(secondaryKeys = it.lines())) }, enabled, inGroup = true)
    if (uiStyle == AppearanceUiStyle.COUI) {
        CouiSwitchPreference(
            title = "同时匹配次级关键词",
            checked = entry.selective,
            enabled = enabled,
            onCheckedChange = { onChange(entry.copy(selective = it)) },
        )
    } else {
        SwitchPreference(
            title = "同时匹配次级关键词",
            checked = entry.selective,
            enabled = enabled,
            onCheckedChange = { onChange(entry.copy(selective = it)) },
        )
    }
    CharacterFieldGroupLabel("插入")
    if (uiStyle == AppearanceUiStyle.COUI) {
        CouiSwitchPreference(
            title = "常驻上下文",
            checked = entry.constant,
            enabled = enabled,
            onCheckedChange = { onChange(entry.copy(constant = it)) },
        )
        Divider()
        CouiSwitchPreference(
            title = "放在角色设定之前",
            summary = "关闭时放在角色设定之后",
            checked = entry.position == "before_char",
            enabled = enabled,
            onCheckedChange = { onChange(entry.copy(position = if (it) "before_char" else "after_char")) },
        )
    } else {
        SwitchPreference(
            title = "常驻上下文",
            checked = entry.constant,
            enabled = enabled,
            onCheckedChange = { onChange(entry.copy(constant = it)) },
        )
        SwitchPreference(
            title = "放在角色设定之前",
            summary = "关闭时放在角色设定之后",
            checked = entry.position == "before_char",
            enabled = enabled,
            onCheckedChange = { onChange(entry.copy(position = if (it) "before_char" else "after_char")) },
        )
    }
    CharacterTextField("插入顺序", entry.insertionOrder.toString(), { value ->
        value.toIntOrNull()?.takeIf { it >= 0 }?.let { onChange(entry.copy(insertionOrder = it)) }
    }, enabled, singleLine = true, inGroup = true)
    if (uiStyle == AppearanceUiStyle.COUI) {
        CouiButton(
            text = "移除条目",
            enabled = enabled,
            role = CouiButtonRole.Destructive,
            onClick = onDelete,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
        )
    } else {
        TextButton(
            text = "移除条目",
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
