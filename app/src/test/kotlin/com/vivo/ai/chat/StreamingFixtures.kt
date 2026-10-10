package com.vivo.ai.chat

import java.util.concurrent.ConcurrentHashMap

class MessageExtents(var text: String = "", val extras: ConcurrentHashMap<String, Any> = ConcurrentHashMap())
class GptParams(val trace_id: String, val request_id: String, val data: Any, private val last: Boolean) {
    fun is_last(): Boolean = last
}
class MessageParams(val gptParams: GptParams)
