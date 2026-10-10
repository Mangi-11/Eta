package io.github.mangi.eta.agent.web

import java.util.Locale
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.parser.Parser

internal data class SearchResult(val title: String, val url: String, val snippet: String)

internal data class SearchPage(
    val provider: String,
    val searchUrl: String,
    val results: List<SearchResult>,
    val limited: Boolean,
    val scope: String = "first_page",
    val limitReasons: List<String> = emptyList(),
    val skippedResults: Int = 0,
)

internal class WebSearchException(val code: String, message: String) : IllegalArgumentException(message)

/** 公开搜索页只作为链接发现来源；页面挑战和解析失败不能伪装成零条结果。 */
internal object PublicWebSearch {
    const val PROVIDER = "duckduckgo_html"
    const val BING_PROVIDER = "bing_rss"
    val providers = listOf(PROVIDER, BING_PROVIDER)
    const val MAX_QUERY_CHARS = 2_000
    const val MAX_URL_CHARS = 8_192
    const val MAX_HTML_CHARS = 512_000
    private const val MAX_TITLE_CHARS = 512
    private const val MAX_SNIPPET_CHARS = 1_200
    private const val MAX_RESULT_CHARS = 24_000
    private const val MAX_RESULT_BLOCKS = 100
    private const val SEARCH_ENDPOINT = "https://html.duckduckgo.com/html/"

    fun searchUrl(query: String, maxResults: Int = 5, provider: String = PROVIDER): String {
        validateLimit(maxResults)
        if (query.isBlank() || query.length > MAX_QUERY_CHARS || '\u0000' in query ||
            !Charsets.UTF_8.newEncoder().canEncode(query)
        ) fail("INVALID_ARGUMENT", "query 必须为 1 到 2000 个字符的有效文本")
        val endpoint = when (provider) {
            PROVIDER -> SEARCH_ENDPOINT
            BING_PROVIDER -> "https://www.bing.com/search"
            else -> fail("INVALID_ARGUMENT", "未知搜索来源")
        }
        val builder = requireNotNull(endpoint.toHttpUrlOrNull()).newBuilder()
            .addQueryParameter("q", query.trim())
        if (provider == BING_PROVIDER) builder.addQueryParameter("format", "rss")
        val url = builder.build().toString()
        if (url.length > MAX_URL_CHARS) fail("INVALID_ARGUMENT", "查询编码后的网址超过长度限制，请缩短 query")
        return url
    }

    fun parseRss(html: String, finalUrl: String, maxResults: Int = 5, ensureActive: () -> Unit = {}): SearchPage {
        validateLimit(maxResults)
        ensureActive()
        if (html.length > MAX_HTML_CHARS) fail("SEARCH_RESPONSE_TOO_LARGE", "搜索页面超过解析大小限制")
        val base = httpUrl(finalUrl)?.takeIf { it.host == "bing.com" || it.host.endsWith(".bing.com") }
            ?: fail("SEARCH_PARSE_FAILED", "搜索响应没有来自预期的搜索站点")
        val document = Jsoup.parse(html, base.toString(), Parser.xmlParser())
        if (document.selectFirst("rss > channel") == null) fail("SEARCH_PARSE_FAILED", "未收到有效搜索 RSS")
        val reasons = linkedSetOf<String>()
        val items = document.select("rss > channel > item")
        if (items.size > MAX_RESULT_BLOCKS) reasons += "scan_limit"
        val results = mutableListOf<SearchResult>()
        val seen = hashSetOf<String>()
        var skipped = 0
        var chars = 0
        for (item in items.take(MAX_RESULT_BLOCKS)) {
            ensureActive()
            val url = item.selectFirst("link")?.text()?.let(::httpUrl)
                ?.takeIf { it.username.isEmpty() && it.password.isEmpty() }?.toString()
            val rawTitle = item.selectFirst("title")?.text().orEmpty().trim()
            if (url == null || rawTitle.isBlank()) { skipped++; reasons += "invalid_result"; continue }
            if (!seen.add(url)) continue
            if (results.size >= maxResults) { reasons += "result_limit"; continue }
            val rawSnippet = Jsoup.parse(item.selectFirst("description")?.text().orEmpty()).text().trim()
            val title = takeCompleteCharacters(rawTitle, MAX_TITLE_CHARS)
            val snippet = takeCompleteCharacters(rawSnippet, MAX_SNIPPET_CHARS)
            if (title.length != rawTitle.length || snippet.length != rawSnippet.length) reasons += "text_limit"
            val cost = title.length + snippet.length + url.length
            if (chars + cost > MAX_RESULT_CHARS) { reasons += "output_limit"; continue }
            results += SearchResult(title, url, snippet)
            chars += cost
        }
        if (results.isEmpty()) fail("SEARCH_PARSE_FAILED", "未识别到有效搜索结果")
        ensureActive()
        return SearchPage(BING_PROVIDER, base.toString(), results, reasons.isNotEmpty(),
            limitReasons = reasons.toList(), skippedResults = skipped)
    }

    fun parse(
        html: String,
        finalUrl: String,
        maxResults: Int = 5,
        ensureActive: () -> Unit = {},
    ): SearchPage {
        validateLimit(maxResults)
        ensureActive()
        if (html.length > MAX_HTML_CHARS) fail("SEARCH_RESPONSE_TOO_LARGE", "搜索页面超过解析大小限制")
        val base = httpUrl(finalUrl)?.takeIf { isSearchHost(it.host) }
            ?: fail("SEARCH_PARSE_FAILED", "搜索响应没有来自预期的搜索站点")
        val document = Jsoup.parse(html, base.toString())
        ensureActive()
        if (document.selectFirst("#challenge-form, .anomaly-modal__modal, .anomaly-modal__puzzle") != null ||
            document.select("form[action]").any { form ->
                base.resolve(form.attr("action"))?.encodedPath == "/anomaly.js"
            }
        ) fail("SEARCH_CHALLENGE", "搜索站点要求人工验证，请在浏览器中处理或稍后重试")
        if (rateLimited(document)) fail("SEARCH_RATE_LIMITED", "搜索站点暂时限制请求，请稍后重试")

        val blocks = document.select("#links .web-result, .results > .result")
            .filterNot { it.hasClass("result--ad") || it.hasClass("result--no-result") }
        val reasons = linkedSetOf<String>()
        if (blocks.size > MAX_RESULT_BLOCKS) reasons += "scan_limit"
        val results = mutableListOf<SearchResult>()
        val seen = hashSetOf<String>()
        var skipped = 0
        var chars = 0
        for (block in blocks.take(MAX_RESULT_BLOCKS)) {
            ensureActive()
            val anchor = block.selectFirst("a.result__a[href]") ?: block.selectFirst("h2 a[href]")
            val url = anchor?.attr("href")?.let { resultUrl(it, base) }
            val rawTitle = anchor?.text().orEmpty().trim()
            if (url == null || rawTitle.isBlank()) {
                skipped++
                reasons += "invalid_result"
                continue
            }
            if (!seen.add(url)) continue
            if (results.size >= maxResults) {
                reasons += "result_limit"
                continue
            }
            val rawSnippet = block.selectFirst(".result__snippet")?.text().orEmpty().trim()
            val title = takeCompleteCharacters(rawTitle, MAX_TITLE_CHARS)
            val snippet = takeCompleteCharacters(rawSnippet, MAX_SNIPPET_CHARS)
            if (title.length != rawTitle.length || snippet.length != rawSnippet.length) reasons += "text_limit"
            val cost = title.length + snippet.length + url.length
            if (chars + cost > MAX_RESULT_CHARS) {
                reasons += "output_limit"
                continue
            }
            results += SearchResult(title, url, snippet)
            chars += cost
        }
        if (results.isEmpty()) {
            val emptyNotice = document.selectFirst(".no-results")
            if (blocks.isNotEmpty() || emptyNotice == null || emptyNotice.text().isBlank()) {
                fail("SEARCH_PARSE_FAILED", "未识别到搜索结果或明确的无结果提示，页面结构可能已变化")
            }
        }
        ensureActive()
        return SearchPage(PROVIDER, base.toString(), results, reasons.isNotEmpty(),
            limitReasons = reasons.toList(), skippedResults = skipped)
    }

    private fun resultUrl(href: String, base: HttpUrl): String? {
        if (href.isBlank() || href.length > MAX_URL_CHARS || href.any { it == '\r' || it == '\n' || it == '\u0000' }) return null
        val resolved = base.resolve(href) ?: return null
        val url = if (isSearchHost(resolved.host) && resolved.encodedPath.trimEnd('/') == "/l") {
            val target = resolved.queryParameter("uddg") ?: return null
            httpUrl(target) ?: return null
        } else resolved
        if (isSearchHost(url.host) && url.encodedPath.trimEnd('/') in setOf("/l", "/y.js", "/anomaly.js")) return null
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) return null
        return url.toString().takeIf { it.length <= MAX_URL_CHARS }
    }

    private fun httpUrl(value: String): HttpUrl? {
        if (value.length > MAX_URL_CHARS || value.any { it == '\r' || it == '\n' || it == '\u0000' }) return null
        return value.toHttpUrlOrNull()
    }

    private fun isSearchHost(host: String): Boolean =
        host == "duckduckgo.com" || host.endsWith(".duckduckgo.com")

    private fun rateLimited(document: Document): Boolean {
        val headings = listOf(document.title()) + document.select("h1, .error-title, #error-title").map { it.text() }
        return headings.any { text ->
            val normalized = text.trim().lowercase(Locale.ROOT)
            normalized == "too many requests" || normalized == "429 too many requests" ||
                normalized == "rate limit exceeded" || normalized == "rate limited"
        }
    }

    private fun validateLimit(maxResults: Int) {
        if (maxResults !in 1..10) fail("INVALID_ARGUMENT", "max_results 必须为 1 到 10")
    }

    private fun fail(code: String, message: String): Nothing = throw WebSearchException(code, message)
}
