package io.github.mangi.eta.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.model.QueuedMessageUi
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.squircle.squircleBorder
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val QueueContainerCornerRadius = 16.dp
private val QueueActionSize = 28.dp
private val QueueExpandedMaxHeight = 148.dp
private val WHITESPACE_REGEX = Regex("\\s+")

/**
 * 悬浮在输入栏正上方的排队消息胶囊。
 *
 * - 单条时保持单行极简形态；多条时默认展示队首预览与计数，点击计数可展开管理全部条目。
 * - 点击任意排队消息正文即可将其撤回输入框继续修改，无需内嵌二次输入框。
 */
@Composable
internal fun AgentChatQueueStrip(
    queuedMessages: List<QueuedMessageUi>,
    isStreaming: Boolean,
    onSteer: (String) -> Unit,
    onRecall: (String) -> Unit,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (queuedMessages.isEmpty()) return

    var expanded by rememberSaveable { mutableStateOf(false) }
    val hasMultiple = queuedMessages.size > 1
    LaunchedEffect(hasMultiple) {
        if (!hasMultiple) expanded = false
    }
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(durationMillis = 180),
        label = "queue_chevron",
    )
    val first = queuedMessages.first()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .squircleSurface(
                color = MiuixTheme.colorScheme.surfaceContainer,
                cornerRadius = QueueContainerCornerRadius,
            )
            .squircleBorder(
                width = 0.5.dp,
                color = MiuixTheme.colorScheme.outline.copy(alpha = 0.55f),
                cornerRadius = QueueContainerCornerRadius,
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .then(
                        if (hasMultiple) {
                            Modifier.clickable { expanded = !expanded }
                        } else {
                            Modifier
                        },
                    )
                    .padding(horizontal = 2.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(MiuixTheme.colorScheme.primary),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = when {
                        first.immediateInterrupt -> stringResource(R.string.chat_queue_interrupting)
                        hasMultiple -> stringResource(R.string.chat_queue_status_count, queuedMessages.size)
                        else -> stringResource(R.string.chat_queue_status)
                    },
                    style = MiuixTheme.textStyles.footnote1,
                    fontWeight = FontWeight.Medium,
                    color = MiuixTheme.colorScheme.primary,
                )
                if (hasMultiple) {
                    Spacer(modifier = Modifier.width(2.dp))
                    Icon(
                        imageVector = Icons.Rounded.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier
                            .size(14.dp)
                            .rotate(chevronRotation),
                        tint = MiuixTheme.colorScheme.primary,
                    )
                }
            }

            if (!expanded) {
                Spacer(modifier = Modifier.width(8.dp))
                QueuedMessagePreviewText(
                    item = first,
                    onClick = { onRecall(first.id) },
                    modifier = Modifier.weight(1f),
                )
                QueuedMessageItemActions(
                    item = first,
                    isStreaming = isStreaming,
                    onSteer = { onSteer(first.id) },
                    onRemove = { onRemove(first.id) },
                )
            } else {
                Spacer(modifier = Modifier.weight(1f))
            }
        }

        AnimatedVisibility(
            visible = expanded && hasMultiple,
            enter = fadeIn(tween(150)) + expandVertically(tween(180)),
            exit = fadeOut(tween(110)) + shrinkVertically(tween(150)),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = QueueExpandedMaxHeight)
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 4.dp),
            ) {
                queuedMessages.forEachIndexed { index, item ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp)
                            .height(0.5.dp)
                            .background(MiuixTheme.colorScheme.outline.copy(alpha = 0.25f)),
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 14.dp, end = 6.dp, top = 3.dp, bottom = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "${index + 1}",
                            style = MiuixTheme.textStyles.footnote2,
                            fontWeight = FontWeight.Medium,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        QueuedMessagePreviewText(
                            item = item,
                            onClick = { onRecall(item.id) },
                            modifier = Modifier.weight(1f),
                        )
                        QueuedMessageItemActions(
                            item = item,
                            isStreaming = isStreaming,
                            onSteer = { onSteer(item.id) },
                            onRemove = { onRemove(item.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QueuedMessagePreviewText(
    item: QueuedMessageUi,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val singleLine = item.content.replace(WHITESPACE_REGEX, " ").trim()
    val attachmentCount = item.pendingImages.size + item.pendingFileReferences.size
    val displayText = when {
        singleLine.isNotEmpty() -> singleLine
        item.pendingImages.isNotEmpty() -> stringResource(R.string.chat_queue_images_only, item.pendingImages.size)
        item.pendingFileReferences.isNotEmpty() -> stringResource(R.string.chat_queue_files_only, item.pendingFileReferences.size)
        else -> ""
    }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        Text(
            text = displayText,
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (singleLine.isNotEmpty() && attachmentCount > 0) {
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "+$attachmentCount",
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

@Composable
private fun QueuedMessageItemActions(
    item: QueuedMessageUi,
    isStreaming: Boolean,
    onSteer: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (!item.immediateInterrupt) {
            IconButton(
                onClick = onSteer,
                minWidth = QueueActionSize,
                minHeight = QueueActionSize,
            ) {
                Icon(
                    imageVector = if (isStreaming) Icons.Rounded.Bolt else Icons.Rounded.ArrowUpward,
                    contentDescription = stringResource(R.string.chat_queue_steer_now),
                    modifier = Modifier.size(15.dp),
                    tint = MiuixTheme.colorScheme.primary,
                )
            }
        }
        IconButton(
            onClick = onRemove,
            minWidth = QueueActionSize,
            minHeight = QueueActionSize,
        ) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = stringResource(R.string.chat_queue_remove),
                modifier = Modifier.size(14.dp),
                tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
            )
        }
    }
}
