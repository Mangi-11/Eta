package io.github.mangi.eta.ui.components

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.BrokenImage
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Share
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.itos.coui.layout.CouiPageDefaults
import com.itos.coui.theme.CouiTheme
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.imagegen.GeneratedImageActivityPayload
import io.github.mangi.eta.agent.imagegen.GeneratedImageStore
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.ui.app.LocalUiStyle
import io.github.mangi.eta.ui.model.ToolActivityMessageUi
import io.github.mangi.eta.ui.model.ToolActivityStatusUi
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.squircle.squircleBorder
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 聊天流中的原生 AI 生图卡片（脱离工作过程折叠条，直接展示在对话正文）。
 *
 * - Running: 按比例自适应的呼吸加载占位卡片 + 实时计时与提示词预览；
 * - Success: 单图自适应比例 / 多图 2 列紧凑画廊 + 点击 120Hz 原位全屏预览 + 保存/分享/继续编辑；
 * - Failed: 清晰可读的错误卡片。
 */
@Composable
internal fun GeneratedImageMessageCard(
    message: ToolActivityMessageUi,
    assistantOverlay: Boolean = false,
    onEditImage: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val payload = remember(message.command) { GeneratedImageActivityPayload.decode(message.command) }
    val isCoui = LocalUiStyle.current == AppearanceUiStyle.COUI
    val horizontalPad = if (assistantOverlay) 14.dp else 20.dp

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPad, vertical = 6.dp),
    ) {
        when (message.status) {
            ToolActivityStatusUi.Running -> {
                GeneratingPlaceholderCard(
                    messageId = message.id,
                    payload = payload,
                    isCoui = isCoui,
                    assistantOverlay = assistantOverlay,
                )
            }

            ToolActivityStatusUi.Success -> {
                val images = payload?.images.orEmpty()
                if (images.isNotEmpty()) {
                    GeneratedImagesGalleryCard(
                        messageId = message.id,
                        payload = payload!!,
                        isCoui = isCoui,
                        assistantOverlay = assistantOverlay,
                        onEditImage = onEditImage,
                    )
                } else {
                    GeneratedImageStatusNotice(
                        isError = false,
                        title = message.resultSummary ?: stringResource(R.string.imagegen_card_completed),
                        detail = payload?.prompt,
                        isCoui = isCoui,
                    )
                }
            }

            ToolActivityStatusUi.Failed, ToolActivityStatusUi.Unknown -> {
                val failedTitle = stringResource(R.string.imagegen_card_failed)
                val detail = payload?.errorMessage
                    ?: message.outcomeLine()
                    ?: message.resultSummary
                    ?: failedTitle
                GeneratedImageStatusNotice(
                    isError = true,
                    title = failedTitle,
                    detail = detail,
                    isCoui = isCoui,
                )
            }
        }
    }
}

@Composable
private fun GeneratingPlaceholderCard(
    messageId: String,
    payload: GeneratedImageActivityPayload?,
    isCoui: Boolean,
    assistantOverlay: Boolean,
) {
    var elapsedSeconds by remember(messageId) { mutableIntStateOf(0) }
    LaunchedEffect(messageId) {
        while (true) {
            delay(1_000L)
            elapsedSeconds += 1
        }
    }
    val transition = rememberInfiniteTransition(label = "gen_img_shimmer")
    val pulse by transition.animateFloat(
        initialValue = 0.36f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "gen_img_pulse",
    )

    val aspectRatio = remember(payload?.aspectRatio) { parsePlaceholderRatio(payload?.aspectRatio) }
    val couiDark = if (isCoui) CouiTheme.isDark else false
    val surfaceBg = if (isCoui) {
        if (couiDark) Color(0xFF1F2229) else Color(0xFFF2F4F8)
    } else {
        MiuixTheme.colorScheme.surfaceContainerHigh
    }
    val accent = if (isCoui) CouiPageDefaults.themeColors(couiDark).accent else MiuixTheme.colorScheme.primary
    val titleColor = if (isCoui) CouiPageDefaults.themeColors(couiDark).title else MiuixTheme.colorScheme.onSurface
    val summaryColor = if (isCoui) CouiPageDefaults.themeColors(couiDark).summary else MiuixTheme.colorScheme.onSurfaceVariantSummary
    val maxCardWidth = if (assistantOverlay) 260.dp else 300.dp
    val cornerShape = RoundedCornerShape(18.dp)

    Column(
        modifier = Modifier
            .widthIn(max = maxCardWidth)
            .fillMaxWidth()
            .then(
                if (isCoui) {
                    Modifier
                        .clip(cornerShape)
                        .background(surfaceBg)
                        .border(0.75.dp, accent.copy(alpha = 0.16f), cornerShape)
                } else {
                    Modifier
                        .squircleSurface(color = surfaceBg, cornerRadius = 18.dp)
                        .squircleBorder(0.75.dp, accent.copy(alpha = 0.16f), 18.dp)
                },
            ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(aspectRatio)
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            accent.copy(alpha = 0.08f * pulse),
                            accent.copy(alpha = 0.18f * pulse),
                            accent.copy(alpha = 0.06f * pulse),
                        ),
                    ),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(20.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(accent.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.AutoAwesome,
                        contentDescription = null,
                        tint = accent.copy(alpha = pulse),
                        modifier = Modifier.size(22.dp),
                    )
                }
                val requestedCount = payload?.requestedCount ?: 1
                val isEditing = (payload?.referenceCount ?: 0) > 0
                val actionLabel = when {
                    isEditing && requestedCount > 1 -> stringResource(R.string.imagegen_card_editing_multiple, requestedCount)
                    isEditing -> stringResource(R.string.imagegen_card_editing)
                    requestedCount > 1 -> stringResource(R.string.imagegen_card_generating_multiple, requestedCount)
                    else -> stringResource(R.string.imagegen_card_generating)
                }
                Text(
                    text = actionLabel,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = titleColor,
                )
                Text(
                    text = if (elapsedSeconds > 0) {
                        stringResource(R.string.imagegen_card_elapsed_seconds, elapsedSeconds)
                    } else {
                        stringResource(R.string.imagegen_card_please_wait)
                    },
                    fontSize = 12.sp,
                    color = summaryColor,
                )
            }
        }
        val promptPreview = payload?.prompt?.trim().orEmpty()
        if (promptPreview.isNotEmpty()) {
            Text(
                text = promptPreview,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = summaryColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun GeneratedImagesGalleryCard(
    messageId: String,
    payload: GeneratedImageActivityPayload,
    isCoui: Boolean,
    assistantOverlay: Boolean,
    onEditImage: ((String) -> Unit)?,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val previewer = LocalChatImagePreviewer.current.takeUnless { assistantOverlay }
    val imageBounds = remember(messageId) { mutableStateMapOf<Int, Rect>() }
    val imagePaths = remember(payload.images) { payload.images.map { it.filePath } }
    var promptExpanded by rememberSaveable(messageId) { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }

    val couiDark = if (isCoui) CouiTheme.isDark else false
    val surfaceBg = if (isCoui) {
        if (couiDark) Color(0xFF1F2229) else Color(0xFFF4F6FA)
    } else {
        MiuixTheme.colorScheme.surfaceContainerHigh
    }
    val titleColor = if (isCoui) CouiPageDefaults.themeColors(couiDark).title else MiuixTheme.colorScheme.onSurface
    val summaryColor = if (isCoui) CouiPageDefaults.themeColors(couiDark).summary else MiuixTheme.colorScheme.onSurfaceVariantSummary
    val borderColor = if (isCoui) {
        if (couiDark) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.06f)
    } else {
        MiuixTheme.colorScheme.outline.copy(alpha = 0.14f)
    }
    val maxWidth = if (assistantOverlay) 276.dp else 324.dp

    val openPreviewAt: (Int) -> Unit = { idx ->
        previewer?.open(
            images = imagePaths,
            initialIndex = idx,
            groupKey = messageId,
            sourceBoundsProvider = { page -> imageBounds[page] },
            sourceCornerRadius = 16.dp,
            onEditImage = onEditImage?.let { editCb ->
                { path -> editCb(toAttachableUri(path)) }
            },
        )
    }

    Column(
        modifier = Modifier
            .widthIn(max = maxWidth)
            .fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (payload.images.size == 1) {
            val item = payload.images.first()
            val ratio = if (item.width > 0 && item.height > 0) {
                (item.width.toFloat() / item.height.toFloat()).coerceIn(0.62f, 1.85f)
            } else {
                parsePlaceholderRatio(payload.aspectRatio)
            }
            GeneratedImageTile(
                filePath = item.filePath,
                groupKey = messageId,
                index = 0,
                previewer = previewer,
                isCoui = isCoui,
                surfaceBg = surfaceBg,
                borderColor = borderColor,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(ratio),
                onBoundsChanged = { rect -> imageBounds[0] = rect },
                onClick = { openPreviewAt(0) },
            )
        } else {
            val rows = payload.images.chunked(2)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                rows.forEachIndexed { rowIdx, rowItems ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        rowItems.forEachIndexed { colIdx, item ->
                            val globalIdx = rowIdx * 2 + colIdx
                            GeneratedImageTile(
                                filePath = item.filePath,
                                groupKey = messageId,
                                index = globalIdx,
                                previewer = previewer,
                                isCoui = isCoui,
                                surfaceBg = surfaceBg,
                                borderColor = borderColor,
                                modifier = Modifier
                                    .weight(1f)
                                    .aspectRatio(1f),
                                onBoundsChanged = { rect -> imageBounds[globalIdx] = rect },
                                onClick = { openPreviewAt(globalIdx) },
                            )
                        }
                        if (rowItems.size == 1) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }

        // 图片下方轻巧操作栏（单图或多图首图快捷保存/分享/继续编辑 + 提示词折叠）
        if (!assistantOverlay) {
            val firstPath = payload.images.first().filePath
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ImageCardChipButton(
                    icon = Icons.Rounded.Download,
                    label = when {
                        saving -> stringResource(R.string.imagegen_card_saving)
                        payload.images.size > 1 -> stringResource(R.string.imagegen_card_save_all)
                        else -> stringResource(R.string.action_save)
                    },
                    enabled = !saving,
                    surfaceBg = surfaceBg,
                    contentColor = titleColor,
                    onClick = {
                        if (saving) return@ImageCardChipButton
                        saving = true
                        scope.launch {
                            val okCount = withContext(Dispatchers.IO) {
                                payload.images.count { img ->
                                    GeneratedImageStore.saveToSystemGallery(context, img.filePath).isSuccess
                                }
                            }
                            saving = false
                            if (okCount > 0) {
                                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                                val msg = if (okCount == 1) {
                                    resources.getString(R.string.imagegen_card_saved_single)
                                } else {
                                    resources.getString(R.string.imagegen_card_saved_multiple, okCount)
                                }
                                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(
                                    context,
                                    resources.getString(R.string.imagegen_card_save_failed),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        }
                    },
                )
                ImageCardChipButton(
                    icon = Icons.Rounded.Share,
                    label = stringResource(R.string.imagegen_card_action_share),
                    surfaceBg = surfaceBg,
                    contentColor = titleColor,
                    onClick = {
                        scope.launch {
                            val res = withContext(Dispatchers.IO) {
                                GeneratedImageStore.shareImage(context, firstPath)
                            }
                            if (res.isFailure) {
                                Toast.makeText(
                                    context,
                                    resources.getString(R.string.imagegen_card_share_failed),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        }
                    },
                )
                if (onEditImage != null) {
                    ImageCardChipButton(
                        icon = Icons.Rounded.AutoFixHigh,
                        label = stringResource(R.string.imagegen_card_action_edit),
                        surfaceBg = surfaceBg,
                        contentColor = titleColor,
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onEditImage(toAttachableUri(firstPath))
                            Toast.makeText(
                                context,
                                resources.getString(R.string.imagegen_card_edit_loaded_hint),
                                Toast.LENGTH_SHORT,
                            ).show()
                        },
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                if (payload.prompt.isNotBlank()) {
                    Row(
                        modifier = Modifier
                            .clip(CircleShape)
                            .clickable { promptExpanded = !promptExpanded }
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.imagegen_card_prompt_label),
                            fontSize = 11.sp,
                            color = summaryColor,
                        )
                        Icon(
                            imageVector = if (promptExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                            contentDescription = null,
                            tint = summaryColor,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            }

            AnimatedVisibility(visible = promptExpanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(surfaceBg)
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (payload.model.isNotBlank()) {
                        val meta = buildString {
                            append(payload.model)
                            payload.images.firstOrNull()?.takeIf { it.width > 0 && it.height > 0 }?.let {
                                append(" · ${it.width}×${it.height}")
                            }
                        }
                        Text(
                            text = meta,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = summaryColor,
                        )
                    }
                    Text(
                        text = payload.prompt,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        color = titleColor.copy(alpha = 0.88f),
                    )
                }
            }
        }
    }
}

@Composable
private fun GeneratedImageTile(
    filePath: String,
    groupKey: Any,
    index: Int,
    previewer: ChatImagePreviewer?,
    isCoui: Boolean,
    surfaceBg: Color,
    borderColor: Color,
    modifier: Modifier = Modifier,
    onBoundsChanged: (Rect) -> Unit,
    onClick: () -> Unit,
) {
    val bitmap = rememberChatImageBitmap(source = filePath, maxDimensionPx = 1600)
    val fileMissing = remember(filePath, bitmap) { bitmap == null && !File(filePath.removePrefix("file://")).isFile }
    val cornerShape = RoundedCornerShape(16.dp)

    Box(
        modifier = modifier
            .onGloballyPositioned { coords ->
                if (coords.isAttached) onBoundsChanged(coords.boundsInWindow())
            }
            .graphicsLayer {
                alpha = if (previewer?.isPresenting(groupKey, index) == true) 0f else 1f
            }
            .then(
                if (isCoui) {
                    Modifier
                        .clip(cornerShape)
                        .background(surfaceBg)
                        .border(0.75.dp, borderColor, cornerShape)
                } else {
                    Modifier
                        .squircleSurface(color = surfaceBg, cornerRadius = 16.dp)
                        .squircleBorder(0.75.dp, borderColor, 16.dp)
                },
            )
            .clickable(enabled = bitmap != null && previewer != null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        when {
            bitmap != null -> {
                Image(
                    bitmap = bitmap,
                    contentDescription = stringResource(R.string.imagegen_generated_image_desc),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            fileMissing -> {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(12.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.BrokenImage,
                        contentDescription = null,
                        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.size(22.dp),
                    )
                    Text(
                        text = stringResource(R.string.imagegen_card_image_missing),
                        fontSize = 11.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
            else -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(surfaceBg),
                )
            }
        }
    }
}

@Composable
private fun ImageCardChipButton(
    icon: ImageVector,
    label: String,
    surfaceBg: Color,
    contentColor: Color,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(surfaceBg)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = contentColor.copy(alpha = if (enabled) 0.85f else 0.4f),
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = contentColor.copy(alpha = if (enabled) 0.88f else 0.4f),
        )
    }
}

@Composable
private fun GeneratedImageStatusNotice(
    isError: Boolean,
    title: String,
    detail: String?,
    isCoui: Boolean,
) {
    val couiDark = if (isCoui) CouiTheme.isDark else false
    val bg = if (isError) {
        StatusError.copy(alpha = if (couiDark) 0.16f else 0.09f)
    } else if (isCoui) {
        if (couiDark) Color(0xFF1F2229) else Color(0xFFF4F6FA)
    } else {
        MiuixTheme.colorScheme.surfaceContainerHigh
    }
    val tint = if (isError) StatusError else MiuixTheme.colorScheme.primary
    val titleColor = if (isCoui) CouiPageDefaults.themeColors(couiDark).title else MiuixTheme.colorScheme.onSurface
    val summaryColor = if (isCoui) CouiPageDefaults.themeColors(couiDark).summary else MiuixTheme.colorScheme.onSurfaceVariantSummary

    Row(
        modifier = Modifier
            .widthIn(max = 324.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(bg)
            .padding(horizontal = 13.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = if (isError) Icons.Rounded.ErrorOutline else Icons.Rounded.AutoAwesome,
            contentDescription = null,
            tint = tint,
            modifier = Modifier
                .padding(top = 1.dp)
                .size(18.dp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = title,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = titleColor,
            )
            if (!detail.isNullOrBlank()) {
                Text(
                    text = detail,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = summaryColor,
                )
            }
        }
    }
}

private fun toAttachableUri(path: String): String =
    if (path.startsWith("data:", ignoreCase = true) || path.startsWith("file://", ignoreCase = true) || path.startsWith("content://", ignoreCase = true)) {
        path
    } else {
        File(path).toURI().toString()
    }

private fun parsePlaceholderRatio(ratio: String?): Float = when (ratio?.trim()) {
    "16:9" -> 16f / 9f
    "9:16" -> 9f / 16f
    "4:3" -> 4f / 3f
    "3:4" -> 3f / 4f
    "3:2" -> 3f / 2f
    "2:3" -> 2f / 3f
    else -> 1f
}
