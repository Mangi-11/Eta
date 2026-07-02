package fuck.andes.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.R as LucideR
import fuck.andes.ui.model.AgentChatMessageUi
import fuck.andes.ui.model.PendingImageUi
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 聊天主体：消息流 + 底部输入框。
 */
@Composable
fun AgentChatBody(
    messages: List<AgentChatMessageUi>,
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
    onSuggestionClick: (String) -> Unit,
    onRunTraceClick: () -> Unit,
    isDrawerOpen: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberLazyListState()
    val keyboard = LocalSoftwareKeyboardController.current

    val displayMessages = messages.asReversed()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            scrollState.scrollToItem(0)
        }
    }

    var sentFromKeyboard by remember { mutableStateOf(false) }
    LaunchedEffect(isStreaming) {
        if (isStreaming && sentFromKeyboard) {
            keyboard?.hide()
            sentFromKeyboard = false
        }
    }

    LaunchedEffect(isDrawerOpen) {
        if (isDrawerOpen) {
            keyboard?.hide()
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        if (messages.isEmpty()) {
            EmptyChatState(
                onSuggestionClick = onSuggestionClick,
                modifier = Modifier.weight(1f),
            )
        } else {
            LazyColumn(
                state = scrollState,
                reverseLayout = true,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(top = 12.dp, bottom = 12.dp),
            ) {
                items(
                    items = displayMessages,
                    key = { it.id },
                ) { message ->
                    ChatMessageItem(
                        message = message,
                        onSuggestionClick = onSuggestionClick,
                        onRunTraceClick = onRunTraceClick,
                    )
                }
            }
        }
        AgentChatInputBar(
            input = input,
            isStreaming = isStreaming,
            thinkingEnabled = thinkingEnabled,
            pendingImages = pendingImages,
            onInputChange = onInputChange,
            onThinkingChange = onThinkingChange,
            onSend = {
                sentFromKeyboard = true
                onSend()
            },
            onStop = onStop,
            onAttachImage = onAttachImage,
            onRemoveImage = onRemoveImage,
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding(),
        )
    }
}

@Composable
private fun EmptyChatState(
    onSuggestionClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val suggestions = listOf(
        SuggestionItem(
            title = "分析当前屏幕",
            description = "截图并描述当前屏幕",
            iconRes = LucideR.drawable.lucide_ic_scan_text,
            prompt = "截图并描述当前屏幕",
            highlighted = false,
        ),
        SuggestionItem(
            title = "打开微信",
            description = "快速启动微信应用",
            iconRes = LucideR.drawable.lucide_ic_rocket,
            prompt = "帮我打开微信",
            highlighted = false,
        ),
        SuggestionItem(
            title = "查看内存压力",
            description = "读取 /proc/meminfo 和 /proc/pressure/，总结内存与系统压力",
            iconRes = LucideR.drawable.lucide_ic_search,
            prompt = "读取 /proc/meminfo 和 /proc/pressure/，重点分析 PSI（Pressure Stall Information）指标，总结当前内存压力和系统状态",
            highlighted = false,
        ),
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 32.dp),
    ) {
        // Kimi-style avatar
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.12f))
                .padding(11.dp),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(LucideR.drawable.lucide_ic_bot),
                contentDescription = null,
                modifier = Modifier.size(26.dp),
                tint = MiuixTheme.colorScheme.primary,
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "有什么可以帮你？",
            style = MiuixTheme.textStyles.headline1,
            color = MiuixTheme.colorScheme.onSurface,
        )

        Spacer(modifier = Modifier.height(20.dp))

        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            suggestions.forEach { item ->
                SuggestionCard(
                    item = item,
                    onClick = { onSuggestionClick(item.prompt) },
                )
            }
        }
    }
}

@Composable
private fun SuggestionCard(
    item: SuggestionItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bgColor = if (item.highlighted) {
        MiuixTheme.colorScheme.primary.copy(alpha = 0.08f)
    } else {
        MiuixTheme.colorScheme.surface
    }
    val borderColor = if (item.highlighted) {
        MiuixTheme.colorScheme.primary.copy(alpha = 0.16f)
    } else {
        MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.12f)
    }
    val contentColor = if (item.highlighted) {
        MiuixTheme.colorScheme.primary
    } else {
        MiuixTheme.colorScheme.onSurface
    }

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(bgColor)
            .border(0.5.dp, borderColor, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (item.highlighted) {
            Icon(
                painter = painterResource(item.iconRes),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = contentColor,
            )
            Spacer(modifier = Modifier.width(10.dp))
        }
        Text(
            text = item.title,
            style = MiuixTheme.textStyles.body1,
            color = contentColor,
        )
    }
}

private data class SuggestionItem(
    val title: String,
    val description: String,
    val iconRes: Int,
    val prompt: String,
    val highlighted: Boolean = false,
)

