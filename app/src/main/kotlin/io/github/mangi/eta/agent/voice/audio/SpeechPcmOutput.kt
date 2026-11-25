package io.github.mangi.eta.agent.voice.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import io.github.mangi.eta.R
import io.github.mangi.eta.core.AppStrings
import kotlinx.coroutines.CancellationException
import io.github.mangi.eta.agent.voice.tts.SpeechAudioOutput

internal class SpeechPcmOutput : SpeechAudioOutput {
    private var track: AudioTrack? = AudioTrack.Builder()
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        .setAudioFormat(AudioFormat.Builder().setSampleRate(SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
        .setBufferSizeInBytes(maxOf(SAMPLE_RATE, AudioTrack.getMinBufferSize(SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)))
        .setTransferMode(AudioTrack.MODE_STREAM).build()
    private var frames = 0L
    private var started = false
    private var finished = false

    override fun write(bytes: ByteArray) {
        var offset = 0
        while (offset < bytes.size) {
            val written = synchronized(this) {
                val audio = track ?: throw CancellationException("Speech playback stopped")
                check(!finished) { "Speech audio input already finished" }
                val remaining = bytes.size - offset
                val count = audio.write(bytes, offset, remaining, AudioTrack.WRITE_NON_BLOCKING)
                if (count < 0) {
                    throw SpeechFailure(
                        SpeechErrorCode.AUDIO,
                        AppStrings.get(R.string.voice_i18n_audio_playback_failed, "音频播放失败"),
                    )
                }
                frames += count / BYTES_PER_FRAME
                // 空音轨提前启动会在等待云端首包时超时停用；写满后再启动，短写也表示已达到可写上限。
                if (!started && frames > 0 && (frames >= audio.bufferSizeInFrames || count < remaining)) {
                    audio.play()
                    started = true
                }
                count
            }
            offset += written
            if (written == 0) Thread.sleep(5)
        }
    }

    @Synchronized override fun finish() {
        val audio = track ?: throw CancellationException("Speech playback stopped")
        if (finished) return
        // 短音频可能永远填不满启动阈值，结束输入时按实际帧数启动，不补静音或截掉正文。
        if (!started && frames > 0) {
            audio.setStartThresholdInFrames(frames.toInt())
            audio.play()
            started = true
        }
        finished = true
    }

    @Synchronized override fun drained(): Boolean = track?.let {
        finished && (it.playbackHeadPosition.toLong() and 0xffffffffL) >= frames
    } ?: true

    @Synchronized override fun close() {
        track?.let { it.pause(); it.flush(); it.release() }
        track = null
    }

    private companion object {
        const val SAMPLE_RATE = 24_000
        const val BYTES_PER_FRAME = 2
    }
}
