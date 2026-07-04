package fuck.andes.ui.components

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.R as LucideR
import fuck.andes.ui.model.PendingImageUi
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.overlay.OverlayListPopup
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val ATTACH_MENU_ITEMS = listOf("添加图片")
private val InputIconSize = 24.dp
private val InputCardHorizontalPadding = 4.dp

@Composable
fun AgentChatInputBar(
    input: String,
    isStreaming: Boolean,
    thinkingEnabled: Boolean,
    pendingImages: List<PendingImageUi>,
    onInputChange: (String) -> Unit,
    onThinkingChange: (Boolean) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onAttachImage: (String) -> Unit,
    onRemoveImage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            onAttachImage(uri.toString())
        }
    }

    var showAttachPopup by remember { mutableStateOf(false) }
    var isFocused by remember { mutableStateOf(false) }

    // CRITICAL: The card IMMEDIATELY expands when clicking (isFocused) OR when text is entered, OR streaming!
    val isExpanded = isFocused || input.isNotBlank() || pendingImages.isNotEmpty() || isStreaming

    // Spring Animations for beautiful horizontal scaling of side icons
    val leadingWidth by animateDpAsState(
        targetValue = if (isExpanded) 0.dp else 36.dp,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "leading"
    )

    val trailingWidth by animateDpAsState(
        targetValue = if (isExpanded) 0.dp else 36.dp,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "trailing"
    )

    val topButtonsAlpha by animateFloatAsState(
        targetValue = if (isExpanded) 0f else 1f,
        animationSpec = tween(durationMillis = 200),
        label = "alpha"
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MiuixTheme.colorScheme.surface)
            .padding(start = 16.dp, top = 2.dp, end = 16.dp, bottom = 18.dp),
    ) {
        // Pending attached images row (sits beautifully above the input card)
        if (pendingImages.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                pendingImages.forEach { image ->
                    Box(
                        modifier = Modifier
                            .size(60.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(MiuixTheme.colorScheme.surfaceContainer)
                            .border(
                                0.5.dp,
                                MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.1f),
                                RoundedCornerShape(10.dp)
                            ),
                    ) {
                        val bitmap = rememberDataUrlBitmap(image.dataUrl)
                        if (bitmap != null) {
                            Image(
                                bitmap = bitmap,
                                contentDescription = null,
                                modifier = Modifier.fillMaxWidth(),
                                contentScale = ContentScale.Crop,
                            )
                        }
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(2.dp)
                                .size(16.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.5f))
                                .clickable { onRemoveImage(image.id) },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                painter = painterResource(LucideR.drawable.lucide_ic_x),
                                contentDescription = "移除图片",
                                modifier = Modifier.size(10.dp),
                                tint = Color.White
                            )
                        }
                    }
                }
            }
        }

        // Kimi-style Premium Rounded Rectangle Card (ALWAYS RoundedCornerShape of 16.dp, NEVER a pill!)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(MiuixTheme.colorScheme.surfaceContainer)
                .border(
                    width = 0.5.dp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.08f),
                    shape = RoundedCornerShape(16.dp)
                )
                .padding(horizontal = InputCardHorizontalPadding, vertical = 6.dp)
        ) {
            // Row 1 (Top / Collapsed Row): Dynamic width allocation
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left spacing & button (collapses horizontally when expanded)
                Box(
                    modifier = Modifier
                        .width(leadingWidth)
                        .height(36.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    if (topButtonsAlpha > 0.05f) {
                        Box(modifier = Modifier.graphicsLayer(alpha = topButtonsAlpha)) {
                            IconButton(
                                onClick = { showAttachPopup = true },
                            ) {
                                Icon(
                                    painter = painterResource(LucideR.drawable.lucide_ic_circle_plus),
                                    contentDescription = "附件",
                                    modifier = Modifier.size(InputIconSize),
                                    tint = MiuixTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }

                // Center Text input (grows to full width when side spacing collapses to 0)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = 36.dp)
                        .padding(horizontal = 4.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    if (input.isBlank()) {
                        Text(
                            text = "尽管问，带图也行",
                            style = MiuixTheme.textStyles.body1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.5f)
                        )
                    }
                    BasicTextField(
                        value = input,
                        onValueChange = onInputChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusChanged { isFocused = it.isFocused },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                        textStyle = TextStyle(
                            color = MiuixTheme.colorScheme.onSurface,
                            fontSize = 16.sp
                        ),
                        cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
                        maxLines = 6,
                        minLines = 1
                    )
                }

                // Right spacing & sparkles button (collapses horizontally when expanded)
                Box(
                    modifier = Modifier
                        .width(trailingWidth)
                        .height(36.dp),
                    contentAlignment = Alignment.CenterEnd
                ) {
                    if (topButtonsAlpha > 0.05f) {
                        Box(modifier = Modifier.graphicsLayer(alpha = topButtonsAlpha)) {
                            IconButton(
                                onClick = { onThinkingChange(!thinkingEnabled) },
                                enabled = !isStreaming,
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    painter = painterResource(LucideR.drawable.lucide_ic_atom),
                                    contentDescription = if (thinkingEnabled) "关闭思考" else "开启思考",
                                    modifier = Modifier.size(InputIconSize),
                                    tint = if (thinkingEnabled) {
                                        MiuixTheme.colorScheme.primary
                                    } else {
                                        MiuixTheme.colorScheme.onSurface
                                    },
                                )
                            }
                        }
                    }
                }
            }

            // Row 2 (Bottom Actions Row): Slides down and fades in when active/expanded
            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically(animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) + fadeIn(),
                exit = shrinkVertically(animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) + fadeOut()
            ) {
                Column {
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        // Left Action: Attachment Plus Button
                        Box {
                            IconButton(
                                onClick = { showAttachPopup = true },
                            ) {
                                Icon(
                                    painter = painterResource(LucideR.drawable.lucide_ic_circle_plus),
                                    contentDescription = "附件",
                                    modifier = Modifier.size(InputIconSize),
                                    tint = MiuixTheme.colorScheme.onSurface
                                )
                            }
                            OverlayListPopup(
                                show = showAttachPopup,
                                alignment = PopupPositionProvider.Align.BottomStart,
                                onDismissRequest = { showAttachPopup = false },
                                content = {
                                    ListPopupColumn {
                                        ATTACH_MENU_ITEMS.forEachIndexed { index, label ->
                                            DropdownImpl(
                                                text = label,
                                                optionSize = ATTACH_MENU_ITEMS.size,
                                                isSelected = false,
                                                index = index,
                                                onSelectedIndexChange = {
                                                    showAttachPopup = false
                                                    photoPicker.launch(
                                                        PickVisualMediaRequest(
                                                            ActivityResultContracts.PickVisualMedia.ImageOnly
                                                        )
                                                    )
                                                },
                                            )
                                        }
                                    }
                                },
                            )
                        }

                        // Right Actions: Sparkles Thinking Toggle & Send/Stop Button
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // Sparkles Thinking Toggle
                            IconButton(
                                onClick = { onThinkingChange(!thinkingEnabled) },
                                enabled = !isStreaming,
                            ) {
                                Icon(
                                    painter = painterResource(LucideR.drawable.lucide_ic_atom),
                                    contentDescription = if (thinkingEnabled) "关闭思考" else "开启思考",
                                    modifier = Modifier.size(InputIconSize),
                                    tint = if (thinkingEnabled) {
                                        MiuixTheme.colorScheme.primary
                                    } else {
                                        MiuixTheme.colorScheme.onSurface
                                    },
                                )
                            }

                            // Send/Stop button using ready-made Lucide circle icons
                            val showSendOrStop = input.isNotBlank() || pendingImages.isNotEmpty() || isStreaming
                            AnimatedVisibility(
                                visible = showSendOrStop,
                                enter = fadeIn(),
                                exit = fadeOut()
                            ) {
                                if (isStreaming) {
                                    IconButton(
                                        onClick = { onStop() },
                                    ) {
                                        Icon(
                                            painter = painterResource(LucideR.drawable.lucide_ic_circle_stop),
                                            contentDescription = "停止",
                                            modifier = Modifier.size(InputIconSize),
                                            tint = MiuixTheme.colorScheme.onSurface
                                        )
                                    }
                                } else {
                                    IconButton(
                                        onClick = { onSend() },
                                    ) {
                                        Icon(
                                            painter = painterResource(LucideR.drawable.lucide_ic_circle_arrow_up),
                                            contentDescription = "发送",
                                            modifier = Modifier.size(InputIconSize),
                                            tint = MiuixTheme.colorScheme.onSurface
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
