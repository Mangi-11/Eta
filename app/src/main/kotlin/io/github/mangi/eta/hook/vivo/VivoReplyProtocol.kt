package io.github.mangi.eta.hook.vivo

import org.json.JSONObject

/** BlueLM 6.8.5.3's ordinary text response, consumed by ChatRepository. */
internal object VivoReplyProtocol {
    data class Turn(
        val traceId: String,
        val requestId: String,
        val sessionId: String,
        val productId: String,
    )

    fun reply(turn: Turn, text: String): String = JSONObject()
        .put("main_type", "data")
        .put("subs_type", "talk")
        .put("trace_id", turn.traceId)
        .put("request_id", turn.requestId)
        .put("sid", turn.sessionId)
        .put("pro_id", turn.productId)
        .put("code", 0)
        .put("desc", "success")
        .put("ack", false)
        .put("is_last", true)
        .put("multi_task_end", false)
        .put("idx", 1)
        .put("data", JSONObject().put("text", text))
        .toString()
}
