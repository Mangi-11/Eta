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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.itos.coui.dialog.CouiDialogDefaults
import com.itos.coui.layout.CouiPageDefaults
import com.itos.coui.layout.Divider
import com.itos.coui.layout.Footer
import com.itos.coui.layout.Group
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.terminal.AlpineEnvironmentPaths
import io.github.mangi.eta.agent.terminal.LinuxDistribution
import io.github.mangi.eta.agent.terminal.LinuxEnvironmentPaths
import io.github.mangi.eta.agent.terminal.SharedFolderMount
import io.github.mangi.eta.agent.terminal.SharedFolderMounts
import io.github.mangi.eta.agent.terminal.ShellProcessSupervisor
import io.github.mangi.eta.agent.terminal.TerminalEnvironment
import io.github.mangi.eta.agent.terminal.TerminalRuntime
import io.github.mangi.eta.agent.terminal.runOneShotShell
import io.github.mangi.eta.agent.terminal.shellQuote
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.ui.app.LocalUiStyle
import io.github.mangi.eta.ui.components.EtaMonospace
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.components.coui.CouiConfirmDialog
import io.github.mangi.eta.ui.components.coui.CouiPageBottomSpacer
import io.github.mangi.eta.ui.components.coui.CouiPreference
import io.github.mangi.eta.ui.components.coui.CouiScaffoldPage
import io.github.mangi.eta.ui.components.coui.CouiTextField
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
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

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
    var removeTarget by remember { mutableStateOf<SharedFolderMount?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    val publicAccessLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (Environment.isExternalStorageManager()) showPicker = true
        else notice = context.getString(R.string.capability_workspace_public_denied)
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

    when (LocalUiStyle.current) {
        AppearanceUiStyle.COUI -> {
            val pageColors = CouiPageDefaults.themeColors()
            val dialogColors = CouiDialogDefaults.themeColors()
            CouiScaffoldPage(
                title = stringResource(R.string.shared_folders_title),
                onBack = onBack,
            ) {
                Group {
                    if (mounts.isEmpty()) {
                        CouiPreference(
                            title = stringResource(R.string.shared_folders_empty),
                            summary = stringResource(R.string.shared_folders_entry_summary),
                            showChevron = false,
                        )
                        Divider()
                    }
                    mounts.forEach { mount ->
                        val missing = sourceExists[mount.sourcePath] == false
                        CouiPreference(
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
                            summaryColor = if (missing) dialogColors.destructive else Color.Unspecified,
                            showChevron = false,
                            trailing = {
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
                        Divider()
                    }
                    CouiPreference(
                        title = stringResource(R.string.shared_folders_add),
                        titleColor = pageColors.accent,
                        showChevron = false,
                        onClick = onAddMountClick,
                    )
                }

                notice?.let { message ->
                    Group {
                        CouiPreference(
                            title = message,
                            titleColor = dialogColors.destructive,
                            showChevron = false,
                            trailing = {
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

                Footer(text = stringResource(R.string.shared_folders_footer))
                CouiPageBottomSpacer()
            }
        }

        AppearanceUiStyle.MIUIX -> {
            MiuixScaffoldPage(
                title = stringResource(R.string.shared_folders_title),
                onBack = onBack,
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
    }

    if (showPicker) {
        SharedFolderPickerDialog(
            context = context,
            supervisor = shellSupervisor,
            existing = mounts,
            extraForbiddenRoots = rootfsPaths,
            onDismiss = { showPicker = false },
            onConfirm = { source, name ->
                val updated = mounts + SharedFolderMount(name = name, sourcePath = source)
                if (SharedFolderMounts.save(updated)) {
                    mounts = updated
                    refreshSources(updated)
                    showPicker = false
                } else {
                    notice = context.getString(R.string.shared_folders_error_save)
                }
            },
        )
    }

    removeTarget?.let { target ->
        val message = stringResource(R.string.shared_folders_remove_message, target.name, target.sourcePath)
        val onConfirmRemove: () -> Unit = {
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
        when (LocalUiStyle.current) {
            AppearanceUiStyle.COUI -> {
                CouiConfirmDialog(
                    visible = true,
                    title = stringResource(R.string.shared_folders_remove_title),
                    message = message,
                    confirmText = stringResource(R.string.action_delete),
                    destructive = true,
                    onConfirm = onConfirmRemove,
                    onDismissRequest = { removeTarget = null },
                )
            }
            AppearanceUiStyle.MIUIX -> {
                WindowDialog(
                    show = true,
                    title = stringResource(R.string.shared_folders_remove_title),
                    summary = message,
                    onDismissRequest = { removeTarget = null },
                ) {
                    MiuixDialogActions(
                        confirmText = stringResource(R.string.action_delete),
                        destructive = true,
                        onCancel = { removeTarget = null },
                        onConfirm = onConfirmRemove,
                    )
                }
            }
        }
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
private fun SharedFolderPickerDialog(
    context: Context,
    supervisor: ShellProcessSupervisor,
    existing: List<SharedFolderMount>,
    extraForbiddenRoots: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (sourcePath: String, name: String) -> Unit,
) {
    var path by remember { mutableStateOf("/sdcard") }
    var pathInput by remember { mutableStateOf("/sdcard") }
    var entries by remember { mutableStateOf<List<String>?>(null) }
    var browseError by remember { mutableStateOf<String?>(null) }
    var nameInput by remember { mutableStateOf("sdcard") }
    var nameTouched by remember { mutableStateOf(false) }
    var formError by remember { mutableStateOf<String?>(null) }

    fun navigateTo(target: String) {
        val normalized = SharedFolderMounts.normalizeSourcePath(target) ?: "/"
        path = normalized
        pathInput = normalized
        browseError = null
        formError = null
        if (!nameTouched) nameInput = SharedFolderMounts.defaultName(normalized)
    }

    LaunchedEffect(path) {
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
    val uiStyle = LocalUiStyle.current
    val bodyContent: @Composable () -> Unit = {
        if (uiStyle == AppearanceUiStyle.COUI) {
            CouiTextField(
                value = pathInput,
                onValueChange = { pathInput = it },
                label = stringResource(R.string.shared_folders_path_label),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { navigateTo(pathInput) }),
            )
        } else {
            TextField(
                value = pathInput,
                onValueChange = { pathInput = it },
                label = stringResource(R.string.shared_folders_path_label),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { navigateTo(pathInput) }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
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
        if (uiStyle == AppearanceUiStyle.COUI) {
            CouiTextField(
                value = nameInput,
                onValueChange = {
                    nameInput = it
                    nameTouched = true
                    formError = null
                },
                label = stringResource(R.string.shared_folders_name_label),
                singleLine = true,
                modifier = Modifier.padding(top = 8.dp),
            )
        } else {
            TextField(
                value = nameInput,
                onValueChange = {
                    nameInput = it
                    nameTouched = true
                    formError = null
                },
                label = stringResource(R.string.shared_folders_name_label),
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            )
        }
        formError?.let { error ->
            Text(
                text = error,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }

    when (uiStyle) {
        AppearanceUiStyle.COUI -> {
            CouiConfirmDialog(
                visible = true,
                title = stringResource(R.string.shared_folders_picker_title),
                message = null,
                confirmText = stringResource(R.string.shared_folders_add),
                onConfirm = onValidateAndConfirm,
                onDismissRequest = onDismiss,
            ) {
                bodyContent()
            }
        }
        AppearanceUiStyle.MIUIX -> {
            WindowDialog(
                show = true,
                title = stringResource(R.string.shared_folders_picker_title),
                onDismissRequest = onDismiss,
            ) {
                Column {
                    bodyContent()
                    MiuixDialogActions(
                        confirmText = stringResource(R.string.shared_folders_add),
                        onCancel = onDismiss,
                        onConfirm = onValidateAndConfirm,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
        }
    }
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
