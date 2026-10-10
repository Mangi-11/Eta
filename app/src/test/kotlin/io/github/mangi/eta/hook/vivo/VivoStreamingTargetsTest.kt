package io.github.mangi.eta.hook.vivo

import com.vivo.ai.chat.GptParams
import com.vivo.ai.chat.MessageExtents
import com.vivo.ai.chat.MessageParams
import org.junit.Assert.*
import org.junit.Test
import repackage_name.w77

class VivoStreamingTargetsTest {
    private val targets = VivoStreamingTargets.resolve(javaClass.classLoader!!)!!
    private fun message(delta: String, full: String, last: Boolean = false, token: String = "owned") = MessageParams(
        GptParams("trace", "request", MessageExtents(delta).apply {
            extras[VivoReplyProtocol.STREAM_TOKEN] = token
            extras[VivoReplyProtocol.STREAM_TEXT] = full
        }, last),
    )

    @Test
    fun nativeAccumulatorShowsReplacementAndFinalTailWithoutDuplicatingTheAnswer() {
        val processor = w77()
        for (message in listOf(message("旧片段", "旧片段"), message("修订", "修订"),
            message("回答", "修订回答"), message("", "修订回答", last = true))) {
            targets.prepare(processor, message) { trace, request, token -> trace == "trace" && request == "request" && token == "owned" }
            processor.onResult(null, message)
            assertTrue((message.gptParams.data as MessageExtents).extras.isEmpty())
        }
        assertEquals("修订回答", processor.a!!.text)
    }

    @Test
    fun unownedMarkersCannotChangeTheAccumulator() {
        val processor = w77().apply { a = MessageExtents("原生回答"); b = message("", "原生回答") }
        targets.prepare(processor, message("注入", "替换", token = "foreign")) { _, _, token -> token == "owned" }
        assertEquals("原生回答", processor.a!!.text)
    }

    @Test
    fun terminalPacketRecoversIfNativeUiResetItsBuffer() {
        val processor = w77()
        val result = message("", "最终回答", last = true)
        targets.prepare(processor, result) { _, _, _ -> true }
        processor.onResult(null, result)
        assertEquals("最终回答", processor.a!!.text)
    }

    @Test
    fun rawGsonMapDataReconcilesTheAccumulatedTextBeforeNativeConversion() {
        val processor = w77().apply { a = MessageExtents("错误前缀"); b = message("", "错误前缀") }
        val data = mutableMapOf<String, Any>("text" to "新回答", "extras" to mutableMapOf(
            VivoReplyProtocol.STREAM_TOKEN to "owned", VivoReplyProtocol.STREAM_TEXT to "新回答"))
        targets.prepare(processor, MessageParams(GptParams("trace", "request", data, false))) { _, _, _ -> true }
        assertEquals("", processor.a!!.text)
        assertTrue((data["extras"] as Map<*, *>).isEmpty())
        processor.a = null
        val finalData = mutableMapOf<String, Any>("text" to "", "extras" to mutableMapOf(
            VivoReplyProtocol.STREAM_TOKEN to "owned", VivoReplyProtocol.STREAM_TEXT to "最终回答"))
        targets.prepare(processor, MessageParams(GptParams("trace", "request", finalData, true))) { _, _, _ -> true }
        assertEquals("最终回答", finalData["text"])
    }
}
