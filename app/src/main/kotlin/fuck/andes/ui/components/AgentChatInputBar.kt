package fuck.andes.ui.components

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.R as LucideR
import fuck.andes.ui.model.PendingImageUi
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.squircle.squircleBorder
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val InputIconSize = 20.dp
private val SendButtonVisualSize = 30.dp

/**
 * Agent 输入器始终保持同一空间结构，聚焦、输入和执行过程只改变状态，不搬动操作入口。
 */
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
        if (uri != null) onAttachImage(uri.toString())
    }
    val canSend = input.isNotBlank() || pendingImages.isNotEmpty()

    Column(
        modifier = modifier
            .fillMaxWidth(),
    ) {
        if (pendingImages.isNotEmpty()) {
            PendingImageStrip(
                images = pendingImages,
                onRemoveImage = onRemoveImage,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .squircleSurface(
                    color = MiuixTheme.colorScheme.surfaceContainer,
                    cornerRadius = 18.dp,
                )
                .squircleBorder(
                    width = 0.5.dp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantActions.copy(alpha = 0.16f),
                    cornerRadius = 18.dp,
                )
                .padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 36.dp)
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                contentAlignment = Alignment.TopStart,
            ) {
                if (input.isBlank()) {
                    Text(
                        text = if (isStreaming) "Eta 正在执行…" else "交给 Eta 去完成",
                        style = MiuixTheme.textStyles.body1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
                BasicTextField(
                    value = input,
                    onValueChange = onInputChange,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                    textStyle = TextStyle(
                        color = MiuixTheme.colorScheme.onSurface,
                        fontSize = 16.sp,
                        lineHeight = 22.sp,
                    ),
                    cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
                    maxLines = 6,
                    minLines = 1,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    IconButton(
                        onClick = {
                            photoPicker.launch(
                                PickVisualMediaRequest(
                                    ActivityResultContracts.PickVisualMedia.ImageOnly
                                )
                            )
                        },
                        minWidth = 36.dp,
                        minHeight = 36.dp,
                    ) {
                        Icon(
                            painter = painterResource(LucideR.drawable.lucide_ic_image_plus),
                            contentDescription = "添加图片",
                            modifier = Modifier.size(InputIconSize),
                            tint = MiuixTheme.colorScheme.onSurface,
                        )
                    }

                    // Keep the secondary action visually equivalent to the image action:
                    // same hit target and icon scale, with the selected state carried by tint.
                    IconButton(
                        onClick = { onThinkingChange(!thinkingEnabled) },
                        enabled = !isStreaming,
                        minWidth = 36.dp,
                        minHeight = 36.dp,
                        cornerRadius = 18.dp,
                    ) {
                        Icon(
                            painter = painterResource(LucideR.drawable.lucide_ic_sparkles),
                            contentDescription = if (thinkingEnabled) "关闭思考模式" else "开启思考模式",
                            modifier = Modifier.size(InputIconSize),
                            tint = if (thinkingEnabled) {
                                MiuixTheme.colorScheme.primary
                            } else {
                                MiuixTheme.colorScheme.onSurface
                            },
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (isStreaming) {
                        Text(
                            text = "正在执行",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                    IconButton(
                        onClick = if (isStreaming) onStop else onSend,
                        enabled = isStreaming || canSend,
                        minWidth = 36.dp,
                        minHeight = 36.dp,
                    ) {
                        // The 36dp outer button remains easy to hit; only the visible
                        // circle is reduced so it has the same optical weight as the
                        // adjacent 20dp toolbar icons.
                        Box(
                            modifier = Modifier
                                .size(SendButtonVisualSize)
                                .clip(CircleShape)
                                .background(
                                    when {
                                        isStreaming -> MiuixTheme.colorScheme.onSurface
                                        canSend -> MiuixTheme.colorScheme.primary
                                        else -> MiuixTheme.colorScheme.surfaceContainerHigh
                                    }
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                painter = painterResource(
                                    if (isStreaming) {
                                        LucideR.drawable.lucide_ic_square
                                    } else {
                                        LucideR.drawable.lucide_ic_arrow_up
                                    }
                                ),
                                contentDescription = if (isStreaming) "停止" else "发送",
                                modifier = Modifier.size(if (isStreaming) 11.dp else 16.dp),
                                tint = when {
                                    isStreaming -> MiuixTheme.colorScheme.surface
                                    canSend -> MiuixTheme.colorScheme.onPrimary
                                    else -> MiuixTheme.colorScheme.onSurfaceVariantActions
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PendingImageStrip(
    images: List<PendingImageUi>,
    onRemoveImage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        images.forEach { image ->
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MiuixTheme.colorScheme.surfaceContainer),
            ) {
                rememberDataUrlBitmap(image.dataUrl)?.let { bitmap ->
                    Image(
                        bitmap = bitmap,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(3.dp)
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.58f))
                        .clickable { onRemoveImage(image.id) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(LucideR.drawable.lucide_ic_x),
                        contentDescription = "移除图片",
                        modifier = Modifier.size(11.dp),
                        tint = Color.White,
                    )
                }
            }
        }
    }
}
