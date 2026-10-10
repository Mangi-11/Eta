package io.github.mangi.eta.hook.vivo

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VivoReplyProtocolTest {
    @Test
    fun completedReplyUsesNativeTalkCardAndCorrelatesTheOriginalTurn() {
        val turn = VivoReplyProtocol.Turn("trace", "request", "session", "product")
        val reply = JSONObject(VivoReplyProtocol.reply(turn, "回答\n\"quoted\" \\ 🙂"))

        assertEquals("data", reply.getString("main_type"))
        assertEquals("talk", reply.getString("subs_type"))
        assertEquals("trace", reply.getString("trace_id"))
        assertEquals("request", reply.getString("request_id"))
        assertEquals("session", reply.getString("sid"))
        assertEquals("product", reply.getString("pro_id"))
        assertEquals("回答\n\"quoted\" \\ 🙂", reply.getJSONObject("data").getString("text"))
        assertTrue(reply.getBoolean("is_last"))
        assertFalse(reply.getBoolean("ack"))
        assertFalse(reply.getBoolean("multi_task_end"))
        assertEquals(0, reply.getInt("code"))
    }
}
