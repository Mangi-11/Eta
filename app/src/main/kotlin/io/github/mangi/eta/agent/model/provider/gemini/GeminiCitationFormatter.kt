package io.github.mangi.eta.agent.model.provider.gemini

import org.json.JSONArray
import io.github.mangi.eta.agent.model.format.CitationAnnotation
import io.github.mangi.eta.agent.model.format.CitationFormatter

internal object GeminiCitationFormatter {
    fun apply(text: String, annotations: JSONArray): String {
        if (annotations.length() == 0) return text
        val boundaries = mutableMapOf(0 to 0)
        var chars = 0
        var bytes = 0
        while (chars < text.length) {
            val next = chars + Character.charCount(text.codePointAt(chars))
            bytes += text.substring(chars, next).toByteArray(Charsets.UTF_8).size
            chars = next
            boundaries[bytes] = chars
        }
        val citations = (0 until annotations.length()).mapNotNull { index ->
            val annotation = annotations.optJSONObject(index) ?: return@mapNotNull null
            if (annotation.optString("type") != "url_citation") return@mapNotNull null
            fun offset(key: String): Int? {
                val value = annotation.opt(key) as? Number ?: return null
                val byteOffset = value.toLong().takeIf { it in 0..bytes.toLong() && it.toDouble() == value.toDouble() } ?: return null
                return boundaries[byteOffset.toInt()]
            }
            val start = offset("start_index")
            val end = offset("end_index")
            CitationAnnotation(start, end, annotation.optString("url"), annotation.optString("title"))
        }
        return CitationFormatter.apply(text, citations)
    }
}
