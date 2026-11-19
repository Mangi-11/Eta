package io.github.mangi.eta.agent.voice

import io.github.mangi.eta.R
import io.github.mangi.eta.core.AppStrings
import okio.Buffer

/** 每次合成独立识别 WAV 容器或裸 PCM，文件头与采样都可以跨网络片段。 */
internal class SpeechPcmDecoder(private val onAudio: (ByteArray) -> Unit) {
    private enum class Phase { DETECT, RIFF_HEADER, WAV_HEADER, PCM }

    private val buffer = Buffer()
    private var phase = Phase.DETECT
    private var headerBytes = 0L
    private var formatValidated = false
    private var pcmBytes = 0L

    fun write(bytes: ByteArray) {
        buffer.write(bytes)
        decode()
    }

    private fun decode() {
        if (phase == Phase.DETECT) {
            if (buffer.size < 4) return
            if (buffer.peek().readUtf8(4) != "RIFF") {
                phase = Phase.PCM
            } else {
                phase = Phase.RIFF_HEADER
            }
        }
        if (phase == Phase.RIFF_HEADER) {
            if (buffer.size < 12) return
            buffer.skip(8)
            if (buffer.readUtf8(4) != "WAVE") invalid()
            headerBytes = 12
            phase = Phase.WAV_HEADER
        }
        while (phase == Phase.WAV_HEADER) {
            if (buffer.size < 8) return
            val chunk = buffer.peek()
            val id = chunk.readUtf8(4)
            val size = chunk.readIntLe().toLong() and 0xffffffffL
            if (id == "data") {
                if (!formatValidated || headerBytes + 8 > MAX_HEADER_BYTES) invalid()
                buffer.skip(8)
                // 云端流式 WAV 的长度字段可能是占位值；音频结束以服务的成功终止事件为准。
                phase = Phase.PCM
                break
            }
            val paddedSize = size + (size and 1L)
            if (headerBytes + 8 + paddedSize > MAX_HEADER_BYTES) invalid()
            if (buffer.size < 8 + paddedSize) return
            buffer.skip(8)
            if (id == "fmt ") {
                if (formatValidated || size < 16) invalid()
                val encoding = buffer.readShortLe().toInt() and 0xffff
                val channels = buffer.readShortLe().toInt() and 0xffff
                val sampleRate = buffer.readIntLe()
                val byteRate = buffer.readIntLe()
                val frameSize = buffer.readShortLe().toInt() and 0xffff
                val bits = buffer.readShortLe().toInt() and 0xffff
                if (encoding != 1 || channels != 1 || sampleRate != 24_000 ||
                    byteRate != 48_000 || frameSize != 2 || bits != 16) invalid()
                buffer.skip(paddedSize - 16)
                formatValidated = true
            } else {
                buffer.skip(paddedSize)
            }
            headerBytes += 8 + paddedSize
        }
        if (phase == Phase.PCM) emitFrames()
    }

    private fun emitFrames() {
        val count = buffer.size - buffer.size % 2
        if (count == 0L) return
        pcmBytes += count
        onAudio(buffer.readByteArray(count))
    }

    fun end() {
        decode()
        if (phase == Phase.DETECT) {
            phase = Phase.PCM
            emitFrames()
        }
        if (phase != Phase.PCM || buffer.size != 0L || pcmBytes == 0L) invalid()
    }

    private fun invalid(): Nothing = throw SpeechFailure(
        SpeechErrorCode.PROTOCOL,
        AppStrings.get(R.string.voice_i18n_audio_format_or_length_invalid, "语音音频格式或长度无效"),
    )

    private companion object {
        const val MAX_HEADER_BYTES = 65_536L
    }
}
