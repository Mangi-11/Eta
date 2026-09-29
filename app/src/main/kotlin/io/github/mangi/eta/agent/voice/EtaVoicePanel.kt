package io.github.mangi.eta.agent.voice

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.ui.components.AgentConversationMessages
import io.github.mangi.eta.ui.model.AgentChatMessageUi
import kotlin.math.abs
import kotlin.math.max
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.anim.folmeSpring
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal enum class EtaVoicePhase {
    READY,
    PROCESSING,
    ERROR,
}

internal data class EtaVoiceUiState(
    val messages: List<AgentChatMessageUi> = emptyList(),
    val phase: EtaVoicePhase = EtaVoicePhase.READY,
    val status: EtaVoiceStatus = EtaVoiceStatus.InputRequest,
)

internal sealed interface EtaVoiceStatus {
    data object InputRequest : EtaVoiceStatus
    data object Reasoning : EtaVoiceStatus
    data object Completed : EtaVoiceStatus
    data class RunningTool(val name: String) : EtaVoiceStatus
    data class Failed(val detail: String?) : EtaVoiceStatus
    data object Stopped : EtaVoiceStatus
}

internal data class EtaVoicePanelColors(
    val content: Color,
    val input: Color,
    val focusedInput: Color,
    val inputPrimary: Color,
    val inputSecondary: Color,
    val inputTertiary: Color,
    val tertiary: Color,
    val panelStroke: Color,
    val scrim: Color,
)

@Composable
private fun rememberEtaVoicePanelColors(): EtaVoicePanelColors {
    val dark = MiuixTheme.colorScheme.background.luminance() < 0.5f
    return remember(dark) {
        if (dark) {
            EtaVoicePanelColors(
                content = Color(0xFF1E1E1E),
                input = Color(0xFF34363B),
                focusedInput = Color(0xFF42444A),
                inputPrimary = Color(0xE6FFFFFF),
                inputSecondary = Color(0x8AFFFFFF),
                inputTertiary = Color(0x4DFFFFFF),
                tertiary = Color(0x40FFFFFF),
                panelStroke = Color(0x0AFFFFFF),
                scrim = Color(0x52000000),
            )
        } else {
            EtaVoicePanelColors(
                content = Color(0xFFF0F1F2),
                input = Color(0xFFF4F5F7),
                focusedInput = Color.White,
                inputPrimary = Color(0xE6000000),
                inputSecondary = Color(0x8A000000),
                inputTertiary = Color(0x42000000),
                tertiary = Color(0x29000000),
                panelStroke = Color.White,
                scrim = Color(0x30000000),
            )
        }
    }
}

@Composable
internal fun EtaVoicePanel(
    speechLevel: () -> Float,
    state: EtaVoiceUiState,
    input: String,
    speech: EtaSpeechState,
    onMicrophone: () -> Unit,
    onFinishSpeech: () -> Unit,
    onDownloadModel: () -> Unit,
    onOpenSpeechSettings: () -> Unit,
    onKeyboard: () -> Unit,
    inputFocusRequestKey: Int,
    canOpenConversation: Boolean,
    exitRequested: Boolean,
    onInputChange: (String) -> Unit,
    onSuggestionClick: (String) -> Unit,
    onSubmit: () -> Unit,
    onSupplement: (String, String, (Boolean) -> Unit) -> Unit,
    onStop: () -> Unit,
    onClose: () -> Unit,
    onOpenConversation: () -> Unit,
) {
    val colors = rememberEtaVoicePanelColors()
    val keyboard = LocalSoftwareKeyboardController.current
    val density = LocalDensity.current
    val focusRequester = remember { FocusRequester() }
    val entryProgress = remember { Animatable(0f) }
    val scrimProgress = remember { Animatable(0f) }
    val exitAlpha by animateFloatAsState(
        targetValue = if (exitRequested) 0f else 1f,
        animationSpec = tween(220, easing = FastOutSlowInEasing),
        label = "assistant_exit",
    )

    LaunchedEffect(Unit) {
        launch { entryProgress.animateTo(1f, tween(160, easing = LinearOutSlowInEasing)) }
        delay(180)
        scrimProgress.animateTo(1f, tween(280, easing = LinearOutSlowInEasing))
    }

    LaunchedEffect(inputFocusRequestKey) {
        if (inputFocusRequestKey >= 0 && state.phase != EtaVoicePhase.PROCESSING) {
            delay(120)
            focusRequester.requestFocus()
            keyboard?.show()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = exitAlpha }
            .drawBehind { drawRect(colors.scrim.copy(alpha = colors.scrim.alpha * scrimProgress.value)) },
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClose,
                ),
        )

        EtaAssistantEdgeGlow(
            active = speech.active && !exitRequested,
        )
        EtaAssistantWave(
            active = speech.active && !exitRequested,
            level = speechLevel,
            modifier = Modifier.align(Alignment.BottomCenter),
        )

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    alpha = entryProgress.value
                },
        ) {
            val imeBottom = WindowInsets.ime.getBottom(density)
            val navigationBottom = WindowInsets.navigationBars.getBottom(density)
            val statusTop = WindowInsets.statusBars.getTop(density)
            val bottomInset = max(imeBottom, navigationBottom)
            val imeOverlap = (imeBottom - navigationBottom).coerceAtLeast(0)
            val maxContentHeightPx = with(density) {
                (maxHeight - 88.dp).toPx() - statusTop - navigationBottom
            }.coerceAtLeast(with(density) { 220.dp.toPx() })
            AssistantPanel(
                state = state,
                input = input,
                speech = speech,
                onMicrophone = onMicrophone,
                onFinishSpeech = onFinishSpeech,
                onDownloadModel = onDownloadModel,
            onOpenSpeechSettings = onOpenSpeechSettings,
                onKeyboard = onKeyboard,
                colors = colors,
                focusRequester = focusRequester,
                canOpenConversation = canOpenConversation,
                baseContentHeightPx = assistantBaseHeightPx(
                    messages = state.messages,
                    maxHeightPx = maxContentHeightPx,
                    density = density.density,
                ),
                maxContentHeightPx = maxContentHeightPx,
                bottomInsetPx = bottomInset,
                imeOverlapPx = imeOverlap,
                onInputChange = onInputChange,
                onSuggestionClick = { suggestion ->
                    keyboard?.hide()
                    onSuggestionClick(suggestion)
                },
                keyboardVisible = imeBottom > navigationBottom,
                onSubmit = {
                    keyboard?.hide()
                    onSubmit()
                },
                onSupplement = onSupplement,
                onStop = onStop,
                onClose = onClose,
                onOpenConversation = onOpenConversation,
            )
        }
    }
}

@Composable
private fun BoxScope.AssistantPanel(
    state: EtaVoiceUiState,
    input: String,
    speech: EtaSpeechState,
    onMicrophone: () -> Unit,
    onFinishSpeech: () -> Unit,
    onDownloadModel: () -> Unit,
    onOpenSpeechSettings: () -> Unit,
    onKeyboard: () -> Unit,
    colors: EtaVoicePanelColors,
    focusRequester: FocusRequester,
    canOpenConversation: Boolean,
    baseContentHeightPx: Float,
    maxContentHeightPx: Float,
    bottomInsetPx: Int,
    imeOverlapPx: Int,
    onInputChange: (String) -> Unit,
    onSuggestionClick: (String) -> Unit,
    keyboardVisible: Boolean,
    onSubmit: () -> Unit,
    onSupplement: (String, String, (Boolean) -> Unit) -> Unit,
    onStop: () -> Unit,
    onClose: () -> Unit,
    onOpenConversation: () -> Unit,
) {
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var settledHeightPx by remember { mutableFloatStateOf(baseContentHeightPx) }
    var draggedHeightPx by remember { mutableStateOf<Float?>(null) }
    var autoExpandSuppressed by remember { mutableStateOf(false) }
    var dismissPullPx by remember { mutableFloatStateOf(0f) }
    var handoffPullPx by remember { mutableFloatStateOf(0f) }
    var directHandoffPullPx by remember { mutableFloatStateOf(0f) }
    var thresholdHapticSent by remember { mutableStateOf(false) }
    var handoffRunning by remember { mutableStateOf(false) }
    var keepBottomAnchored by remember { mutableStateOf(true) }
    var fittedReplyId by remember { mutableStateOf<String?>(null) }
    val handoffThresholdPx = with(density) { 72.dp.toPx() }
    val directHandoffThresholdPx = with(density) { 48.dp.toPx() }
    val dismissThresholdPx = with(density) { 92.dp.toPx() }
    val handoffVelocityPx = with(density) { 900.dp.toPx() }
    val autoSettleTolerancePx = with(density) { 8.dp.toPx() }
    val overflowTolerancePx = with(density) { 16.dp.toPx() }
    val minimumContentHeightPx = with(density) { 140.dp.toPx() }.coerceAtMost(maxContentHeightPx)
    val dragHandleHeightPx = with(density) { 38.dp.toPx() }
    val contentFitSlackPx = with(density) { 12.dp.toPx() }
    val mediumHeightPx = baseContentHeightPx +
        (maxContentHeightPx - baseContentHeightPx) * 0.58f
    val hasMessages = state.messages.isNotEmpty()
    val latestMessageId = state.messages.lastOrNull()?.id
    val targetHeightPx = if (hasMessages) {
        if (settledHeightPx <= 0f) baseContentHeightPx
        else settledHeightPx.coerceIn(minimumContentHeightPx, maxContentHeightPx)
    } else {
        0f
    }
    val animatedHeightPx by animateFloatAsState(
        targetValue = targetHeightPx,
        animationSpec = when {
            !autoExpandSuppressed &&
                abs(settledHeightPx - baseContentHeightPx) > autoSettleTolerancePx ->
                folmeSpring(damping = 1f, response = 0.72f)
            autoExpandSuppressed -> folmeSpring(damping = 1f, response = 0.38f)
            else -> folmeSpring(damping = 1f, response = 0.50f)
        },
        label = "assistant_content_height",
    )
    val sheetBackgroundAlpha = animateFloatAsState(
        targetValue = if (hasMessages) 1f else 0f,
        animationSpec = tween(durationMillis = 160, easing = LinearOutSlowInEasing),
        label = "assistant_sheet_background",
    )
    val messageRevealProgress = animateFloatAsState(
        targetValue = if (hasMessages) 1f else 0f,
        animationSpec = if (hasMessages) {
            tween(durationMillis = 180, delayMillis = 50, easing = LinearOutSlowInEasing)
        } else {
            tween(durationMillis = 100, easing = FastOutSlowInEasing)
        },
        label = "assistant_message_reveal",
    )
    val currentAnimatedHeight = rememberUpdatedState(animatedHeightPx)
    val sheetHeightPx = draggedHeightPx ?: animatedHeightPx
    val visibleSheetHeightPx = (sheetHeightPx - imeOverlapPx * 0.42f)
        .coerceAtLeast(if (hasMessages) with(density) { 140.dp.toPx() }.coerceAtMost(sheetHeightPx) else 0f)
        .coerceAtMost(
            (maxContentHeightPx - imeOverlapPx).coerceAtLeast(0f),
        )
    val nearFullscreen = sheetHeightPx >= maxContentHeightPx * 0.88f
    val handoffReady = canOpenConversation && nearFullscreen &&
        (handoffPullPx >= handoffThresholdPx ||
            directHandoffPullPx >= directHandoffThresholdPx)
    val sheetTranslationPx = dismissPullPx * 0.28f - handoffPullPx.coerceAtMost(
        with(density) { 28.dp.toPx() },
    ) * 0.12f

    LaunchedEffect(hasMessages, minimumContentHeightPx, maxContentHeightPx) {
        settledHeightPx = if (hasMessages) {
            if (settledHeightPx <= 0f) baseContentHeightPx
            else settledHeightPx.coerceIn(minimumContentHeightPx, maxContentHeightPx)
        } else {
            autoExpandSuppressed = false
            fittedReplyId = null
            0f
        }
    }
    LaunchedEffect(state.phase, latestMessageId) {
        if (state.phase == EtaVoicePhase.PROCESSING) fittedReplyId = null
    }
    // 回复中按真实溢出逐级升高；回复结束且全部条目可测量时交给一次性内容拟合。
    LaunchedEffect(hasMessages, state.phase, latestMessageId, keyboardVisible, baseContentHeightPx, maxContentHeightPx, listState) {
        if (!hasMessages || keyboardVisible) return@LaunchedEffect
        snapshotFlow {
            !autoExpandSuppressed &&
                fittedReplyId != latestMessageId &&
                draggedHeightPx == null &&
                !handoffRunning &&
                keepBottomAnchored &&
                abs(currentAnimatedHeight.value - settledHeightPx) <= autoSettleTolerancePx &&
                run {
                    val measuredHeightPx = measuredConversationHeightPx(listState)
                    if (state.phase != EtaVoicePhase.PROCESSING && measuredHeightPx != null) {
                        false
                    } else if (measuredHeightPx != null) {
                        measuredHeightPx > listState.layoutInfo.viewportSize.height + overflowTolerancePx
                    } else {
                        listState.canScrollBackward || listState.canScrollForward
                    }
                }
        }
            .distinctUntilChanged()
            .collect { overflowAtRest ->
                if (!overflowAtRest) return@collect
                val nextHeight = when {
                    settledHeightPx < baseContentHeightPx - autoSettleTolerancePx -> baseContentHeightPx
                    settledHeightPx < mediumHeightPx - autoSettleTolerancePx -> mediumHeightPx
                    settledHeightPx < maxContentHeightPx - autoSettleTolerancePx -> maxContentHeightPx
                    else -> null
                }
                if (nextHeight != null) settledHeightPx = nextHeight
            }
    }
    // 完成排版后本轮只拟合一次；列表的旧滚动偏移不能把它再次推回高档。
    LaunchedEffect(hasMessages, state.phase, latestMessageId, keyboardVisible, maxContentHeightPx, listState) {
        if (!hasMessages || state.phase == EtaVoicePhase.PROCESSING || keyboardVisible) return@LaunchedEffect
        snapshotFlow {
            if (autoExpandSuppressed || fittedReplyId == latestMessageId ||
                draggedHeightPx != null || handoffRunning ||
                !keepBottomAnchored ||
                abs(currentAnimatedHeight.value - settledHeightPx) > autoSettleTolerancePx
            ) return@snapshotFlow null
            val contentHeightPx = measuredConversationHeightPx(listState) ?: return@snapshotFlow null
            (dragHandleHeightPx + contentHeightPx + contentFitSlackPx)
                .coerceIn(minimumContentHeightPx, maxContentHeightPx)
        }
            .distinctUntilChanged()
            .collectLatest { fittedHeightPx ->
                val targetHeightPx = fittedHeightPx ?: return@collectLatest
                delay(500)
                fittedReplyId = latestMessageId
                if (abs(settledHeightPx - targetHeightPx) > autoSettleTolerancePx) {
                    settledHeightPx = targetHeightPx
                }
            }
    }
    LaunchedEffect(handoffReady) {
        if (handoffReady && !thresholdHapticSent) {
            thresholdHapticSent = true
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        } else if (!handoffReady) {
            thresholdHapticSent = false
        }
    }

    fun triggerHandoff() {
        if (handoffRunning || !canOpenConversation) return
        handoffRunning = true
        settledHeightPx = maxContentHeightPx
        draggedHeightPx = null
        scope.launch {
            delay(120)
            onOpenConversation()
        }
    }

    fun stopAutoExpand() {
        if (autoExpandSuppressed) return
        autoExpandSuppressed = true
        if (settledHeightPx > currentAnimatedHeight.value + autoSettleTolerancePx) {
            settledHeightPx = currentAnimatedHeight.value.coerceIn(minimumContentHeightPx, maxContentHeightPx)
        }
    }

    fun dragBy(deltaY: Float): Float {
        if (handoffRunning || state.messages.isEmpty()) return 0f
        val current = draggedHeightPx ?: currentAnimatedHeight.value
        val requested = current - deltaY
        val minimumDragHeightPx = minOf(baseContentHeightPx, settledHeightPx)
        return when {
            requested > maxContentHeightPx -> {
                draggedHeightPx = maxContentHeightPx
                if (deltaY < 0f) handoffPullPx += -deltaY
                deltaY
            }
            requested < minimumDragHeightPx -> {
                draggedHeightPx = minimumDragHeightPx
                if (deltaY > 0f) dismissPullPx += deltaY
                deltaY
            }
            else -> {
                draggedHeightPx = requested
                dismissPullPx = 0f
                handoffPullPx = 0f
                deltaY
            }
        }
    }

    fun finishDrag(velocityY: Float = 0f) {
        val current = draggedHeightPx ?: currentAnimatedHeight.value
        when {
            dismissPullPx >= dismissThresholdPx -> onClose()
            canOpenConversation && current >= maxContentHeightPx * 0.88f &&
                (handoffReady || velocityY <= -handoffVelocityPx) -> triggerHandoff()
            else -> {
                val anchors = floatArrayOf(
                    minOf(baseContentHeightPx, settledHeightPx),
                    baseContentHeightPx,
                    mediumHeightPx,
                    maxContentHeightPx,
                )
                settledHeightPx = anchors.minBy { kotlin.math.abs(it - current) }
                draggedHeightPx = null
                dismissPullPx = 0f
                handoffPullPx = 0f
            }
        }
    }

    val dragByState = rememberUpdatedState<(Float) -> Float>(::dragBy)
    val finishDragState = rememberUpdatedState<(Float) -> Unit>(::finishDrag)
    val nestedScrollConnection = remember(
        baseContentHeightPx,
        maxContentHeightPx,
        canOpenConversation,
        listState,
    ) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput) return Offset.Zero
                stopAutoExpand()
                val current = draggedHeightPx ?: currentAnimatedHeight.value
                if (
                    available.y < 0f &&
                    canOpenConversation &&
                    current >= maxContentHeightPx * 0.88f &&
                    !listState.canScrollForward
                ) {
                    directHandoffPullPx += -available.y
                    if (directHandoffPullPx >= directHandoffThresholdPx) {
                        triggerHandoff()
                    }
                    // 第二段上滑由父容器在 pre-scroll 阶段完整消费，避免列表或
                    // overscroll 先截走事件后，接管手势永远达不到阈值。
                    return Offset(0f, available.y)
                }
                val shouldResize = (available.y < 0f && current < maxContentHeightPx) ||
                    (available.y > 0f && current > minOf(baseContentHeightPx, settledHeightPx) &&
                        !listState.canScrollBackward)
                return if (shouldResize) {
                    Offset(0f, dragByState.value(available.y))
                } else {
                    Offset.Zero
                }
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (source != NestedScrollSource.UserInput || available.y == 0f) return Offset.Zero
                stopAutoExpand()
                val current = draggedHeightPx ?: currentAnimatedHeight.value
                val atUpperEdge = available.y < 0f && current >= maxContentHeightPx * 0.88f
                val atLowerEdge = available.y > 0f && current <= minOf(baseContentHeightPx, settledHeightPx)
                return if (atUpperEdge || atLowerEdge) {
                    Offset(0f, dragByState.value(available.y))
                } else {
                    Offset.Zero
                }
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                stopAutoExpand()
                finishDragState.value(available.y)
                return Velocity.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                stopAutoExpand()
                finishDragState.value(available.y)
                return Velocity.Zero
            }
        }
    }

    val bottomInset = with(density) { bottomInsetPx.toDp() }
    val messageRevealOffsetPx = with(density) { 12.dp.toPx() }
    val sheetShape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)
    Column(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .offset(y = with(density) { sheetTranslationPx.toDp() })
            .clip(sheetShape)
            .drawBehind {
                drawRect(
                    colors.content.copy(
                        alpha = colors.content.alpha * sheetBackgroundAlpha.value,
                    ),
                )
            }
            .border(
                width = 1.6.dp,
                color = colors.panelStroke.copy(alpha = colors.panelStroke.alpha * sheetBackgroundAlpha.value),
                shape = sheetShape,
            ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(with(density) { visibleSheetHeightPx.toDp() })
                .nestedScroll(nestedScrollConnection),
        ) {
            DragHandle(
                colors = colors,
                modifier = Modifier.pointerInput(baseContentHeightPx, maxContentHeightPx) {
                    detectVerticalDragGestures(
                        onDragStart = {
                            stopAutoExpand()
                            draggedHeightPx = currentAnimatedHeight.value
                        },
                        onVerticalDrag = { change, dragAmount ->
                            change.consume()
                            dragBy(dragAmount)
                        },
                        onDragEnd = { finishDrag() },
                        onDragCancel = { finishDrag() },
                    )
                },
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .graphicsLayer {
                        alpha = messageRevealProgress.value
                        translationY = (1f - messageRevealProgress.value) * messageRevealOffsetPx
                    },
            ) {
                if (hasMessages) {
                    AgentConversationMessages(
                        visibleMessages = state.messages,
                        scrollState = listState,
                        isStreaming = state.phase == EtaVoicePhase.PROCESSING,
                        bottomInset = 8.dp,
                        keepBottomAnchored = keepBottomAnchored,
                        onBottomAnchorChanged = { keepBottomAnchored = it },
                        assistantOverlay = true,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        EtaAssistantSuggestions(
            onSuggestionClick = onSuggestionClick,
            visible = !hasMessages,
            keyboardVisible = keyboardVisible,
        )
        AssistantComposer(
            state = state,
            input = input,
            speech = speech,
            onMicrophone = onMicrophone,
            onFinishSpeech = onFinishSpeech,
            onDownloadModel = onDownloadModel,
            onOpenSpeechSettings = onOpenSpeechSettings,
            onKeyboard = onKeyboard,
            keyboardVisible = keyboardVisible,
            colors = colors,
            focusRequester = focusRequester,
            onInputChange = onInputChange,
            onClose = onClose,
            onSubmit = onSubmit,
            onSupplement = onSupplement,
            onStop = onStop,
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = 16.dp,
                    end = 16.dp,
                    top = 8.dp,
                    bottom = bottomInset + 10.dp,
                ),
        )
    }
}

@Composable
private fun DragHandle(colors: EtaVoicePanelColors, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(38.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(width = 36.dp, height = 4.dp)
                .clip(CircleShape)
                .background(colors.tertiary),
        )
    }
}

private fun measuredConversationHeightPx(listState: LazyListState): Float? {
    val layout = listState.layoutInfo
    if (layout.totalItemsCount == 0) return null
    val first = layout.visibleItemsInfo.firstOrNull() ?: return null
    val last = layout.visibleItemsInfo.lastOrNull() ?: return null
    if (first.index != 0 || last.index != layout.totalItemsCount - 1) return null
    return (last.offset + last.size - first.offset +
        layout.beforeContentPadding + layout.afterContentPadding).toFloat()
}

private fun assistantBaseHeightPx(
    messages: List<AgentChatMessageUi>,
    maxHeightPx: Float,
    density: Float,
): Float {
    if (messages.isEmpty()) return 0f
    // 回复增量只改变列表内容，不重新估算浮窗高度，避免与跟底滚动互相追赶。
    return (304f * density).coerceAtMost(maxHeightPx)
}
