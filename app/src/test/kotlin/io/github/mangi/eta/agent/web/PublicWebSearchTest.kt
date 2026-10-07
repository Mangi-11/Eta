package io.github.mangi.eta.agent.web

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PublicWebSearchTest {
    @Test fun rssResultsAreBoundedDeduplicatedAndRejectUnsafeLinks() {
        val rss = "<rss><channel>" +
            "<item><title>First</title><link>https://example.com/</link><description>&lt;b&gt;Snippet&lt;/b&gt;</description></item>" +
            "<item><title>Duplicate</title><link>https://example.com/</link></item>" +
            "<item><title>Bad</title><link>javascript:alert(1)</link></item>" +
            "<item><title>Second</title><link>https://example.org/</link></item></channel></rss>"
        val page = PublicWebSearch.parseRss(rss, "https://www.bing.com/search?format=rss", 1)
        assertEquals(1, page.results.size)
        assertEquals("Snippet", page.results.single().snippet)
        assertEquals(PublicWebSearch.BING_PROVIDER, page.provider)
        assertTrue(page.limitReasons.contains("result_limit"))
        assertEquals(1, page.skippedResults)
    }

    @Test(expected = WebSearchException::class)
    fun rssChallengeCannotLookLikeSuccessfulEmptySearch() {
        PublicWebSearch.parseRss("<html>captcha</html>", "https://www.bing.com/search?format=rss")
    }

    private val searchUrl = "https://html.duckduckgo.com/html/?q=example"

    @Test
    fun queryIsEncodedAsOneParameterAndResultLimitIsLocal() {
        val query = "文档 + examples & locale=zh ? # x"
        val url = PublicWebSearch.searchUrl(query, 3).toHttpUrlOrNull()!!
        assertEquals("https", url.scheme)
        assertEquals("html.duckduckgo.com", url.host)
        assertEquals("/html/", url.encodedPath)
        assertEquals(setOf("q"), url.queryParameterNames)
        assertEquals(query, url.queryParameter("q"))
        assertEquals(PublicWebSearch.searchUrl(query, 1), PublicWebSearch.searchUrl(query, 10))
    }

    @Test
    fun queryAndUrlBudgetsAreValidatedBeforeHttp() {
        assertFailure("INVALID_ARGUMENT") { PublicWebSearch.searchUrl(" ") }
        assertFailure("INVALID_ARGUMENT") { PublicWebSearch.searchUrl("x".repeat(2_001)) }
        assertFailure("INVALID_ARGUMENT") { PublicWebSearch.searchUrl("文".repeat(2_000)) }
        assertFailure("INVALID_ARGUMENT") { PublicWebSearch.searchUrl("example", 0) }
        assertFailure("INVALID_ARGUMENT") { PublicWebSearch.searchUrl("example", 11) }
        assertFailure("INVALID_ARGUMENT") { PublicWebSearch.searchUrl("bad\u0000query") }
        assertFailure("INVALID_ARGUMENT") { PublicWebSearch.searchUrl("bad\uD800query") }
    }

    @Test
    fun parsesOrganicResultsAndDecodesRedirectOnlyOnce() {
        val html = page(
            result("A <b>&amp;</b> B", "//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fone%3Fq%3Da%2520b&amp;rut=ignored", "An <b>example</b> &amp; a guide.") +
                result("Plain link", "http://example.test/plain", ""),
        )
        val parsed = PublicWebSearch.parse(html, searchUrl)
        assertEquals("duckduckgo_html", parsed.provider)
        assertEquals(searchUrl, parsed.searchUrl)
        assertEquals("first_page", parsed.scope)
        assertFalse(parsed.limited)
        assertEquals(listOf(
            SearchResult("A & B", "https://example.com/one?q=a%20b", "An example & a guide."),
            SearchResult("Plain link", "http://example.test/plain", ""),
        ), parsed.results)
    }

    @Test
    fun onlyProviderRedirectsAreUnwrappedAndIdenticalUrlsAreDeduplicated() {
        val html = page(
            result("First", "/l/?uddg=https%3A%2F%2Fexample.test%2Farticle", "") +
                result("Duplicate", "https://example.test/article", "") +
                result("Query named uddg", "https://example.test/article?uddg=https%3A%2F%2Fother.test", "") +
                result("Similar host", "https://notduckduckgo.com/l/?uddg=https%3A%2F%2Fother.test", ""),
        )
        val parsed = PublicWebSearch.parse(html, searchUrl)
        assertEquals(3, parsed.results.size)
        assertEquals("https://example.test/article", parsed.results[0].url)
        assertTrue(parsed.results[1].url.startsWith("https://example.test/article?uddg="))
        assertTrue(parsed.results[2].url.startsWith("https://notduckduckgo.com/l/?uddg="))
        assertFalse(parsed.limited)
    }

    @Test
    fun discardsNonHttpLinksAndMarksPartialParsing() {
        val invalid = listOf(
            "javascript:alert(1)", "data:text/plain,example", "file:///data/example", "mailto:example@example.test",
            "/l/?uddg=javascript%3Aalert%281%29", "/l/?rut=missing-target", "/y.js?ad_domain=example.test",
            "https://user:password@example.test/", "https://example.test/" + "x".repeat(8_192),
        )
        val html = page(result("Valid", "https://example.test/valid", "") + invalid.joinToString("") { result("Invalid", it, "") })
        val parsed = PublicWebSearch.parse(html, searchUrl, 10)
        assertEquals(listOf("https://example.test/valid"), parsed.results.map { it.url })
        assertEquals(invalid.size, parsed.skippedResults)
        assertTrue(parsed.limited)
        assertTrue("invalid_result" in parsed.limitReasons)
    }

    @Test
    fun advertisementsAndNavigationAreNotSearchResults() {
        val html = page(
            "<div class='result result--ad'><h2><a class='result__a' href='https://example.test/ad'>Ad</a></h2></div>" +
                result("Organic", "https://example.test/organic", ""),
        ) + "<a href='https://example.test/navigation'>Navigation</a>"
        assertEquals(listOf("Organic"), PublicWebSearch.parse(html, searchUrl).results.map { it.title })
    }

    @Test
    fun observedChallengeMarkupProducesExplicitFailure() {
        listOf(
            "<form id='challenge-form' action='//duckduckgo.com/anomaly.js'><div class='anomaly-modal__modal'>Confirm you are human.</div></form>",
            "<form action='/anomaly.js?sv=html'></form>",
            "<div class='anomaly-modal__puzzle'>Verification</div>",
        ).forEach { challenge ->
            assertFailure("SEARCH_CHALLENGE") { PublicWebSearch.parse(page(challenge), searchUrl) }
        }
    }

    @Test
    fun rateLimitHeadingsAreNotConfusedWithResultSnippets() {
        assertFailure("SEARCH_RATE_LIMITED") {
            PublicWebSearch.parse("<title>429 Too Many Requests</title><h1>Too Many Requests</h1>", searchUrl)
        }
        val parsed = PublicWebSearch.parse(page(result("Rate limit exceeded", "https://example.test/rate", "No results found or CAPTCHA may indicate throttling.")), searchUrl)
        assertEquals(1, parsed.results.size)
    }

    @Test
    fun emptyResultsRequireAnExplicitDomNotice() {
        val empty = PublicWebSearch.parse(page("<div class='result result--no-result'><div class='no-results'>No results found for example.</div></div>"), searchUrl)
        assertTrue(empty.results.isEmpty())
        assertFalse(empty.limited)
        listOf("", "<p>No results found</p>", "<div class='no-results'></div>", result("Broken", "javascript:void(0)", "")).forEach { html ->
            assertFailure("SEARCH_PARSE_FAILED") { PublicWebSearch.parse(page(html), searchUrl) }
        }
    }

    @Test
    fun resultAndTextLimitsAreReportedWithoutSplittingUnicode() {
        val html = page(
            result("x".repeat(511) + "😺 tail", "https://example.test/first", "y".repeat(1199) + "😺 tail") +
                result("Second", "https://example.test/second", "") + result("Third", "https://example.test/third", ""),
        )
        val parsed = PublicWebSearch.parse(html, searchUrl, 2)
        assertEquals(2, parsed.results.size)
        assertEquals(511, parsed.results[0].title.length)
        assertEquals(1199, parsed.results[0].snippet.length)
        assertTrue(parsed.limited)
        assertTrue(parsed.limitReasons.containsAll(listOf("result_limit", "text_limit")))
    }

    @Test
    fun combinedOutputBudgetKeepsUrlsWhole() {
        val html = page((1..4).joinToString("") { result("Entry $it", "https://example.test/$it?q=" + "x".repeat(7_000), "") })
        val parsed = PublicWebSearch.parse(html, searchUrl, 10)
        assertEquals(3, parsed.results.size)
        assertTrue(parsed.limited)
        assertTrue("output_limit" in parsed.limitReasons)
        parsed.results.forEach { assertEquals(7_000, it.url.toHttpUrlOrNull()!!.queryParameter("q")!!.length) }
    }

    @Test
    fun rejectsUnexpectedOriginsOversizedHtmlAndPropagatesCancellation() {
        assertFailure("SEARCH_PARSE_FAILED") { PublicWebSearch.parse(page(""), "https://notduckduckgo.com/html/") }
        assertFailure("SEARCH_RESPONSE_TOO_LARGE") { PublicWebSearch.parse("x".repeat(PublicWebSearch.MAX_HTML_CHARS + 1), searchUrl) }
        var checks = 0
        val cancelled = IllegalStateException("cancelled")
        try {
            PublicWebSearch.parse(page(result("Example", "https://example.test/", "")), searchUrl, ensureActive = {
                if (++checks == 3) throw cancelled
            })
            throw AssertionError("Cancellation should propagate")
        } catch (failure: IllegalStateException) {
            assertTrue(failure === cancelled)
        }
    }

    private fun page(results: String): String = "<html><head><title>Example search</title></head><body><div id='links' class='results'>$results</div></body></html>"

    private fun result(title: String, href: String, snippet: String): String =
        "<div class='result results_links web-result'><div class='result__body'>" +
            "<h2 class='result__title'><a class='result__a' href='$href'>$title</a></h2>" +
            "<a class='result__snippet' href='$href'>$snippet</a></div></div>"

    private fun assertFailure(code: String, block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected $code")
        } catch (failure: WebSearchException) {
            assertEquals(code, failure.code)
        }
    }
}
