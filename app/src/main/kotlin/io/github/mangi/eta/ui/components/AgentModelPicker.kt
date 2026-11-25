package io.github.mangi.eta.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.app.LocalGlassPageBackdrop
import io.github.mangi.eta.ui.model.AgentModelOptionUi
import io.github.mangi.eta.ui.model.AgentModelPickerUiState
import io.github.mangi.eta.ui.model.filterGroups
import io.github.mangi.eta.ui.model.modelCount
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.glass.GlassBottomSheet
import top.yukonga.miuix.kmp.glass.GlassPopupDefaults
import top.yukonga.miuix.kmp.glass.GlassPopupSizing
import top.yukonga.miuix.kmp.glass.GlassTopAppBarDefaults
import top.yukonga.miuix.kmp.glass.GlassTransformPopup
import top.yukonga.miuix.kmp.glass.glassPanel
import top.yukonga.miuix.kmp.glass.glassPopupAnchor
import top.yukonga.miuix.kmp.glass.glassPress
import top.yukonga.miuix.kmp.glass.rememberGlassPopupAnchor
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.Check
import top.yukonga.miuix.kmp.icon.glass.Ok
import top.yukonga.miuix.kmp.material.GlassDefaults
import top.yukonga.miuix.kmp.material.GlassEdgeStrokes
import top.yukonga.miuix.kmp.material.GlassMaterials
import top.yukonga.miuix.kmp.material.GlassShadows
import top.yukonga.miuix.kmp.material.GlassShape
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 聊天首页顶栏的复合标题与模型切换入口：
 * - 新对话：居中呈现轻盈的模型胶囊 `[ ✦ 模型名 ▾ ]`
 * - 对话进行中：上行会话标题，下行紧凑模型副标题，整块可点切换模型
 * - 角色对话：左侧头像直达角色详情，右侧双行展示会话标题（无标题时回落角色名）与当前模型
 */
@Composable
internal fun AgentTopBarChatHeader(
    conversationTitle: String?,
    character: RoleplayChatUi?,
    state: AgentModelPickerUiState,
    isStreaming: Boolean,
    onModelSelected: (String) -> Unit,
    onOpenCharacter: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showPopup by remember { mutableStateOf(false) }
    var showSheet by remember { mutableStateOf(false) }
    var sheetSession by remember { mutableIntStateOf(0) }
    val selected = state.selectedModel
    val enabled = !isStreaming && state.providerGroups.isNotEmpty()
    val canTriggerPicker = enabled && !state.isChanging
    LaunchedEffect(enabled) {
        if (!enabled) {
            showPopup = false
            showSheet = false
        }
    }

    val currentModel = selected?.displayName ?: stringResource(R.string.model_not_selected)
    val switchModelDescription = stringResource(R.string.model_switch_current, currentModel)
    val popupAnchor = rememberGlassPopupAnchor()
    val isDark = MiuixTheme.colorScheme.surface.luminance() < 0.5f
    val ink = MiuixTheme.colorScheme.onSurface.copy(alpha = 1f)
    val subtitleColor = ink.copy(alpha = if (isDark) 0.56f else 0.52f)
    val chevronColor = ink.copy(alpha = if (isDark) 0.46f else 0.42f)
    val interactionSource = remember { MutableInteractionSource() }
    val pageBackdrop = LocalGlassPageBackdrop.current

    val triggerModelPicker: () -> Unit = {
        if (canTriggerPicker) {
            if (state.modelCount <= ModelPopupLimit) {
                showPopup = true
            } else {
                sheetSession += 1
                showSheet = true
            }
        }
    }

    Box(modifier = modifier) {
        when {
            character != null -> {
                val avatarInteractionSource = remember { MutableInteractionSource() }
                val avatarBorderColor = if (isDark) {
                    Color.White.copy(alpha = 0.14f)
                } else {
                    Color.Black.copy(alpha = 0.08f)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .glassPress(avatarInteractionSource, enabled = true)
                            .clickable(
                                interactionSource = avatarInteractionSource,
                                indication = null,
                                role = Role.Button,
                                onClick = onOpenCharacter,
                            )
                            .semantics { contentDescription = character.characterName }
                            .padding(2.dp),
                    ) {
                        CharacterAvatar(
                            name = character.characterName,
                            avatarPath = character.avatarPath,
                            size = 28.dp,
                            shape = CircleShape,
                            borderWidth = 0.5.dp,
                            borderColor = avatarBorderColor,
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    Column(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .glassPopupAnchor(
                                anchor = popupAnchor,
                                cornerRadius = 10.dp,
                            )
                            .clip(RoundedCornerShape(10.dp))
                            .glassPress(interactionSource, enabled = enabled)
                            .clickable(
                                interactionSource = interactionSource,
                                indication = null,
                                enabled = enabled,
                                role = Role.Button,
                                onClick = triggerModelPicker,
                            )
                            .semantics { contentDescription = switchModelDescription }
                            .padding(horizontal = 6.dp, vertical = 3.dp),
                    ) {
                        val headerTitle = conversationTitle?.takeIf(String::isNotBlank) ?: character.characterName
                        Text(
                            text = headerTitle,
                            style = MiuixTheme.textStyles.body1.copy(
                                fontSize = 15.5.sp,
                                lineHeight = 19.sp,
                                fontWeight = FontWeight.SemiBold,
                            ),
                            color = MiuixTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        ModelSubtitleRow(
                            selected = selected,
                            modelName = currentModel,
                            textColor = subtitleColor,
                            chevronColor = chevronColor,
                            showChevron = enabled,
                        )
                    }
                }
            }

            !conversationTitle.isNullOrBlank() -> {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .glassPopupAnchor(
                            anchor = popupAnchor,
                            cornerRadius = 12.dp,
                        )
                        .clip(RoundedCornerShape(12.dp))
                        .glassPress(interactionSource, enabled = enabled)
                        .clickable(
                            interactionSource = interactionSource,
                            indication = null,
                            enabled = enabled,
                            role = Role.Button,
                            onClick = triggerModelPicker,
                        )
                        .semantics { contentDescription = switchModelDescription }
                        .padding(horizontal = 10.dp, vertical = 3.dp),
                ) {
                    Text(
                        text = conversationTitle,
                        style = MiuixTheme.textStyles.body1.copy(
                            fontSize = 15.5.sp,
                            lineHeight = 19.sp,
                            fontWeight = FontWeight.SemiBold,
                        ),
                        color = MiuixTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    ModelSubtitleRow(
                        selected = selected,
                        modelName = currentModel,
                        textColor = subtitleColor,
                        chevronColor = chevronColor,
                        showChevron = enabled,
                    )
                }
            }

            else -> {
                val pillShape = GlassShape(20.dp)
                val capsuleModifier = Modifier
                    .heightIn(min = 40.dp)
                    .glassPress(interactionSource, enabled = enabled)
                    .glassPanel(
                        backdrop = pageBackdrop,
                        shape = pillShape,
                        style = GlassDefaults.style(isDark),
                        material = GlassMaterials.puredThinGlass(isDark),
                        stroke = GlassEdgeStrokes.forTheme(
                            isDark = isDark,
                            light = GlassEdgeStrokes.SmallLight,
                            dark = GlassEdgeStrokes.SmallDark,
                        ),
                        shadow = GlassShadows.Float,
                        shading = false,
                        fallback = Modifier
                            .clip(CircleShape)
                            .background(GlassTopAppBarDefaults.buttonFill()),
                    )
                    .padding(start = 13.dp, end = if (enabled) 10.dp else 14.dp, top = 8.dp, bottom = 8.dp)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .glassPopupAnchor(
                            anchor = popupAnchor,
                            cornerRadius = 20.dp,
                            floating = true,
                        )
                        .clickable(
                            interactionSource = interactionSource,
                            indication = null,
                            enabled = enabled,
                            role = Role.Button,
                            onClick = triggerModelPicker,
                        )
                        .semantics { contentDescription = switchModelDescription }
                        .then(capsuleModifier),
                ) {
                    ModelBrandMark(
                        modelId = selected?.modelId,
                        sourceType = selected?.providerSourceType,
                        size = 15.dp,
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = currentModel,
                        style = MiuixTheme.textStyles.body2.copy(
                            fontSize = 14.sp,
                            lineHeight = 18.sp,
                            fontWeight = FontWeight.Medium,
                        ),
                        color = MiuixTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 180.dp),
                    )
                    if (enabled) {
                        Spacer(modifier = Modifier.width(2.dp))
                        Icon(
                            imageVector = Icons.Rounded.KeyboardArrowDown,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = chevronColor,
                        )
                    }
                }
            }
        }

        val overlayBackdrop = currentGlassOverlayBackdrop() ?: pageBackdrop
        val select = rememberConfirmedSelection { modelId ->
            showPopup = false
            if (modelId != null) onModelSelected(modelId)
        }
        val selectedId = select.pending?.let { it.modelId } ?: state.selectedModel?.id
        val showGroupLabels = state.providerGroups.size > 1

        TopLayer {
            GlassTransformPopup(
                show = showPopup,
                onDismissRequest = { showPopup = false },
                anchor = popupAnchor,
                backdrop = overlayBackdrop,
                sizing = GlassPopupSizing(
                    minWidth = ModelPopupWidth,
                    maxWidth = 300.dp,
                    maxHeight = 380.dp,
                ),
                horizontalAlignment = Alignment.CenterHorizontally,
                anchorContent = {
                    when {
                        character != null -> {
                            val headerTitle = conversationTitle?.takeIf(String::isNotBlank) ?: character.characterName
                            Column(modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)) {
                                Text(
                                    text = headerTitle,
                                    style = MiuixTheme.textStyles.body1.copy(
                                        fontSize = 15.5.sp,
                                        lineHeight = 19.sp,
                                        fontWeight = FontWeight.SemiBold,
                                    ),
                                    color = MiuixTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                ModelSubtitleRow(
                                    selected = selected,
                                    modelName = currentModel,
                                    textColor = subtitleColor,
                                    chevronColor = chevronColor,
                                    showChevron = enabled,
                                )
                            }
                        }
                        !conversationTitle.isNullOrBlank() -> {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                            ) {
                                Text(
                                    text = conversationTitle,
                                    style = MiuixTheme.textStyles.body1.copy(
                                        fontSize = 15.5.sp,
                                        lineHeight = 19.sp,
                                        fontWeight = FontWeight.SemiBold,
                                    ),
                                    color = MiuixTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                ModelSubtitleRow(
                                    selected = selected,
                                    modelName = currentModel,
                                    textColor = subtitleColor,
                                    chevronColor = chevronColor,
                                    showChevron = enabled,
                                )
                            }
                        }
                        else -> {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .heightIn(min = 40.dp)
                                    .padding(start = 13.dp, end = if (enabled) 10.dp else 14.dp, top = 8.dp, bottom = 8.dp),
                            ) {
                                ModelBrandMark(
                                    modelId = selected?.modelId,
                                    sourceType = selected?.providerSourceType,
                                    size = 15.dp,
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = currentModel,
                                    style = MiuixTheme.textStyles.body2.copy(
                                        fontSize = 14.sp,
                                        lineHeight = 18.sp,
                                        fontWeight = FontWeight.Medium,
                                    ),
                                    color = MiuixTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.widthIn(max = 180.dp),
                                )
                                if (enabled) {
                                    Spacer(modifier = Modifier.width(2.dp))
                                    Icon(
                                        imageVector = Icons.Rounded.KeyboardArrowDown,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = chevronColor,
                                    )
                                }
                            }
                        }
                    }
                },
            ) {
                state.providerGroups.forEachIndexed { groupIndex, group ->
                    if (showGroupLabels && groupIndex > 0) {
                        HorizontalDivider(
                            modifier = Modifier.padding(
                                horizontal = GlassPopupDefaults.DividerPaddingHorizontal,
                                vertical = GlassPopupDefaults.DividerPaddingVertical,
                            ),
                            thickness = GlassPopupDefaults.DividerThickness,
                        )
                    }
                    group.models.forEach { model ->
                        GlassModelPopupItem(
                            model = model,
                            summary = if (showGroupLabels) group.providerName else null,
                            selected = model.id == selectedId,
                            onClick = { select(model.id) },
                        )
                    }
                }
            }
        }

        GlassBottomSheet(
            show = showSheet,
            backdrop = overlayBackdrop,
            progressiveBlur = false,
            title = stringResource(R.string.model_picker_title),
            onDismissRequest = { showSheet = false },
            insideMargin = DpSize(0.dp, 0.dp),
        ) { _ ->
            key(sheetSession) {
                ModelSheetContent(
                    state = state,
                    onModelSelected = { modelId ->
                        showSheet = false
                        onModelSelected(modelId)
                    },
                )
            }
        }
    }
}

@Composable
private fun GlassModelPopupItem(
    model: AgentModelOptionUi,
    summary: String?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val pressedColor = GlassPopupDefaults.itemPressedColor()
    val pressFill by animateColorAsState(
        targetValue = if (pressed) pressedColor else Color.Transparent,
        animationSpec = if (pressed) snap() else tween(durationMillis = 150),
        label = "glassModelPopupItemPress",
    )
    val selectedColor = MiuixTheme.colorScheme.primary
    val textColor = if (selected) selectedColor else MiuixTheme.colorScheme.onSurfaceContainer
    val summaryColor = MiuixTheme.colorScheme.onSurfaceContainerVariant

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = GlassPopupDefaults.ItemMinHeight)
            .padding(horizontal = GlassPopupDefaults.ItemPressInset)
            .drawBehind {
                drawRoundRect(
                    color = pressFill,
                    cornerRadius = CornerRadius(GlassPopupDefaults.ItemPressRadius.toPx()),
                )
            }
            .selectable(
                selected = selected,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(
                horizontal = GlassPopupDefaults.ItemPaddingHorizontal - GlassPopupDefaults.ItemPressInset,
                vertical = GlassPopupDefaults.ItemPaddingVertical,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ModelBrandMark(
            modelId = model.modelId,
            sourceType = model.providerSourceType,
            size = 20.dp,
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = model.displayName,
                fontSize = MiuixTheme.textStyles.body1.fontSize,
                fontWeight = FontWeight.Medium,
                color = textColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (summary != null) {
                Text(
                    text = summary,
                    style = MiuixTheme.textStyles.body2,
                    color = summaryColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Icon(
            imageVector = MiuixIcons.Basic.Check,
            contentDescription = null,
            modifier = Modifier
                .padding(start = GlassPopupDefaults.ItemIconGap)
                .size(GlassPopupDefaults.ItemTickSize),
            tint = if (selected) selectedColor else Color.Transparent,
        )
    }
}

@Composable
private fun ModelSubtitleRow(
    selected: AgentModelOptionUi?,
    modelName: String,
    textColor: Color,
    chevronColor: Color,
    showChevron: Boolean,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = 1.dp),
    ) {
        ModelBrandMark(
            modelId = selected?.modelId,
            sourceType = selected?.providerSourceType,
            size = 11.dp,
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = modelName,
            style = MiuixTheme.textStyles.footnote2.copy(
                fontSize = 11.5.sp,
                lineHeight = 14.sp,
            ),
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 168.dp),
        )
        if (showChevron) {
            Icon(
                imageVector = Icons.Rounded.KeyboardArrowDown,
                contentDescription = null,
                modifier = Modifier.size(13.dp),
                tint = chevronColor,
            )
        }
    }
}

internal data class ModelPickerFollowOption(
    val title: String,
    val subtitle: String?,
    val modelId: String?,
    val sourceType: String?,
)

private sealed interface ModelSheetEntry {
    val key: String

    data class Follow(val option: ModelPickerFollowOption) : ModelSheetEntry {
        override val key: String get() = "follow_primary"
    }

    data class Header(val providerId: String, val name: String) : ModelSheetEntry {
        override val key: String get() = "header:$providerId"
    }

    data class Option(val model: AgentModelOptionUi) : ModelSheetEntry {
        override val key: String get() = "model:${model.providerId}:${model.id}"
    }
}

@Composable
private fun ModelSheetContent(
    state: AgentModelPickerUiState,
    onModelSelected: (String) -> Unit,
) {
    ModelPickerSheetContent(
        pickerState = state,
        selectedModelId = state.selectedModel?.id,
        followOption = null,
        onSelectModel = { modelId ->
            if (modelId != null) onModelSelected(modelId)
        },
    )
}

@Composable
internal fun ModelPickerSheetContent(
    pickerState: AgentModelPickerUiState,
    selectedModelId: String?,
    followOption: ModelPickerFollowOption?,
    onSelectModel: (String?) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var providerFilter by remember { mutableStateOf<String?>(null) }
    val entries = remember(pickerState.providerGroups, query, providerFilter, followOption) {
        val followEntries = if (followOption != null && query.isBlank() && providerFilter == null) {
            listOf(ModelSheetEntry.Follow(followOption))
        } else {
            emptyList()
        }
        val groupEntries = pickerState.filterGroups(query)
            .filter { providerFilter == null || it.providerId == providerFilter }
            .flatMap { group ->
                // 只看一个服务商时，筛选标签已经说明了归属，分组标题只会重复。
                val header = if (providerFilter == null) {
                    listOf(ModelSheetEntry.Header(group.providerId, group.providerName))
                } else {
                    emptyList()
                }
                header + group.models.map(ModelSheetEntry::Option)
            }
        followEntries + groupEntries
    }
    val selectedIndex = entries.indexOfFirst { entry ->
        when (entry) {
            is ModelSheetEntry.Follow -> selectedModelId == null
            is ModelSheetEntry.Option -> entry.model.id == selectedModelId
            is ModelSheetEntry.Header -> false
        }
    }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (selectedIndex - 2).coerceAtLeast(0))
    LaunchedEffect(listState) {
        snapshotFlow { query to providerFilter }.drop(1).collect { listState.scrollToItem(0) }
    }
    val select = rememberConfirmedSelection(onSelectModel)
    val effectiveSelectedId = select.pending?.let { it.modelId } ?: selectedModelId
    val isFollowSelected = if (select.pending != null) {
        select.pending?.modelId == null
    } else {
        selectedModelId == null
    }
    val density = LocalDensity.current
    val sheetHeight = with(density) { LocalWindowInfo.current.containerSize.height.toDp() } * 0.68f

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(sheetHeight)
            .navigationBarsPadding(),
    ) {
        InputField(
            query = query,
            onQueryChange = { query = it },
            onSearch = {},
            expanded = false,
            onExpandedChange = {},
            label = stringResource(R.string.ui_search_model_df5586),
            color = glassOverlayFieldBackgroundColor(),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(top = 4.dp, bottom = 8.dp),
        )
        if (pickerState.providerGroups.size > 1) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(bottom = 8.dp),
            ) {
                item(key = "all") {
                    ChoiceChip(
                        text = stringResource(R.string.model_picker_filter_all),
                        selected = providerFilter == null,
                        onClick = { providerFilter = null },
                    )
                }
                items(pickerState.providerGroups, key = { it.providerId }) { group ->
                    ChoiceChip(
                        text = group.providerName,
                        selected = providerFilter == group.providerId,
                        onClick = {
                            providerFilter = if (providerFilter == group.providerId) null else group.providerId
                        },
                    )
                }
            }
        }
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(top = 2.dp, bottom = 16.dp),
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            if (entries.isEmpty()) {
                item(key = "empty") {
                    Text(
                        text = stringResource(R.string.page_no_matching_model_found_ae7e96),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 48.dp),
                    )
                }
            }
            items(entries, key = ModelSheetEntry::key, contentType = { it::class }) { entry ->
                when (entry) {
                    is ModelSheetEntry.Follow -> ModelPickerOptionRow(
                        title = entry.option.title,
                        subtitle = entry.option.subtitle,
                        modelId = entry.option.modelId,
                        sourceType = entry.option.sourceType,
                        selected = isFollowSelected,
                        onClick = { select(null) },
                    )
                    is ModelSheetEntry.Header -> ModelProviderLabel(entry.name)
                    is ModelSheetEntry.Option -> ModelPickerOptionRow(
                        title = entry.model.displayName,
                        subtitle = entry.model.modelId.takeIf {
                            !it.equals(entry.model.displayName, ignoreCase = true)
                        },
                        modelId = entry.model.modelId,
                        sourceType = entry.model.providerSourceType,
                        selected = entry.model.id == effectiveSelectedId,
                        onClick = { select(entry.model.id) },
                    )
                }
            }
        }
    }
}

private class PendingModelSelection(val modelId: String?)

/**
 * 点选后先把对勾移到新模型上，停留片刻再收起弹层，让人看清选中了谁。
 * 弹层收起会让作用域随组合一起离开，未完成的确认随之取消，不会回写已关闭的界面。
 */
private class ConfirmedSelection(
    private val onConfirm: (String?) -> Unit,
    private val launch: (suspend () -> Unit) -> Unit,
) {
    var pending by mutableStateOf<PendingModelSelection?>(null)
        private set

    operator fun invoke(modelId: String?) {
        if (pending != null) return
        pending = PendingModelSelection(modelId)
        launch {
            try {
                delay(SelectionConfirmMs)
                onConfirm(modelId)
                // 等待关闭动画完成后再清状态，避免淡出期间对勾回跳，也防止未销毁宿主再次打开时锁定点击。
                delay(400L)
            } finally {
                pending = null
            }
        }
    }
}

@Composable
private fun rememberConfirmedSelection(onConfirm: (String?) -> Unit): ConfirmedSelection {
    val scope = rememberCoroutineScope()
    val currentOnConfirm by rememberUpdatedState(onConfirm)
    return remember { ConfirmedSelection({ currentOnConfirm(it) }) { block -> scope.launch { block() } } }
}

@Composable
private fun ModelProviderLabel(name: String, horizontalPadding: Dp = 24.dp) {
    Text(
        text = name,
        style = MiuixTheme.textStyles.footnote1,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding)
            .padding(top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun ModelPickerOptionRow(
    title: String,
    subtitle: String?,
    modelId: String?,
    sourceType: String?,
    selected: Boolean,
    onClick: () -> Unit,
    horizontalPadding: Dp = 12.dp,
) {
    val isDark = MiuixTheme.colorScheme.background.luminance() < 0.5f
    val containerColor = if (selected) {
        MiuixTheme.colorScheme.primary.copy(alpha = if (isDark) 0.16f else 0.08f)
    } else {
        Color.Transparent
    }
    val contentColor = if (selected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface
    val checkIcon = MiuixIcons.Glass.Ok
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding, vertical = 2.dp)
            .then(
                if (selected) {
                    Modifier.squircleSurface(color = containerColor, cornerRadius = 14.dp)
                } else {
                    Modifier.clip(RoundedCornerShape(14.dp))
                },
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ModelBrandMark(
            modelId = modelId,
            sourceType = sourceType,
            size = 20.dp,
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MiuixTheme.textStyles.body1,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(1.dp))
                Text(
                    text = subtitle,
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (selected) {
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                imageVector = checkIcon,
                contentDescription = stringResource(R.string.ui_current_model_a0af8f),
                modifier = Modifier.size(18.dp),
                tint = contentColor,
            )
        }
    }
}

@Composable
internal fun ModelBrandMark(
    modelId: String?,
    sourceType: String?,
    size: Dp,
) {
    val logo = modelOrProviderBrandLogoRes(modelId, sourceType)
    if (logo != null) {
        Image(
            painter = painterResource(logo),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .size(size)
                .clip(CircleShape),
        )
    } else {
        Box(
            modifier = Modifier
                .size(size)
                .background(MiuixTheme.colorScheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.Dns,
                contentDescription = null,
                modifier = Modifier.size(size * 0.56f),
                tint = MiuixTheme.colorScheme.primary,
            )
        }
    }
}

private val ModelPopupWidth = 248.dp

/** 不超过这个数量时弹窗一眼就能看完，不需要搜索。 */
private const val ModelPopupLimit = 8

private const val SelectionConfirmMs = 140L
