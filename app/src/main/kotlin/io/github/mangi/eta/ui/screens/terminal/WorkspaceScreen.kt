package io.github.mangi.eta.ui.screens.terminal

import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.itos.coui.dialog.CouiDialogDefaults
import com.itos.coui.layout.CouiPageDefaults
import com.itos.coui.layout.Divider
import com.itos.coui.layout.Group
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.ui.app.LocalUiStyle
import io.github.mangi.eta.ui.app.WorkspaceEntry
import io.github.mangi.eta.ui.app.WorkspaceFileStore
import io.github.mangi.eta.ui.components.EtaPreferenceGroupItem
import io.github.mangi.eta.ui.components.ListEmptyState
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.components.coui.CouiLazyScaffoldPage
import io.github.mangi.eta.ui.components.coui.CouiPreference
import io.github.mangi.eta.ui.components.coui.CouiSectionTitle
import io.github.mangi.eta.ui.components.coui.CouiSettingsIcon
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun WorkspaceScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val store = remember(context.applicationContext) { WorkspaceFileStore(context) }
    val scope = rememberCoroutineScope()
    var path by rememberSaveable { mutableStateOf("") }
    var pendingExport by rememberSaveable { mutableStateOf<String?>(null) }
    var entries by remember { mutableStateOf<List<WorkspaceEntry>?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<WorkspaceMessage?>(null) }
    var publicAccess by remember { mutableStateOf(Environment.isExternalStorageManager()) }
    val accessLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        publicAccess = Environment.isExternalStorageManager()
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) scope.launch {
            busy = true
            try {
                var succeeded = 0
                uris.forEach { if (store.importFile(it)) succeeded++ }
                message = if (succeeded == uris.size) {
                    WorkspaceMessage(resources.getString(R.string.capability_workspace_imported), isError = false)
                } else {
                    WorkspaceMessage(
                        resources.getQuantityString(R.plurals.capability_workspace_partial_import, succeeded, succeeded),
                        isError = true,
                    )
                }
                if (succeeded > 0) path = "imports"
                revision++
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                message = WorkspaceMessage(resources.getString(R.string.capability_workspace_failed), isError = true)
            } finally {
                busy = false
            }
        }
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val source = pendingExport
        pendingExport = null
        if (uri != null && source != null) scope.launch {
            busy = true
            try {
                store.exportFile(source, uri)
                message = WorkspaceMessage(resources.getString(R.string.capability_workspace_exported), isError = false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                message = WorkspaceMessage(resources.getString(R.string.capability_workspace_failed), isError = true)
            } finally {
                busy = false
            }
        }
    }
    LaunchedEffect(path) { entries = null }
    LaunchedEffect(path, revision) {
        try {
            entries = store.list(path)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            entries = emptyList()
            message = WorkspaceMessage(resources.getString(R.string.capability_workspace_failed), isError = true)
        }
    }
    val uiStyle = LocalUiStyle.current
    val onOpenPublicAccess: () -> Unit = {
        try {
            accessLauncher.launch(
                Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:${context.packageName}"),
                ),
            )
        } catch (_: android.content.ActivityNotFoundException) {
            message = WorkspaceMessage(resources.getString(R.string.capability_workspace_failed), isError = true)
        }
    }

    val listContent: LazyListScope.() -> Unit = {
        item(key = "workspace-info") {
            if (uiStyle == AppearanceUiStyle.COUI) {
                Group {
                    CouiPreference(
                        title = stringResource(R.string.capability_workspace_private),
                        summary = stringResource(R.string.capability_workspace_private_summary),
                        showChevron = false,
                    )
                }
            } else {
                Card(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(top = 8.dp, bottom = 12.dp),
                ) {
                    BasicComponent(
                        title = stringResource(R.string.capability_workspace_private),
                        summary = stringResource(R.string.capability_workspace_private_summary),
                    )
                }
            }
        }
        item(key = "actions") {
            val publicSummary = if (publicAccess) {
                stringResource(R.string.capability_workspace_public_granted)
            } else {
                stringResource(R.string.capability_workspace_public_summary)
            }
            if (uiStyle == AppearanceUiStyle.COUI) {
                Group {
                    CouiPreference(
                        title = stringResource(R.string.capability_workspace_import),
                        enabled = !busy,
                        trailing = if (busy) {
                            { InfiniteProgressIndicator(size = 20.dp) }
                        } else {
                            null
                        },
                        onClick = { importLauncher.launch(arrayOf("*/*")) },
                    )
                    Divider()
                    CouiPreference(
                        title = stringResource(R.string.capability_workspace_public),
                        summary = publicSummary,
                        onClick = onOpenPublicAccess,
                    )
                }
            } else {
                Card(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp),
                ) {
                    ArrowPreference(
                        title = stringResource(R.string.capability_workspace_import),
                        enabled = !busy,
                        endActions = {
                            if (busy) {
                                InfiniteProgressIndicator(size = 20.dp)
                            }
                        },
                        onClick = { importLauncher.launch(arrayOf("*/*")) },
                    )
                    ArrowPreference(
                        title = stringResource(R.string.capability_workspace_public),
                        summary = publicSummary,
                        onClick = onOpenPublicAccess,
                    )
                }
            }
        }
        message?.let { current ->
            item(key = "message") {
                if (uiStyle == AppearanceUiStyle.COUI) {
                    val dialogColors = CouiDialogDefaults.themeColors()
                    Group(modifier = Modifier.animateItem()) {
                        CouiPreference(
                            title = current.text,
                            titleColor = if (current.isError) dialogColors.destructive else Color.Unspecified,
                            showChevron = false,
                            trailing = {
                                IconButton(onClick = { message = null }) {
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
                } else {
                    Card(
                        modifier = Modifier
                            .animateItem()
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 12.dp),
                    ) {
                        BasicComponent(
                            title = current.text,
                            titleColor = BasicComponentDefaults.titleColor(
                                color = if (current.isError) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.onBackground,
                            ),
                            endActions = {
                                IconButton(onClick = { message = null }) {
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
        }
        item(key = "path") {
            val sectionText = if (path.isBlank()) stringResource(R.string.capability_workspace_files) else path
            if (uiStyle == AppearanceUiStyle.COUI) {
                CouiSectionTitle(text = sectionText)
            } else {
                SmallTitle(sectionText)
            }
        }
        val currentEntries = entries
        val hasParent = path.isNotBlank()
        if (hasParent) {
            item(key = "parent") {
                EtaPreferenceGroupItem(
                    isFirst = true,
                    isLast = currentEntries.isNullOrEmpty(),
                    hasLeadingIcon = true,
                ) {
                    if (uiStyle == AppearanceUiStyle.COUI) {
                        val colors = CouiPageDefaults.themeColors()
                        CouiPreference(
                            title = stringResource(R.string.capability_workspace_parent),
                            icon = { CouiSettingsIcon(Icons.Rounded.FolderOpen, colors.accent) },
                            onClick = { path = path.substringBeforeLast('/', "") },
                        )
                    } else {
                        ArrowPreference(
                            title = stringResource(R.string.capability_workspace_parent),
                            startAction = {
                                Icon(
                                    imageVector = Icons.Rounded.FolderOpen,
                                    contentDescription = null,
                                    modifier = Modifier.padding(end = 8.dp).size(24.dp),
                                    tint = MiuixTheme.colorScheme.primary,
                                )
                            },
                            onClick = { path = path.substringBeforeLast('/', "") },
                        )
                    }
                }
            }
        }
        when {
            currentEntries == null -> item(key = "loading") {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    InfiniteProgressIndicator(size = 24.dp)
                }
            }
            currentEntries.isEmpty() -> item(key = "empty") {
                ListEmptyState(
                    title = stringResource(R.string.capability_workspace_empty),
                    summary = stringResource(R.string.capability_workspace_empty_summary),
                    icon = Icons.Rounded.FolderOpen,
                )
            }
            else -> itemsIndexed(currentEntries, key = { _, entry -> entry.path }) { index, entry ->
                val summary = if (entry.directory) stringResource(R.string.capability_workspace_directory)
                else stringResource(
                    R.string.capability_workspace_file_export,
                    Formatter.formatShortFileSize(context, entry.size),
                )
                val icon = if (entry.directory) Icons.Rounded.Folder else Icons.AutoMirrored.Rounded.InsertDriveFile
                val onEntryClick: () -> Unit = {
                    if (entry.directory) {
                        path = entry.path
                    } else {
                        pendingExport = entry.path
                        exportLauncher.launch(entry.name)
                    }
                }
                EtaPreferenceGroupItem(
                    isFirst = index == 0 && !hasParent,
                    isLast = index == currentEntries.lastIndex,
                    hasLeadingIcon = true,
                ) {
                    if (uiStyle == AppearanceUiStyle.COUI) {
                        val colors = CouiPageDefaults.themeColors()
                        CouiPreference(
                            title = entry.name,
                            summary = summary,
                            icon = {
                                CouiSettingsIcon(
                                    icon = icon,
                                    tint = if (entry.directory) colors.accent else colors.summary,
                                )
                            },
                            enabled = !busy,
                            onClick = onEntryClick,
                        )
                    } else {
                        ArrowPreference(
                            title = entry.name,
                            summary = summary,
                            startAction = {
                                Icon(
                                    imageVector = icon,
                                    contentDescription = null,
                                    modifier = Modifier.padding(end = 8.dp).size(24.dp),
                                    tint = if (entry.directory) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantActions,
                                )
                            },
                            enabled = !busy,
                            onClick = onEntryClick,
                        )
                    }
                }
            }
        }
    }

    when (uiStyle) {
        AppearanceUiStyle.COUI -> {
            CouiLazyScaffoldPage(
                title = stringResource(R.string.capability_workspace),
                onBack = onBack,
                content = listContent,
            )
        }
        AppearanceUiStyle.MIUIX -> {
            MiuixScaffoldPage(
                title = stringResource(R.string.capability_workspace),
                onBack = onBack,
                content = listContent,
            )
        }
    }
}

private data class WorkspaceMessage(val text: String, val isError: Boolean)
