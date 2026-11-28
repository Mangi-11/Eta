package io.github.mangi.eta.agent.model.provider.gemini

import org.json.JSONArray
import io.github.mangi.eta.agent.model.format.CitationFormatter
import io.github.mangi.eta.agent.model.format.CitationSource

internal object GeminiCitationFormatter {
    fun extract(annotations: JSONArray): List<CitationSource> {
        if (annotations.length() == 0) return emptyList()
        val sources = (0 until annotations.length()).mapNotNull { index ->
            val annotation = annotations.optJSONObject(index) ?: return@mapNotNull null
            if (annotation.optString("type") != "url_citation") return@mapNotNull null
            CitationSource(
                url = annotation.optString("url"),
                title = annotation.optString("title"),
            )
        }
        return CitationFormatter.normalize(sources)
    }
}
