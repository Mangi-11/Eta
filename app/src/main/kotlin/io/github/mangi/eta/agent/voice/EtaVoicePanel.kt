package io.github.mangi.eta.agent.voice

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.components.AgentConversationMessages
import io.github.mangi.eta.ui.model.AgentChatMessageUi
import kotlin.math.abs
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.anim.folmeSpring
import top.yukonga.miuix.kmp.basic.Text

internal enum class EtaVoicePhase {
    READY,
    PROCESSING,
    ERROR,
}

internal data class EtaVoiceUiState(
    val messages: List<AgentChatMessageUi> = emptyList(),
    val phase: EtaVoicePhase = EtaVoicePhase.READY,
)

private val CouiEnterEasing = CubicBezierEasing(0.20f, 0f, 0f, 1f)
private val CouiExitEasing = CubicBezierEasing(0.30f, 0f, 1f, 1f)

@Composable
internal fun EtaVoicePanel(
    speechLevel: () -> Float,
    state: EtaVoiceUiState,
    input: String,
    speech: EtaSpeechState,
    recognitionEnabled: Boolean?,
    entranceComplete: Boolean,
    onEntranceFinished: () -> Unit,
    onContentReady: () -> Unit,
    onInputFocus: () -> Unit,
    onMicrophone: (holdToTalk: Boolean) -> Unit,
    onCancelSpeech: () -> Unit,
    onFinishSpeech: () -> Unit,
    onOpenSpeechSettings: () -> Unit,
    onKeyboard: () -> Unit,
    inputFocusRequestKey: Int,
    canOpenConversation: Boolean,
    handoffRunning: Boolean,
    exitRequested: Boolean,
    onInputChange: (String) -> Unit,
    onSuggestionClick: (String) -> Unit,
    onSubmit: () -> Unit,
    onStop: () -> Unit,
    onClose: () -> Unit,
    onOpenConversation: () -> Unit,
) {
    val colors = rememberEtaVoicePanelColors()
    val keyboard = LocalSoftwareKeyboardController.current
    val density = LocalDensity.current
    val entryProgress = remember { Animatable(0f) }
    val entryAlpha = remember { Animatable(0f) }
    val scrimProgress = remember { Animatable(0f) }
    val contentVisible = recognitionEnabled != null && (recognitionEnabled || entranceComplete)
    val contentReady by rememberUpdatedState(onContentReady)
    val entryOffsetPx = with(density) { (if (recognitionEnabled == false) 18.dp else 14.dp).toPx() }
    val exitOffsetPx = with(density) { (if (handoffRunning) (-18).dp else 22.dp).toPx() }

    val exitProgress by animateFloatAsState(
        targetValue = if (exitRequested) 1f else 0f,
        animationSpec = tween(210, easing = CouiExitEasing),
        label = "assistant_exit",
    )
    val hasMessages = state.messages.isNotEmpty()
    val sheetScrimMix by animateFloatAsState(
        targetValue = if (hasMessages) 1f else 0f,
        animationSpec = tween(240, easing = LinearOutSlowInEasing),
        label = "assistant_sheet_scrim_mix",
    )
    val voiceCushionAlpha by animateFloatAsState(
        targetValue = if (contentVisible && speech.active && !hasMessages) 1f else 0f,
        animationSpec = tween(220, easing = FastOutSlowInEasing),
        label = "assistant_voice_cushion",
    )
    val textCushionAlpha by animateFloatAsState(
        targetValue = if (contentVisible && !speech.active && !hasMessages) 1f else 0f,
        animationSpec = tween(200, easing = FastOutSlowInEasing),
        label = "assistant_text_cushion",
    )
    val imeInsets = WindowInsets.ime
    val navigationInsets = WindowInsets.navigationBars

    LaunchedEffect(Unit) {
        delay(50)
        scrimProgress.animateTo(1f, tween(240, easing = LinearOutSlowInEasing))
    }

    LaunchedEffect(contentVisible) {
        if (!contentVisible) return@LaunchedEffect
        coroutineScope {
            launch {
                entryAlpha.animateTo(
                    1f,
                    tween(
                        durationMillis = if (recognitionEnabled == false) 210 else 180,
                        easing = FastOutSlowInEasing,
                    ),
                )
            }
            launch {
                entryProgress.animateTo(
                    1f,
                    tween(
                        durationMillis = if (recognitionEnabled == false) 250 else 210,
                        easing = CouiEnterEasing,
                    ),
                )
            }
        }
        // 焦点会主动唤起 IME；必须等显现和位移都结束，不能在仍透明时交出输入焦点。
        contentReady()
    }

    val exitFade = 1f - exitProgress
    Box(
        modifier = Modifier
            .fillMaxSize()
            .drawBehind {
                val baseScrim = lerp(colors.idleScrim, colors.sheetScrim, sheetScrimMix)
                val scrimAlpha = baseScrim.alpha * scrimProgress.value * exitFade
                if (scrimAlpha > 0.002f) {
                    drawRect(baseScrim.copy(alpha = scrimAlpha))
                }

                val bottomInsetPx = maxOf(
                    imeInsets.getBottom(this),
                    navigationInsets.getBottom(this),
                ).toFloat()

                val voiceCushion = scrimProgress.value * voiceCushionAlpha * exitFade
                if (voiceCushion > 0.005f) {
                    val bandPx = 228.dp.toPx()
                    val startY = (size.height - bottomInsetPx - bandPx).coerceAtLeast(0f)
                    val baseAlpha = colors.voiceBottomCushion.alpha * voiceCushion
                    drawRect(
                        brush = Brush.verticalGradient(
                            0f to Color.Transparent,
                            0.36f to colors.voiceBottomCushion.copy(alpha = baseAlpha * 0.22f),
                            0.70f to colors.voiceBottomCushion.copy(alpha = baseAlpha * 0.64f),
                            1f to colors.voiceBottomCushion.copy(alpha = baseAlpha),
                            startY = startY,
                            endY = size.height,
                        ),
                        topLeft = Offset(0f, startY),
                        size = Size(size.width, size.height - startY),
                    )
                }

                val textCushion = scrimProgress.value * textCushionAlpha * exitFade
                if (textCushion > 0.005f) {
                    // 仅在输入胶囊与软键盘交界处铺一层柔和羽化托底，避免大面积灰雾截断壁纸。
                    val bandPx = 136.dp.toPx()
                    val startY = (size.height - bottomInsetPx - bandPx).coerceAtLeast(0f)
                    drawRect(
                        brush = Brush.verticalGradient(
                            0f to Color.Transparent,
                            0.58f to colors.textBottomCushion.copy(alpha = colors.textBottomCushion.alpha * 0.50f * textCushion),
                            1f to colors.textBottomCushion.copy(alpha = colors.textBottomCushion.alpha * textCushion),
                            startY = startY,
                            endY = size.height,
                        ),
                        topLeft = Offset(0f, startY),
                        size = Size(size.width, size.height - startY),
                    )
                }
            },
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
            active = (speech.active || !entranceComplete) && !exitRequested,
            onEntranceFinished = onEntranceFinished,
            level = speechLevel,
        )
        EtaAssistantWave(
            active = (speech.active || !entranceComplete) && !exitRequested,
            level = speechLevel,
            modifier = Modifier.align(Alignment.BottomCenter),
        )

        if (contentVisible) BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    alpha = entryAlpha.value * exitFade
                    translationY = (1f - entryProgress.value) * entryOffsetPx + exitProgress * exitOffsetPx
                },
        ) {
            val keyboardVisible by remember(density, imeInsets, navigationInsets) {
                derivedStateOf { imeInsets.getBottom(density) > navigationInsets.getBottom(density) }
            }
            val navigationBottom = navigationInsets.getBottom(density)
            val statusTop = WindowInsets.statusBars.getTop(density)
            val imeOverlap = if (state.messages.isEmpty()) 0
                else (imeInsets.getBottom(density) - navigationBottom).coerceAtLeast(0)
            val maxContentHeightPx = with(density) {
                (maxHeight - 88.dp).toPx() - statusTop - navigationBottom
            }.coerceAtLeast(with(density) { 220.dp.toPx() })
            AssistantPanel(
                state = state,
                input = input,
                speech = speech,
                speechLevel = speechLevel,
                recognitionEnabled = recognitionEnabled == true,
                inputFocusRequestKey = inputFocusRequestKey,
                onInputFocus = onInputFocus,
                onMicrophone = onMicrophone,
                onCancelSpeech = onCancelSpeech,
                onFinishSpeech = onFinishSpeech,
                onOpenSpeechSettings = onOpenSpeechSettings,
                onKeyboard = onKeyboard,
                colors = colors,
                canOpenConversation = canOpenConversation,
                handoffRunning = handoffRunning,
                baseContentHeightPx = assistantBaseHeightPx(
                    messages = state.messages,
                    maxHeightPx = maxContentHeightPx,
                    density = density.density,
                ),
                maxContentHeightPx = maxContentHeightPx,
                imeOverlapPx = imeOverlap,
                onInputChange = onInputChange,
                onSuggestionClick = { suggestion ->
                    keyboard?.hide()
                    onSuggestionClick(suggestion)
                },
                keyboardVisible = keyboardVisible,
                onSubmit = {
                    keyboard?.hide()
                    onSubmit()
                },
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
    speechLevel: () -> Float,
    recognitionEnabled: Boolean,
    inputFocusRequestKey: Int,
    onInputFocus: () -> Unit,
    onMicrophone: (holdToTalk: Boolean) -> Unit,
    onCancelSpeech: () -> Unit,
    onFinishSpeech: () -> Unit,
    onOpenSpeechSettings: () -> Unit,
    onKeyboard: () -> Unit,
    colors: EtaVoicePanelColors,
    canOpenConversation: Boolean,
    handoffRunning: Boolean,
    baseContentHeightPx: Float,
    maxContentHeightPx: Float,
    imeOverlapPx: Int,
    onInputChange: (String) -> Unit,
    onSuggestionClick: (String) -> Unit,
    keyboardVisible: Boolean,
    onSubmit: () -> Unit,
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
    val releaseRubberPx = remember { Animatable(0f) }
    var thresholdHapticSent by remember { mutableStateOf(false) }
    var keepBottomAnchored by remember { mutableStateOf(true) }
    // 满高时把手与状态栏之间的空间有限，需在扣除触摸 slop 后仍能完成过顶手势。
    val handoffThresholdPx = with(density) { 24.dp.toPx() }
    val dismissThresholdPx = with(density) { 92.dp.toPx() }
    val maxHandoffVisualPx = with(density) { 40.dp.toPx() }
    val autoSettleTolerancePx = with(density) { 6.dp.toPx() }
    val minimumContentHeightPx = with(density) { 140.dp.toPx() }.coerceAtMost(maxContentHeightPx)
    val dragHandleHeightPx = with(density) { 38.dp.toPx() }
    val contentFitSlackPx = with(density) { 12.dp.toPx() }
    val mediumHeightPx = baseContentHeightPx +
        (maxContentHeightPx - baseContentHeightPx) * 0.58f
    val hasMessages = state.messages.isNotEmpty()
    val targetHeightPx = if (hasMessages) {
        if (settledHeightPx <= 0f) baseContentHeightPx
        else settledHeightPx.coerceIn(minimumContentHeightPx, maxContentHeightPx)
    } else {
        0f
    }
    val animatedHeightPx by animateFloatAsState(
        targetValue = targetHeightPx,
        animationSpec = when {
            autoExpandSuppressed -> folmeSpring(damping = 1f, response = 0.36f)
            else -> folmeSpring(damping = 1f, response = 0.44f)
        },
        label = "assistant_content_height",
    )
    val sheetBackgroundAlpha = animateFloatAsState(
        targetValue = if (hasMessages) 1f else 0f,
        animationSpec = tween(durationMillis = 190, easing = LinearOutSlowInEasing),
        label = "assistant_sheet_background",
    )
    val messageRevealProgress = animateFloatAsState(
        targetValue = if (hasMessages) 1f else 0f,
        animationSpec = if (hasMessages) {
            tween(durationMillis = 210, delayMillis = 45, easing = CouiEnterEasing)
        } else {
            tween(durationMillis = 110, easing = FastOutSlowInEasing)
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
    // 接管只认把手发起的过顶拉取：列表滚动与惯性 fling 不再触发，避免读回复时误入本体。
    val handoffReady = canOpenConversation && nearFullscreen && handoffPullPx >= handoffThresholdPx
    val handoffPullProgress = if (canOpenConversation && nearFullscreen && handoffThresholdPx > 0f) {
        (handoffPullPx / handoffThresholdPx).coerceIn(0f, 1f)
    } else {
        0f
    }
    val activeRubberPx = dismissPullPx * 0.28f - (handoffPullPx * 0.36f).coerceAtMost(maxHandoffVisualPx)
    // 拖拽期间 1:1 跟手，松手后由弹簧承接回弹至 0，消除过顶与下滑松手瞬间的跳帧。
    val sheetTranslationPx = if (draggedHeightPx != null) activeRubberPx else releaseRubberPx.value

    LaunchedEffect(hasMessages, minimumContentHeightPx, maxContentHeightPx) {
        settledHeightPx = if (hasMessages) {
            if (settledHeightPx <= 0f) baseContentHeightPx
            else settledHeightPx.coerceIn(minimumContentHeightPx, maxContentHeightPx)
        } else {
            autoExpandSuppressed = false
            releaseRubberPx.snapTo(0f)
            0f
        }
    }
    // 单向连续生长：随实测内容高度平滑推高面板（弹簧保持速度连续），只增不减，
    // 避免分档等待造成的顿挫以及流式结束后的二次弹起与回落。
    LaunchedEffect(hasMessages, keyboardVisible, baseContentHeightPx, maxContentHeightPx, listState) {
        if (!hasMessages || keyboardVisible) return@LaunchedEffect
        snapshotFlow {
            if (autoExpandSuppressed || draggedHeightPx != null || handoffRunning || !keepBottomAnchored) {
                return@snapshotFlow null
            }
            requiredPanelHeightPx(
                listState = listState,
                currentAnimatedHeightPx = currentAnimatedHeight.value,
                settledHeightPx = settledHeightPx,
                dragHandleHeightPx = dragHandleHeightPx,
                contentFitSlackPx = contentFitSlackPx,
                autoSettleTolerancePx = autoSettleTolerancePx,
                baseContentHeightPx = baseContentHeightPx,
                maxContentHeightPx = maxContentHeightPx,
            )
        }
            .distinctUntilChanged()
            .collect { requiredHeightPx ->
                val target = requiredHeightPx ?: return@collect
                if (target > settledHeightPx + autoSettleTolerancePx) {
                    settledHeightPx = target
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
        settledHeightPx = maxContentHeightPx
        draggedHeightPx = null
        dismissPullPx = 0f
        handoffPullPx = 0f
        scope.launch { releaseRubberPx.snapTo(0f) }
        onOpenConversation()
    }

    fun stopAutoExpand() {
        if (autoExpandSuppressed) return
        autoExpandSuppressed = true
        if (settledHeightPx > currentAnimatedHeight.value + autoSettleTolerancePx) {
            settledHeightPx = currentAnimatedHeight.value.coerceIn(minimumContentHeightPx, maxContentHeightPx)
        }
    }

    fun dragBy(deltaY: Float, allowHandoff: Boolean = false): Float {
        if (handoffRunning || state.messages.isEmpty()) return 0f
        val current = draggedHeightPx ?: currentAnimatedHeight.value
        val requested = current + handoffPullPx - dismissPullPx - deltaY
        val minimumDragHeightPx = minOf(baseContentHeightPx, settledHeightPx)
        return when {
            requested > maxContentHeightPx -> {
                draggedHeightPx = maxContentHeightPx
                dismissPullPx = 0f
                handoffPullPx = if (allowHandoff) requested - maxContentHeightPx else 0f
                if (allowHandoff) deltaY else current - maxContentHeightPx
            }
            requested < minimumDragHeightPx -> {
                draggedHeightPx = minimumDragHeightPx
                dismissPullPx = minimumDragHeightPx - requested
                handoffPullPx = 0f
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

    fun finishDrag(commit: Boolean = true, allowHandoff: Boolean = false) {
        if (handoffRunning) return
        val current = draggedHeightPx ?: currentAnimatedHeight.value
        when {
            commit && dismissPullPx >= dismissThresholdPx -> onClose()
            // 松手可能与最后一个位移在同一帧内到达，直接读取状态，不依赖重组后的派生值。
            commit && allowHandoff && canOpenConversation &&
                current >= maxContentHeightPx && handoffPullPx >= handoffThresholdPx -> triggerHandoff()
            else -> {
                val lingeringRubber = dismissPullPx * 0.28f -
                    (handoffPullPx * 0.36f).coerceAtMost(maxHandoffVisualPx)
                val anchors = floatArrayOf(
                    minOf(baseContentHeightPx, settledHeightPx),
                    baseContentHeightPx,
                    mediumHeightPx,
                    maxContentHeightPx,
                )
                settledHeightPx = anchors.minBy { abs(it - current) }
                draggedHeightPx = null
                dismissPullPx = 0f
                handoffPullPx = 0f
                if (abs(lingeringRubber) > 0.5f) {
                    scope.launch {
                        releaseRubberPx.snapTo(lingeringRubber)
                        releaseRubberPx.animateTo(0f, folmeSpring(damping = 0.94f, response = 0.30f))
                    }
                }
            }
        }
    }

    // 局部函数引用的相等判断不包含捕获值，不能直接作为结构相等 State 的回调值。
    val stopAutoExpandState = rememberUpdatedState { stopAutoExpand() }
    val startDragState = rememberUpdatedState {
        stopAutoExpand()
        scope.launch { releaseRubberPx.snapTo(0f) }
        dismissPullPx = 0f
        handoffPullPx = 0f
        draggedHeightPx = currentAnimatedHeight.value
    }
    val dragByState = rememberUpdatedState { deltaY: Float, allowHandoff: Boolean ->
        dragBy(deltaY, allowHandoff)
    }
    val finishDragState = rememberUpdatedState { commit: Boolean, allowHandoff: Boolean ->
        finishDrag(commit, allowHandoff)
    }
    val nestedScrollConnection = remember(
        baseContentHeightPx,
        maxContentHeightPx,
        canOpenConversation,
        listState,
    ) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput) return Offset.Zero
                stopAutoExpandState.value()
                val current = draggedHeightPx ?: currentAnimatedHeight.value
                val shouldResize = (available.y < 0f && current < maxContentHeightPx) ||
                    (available.y > 0f && current > minOf(baseContentHeightPx, settledHeightPx) &&
                        !listState.canScrollBackward)
                return if (shouldResize) {
                    Offset(0f, dragByState.value(available.y, false))
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
                stopAutoExpandState.value()
                val current = draggedHeightPx ?: currentAnimatedHeight.value
                val atLowerEdge = available.y > 0f && current <= minOf(baseContentHeightPx, settledHeightPx)
                return if (atLowerEdge) {
                    Offset(0f, dragByState.value(available.y, false))
                } else {
                    Offset.Zero
                }
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                stopAutoExpandState.value()
                finishDragState.value(true, false)
                return Velocity.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                stopAutoExpandState.value()
                finishDragState.value(true, false)
                return Velocity.Zero
            }
        }
    }

    val messageRevealOffsetPx = with(density) { 14.dp.toPx() }
    val sheetCornerRadius = 32.dp
    val sheetShape = remember { TopCouiSheetShape(topRadius = sheetCornerRadius, cornerWeight = 2.6f) }
    val sheetSurfaceVisible = hasMessages || sheetBackgroundAlpha.value > 0.001f

    Column(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .offset(y = with(density) { sheetTranslationPx.toDp() })
            .drawBehind {
                val sheetAlpha = sheetBackgroundAlpha.value
                if (sheetAlpha > 0.01f) {
                    // 面板顶部向上柔和漫射环境投影，裁剪之前绘制，不占用布局尺寸。
                    val shadowRisePx = 28.dp.toPx()
                    val topDropAlpha = (if (colors.dark) 0.34f else 0.10f) * sheetAlpha
                    drawRect(
                        brush = Brush.verticalGradient(
                            0f to Color.Transparent,
                            1f to Color.Black.copy(alpha = topDropAlpha),
                            startY = -shadowRisePx,
                            endY = 8.dp.toPx(),
                        ),
                        topLeft = Offset(0f, -shadowRisePx),
                        size = Size(size.width, shadowRisePx + 8.dp.toPx()),
                    )
                }
            }
            // 未展开回复面板时不启用面板轮廓裁剪，避免把空状态下的悬浮胶囊顶部与阴影裁掉。
            .then(if (sheetSurfaceVisible) Modifier.clip(sheetShape) else Modifier)
            .drawWithContent {
                val sheetAlpha = sheetBackgroundAlpha.value
                if (sheetAlpha > 0f) {
                    drawRect(
                        colors.content.copy(
                            alpha = colors.content.alpha * sheetAlpha,
                        ),
                    )
                }
                drawContent()
                if (sheetAlpha > 0.01f && size.height > 8f) {
                    val rimFadePx = minOf(84.dp.toPx(), size.height * 0.55f)
                    val path = buildTopCouiSheetPath(size, sheetCornerRadius.toPx(), 2.6f)
                    drawPath(
                        path = path,
                        brush = Brush.verticalGradient(
                            0f to colors.panelSpecular.copy(alpha = colors.panelSpecular.alpha * sheetAlpha),
                            1f to Color.Transparent,
                            startY = 0f,
                            endY = rimFadePx.coerceAtLeast(1f),
                        ),
                        style = Stroke(width = 1.15.dp.toPx()),
                    )
                }
            }
            // 背景先覆盖导航区域，再让内容避开系统栏；否则底部 Insets 会露出下层窗口。
            // IME 与导航栏取并集，布局阶段消费一次，跟随系统键盘动画。
            .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars).only(WindowInsetsSides.Bottom))
            .windowInsetsPadding(
                WindowInsets.navigationBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal),
            ),
    ) {
        if (sheetSurfaceVisible) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(with(density) { visibleSheetHeightPx.toDp() })
                    .nestedScroll(nestedScrollConnection),
            ) {
                DragHandle(
                    colors = colors,
                    handoffReady = handoffReady,
                    handoffProgress = handoffPullProgress,
                    modifier = Modifier.pointerInput(baseContentHeightPx, maxContentHeightPx) {
                        // 回复完成会改变接管资格；更新回调而不重启正在进行的手势。
                        detectVerticalDragGestures(
                            onDragStart = { startDragState.value() },
                            onVerticalDrag = { change, dragAmount ->
                                change.consume()
                                dragByState.value(dragAmount, true)
                            },
                            onDragEnd = { finishDragState.value(true, true) },
                            onDragCancel = { finishDragState.value(false, false) },
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
        }
        EtaAssistantSuggestions(
            onSuggestionClick = onSuggestionClick,
            colors = colors,
            visible = !hasMessages && !speech.hasFeedback,
        )
        AssistantComposer(
            state = state,
            input = input,
            speech = speech,
            speechLevel = speechLevel,
            recognitionEnabled = recognitionEnabled,
            inputFocusRequestKey = inputFocusRequestKey,
            onInputFocus = onInputFocus,
            onMicrophone = onMicrophone,
            onCancelSpeech = onCancelSpeech,
            onFinishSpeech = onFinishSpeech,
            onOpenSpeechSettings = onOpenSpeechSettings,
            onKeyboard = onKeyboard,
            keyboardVisible = keyboardVisible,
            colors = colors,
            onInputChange = onInputChange,
            onSubmit = onSubmit,
            onStop = onStop,
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = 18.dp,
                    end = 18.dp,
                    top = 2.dp,
                    bottom = 14.dp,
                ),
        )
    }
}

@Composable
private fun DragHandle(
    colors: EtaVoicePanelColors,
    handoffReady: Boolean,
    handoffProgress: Float,
    modifier: Modifier = Modifier,
) {
    // 把手是接管的唯一入口：拉到阈值时变宽并染成主色，配合微文案与触觉反馈提示松手进入本体。
    val handleWidth by animateDpAsState(
        targetValue = if (handoffReady) 52.dp else 36.dp,
        animationSpec = folmeSpring(damping = 0.88f, response = 0.26f),
        label = "assistant_handle_width",
    )
    val handleColor by animateColorAsState(
        targetValue = if (handoffReady) colors.accent else colors.tertiary,
        animationSpec = tween(160),
        label = "assistant_handle_color",
    )
    val hintAlpha by animateFloatAsState(
        targetValue = if (handoffProgress > 0.18f) 1f else 0f,
        animationSpec = tween(140),
        label = "assistant_handoff_hint_alpha",
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(38.dp)
            .testTag("eta_assistant_drag_handle"),
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = if (hintAlpha > 0.05f) 8.dp else 15.dp)
                .size(width = handleWidth, height = 4.dp)
                .clip(CircleShape)
                .background(handleColor),
        )
        if (hintAlpha > 0.01f) {
            Text(
                text = stringResource(
                    if (handoffReady) R.string.voice_handoff_release else R.string.voice_handoff_pull,
                ),
                color = if (handoffReady) colors.accent else colors.inputSecondary,
                fontSize = 11.sp,
                lineHeight = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 3.dp)
                    .graphicsLayer { alpha = hintAlpha },
            )
        }
    }
}

private fun requiredPanelHeightPx(
    listState: LazyListState,
    currentAnimatedHeightPx: Float,
    settledHeightPx: Float,
    dragHandleHeightPx: Float,
    contentFitSlackPx: Float,
    autoSettleTolerancePx: Float,
    baseContentHeightPx: Float,
    maxContentHeightPx: Float,
): Float? {
    val layout = listState.layoutInfo
    if (layout.totalItemsCount == 0 || layout.viewportSize.height <= 0) return null
    val first = layout.visibleItemsInfo.firstOrNull() ?: return null
    val last = layout.visibleItemsInfo.lastOrNull() ?: return null
    val visibleSpanPx = (last.offset + last.size - first.offset +
        layout.beforeContentPadding + layout.afterContentPadding).toFloat()
    val allItemsVisible = first.index == 0 && last.index == layout.totalItemsCount - 1
    val requiredContentHeightPx = if (allItemsVisible) {
        dragHandleHeightPx + visibleSpanPx + contentFitSlackPx
    } else if (currentAnimatedHeightPx >= settledHeightPx - autoSettleTolerancePx) {
        maxContentHeightPx
    } else {
        dragHandleHeightPx + visibleSpanPx + contentFitSlackPx
    }
    return requiredContentHeightPx.coerceIn(baseContentHeightPx, maxContentHeightPx)
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
