package io.github.mangi.eta.agent.voice

import io.github.mangi.eta.data.model.MimoTtsConfig
import java.io.ByteArrayOutputStream
import okhttp3.Request
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MimoSpeechSynthesisTest {
    @Test fun requestKeepsStyleSeparateAndPutsExactTextInAssistantMessage() {
        val text = "今天下午三点出发。"
        val request = mimoTtsRequest(
            MimoTtsConfig(baseUrl = "https://example.invalid/proxy/v1/", voice = " 茉莉 ", style = " 自然温和 "),
            " test-key ", text,
        )
        assertEquals("/proxy/v1/chat/completions", request.url.encodedPath)
        assertEquals("Bearer test-key", request.header("Authorization"))
        assertEquals("text/event-stream", request.header("Accept"))
        assertNull(request.url.query)
        val body = body(request)
        assertEquals(setOf("model", "messages", "audio", "stream"), body.keys().asSequence().toSet())
        assertEquals("mimo-v2.5-tts", body.getString("model"))
        assertTrue(body.getBoolean("stream"))
        val messages = body.getJSONArray("messages")
        assertEquals(2, messages.length())
        assertEquals("user", messages.getJSONObject(0).getString("role"))
        assertEquals("自然温和", messages.getJSONObject(0).getString("content"))
        assertEquals("assistant", messages.getJSONObject(1).getString("role"))
        assertEquals(text, messages.getJSONObject(1).getString("content"))
        assertEquals("pcm16", body.getJSONObject("audio").getString("format"))
        assertEquals("茉莉", body.getJSONObject("audio").getString("voice"))

        val plain = body(mimoTtsRequest(MimoTtsConfig(style = " \n"), "test-key", text))
        assertEquals(1, plain.getJSONArray("messages").length())
        assertEquals("assistant", plain.getJSONArray("messages").getJSONObject(0).getString("role"))
        assertEquals("mimo_default", plain.getJSONObject("audio").getString("voice"))
    }

    @Test fun fragmentedSsePreservesAudioOrderAndDoesNotDeduplicateSharedAudioIds() {
        val out = ByteArrayOutputStream()
        val decoder = MimoTtsDecoder(out::write)
        val wire = Buffer().writeUtf8(
            ": heartbeat\r\n\r\n" +
                "data: {\"choices\":[{\"delta\":{\"role\":\"assistant\"},\"finish_reason\":null}]}\r\n\r\n" +
                "data: ${chunk(null)}\n\n" +
                "data: ${chunk("AQ==")}\n\n" +
                "data: {\"choices\":[],\"usage\":{}}\n\n" +
                "data: ${chunk("AgME", "stop")}\n\n" +
                "data: ${chunk("AQIDBA==", "stop")}\n\n",
        )
        val fragmented = object : ForwardingSource(wire) {
            override fun read(sink: Buffer, byteCount: Long) = super.read(sink, minOf(byteCount, 1))
        }.buffer()
        readSpeechSse(fragmented) { _, value -> decoder.event(value) }
        decoder.end()
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), out.toByteArray())
    }

    @Test fun terminalMetadataMayOmitDeltaButCannotReplayNonstreamingAudio() {
        for (delta in listOf(null, JSONObject.NULL, JSONObject())) {
            val out = ByteArrayOutputStream()
            val decoder = MimoTtsDecoder(out::write)
            decoder.event(chunk("AQIDBA=="))
            val terminal = JSONObject().put("finish_reason", "stop")
                .put("message", JSONObject().put("audio", JSONObject().put("data", "AQIDBA==")))
            if (delta != null) terminal.put("delta", delta)
            assertFalse(decoder.event(JSONObject().put("choices", JSONArray().put(terminal)).toString()))
            decoder.end()
            assertArrayEquals(byteArrayOf(1, 2, 3, 4), out.toByteArray())
        }
    }

    @Test fun doneOrEofCannotReplaceSuccessfulCompletionAndAudioMustBeNonempty() {
        for (terminal in listOf("", "data: [DONE]\n\n")) {
            val decoder = MimoTtsDecoder {}
            readSpeechSse(Buffer().writeUtf8("data: ${chunk("AQIDBA==")}\n\n$terminal")) { _, value -> decoder.event(value) }
            assertThrows(SpeechFailure::class.java) { decoder.end() }
        }
        val empty = MimoTtsDecoder {}
        empty.event(chunk(null, "stop"))
        assertThrows(SpeechFailure::class.java) { empty.end() }
        val partialSample = MimoTtsDecoder {}
        partialSample.event(chunk("AQ==", "stop"))
        assertThrows(SpeechFailure::class.java) { partialSample.end() }
    }

    @Test fun failedOrUnknownTerminalStatesNeverSucceedOrEmitTheirAudio() {
        for ((reason, code) in listOf(
            "length" to SpeechErrorCode.SERVER,
            "content_filter" to SpeechErrorCode.SERVER,
            "tool_calls" to SpeechErrorCode.PROTOCOL,
            "" to SpeechErrorCode.PROTOCOL,
        )) {
            val decoder = MimoTtsDecoder { fail("A failed terminal chunk must not emit audio") }
            assertEquals(code, assertThrows(SpeechFailure::class.java) {
                decoder.event(chunk("AQIDBA==", reason))
            }.code)
        }
    }

    @Test fun malformedPayloadsAndServerErrorsDoNotLeakPrivateResponseContent() {
        for (payload in listOf(
            "{}",
            """{"choices":[{"delta":{},"finish_reason":42}]}""",
            """{"choices":[{"delta":"private response"}]}""",
            """{"choices":[{"delta":{"audio":"private response"}}]}""",
            """{"choices":[{"delta":{"audio":{"data":42}}}]}""",
            chunk("%%% private response %%%"),
        )) {
            val error = assertThrows(SpeechFailure::class.java) { MimoTtsDecoder {}.event(payload) }
            assertEquals(SpeechErrorCode.PROTOCOL, error.code)
            assertFalse(error.userMessage.contains("private"))
            assertFalse(error.toString().contains("private"))
        }
        val error = assertThrows(SpeechFailure::class.java) {
            MimoTtsDecoder {}.event("""{"error":{"message":"private response"}}""")
        }
        assertEquals(SpeechErrorCode.SERVER, error.code)
        assertFalse(error.userMessage.contains("private"))
        assertFalse(error.toString().contains("private"))
    }

    private fun body(request: Request) = JSONObject(Buffer().also { request.body!!.writeTo(it) }.readUtf8())

    private fun chunk(data: String?, finishReason: String? = null): String {
        val audio = JSONObject().put("id", "shared-audio-id")
        if (data != null) audio.put("data", data)
        return JSONObject().put("choices", JSONArray().put(JSONObject()
            .put("delta", JSONObject().put("audio", audio))
            .put("finish_reason", finishReason ?: JSONObject.NULL))).toString()
    }
}
