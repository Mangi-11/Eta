package io.github.mangi.eta.agent.voice.ui

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
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.components.chat.AgentConversationMessages
import io.github.mangi.eta.ui.model.AgentChatMessageUi
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.anim.folmeSpring
import top.yukonga.miuix.kmp.basic.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

internal enum class EtaVoicePhase {
    READY,
    PROCESSING,
    ERROR,
}

internal data class EtaVoiceUiState(
    val messages: List<AgentChatMessageUi> = emptyList(),
    val phase: EtaVoicePhase = EtaVoicePhase.READY,
)

private val AssistantEnterEasing = CubicBezierEasing(0.20f, 0f, 0f, 1f)
private val AssistantExitEasing = CubicBezierEasing(0.30f, 0f, 1f, 1f)

@Composable
internal fun EtaVoicePanel(
    speechLevel: () -> Float,
    state: EtaVoiceUiState,
    input: TextFieldState,
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
    onInputEdited: () -> Unit,
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
        animationSpec = tween(210, easing = AssistantExitEasing),
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
                        easing = AssistantEnterEasing,
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
                onInputEdited = onInputEdited,
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
    input: TextFieldState,
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
    onInputEdited: () -> Unit,
    onSuggestionClick: (String) -> Unit,
    keyboardVisible: Boolean,
    onSubmit: () -> Unit,
    onStop: () -> Unit,
    onClose: () -> Unit,
    onOpenConversation: () -> Unit,
) {
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val listState = rememberLazyListState()
    var settledHeightPx by remember { mutableFloatStateOf(baseContentHeightPx) }
    val heightAnim = remember { Animatable(0f) }
    var sheetHeightPx by remember { mutableFloatStateOf(0f) }
    var needsHeightAnimSync by remember { mutableStateOf(false) }
    val rubberAnim = remember { Animatable(0f) }
    var rubberYPx by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }
    var autoExpandSuppressed by remember { mutableStateOf(false) }
    var dismissPullPx by remember { mutableFloatStateOf(0f) }
    var handoffPullPx by remember { mutableFloatStateOf(0f) }
    var thresholdHapticSent by remember { mutableStateOf(false) }
    var keepBottomAnchored by remember { mutableStateOf(true) }
    // 满高时把手与状态栏之间的空间有限，需在扣除触摸 slop 后仍能完成过顶手势。
    val handoffThresholdPx = with(density) { 24.dp.toPx() }
    val dismissThresholdPx = with(density) { 92.dp.toPx() }
    val maxHandoffVisualPx = with(density) { 40.dp.toPx() }
    val autoSettleTolerancePx = with(density) { 6.dp.toPx() }
    val dragHandleHeightPx = with(density) { 38.dp.toPx() }
    val contentFitSlackPx = with(density) { 12.dp.toPx() }
    val availableMaxHeightPx = (maxContentHeightPx - imeOverlapPx).coerceAtLeast(0f)
    val minimumContentHeightPx = with(density) { 140.dp.toPx() }.coerceAtMost(availableMaxHeightPx)
    val clampedBaseHeightPx = baseContentHeightPx.coerceIn(minimumContentHeightPx, availableMaxHeightPx)
    val mediumHeightPx = clampedBaseHeightPx +
        (availableMaxHeightPx - clampedBaseHeightPx) * 0.58f
    val effectiveMinHeightPx = minOf(clampedBaseHeightPx, settledHeightPx)
        .coerceIn(minimumContentHeightPx, availableMaxHeightPx)
    val hasMessages = state.messages.isNotEmpty()
    val targetHeightPx = if (hasMessages) {
        if (settledHeightPx <= 0f) clampedBaseHeightPx
        else settledHeightPx.coerceIn(minimumContentHeightPx, availableMaxHeightPx)
    } else {
        0f
    }
    val sheetBackgroundAlpha = animateFloatAsState(
        targetValue = if (hasMessages) 1f else 0f,
        animationSpec = tween(durationMillis = 190, easing = LinearOutSlowInEasing),
        label = "assistant_sheet_background",
    )
    val messageRevealProgress = animateFloatAsState(
        targetValue = if (hasMessages) 1f else 0f,
        animationSpec = if (hasMessages) {
            tween(durationMillis = 210, delayMillis = 45, easing = AssistantEnterEasing)
        } else {
            tween(durationMillis = 110, easing = FastOutSlowInEasing)
        },
        label = "assistant_message_reveal",
    )
    val visibleSheetHeightPx = if (hasMessages || sheetHeightPx > 0.5f) {
        sheetHeightPx.coerceIn(0f, availableMaxHeightPx)
    } else {
        0f
    }
    // 接管只认把手发起的过顶拉取：列表滚动与惯性 fling 不再触发，避免读回复时误入本体。
    val handoffReady = canOpenConversation &&
        sheetHeightPx >= availableMaxHeightPx - 1f &&
        handoffPullPx >= handoffThresholdPx
    val handoffPullProgress = if (canOpenConversation && handoffThresholdPx > 0f && handoffPullPx > 0f) {
        (handoffPullPx / handoffThresholdPx).coerceIn(0f, 1f)
    } else {
        0f
    }

    LaunchedEffect(hasMessages, minimumContentHeightPx, maxContentHeightPx) {
        if (hasMessages) {
            settledHeightPx = if (settledHeightPx <= 0f) {
                baseContentHeightPx.coerceIn(minimumContentHeightPx, maxContentHeightPx)
            } else {
                settledHeightPx.coerceIn(minimumContentHeightPx, maxContentHeightPx)
            }
        } else {
            autoExpandSuppressed = false
            isDragging = false
            needsHeightAnimSync = false
            dismissPullPx = 0f
            handoffPullPx = 0f
            settledHeightPx = 0f
            rubberYPx = 0f
            rubberAnim.snapTo(0f)
        }
    }
    LaunchedEffect(targetHeightPx, isDragging) {
        if (isDragging) return@LaunchedEffect
        if (needsHeightAnimSync) {
            heightAnim.snapTo(sheetHeightPx)
            needsHeightAnimSync = false
        }
        heightAnim.animateTo(
            targetValue = targetHeightPx,
            animationSpec = if (autoExpandSuppressed) {
                folmeSpring(damping = 1f, response = 0.36f)
            } else {
                folmeSpring(damping = 1f, response = 0.44f)
            },
        ) {
            if (!isDragging) sheetHeightPx = value
        }
    }
    LaunchedEffect(isDragging) {
        if (isDragging) return@LaunchedEffect
        if (abs(rubberYPx) > 0.5f) {
            rubberAnim.snapTo(rubberYPx)
            rubberAnim.animateTo(0f, folmeSpring(damping = 0.94f, response = 0.30f)) {
                if (!isDragging) rubberYPx = value
            }
        } else {
            rubberYPx = 0f
            rubberAnim.snapTo(0f)
        }
    }
    // 单向连续生长：随实测内容高度平滑推高面板（弹簧保持速度连续），只增不减，
    // 避免分档等待造成的顿挫以及流式结束后的二次弹起与回落。
    LaunchedEffect(hasMessages, keyboardVisible, baseContentHeightPx, availableMaxHeightPx, listState) {
        if (!hasMessages || keyboardVisible) return@LaunchedEffect
        snapshotFlow {
            if (autoExpandSuppressed || isDragging || handoffRunning || !keepBottomAnchored) {
                return@snapshotFlow null
            }
            requiredPanelHeightPx(
                listState = listState,
                currentAnimatedHeightPx = sheetHeightPx,
                settledHeightPx = settledHeightPx,
                dragHandleHeightPx = dragHandleHeightPx,
                contentFitSlackPx = contentFitSlackPx,
                autoSettleTolerancePx = autoSettleTolerancePx,
                baseContentHeightPx = clampedBaseHeightPx,
                maxContentHeightPx = availableMaxHeightPx,
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
        settledHeightPx = availableMaxHeightPx
        dismissPullPx = 0f
        handoffPullPx = 0f
        rubberYPx = 0f
        isDragging = false
        onOpenConversation()
    }

    fun stopAutoExpand() {
        if (autoExpandSuppressed) return
        autoExpandSuppressed = true
        if (settledHeightPx > sheetHeightPx + autoSettleTolerancePx) {
            settledHeightPx = sheetHeightPx.coerceIn(minimumContentHeightPx, availableMaxHeightPx)
        }
    }

    fun startDrag() {
        stopAutoExpand()
        isDragging = true
        needsHeightAnimSync = true
        dismissPullPx = 0f
        handoffPullPx = 0f
        sheetHeightPx = sheetHeightPx.coerceIn(effectiveMinHeightPx, availableMaxHeightPx)
        rubberYPx = 0f
    }

    fun dragBy(deltaRootY: Float, allowHandoff: Boolean) {
        if (handoffRunning || state.messages.isEmpty() || deltaRootY == 0f) return
        val currentPos = sheetHeightPx + handoffPullPx - dismissPullPx
        val requestedPos = currentPos - deltaRootY
        val clampedHeight = requestedPos.coerceIn(effectiveMinHeightPx, availableMaxHeightPx)
        val nextHandoff = if (allowHandoff && requestedPos > availableMaxHeightPx) {
            requestedPos - availableMaxHeightPx
        } else {
            0f
        }
        val nextDismiss = if (requestedPos < effectiveMinHeightPx) {
            effectiveMinHeightPx - requestedPos
        } else {
            0f
        }
        handoffPullPx = nextHandoff
        dismissPullPx = nextDismiss
        sheetHeightPx = clampedHeight
        rubberYPx = nextDismiss * 0.28f - (nextHandoff * 0.36f).coerceAtMost(maxHandoffVisualPx)
    }

    fun finishDrag(commit: Boolean, allowHandoff: Boolean) {
        if (handoffRunning) {
            isDragging = false
            return
        }
        val current = sheetHeightPx
        when {
            commit && dismissPullPx >= dismissThresholdPx -> {
                dismissPullPx = 0f
                handoffPullPx = 0f
                isDragging = false
                onClose()
            }
            // 松手可能与最后一个位移在同一帧内到达，直接读取状态，不依赖重组后的派生值。
            commit && allowHandoff && canOpenConversation &&
                current >= availableMaxHeightPx - 1f && handoffPullPx >= handoffThresholdPx -> {
                triggerHandoff()
            }
            else -> {
                val anchors = floatArrayOf(
                    effectiveMinHeightPx,
                    clampedBaseHeightPx,
                    mediumHeightPx,
                    availableMaxHeightPx,
                )
                settledHeightPx = anchors.minBy { abs(it - current) }
                dismissPullPx = 0f
                handoffPullPx = 0f
                isDragging = false
            }
        }
    }

    val startDragState = rememberUpdatedState { startDrag() }
    val dragByState = rememberUpdatedState { deltaY: Float, allowHandoff: Boolean ->
        dragBy(deltaY, allowHandoff)
    }
    val finishDragState = rememberUpdatedState { commit: Boolean, allowHandoff: Boolean ->
        finishDrag(commit, allowHandoff)
    }
    val shouldInterceptListDrag = rememberUpdatedState { totalDy: Float ->
        (totalDy < 0f && sheetHeightPx < availableMaxHeightPx - 1f) ||
            (totalDy > 0f && !listState.canScrollBackward)
    }

    val messageRevealOffsetPx = with(density) { 14.dp.toPx() }
    val sheetCornerRadius = 32.dp
    val sheetShape = remember { AssistantSheetShape(topRadius = sheetCornerRadius, cornerWeight = 2.6f) }
    val sheetSurfaceVisible = hasMessages || sheetBackgroundAlpha.value > 0.001f

    Column(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .offset { IntOffset(0, rubberYPx.roundToInt()) }
            .drawBehind {
                val sheetAlpha = sheetBackgroundAlpha.value
                if (sheetAlpha > 0.01f && size.height > 8f) {
                    // 沿着 G2 连续顶部圆角外侧绘制渐隐环境投影，切出面板内部后贴合左右切点收束至 0，
                    // 避免矩形阴影在圆角外侧露馅。
                    val shadowRisePx = 28.dp.toPx()
                    val cornerPx = sheetCornerRadius.toPx().coerceIn(0f, minOf(size.width * 0.5f, size.height))
                    val maxAlpha = (if (colors.dark) 0.30f else 0.10f) * sheetAlpha
                    val sheetPath = buildAssistantSheetPath(size, cornerPx, 2.6f)
                    val rimPath = buildAssistantRimPath(size, cornerPx, 2.6f)
                    clipRect(left = 0f, top = -shadowRisePx, right = size.width, bottom = cornerPx) {
                        clipPath(sheetPath, clipOp = ClipOp.Difference) {
                            val layers = 8
                            for (i in 0 until layers) {
                                val fraction = (i + 1f) / layers
                                val spread = shadowRisePx * fraction
                                val falloff = 1f - i.toFloat() / layers
                                val layerAlpha = maxAlpha * falloff * falloff * (2.2f / layers)
                                if (layerAlpha > 0.001f) {
                                    drawPath(
                                        path = rimPath,
                                        color = Color.Black.copy(alpha = layerAlpha),
                                        style = Stroke(width = spread * 2f, cap = StrokeCap.Butt),
                                    )
                                }
                            }
                        }
                    }
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
                    val path = buildAssistantSheetPath(size, sheetCornerRadius.toPx(), 2.6f)
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
                    .height(with(density) { visibleSheetHeightPx.toDp() }),
            ) {
                var handleCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
                DragHandle(
                    colors = colors,
                    handoffReady = handoffReady,
                    handoffProgress = handoffPullProgress,
                    modifier = Modifier
                        .onGloballyPositioned { handleCoords = it }
                        .pointerInput(Unit) {
                            handleRootVerticalDrag(
                                getCoords = { handleCoords },
                                pass = PointerEventPass.Main,
                                shouldStart = { true },
                                onDragStart = { startDragState.value() },
                                onDrag = { deltaRootY -> dragByState.value(deltaRootY, true) },
                                onDragEnd = { finishDragState.value(true, true) },
                                onDragCancel = { finishDragState.value(false, false) },
                            )
                        },
                )
                var listBoxCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .onGloballyPositioned { listBoxCoords = it }
                        .pointerInput(Unit) {
                            handleRootVerticalDrag(
                                getCoords = { listBoxCoords },
                                pass = PointerEventPass.Initial,
                                shouldStart = { totalRootY -> shouldInterceptListDrag.value(totalRootY) },
                                onDragStart = { startDragState.value() },
                                onDrag = { deltaRootY -> dragByState.value(deltaRootY, false) },
                                onDragEnd = { finishDragState.value(true, false) },
                                onDragCancel = { finishDragState.value(false, false) },
                            )
                        }
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
            onInputEdited = onInputEdited,
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

private suspend fun PointerInputScope.handleRootVerticalDrag(
    getCoords: () -> LayoutCoordinates?,
    pass: PointerEventPass,
    shouldStart: (totalRootY: Float) -> Boolean,
    onDragStart: () -> Unit,
    onDrag: (deltaRootY: Float) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = pass)
        var activeId = down.id
        val startRoot = getCoords().toRootOffset(down.position)
        var lastRootY = startRoot.y
        val touchSlop = viewConfiguration.touchSlop
        var started = false
        var finished = false
        try {
            while (true) {
                val event = awaitPointerEvent(pass)
                val changes = event.changes
                val activeChange = changes.firstOrNull { it.id == activeId }
                if (activeChange == null) {
                    if (started) onDragCancel()
                    finished = true
                    break
                }
                if (!activeChange.pressed) {
                    val nextPointer = changes.firstOrNull { it.pressed }
                    if (nextPointer != null) {
                        activeId = nextPointer.id
                        lastRootY = getCoords().toRootOffset(nextPointer.position).y
                        if (started) nextPointer.consume()
                        continue
                    }
                    if (started) {
                        val releasedCleanly = activeChange.changedToUpIgnoreConsumed() &&
                            (pass == PointerEventPass.Initial || !activeChange.isConsumed)
                        if (releasedCleanly) {
                            onDragEnd()
                        } else {
                            onDragCancel()
                        }
                    }
                    finished = true
                    break
                }
                if (pass == PointerEventPass.Main && activeChange.isConsumed) {
                    if (started) onDragCancel()
                    finished = true
                    break
                }
                val currentRoot = getCoords().toRootOffset(activeChange.position)
                if (!started) {
                    val totalDx = currentRoot.x - startRoot.x
                    val totalDy = currentRoot.y - startRoot.y
                    if (abs(totalDx) > touchSlop && abs(totalDx) > abs(totalDy)) {
                        finished = true
                        break
                    }
                    if (abs(totalDy) > touchSlop && abs(totalDy) >= abs(totalDx)) {
                        if (!shouldStart(totalDy)) {
                            finished = true
                            break
                        }
                        started = true
                        onDragStart()
                        val overSlop = totalDy - sign(totalDy) * touchSlop
                        activeChange.consume()
                        lastRootY = currentRoot.y
                        if (overSlop != 0f) onDrag(overSlop)
                    }
                } else {
                    val deltaRootY = currentRoot.y - lastRootY
                    lastRootY = currentRoot.y
                    activeChange.consume()
                    if (deltaRootY != 0f) onDrag(deltaRootY)
                }
            }
        } finally {
            if (started && !finished) {
                onDragCancel()
            }
        }
    }
}

private fun LayoutCoordinates?.toRootOffset(localPosition: Offset): Offset {
    val coords = this?.takeIf { it.isAttached } ?: return localPosition
    return coords.localToRoot(localPosition)
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
                .padding(top = 15.dp)
                .graphicsLayer { translationY = -7.dp.toPx() * hintAlpha }
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
