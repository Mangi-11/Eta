package io.github.mangi.eta.ui.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.voice.EtaSpeechPhase
import io.github.mangi.eta.agent.voice.ReadAloudPlaybackService
import io.github.mangi.eta.agent.voice.SpeechInputController
import io.github.mangi.eta.agent.voice.SpeechPlaybackController
import io.github.mangi.eta.data.model.TtsProvider
import io.github.mangi.eta.data.repository.SpeechSettingsRepository
import io.github.mangi.eta.ui.MainActivity
import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.SystemNoticeCode
import io.github.mangi.eta.ui.model.SystemNoticeMessageUi
import io.github.mangi.eta.ui.components.AnswerActionSize
import io.github.mangi.eta.ui.components.ChatInputActionIconSize
import io.github.mangi.eta.ui.components.ChatInputActionSize
import io.github.mangi.eta.ui.components.EtaTextButton
import io.github.mangi.eta.ui.components.StatusError
import kotlin.math.sqrt
import kotlin.random.Random
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

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
internal fun rememberSpeechInput(onResult: (String) -> Unit): SpeechInputController {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val result by rememberUpdatedState(onResult)
    val controller = remember(context, scope) { SpeechInputController(context, scope, onResult = { result(it) }) }
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

/** 输入栏听写入口，沿用输入栏统一的操作尺寸；录音中点击完成识别并插入文字。 */
@Composable
internal fun SpeechDictationButton(
    controller: SpeechInputController,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val state by controller.state.collectAsState()
    val request = rememberSpeechPermission { if (enabled) controller.start() }
    IconButton(
        onClick = { if (state.active) controller.finish() else request() },
        enabled = enabled && state.phase != EtaSpeechPhase.RECOGNIZING,
        minWidth = ChatInputActionSize,
        minHeight = ChatInputActionSize,
        modifier = modifier,
    ) {
        Icon(
            imageVector = if (state.active) Icons.Rounded.Check else Icons.Rounded.Mic,
            contentDescription = stringResource(
                if (state.active) R.string.speech_dictation_finish else R.string.speech_dictation,
            ),
            modifier = Modifier.size(ChatInputActionIconSize),
            tint = if (state.active) {
                MiuixTheme.colorScheme.primary
            } else {
                MiuixTheme.colorScheme.onSurface
            },
        )
    }
}

/** 输入栏上方的听写状态条：录音中显示电平与完成/取消，错误给出下载、设置或关闭出口。 */
@Composable
internal fun SpeechInputFeedback(controller: SpeechInputController) {
    val state by controller.state.collectAsState()
    val context = LocalContext.current
    AnimatedVisibility(
        visible = state.active || state.error != null,
        enter = fadeIn(tween(160)) + expandVertically(tween(160)),
        exit = fadeOut(tween(100)) + shrinkVertically(tween(140)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state.active) {
                SpeechLevelBars(level = { controller.state.value.level })
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(
                text = state.error ?: state.preview.ifBlank { state.progress },
                style = MiuixTheme.textStyles.body2,
                color = if (state.error != null) {
                    StatusError
                } else {
                    MiuixTheme.colorScheme.onSurfaceVariantSummary
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (state.active) {
                SpeechFeedbackAction(text = stringResource(R.string.action_cancel), onClick = controller::cancel)
                SpeechFeedbackAction(
                    text = stringResource(R.string.action_done),
                    primary = true,
                    onClick = controller::finish,
                )
            } else {
                if (state.downloadAvailable) {
                    SpeechFeedbackAction(
                        text = stringResource(R.string.voice_download_model),
                        primary = true,
                        onClick = controller::downloadModel,
                    )
                }
                if (state.configureAvailable) {
                    SpeechFeedbackAction(
                        text = stringResource(R.string.speech_open_settings),
                        primary = true,
                        onClick = { openSpeechSettings(context) },
                    )
                }
                IconButton(onClick = controller::cancel, minWidth = 36.dp, minHeight = 36.dp) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.action_cancel),
                        modifier = Modifier.size(16.dp),
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
private fun SpeechLevelBars(level: () -> Float) {
    val color = MiuixTheme.colorScheme.primary
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
    EtaTextButton(
        text = text,
        onClick = onClick,
        minWidth = 0.dp,
        minHeight = 34.dp,
        cornerRadius = 17.dp,
        colors = if (primary) ButtonDefaults.textButtonColorsPrimary() else ButtonDefaults.textButtonColors(),
        insideMargin = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
    )
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
    val settings by remember { SpeechSettingsRepository.settingsFlow() }.collectAsState(initial = null)
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
    val settings by remember { SpeechSettingsRepository.settingsFlow() }.collectAsState(initial = null)
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
    val state by controller.state.collectAsState()
    LaunchedEffect(state.error) {
        state.error?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
    }
}

/** 朗读入口与相邻的消息操作共享同一套尺寸与弱化配色，播放中转为停止。 */
@Composable
internal fun SpeechReadAloudButton(messageId: String, text: String) {
    val controller = LocalSpeechPlayback.current ?: return
    if (!LocalSpeechPlaybackEnabled.current) return
    val state by controller.state.collectAsState()
    val active = state.messageId == messageId
    IconButton(
        onClick = { if (active) controller.stop() else controller.speak(messageId, text) },
        minWidth = AnswerActionSize,
        minHeight = AnswerActionSize,
    ) {
        Icon(
            imageVector = if (active) Icons.Rounded.Stop else Icons.AutoMirrored.Rounded.VolumeUp,
            contentDescription = stringResource(
                if (active) R.string.speech_read_stop else R.string.speech_read_aloud,
            ),
            modifier = Modifier.size(if (active) 14.dp else 16.dp),
            tint = if (active) {
                MiuixTheme.colorScheme.primary
            } else {
                MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.75f)
            },
        )
    }
}
