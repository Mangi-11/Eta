package io.github.mangi.eta.agent.voice.asr

import org.json.JSONObject

/** text/stash 是同一句的替换快照；已完成的 item 不能被迟到的预览覆盖。 */
internal class QwenTranscript {
    private val items = linkedMapOf<String, String>()
    private val completed = mutableSetOf<String>()

    fun accept(event: JSONObject): String {
        val id = event.optString("item_id")
        when (event.optString("type")) {
            "conversation.item.input_audio_transcription.text" -> if (id !in completed) {
                items[id] = event.optString("text") + event.optString("stash")
            }
            "conversation.item.input_audio_transcription.completed" -> {
                items[id] = event.optString("transcript")
                completed += id
            }
        }
        return items.values.joinToString("")
    }

    fun result(): String = items.filterKeys { it in completed }.values.joinToString("").trim()
}
