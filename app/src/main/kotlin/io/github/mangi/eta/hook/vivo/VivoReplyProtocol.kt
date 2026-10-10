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

    const val STREAM_TOKEN = "eta_stream_token"
    const val STREAM_TEXT = "eta_stream_text"

    fun reply(turn: Turn, text: String, index: Int = 1, isLast: Boolean = true,
        streamToken: String? = null, fullText: String = text): String = JSONObject()
        .put("main_type", "data")
        .put("subs_type", "talk")
        .put("trace_id", turn.traceId)
        .put("request_id", turn.requestId)
        .put("sid", turn.sessionId)
        .put("pro_id", turn.productId)
        .put("code", 0)
        .put("desc", "success")
        .put("ack", false)
        .put("is_last", isLast)
        .put("multi_task_end", false)
        .put("idx", index)
        .put("data", JSONObject().put("text", text).also { data ->
            streamToken?.let { data.put("extras", JSONObject().put(STREAM_TOKEN, it).put(STREAM_TEXT, fullText)) }
        })
        .toString()
}
