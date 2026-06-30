package fuck.andes.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import fuck.andes.ui.model.ConversationPaneUiState
import fuck.andes.ui.model.ConversationSummaryUi
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.SearchBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.AddCircle
import top.yukonga.miuix.kmp.icon.extended.Folder
import top.yukonga.miuix.kmp.icon.extended.Lock
import top.yukonga.miuix.kmp.icon.extended.Recent
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun ConversationSidePaneScaffold(
    state: ConversationPaneUiState,
    visible: Boolean,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
    onSearchChange: (String) -> Unit,
    onNewConversation: () -> Unit,
    onConversationSelected: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenRuns: () -> Unit,
    onOpenTools: () -> Unit,
    onOpenPermissions: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val paneWidth = minOf(maxWidth * 0.84f, 340.dp)
        val edgeSwipeWidthPx = with(density) { 36.dp.toPx() }
        val paneWidthPx = with(density) { paneWidth.toPx() }
        var dragging by remember { mutableStateOf(false) }
        var dragOffsetPx by remember { mutableFloatStateOf(0f) }
        var acceptsDrag by remember { mutableStateOf(false) }
        val animatedOffsetPx by animateFloatAsState(
            targetValue = if (visible) paneWidthPx else 0f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessMediumLow,
            ),
            label = "ConversationPaneOffset",
        )
        val offsetPx = if (dragging) dragOffsetPx else animatedOffsetPx
        val progress = if (paneWidthPx > 0f) {
            (offsetPx / paneWidthPx).coerceIn(0f, 1f)
        } else {
            0f
        }

        if (visible) {
            BackHandler(onBack = onDismiss)
        }

        ConversationPanePanel(
            state = state,
            width = paneWidth,
            onDismiss = onDismiss,
            onSearchChange = onSearchChange,
            onNewConversation = onNewConversation,
            onConversationSelected = onConversationSelected,
            onOpenSettings = onOpenSettings,
            onOpenRuns = onOpenRuns,
            onOpenTools = onOpenTools,
            onOpenPermissions = onOpenPermissions,
            modifier = Modifier.zIndex(0f),
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .offset { IntOffset(offsetPx.roundToInt(), 0) }
                .pointerInput(visible, paneWidthPx) {
                    detectHorizontalDragGestures(
                        onDragStart = { offset ->
                            acceptsDrag = visible || offset.x <= edgeSwipeWidthPx
                            if (acceptsDrag) {
                                dragging = true
                                dragOffsetPx = animatedOffsetPx
                            }
                        },
                        onHorizontalDrag = { change: PointerInputChange, dragAmount: Float ->
                            if (acceptsDrag) {
                                change.consume()
                                dragOffsetPx = (dragOffsetPx + dragAmount).coerceIn(0f, paneWidthPx)
                            }
                        },
                        onDragEnd = {
                            if (acceptsDrag) {
                                if (dragOffsetPx >= paneWidthPx * 0.44f) {
                                    onOpen()
                                } else {
                                    onDismiss()
                                }
                            }
                            dragging = false
                            acceptsDrag = false
                        },
                        onDragCancel = {
                            dragging = false
                            acceptsDrag = false
                        },
                    )
                }
                .zIndex(1f),
        ) {
            content()
            if (progress > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.30f * progress))
                        .clickable(onClick = onDismiss),
                )
            }
        }
    }
}

@Composable
private fun ConversationPanePanel(
    state: ConversationPaneUiState,
    width: androidx.compose.ui.unit.Dp,
    onDismiss: () -> Unit,
    onSearchChange: (String) -> Unit,
    onNewConversation: () -> Unit,
    onConversationSelected: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenRuns: () -> Unit,
    onOpenTools: () -> Unit,
    onOpenPermissions: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val visibleConversations = state.conversations.filter { conversation ->
        val query = state.searchQuery.trim()
        query.isBlank() ||
            conversation.title.contains(query, ignoreCase = true) ||
            conversation.preview.contains(query, ignoreCase = true)
    }
    val pinned = visibleConversations.filter { it.isPinned }
    val recent = visibleConversations.filterNot { it.isPinned }

    Column(
        modifier = modifier
            .width(width)
            .fillMaxHeight()
            .background(MiuixTheme.colorScheme.background)
            .safeDrawingPadding()
            .padding(horizontal = 18.dp),
    ) {
        PaneHeader(
            onNewConversation = onNewConversation,
        )
        SearchBar(
            inputField = {
                InputField(
                    query = state.searchQuery,
                    onQueryChange = onSearchChange,
                    onSearch = onSearchChange,
                    expanded = false,
                    onExpandedChange = {},
                    label = "搜索对话",
                )
            },
            expanded = false,
            onExpandedChange = {},
            content = {},
        )
        Spacer(modifier = Modifier.height(14.dp))
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            if (visibleConversations.isEmpty()) {
                item {
                    EmptyConversations(isSearching = state.searchQuery.isNotBlank())
                }
            } else {
                if (pinned.isNotEmpty()) {
                    item { PaneSectionLabel(text = "置顶") }
                    items(pinned, key = { it.id }) { conversation ->
                        ConversationTitleRow(
                            conversation = conversation,
                            selected = conversation.id == state.selectedConversationId,
                            onClick = { onConversationSelected(conversation.id) },
                        )
                    }
                }
                if (recent.isNotEmpty()) {
                    item { PaneSectionLabel(text = if (pinned.isEmpty()) "最近" else "今天与更早") }
                    items(recent, key = { it.id }) { conversation ->
                        ConversationTitleRow(
                            conversation = conversation,
                            selected = conversation.id == state.selectedConversationId,
                            onClick = { onConversationSelected(conversation.id) },
                        )
                    }
                }
            }
        }
        PaneFooter(
            onOpenSettings = onOpenSettings,
            onOpenRuns = onOpenRuns,
            onOpenTools = onOpenTools,
            onOpenPermissions = onOpenPermissions,
        )
    }
}

@Composable
private fun PaneHeader(
    onNewConversation: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "FuckAndes",
            style = MiuixTheme.textStyles.title2,
            color = MiuixTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        IconButton(onClick = onNewConversation) {
            Icon(
                imageVector = MiuixIcons.AddCircle,
                contentDescription = "新对话",
            )
        }
    }
}

@Composable
private fun PaneSectionLabel(text: String) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.subtitle,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(top = 18.dp, bottom = 6.dp),
    )
}

@Composable
private fun ConversationTitleRow(
    conversation: ConversationSummaryUi,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (selected) {
                    MiuixTheme.colorScheme.surfaceContainerHigh
                } else {
                    Color.Transparent
                },
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = conversation.title,
            style = MiuixTheme.textStyles.headline1,
            fontWeight = FontWeight.Normal,
            color = MiuixTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (conversation.isActiveRun) {
            Box(
                modifier = Modifier
                    .padding(start = 8.dp)
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(MiuixTheme.colorScheme.primary),
            )
        }
    }
}

@Composable
private fun EmptyConversations(isSearching: Boolean) {
    Text(
        text = if (isSearching) "没有匹配的对话" else "还没有对话",
        style = MiuixTheme.textStyles.headline1,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(vertical = 24.dp),
    )
}

@Composable
private fun PaneFooter(
    onOpenSettings: () -> Unit,
    onOpenRuns: () -> Unit,
    onOpenTools: () -> Unit,
    onOpenPermissions: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FooterButton(
            icon = { MiuixIcons.Settings },
            label = "设置",
            onClick = onOpenSettings,
        )
        FooterButton(
            icon = { MiuixIcons.Folder },
            label = "工具",
            onClick = onOpenTools,
        )
        FooterButton(
            icon = { MiuixIcons.Recent },
            label = "运行",
            onClick = onOpenRuns,
        )
        FooterButton(
            icon = { MiuixIcons.Lock },
            label = "权限",
            onClick = onOpenPermissions,
        )
    }
}

@Composable
private fun FooterButton(
    icon: @Composable () -> androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(54.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(MiuixTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon(),
                contentDescription = label,
                modifier = Modifier.size(20.dp),
            )
        }
        Text(
            text = label,
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
