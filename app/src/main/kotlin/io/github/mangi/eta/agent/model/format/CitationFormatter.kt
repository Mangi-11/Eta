package io.github.mangi.eta.agent.model.format

internal data class CitationAnnotation(
    val start: Int?,
    val end: Int?,
    val url: String,
    val title: String,
)

internal object CitationFormatter {
    fun apply(text: String, annotations: List<CitationAnnotation>): String {
        val unique = annotations
            .filter { it.url.startsWith("https://") || it.url.startsWith("http://") }
            .distinctBy { it.url }
        if (unique.isEmpty()) return text

        val valid = unique.withIndex().filter { (_, citation) ->
            citation.end != null && citation.end in 0..text.length &&
                citation.start != null && citation.start in 0..citation.end
        }
        val result = StringBuilder(text)
        valid.sortedByDescending { it.value.end }.forEach { (index, citation) ->
            result.insert(citation.end!!, " [[${index + 1}]](<${citation.url.escapeAngleUrl()}>)")
        }
        val invalid = unique.filterNot(valid.map { it.value }.toSet()::contains)
        if (invalid.isNotEmpty()) {
            result.append("\n\n来源：")
            invalid.forEachIndexed { index, citation ->
                val number = unique.indexOf(citation) + 1
                val label = citation.title.ifBlank { "来源 ${index + 1}" }
                result.append("\n- [$number] [$label](<${citation.url.escapeAngleUrl()}>)")
            }
        }
        return result.toString()
    }

    private fun String.escapeAngleUrl(): String = replace(">", "%3E")
}
