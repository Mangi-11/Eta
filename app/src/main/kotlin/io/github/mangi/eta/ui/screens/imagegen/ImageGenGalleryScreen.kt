package io.github.mangi.eta.ui.screens.imagegen

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.imagegen.GeneratedImageRecord
import io.github.mangi.eta.agent.imagegen.GeneratedImageStore
import io.github.mangi.eta.ui.components.DialogActions
import io.github.mangi.eta.ui.components.ListEmptyState
import io.github.mangi.eta.ui.components.LocalChatImagePreviewer
import io.github.mangi.eta.ui.components.ScaffoldPage
import io.github.mangi.eta.ui.components.TopBarActionButton
import io.github.mangi.eta.ui.components.currentGlassOverlayBackdrop
import io.github.mangi.eta.ui.components.chat.rememberChatImageBitmap
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.glass.GlassDialog
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.Delete
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

@Composable
internal fun ImageGenGalleryScreen(
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val previewer = LocalChatImagePreviewer.current
    var records by remember { mutableStateOf<List<GeneratedImageRecord>>(emptyList()) }
    var totalBytes by remember { mutableStateOf(0L) }
    var confirmClearAll by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<GeneratedImageRecord?>(null) }
    var retainedDelete by remember { mutableStateOf<GeneratedImageRecord?>(null) }
    if (pendingDelete != null) retainedDelete = pendingDelete
    val itemBounds = remember { mutableStateMapOf<Int, Rect>() }

    fun reload() {
        scope.launch {
            val (list, bytes) = withContext(Dispatchers.IO) {
                GeneratedImageStore.listRecords(context) to GeneratedImageStore.totalStorageBytes(context)
            }
            records = list
            totalBytes = bytes
        }
    }

    LaunchedEffect(Unit) { reload() }

    val imagePaths = remember(records) { records.map { it.filePath } }
    val openAt: (Int) -> Unit = { idx ->
        previewer?.open(
            images = imagePaths,
            initialIndex = idx,
            groupKey = "image_gen_gallery",
            sourceBoundsProvider = { page -> itemBounds[page] },
            sourceCornerRadius = 14.dp,
        )
    }

    val deleteSingle: (GeneratedImageRecord) -> Unit = { rec ->
        scope.launch {
            val ok = withContext(Dispatchers.IO) { GeneratedImageStore.deleteRecord(context, rec.filePath) }
            pendingDelete = null
            reload()
            val msg = resources.getString(
                if (ok) R.string.imagegen_gallery_deleted else R.string.imagegen_gallery_delete_failed,
            )
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }

    val clearAll: () -> Unit = {
        scope.launch {
            val count = withContext(Dispatchers.IO) { GeneratedImageStore.clearAll(context) }
            confirmClearAll = false
            reload()
            val msg = if (count > 0) {
                resources.getQuantityString(R.plurals.imagegen_gallery_cleared, count, count)
            } else {
                resources.getString(R.string.imagegen_gallery_nothing_to_clear)
            }
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }

    val rows = remember(records) { records.chunked(2) }

    ScaffoldPage(
        title = stringResource(R.string.image_gen_gallery_title),
        onBack = onBack,
        actions = {
            if (records.isNotEmpty()) {
                TopBarActionButton(
                    icon = MiuixIcons.Glass.Delete,
                    contentDescription = stringResource(R.string.imagegen_gallery_clear_all_desc),
                    onClick = { confirmClearAll = true },
                )
            }
        },
        overlay = {
            val backdrop = currentGlassOverlayBackdrop()
            GlassDialog(
                show = confirmClearAll,
                title = stringResource(R.string.imagegen_gallery_clear_title),
                summary = pluralStringResource(
                    R.plurals.imagegen_gallery_clear_message,
                    records.size,
                    records.size,
                    formatImageStorageSize(totalBytes),
                ),
                backdrop = backdrop,
                onDismissRequest = { confirmClearAll = false },
            ) {
                DialogActions(
                    cancelText = stringResource(R.string.action_cancel),
                    confirmText = stringResource(R.string.imagegen_action_clear),
                    destructive = true,
                    onCancel = { confirmClearAll = false },
                    onConfirm = clearAll,
                )
            }
            val activeDelete = pendingDelete ?: retainedDelete
            GlassDialog(
                show = pendingDelete != null,
                title = stringResource(R.string.imagegen_gallery_delete_title),
                summary = stringResource(R.string.imagegen_gallery_delete_message),
                backdrop = backdrop,
                onDismissRequest = { pendingDelete = null },
                onDismissFinished = { if (pendingDelete == null) retainedDelete = null },
            ) {
                DialogActions(
                    cancelText = stringResource(R.string.action_cancel),
                    confirmText = stringResource(R.string.action_delete),
                    destructive = true,
                    confirmEnabled = activeDelete != null,
                    onCancel = { pendingDelete = null },
                    onConfirm = { activeDelete?.let(deleteSingle) },
                )
            }
        },
    ) {
        if (records.isEmpty()) {
            item(key = "empty") {
                ListEmptyState(
                    icon = Icons.Rounded.PhotoLibrary,
                    title = stringResource(R.string.imagegen_gallery_empty_title),
                    summary = stringResource(R.string.imagegen_gallery_empty_summary),
                )
            }
        } else {
            item(key = "summary") {
                Text(
                    text = pluralStringResource(
                        R.plurals.imagegen_gallery_header_summary,
                        records.size,
                        records.size,
                        formatImageStorageSize(totalBytes),
                    ),
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            items(rows.size, key = { idx -> rows[idx].first().id }) { rowIdx ->
                val rowItems = rows[rowIdx]
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 5.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    rowItems.forEachIndexed { colIdx, item ->
                        val globalIdx = rowIdx * 2 + colIdx
                        GalleryImageTile(
                            record = item,
                            index = globalIdx,
                            modifier = Modifier.weight(1f),
                            onBoundsChanged = { itemBounds[globalIdx] = it },
                            onClick = { openAt(globalIdx) },
                            onDelete = { pendingDelete = item },
                        )
                    }
                    if (rowItems.size == 1) Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun GalleryImageTile(
    record: GeneratedImageRecord,
    index: Int,
    modifier: Modifier = Modifier,
    onBoundsChanged: (Rect) -> Unit,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    val previewer = LocalChatImagePreviewer.current
    val bitmap = rememberChatImageBitmap(record.filePath, maxDimensionPx = 560)
    val dateLabel = remember(record.createdAt) {
        SimpleDateFormat("MM/dd HH:mm", Locale.getDefault()).format(Date(record.createdAt))
    }
    val cardBg = MiuixTheme.colorScheme.surfaceContainerHigh
    val titleColor = MiuixTheme.colorScheme.onSurface
    val summaryColor = MiuixTheme.colorScheme.onSurfaceVariantSummary

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(cardBg),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .onGloballyPositioned { coords ->
                    if (coords.isAttached) onBoundsChanged(coords.boundsInWindow())
                }
                .graphicsLayer {
                    alpha = if (previewer?.isPresenting("image_gen_gallery", index) == true) 0f else 1f
                }
                .clickable(onClick = onClick),
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = record.prompt.ifBlank { stringResource(R.string.imagegen_generated_image_desc) },
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.45f))
                    .clickable(onClick = onDelete),
                contentAlignment = Alignment.Center,
            ) {
                val deleteImageDesc = stringResource(R.string.imagegen_gallery_delete_image_desc)
                Icon(
                    imageVector = Icons.Rounded.DeleteOutline,
                    contentDescription = deleteImageDesc,
                    tint = Color.White,
                    modifier = Modifier.size(15.dp),
                )
            }
        }
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = record.prompt.ifBlank { stringResource(R.string.imagegen_gallery_no_prompt) },
                fontSize = 12.sp,
                color = titleColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = listOfNotNull(
                    record.model.takeIf { it.isNotBlank() },
                    dateLabel,
                ).joinToString(" · "),
                fontSize = 10.sp,
                color = summaryColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
