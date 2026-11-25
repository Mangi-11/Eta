package io.github.mangi.eta.agent.voice.tts

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DoubaoTtsCodecTest {
    @Test fun encodesSessionEventWithIdAndPayload() {
        val frame = DoubaoTtsCodec.encode(DoubaoTtsCodec.TASK_REQUEST, "sid", "{}")
        val buffer = ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN)
        assertEquals(0x11, buffer.get().toInt())
        assertEquals(0x14, buffer.get().toInt())
        assertEquals(0x10, buffer.get().toInt())
        assertEquals(0, buffer.get().toInt())
        assertEquals(200, buffer.int)
        assertEquals(3, buffer.int)
        assertEquals("sid", ByteArray(3).also(buffer::get).toString(Charsets.UTF_8))
        assertEquals(2, buffer.int)
        assertEquals("{}", ByteArray(2).also(buffer::get).toString(Charsets.UTF_8))
        assertFalse(buffer.hasRemaining())
    }

    @Test fun connectionEventsCarryNoSessionId() {
        val frame = DoubaoTtsCodec.encode(DoubaoTtsCodec.START_CONNECTION, null, "{}")
        assertEquals(4 + 4 + 4 + 2, frame.size)
    }

    @Test fun decodesAudioResponseAfterSessionId() {
        val audio = byteArrayOf(1, 2, 3, 4)
        val frame = ByteBuffer.allocate(4 + 4 + 4 + 3 + 4 + audio.size).order(ByteOrder.BIG_ENDIAN)
            .put(0x11).put(0xB4.toByte()).put(0x00).put(0)
            .putInt(DoubaoTtsCodec.TTS_RESPONSE).putInt(3).put("sid".toByteArray())
            .putInt(audio.size).put(audio).array()
        val decoded = DoubaoTtsCodec.decode(frame)
        assertEquals(DoubaoTtsCodec.TTS_RESPONSE, decoded.event)
        assertTrue(decoded.audio)
        assertArrayEquals(audio, decoded.payload)
    }

    @Test fun decodesConnectionStartedWithConnectId() {
        val frame = ByteBuffer.allocate(4 + 4 + 4 + 2 + 4 + 2).order(ByteOrder.BIG_ENDIAN)
            .put(0x11).put(0x94.toByte()).put(0x10).put(0)
            .putInt(DoubaoTtsCodec.CONNECTION_STARTED).putInt(2).put("c1".toByteArray())
            .putInt(2).put("{}".toByteArray()).array()
        val decoded = DoubaoTtsCodec.decode(frame)
        assertEquals(DoubaoTtsCodec.CONNECTION_STARTED, decoded.event)
        assertEquals("{}", decoded.text())
    }

    @Test fun errorFrameExposesCode() {
        val frame = ByteBuffer.allocate(4 + 4 + 4 + 2).order(ByteOrder.BIG_ENDIAN)
            .put(0x11).put(0xF0.toByte()).put(0x10).put(0)
            .putInt(45000001).putInt(2).put("{}".toByteArray()).array()
        assertEquals(45000001, DoubaoTtsCodec.decode(frame).errorCode)
    }
}
