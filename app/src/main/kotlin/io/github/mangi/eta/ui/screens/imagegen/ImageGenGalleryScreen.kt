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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.itos.coui.layout.CouiPageDefaults
import com.itos.coui.theme.CouiTheme
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.imagegen.GeneratedImageRecord
import io.github.mangi.eta.agent.imagegen.GeneratedImageStore
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.ui.app.LocalUiStyle
import io.github.mangi.eta.ui.components.ListEmptyState
import io.github.mangi.eta.ui.components.LocalChatImagePreviewer
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.components.StatusError
import io.github.mangi.eta.ui.components.coui.CouiConfirmDialog
import io.github.mangi.eta.ui.components.coui.CouiIcon
import io.github.mangi.eta.ui.components.coui.CouiLazyScaffoldPage
import io.github.mangi.eta.ui.components.coui.CouiPageBottomSpacer
import io.github.mangi.eta.ui.components.currentGlassOverlayBackdrop
import io.github.mangi.eta.ui.components.rememberChatImageBitmap
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.glass.GlassDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

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

    val isCoui = LocalUiStyle.current == AppearanceUiStyle.COUI

    if (confirmClearAll) {
        val clearTitle = stringResource(R.string.imagegen_gallery_clear_title)
        val clearMessage = pluralStringResource(
            R.plurals.imagegen_gallery_clear_message,
            records.size,
            records.size,
            formatImageStorageSize(totalBytes),
        )
        val clearAction = stringResource(R.string.imagegen_action_clear)
        if (isCoui) {
            CouiConfirmDialog(
                visible = true,
                title = clearTitle,
                message = clearMessage,
                confirmText = clearAction,
                destructive = true,
                onDismissRequest = { confirmClearAll = false },
                onConfirm = clearAll,
            )
        } else {
            GlassDialog(
                show = true,
                title = clearTitle,
                summary = clearMessage,
                backdrop = currentGlassOverlayBackdrop(),
                onDismissRequest = { confirmClearAll = false },
            ) {
                MiuixDialogActions(
                    cancelText = stringResource(R.string.action_cancel),
                    confirmText = clearAction,
                    destructive = true,
                    onCancel = { confirmClearAll = false },
                    onConfirm = clearAll,
                )
            }
        }
    }

    pendingDelete?.let { target ->
        val deleteTitle = stringResource(R.string.imagegen_gallery_delete_title)
        val deleteMessage = stringResource(R.string.imagegen_gallery_delete_message)
        val deleteAction = stringResource(R.string.action_delete)
        if (isCoui) {
            CouiConfirmDialog(
                visible = true,
                title = deleteTitle,
                message = deleteMessage,
                confirmText = deleteAction,
                destructive = true,
                onDismissRequest = { pendingDelete = null },
                onConfirm = { deleteSingle(target) },
            )
        } else {
            GlassDialog(
                show = true,
                title = deleteTitle,
                summary = deleteMessage,
                backdrop = currentGlassOverlayBackdrop(),
                onDismissRequest = { pendingDelete = null },
            ) {
                MiuixDialogActions(
                    cancelText = stringResource(R.string.action_cancel),
                    confirmText = deleteAction,
                    destructive = true,
                    onCancel = { pendingDelete = null },
                    onConfirm = { deleteSingle(target) },
                )
            }
        }
    }

    val rows = remember(records) { records.chunked(2) }

    when (LocalUiStyle.current) {
        AppearanceUiStyle.COUI -> {
            val dark = CouiTheme.isDark
            val colors = CouiPageDefaults.themeColors(dark)
            // 使用 CouiLazyScaffoldPage 惰性回收离屏行，避免大量缩略图同时驻留内存。
            CouiLazyScaffoldPage(
                title = stringResource(R.string.image_gen_gallery_title),
                onBack = onBack,
                actions = {
                    if (records.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .clickable { confirmClearAll = true }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.imagegen_action_clear),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = StatusError,
                            )
                        }
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
                            color = colors.summary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                    items(rows.size, key = { idx -> rows[idx].first().id }) { rowIdx ->
                        val rowItems = rows[rowIdx]
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 5.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            rowItems.forEachIndexed { colIdx, item ->
                                val globalIdx = rowIdx * 2 + colIdx
                                GalleryImageTile(
                                    record = item,
                                    index = globalIdx,
                                    isCoui = true,
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
                item(key = "bottom_spacer") {
                    CouiPageBottomSpacer()
                }
            }
        }

        AppearanceUiStyle.MIUIX -> {
            MiuixScaffoldPage(
                title = stringResource(R.string.image_gen_gallery_title),
                onBack = onBack,
                actions = {
                    if (records.isNotEmpty()) {
                        IconButton(onClick = { confirmClearAll = true }) {
                            Icon(
                                imageVector = Icons.Rounded.DeleteOutline,
                                contentDescription = stringResource(R.string.imagegen_gallery_clear_all_desc),
                                tint = StatusError,
                            )
                        }
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
                                    isCoui = false,
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
    }
}

@Composable
private fun GalleryImageTile(
    record: GeneratedImageRecord,
    index: Int,
    isCoui: Boolean,
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
    val cardBg = if (isCoui) {
        if (CouiTheme.isDark) Color(0xFF1F2229) else Color(0xFFF3F5F9)
    } else {
        MiuixTheme.colorScheme.surfaceContainerHigh
    }
    val titleColor = if (isCoui) {
        CouiPageDefaults.themeColors(CouiTheme.isDark).title
    } else {
        MiuixTheme.colorScheme.onSurface
    }
    val summaryColor = if (isCoui) {
        CouiPageDefaults.themeColors(CouiTheme.isDark).summary
    } else {
        MiuixTheme.colorScheme.onSurfaceVariantSummary
    }

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
                if (isCoui) {
                    CouiIcon(
                        imageVector = Icons.Rounded.DeleteOutline,
                        contentDescription = deleteImageDesc,
                        tint = Color.White,
                        modifier = Modifier.size(15.dp),
                    )
                } else {
                    Icon(
                        imageVector = Icons.Rounded.DeleteOutline,
                        contentDescription = deleteImageDesc,
                        tint = Color.White,
                        modifier = Modifier.size(15.dp),
                    )
                }
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
