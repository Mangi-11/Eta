package io.github.mangi.eta.agent.model.provider

import org.json.JSONArray
import org.json.JSONObject

internal fun providerMessageText(content: Any?): String =
    when (content) {
        null, JSONObject.NULL -> ""
        is String -> content
        is JSONArray -> buildString {
            for (index in 0 until content.length()) {
                val item = content.optJSONObject(index) ?: continue
                if (item.optString("type") !in TEXT_CONTENT_TYPES) continue
                if (isNotEmpty()) append('\n')
                append(item.optString("text"))
            }
        }
        else -> content.toString()
    }

private val TEXT_CONTENT_TYPES = setOf("text", "input_text", "output_text")
