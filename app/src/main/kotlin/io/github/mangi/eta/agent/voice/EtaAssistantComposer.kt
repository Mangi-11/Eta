package io.github.mangi.eta.agent.voice

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.mangi.eta.R
import java.util.UUID
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text

@Composable
internal fun AssistantComposer(
    state: EtaVoiceUiState,
    input: String,
    speech: EtaSpeechState,
    onMicrophone: () -> Unit,
    onFinishSpeech: () -> Unit,
    onDownloadModel: () -> Unit,
    onOpenSpeechSettings: () -> Unit,
    onKeyboard: () -> Unit,
    onClose: () -> Unit,
    keyboardVisible: Boolean,
    colors: EtaVoicePanelColors,
    focusRequester: FocusRequester,
    onInputChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onSupplement: (String, String, (Boolean) -> Unit) -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
    ) {
        EtaSpeechFeedback(speech, onDownloadModel, onOpenSpeechSettings)
        AnimatedContent(
            targetState = speech.active && state.messages.isEmpty(),
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
                    onMicrophone = onMicrophone,
                    onFinishSpeech = onFinishSpeech,
                    onKeyboard = onKeyboard,
                    onClose = onClose,
                    keyboardVisible = keyboardVisible,
                    colors = colors,
                    focusRequester = focusRequester,
                    onInputChange = onInputChange,
                    onSubmit = onSubmit,
                    onSupplement = onSupplement,
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
    onMicrophone: () -> Unit,
    onFinishSpeech: () -> Unit,
    onKeyboard: () -> Unit,
    onClose: () -> Unit,
    keyboardVisible: Boolean,
    colors: EtaVoicePanelColors,
    focusRequester: FocusRequester,
    onInputChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onSupplement: (String, String, (Boolean) -> Unit) -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val canSubmit = input.isNotBlank() && state.phase != EtaVoicePhase.PROCESSING
    var supplementRequestId by remember { mutableStateOf<String?>(null) }
    var supplementRequestText by remember { mutableStateOf<String?>(null) }
    var supplementInFlight by remember { mutableStateOf(false) }
    val currentInput by rememberUpdatedState(input)
    val canSupplement = input.isNotBlank() &&
        state.phase == EtaVoicePhase.PROCESSING &&
        !speech.active &&
        !supplementInFlight
    val submitSupplement: () -> Unit = {
        if (canSupplement) {
            val supplementText = input
            val requestId = if (supplementRequestText == supplementText && supplementRequestId != null) {
                supplementRequestId!!
            } else {
                UUID.randomUUID().toString().also {
                    supplementRequestId = it
                    supplementRequestText = supplementText
                }
            }
            supplementInFlight = true
            onSupplement(supplementText, requestId) { accepted ->
                if (supplementRequestId != requestId) return@onSupplement
                supplementInFlight = false
                if (accepted && currentInput == supplementText) {
                    onInputChange("")
                    supplementRequestId = null
                    supplementRequestText = null
                } else if (accepted || currentInput != supplementText) {
                    supplementRequestId = null
                    supplementRequestText = null
                }
            }
        }
    }
    var textFocused by remember { mutableStateOf(false) }
    val inputHighlighted = textFocused || keyboardVisible
    val contentColor = colors.inputPrimary
    val secondaryColor = colors.inputSecondary
    val hintColor = colors.inputTertiary
    val inputShape = RoundedCornerShape(24.dp)
    val trailingIcon = when {
        speech.active || state.phase == EtaVoicePhase.PROCESSING -> R.drawable.ic_assistant_stop
        canSubmit -> R.drawable.ic_assistant_send
        else -> R.drawable.ic_assistant_voice
    }
    val trailingDescription = when {
        speech.active -> R.string.voice_finish_speech
        state.phase == EtaVoicePhase.PROCESSING -> R.string.action_stop
        canSubmit -> R.string.voice_send
        else -> R.string.voice_tap_to_speak
    }
    val trailingAction = when {
        speech.active -> onFinishSpeech
        state.phase == EtaVoicePhase.PROCESSING -> onStop
        canSubmit -> onSubmit
        else -> onMicrophone
    }
    Row(
        modifier = modifier
            .heightIn(min = 50.dp)
            .clip(inputShape)
            .background(if (inputHighlighted) colors.focusedInput else colors.input)
            .border(
                if (inputHighlighted) 1.2.dp else 0.75.dp,
                contentColor.copy(alpha = if (inputHighlighted) 0.35f else 0.12f),
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
        IconButton(
            onClick = if (speech.active) onKeyboard else onClose,
            minWidth = 40.dp,
            minHeight = 40.dp,
            backgroundColor = Color.Transparent,
        ) {
            Icon(
                painter = painterResource(if (speech.active) R.drawable.ic_assistant_keyboard_float else R.drawable.ic_assistant_home),
                contentDescription = stringResource(if (speech.active) R.string.voice_use_keyboard else R.string.action_close),
                tint = secondaryColor,
                modifier = Modifier.size(24.dp),
            )
        }
        BasicTextField(
            value = input,
            onValueChange = onInputChange,
            modifier = Modifier
                .weight(1f)
                .padding(start = 4.dp, end = 2.dp, top = 6.dp, bottom = 6.dp)
                .onFocusChanged { textFocused = it.isFocused }
                .focusRequester(focusRequester),
            enabled = !speech.active,
            textStyle = TextStyle(
                color = contentColor,
                fontSize = 16.sp,
                lineHeight = 24.sp,
            ),
            cursorBrush = SolidColor(contentColor),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = {
                if (state.phase == EtaVoicePhase.PROCESSING) {
                    submitSupplement()
                } else if (canSubmit) {
                    onSubmit()
                }
            }),
            maxLines = 4,
            minLines = 1,
            decorationBox = { innerTextField ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (input.isEmpty()) {
                        Text(
                            text = stringResource(R.string.voice_input_hint),
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
        if (state.phase == EtaVoicePhase.PROCESSING && !speech.active) {
            IconButton(
                onClick = submitSupplement,
                enabled = canSupplement && speech.phase != EtaSpeechPhase.RECOGNIZING,
                minWidth = 40.dp,
                minHeight = 40.dp,
                backgroundColor = Color.Transparent,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_assistant_send),
                    contentDescription = stringResource(R.string.voice_supplement),
                    modifier = Modifier.size(24.dp),
                    tint = if (canSupplement) contentColor else hintColor,
                )
            }
            IconButton(
                onClick = onStop,
                minWidth = 40.dp,
                minHeight = 40.dp,
                backgroundColor = Color.Transparent,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_assistant_stop),
                    contentDescription = stringResource(R.string.action_stop),
                    modifier = Modifier.size(24.dp),
                    tint = contentColor,
                )
            }
        } else {
            IconButton(
                onClick = trailingAction,
                enabled = speech.phase != EtaSpeechPhase.RECOGNIZING,
                minWidth = 40.dp,
                minHeight = 40.dp,
                backgroundColor = Color.Transparent,
            ) {
                Icon(
                    painter = painterResource(trailingIcon),
                    contentDescription = stringResource(trailingDescription),
                    modifier = Modifier.size(24.dp),
                    tint = contentColor,
                )
            }
        }
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
