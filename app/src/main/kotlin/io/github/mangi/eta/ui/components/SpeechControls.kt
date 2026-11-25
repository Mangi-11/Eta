package io.github.mangi.eta.ui.components

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.voice.ui.EtaSpeechPhase
import io.github.mangi.eta.agent.voice.ReadAloudPlaybackService
import io.github.mangi.eta.agent.voice.asr.SpeechInputController
import io.github.mangi.eta.agent.voice.tts.SpeechPlaybackController
import io.github.mangi.eta.data.model.TtsProvider
import io.github.mangi.eta.data.repository.SpeechSettingsRepository
import io.github.mangi.eta.ui.MainActivity
import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.SystemNoticeCode
import io.github.mangi.eta.ui.model.SystemNoticeMessageUi
import io.github.mangi.eta.ui.components.chat.AnswerActionButton
import io.github.mangi.eta.ui.components.chat.ChatActionIcons
import io.github.mangi.eta.ui.components.StatusError
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh
import kotlin.random.Random
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.squircle.squircleBorder
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

internal const val ACTION_SPEECH_SETTINGS = "io.github.mangi.eta.OPEN_SPEECH_SETTINGS"

internal fun openSpeechSettings(context: Context) {
    context.startActivity(
        Intent(context, MainActivity::class.java).setAction(ACTION_SPEECH_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
    )
}

/** 页面不可见或销毁时终止音频任务。 */
@Composable
internal fun SpeechLifecycle(onStop: () -> Unit) {
    val owner = LocalLifecycleOwner.current
    val stop by rememberUpdatedState(onStop)
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) stop() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); stop() }
    }
}

@Composable
internal fun rememberSpeechInput(
    onHoldResult: ((String) -> Unit)? = null,
    onResult: (String) -> Unit,
): SpeechInputController {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val result by rememberUpdatedState(onResult)
    val holdResult by rememberUpdatedState(onHoldResult)
    val controller = remember(context, scope) {
        SpeechInputController(
            context = context,
            scope = scope,
            onResult = { result(it) },
            onHoldResult = { recognized ->
                val handler = holdResult
                if (handler != null) handler(recognized) else result(recognized)
            },
        )
    }
    SpeechLifecycle { controller.cancel() }
    return controller
}

@Composable
internal fun rememberSpeechPermission(onGranted: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val deniedMessage = stringResource(R.string.speech_mic_permission)
    val action by rememberUpdatedState(onGranted)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) action() else Toast.makeText(context, deniedMessage, Toast.LENGTH_SHORT).show()
    }
    return {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            action()
        } else {
            launcher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
}

private const val HOLD_TO_TALK_THRESHOLD_MS = 150L
private val HoldCancelMargin = 36.dp
private val HoldMaxMagneticOffset = 5.dp
private val PressDownSpring = spring<Float>(dampingRatio = 0.78f, stiffness = 860f)
private val ReleaseSpring = spring<Float>(dampingRatio = 0.72f, stiffness = 520f)
private val MagneticReturnSpring = spring<Float>(dampingRatio = 0.68f, stiffness = 480f)

private enum class MicGlyphState {
    IDLE,
    TAP_FINISH,
    HOLD_TALKING,
    HOLD_CANCEL,
    RECOGNIZING,
}

internal fun isInHoldCancelZone(
    position: Offset,
    width: Float,
    height: Float,
    cancelMarginPx: Float,
): Boolean =
    position.y < -cancelMarginPx ||
        position.y > height + cancelMarginPx ||
        position.x < -cancelMarginPx ||
        position.x > width + cancelMarginPx

/** 输入栏语音入口：轻点进入两步听写（点开始、点完成），按住说话并在安全区松手直接自动发送，移出取消。 */
@Composable
internal fun SpeechDictationButton(
    controller: SpeechInputController,
    enabled: Boolean,
    holdCancelling: Boolean = false,
    onHoldCancellingChange: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val state by controller.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val animScope = rememberCoroutineScope()
    val requestTapPermission = rememberSpeechPermission { if (enabled) controller.start(holdToTalk = false) }
    val requestPermissionOnly = rememberSpeechPermission {}

    val currentEnabled by rememberUpdatedState(enabled)
    val currentRequestTap by rememberUpdatedState(requestTapPermission)
    val currentRequestPermissionOnly by rememberUpdatedState(requestPermissionOnly)
    val currentOnHoldCancellingChange by rememberUpdatedState(onHoldCancellingChange)

    val pressScale = remember { Animatable(1f) }
    val dragOffsetX = remember { Animatable(0f) }
    val dragOffsetY = remember { Animatable(0f) }

    val holdVisualActive = state.active && state.holdToTalk && state.phase != EtaSpeechPhase.RECOGNIZING
    val tapActive = state.active && !state.holdToTalk && state.phase != EtaSpeechPhase.RECOGNIZING
    val recognizing = state.phase == EtaSpeechPhase.RECOGNIZING

    val holdProgress by animateFloatAsState(
        targetValue = if (holdVisualActive) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 420f),
        label = "mic_hold_progress",
    )
    val tapActiveProgress by animateFloatAsState(
        targetValue = if (tapActive) 1f else 0f,
        animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
        label = "mic_tap_active_progress",
    )
    val cancelProgress by animateFloatAsState(
        targetValue = if (holdVisualActive && holdCancelling) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.85f, stiffness = 480f),
        label = "mic_cancel_progress",
    )

    var pulseLevel by remember { mutableFloatStateOf(0f) }
    var pulsePhase by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(holdVisualActive, holdProgress > 0.01f) {
        if (!holdVisualActive && holdProgress <= 0.01f) {
            pulseLevel = 0f
            pulsePhase = 0f
            return@LaunchedEffect
        }
        var lastNanos = 0L
        while (true) {
            withFrameNanos { nanos ->
                val dt = if (lastNanos == 0L) 0.016f else ((nanos - lastNanos) / 1_000_000_000f).coerceIn(0f, 0.05f)
                lastNanos = nanos
                val target = if (holdVisualActive) sqrt(controller.state.value.level.coerceIn(0f, 1f)) else 0f
                val rate = if (target > pulseLevel) 0.36f else 0.14f
                pulseLevel += (target - pulseLevel) * rate
                pulsePhase = (pulsePhase + dt * 4.4f) % (PI.toFloat() * 2f)
            }
        }
    }

    val primaryColor = MiuixTheme.colorScheme.primary
    val onPrimaryColor = MiuixTheme.colorScheme.onPrimary
    val errorColor = StatusError
    val holdFillColor = lerp(primaryColor, errorColor, cancelProgress)
    val glyphState = when {
        holdVisualActive && holdCancelling -> MicGlyphState.HOLD_CANCEL
        holdVisualActive -> MicGlyphState.HOLD_TALKING
        recognizing -> MicGlyphState.RECOGNIZING
        tapActive -> MicGlyphState.TAP_FINISH
        else -> MicGlyphState.IDLE
    }
    val iconTint by animateColorAsState(
        targetValue = when (glyphState) {
            MicGlyphState.HOLD_TALKING, MicGlyphState.HOLD_CANCEL -> onPrimaryColor
            MicGlyphState.TAP_FINISH -> primaryColor
            MicGlyphState.RECOGNIZING -> primaryColor.copy(alpha = 0.56f)
            MicGlyphState.IDLE -> MiuixTheme.colorScheme.onSurface.copy(alpha = if (enabled) 0.68f else 0.34f)
        },
        animationSpec = tween(durationMillis = 160, easing = FastOutSlowInEasing),
        label = "mic_icon_tint",
    )
    val contentDesc = stringResource(
        when {
            holdVisualActive && holdCancelling -> R.string.speech_hold_release_to_cancel
            holdVisualActive -> R.string.voice_release_to_send
            state.active -> R.string.speech_dictation_finish
            else -> R.string.speech_dictation
        },
    )
    val cancelMarginPx = with(density) { HoldCancelMargin.toPx() }
    val maxMagneticPx = with(density) { HoldMaxMagneticOffset.toPx() }

    Box(
        modifier = modifier
            .size(34.dp)
            .graphicsLayer {
                val scale = pressScale.value * (1f + 0.18f * holdProgress - 0.06f * cancelProgress)
                scaleX = scale
                scaleY = scale
                translationX = dragOffsetX.value
                translationY = dragOffsetY.value
            }
            .drawBehind {
                val radius = size.minDimension / 2f
                if (tapActiveProgress > 0.005f) {
                    drawCircle(
                        color = primaryColor.copy(alpha = 0.12f * tapActiveProgress),
                        radius = radius * (0.84f + 0.16f * tapActiveProgress),
                    )
                }
                if (holdProgress > 0.005f) {
                    val wave = 0.5f + 0.5f * sin(pulsePhase)
                    val outerRadius = radius * (1.24f + 0.34f * pulseLevel + 0.06f * wave) * holdProgress
                    val outerAlpha = (0.16f + 0.20f * pulseLevel) * holdProgress * (1f - 0.18f * cancelProgress)
                    drawCircle(
                        color = holdFillColor.copy(alpha = outerAlpha),
                        radius = outerRadius,
                    )
                    drawCircle(
                        color = holdFillColor.copy(alpha = (0.24f + 0.12f * pulseLevel) * holdProgress),
                        radius = radius * (1.06f + 0.16f * pulseLevel) * holdProgress,
                    )
                    drawCircle(
                        color = holdFillColor.copy(alpha = holdProgress),
                        radius = radius * (0.76f + 0.24f * holdProgress),
                    )
                }
            }
            .semantics(mergeDescendants = true) {
                contentDescription = contentDesc
                role = Role.Button
                if (!enabled || recognizing) disabled()
                onClick {
                    if (!currentEnabled || controller.state.value.phase == EtaSpeechPhase.RECOGNIZING) {
                        false
                    } else {
                        if (controller.state.value.active) controller.finish() else currentRequestTap()
                        true
                    }
                }
            }
            .pointerInput(cancelMarginPx, maxMagneticPx) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val initialSnapshot = controller.state.value
                    if (!currentEnabled || initialSnapshot.phase == EtaSpeechPhase.RECOGNIZING) {
                        return@awaitEachGesture
                    }
                    // 轻点听写进行中：按下微缩反馈，抬起仍在控件周边即完成识别。
                    if (initialSnapshot.active && !initialSnapshot.holdToTalk) {
                        animScope.launch { pressScale.animateTo(0.88f, PressDownSpring) }
                        var finishedByTap = false
                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id }
                                if (change == null || change.isConsumed) break
                                if (!change.pressed) {
                                    val inside = change.position.x in -cancelMarginPx..(size.width + cancelMarginPx) &&
                                        change.position.y in -cancelMarginPx..(size.height + cancelMarginPx)
                                    if (inside) {
                                        change.consume()
                                        finishedByTap = true
                                    }
                                    break
                                }
                            }
                        } finally {
                            animScope.launch { pressScale.animateTo(1f, ReleaseSpring) }
                        }
                        if (finishedByTap && controller.state.value.active) {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            controller.finish()
                        }
                        return@awaitEachGesture
                    }

                    if (initialSnapshot.active) return@awaitEachGesture

                    animScope.launch { pressScale.animateTo(0.88f, PressDownSpring) }
                    val holdTriggered = withTimeoutOrNull(HOLD_TO_TALK_THRESHOLD_MS) {
                        var aborted = false
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id }
                            if (change == null || change.isConsumed) {
                                aborted = true
                                break
                            }
                            if (!change.pressed) {
                                change.consume()
                                animScope.launch { pressScale.animateTo(1f, ReleaseSpring) }
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                currentRequestTap()
                                aborted = true
                                break
                            }
                            if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                                aborted = true
                                break
                            }
                        }
                        !aborted
                    } ?: true

                    if (!holdTriggered) {
                        animScope.launch { pressScale.animateTo(1f, ReleaseSpring) }
                        return@awaitEachGesture
                    }

                    val hasMicPermission =
                        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                    if (!hasMicPermission) {
                        animScope.launch { pressScale.animateTo(1f, ReleaseSpring) }
                        currentRequestPermissionOnly()
                        return@awaitEachGesture
                    }

                    down.consume()
                    animScope.launch { pressScale.animateTo(1f, ReleaseSpring) }
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    currentOnHoldCancellingChange(false)
                    controller.start(holdToTalk = true)

                    var cancelledZone = false
                    var releasedNormally = false
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id }
                            if (change == null || change.isConsumed || !currentEnabled || !controller.state.value.active) {
                                break
                            }
                            if (!change.pressed) {
                                change.consume()
                                releasedNormally = true
                                break
                            }
                            change.consume()
                            val pos = change.position
                            val dx = pos.x - size.width / 2f
                            val dy = pos.y - size.height / 2f
                            val offsetX = maxMagneticPx * tanh(dx / (maxMagneticPx * 4f))
                            val offsetY = maxMagneticPx * tanh(dy / (maxMagneticPx * 4f))
                            animScope.launch {
                                dragOffsetX.snapTo(offsetX)
                                dragOffsetY.snapTo(offsetY)
                            }
                            val inCancelZone = isInHoldCancelZone(
                                position = pos,
                                width = size.width.toFloat(),
                                height = size.height.toFloat(),
                                cancelMarginPx = cancelMarginPx,
                            )
                            if (inCancelZone != cancelledZone) {
                                cancelledZone = inCancelZone
                                currentOnHoldCancellingChange(inCancelZone)
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                        }
                    } finally {
                        currentOnHoldCancellingChange(false)
                        animScope.launch {
                            pressScale.animateTo(1f, ReleaseSpring)
                        }
                        animScope.launch {
                            dragOffsetX.animateTo(0f, MagneticReturnSpring)
                        }
                        animScope.launch {
                            dragOffsetY.animateTo(0f, MagneticReturnSpring)
                        }
                    }

                    if (releasedNormally && !cancelledZone) {
                        controller.finish()
                    } else if (controller.state.value.active) {
                        controller.cancel()
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = glyphState,
            transitionSpec = {
                (fadeIn(tween(130)) + scaleIn(tween(170, easing = FastOutSlowInEasing), initialScale = 0.70f))
                    .togetherWith(
                        fadeOut(tween(90)) + scaleOut(tween(110), targetScale = 0.70f),
                    )
            },
            label = "mic_glyph_transition",
        ) { targetGlyph ->
            Icon(
                imageVector = when (targetGlyph) {
                    MicGlyphState.HOLD_CANCEL -> Icons.Rounded.Close
                    MicGlyphState.TAP_FINISH -> Icons.Rounded.Check
                    MicGlyphState.IDLE, MicGlyphState.HOLD_TALKING, MicGlyphState.RECOGNIZING -> Icons.Rounded.Mic
                },
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = iconTint,
            )
        }
    }
}

/** 输入栏上方的听写状态条：录音中显示电平与完成/取消，按住说话实时反馈送出/取消态，错误给出设置或关闭出口。 */
@Composable
internal fun SpeechInputFeedback(
    controller: SpeechInputController,
    holdCancelling: Boolean = false,
) {
    val state by controller.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val isHold = state.active && state.holdToTalk
    val isHoldCancel = isHold && holdCancelling
    val baseContainer = MiuixTheme.colorScheme.surfaceContainer
    val primaryColor = MiuixTheme.colorScheme.primary
    val errorColor = StatusError
    val containerColor by animateColorAsState(
        targetValue = when {
            isHoldCancel -> lerp(baseContainer, errorColor, 0.10f)
            isHold -> lerp(baseContainer, primaryColor, 0.07f)
            else -> baseContainer
        },
        animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
        label = "speech_feedback_bg",
    )
    val borderColor by animateColorAsState(
        targetValue = when {
            isHoldCancel -> errorColor.copy(alpha = 0.48f)
            isHold -> primaryColor.copy(alpha = 0.36f)
            else -> MiuixTheme.colorScheme.outline.copy(alpha = 0.55f)
        },
        animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
        label = "speech_feedback_border",
    )

    AnimatedVisibility(
        visible = state.active || state.error != null,
        enter = fadeIn(tween(170)) + expandVertically(
            animationSpec = spring(dampingRatio = 0.85f, stiffness = 460f),
            expandFrom = Alignment.Bottom,
        ),
        exit = fadeOut(tween(110)) + shrinkVertically(
            animationSpec = spring(dampingRatio = 0.90f, stiffness = 520f),
            shrinkTowards = Alignment.Bottom,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp)
                .heightIn(min = 38.dp)
                .squircleSurface(
                    color = containerColor,
                    cornerRadius = 19.dp,
                )
                .squircleBorder(
                    width = 0.5.dp,
                    color = borderColor,
                    cornerRadius = 19.dp,
                )
                .padding(
                    start = 14.dp,
                    end = if (isHold && state.phase != EtaSpeechPhase.RECOGNIZING) 14.dp else 6.dp,
                    top = 5.dp,
                    bottom = 5.dp,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (isHoldCancel || state.error != null) {
                Box(
                    modifier = Modifier
                        .size(6.5.dp)
                        .clip(CircleShape)
                        .background(errorColor),
                )
            } else if (state.active) {
                SpeechLevelBars(
                    level = { controller.state.value.level },
                    color = primaryColor,
                )
            }

            val displayMode = when {
                state.error != null -> 0
                isHoldCancel -> 1
                isHold && state.preview.isNotBlank() -> 2
                isHold && state.phase == EtaSpeechPhase.RECOGNIZING -> 3
                isHold && state.phase == EtaSpeechPhase.LISTENING -> 4
                else -> 5
            }
            AnimatedContent(
                targetState = displayMode,
                transitionSpec = {
                    (fadeIn(tween(130)) + slideInVertically(tween(150)) { it / 4 })
                        .togetherWith(
                            fadeOut(tween(90)) + slideOutVertically(tween(100)) { -it / 4 },
                        )
                },
                modifier = Modifier.weight(1f),
                label = "speech_feedback_text",
            ) { mode ->
                val text = when (mode) {
                    0 -> state.error.orEmpty()
                    1 -> stringResource(R.string.speech_hold_release_to_cancel)
                    2 -> state.preview
                    3 -> stringResource(R.string.speech_hold_recognizing_send)
                    4 -> stringResource(R.string.voice_release_to_send)
                    else -> state.preview.ifBlank { state.progress }
                }
                val emphasizeHoldText = mode == 1 || mode == 4
                val textColor by animateColorAsState(
                    targetValue = when (mode) {
                        1 -> errorColor
                        4 -> primaryColor
                        0 -> MiuixTheme.colorScheme.onSurface
                        else -> MiuixTheme.colorScheme.onSurfaceVariantSummary
                    },
                    animationSpec = tween(durationMillis = 150),
                    label = "speech_feedback_text_color",
                )
                Text(
                    text = text,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    fontWeight = if (emphasizeHoldText) FontWeight.Medium else FontWeight.Normal,
                    color = textColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (state.active && !state.holdToTalk) {
                SpeechFeedbackAction(text = stringResource(R.string.action_cancel), onClick = controller::cancel)
                SpeechFeedbackAction(
                    text = stringResource(R.string.action_done),
                    primary = true,
                    onClick = controller::finish,
                )
            } else if (isHold && state.phase == EtaSpeechPhase.RECOGNIZING) {
                IconButton(onClick = controller::cancel, minWidth = 28.dp, minHeight = 28.dp) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.action_cancel),
                        modifier = Modifier.size(15.dp),
                        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            } else if (!state.active) {
                if (state.configureAvailable) {
                    SpeechFeedbackAction(
                        text = stringResource(R.string.speech_open_settings),
                        primary = true,
                        onClick = { openSpeechSettings(context) },
                    )
                }
                IconButton(onClick = controller::cancel, minWidth = 28.dp, minHeight = 28.dp) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.action_cancel),
                        modifier = Modifier.size(15.dp),
                        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        }
    }
}

/**
 * 录音电平波形。录音端约 5 次/秒上报 RMS，这里逐帧做包络插值：上升快、回落慢，
 * 说话时跳动跟手，停顿时平滑收回。电平与包络只在绘制阶段读取，帧循环不触发重组。
 */
@Composable
private fun SpeechLevelBars(
    level: () -> Float,
    color: Color = MiuixTheme.colorScheme.primary,
) {
    val envelope = remember { FloatArray(LEVEL_BAR_WEIGHTS.size) }
    var frame by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) withFrameNanos { frame = it }
    }
    Canvas(modifier = Modifier.size(width = 22.dp, height = 16.dp)) {
        frame
        // RMS 与听感近似对数关系，开方后小声说话也能看到明显起伏。
        val input = sqrt(level().coerceIn(0f, 1f))
        val barWidth = 2.5.dp.toPx()
        val gap = (size.width - barWidth * envelope.size) / (envelope.size - 1)
        val minHeight = barWidth
        envelope.indices.forEach { index ->
            val jitter = 1f + (Random.nextFloat() * 2f - 1f) * LEVEL_BAR_JITTER
            val target = (input * LEVEL_BAR_WEIGHTS[index] * jitter).coerceIn(0f, 1f)
            val current = envelope[index]
            envelope[index] = current + (target - current) * if (target > current) LEVEL_ATTACK else LEVEL_RELEASE
            val height = minHeight + (size.height - minHeight) * envelope[index]
            drawRoundRect(
                color = color,
                topLeft = Offset(index * (barWidth + gap), (size.height - height) / 2f),
                size = Size(barWidth, height),
                cornerRadius = CornerRadius(barWidth / 2f),
            )
        }
    }
}

private val LEVEL_BAR_WEIGHTS = floatArrayOf(0.5f, 0.8f, 1f, 0.75f, 0.55f)
private const val LEVEL_ATTACK = 0.4f
private const val LEVEL_RELEASE = 0.15f
private const val LEVEL_BAR_JITTER = 0.04f

@Composable
private fun SpeechFeedbackAction(text: String, primary: Boolean = false, onClick: () -> Unit) {
    val tint = if (primary) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary
    val background = if (primary) {
        MiuixTheme.colorScheme.primary.copy(alpha = 0.12f)
    } else {
        MiuixTheme.colorScheme.surfaceContainerHigh
    }
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(background)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = tint,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}

internal val LocalSpeechPlayback = staticCompositionLocalOf<SpeechPlaybackController?> { null }
internal val LocalSpeechPlaybackEnabled = compositionLocalOf { false }

/**
 * 聊天页自动朗读：一轮回答在本页流式生成并正常结束后，朗读这一轮的最终回答。
 * 只认在本页看到过流式生成的回答，切换会话、打开历史或停止、失败的回合都不会补读。
 */
@Composable
internal fun SpeechAutoReadAloud(messages: List<AgentChatMessageUi>, isStreaming: Boolean) {
    val controller = LocalSpeechPlayback.current ?: return
    val settings by remember { SpeechSettingsRepository.settingsFlow() }.collectAsStateWithLifecycle(initialValue = null)
    val enabled = settings?.let { it.chatAutoSpeak && it.tts != TtsProvider.NONE } == true
    var watchedId by remember { mutableStateOf<String?>(null) }
    val currentMessages by rememberUpdatedState(messages)
    LaunchedEffect(messages) {
        messages.lastOrNull { it is AgentMessageUi && it.isStreaming }?.let { watchedId = it.id }
        // 被观察的回答不在当前列表里，说明已经切到别的会话。
        if (watchedId != null && messages.none { it.id == watchedId }) watchedId = null
    }
    LaunchedEffect(isStreaming) {
        if (isStreaming) return@LaunchedEffect
        val watched = watchedId ?: return@LaunchedEffect
        watchedId = null
        if (!enabled) return@LaunchedEffect
        val list = currentMessages
        if (list.none { it.id == watched }) return@LaunchedEffect
        val answerIndex = list.indexOfLast { it is AgentMessageUi }
        val answer = list.getOrNull(answerIndex) as? AgentMessageUi ?: return@LaunchedEffect
        val interrupted = list.drop(answerIndex + 1).any {
            it is SystemNoticeMessageUi && it.code in setOf(SystemNoticeCode.Stopped, SystemNoticeCode.RuntimeFailed)
        }
        if (!interrupted && answer.content.isNotBlank()) controller.speak(answer.id, answer.content)
    }
    // 关闭自动朗读视同“别再读了”，正在进行的朗读一并停止。
    var wasEnabled by remember { mutableStateOf(enabled) }
    LaunchedEffect(enabled) {
        if (wasEnabled && !enabled) controller.stop()
        wasEnabled = enabled
    }
}

/**
 * 朗读控制器随聊天页存在；未配置播报服务时朗读入口整体隐藏。
 * 退到后台或锁屏时继续朗读（出声期间由前台播放服务保活），离开聊天页才停止。
 */
@Composable
internal fun SpeechPlaybackHost(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val controller = remember {
        val appContext = context.applicationContext
        lateinit var created: SpeechPlaybackController
        created = SpeechPlaybackController(
            context = context,
            scope = scope,
            beforePlayback = { ReadAloudPlaybackService.start(appContext) { created.stop() } },
            afterPlayback = { ReadAloudPlaybackService.stop(appContext) },
            interruptOnScreenOff = false,
        )
        created
    }
    val settings by remember { SpeechSettingsRepository.settingsFlow() }.collectAsStateWithLifecycle(initialValue = null)
    DisposableEffect(controller) { onDispose { controller.stop() } }
    SpeechPlaybackErrors(controller)
    CompositionLocalProvider(
        LocalSpeechPlayback provides controller,
        LocalSpeechPlaybackEnabled provides (settings != null && settings?.tts != TtsProvider.NONE),
        content = content,
    )
}

@Composable
internal fun SpeechPlaybackErrors(controller: SpeechPlaybackController) {
    val context = LocalContext.current
    val state by controller.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.error) {
        state.error?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
    }
}

/** 朗读入口与相邻的消息操作共享同一套尺寸与线性图标风格，播放中转为停止。 */
@Composable
internal fun SpeechReadAloudButton(messageId: String, text: String) {
    val controller = LocalSpeechPlayback.current ?: return
    if (!LocalSpeechPlaybackEnabled.current) return
    val state by controller.state.collectAsStateWithLifecycle()
    val active = state.messageId == messageId
    AnswerActionButton(
        icon = if (active) ChatActionIcons.Stop else ChatActionIcons.Volume,
        contentDescription = stringResource(
            if (active) R.string.speech_read_stop else R.string.speech_read_aloud,
        ),
        onClick = { if (active) controller.stop() else controller.speak(messageId, text) },
        tint = if (active) MiuixTheme.colorScheme.primary else null,
    )
}
