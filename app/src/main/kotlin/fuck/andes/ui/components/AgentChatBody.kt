package fuck.andes.ui.components

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imeNestedScroll
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import fuck.andes.ui.model.AgentChatMessageUi
import top.yukonga.miuix.kmp.basic.Scaffold

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AgentChatBody(
    messages: List<AgentChatMessageUi>,
    input: String,
    isStreaming: Boolean,
    thinkingEnabled: Boolean,
    onInputChange: (String) -> Unit,
    onThinkingChange: (Boolean) -> Unit,
    onSend: () -> Unit,
    onAttach: () -> Unit,
    onScreenContext: () -> Unit,
    onVoice: () -> Unit,
    onSuggestionClick: (String) -> Unit,
    onRunTraceClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            AgentChatInputBar(
                input = input,
                isStreaming = isStreaming,
                thinkingEnabled = thinkingEnabled,
                onInputChange = onInputChange,
                onThinkingChange = onThinkingChange,
                onSend = onSend,
                onAttach = onAttach,
                onScreenContext = onScreenContext,
                onVoice = onVoice,
                modifier = Modifier
                    .navigationBarsPadding()
                    .imePadding(),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .consumeWindowInsets(padding)
                .imeNestedScroll(),
            contentPadding = PaddingValues(
                top = 12.dp,
                bottom = padding.calculateBottomPadding() + 12.dp,
            ),
        ) {
            items(
                items = messages,
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
}
