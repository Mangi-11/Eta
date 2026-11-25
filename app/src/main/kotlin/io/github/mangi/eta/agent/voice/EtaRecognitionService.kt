package io.github.mangi.eta.agent.voice

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import io.github.mangi.eta.agent.voice.asr.SystemSpeechRecognizer

/**
 * Android 助理角色要求声明识别服务。Eta 自身只使用在线语音识别；若系统调用该兼容入口，则委托给外部 ASR。
 */
class EtaRecognitionService : RecognitionService() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var activeCallback: Callback? = null
    private var recognizer: SpeechRecognizer? = null

    override fun getMaxConcurrentSessionsCount(): Int = 1

    override fun onStartListening(recognizerIntent: Intent, callback: Callback) {
        mainHandler.post {
            releaseRecognizer()
            val delegate = SystemSpeechRecognizer.create(this)
            if (delegate == null) {
                notifyError(callback, SpeechRecognizer.ERROR_CLIENT)
                return@post
            }
            activeCallback = callback
            recognizer = delegate
            delegate.setRecognitionListener(ForwardingRecognitionListener(callback))
            runCatching { delegate.startListening(recognizerIntent) }
                .onFailure {
                    notifyError(callback, SpeechRecognizer.ERROR_CLIENT)
                    releaseRecognizer(callback)
                }
        }
    }

    override fun onStopListening(callback: Callback) {
        mainHandler.post {
            if (activeCallback === callback) {
                runCatching { recognizer?.stopListening() }
            }
        }
    }

    override fun onCancel(callback: Callback) {
        mainHandler.post {
            if (activeCallback === callback) {
                runCatching { recognizer?.cancel() }
                releaseRecognizer(callback)
            }
        }
    }

    override fun onDestroy() {
        releaseRecognizer()
        super.onDestroy()
    }

    private fun releaseRecognizer(expected: Callback? = null) {
        if (expected != null && activeCallback !== expected) return
        recognizer?.setRecognitionListener(null)
        recognizer?.destroy()
        recognizer = null
        activeCallback = null
    }

    private fun notifyError(callback: Callback, error: Int) {
        runCatching { callback.error(error) }
    }

    private inner class ForwardingRecognitionListener(
        private val callback: Callback,
    ) : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle) {
            runCatching { callback.readyForSpeech(params) }
        }

        override fun onBeginningOfSpeech() {
            runCatching { callback.beginningOfSpeech() }
        }

        override fun onRmsChanged(rmsdB: Float) {
            runCatching { callback.rmsChanged(rmsdB) }
        }

        override fun onBufferReceived(buffer: ByteArray) {
            runCatching { callback.bufferReceived(buffer) }
        }

        override fun onEndOfSpeech() {
            runCatching { callback.endOfSpeech() }
        }

        override fun onError(error: Int) {
            notifyError(callback, error)
            releaseRecognizer(callback)
        }

        override fun onResults(results: Bundle) {
            runCatching { callback.results(results) }
            releaseRecognizer(callback)
        }

        override fun onPartialResults(partialResults: Bundle) {
            runCatching { callback.partialResults(partialResults) }
        }

        override fun onEvent(eventType: Int, params: Bundle) = Unit

        override fun onSegmentResults(segmentResults: Bundle) {
            runCatching { callback.segmentResults(segmentResults) }
        }

        override fun onEndOfSegmentedSession() {
            runCatching { callback.endOfSegmentedSession() }
            releaseRecognizer(callback)
        }

        override fun onLanguageDetection(results: Bundle) {
            runCatching { callback.languageDetection(results) }
        }
    }
}
