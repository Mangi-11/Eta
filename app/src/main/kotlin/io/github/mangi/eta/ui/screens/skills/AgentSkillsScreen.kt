package io.github.mangi.eta.ui.screens.skills

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.ui.app.LocalUiStyle
import io.github.mangi.eta.ui.components.ListEmptyState
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.components.coui.CouiButton
import io.github.mangi.eta.ui.components.coui.CouiButtonRole
import io.github.mangi.eta.ui.components.coui.CouiConfirmDialog
import io.github.mangi.eta.ui.components.coui.CouiNoticeDialog
import io.github.mangi.eta.ui.components.coui.CouiPageBottomSpacer
import io.github.mangi.eta.ui.components.coui.CouiPreference
import io.github.mangi.eta.ui.components.coui.CouiPreferenceGroup
import io.github.mangi.eta.ui.components.coui.CouiScaffoldPage
import io.github.mangi.eta.ui.components.currentGlassOverlayBackdrop
import io.github.mangi.eta.ui.model.AgentSkillsAction
import io.github.mangi.eta.ui.model.AgentSkillsUiState
import io.github.mangi.eta.ui.model.SkillItemUi
import io.github.mangi.eta.ui.model.canDeleteUserSkill
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.glass.GlassDialog
import top.yukonga.miuix.kmp.glass.GlassDialogButton
import top.yukonga.miuix.kmp.glass.GlassDialogButtonRole
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun AgentSkillsScreen(
    state: AgentSkillsUiState,
    onAction: (AgentSkillsAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    var deleteTarget by remember { mutableStateOf<SkillItemUi?>(null) }
    var retainedDeleteTarget by remember { mutableStateOf<SkillItemUi?>(null) }
    if (deleteTarget != null) retainedDeleteTarget = deleteTarget
    var retainedReplacement by remember { mutableStateOf(state.replacement) }
    if (state.replacement != null) retainedReplacement = state.replacement
    var retainedNotice by remember { mutableStateOf(state.notice) }
    if (state.notice != null) retainedNotice = state.notice
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

    val installed = state.skills.filter { it.installed }
    val builtinInstalled = installed.filter { it.source == "builtin" }
    val userInstalled = installed.filter { it.canDeleteUserSkill }
    val removed = state.skills.filter { !it.installed }

    when (LocalUiStyle.current) {
        AppearanceUiStyle.COUI -> {
            CouiScaffoldPage(
                title = stringResource(R.string.route_skills),
                onBack = { onAction(AgentSkillsAction.NavigateBack) },
                modifier = modifier,
                overlay = {
                    val activeReplacement = state.replacement ?: retainedReplacement
                    CouiConfirmDialog(
                        visible = state.replacement != null,
                        title = stringResource(R.string.ui_replace_user_skills_250e98),
                        message = activeReplacement?.let { replacement ->
                            stringResource(R.string.skills_replace_summary, replacement.name, replacement.id)
                        },
                        confirmText = stringResource(R.string.skills_replace),
                        confirmEnabled = !operationPending && activeReplacement != null,
                        onConfirm = { onAction(AgentSkillsAction.ConfirmZipReplacement) },
                        onDismissRequest = { onAction(AgentSkillsAction.CancelZipReplacement) },
                        onDismissFinished = { if (state.replacement == null) retainedReplacement = null },
                    )

                    val activeDelete = deleteTarget ?: retainedDeleteTarget
                    CouiConfirmDialog(
                        visible = deleteTarget != null,
                        title = stringResource(R.string.ui_delete_user_skills_f319a9),
                        message = activeDelete?.let { skill ->
                            stringResource(R.string.skills_delete_summary, skill.name)
                        },
                        confirmText = stringResource(R.string.ui_delete_3755f5),
                        confirmEnabled = !operationPending && activeDelete != null,
                        destructive = true,
                        onConfirm = {
                            val target = activeDelete
                            deleteTarget = null
                            if (target != null) onAction(AgentSkillsAction.DeleteSkill(target.id))
                        },
                        onDismissRequest = { deleteTarget = null },
                        onDismissFinished = { if (deleteTarget == null) retainedDeleteTarget = null },
                    )

                    val activeNotice = state.notice ?: retainedNotice
                    CouiNoticeDialog(
                        visible = state.notice != null,
                        title = activeNotice?.title,
                        message = activeNotice?.message,
                        confirmText = stringResource(R.string.ui_knew_cb63c6),
                        onDismissRequest = { onAction(AgentSkillsAction.DismissNotice) },
                        onDismissFinished = { if (state.notice == null) retainedNotice = null },
                    )
                },
            ) {
                CouiPreferenceGroup(title = stringResource(R.string.ui_install_087db6)) {
                    item {
                        CouiPreference(
                            title = if (state.isImporting) stringResource(R.string.skills_checking_package) else stringResource(R.string.skills_import_zip),
                            summary = if (state.isImporting) {
                                stringResource(R.string.skills_installing_package)
                            } else {
                                stringResource(R.string.skills_choose_package)
                            },
                            trailing = if (state.isImporting) {
                                { InfiniteProgressIndicator(size = 20.dp) }
                            } else {
                                null
                            },
                            enabled = !operationPending,
                            onClick = openZipPicker,
                        )
                    }
                }

                if (builtinInstalled.isNotEmpty()) {
                    CouiPreferenceGroup(title = stringResource(R.string.ui_built_in_skills_1ceedf)) {
                        builtinInstalled.forEach { skill ->
                            item(key = skill.id) {
                                SkillSwitchRow(
                                    skill = skill,
                                    enabled = !operationPending,
                                    onToggle = { enabled ->
                                        onAction(AgentSkillsAction.ToggleSkill(skill.id, enabled))
                                    },
                                )
                            }
                        }
                    }
                }

                if (userInstalled.isNotEmpty()) {
                    CouiPreferenceGroup(title = stringResource(R.string.ui_user_skills_748e7f)) {
                        userInstalled.forEach { skill ->
                            item(key = skill.id) {
                                SkillSwitchRow(
                                    skill = skill,
                                    enabled = !operationPending,
                                    onToggle = { enabled ->
                                        onAction(AgentSkillsAction.ToggleSkill(skill.id, enabled))
                                    },
                                    onDelete = { deleteTarget = skill },
                                )
                            }
                        }
                    }
                }

                if (removed.isNotEmpty()) {
                    CouiPreferenceGroup(title = stringResource(R.string.ui_removed_4e5c49)) {
                        removed.forEach { skill ->
                            item(key = skill.id) {
                                CouiPreference(
                                    title = skill.name,
                                    summary = stringResource(R.string.ui_click_to_reinstall_dc60de),
                                    enabled = !operationPending,
                                    onClick = {
                                        onAction(AgentSkillsAction.ReinstallBuiltin(skill.id))
                                    },
                                )
                            }
                        }
                    }
                }

                if (state.skills.isEmpty() && state.isLoading) {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        InfiniteProgressIndicator(size = 24.dp)
                    }
                }
                if (state.skills.isEmpty() && !state.isLoading) {
                    ListEmptyState(
                        title = stringResource(R.string.ui_no_skills_installed_yet_4e960f),
                        summary = stringResource(R.string.skills_choose_package),
                        icon = Icons.Rounded.FolderZip,
                        action = {
                            CouiButton(
                                text = stringResource(R.string.skills_import_zip),
                                enabled = !operationPending,
                                role = CouiButtonRole.Primary,
                                onClick = openZipPicker,
                            )
                        },
                    )
                }

                CouiPageBottomSpacer()
            }
        }

        AppearanceUiStyle.MIUIX -> {
            MiuixScaffoldPage(
                title = stringResource(R.string.route_skills),
                onBack = { onAction(AgentSkillsAction.NavigateBack) },
                modifier = modifier,
                overlay = {
                    val overlayBackdrop = currentGlassOverlayBackdrop()
                    val activeReplacement = state.replacement ?: retainedReplacement
                    GlassDialog(
                        show = state.replacement != null,
                        backdrop = overlayBackdrop,
                        title = stringResource(R.string.ui_replace_user_skills_250e98),
                        summary = activeReplacement?.let { replacement ->
                            stringResource(R.string.skills_replace_summary, replacement.name, replacement.id)
                        },
                        onDismissRequest = { onAction(AgentSkillsAction.CancelZipReplacement) },
                        onDismissFinished = { if (state.replacement == null) retainedReplacement = null },
                    ) {
                        MiuixDialogActions(
                            confirmText = stringResource(R.string.skills_replace),
                            confirmEnabled = !operationPending && activeReplacement != null,
                            onCancel = { onAction(AgentSkillsAction.CancelZipReplacement) },
                            onConfirm = { onAction(AgentSkillsAction.ConfirmZipReplacement) },
                        )
                    }

                    val activeDeleteSkill = deleteTarget ?: retainedDeleteTarget
                    GlassDialog(
                        show = deleteTarget != null,
                        backdrop = overlayBackdrop,
                        title = stringResource(R.string.ui_delete_user_skills_f319a9),
                        summary = activeDeleteSkill?.let { skill ->
                            stringResource(R.string.skills_delete_summary, skill.name)
                        },
                        onDismissRequest = { deleteTarget = null },
                        onDismissFinished = { if (deleteTarget == null) retainedDeleteTarget = null },
                    ) {
                        MiuixDialogActions(
                            confirmText = stringResource(R.string.ui_delete_3755f5),
                            destructive = true,
                            confirmEnabled = !operationPending && activeDeleteSkill != null,
                            onCancel = { deleteTarget = null },
                            onConfirm = {
                                val target = activeDeleteSkill
                                deleteTarget = null
                                if (target != null) onAction(AgentSkillsAction.DeleteSkill(target.id))
                            },
                        )
                    }

                    val activeNotice = state.notice ?: retainedNotice
                    GlassDialog(
                        show = state.notice != null,
                        backdrop = overlayBackdrop,
                        title = activeNotice?.title,
                        summary = activeNotice?.message,
                        onDismissRequest = { onAction(AgentSkillsAction.DismissNotice) },
                        onDismissFinished = { if (state.notice == null) retainedNotice = null },
                    ) {
                        GlassDialogButton(
                            onClick = { onAction(AgentSkillsAction.DismissNotice) },
                            role = if (activeNotice?.isError == true) {
                                GlassDialogButtonRole.Normal
                            } else {
                                GlassDialogButtonRole.Primary
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = stringResource(R.string.ui_knew_cb63c6),
                                style = MiuixTheme.textStyles.button,
                            )
                        }
                    }
                },
            ) {
                item(key = "zip-import") {
                    SmallTitle(stringResource(R.string.ui_install_087db6))
                    Card(
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 12.dp),
                    ) {
                        ArrowPreference(
                            title = if (state.isImporting) stringResource(R.string.skills_checking_package) else stringResource(R.string.skills_import_zip),
                            summary = if (state.isImporting) {
                                stringResource(R.string.skills_installing_package)
                            } else {
                                stringResource(R.string.skills_choose_package)
                            },
                            endActions = {
                                if (state.isImporting) {
                                    InfiniteProgressIndicator(size = 20.dp)
                                }
                            },
                            enabled = !operationPending,
                            onClick = openZipPicker,
                        )
                    }
                }

                if (builtinInstalled.isNotEmpty()) {
                    item(key = "builtin") {
                        SmallTitle(stringResource(R.string.ui_built_in_skills_1ceedf))
                        Card(
                            modifier = Modifier
                                .padding(horizontal = 12.dp)
                                .padding(bottom = 12.dp),
                        ) {
                            builtinInstalled.forEach { skill ->
                                SkillSwitchRow(
                                    skill = skill,
                                    enabled = !operationPending,
                                    onToggle = { enabled ->
                                        onAction(AgentSkillsAction.ToggleSkill(skill.id, enabled))
                                    },
                                )
                            }
                        }
                    }
                }

                if (userInstalled.isNotEmpty()) {
                    item(key = "user") {
                        SmallTitle(stringResource(R.string.ui_user_skills_748e7f))
                        Card(
                            modifier = Modifier
                                .padding(horizontal = 12.dp)
                                .padding(bottom = 12.dp),
                        ) {
                            userInstalled.forEach { skill ->
                                SkillSwitchRow(
                                    skill = skill,
                                    enabled = !operationPending,
                                    onToggle = { enabled ->
                                        onAction(AgentSkillsAction.ToggleSkill(skill.id, enabled))
                                    },
                                    onDelete = { deleteTarget = skill },
                                )
                            }
                        }
                    }
                }

                if (removed.isNotEmpty()) {
                    item(key = "removed") {
                        SmallTitle(stringResource(R.string.ui_removed_4e5c49))
                        Card(
                            modifier = Modifier
                                .padding(horizontal = 12.dp)
                                .padding(bottom = 12.dp),
                        ) {
                            removed.forEach { skill ->
                                BasicComponent(
                                    title = skill.name,
                                    summary = stringResource(R.string.ui_click_to_reinstall_dc60de),
                                    enabled = !operationPending,
                                    onClick = {
                                        onAction(AgentSkillsAction.ReinstallBuiltin(skill.id))
                                    },
                                )
                            }
                        }
                    }
                }

                if (state.skills.isEmpty() && state.isLoading) {
                    item(key = "loading") {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            InfiniteProgressIndicator(size = 24.dp)
                        }
                    }
                }
                if (state.skills.isEmpty() && !state.isLoading) {
                    item(key = "empty") {
                        ListEmptyState(
                            title = stringResource(R.string.ui_no_skills_installed_yet_4e960f),
                            summary = stringResource(R.string.skills_choose_package),
                            icon = Icons.Rounded.FolderZip,
                            action = {
                                TextButton(
                                    text = stringResource(R.string.skills_import_zip),
                                    enabled = !operationPending,
                                    onClick = openZipPicker,
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}
