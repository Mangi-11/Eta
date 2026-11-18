package io.github.mangi.eta.ui.components

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.captionBar
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.itos.coui.menu.CouiMenuItem
import com.itos.coui.menu.Menu
import com.itos.coui.menu.couiMenuAnchor
import com.itos.coui.menu.rememberCouiMenuAnchor
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.model.AgentFileReference
import io.github.mangi.eta.agent.model.AgentFileReferenceKind
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.ui.app.LocalUiStyle
import io.github.mangi.eta.ui.components.coui.CouiInputDialog
import io.github.mangi.eta.ui.model.PendingFileReferenceUi
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.glass.GlassDialog
import top.yukonga.miuix.kmp.glass.GlassPopupDefaults
import top.yukonga.miuix.kmp.glass.GlassPopupItem
import top.yukonga.miuix.kmp.glass.GlassPopupSizing
import top.yukonga.miuix.kmp.glass.GlassTransformPopup
import top.yukonga.miuix.kmp.glass.glassPopupAnchor
import top.yukonga.miuix.kmp.glass.glassPopupAnchorContent
import top.yukonga.miuix.kmp.glass.rememberGlassPopupAnchor
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.Add
import top.yukonga.miuix.kmp.icon.glass.Edit
import top.yukonga.miuix.kmp.icon.glass.File
import top.yukonga.miuix.kmp.icon.glass.FolderOutline
import top.yukonga.miuix.kmp.icon.glass.ImageOutline
import top.yukonga.miuix.kmp.squircle.squircleBorder
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal val ChatInputPopupMargin = 8.dp
internal val ChatInputActionSize = 40.dp
internal val ChatInputActionIconSize = 24.dp

internal data class InputBarPopupLayout(
    val gap: Dp,
    val maxHeight: Dp,
)

/**
 * 底部输入区弹窗需悬浮在整块输入容器上方，避免回退向上展开时压住按钮与输入栏本身。
 *
 * `GlassTransformPopup` 内部按无 `ime` 的窗口底边判断是否向下展开；当软键盘抬高输入栏时，
 * 下方看似空余极大而触发向下对齐，导致菜单压回输入框并被键盘裁切。这里按相同的窗口底边
 * 提前判定实际对齐方向，并在向下对齐分支反推 `gap`，让菜单底边始终停在输入栏上方 [ChatInputPopupMargin]。
 */
internal fun calculateInputBarPopupLayout(
    inputContainerTopPx: Float,
    anchorTopPx: Float,
    anchorBottomPx: Float,
    measuredHeightPx: Float,
    statusBarTopPx: Float,
    windowBottomPx: Float,
    safeMarginPx: Float,
    marginPx: Float,
    density: Float,
    defaultMaxHeightDp: Dp = GlassPopupDefaults.MaxHeight,
): InputBarPopupLayout {
    val maxHeightDp = if (inputContainerTopPx > statusBarTopPx) {
        ((inputContainerTopPx - statusBarTopPx - marginPx - safeMarginPx) / density).dp
    } else {
        defaultMaxHeightDp
    }.coerceIn(96.dp, defaultMaxHeightDp)

    if (inputContainerTopPx <= 0f || anchorBottomPx <= 0f) {
        return InputBarPopupLayout(gap = 0.dp, maxHeight = maxHeightDp)
    }

    val targetBottomPx = inputContainerTopPx - marginPx
    val baseGapPx = (targetBottomPx - anchorBottomPx).coerceAtMost(0f)
    val gapPx = if (measuredHeightPx > 0f && anchorTopPx > 0f) {
        val minTopPx = statusBarTopPx + safeMarginPx
        val shiftedTop = anchorTopPx + baseGapPx
        val below = windowBottomPx - safeMarginPx - shiftedTop
        val above = targetBottomPx - minTopPx
        val willAlignTop = measuredHeightPx <= below || below >= above
        if (willAlignTop) {
            ((targetBottomPx - measuredHeightPx) - anchorTopPx).coerceAtMost(0f)
        } else {
            baseGapPx
        }
    } else {
        baseGapPx
    }
    return InputBarPopupLayout(gap = (gapPx / density).dp, maxHeight = maxHeightDp)
}

@Composable
internal fun rememberInputBarPopupLayout(
    inputContainerTopPx: Float,
    anchorTopPx: Float,
    anchorBottomPx: Float,
    measuredHeightPx: Float,
): InputBarPopupLayout {
    val density = LocalDensity.current
    val statusBarTopPx = WindowInsets.statusBars.getTop(density).toFloat()
    val navBarBottomPx = WindowInsets.navigationBars.getBottom(density).toFloat()
    val captionBarBottomPx = WindowInsets.captionBar.getBottom(density).toFloat()
    val windowBottomPx = LocalWindowInfo.current.containerSize.height - navBarBottomPx - captionBarBottomPx
    return with(density) {
        calculateInputBarPopupLayout(
            inputContainerTopPx = inputContainerTopPx,
            anchorTopPx = anchorTopPx,
            anchorBottomPx = anchorBottomPx,
            measuredHeightPx = measuredHeightPx,
            statusBarTopPx = statusBarTopPx,
            windowBottomPx = windowBottomPx,
            safeMarginPx = GlassPopupDefaults.SafeMargin.toPx(),
            marginPx = ChatInputPopupMargin.toPx(),
            density = this.density,
        )
    }
}

@Composable
internal fun AgentAttachmentPickerButton(
    glassBackdrop: Backdrop?,
    inputContainerTopPx: Float,
    onAttachImage: (String) -> Unit,
    onAttachFiles: (List<String>) -> Unit,
    onAttachFolder: (String) -> Unit,
    onAttachFilePath: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var showPopup by remember { mutableStateOf(false) }
    var showPathDialog by remember { mutableStateOf(false) }
    var pathInput by remember { mutableStateOf("") }
    var buttonTopPx by remember { mutableFloatStateOf(0f) }
    var buttonBottomPx by remember { mutableFloatStateOf(0f) }
    var measuredPopupHeightPx by remember { mutableFloatStateOf(0f) }
    val popupAnchor = rememberGlassPopupAnchor()
    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(),
    ) { uris ->
        uris.forEach { uri ->
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            onAttachImage(uri.toString())
        }
    }
    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNotEmpty()) onAttachFiles(uris.map { it.toString() })
    }
    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) onAttachFolder(uri.toString())
    }

    val anchor = rememberCouiMenuAnchor()
    val options = listOf(
        stringResource(R.string.attachment_image),
        stringResource(R.string.attachment_file),
        stringResource(R.string.attachment_folder),
        stringResource(R.string.attachment_enter_path),
    )
    val onSelectOption: (Int) -> Unit = { index ->
        when (index) {
            0 -> photoPicker.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
            )
            1 -> filePicker.launch(arrayOf("*/*"))
            2 -> folderPicker.launch(null)
            3 -> {
                pathInput = ""
                showPathDialog = true
            }
        }
    }

    val uiStyle = LocalUiStyle.current
    val isCoui = uiStyle == AppearanceUiStyle.COUI
    val popupLayout = rememberInputBarPopupLayout(
        inputContainerTopPx = inputContainerTopPx,
        anchorTopPx = buttonTopPx,
        anchorBottomPx = buttonBottomPx,
        measuredHeightPx = measuredPopupHeightPx,
    )
    Box(modifier = modifier) {
        IconButton(
            onClick = { if (isCoui) anchor.open { showPopup = true } else showPopup = true },
            minWidth = 34.dp,
            minHeight = 34.dp,
            modifier = if (isCoui) {
                Modifier.couiMenuAnchor(anchor)
            } else {
                Modifier
                    .glassPopupAnchor(
                        anchor = popupAnchor,
                        cornerRadius = 17.dp,
                    )
                    .onGloballyPositioned {
                        val bounds = it.boundsInWindow()
                        buttonTopPx = bounds.top
                        buttonBottomPx = bounds.bottom
                    }
            },
        ) {
            Icon(
                imageVector = if (isCoui) Icons.Rounded.Add else MiuixIcons.Glass.Add,
                contentDescription = stringResource(R.string.ui_add_attachment_dba9e8),
                modifier = Modifier
                    .size(if (isCoui) 21.dp else 23.dp)
                    .then(if (isCoui) Modifier else Modifier.glassPopupAnchorContent(popupAnchor)),
                tint = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.72f),
            )
        }
        when (uiStyle) {
            AppearanceUiStyle.COUI -> {
                val menuItems = remember(options) {
                    options.mapIndexed { index, label -> CouiMenuItem(value = index, text = label) }
                }
                Menu(
                    expanded = showPopup,
                    onDismissRequest = { showPopup = false },
                    items = menuItems,
                    onItemClick = { item ->
                        showPopup = false
                        onSelectOption(item.value)
                    },
                    anchor = anchor,
                )
            }
            AppearanceUiStyle.MIUIX -> {
                val overlayBackdrop = currentGlassOverlayBackdrop() ?: glassBackdrop
                val optionIcons = remember {
                    listOf(
                        MiuixIcons.Glass.ImageOutline,
                        MiuixIcons.Glass.File,
                        MiuixIcons.Glass.FolderOutline,
                        MiuixIcons.Glass.Edit,
                    )
                }
                EtaTopLayer {
                    GlassTransformPopup(
                        show = showPopup,
                        onDismissRequest = { showPopup = false },
                        anchor = popupAnchor,
                        backdrop = overlayBackdrop,
                        gap = popupLayout.gap,
                        sizing = GlassPopupSizing(maxHeight = popupLayout.maxHeight),
                        onMeasured = { measuredPopupHeightPx = it.height },
                        anchorContent = {
                            Icon(
                                imageVector = MiuixIcons.Glass.Add,
                                contentDescription = null,
                                modifier = Modifier.size(23.dp),
                                tint = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.72f),
                            )
                        },
                    ) {
                        options.forEachIndexed { index, option ->
                            GlassPopupItem(
                                text = option,
                                icon = optionIcons[index],
                                onClick = {
                                    showPopup = false
                                    onSelectOption(index)
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    when (uiStyle) {
        AppearanceUiStyle.COUI -> {
            CouiInputDialog(
                visible = showPathDialog,
                title = stringResource(R.string.ui_input_file_path_36d474),
                message = stringResource(R.string.ui_supports_files_and_folders_under_internal_storage_or_520786),
                value = pathInput,
                onValueChange = { pathInput = it },
                placeholder = stringResource(R.string.ui_absolute_path_9ac6fc),
                confirmText = stringResource(R.string.attachment_add),
                confirmEnabled = pathInput.trim().startsWith('/'),
                onConfirm = {
                    val path = pathInput.trim()
                    showPathDialog = false
                    onAttachFilePath(path)
                },
                onDismissRequest = { showPathDialog = false },
            )
        }
        AppearanceUiStyle.MIUIX -> {
            GlassDialog(
                show = showPathDialog,
                backdrop = currentGlassOverlayBackdrop() ?: glassBackdrop,
                title = stringResource(R.string.ui_input_file_path_36d474),
                summary = stringResource(R.string.ui_supports_files_and_folders_under_internal_storage_or_520786),
                onDismissRequest = { showPathDialog = false },
            ) {
                Column {
                    TextField(
                        value = pathInput,
                        onValueChange = { pathInput = it },
                        label = stringResource(R.string.ui_absolute_path_9ac6fc),
                        colors = glassOverlayTextFieldColors(),
                        useLabelAsPlaceholder = true,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    MiuixDialogActions(
                        confirmText = stringResource(R.string.attachment_add),
                        confirmEnabled = pathInput.trim().startsWith('/'),
                        onCancel = { showPathDialog = false },
                        onConfirm = {
                            val path = pathInput.trim()
                            showPathDialog = false
                            onAttachFilePath(path)
                        },
                        modifier = Modifier.padding(top = 16.dp),
                    )
                }
            }
        }
    }
}

@Composable
internal fun PendingFileReferenceStrip(
    references: List<PendingFileReferenceUi>,
    onRemoveReference: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    var previousCount by remember { mutableIntStateOf(references.size) }
    LaunchedEffect(references.size) {
        if (references.size > previousCount && references.isNotEmpty()) {
            listState.animateScrollToItem(references.lastIndex)
        }
        previousCount = references.size
    }
    LazyRow(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(
            items = references,
            key = { it.id },
        ) { pending ->
            val reference = pending.reference
            Row(
                modifier = Modifier
                    .animateItem(
                        fadeInSpec = null,
                        placementSpec = spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = Spring.StiffnessMediumLow,
                        ),
                        fadeOutSpec = tween(durationMillis = 120),
                    )
                    .attachmentItemEntrance()
                    .height(42.dp)
                    .widthIn(max = 250.dp)
                    .squircleSurface(
                        color = MiuixTheme.colorScheme.surfaceContainerHigh,
                        cornerRadius = 12.dp,
                    )
                    .squircleBorder(
                        width = 0.5.dp,
                        color = MiuixTheme.colorScheme.outline.copy(alpha = 0.5f),
                        cornerRadius = 12.dp,
                    )
                    .padding(start = 12.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = if (reference.kind == AgentFileReferenceKind.Directory) {
                        Icons.Rounded.FolderOpen
                    } else {
                        Icons.Rounded.Description
                    },
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MiuixTheme.colorScheme.primary,
                )
                Text(
                    text = reference.displayName +
                        if (reference.kind == AgentFileReferenceKind.Directory) "/" else "",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .clickable { onRemoveReference(pending.id) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.ui_remove_file_reference_04bbfc),
                        modifier = Modifier.size(15.dp),
                        tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
                    )
                }
            }
        }
    }
}

/**
 * 附件胶囊与缩略图的入场动效：在 graphicsLayer 阶段驱动缩放、纵向微移与透明度，避免引发组合期重组。
 */
internal fun Modifier.attachmentItemEntrance(): Modifier = composed {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        progress.animateTo(
            targetValue = 1f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = 420f,
            ),
        )
    }
    graphicsLayer {
        val p = progress.value
        alpha = p
        val scale = 0.9f + 0.1f * p
        scaleX = scale
        scaleY = scale
        translationY = (1f - p) * 6.dp.toPx()
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun SentFileReferenceFlow(
    references: List<AgentFileReference>,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        references.forEach { reference ->
            Row(
                modifier = Modifier
                    .height(38.dp)
                    .widthIn(max = 280.dp)
                    .squircleSurface(
                        color = MiuixTheme.colorScheme.surface,
                        cornerRadius = 12.dp,
                    )
                    .squircleBorder(
                        width = 0.5.dp,
                        color = MiuixTheme.colorScheme.outline.copy(alpha = 0.45f),
                        cornerRadius = 12.dp,
                    )
                    .padding(horizontal = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = if (reference.kind == AgentFileReferenceKind.Directory) {
                        Icons.Rounded.FolderOpen
                    } else {
                        Icons.Rounded.Description
                    },
                    contentDescription = null,
                    modifier = Modifier.size(17.dp),
                    tint = MiuixTheme.colorScheme.primary,
                )
                Text(
                    text = reference.displayName +
                        if (reference.kind == AgentFileReferenceKind.Directory) "/" else "",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

internal class InputPopupPositionProvider(
    private val inputContainerTopPx: Int,
    private val windowHorizontalInsetPx: Int? = null,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowBounds: IntRect,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
        popupMargin: IntRect,
        alignment: PopupPositionProvider.Align,
    ): IntOffset {
        val alignToEnd = when (alignment) {
            PopupPositionProvider.Align.End,
            PopupPositionProvider.Align.TopEnd,
            PopupPositionProvider.Align.BottomEnd,
            -> true

            else -> false
        }
        val physicalEnd = if (layoutDirection == LayoutDirection.Ltr) alignToEnd else !alignToEnd
        val requestedX = when {
            windowHorizontalInsetPx != null && physicalEnd ->
                windowBounds.right - popupContentSize.width - popupMargin.right - windowHorizontalInsetPx

            windowHorizontalInsetPx != null ->
                windowBounds.left + popupMargin.left + windowHorizontalInsetPx

            physicalEnd -> anchorBounds.right - popupContentSize.width - popupMargin.right
            else -> anchorBounds.left + popupMargin.left
        }
        val maxX = (windowBounds.right - popupContentSize.width - popupMargin.right)
            .coerceAtLeast(windowBounds.left)
        val requestedY = inputContainerTopPx - popupContentSize.height - popupMargin.bottom
        val maxY = windowBounds.bottom - popupContentSize.height - popupMargin.bottom
        val minY = (windowBounds.top + popupMargin.top).coerceAtMost(maxY)
        return IntOffset(
            x = requestedX.coerceIn(windowBounds.left, maxX),
            y = requestedY.coerceIn(minY, maxY),
        )
    }

    override fun getMargins(): PaddingValues = PaddingValues(vertical = ChatInputPopupMargin)
}
