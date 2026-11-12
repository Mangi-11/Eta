package io.github.mangi.eta.agent.voice

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.itos.coui.motion.couiPressMotion
import com.itos.coui.motion.rememberCouiPressMotion
import io.github.mangi.eta.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Text

private val ComposerModeEasing = CubicBezierEasing(0.20f, 0f, 0f, 1f)

@Composable
internal fun AssistantComposer(
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
    keyboardVisible: Boolean,
    colors: EtaVoicePanelColors,
    onInputChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 按住说话期间保持输入栏：切换布局会移除正被按住的按钮，松手事件随之丢失。
    val voiceMode = recognitionEnabled && speech.active && !speech.holdToTalk && state.messages.isEmpty()

    Column(modifier = modifier) {
        if (recognitionEnabled) EtaSpeechFeedback(speech, colors, onOpenSpeechSettings)
        AnimatedContent(
            targetState = voiceMode,
            transitionSpec = {
                val enter = fadeIn(tween(200, easing = ComposerModeEasing)) +
                    scaleIn(tween(220, easing = ComposerModeEasing), initialScale = 0.96f)
                val exit = fadeOut(tween(130, easing = FastOutSlowInEasing)) +
                    scaleOut(tween(140, easing = FastOutSlowInEasing), targetScale = 0.96f)
                enter togetherWith exit using SizeTransform(
                    clip = false,
                    sizeAnimationSpec = { _, _ -> spring(dampingRatio = 0.92f, stiffness = 500f) },
                )
            },
            contentAlignment = Alignment.BottomCenter,
            label = "assistant_input_mode",
        ) { isVoiceMode ->
            if (isVoiceMode) {
                AssistantVoiceEntry(
                    speech = speech,
                    speechLevel = speechLevel,
                    input = input,
                    colors = colors,
                    onKeyboard = onKeyboard,
                    onFinishSpeech = onFinishSpeech,
                )
            } else {
                AssistantInputBar(
                    state = state,
                    input = input,
                    speech = speech,
                    speechLevel = speechLevel,
                    recognitionEnabled = recognitionEnabled,
                    inputFocusRequestKey = inputFocusRequestKey,
                    onInputFocus = onInputFocus,
                    keyboardVisible = keyboardVisible,
                    onMicrophone = onMicrophone,
                    onCancelSpeech = onCancelSpeech,
                    onFinishSpeech = onFinishSpeech,
                    colors = colors,
                    onInputChange = onInputChange,
                    onSubmit = onSubmit,
                    onStop = onStop,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun AssistantInputBar(
    state: EtaVoiceUiState,
    input: String,
    speech: EtaSpeechState,
    speechLevel: () -> Float,
    recognitionEnabled: Boolean,
    inputFocusRequestKey: Int,
    onInputFocus: () -> Unit,
    keyboardVisible: Boolean,
    onMicrophone: (holdToTalk: Boolean) -> Unit,
    onCancelSpeech: () -> Unit,
    onFinishSpeech: () -> Unit,
    colors: EtaVoicePanelColors,
    onInputChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val canSubmit = input.isNotBlank() && state.phase != EtaVoicePhase.PROCESSING
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val windowInfo = LocalWindowInfo.current
    val inputEnabled = state.phase != EtaVoicePhase.PROCESSING && !speech.active
    LaunchedEffect(inputFocusRequestKey, inputEnabled) {
        if (inputFocusRequestKey < 0 || !inputEnabled) return@LaunchedEffect
        // 输入栏挂载且浮窗取得焦点后再连接 IME，避免动画切换时请求落在尚不存在的输入框上。
        withFrameNanos { }
        snapshotFlow { windowInfo.isWindowFocused }.first { it }
        focusRequester.requestFocus()
        withFrameNanos { }
        keyboard?.show()
    }
    var textFocused by remember { mutableStateOf(false) }
    val inputHighlighted = textFocused || keyboardVisible || speech.holdToTalk
    val inputBackground by animateColorAsState(
        targetValue = if (inputHighlighted) colors.focusedInput else colors.input,
        animationSpec = tween(190, easing = LinearOutSlowInEasing),
        label = "assistant_input_background",
    )
    val highlightProgress by animateFloatAsState(
        targetValue = if (inputHighlighted) 1f else 0f,
        animationSpec = tween(210, easing = FastOutSlowInEasing),
        label = "assistant_input_highlight",
    )
    val contentColor = colors.inputPrimary
    val hintColor = if (speech.holdToTalk) colors.accent else colors.inputTertiary
    val glyphKind = when {
        speech.active && !speech.holdToTalk -> AssistantGlyphKind.Stop
        state.phase == EtaVoicePhase.PROCESSING -> AssistantGlyphKind.Stop
        speech.holdToTalk -> AssistantGlyphKind.Voice
        canSubmit || !recognitionEnabled -> AssistantGlyphKind.Send
        else -> AssistantGlyphKind.Voice
    }
    val trailingDescription = when {
        speech.active -> R.string.voice_finish_speech
        state.phase == EtaVoicePhase.PROCESSING -> R.string.action_stop
        canSubmit || !recognitionEnabled -> R.string.voice_send
        else -> R.string.voice_tap_to_speak
    }
    val trailingAction = when {
        speech.active -> onFinishSpeech
        state.phase == EtaVoicePhase.PROCESSING -> onStop
        canSubmit || !recognitionEnabled -> onSubmit
        else -> { { onMicrophone(false) } }
    }
    val trailingEnabled = speech.phase != EtaSpeechPhase.RECOGNIZING &&
        (recognitionEnabled || canSubmit || state.phase == EtaVoicePhase.PROCESSING)
    val trailingContainerColor by animateColorAsState(
        targetValue = when {
            speech.holdToTalk -> colors.accent
            speech.active || state.phase == EtaVoicePhase.PROCESSING -> colors.inputPrimary
            canSubmit -> colors.accent
            else -> colors.controlSurface
        },
        animationSpec = tween(180, easing = FastOutSlowInEasing),
        label = "assistant_trailing_bg",
    )
    val trailingIconTint by animateColorAsState(
        targetValue = when {
            speech.holdToTalk || canSubmit -> colors.onAccent
            speech.active || state.phase == EtaVoicePhase.PROCESSING ->
                if (colors.dark) Color(0xFF17191E) else Color.White
            !trailingEnabled -> colors.inputTertiary.copy(alpha = 0.42f)
            else -> colors.inputPrimary
        },
        animationSpec = tween(180, easing = FastOutSlowInEasing),
        label = "assistant_trailing_tint",
    )

    Row(
        modifier = modifier
            .heightIn(min = 50.dp)
            .couiAssistantCapsule(
                surfaceColor = inputBackground,
                strokeColor = colors.chipStroke,
                dark = colors.dark,
                elevation = AssistantCapsuleElevation.Composer,
                highlightProgress = highlightProgress,
                highlightColor = colors.accent.copy(alpha = if (colors.dark) 0.44f else 0.30f),
                interactive = true,
                spotlightEnabled = true,
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            )
            .padding(start = 18.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = input,
            onValueChange = onInputChange,
            modifier = Modifier
                .weight(1f)
                .padding(end = 8.dp, top = 9.dp, bottom = 9.dp)
                .onFocusChanged {
                    if (it.isFocused && !textFocused) onInputFocus()
                    textFocused = it.isFocused
                }
                .focusRequester(focusRequester),
            enabled = inputEnabled,
            textStyle = TextStyle(
                color = contentColor,
                fontSize = 15.5.sp,
                lineHeight = 22.sp,
            ),
            cursorBrush = SolidColor(colors.accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { if (canSubmit) onSubmit() }),
            maxLines = 4,
            minLines = 1,
            decorationBox = { innerTextField ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (input.isEmpty()) {
                        Text(
                            text = stringResource(
                                if (speech.holdToTalk) R.string.voice_release_to_send else R.string.voice_input_hint,
                            ),
                            color = hintColor,
                            fontSize = 15.5.sp,
                            lineHeight = 22.sp,
                            fontWeight = if (speech.holdToTalk) FontWeight.Medium else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    innerTextField()
                }
            },
        )
        AssistantTrailingButton(
            glyphKind = glyphKind,
            contentDescription = stringResource(trailingDescription),
            containerColor = trailingContainerColor,
            iconTint = trailingIconTint,
            accentColor = colors.accent,
            holdingSpeech = speech.holdToTalk,
            speechLevel = speechLevel,
            enabled = trailingEnabled,
            holdToTalkAvailable = recognitionEnabled && !speech.active && !canSubmit && state.phase != EtaVoicePhase.PROCESSING,
            onClick = trailingAction,
            onHoldStart = { onMicrophone(true) },
            onHoldEnd = onFinishSpeech,
            onHoldCancel = onCancelSpeech,
        )
    }
}

/**
 * 输入栏尾部按钮。空闲时长按进入按住说话：松手发送，手指移出按钮取消；
 * 长按触发后不再派发点击，避免松手时又按旧状态开始一次新的录音。
 */
@Composable
private fun AssistantTrailingButton(
    glyphKind: AssistantGlyphKind,
    contentDescription: String,
    containerColor: Color,
    iconTint: Color,
    accentColor: Color,
    holdingSpeech: Boolean,
    speechLevel: () -> Float,
    enabled: Boolean,
    holdToTalkAvailable: Boolean,
    onClick: () -> Unit,
    onHoldStart: () -> Unit,
    onHoldEnd: () -> Unit,
    onHoldCancel: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val press = rememberCouiPressMotion()
    val currentClick by rememberUpdatedState(onClick)
    val currentHoldAvailable by rememberUpdatedState(holdToTalkAvailable)
    val currentHoldStart by rememberUpdatedState(onHoldStart)
    val currentHoldEnd by rememberUpdatedState(onHoldEnd)
    val currentHoldCancel by rememberUpdatedState(onHoldCancel)

    Box(
        modifier = Modifier
            .size(38.dp)
            .graphicsLayer {
                val scale = press.scale.value * if (holdingSpeech) 1.06f else 1f
                scaleX = scale
                scaleY = scale
            }
            .drawBehind {
                if (holdingSpeech) {
                    val pulse = speechLevel().coerceIn(0f, 1f)
                    drawCircle(
                        color = accentColor.copy(alpha = 0.22f + 0.22f * pulse),
                        radius = size.minDimension * (0.56f + 0.16f * pulse),
                    )
                }
            }
            .clip(CircleShape)
            .background(containerColor, CircleShape)
            // 手势检测不产生无障碍动作；读屏用户只需要点击语义，按住说话依赖触摸时长不适合读屏。
            .semantics(mergeDescendants = true) {
                this.contentDescription = contentDescription
                role = Role.Button
                if (!enabled) disabled()
                onClick {
                    if (enabled) currentClick()
                    enabled
                }
            }
            .pointerInput(enabled, press) {
                if (!enabled) return@pointerInput
                var holding = false
                detectTapGestures(
                    onPress = {
                        press.press(size)
                        val released = tryAwaitRelease()
                        press.release()
                        if (holding) {
                            holding = false
                            if (released) currentHoldEnd() else currentHoldCancel()
                        }
                    },
                    onLongPress = {
                        if (currentHoldAvailable) {
                            holding = true
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            currentHoldStart()
                        }
                    },
                    onTap = {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        currentClick()
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = glyphKind,
            transitionSpec = {
                (fadeIn(tween(140)) + scaleIn(tween(170, easing = ComposerModeEasing), initialScale = 0.72f))
                    .togetherWith(
                        fadeOut(tween(90)) + scaleOut(tween(110), targetScale = 0.72f),
                    )
            },
            label = "assistant_trailing_glyph",
        ) { targetGlyph ->
            AssistantActionGlyph(
                kind = targetGlyph,
                tint = iconTint,
                modifier = Modifier.size(if (targetGlyph == AssistantGlyphKind.Stop) 18.dp else 19.dp),
            )
        }
    }
}

/**
 * 语音聆听态采用“左侧自适应紧凑状态胶囊 + 右侧双独立圆形悬浮按钮”布局：
 * 空闲聆听时留出中部视野供底部多彩声波舒展，识别出实时文字时胶囊随内容自然向右生长。
 */
@Composable
private fun AssistantVoiceEntry(
    speech: EtaSpeechState,
    speechLevel: () -> Float,
    input: String,
    colors: EtaVoicePanelColors,
    onKeyboard: () -> Unit,
    onFinishSpeech: () -> Unit,
) {
    val pillProgress = remember { Animatable(0f) }
    val keyboardProgress = remember { Animatable(0f) }
    val stopProgress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        launch {
            pillProgress.animateTo(1f, tween(220, easing = ComposerModeEasing))
        }
        launch {
            delay(55)
            keyboardProgress.animateTo(1f, tween(210, easing = ComposerModeEasing))
        }
        launch {
            delay(100)
            stopProgress.animateTo(1f, tween(220, easing = ComposerModeEasing))
        }
    }

    val hasTranscript = input.isNotBlank()
    val statusLabel = stringResource(
        if (speech.phase == EtaSpeechPhase.RECOGNIZING) {
            R.string.voice_recognizing
        } else {
            R.string.voice_listening
        },
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Row(
                modifier = Modifier
                    .heightIn(min = 44.dp)
                    .graphicsLayer {
                        val p = pillProgress.value
                        alpha = p
                        val s = 0.92f + 0.08f * p
                        scaleX = s
                        scaleY = s
                    }
                    .animateContentSize(
                        animationSpec = spring(dampingRatio = 0.92f, stiffness = 480f),
                    )
                    .couiAssistantCapsule(
                        surfaceColor = colors.chipBackground,
                        strokeColor = colors.chipStroke,
                        dark = colors.dark,
                        elevation = AssistantCapsuleElevation.Chip,
                        interactive = false,
                    )
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    )
                    .padding(start = 13.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AssistantVoiceOrb(
                    phase = speech.phase,
                    level = speechLevel,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(8.dp))
                if (hasTranscript) {
                    Text(
                        text = input,
                        fontSize = 14.5.sp,
                        lineHeight = 20.sp,
                        fontWeight = FontWeight.Normal,
                        color = colors.inputPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    AnimatedContent(
                        targetState = statusLabel,
                        transitionSpec = {
                            (fadeIn(tween(180)) + slideInVertically(tween(180)) { it / 3 })
                                .togetherWith(fadeOut(tween(120)) + slideOutVertically(tween(120)) { -it / 3 })
                        },
                        label = "assistant_voice_status_label",
                    ) { text ->
                        Text(
                            text = text,
                            fontSize = 14.5.sp,
                            lineHeight = 20.sp,
                            fontWeight = FontWeight.Medium,
                            color = colors.inputPrimary.copy(alpha = 0.88f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        AssistantFloatingRoundButton(
            glyphKind = AssistantGlyphKind.Keyboard,
            contentDescription = stringResource(R.string.voice_use_keyboard),
            surfaceColor = colors.chipBackground,
            strokeColor = colors.chipStroke,
            iconTint = colors.inputPrimary,
            dark = colors.dark,
            progress = keyboardProgress.value,
            enabled = keyboardProgress.value >= 0.4f,
            onClick = onKeyboard,
        )
        Spacer(Modifier.width(10.dp))
        AssistantFloatingRoundButton(
            glyphKind = AssistantGlyphKind.Stop,
            contentDescription = stringResource(R.string.voice_finish_speech),
            surfaceColor = colors.accent,
            strokeColor = Color.Transparent,
            iconTint = colors.onAccent,
            dark = colors.dark,
            progress = stopProgress.value,
            enabled = stopProgress.value >= 0.4f && speech.phase != EtaSpeechPhase.RECOGNIZING,
            onClick = onFinishSpeech,
        )
    }
}

@Composable
private fun AssistantFloatingRoundButton(
    glyphKind: AssistantGlyphKind,
    contentDescription: String,
    surfaceColor: Color,
    strokeColor: Color,
    iconTint: Color,
    dark: Boolean,
    progress: Float,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val interactionSource = remember { MutableInteractionSource() }
    val press = rememberCouiPressMotion()
    Box(
        modifier = Modifier
            .size(44.dp)
            .graphicsLayer {
                val entryScale = 0.84f + progress * 0.16f
                val totalScale = entryScale * press.scale.value
                alpha = progress
                scaleX = totalScale
                scaleY = totalScale
            }
            .couiPressMotion(press, enabled = enabled)
            .couiAssistantCapsule(
                surfaceColor = surfaceColor,
                strokeColor = strokeColor,
                dark = dark,
                elevation = AssistantCapsuleElevation.Chip,
                interactive = enabled,
                interactionSource = interactionSource,
                spotlightEnabled = true,
            )
            .semantics(mergeDescendants = true) {
                this.contentDescription = contentDescription
                role = Role.Button
                if (!enabled) disabled()
            }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onClick()
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        AssistantActionGlyph(
            kind = glyphKind,
            tint = if (enabled) iconTint else iconTint.copy(alpha = 0.42f),
            modifier = Modifier.size(if (glyphKind == AssistantGlyphKind.Stop) 19.dp else 20.dp),
        )
    }
}
