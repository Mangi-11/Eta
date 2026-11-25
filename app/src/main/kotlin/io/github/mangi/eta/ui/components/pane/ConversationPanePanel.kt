package io.github.mangi.eta.ui.components.pane

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.model.ConversationPaneUiState
import io.github.mangi.eta.ui.model.ConversationSummaryUi
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.glass.GlassPopup
import top.yukonga.miuix.kmp.glass.GlassPopupDefaults
import top.yukonga.miuix.kmp.glass.GlassPopupItem
import top.yukonga.miuix.kmp.glass.GlassTransformPopup
import top.yukonga.miuix.kmp.glass.glassPopupAnchor
import top.yukonga.miuix.kmp.glass.glassPopupAnchorContent
import top.yukonga.miuix.kmp.glass.rememberGlassPopupAnchor
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.BookOutline
import top.yukonga.miuix.kmp.icon.glass.ChevronForward
import top.yukonga.miuix.kmp.icon.glass.ClockOutline
import top.yukonga.miuix.kmp.icon.glass.Contacts
import top.yukonga.miuix.kmp.icon.glass.Delete
import top.yukonga.miuix.kmp.icon.glass.Download
import top.yukonga.miuix.kmp.icon.glass.Edit
import top.yukonga.miuix.kmp.icon.glass.GridView
import top.yukonga.miuix.kmp.icon.glass.More
import top.yukonga.miuix.kmp.icon.glass.Pin
import top.yukonga.miuix.kmp.icon.glass.Processor
import top.yukonga.miuix.kmp.icon.glass.Search
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import io.github.mangi.eta.ui.components.CharacterAvatar
import io.github.mangi.eta.ui.components.TopLayer
import io.github.mangi.eta.ui.components.captureGlassBackdrop
import io.github.mangi.eta.ui.components.color
import io.github.mangi.eta.ui.components.currentGlassOverlayBackdrop
import io.github.mangi.eta.ui.components.label
import io.github.mangi.eta.ui.components.rememberGlassBackdrop
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

@Stable
private class PaneBarInsets {
    var top by mutableStateOf(0.dp)
    var bottom by mutableStateOf(0.dp)
}

@Composable
internal fun ConversationPanePanel(
    state: ConversationPaneUiState,
    width: Dp,
    listState: LazyListState,
    isRevealed: Boolean,
    activeModelLabel: String?,
    hasPermissionAttention: Boolean,
    onSearchChange: (String) -> Unit,
    onNewConversation: () -> Unit,
    onConversationSelected: (String) -> Unit,
    onConversationTogglePin: (ConversationSummaryUi) -> Unit,
    onConversationRename: (ConversationSummaryUi) -> Unit,
    onConversationExport: (ConversationSummaryUi) -> Unit,
    onConversationDelete: (ConversationSummaryUi) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenModelProviders: () -> Unit,
    onOpenTools: () -> Unit,
    onOpenSkills: () -> Unit,
    onOpenScheduledTasks: () -> Unit = {},
    onOpenCharacters: () -> Unit,
    onOpenPermissions: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // state.conversations 已由 AgentAppState 按标题、预览与消息内容过滤。
    val query = state.searchQuery.trim()
    val isSearching = query.isNotBlank()
    val groups = remember(state.conversations) { state.conversations.groupForDrawer() }
    val containerColor = conversationPaneContainerColor()
    val paneBackdrop = rememberGlassBackdrop(containerColor)
    val barInsets = remember { PaneBarInsets() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) keyboard?.hide()
    }
    val bodyCaptureModifier = if (isRevealed && paneBackdrop != null) {
        Modifier.captureGlassBackdrop(paneBackdrop)
    } else {
        Modifier
    }
    val listScrollModifier = Modifier
        .scrollEndHaptic()
        .overScrollVertical()
    val flingBehavior = androidx.compose.foundation.gestures.ScrollableDefaults.flingBehavior()

    Box(
        modifier = modifier
            .width(width)
            .fillMaxHeight()
            .background(containerColor),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // 测量顶栏与底栏真实高度（含安全区与 IME），同步给滚动画布的 contentPadding，
            // 使列表自然穿梭于顶底浮层之下并分别被各自 backdrop 采样，同时避免 Subcompose 重组开销。
            Layout(
                modifier = Modifier.fillMaxSize(),
                content = {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(containerColor)
                            .then(bodyCaptureModifier),
                    ) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .fillMaxSize()
                                .clipToBounds()
                                .windowInsetsPadding(
                                    WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
                                )
                                .then(listScrollModifier),
                            contentPadding = PaddingValues(
                                top = barInsets.top + 10.dp,
                                bottom = barInsets.bottom + 10.dp,
                            ),
                            verticalArrangement = Arrangement.spacedBy(ConversationPanelMetrics.RowGap),
                            flingBehavior = flingBehavior,
                            overscrollEffect = null,
                        ) {
                            if (!isSearching) {
                                item(key = "pane_workbench") {
                                    PaneWorkbenchSection(
                                        activeModelLabel = activeModelLabel,
                                        onOpenModelProviders = onOpenModelProviders,
                                        onOpenCharacters = onOpenCharacters,
                                        onOpenTools = onOpenTools,
                                        onOpenSkills = onOpenSkills,
                                        onOpenScheduledTasks = onOpenScheduledTasks,
                                    )
                                }
                            }

                            if (state.conversations.isEmpty()) {
                                item(key = "pane_empty_conversations") {
                                    EmptyConversations(isSearching = isSearching)
                                }
                            } else {
                                groups.forEachIndexed { groupIndex, group ->
                                    item(key = "section-${group.section}") {
                                        ConversationSectionHeader(
                                            group = group,
                                            isFirstSection = groupIndex == 0,
                                            hasWorkbenchAbove = !isSearching,
                                        )
                                    }
                                    items(
                                        items = group.items,
                                        key = { it.id },
                                    ) { conversation ->
                                        ConversationHistoryRow(
                                            conversation = conversation,
                                            selected = conversation.id == state.selectedConversationId,
                                            backdrop = paneBackdrop,
                                            onClick = { onConversationSelected(conversation.id) },
                                            onTogglePin = { onConversationTogglePin(conversation) },
                                            onRename = { onConversationRename(conversation) },
                                            onExport = { onConversationExport(conversation) },
                                            onDelete = { onConversationDelete(conversation) },
                                        )
                                    }
                                }
                            }
                        }
                    }
                    ConversationPaneTopBar(
                        containerColor = containerColor,
                        backdrop = paneBackdrop,
                        onNewConversation = onNewConversation,
                    )
                    ConversationPaneBottomDock(
                        query = state.searchQuery,
                        isRevealed = isRevealed,
                        hasPermissionAttention = hasPermissionAttention,
                        containerColor = containerColor,
                        backdrop = paneBackdrop,
                        onSearchChange = onSearchChange,
                        onOpenPermissions = onOpenPermissions,
                        onOpenSettings = onOpenSettings,
                    )
                },
            ) { measurables, constraints ->
                val looseConstraints = constraints.copy(minWidth = 0, minHeight = 0)
                val topBar = measurables[1].measure(looseConstraints)
                val bottomDock = measurables[2].measure(looseConstraints)
                val topDp = topBar.height.toDp()
                val bottomDp = bottomDock.height.toDp()
                if (barInsets.top != topDp) barInsets.top = topDp
                if (barInsets.bottom != bottomDp) barInsets.bottom = bottomDp
                val body = measurables[0].measure(constraints)
                layout(constraints.maxWidth, constraints.maxHeight) {
                    body.placeRelative(0, 0)
                    topBar.placeRelative(0, 0)
                    bottomDock.placeRelative(0, constraints.maxHeight - bottomDock.height)
                }
            }

            // 侧栏物理右侧细微收边，保证浅/深/纯黑模式下与右侧聊天舞台始终保持利落边界。
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .width(0.5.dp)
                    .background(conversationPaneEdgeBorderColor()),
            )
        }
    }
}

private data class WorkbenchEntry(
    val icon: ImageVector,
    val title: String,
    val trailingValue: String? = null,
    val onClick: () -> Unit,
)

@Composable
private fun PaneWorkbenchSection(
    activeModelLabel: String?,
    onOpenModelProviders: () -> Unit,
    onOpenCharacters: () -> Unit,
    onOpenTools: () -> Unit,
    onOpenSkills: () -> Unit,
    onOpenScheduledTasks: () -> Unit = {},
) {
    val modelSummary = activeModelLabel?.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.conversation_model_unconfigured)
    val entries = listOf(
        WorkbenchEntry(
            icon = MiuixIcons.Glass.Processor,
            title = stringResource(R.string.conversation_dock_models),
            trailingValue = modelSummary,
            onClick = onOpenModelProviders,
        ),
        WorkbenchEntry(
            icon = MiuixIcons.Glass.Contacts,
            title = stringResource(R.string.conversation_dock_characters),
            onClick = onOpenCharacters,
        ),
        WorkbenchEntry(
            icon = MiuixIcons.Glass.GridView,
            title = stringResource(R.string.route_tools),
            onClick = onOpenTools,
        ),
        WorkbenchEntry(
            icon = MiuixIcons.Glass.BookOutline,
            title = stringResource(R.string.route_skills),
            onClick = onOpenSkills,
        ),
        WorkbenchEntry(
            icon = MiuixIcons.Glass.ClockOutline,
            title = stringResource(R.string.route_scheduled_tasks),
            onClick = onOpenScheduledTasks,
        ),
    )

    val cardColor = conversationPaneCardColor()
    val dividerIndent = ConversationPanelMetrics.WorkbenchRowHorizontalPadding +
        ConversationPanelMetrics.WorkbenchIconSize +
        ConversationPanelMetrics.WorkbenchIconGap

    val colors = MiuixTheme.colorScheme
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ConversationPanelMetrics.PaneHorizontalPadding),
        colors = CardDefaults.defaultColors(color = cardColor),
    ) {
        entries.forEachIndexed { index, entry ->
            WorkbenchRow(entry = entry)
            if (index < entries.lastIndex) {
                HorizontalDivider(
                    modifier = Modifier.padding(
                        start = dividerIndent,
                        end = ConversationPanelMetrics.WorkbenchRowHorizontalPadding,
                    ),
                    thickness = 0.5.dp,
                    color = colors.dividerLine.copy(alpha = 0.45f),
                )
            }
        }
    }
}

@Composable
private fun WorkbenchRow(entry: WorkbenchEntry) {
    val colors = MiuixTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = ConversationPanelMetrics.WorkbenchRowMinHeight)
            .clickable(role = Role.Button, onClick = entry.onClick)
            .padding(
                horizontal = ConversationPanelMetrics.WorkbenchRowHorizontalPadding,
                vertical = 11.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = entry.icon,
            contentDescription = null,
            modifier = Modifier.size(ConversationPanelMetrics.WorkbenchIconSize),
            tint = colors.onSurface.copy(alpha = 0.85f),
        )
        Spacer(Modifier.width(ConversationPanelMetrics.WorkbenchIconGap))
        Text(
            text = entry.title,
            style = MiuixTheme.textStyles.body1.copy(fontSize = 15.sp),
            color = colors.onSurface,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (!entry.trailingValue.isNullOrEmpty()) {
            Text(
                text = entry.trailingValue,
                style = MiuixTheme.textStyles.footnote1,
                color = colors.onSurfaceVariantSummary,
                modifier = Modifier
                    .widthIn(max = 136.dp)
                    .padding(start = 8.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            imageVector = MiuixIcons.Glass.ChevronForward,
            contentDescription = null,
            modifier = Modifier
                .padding(start = 4.dp)
                .size(15.dp),
            tint = colors.onSurfaceVariantActions,
        )
    }
}

@Composable
private fun ConversationSectionHeader(
    group: ConversationDrawerGroup,
    isFirstSection: Boolean,
    hasWorkbenchAbove: Boolean,
) {
    val label = group.localizedLabel()
    val topPadding = when {
        isFirstSection && hasWorkbenchAbove -> ConversationPanelMetrics.FirstSectionTopPadding
        isFirstSection -> 8.dp
        else -> ConversationPanelMetrics.SectionTopPadding
    }
    val horizontalInset = ConversationPanelMetrics.PaneHorizontalPadding +
        ConversationPanelMetrics.RowHorizontalPadding

    Text(
        text = label,
        style = MiuixTheme.textStyles.footnote1,
        fontWeight = FontWeight.Medium,
        color = MiuixTheme.colorScheme.onBackgroundVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = horizontalInset,
                top = topPadding,
                end = horizontalInset,
                bottom = ConversationPanelMetrics.SectionBottomPadding,
            )
            .semantics { heading() },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationHistoryRow(
    conversation: ConversationSummaryUi,
    selected: Boolean,
    backdrop: Backdrop?,
    onClick: () -> Unit,
    onTogglePin: () -> Unit,
    onRename: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    var showActionMenu by remember { mutableStateOf(false) }
    var rowBounds by remember { mutableStateOf(Rect.Zero) }
    val hapticFeedback = LocalHapticFeedback.current
    val moreAnchor = rememberGlassPopupAnchor()
    val colors = MiuixTheme.colorScheme
    val isDark = colors.surface.luminance() < 0.5f

    val rowBackground = if (selected) {
        colors.tertiaryContainer.copy(alpha = if (isDark) 0.72f else 0.90f)
    } else {
        Color.Transparent
    }
    val rowModifier = Modifier.squircleSurface(
        color = rowBackground,
        cornerRadius = ConversationPanelMetrics.RowCornerRadius,
    )

    val titleColor = colors.onSurface
    val summaryColor = colors.onSurfaceVariantSummary
    val accentColor = colors.primary
    val avatarBorderColor = if (isDark) {
        Color.White.copy(alpha = 0.14f)
    } else {
        Color.Black.copy(alpha = 0.08f)
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ConversationPanelMetrics.PaneHorizontalPadding),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = ConversationPanelMetrics.RowMinHeight)
                .onGloballyPositioned { rowBounds = it.boundsInWindow() }
                .then(rowModifier)
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = {
                        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                        showActionMenu = true
                    },
                )
                .padding(
                    start = ConversationPanelMetrics.RowHorizontalPadding,
                    end = if (selected) 8.dp else ConversationPanelMetrics.RowHorizontalPadding,
                    top = ConversationPanelMetrics.RowVerticalPadding,
                    bottom = ConversationPanelMetrics.RowVerticalPadding,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (conversation.characterName != null) {
                CharacterAvatar(
                    name = conversation.characterName,
                    avatarPath = conversation.characterAvatarPath,
                    size = ConversationPanelMetrics.CharacterAvatarSize,
                    shape = CircleShape,
                    borderWidth = 0.5.dp,
                    borderColor = avatarBorderColor,
                )
                Spacer(Modifier.width(ConversationPanelMetrics.CharacterAvatarGap))
            }

            Column(modifier = Modifier.weight(1f)) {
                val title = conversation.title.ifBlank { conversation.preview }
                Text(
                    text = title,
                    color = titleColor,
                    style = MiuixTheme.textStyles.body1.copy(fontSize = 15.sp),
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // 标题与角色名相同（如尚未发起首轮对话的角色会话）时不再重复第二行。
                conversation.characterName?.takeIf { it != title }?.let { name ->
                    Text(
                        text = name,
                        style = MiuixTheme.textStyles.footnote1.copy(fontSize = 12.sp),
                        color = summaryColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                conversation.runStatus?.let { status ->
                    Text(
                        text = status,
                        style = MiuixTheme.textStyles.footnote1.copy(fontSize = 12.sp),
                        color = accentColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }

            if (conversation.isActiveRun) {
                Box(
                    modifier = Modifier
                        .padding(start = ConversationPanelMetrics.ActiveDotGap)
                        .size(ConversationPanelMetrics.ActiveDotSize)
                        .clip(CircleShape)
                        .background(accentColor),
                )
            } else if (conversation.isPinned) {
                Icon(
                    imageVector = MiuixIcons.Glass.Pin,
                    contentDescription = null,
                    modifier = Modifier
                        .padding(start = 6.dp)
                        .size(16.dp),
                    tint = summaryColor.copy(alpha = 0.72f),
                )
            }

            if (selected) {
                val moreLabel = stringResource(R.string.action_more)
                Box(
                    modifier = Modifier
                        .padding(start = 4.dp)
                        .glassPopupAnchor(
                            anchor = moreAnchor,
                            cornerRadius = 14.dp,
                        )
                        .size(28.dp)
                        .clip(CircleShape)
                        .clickable(
                            role = Role.Button,
                            onClickLabel = moreLabel,
                        ) {
                            showActionMenu = true
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = MiuixIcons.Glass.More,
                        contentDescription = moreLabel,
                        modifier = Modifier
                            .size(20.dp)
                            .glassPopupAnchorContent(moreAnchor),
                        tint = summaryColor,
                    )
                }
            }
        }

        // 将选中指示条放在左内边距留白区，普通与角色会话共用同一套选中反馈语言与左对齐节奏。
        if (selected) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 5.5.dp)
                    .width(2.5.dp)
                    .height(16.dp)
                    .clip(CircleShape)
                    .background(accentColor),
            )
        }

        val pinText = stringResource(
            if (conversation.isPinned) {
                R.string.action_unpin_conversation
            } else {
                R.string.action_pin_conversation
            },
        )
        val renameText = stringResource(R.string.action_rename)
        val exportText = stringResource(R.string.action_export)
        val deleteText = stringResource(R.string.action_delete)
        val overlayBackdrop = currentGlassOverlayBackdrop() ?: backdrop
        val menuContent: @Composable ColumnScope.() -> Unit = {
            GlassPopupItem(
                text = pinText,
                icon = MiuixIcons.Glass.Pin,
                onClick = {
                    showActionMenu = false
                    onTogglePin()
                },
            )
            GlassPopupItem(
                text = renameText,
                icon = MiuixIcons.Glass.Edit,
                onClick = {
                    showActionMenu = false
                    onRename()
                },
            )
            GlassPopupItem(
                text = exportText,
                icon = MiuixIcons.Glass.Download,
                onClick = {
                    showActionMenu = false
                    onExport()
                },
            )
            HorizontalDivider(
                modifier = Modifier.padding(
                    horizontal = GlassPopupDefaults.DividerPaddingHorizontal,
                    vertical = GlassPopupDefaults.DividerPaddingVertical,
                ),
                thickness = GlassPopupDefaults.DividerThickness,
            )
            GlassPopupItem(
                text = deleteText,
                icon = MiuixIcons.Glass.Delete,
                contentColor = MiuixTheme.colorScheme.error,
                onClick = {
                    showActionMenu = false
                    onDelete()
                },
            )
        }
        TopLayer {
            if (selected) {
                GlassTransformPopup(
                    show = showActionMenu,
                    onDismissRequest = { showActionMenu = false },
                    anchor = moreAnchor,
                    backdrop = overlayBackdrop,
                    anchorContent = {
                        Icon(
                            imageVector = MiuixIcons.Glass.More,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = summaryColor,
                        )
                    },
                    content = menuContent,
                )
            } else {
                GlassPopup(
                    show = showActionMenu,
                    onDismissRequest = { showActionMenu = false },
                    anchorBounds = rowBounds,
                    backdrop = overlayBackdrop,
                    content = menuContent,
                )
            }
        }
    }
}

@Composable
private fun EmptyConversations(isSearching: Boolean) {
    val primaryTextColor = MiuixTheme.colorScheme.onSurfaceVariantSummary
    val badgeBg = conversationPaneCardColor()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = ConversationPanelMetrics.PaneHorizontalPadding + 12.dp,
                vertical = ConversationPanelMetrics.EmptyVerticalPadding,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .squircleSurface(color = badgeBg, cornerRadius = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (isSearching) MiuixIcons.Glass.Search else ConversationPaneIcons.BrandEta,
                contentDescription = null,
                modifier = Modifier.size(if (isSearching) 24.dp else 20.dp),
                tint = primaryTextColor.copy(alpha = 0.72f),
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(
                if (isSearching) R.string.conversation_no_results else R.string.conversation_empty,
            ),
            color = primaryTextColor,
            style = MiuixTheme.textStyles.body2,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
        )
        if (!isSearching) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.conversation_empty_hint),
                color = primaryTextColor.copy(alpha = 0.75f),
                style = MiuixTheme.textStyles.footnote1.copy(fontSize = 12.sp),
                textAlign = TextAlign.Center,
            )
        }
    }
}

private data class ConversationDrawerGroup(
    val section: ConversationDrawerSection,
    val items: List<ConversationSummaryUi>,
)

private sealed interface ConversationDrawerSection {
    data object Pinned : ConversationDrawerSection
    data object Today : ConversationDrawerSection
    data class Dated(val label: String) : ConversationDrawerSection
}

@Composable
private fun ConversationDrawerGroup.localizedLabel(): String = when (val value = section) {
    ConversationDrawerSection.Pinned -> stringResource(R.string.conversation_section_pinned)
    ConversationDrawerSection.Today -> stringResource(R.string.conversation_section_today)
    is ConversationDrawerSection.Dated -> value.label
}

private fun List<ConversationSummaryUi>.groupForDrawer(): List<ConversationDrawerGroup> {
    if (isEmpty()) return emptyList()
    val groups = mutableListOf<ConversationDrawerGroup>()
    for (conversation in this) {
        val section = conversation.drawerSection()
        val last = groups.lastOrNull()
        if (last?.section == section) {
            groups[groups.lastIndex] = last.copy(items = last.items + conversation)
        } else {
            groups += ConversationDrawerGroup(section = section, items = listOf(conversation))
        }
    }
    return groups
}

private fun ConversationSummaryUi.drawerSection(): ConversationDrawerSection = when {
    isPinned -> ConversationDrawerSection.Pinned
    isActiveRun || isUpdatedToday(updatedAtMillis) -> ConversationDrawerSection.Today
    else -> ConversationDrawerSection.Dated(timeLabel)
}

private fun isUpdatedToday(timestampMillis: Long): Boolean {
    if (timestampMillis <= 0L) return true
    val now = java.util.Calendar.getInstance()
    val target = java.util.Calendar.getInstance().apply { timeInMillis = timestampMillis }
    return now.get(java.util.Calendar.ERA) == target.get(java.util.Calendar.ERA) &&
        now.get(java.util.Calendar.YEAR) == target.get(java.util.Calendar.YEAR) &&
        now.get(java.util.Calendar.DAY_OF_YEAR) == target.get(java.util.Calendar.DAY_OF_YEAR)
}
