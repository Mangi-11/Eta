package io.github.mangi.eta.agent.voice.audio

/**
 * 长文本按段独立合成后无缝拼接播放，相邻两段的末尾与开头采样不一定在零点附近，
 * 波形阶跃会听成一声“滋”。每段首尾各做几毫秒线性淡入淡出：段尾需要等段结束才能确定，
 * 因此始终保留最后 [fadeSamples] 个采样，直到 [end] 时淡出写出。输入为 16 位小端单声道 PCM，
 * 每次写入都是整采样。
 */
internal class SpeechSegmentFade(private val fadeSamples: Int = FADE_SAMPLES) {
    private var tail = ByteArray(0)
    private var emittedSamples = 0L

    /** 返回可以立即播放的部分，可能为空。 */
    fun write(bytes: ByteArray): ByteArray {
        val joined = tail + bytes
        val keep = minOf(joined.size, fadeSamples * BYTES_PER_SAMPLE)
        tail = joined.copyOfRange(joined.size - keep, joined.size)
        return joined.copyOfRange(0, joined.size - keep).also(::fadeIn)
    }

    /** 结束当前段并返回淡出后的段尾，之后可以开始下一段。 */
    fun end(): ByteArray {
        val last = tail
        tail = ByteArray(0)
        fadeIn(last)
        val samples = last.size / BYTES_PER_SAMPLE
        val ramp = minOf(samples, fadeSamples)
        for (index in samples - ramp until samples) {
            scale(last, index, (samples - 1 - index).toFloat() / ramp)
        }
        emittedSamples = 0
        return last
    }

    private fun fadeIn(bytes: ByteArray) {
        val samples = bytes.size / BYTES_PER_SAMPLE
        var index = 0
        while (index < samples && emittedSamples + index < fadeSamples) {
            scale(bytes, index, (emittedSamples + index).toFloat() / fadeSamples)
            index++
        }
        emittedSamples += samples
    }

    private fun scale(bytes: ByteArray, sample: Int, gain: Float) {
        val offset = sample * BYTES_PER_SAMPLE
        val value = (bytes[offset].toInt() and 0xff) or (bytes[offset + 1].toInt() shl 8)
        val scaled = (value.toShort() * gain).toInt()
        bytes[offset] = scaled.toByte()
        bytes[offset + 1] = (scaled shr 8).toByte()
    }

    private companion object {
        const val BYTES_PER_SAMPLE = 2
        /** 24 kHz 下 5 ms，短到听不出音量变化，足以消除阶跃。 */
        const val FADE_SAMPLES = 120
    }
}
