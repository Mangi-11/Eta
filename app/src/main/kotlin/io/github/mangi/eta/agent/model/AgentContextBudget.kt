package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentTokenUsage
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.ceil

/** usage 只校准同一模型的请求估算，不把累计计费用量当作窗口占用。 */
internal class AgentContextBudget(private val window: Int?) {
    private var calibration = 1.0

    fun observe(usage: AgentTokenUsage?, requestEstimate: Int) {
        val input = usage?.inputTokens ?: usage?.contextTokens ?: return
        if (input > 0 && requestEstimate > 0) {
            calibration = (input.toDouble() / requestEstimate).coerceIn(1.0, 8.0)
        }
    }

    fun estimate(messages: JSONArray, tools: JSONArray): Int =
        ceil(rawEstimate(messages, tools) * calibration).toInt()

    fun shouldCompact(tokens: Int): Boolean =
        window?.takeIf { it > 0 }?.let { tokens >= it * TRIGGER_RATIO } == true

    fun exceedsWindow(tokens: Int): Boolean = window?.takeIf { it > 0 }?.let { tokens >= it } == true

    companion object {
        const val TRIGGER_RATIO = 0.85
        const val RECENT_MESSAGES = 4
        const val RECENT_RATIO = 0.20
        const val MAX_OVERFLOW_ATTEMPTS = 3

        fun textTokens(text: String): Int {
            var ascii = 0
            var other = 0
            text.codePoints().forEach { if (it < 128) ascii++ else other++ }
            return (ascii + 2) / 3 + other
        }

        fun rawEstimate(messages: JSONArray, tools: JSONArray = JSONArray()): Int {
            var tokens = textTokens(tools.toString()) + 16
            for (index in 0 until messages.length()) {
                val message = messages.optJSONObject(index) ?: continue
                val copy = JSONObject()
                message.keys().forEach { key -> if (key != "content") copy.put(key, message.get(key)) }
                val parts = message.optJSONArray("content")
                if (parts != null) {
                    val text = JSONArray()
                    for (partIndex in 0 until parts.length()) {
                        val part = parts.optJSONObject(partIndex) ?: continue
                        if (part.optString("type") in setOf("image_url", "input_image", "image")) {
                            tokens += 4096
                        } else text.put(part)
                    }
                    copy.put("content", text)
                } else copy.put("content", message.opt("content"))
                tokens += textTokens(copy.toString()) + 8
            }
            return tokens
        }
    }
}
