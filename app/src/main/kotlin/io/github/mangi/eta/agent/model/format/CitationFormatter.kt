package io.github.mangi.eta.agent.model.format

import kotlinx.serialization.Serializable

@Serializable
data class CitationSource(
    val url: String,
    val title: String = "",
)

/** 过滤非 HTTP(S) 链接并按首次出现顺序去重，生成供 UI 展示的结构化参考来源。 */
internal object CitationFormatter {
    const val MAX_SOURCES = 64
    private const val MAX_URL_CHARS = 2048
    private const val MAX_TITLE_CHARS = 240
    private val WHITESPACE_REGEX = Regex("\\s+")

    fun normalize(sources: Iterable<CitationSource>): List<CitationSource> {
        val seen = LinkedHashMap<String, CitationSource>()
        for (source in sources) {
            if (seen.size >= MAX_SOURCES) break
            val url = source.url.trim()
            if (url.length > MAX_URL_CHARS || !isHttpUrl(url)) continue
            val title = normalizeTitle(source.title)
            val existing = seen[url]
            if (existing == null) {
                seen[url] = CitationSource(url = url, title = title)
            } else if (existing.title.isBlank() && title.isNotBlank()) {
                seen[url] = existing.copy(title = title)
            }
        }
        return seen.values.toList()
    }

    fun merge(first: List<CitationSource>, second: List<CitationSource>): List<CitationSource> {
        if (first.isEmpty()) return normalize(second)
        if (second.isEmpty()) return normalize(first)
        return normalize(first + second)
    }

    private fun isHttpUrl(url: String): Boolean =
        url.startsWith("https://", ignoreCase = true) || url.startsWith("http://", ignoreCase = true)

    private fun normalizeTitle(raw: String): String =
        raw.replace(WHITESPACE_REGEX, " ").trim().take(MAX_TITLE_CHARS)
}
