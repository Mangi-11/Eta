package io.github.mangi.eta.agent.voice

import android.content.Context
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.data.model.AsrProvider
import io.github.mangi.eta.data.model.SpeechCredentials
import io.github.mangi.eta.data.model.SpeechSettings
import io.github.mangi.eta.data.repository.SpeechSettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

internal data class SpeechInputState(
    val phase: EtaSpeechPhase = EtaSpeechPhase.IDLE,
    val preview: String = "",
    val level: Float = 0f,
    val progress: String = "",
    val error: String? = null,
    val configureAvailable: Boolean = false,
) {
    val active: Boolean get() = phase != EtaSpeechPhase.IDLE
}

internal class SpeechInputController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val automaticEndpoint: Boolean = false,
    private val beforeCapture: () -> Unit = {},
    private val afterCapture: () -> Unit = {},
    private val refiner: SpeechTranscriptRefiner = SpeechTranscriptRefiner(),
    private val createRecorder: () -> SpeechAudioCapture = ::SpeechRecorder,
    private val recognizeStream: suspend (
        SpeechSettings,
        SpeechCredentials,
        ReceiveChannel<ByteArray>,
        () -> Unit,
        (String) -> Unit,
        () -> Unit,
        Int,
    ) -> String = ::recognizeSpeechStream,
    private val recognizeFile: suspend (
        Context,
        SpeechSettings,
        SpeechCredentials,
        ByteArray,
        (String) -> Unit,
    ) -> String = ::recognizeSpeechFile,
    private val onResult: (String) -> Unit,
) {
    private val mutableState = MutableStateFlow(SpeechInputState())
    val state = mutableState.asStateFlow()
    private var generation = 0L
    private var finishRequested = false
    private var job: Job? = null
    private var recorder: SpeechAudioCapture? = null
    private var lease: SpeechAudioLease? = null
    private var capturing = false

    /**
     * [holdToTalk] 表示用户按住说话：松手才结束，期间的停顿不会自动发送。
     * 停顿自动发送只对构造时声明 [automaticEndpoint] 的入口生效，聊天听写始终等待用户确认。
     */
    fun start(settings: SpeechSettings? = null, credentials: SpeechCredentials? = null, holdToTalk: Boolean = false) {
        cancel()
        val session = generation
        mutableState.value = SpeechInputState(phase = EtaSpeechPhase.STARTING, progress = "正在连接")
        job = scope.launch(Dispatchers.Main.immediate) {
            try {
                val rawConfig = settings ?: SpeechSettingsRepository.settings()
                if (!rawConfig.recognitionEnabled) return@launch
                lease = SpeechAudioLease(context) { cancel() }.also { it.acquire(playback = false) }
                val rawSecrets = credentials ?: SpeechSettingsRepository.credentials(context)
                val (config, secrets) = SpeechSettingsRepository.resolveDeepSeekFallback(rawConfig, rawSecrets)
                validateSpeechSettings(config, secrets, synthesis = false)
                val endpointSilenceMs = if (automaticEndpoint && !holdToTalk) config.autoSendSilenceMs else 0
                val audio = createRecorder()
                recorder = audio
                fun update(transform: (SpeechInputState) -> SpeechInputState) {
                    scope.launch(Dispatchers.Main.immediate) {
                        if (generation == session) mutableState.value = transform(mutableState.value)
                    }
                }
                val text = coroutineScope {
                    var recording: Job? = null
                    val begin = {
                        if (recording == null) {
                            beforeCapture()
                            capturing = true
                            recording = launch {
                                try {
                                    audio.record(
                                        onReady = { update { it.copy(phase = EtaSpeechPhase.LISTENING, progress = "正在聆听，点击完成结束") } },
                                        onLevel = { level -> update { it.copy(level = level) } },
                                    )
                                } finally {
                                    if (generation == session) releaseCapture()
                                }
                                update { it.copy(phase = EtaSpeechPhase.RECOGNIZING, level = 0f, progress = "正在识别") }
                            }
                        }
                        Unit
                    }
                    try {
                        if (config.asr == AsrProvider.QWEN_REALTIME || config.asr == AsrProvider.DOUBAO) {
                            recognizeStream(
                                config,
                                secrets,
                                audio.frames,
                                begin,
                                { preview -> update { it.copy(preview = preview) } },
                                { audio.finish() },
                                endpointSilenceMs,
                            )
                        } else {
                            begin()
                            val wav = withTimeout(65_000) { collectSpeechAudio(audio.frames) }
                            // 关闭通道可能同步唤醒收集器，旧会话不能在取消传播完成前启动请求。
                            coroutineContext.ensureActive()
                            if (generation != session) throw CancellationException("Speech session replaced")
                            recognizeFile(context, config, secrets, wav) { progress ->
                                update { it.copy(phase = EtaSpeechPhase.RECOGNIZING, progress = progress) }
                            }
                        }
                    } finally {
                        audio.cancel()
                        recording?.cancel()
                    }
                }
                if (text.isBlank()) throw SpeechFailure(SpeechErrorCode.NO_SPEECH, "没有识别到语音，请重试")
                val final = refined(session, config.refineTranscript, text)
                if (generation == session) {
                    cancel()
                    onResult(final)
                }
            } catch (error: Exception) {
                if (error is CancellationException && error !is kotlinx.coroutines.TimeoutCancellationException) throw error
                if (generation == session) {
                    val failure = error.speechFailure()
                    AndroidAgentLogger.warn("Eta speech recognition failed: code=${failure.code} type=${error.javaClass.simpleName}")
                    cancel()
                    mutableState.value = SpeechInputState(
                        error = failure.userMessage,
                        configureAvailable = failure.code == SpeechErrorCode.CONFIGURATION ||
                            failure.code == SpeechErrorCode.CREDENTIALS ||
                            failure.code == SpeechErrorCode.AUTHENTICATION,
                    )
                }
            } finally {
                if (generation == session) cancel()
            }
        }
    }

    /** 纠错失败不影响交付；只有纠错仍属于当前会话时才展示进度。 */
    private suspend fun refined(session: Long, refineTranscript: Boolean, text: String): String {
        if (!refineTranscript || generation != session) return text
        mutableState.value = mutableState.value.copy(
            phase = EtaSpeechPhase.RECOGNIZING, preview = text, progress = "正在校对", level = 0f,
        )
        return try {
            refiner.refine(text)
        } catch (error: Exception) {
            if (error is CancellationException && error !is kotlinx.coroutines.TimeoutCancellationException) throw error
            val failure = error.speechFailure()
            AndroidAgentLogger.warn("Eta speech refine skipped: code=${failure.code} type=${error.javaClass.simpleName}")
            text
        }
    }

    fun finish() {
        if (!mutableState.value.active || finishRequested) return
        finishRequested = true
        if (mutableState.value.phase == EtaSpeechPhase.STARTING) { cancel(); return }
        recorder?.finish()
        mutableState.value = mutableState.value.copy(phase = EtaSpeechPhase.RECOGNIZING, progress = "正在识别", level = 0f)
    }

    fun cancel() {
        generation++
        finishRequested = false
        // 先取消识别协程，避免关闭录音通道被当作正常录音完成而提交请求。
        job?.cancel(); job = null
        recorder?.cancel(); recorder = null
        releaseCapture()
        lease?.close(); lease = null
        mutableState.value = SpeechInputState()
    }

    private fun releaseCapture() {
        if (capturing) afterCapture()
        capturing = false
    }
}
