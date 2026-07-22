package fuck.andes.ui.screens.skills

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.R as LucideR
import fuck.andes.ui.components.MiuixScaffoldPage
import fuck.andes.ui.components.PrefDivider
import fuck.andes.ui.model.AgentSkillsAction
import fuck.andes.ui.model.AgentSkillsUiState
import fuck.andes.ui.model.SkillItemUi
import fuck.andes.ui.model.canDeleteUserSkill
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

private val CardHorizontalPadding = 12.dp
private val CardBottomPadding = 12.dp

@Composable
fun AgentSkillsScreen(
    state: AgentSkillsUiState,
    onAction: (AgentSkillsAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    var deleteTarget by remember { mutableStateOf<SkillItemUi?>(null) }
    val operationPending = state.isImporting || state.busySkillId != null
    val zipPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) onAction(AgentSkillsAction.ImportZip(uri.toString()))
    }
    val openZipPicker = {
        zipPicker.launch(
            arrayOf(
                "application/zip",
                "application/x-zip-compressed",
                "application/octet-stream",
            ),
        )
    }

    MiuixScaffoldPage(
        title = "技能",
        onBack = { onAction(AgentSkillsAction.NavigateBack) },
        modifier = modifier,
    ) {
        val installed = state.skills.filter { it.installed }
        val builtinInstalled = installed.filter { it.source == "builtin" }
        val userInstalled = installed.filter { it.canDeleteUserSkill }
        val removed = state.skills.filter { !it.installed }

        item(key = "zip-import-title") { SmallTitle("安装") }
        item(key = "zip-import-card") {
            Card(
                modifier = Modifier
                    .padding(horizontal = CardHorizontalPadding)
                    .padding(bottom = CardBottomPadding),
            ) {
                BasicComponent(
                    title = if (state.isImporting) "正在检查技能包" else "从 ZIP 导入",
                    summary = if (state.isImporting) {
                        "正在验证并安装，请稍候"
                    } else {
                        "选择包含 SKILL.md 的技能包"
                    },
                    startAction = {
                        if (state.isImporting) {
                            Box(
                                modifier = Modifier
                                    .padding(end = 12.dp)
                                    .size(36.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                InfiniteProgressIndicator(size = 22.dp)
                            }
                        } else {
                            ZipImportIcon()
                        }
                    },
                    enabled = !operationPending,
                    onClick = openZipPicker,
                    onClickLabel = "选择 ZIP 技能包",
                )
            }
        }

        if (builtinInstalled.isNotEmpty()) {
            item(key = "builtin-title") { SmallTitle("内置技能") }
            item(key = "builtin-card") {
                Card(
                    modifier = Modifier
                        .padding(horizontal = CardHorizontalPadding)
                        .padding(bottom = CardBottomPadding),
                ) {
                    builtinInstalled.forEachIndexed { index, skill ->
                        SkillSwitchRow(
                            skill = skill,
                            enabled = !operationPending,
                            onToggle = { enabled ->
                                onAction(AgentSkillsAction.ToggleSkill(skill.id, enabled))
                            },
                        )
                        if (index < builtinInstalled.lastIndex) PrefDivider()
                    }
                }
            }
        }

        if (userInstalled.isNotEmpty()) {
            item(key = "user-title") { SmallTitle("用户技能") }
            item(key = "user-card") {
                Card(
                    modifier = Modifier
                        .padding(horizontal = CardHorizontalPadding)
                        .padding(bottom = CardBottomPadding),
                ) {
                    userInstalled.forEachIndexed { index, skill ->
                        SkillSwitchRow(
                            skill = skill,
                            enabled = !operationPending,
                            onToggle = { enabled ->
                                onAction(AgentSkillsAction.ToggleSkill(skill.id, enabled))
                            },
                            onDelete = { deleteTarget = skill },
                        )
                        if (index < userInstalled.lastIndex) PrefDivider()
                    }
                }
            }
        }

        if (removed.isNotEmpty()) {
            item(key = "removed-title") { SmallTitle("已移除") }
            item(key = "removed-card") {
                Card(
                    modifier = Modifier
                        .padding(horizontal = CardHorizontalPadding)
                        .padding(bottom = CardBottomPadding),
                ) {
                    removed.forEachIndexed { index, skill ->
                        BasicComponent(
                            title = skill.name,
                            summary = "点击重新安装",
                            startAction = { SkillIcon(skill) },
                            enabled = !operationPending,
                            onClick = {
                                onAction(AgentSkillsAction.ReinstallBuiltin(skill.id))
                            },
                        )
                        if (index < removed.lastIndex) PrefDivider()
                    }
                }
            }
        }

        if (state.skills.isEmpty() && !state.isLoading) {
            item(key = "empty") { SmallTitle("暂无已安装技能") }
        }
    }

    state.replacement?.let { replacement ->
        WindowDialog(
            show = true,
            title = "替换用户技能？",
            summary = "已存在用户技能「${replacement.name}」（${replacement.id}）。替换会覆盖它当前的全部文件。",
            onDismissRequest = { onAction(AgentSkillsAction.CancelZipReplacement) },
        ) {
            DialogActions(
                confirmText = "替换",
                confirmEnabled = !operationPending,
                onCancel = { onAction(AgentSkillsAction.CancelZipReplacement) },
                onConfirm = { onAction(AgentSkillsAction.ConfirmZipReplacement) },
            )
        }
    }

    deleteTarget?.let { skill ->
        WindowDialog(
            show = true,
            title = "删除用户技能？",
            summary = "删除「${skill.name}」后需要重新安装才能恢复。",
            onDismissRequest = { deleteTarget = null },
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = {
                        deleteTarget = null
                        onAction(AgentSkillsAction.DeleteSkill(skill.id))
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !operationPending,
                    colors = ButtonDefaults.buttonColorsPrimary(
                        color = MiuixTheme.colorScheme.error,
                        contentColor = MiuixTheme.colorScheme.onError,
                    ),
                ) {
                    Text("删除该技能")
                }
                TextButton(
                    text = "取消",
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { deleteTarget = null },
                )
            }
        }
    }

    state.notice?.let { notice ->
        WindowDialog(
            show = true,
            title = notice.title,
            summary = notice.message,
            onDismissRequest = { onAction(AgentSkillsAction.DismissNotice) },
        ) {
            TextButton(
                text = "知道了",
                onClick = { onAction(AgentSkillsAction.DismissNotice) },
                modifier = Modifier.fillMaxWidth(),
                colors = if (notice.isError) {
                    ButtonDefaults.textButtonColors()
                } else {
                    ButtonDefaults.textButtonColorsPrimary()
                },
            )
        }
    }
}

@Composable
private fun SkillSwitchRow(
    skill: SkillItemUi,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    val truncatedSummary = remember(skill.description) {
        val desc = skill.description.ifBlank { "无描述" }
        if (desc.length > 80) desc.take(80) + "..." else desc
    }
    BasicComponent(
        title = skill.name,
        summary = truncatedSummary,
        startAction = { SkillIcon(skill) },
        endActions = {
            onDelete?.let {
                IconButton(
                    onClick = it,
                    enabled = enabled,
                    minWidth = 36.dp,
                    minHeight = 36.dp,
                ) {
                    Icon(
                        painter = painterResource(LucideR.drawable.lucide_ic_trash_2),
                        contentDescription = "删除 ${skill.name}",
                        modifier = Modifier.size(20.dp),
                        tint = MiuixTheme.colorScheme.error,
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
            }
            Switch(
                checked = skill.enabled,
                onCheckedChange = onToggle,
                enabled = enabled,
            )
        },
    )
}

@Composable
private fun ZipImportIcon() {
    Box(
        modifier = Modifier
            .padding(end = 12.dp)
            .size(36.dp)
            .background(MiuixTheme.colorScheme.surfaceContainerHigh, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(LucideR.drawable.lucide_ic_file_archive),
            contentDescription = null,
            modifier = Modifier.size(22.dp),
            tint = MiuixTheme.colorScheme.onBackground,
        )
    }
}

@Composable
private fun DialogActions(
    confirmText: String,
    confirmEnabled: Boolean,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        TextButton(
            text = "取消",
            onClick = onCancel,
        )
        Spacer(modifier = Modifier.width(8.dp))
        TextButton(
            text = confirmText,
            onClick = onConfirm,
            enabled = confirmEnabled,
            colors = ButtonDefaults.textButtonColorsPrimary(),
        )
    }
}

@Composable
private fun SkillIcon(skill: SkillItemUi) {
    Box(
        modifier = Modifier
            .padding(end = 12.dp)
            .size(36.dp)
            .background(MiuixTheme.colorScheme.surfaceContainerHigh, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(iconForSkill(skill.id)),
            contentDescription = null,
            modifier = Modifier.size(22.dp),
            tint = MiuixTheme.colorScheme.onBackground,
        )
    }
}

private fun iconForSkill(skillId: String): Int = when (skillId) {
    "self-improving-agent" -> LucideR.drawable.lucide_ic_refresh_cw
    "skill-creator" -> LucideR.drawable.lucide_ic_pencil_ruler
    else -> LucideR.drawable.lucide_ic_puzzle
}
