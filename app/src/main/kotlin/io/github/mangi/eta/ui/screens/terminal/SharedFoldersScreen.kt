package io.github.mangi.eta.ui.screens.terminal

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.terminal.linux.AlpineEnvironmentPaths
import io.github.mangi.eta.agent.terminal.LinuxDistribution
import io.github.mangi.eta.agent.terminal.linux.LinuxEnvironmentPaths
import io.github.mangi.eta.agent.terminal.linux.SharedFolderMount
import io.github.mangi.eta.agent.terminal.linux.SharedFolderMounts
import io.github.mangi.eta.agent.terminal.ShellProcessSupervisor
import io.github.mangi.eta.agent.terminal.TerminalEnvironment
import io.github.mangi.eta.agent.terminal.TerminalRuntime
import io.github.mangi.eta.agent.terminal.runOneShotShell
import io.github.mangi.eta.agent.terminal.shellQuote
import io.github.mangi.eta.ui.components.DialogActions
import io.github.mangi.eta.ui.components.EtaMonospace
import io.github.mangi.eta.ui.components.ScaffoldPage
import io.github.mangi.eta.ui.components.currentGlassOverlayBackdrop
import io.github.mangi.eta.ui.components.rememberDraftTextFieldState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.glass.GlassDialog
import top.yukonga.miuix.kmp.glass.GlassTextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun SharedFoldersScreen(
    context: Context,
    onBack: () -> Unit,
) {
    val coroutineScope = rememberCoroutineScope()
    val shellSupervisor = remember { ShellProcessSupervisor() }
    DisposableEffect(Unit) {
        onDispose { shellSupervisor.beginClosing() }
    }
    val rootfsPaths = remember(context.applicationContext) {
        listOf(
            AlpineEnvironmentPaths.rootfsDir(context.applicationContext).absolutePath,
            LinuxEnvironmentPaths.rootfsDir(
                context.applicationContext,
                LinuxDistribution.DEBIAN,
            ).absolutePath,
        )
    }

    var mounts by remember { mutableStateOf(SharedFolderMounts.current()) }
    var sourceExists by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    var showPicker by remember { mutableStateOf(false) }
    var pickerSession by remember { mutableIntStateOf(0) }
    var removeTarget by remember { mutableStateOf<SharedFolderMount?>(null) }
    var retainedRemoveTarget by remember { mutableStateOf<SharedFolderMount?>(null) }
    if (removeTarget != null) retainedRemoveTarget = removeTarget
    var notice by remember { mutableStateOf<String?>(null) }
    val publicAccessLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (Environment.isExternalStorageManager()) {
            pickerSession += 1
            showPicker = true
        } else {
            notice = context.getString(R.string.capability_workspace_public_denied)
        }
    }

    fun refreshSources(list: List<SharedFolderMount>) {
        if (list.isEmpty()) {
            sourceExists = emptyMap()
            return
        }
        coroutineScope.launch {
            sourceExists = withContext(Dispatchers.IO) { probeSources(shellSupervisor, list) }
        }
    }

    LaunchedEffect(Unit) { refreshSources(mounts) }

    val onAddMountClick: () -> Unit = {
        if (TerminalRuntime.rootAvailable || Environment.isExternalStorageManager()) {
            pickerSession += 1
            showPicker = true
        } else {
            try {
                publicAccessLauncher.launch(
                    Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:${context.packageName}"),
                    ),
                )
            } catch (_: android.content.ActivityNotFoundException) {
                notice = context.getString(R.string.capability_workspace_public_denied)
            }
        }
    }
    val onConfirmPicker: (String, String) -> Unit = { source, name ->
        val updated = mounts + SharedFolderMount(name = name, sourcePath = source)
        if (SharedFolderMounts.save(updated)) {
            mounts = updated
            refreshSources(updated)
            showPicker = false
        } else {
            notice = context.getString(R.string.shared_folders_error_save)
        }
    }
    val activeRemoveTarget = removeTarget ?: retainedRemoveTarget
    val onConfirmRemove: (SharedFolderMount) -> Unit = { target ->
        val updated = mounts.filterNot { it.name == target.name }
        if (SharedFolderMounts.save(updated)) {
            mounts = updated
            removeTarget = null
            refreshSources(updated)
            coroutineScope.launch(Dispatchers.IO) {
                runOneShotShell(
                    processSupervisor = shellSupervisor,
                    identity = TerminalRuntime.defaultIdentity(TerminalEnvironment.ANDROID),
                    command = "rmdir " +
                        shellQuote("${SharedFolderMounts.ANDROID_MOUNTS_ROOT}/${target.name}") +
                        " 2>/dev/null",
                    timeoutSeconds = 10,
                )
            }
        } else {
            notice = context.getString(R.string.shared_folders_error_save)
            removeTarget = null
        }
    }

    ScaffoldPage(
        title = stringResource(R.string.shared_folders_title),
        onBack = onBack,
        overlay = {
            SharedFolderPickerDialog(
                show = showPicker,
                session = pickerSession,
                context = context,
                supervisor = shellSupervisor,
                existing = mounts,
                extraForbiddenRoots = rootfsPaths,
                onDismiss = { showPicker = false },
                onConfirm = onConfirmPicker,
            )
            GlassDialog(
                show = removeTarget != null,
                backdrop = currentGlassOverlayBackdrop(),
                title = stringResource(R.string.shared_folders_remove_title),
                summary = activeRemoveTarget?.let { target ->
                    stringResource(R.string.shared_folders_remove_message, target.name, target.sourcePath)
                },
                onDismissRequest = { removeTarget = null },
                onDismissFinished = { if (removeTarget == null) retainedRemoveTarget = null },
            ) {
                DialogActions(
                    confirmText = stringResource(R.string.action_delete),
                    destructive = true,
                    confirmEnabled = activeRemoveTarget != null,
                    onCancel = { removeTarget = null },
                    onConfirm = { activeRemoveTarget?.let(onConfirmRemove) },
                )
            }
        },
    ) {
        item(key = "mounts-card") {
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(top = 8.dp, bottom = 12.dp),
            ) {
                if (mounts.isEmpty()) {
                    BasicComponent(
                        title = stringResource(R.string.shared_folders_empty),
                        summary = stringResource(R.string.shared_folders_entry_summary),
                    )
                }
                mounts.forEach { mount ->
                    val missing = sourceExists[mount.sourcePath] == false
                    BasicComponent(
                        title = mount.name,
                        summary = buildString {
                            append(mount.sourcePath)
                            append("\n")
                            append(
                                context.getString(
                                    R.string.shared_folders_mount_point,
                                    "${SharedFolderMounts.LINUX_MOUNTS_ROOT}/${mount.name}",
                                ),
                            )
                            if (missing) {
                                append(" · ")
                                append(context.getString(R.string.shared_folders_source_missing))
                            }
                        },
                        summaryColor = BasicComponentDefaults.summaryColor(
                            color = if (missing) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        ),
                        endActions = {
                            IconButton(onClick = { removeTarget = mount }) {
                                Icon(
                                    imageVector = Icons.Rounded.DeleteOutline,
                                    contentDescription = stringResource(R.string.action_delete),
                                    modifier = Modifier.size(20.dp),
                                    tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
                                )
                            }
                        },
                    )
                }
                BasicComponent(
                    title = stringResource(R.string.shared_folders_add),
                    titleColor = BasicComponentDefaults.titleColor(color = MiuixTheme.colorScheme.primary),
                    startAction = {
                        Icon(
                            imageVector = Icons.Rounded.Add,
                            contentDescription = null,
                            tint = MiuixTheme.colorScheme.primary,
                            modifier = Modifier.padding(end = 8.dp),
                        )
                    },
                    onClick = onAddMountClick,
                )
            }
        }
        notice?.let { message ->
            item(key = "notice-card") {
                Card(
                    modifier = Modifier
                        .animateItem()
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp),
                ) {
                    BasicComponent(
                        title = message,
                        titleColor = BasicComponentDefaults.titleColor(color = MiuixTheme.colorScheme.error),
                        endActions = {
                            IconButton(onClick = { notice = null }) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = stringResource(R.string.action_close),
                                    modifier = Modifier.size(18.dp),
                                    tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
                                )
                            }
                        },
                    )
                }
            }
        }
        item(key = "footer-note") {
            Text(
                text = stringResource(R.string.shared_folders_footer),
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.animateItem().padding(horizontal = 28.dp),
            )
        }
    }
}

@Composable
private fun SharedFolderPickerDialog(
    show: Boolean,
    session: Int,
    context: Context,
    supervisor: ShellProcessSupervisor,
    existing: List<SharedFolderMount>,
    extraForbiddenRoots: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (sourcePath: String, name: String) -> Unit,
) {
    GlassDialog(
        show = show,
        backdrop = currentGlassOverlayBackdrop(),
        title = stringResource(R.string.shared_folders_picker_title),
        onDismissRequest = onDismiss,
    ) {
        key(session) {
            SharedFolderPickerBody(
                show = show,
                context = context,
                supervisor = supervisor,
                existing = existing,
                extraForbiddenRoots = extraForbiddenRoots,
                onDismiss = onDismiss,
                onConfirm = onConfirm,
            )
        }
    }
}

@Composable
private fun SharedFolderPickerBody(
    show: Boolean,
    context: Context,
    supervisor: ShellProcessSupervisor,
    existing: List<SharedFolderMount>,
    extraForbiddenRoots: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (sourcePath: String, name: String) -> Unit,
) {
    var path by remember { mutableStateOf("/sdcard") }
    val pathInputState = remember { TextFieldState("/sdcard") }
    var entries by remember { mutableStateOf<List<String>?>(null) }
    var browseError by remember { mutableStateOf<String?>(null) }
    var nameInput by remember { mutableStateOf("sdcard") }
    var nameTouched by remember { mutableStateOf(false) }
    var formError by remember { mutableStateOf<String?>(null) }

    val nameState = rememberDraftTextFieldState(nameInput) {
        nameInput = it
        nameTouched = true
        formError = null
    }

    fun navigateTo(target: String) {
        val normalized = SharedFolderMounts.normalizeSourcePath(target) ?: "/"
        path = normalized
        pathInputState.setTextAndPlaceCursorAtEnd(normalized)
        browseError = null
        formError = null
        if (!nameTouched) nameInput = SharedFolderMounts.defaultName(normalized)
    }

    LaunchedEffect(show, path) {
        if (!show) return@LaunchedEffect
        entries = null
        val result = withContext(Dispatchers.IO) {
            runOneShotShell(
                processSupervisor = supervisor,
                identity = TerminalRuntime.defaultIdentity(TerminalEnvironment.ANDROID),
                command = "cd ${shellQuote(path)} && find . -mindepth 1 -maxdepth 1 -type d",
                timeoutSeconds = 15,
            )
        }
        if (result.exitCode != 0) {
            browseError = context.getString(R.string.shared_folders_browse_unavailable)
            entries = emptyList()
        } else {
            browseError = null
            entries = result.output.decodeToString().lineSequence()
                .map { it.removePrefix("./") }
                .filter { it.isNotBlank() && !it.startsWith(".") }
                .sorted()
                .toList()
        }
    }

    val onValidateAndConfirm: () -> Unit = {
        val sourceError = SharedFolderMounts.validateSource(path, existing, extraForbiddenRoots)
        val errorText = when (sourceError) {
            SharedFolderMounts.SourceError.INVALID_PATH ->
                context.getString(R.string.shared_folders_error_path_invalid)
            SharedFolderMounts.SourceError.FORBIDDEN_ROOT ->
                context.getString(R.string.shared_folders_error_path_forbidden)
            SharedFolderMounts.SourceError.DUPLICATE ->
                context.getString(R.string.shared_folders_error_path_duplicate)
            null -> when (SharedFolderMounts.validateName(nameInput, existing)) {
                SharedFolderMounts.NameError.INVALID ->
                    context.getString(R.string.shared_folders_error_name_invalid)
                SharedFolderMounts.NameError.DUPLICATE ->
                    context.getString(R.string.shared_folders_error_name_duplicate)
                null -> if (existing.size >= SharedFolderMounts.MAX_MOUNTS) {
                    context.getString(
                        R.string.shared_folders_error_limit,
                        SharedFolderMounts.MAX_MOUNTS,
                    )
                } else {
                    null
                }
            }
        }
        if (errorText != null) {
            formError = errorText
        } else {
            onConfirm(path, nameInput.trim())
        }
    }

    Column {
        GlassTextField(
            state = pathInputState,
            lineLimits = TextFieldLineLimits.SingleLine,
            label = stringResource(R.string.shared_folders_path_label),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            onKeyboardAction = { navigateTo(pathInputState.text.toString()) },
            modifier = Modifier.fillMaxWidth(),
        )
        if (browseError != null) {
            Text(
                text = browseError.orEmpty(),
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 240.dp)
                .padding(top = 8.dp),
        ) {
            if (path != "/") {
                item(key = "..") {
                    PickerRow(
                        label = "../",
                        onClick = {
                            navigateTo(path.trimEnd('/').substringBeforeLast('/').ifBlank { "/" })
                        },
                    )
                }
            }
            when {
                entries == null -> item(key = "loading") {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        InfiniteProgressIndicator(size = 20.dp)
                    }
                }
                entries.orEmpty().isEmpty() && browseError == null -> item(key = "empty") {
                    Text(
                        text = stringResource(R.string.shared_folders_picker_no_subfolders),
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 12.dp),
                    )
                }
                else -> items(entries.orEmpty(), key = { it }) { entry ->
                    PickerRow(
                        label = entry,
                        onClick = { navigateTo(path.trimEnd('/') + "/" + entry) },
                    )
                }
            }
        }
        Text(
            text = stringResource(R.string.shared_folders_selected_source, path),
            style = MiuixTheme.textStyles.footnote1.copy(fontFamily = EtaMonospace),
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(top = 8.dp),
        )
        GlassTextField(
            lineLimits = TextFieldLineLimits.SingleLine,
            state = nameState,
            label = stringResource(R.string.shared_folders_name_label),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        )
        formError?.let { error ->
            Text(
                text = error,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        DialogActions(
            confirmText = stringResource(R.string.shared_folders_add),
            onCancel = onDismiss,
            onConfirm = onValidateAndConfirm,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

private fun probeSources(
    supervisor: ShellProcessSupervisor,
    mounts: List<SharedFolderMount>,
): Map<String, Boolean> {
    val script = mounts.joinToString("\n") { mount ->
        "if [ -d ${shellQuote(mount.sourcePath)} ]; then echo '${mount.name} 1'; else echo '${mount.name} 0'; fi"
    }
    val result = runOneShotShell(
        processSupervisor = supervisor,
        identity = TerminalRuntime.defaultIdentity(TerminalEnvironment.ANDROID),
        command = script,
        timeoutSeconds = 15,
    )
    if (result.exitCode != 0) return emptyMap()
    return result.output.decodeToString().lineSequence().mapNotNull { line ->
        val parts = line.trim().split(" ")
        if (parts.size == 2) parts[0] to (parts[1] == "1") else null
    }.toMap()
}

@Composable
private fun PickerRow(label: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 6.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.Folder,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MiuixTheme.colorScheme.primary,
        )
        Text(
            text = label,
            style = MiuixTheme.textStyles.body2.copy(fontFamily = EtaMonospace),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
