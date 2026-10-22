package io.github.mangi.eta.agent.voice

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
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
import io.github.mangi.eta.R
import kotlinx.coroutines.flow.first
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text

@Composable
internal fun AssistantComposer(
    state: EtaVoiceUiState,
    input: String,
    speech: EtaSpeechState,
    recognitionEnabled: Boolean,
    inputFocusRequestKey: Int,
    onInputFocus: () -> Unit,
    onMicrophone: (holdToTalk: Boolean) -> Unit,
    onCancelSpeech: () -> Unit,
    onFinishSpeech: () -> Unit,
    onDownloadModel: () -> Unit,
    onOpenSpeechSettings: () -> Unit,
    onKeyboard: () -> Unit,
    keyboardVisible: Boolean,
    colors: EtaVoicePanelColors,
    onInputChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
    ) {
        if (recognitionEnabled) EtaSpeechFeedback(speech, onDownloadModel, onOpenSpeechSettings)
        AnimatedContent(
            // 按住说话期间保持输入栏：切换布局会移除正被按住的按钮，松手事件随之丢失。
            targetState = recognitionEnabled && speech.active && !speech.holdToTalk && state.messages.isEmpty(),
            transitionSpec = {
                fadeIn(tween(250)) togetherWith fadeOut(tween(160)) using
                    SizeTransform(clip = false)
            },
            contentAlignment = Alignment.BottomCenter,
            label = "assistant_input_mode",
        ) { voiceMode ->
            if (voiceMode) {
                AssistantVoiceEntry(speech, input, onKeyboard, onFinishSpeech)
            } else {
                AssistantInputBar(
                    state = state,
                    input = input,
                    speech = speech,
                    recognitionEnabled = recognitionEnabled,
                    inputFocusRequestKey = inputFocusRequestKey,
                    onInputFocus = onInputFocus,
                    onMicrophone = onMicrophone,
                    onCancelSpeech = onCancelSpeech,
                    onFinishSpeech = onFinishSpeech,
                    keyboardVisible = keyboardVisible,
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
    recognitionEnabled: Boolean,
    inputFocusRequestKey: Int,
    onInputFocus: () -> Unit,
    onMicrophone: (holdToTalk: Boolean) -> Unit,
    onCancelSpeech: () -> Unit,
    onFinishSpeech: () -> Unit,
    keyboardVisible: Boolean,
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
    val inputHighlighted = textFocused || keyboardVisible
    val inputBackground by animateColorAsState(
        targetValue = if (inputHighlighted) colors.focusedInput else colors.input,
        animationSpec = tween(180, easing = LinearOutSlowInEasing),
        label = "assistant_input_background",
    )
    val inputBorder by animateColorAsState(
        targetValue = colors.inputPrimary.copy(alpha = if (inputHighlighted) 0.35f else 0.12f),
        animationSpec = tween(180, easing = LinearOutSlowInEasing),
        label = "assistant_input_border",
    )
    val contentColor = colors.inputPrimary
    val hintColor = colors.inputTertiary
    val inputShape = RoundedCornerShape(24.dp)
    val trailingIcon = when {
        speech.active || state.phase == EtaVoicePhase.PROCESSING -> R.drawable.ic_assistant_stop
        canSubmit || !recognitionEnabled -> R.drawable.ic_assistant_send
        else -> R.drawable.ic_assistant_voice
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
    Row(
        modifier = modifier
            .heightIn(min = 50.dp)
            .clip(inputShape)
            .background(inputBackground)
            .border(
                if (inputHighlighted) 1.2.dp else 0.75.dp,
                inputBorder,
                inputShape,
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            )
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = input,
            onValueChange = onInputChange,
            modifier = Modifier
                .weight(1f)
                .padding(start = 14.dp, end = 2.dp, top = 6.dp, bottom = 6.dp)
                .onFocusChanged {
                    if (it.isFocused && !textFocused) onInputFocus()
                    textFocused = it.isFocused
                }
                .focusRequester(focusRequester),
            enabled = inputEnabled,
            textStyle = TextStyle(
                color = contentColor,
                fontSize = 16.sp,
                lineHeight = 24.sp,
            ),
            cursorBrush = SolidColor(contentColor),
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
                            fontSize = 16.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    innerTextField()
                }
            },
        )
        AssistantTrailingButton(
            iconRes = trailingIcon,
            contentDescription = stringResource(trailingDescription),
            tint = contentColor,
            enabled = speech.phase != EtaSpeechPhase.RECOGNIZING &&
                (recognitionEnabled || canSubmit || state.phase == EtaVoicePhase.PROCESSING),
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
    iconRes: Int,
    contentDescription: String,
    tint: Color,
    enabled: Boolean,
    holdToTalkAvailable: Boolean,
    onClick: () -> Unit,
    onHoldStart: () -> Unit,
    onHoldEnd: () -> Unit,
    onHoldCancel: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val currentClick by rememberUpdatedState(onClick)
    val currentHoldAvailable by rememberUpdatedState(holdToTalkAvailable)
    val currentHoldStart by rememberUpdatedState(onHoldStart)
    val currentHoldEnd by rememberUpdatedState(onHoldEnd)
    val currentHoldCancel by rememberUpdatedState(onHoldCancel)
    var pressed by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .size(40.dp)
            .graphicsLayer {
                val scale = if (pressed) 0.9f else 1f
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            // 手势检测不产生无障碍动作；读屏用户只需要点击语义，按住说话依赖触摸时长不适合读屏。
            .semantics(mergeDescendants = true) {
                role = Role.Button
                if (!enabled) disabled()
                onClick { if (enabled) currentClick(); enabled }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                var holding = false
                detectTapGestures(
                    onPress = {
                        pressed = true
                        val released = tryAwaitRelease()
                        pressed = false
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
                    onTap = { currentClick() },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            modifier = Modifier.size(24.dp),
            tint = if (enabled) tint else tint.copy(alpha = 0.4f),
        )
    }
}

@Composable
private fun AssistantVoiceEntry(
    speech: EtaSpeechState,
    input: String,
    onKeyboard: () -> Unit,
    onFinishSpeech: () -> Unit,
) {
    val controls = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(400)
        controls.animateTo(1f, tween(180))
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        Text(
            text = input.ifBlank {
                stringResource(
                    if (speech.phase == EtaSpeechPhase.RECOGNIZING) {
                        R.string.voice_recognizing
                    } else {
                        R.string.voice_listening
                    },
                )
            },
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
            style = TextStyle(
                shadow = Shadow(
                    color = Color.Black.copy(alpha = 0.55f),
                    offset = Offset(0f, 1.5f),
                    blurRadius = 3f,
                ),
            ),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).heightIn(min = 40.dp).padding(start = 6.dp, end = 14.dp, top = 8.dp),
        )
        AssistantRoundButton(
            iconRes = R.drawable.ic_assistant_keyboard_float,
            contentDescription = stringResource(R.string.voice_use_keyboard),
            progress = controls.value,
            enabled = controls.value >= 0.5f,
            onClick = onKeyboard,
        )
        Spacer(Modifier.width(12.dp))
        AssistantRoundButton(
            iconRes = R.drawable.ic_assistant_stop,
            contentDescription = stringResource(R.string.voice_finish_speech),
            progress = controls.value,
            enabled = controls.value >= 0.5f && speech.phase != EtaSpeechPhase.RECOGNIZING,
            onClick = onFinishSpeech,
        )
    }
}

@Composable
private fun AssistantRoundButton(
    iconRes: Int,
    contentDescription: String,
    progress: Float,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .graphicsLayer {
                alpha = progress
                scaleX = 0.8f + progress * 0.2f
                scaleY = 0.8f + progress * 0.2f
            }
            .clip(CircleShape)
            .background(Color(0xFFF4F5F7))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            tint = Color(0xE6000000),
            modifier = Modifier.size(22.dp),
        )
    }
}
