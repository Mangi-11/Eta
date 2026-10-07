package io.github.mangi.eta.agent.web

import java.time.Instant
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentWebToolsTest {
    @Test fun cachedPagesShareTheSameSnapshotWithoutAnotherRequest() {
        val source = "第一行\n" + "a".repeat(998) + "😀" + "正文".repeat(900)
        WebFixtureServer { WebFixtureResponse(body = source.toByteArray()) }.use { server ->
            tools().use { tools ->
                val first = call(tools, "fetch_url", JSONObject().put("url", server.url()).put("max_chars", 1000))
                assertTrue(first.toString(), first.getBoolean("ok"))
                val id = first.getString("document_id")
                val content = StringBuilder(first.getString("text"))
                var page = first
                while (page.getBoolean("has_more")) {
                    page = call(tools, "fetch_url", JSONObject().put("document_id", id).put("offset_chars", page.getInt("next_offset_chars")).put("max_chars", 1000))
                    assertTrue(page.toString(), page.getBoolean("ok"))
                    assertEquals(first.getString("retrieved_at"), page.getString("retrieved_at"))
                    content.append(page.getString("text"))
                }
                assertEquals(source, content.toString())
                assertEquals(1, server.requests.size)
                assertFalse(page.getBoolean("source_truncated"))
            }
        }
    }

    @Test fun snapshotIdsExpireAcrossRunsAndAfterEviction() {
        WebFixtureServer { WebFixtureResponse(body = "text".toByteArray()) }.use { server ->
            tools().use { tools ->
                val first = call(tools, "fetch_url", JSONObject().put("url", server.url("/0"))).getString("document_id")
                repeat(4) { call(tools, "fetch_url", JSONObject().put("url", server.url("/${it + 1}"))) }
                assertEquals("WEB_DOCUMENT_EXPIRED", call(tools, "fetch_url", JSONObject().put("document_id", first)).getString("code"))
                tools().use { other -> assertEquals("WEB_DOCUMENT_EXPIRED", call(other, "fetch_url", JSONObject().put("document_id", first)).getString("code")) }
            }
        }
    }

    @Test fun failedInitialPageDoesNotEvictAUsableSnapshot() {
        WebFixtureServer { WebFixtureResponse(body = "text".toByteArray()) }.use { server ->
            tools().use { tools ->
                val first = call(tools, "fetch_url", JSONObject().put("url", server.url("/0"))).getString("document_id")
                repeat(3) { call(tools, "fetch_url", JSONObject().put("url", server.url("/${it + 1}"))) }
                val invalid = call(tools, "fetch_url", JSONObject().put("url", server.url("/invalid")).put("offset_chars", 100))
                assertEquals("INVALID_CONTENT_RANGE", invalid.getString("code"))
                assertTrue(call(tools, "fetch_url", JSONObject().put("document_id", first)).getBoolean("ok"))
            }
        }
    }

    @Test fun linksCrossingPageBoundariesRemainAvailableOnBothPages() {
        val content = "x".repeat(985) + " https://example.test/long/source " + "tail".repeat(100)
        WebFixtureServer { WebFixtureResponse(body = content.toByteArray()) }.use { server ->
            tools().use { tools ->
                val first = call(tools, "fetch_url", JSONObject().put("url", server.url()).put("max_chars", 1000))
                val second = call(tools, "fetch_url", JSONObject().put("document_id", first.getString("document_id"))
                    .put("offset_chars", first.getInt("next_offset_chars")).put("max_chars", 1000))
                assertEquals("https://example.test/long/source", first.getJSONArray("links").getJSONObject(0).getString("url"))
                assertEquals("https://example.test/long/source", second.getJSONArray("links").getJSONObject(0).getString("url"))
            }
        }
    }

    @Test fun numberedHtmlReferenceSplitAcrossPagesKeepsItsTarget() {
        val html = "<main>" + "x".repeat(994) + "<a href='/source'>link</a></main>"
        WebFixtureServer { WebFixtureResponse(headers = mapOf("Content-Type" to "text/html"), body = html.toByteArray()) }.use { server ->
            tools().use { tools ->
                val first = call(tools, "fetch_url", JSONObject().put("url", server.url()).put("max_chars", 1000))
                assertTrue(first.getString("text").endsWith("["))
                val second = call(tools, "fetch_url", JSONObject().put("document_id", first.getString("document_id"))
                    .put("offset_chars", first.getInt("next_offset_chars")).put("max_chars", 1000))
                assertTrue(second.getString("text").startsWith("1]"))
                assertEquals(server.url("/source"), first.getJSONArray("links").getJSONObject(0).getString("url"))
                assertEquals(server.url("/source"), second.getJSONArray("links").getJSONObject(0).getString("url"))
            }
        }
    }

    @Test fun htmlReturnsNumberedSourcesAndSeparatesSourceLossFromPagination() {
        WebFixtureServer { WebFixtureResponse(headers = mapOf("Content-Type" to "text/html"), body = "<title>标题</title><main>正文<a href='/source'>来源</a></main>".toByteArray()) }.use { server ->
            tools().use { tools ->
                val result = call(tools, "fetch_url", JSONObject().put("url", server.url()))
                assertEquals("标题", result.getString("title"))
                assertTrue(result.getString("text").contains("[1]"))
                assertEquals(server.url("/source"), result.getJSONArray("links").getJSONObject(0).getString("url"))
                assertEquals("untrusted_web_content", result.getString("source_role"))
                assertFalse(result.getBoolean("has_more"))
            }
        }
    }

    @Test fun challengeAndHttpErrorsCannotLookLikeEmptySuccessfulSearch() {
        val client = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(202).message("Accepted")
                .body("<form id='challenge-form'></form>".toResponseBody()).build()
        }).build()
        AgentWebTools(WebHttpTransport(client)).use { tools ->
            val result = call(tools, "web_search", JSONObject().put("query", "example"))
            assertFalse(result.getBoolean("ok"))
            assertEquals("SEARCH_PROVIDERS_FAILED", result.getString("code"))
            assertEquals(2, result.getJSONArray("attempts").length())
            assertEquals("SEARCH_CHALLENGE", result.getJSONArray("attempts").getJSONObject(0).getString("code"))
        }
    }

    @Test fun challengedPrimaryUsesStructuredFallbackWithoutPretendingTheChallengeWasEmpty() {
        val hosts = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val host = chain.request().url.host
            hosts += host
            val challenged = host.endsWith("duckduckgo.com")
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(if (challenged) 202 else 200).message("OK")
                .header("Content-Type", if (challenged) "text/html" else "application/rss+xml; charset=utf-8")
                .body((if (challenged) "<form id='challenge-form'></form>" else
                    "<rss><channel><item><title>Android</title><link>https://developer.android.com/</link><description>官方文档</description></item></channel></rss>").toResponseBody())
                .build()
        }).build()
        AgentWebTools(WebHttpTransport(client)).use { tools ->
            val result = call(tools, "web_search", JSONObject().put("query", "Android"))
            assertTrue(result.toString(), result.getBoolean("ok"))
            assertEquals(PublicWebSearch.BING_PROVIDER, result.getString("provider"))
            assertEquals("SEARCH_CHALLENGE", result.getJSONArray("attempts").getJSONObject(0).getString("code"))
            assertEquals(listOf("html.duckduckgo.com", "www.bing.com"), hosts)
            assertEquals("https://developer.android.com/", result.getJSONArray("results").getJSONObject(0).getString("url"))
        }
    }

    @Test fun invalidQueriesDoNotContactAnyProvider() {
        val client = OkHttpClient.Builder().addInterceptor { error("invalid query cannot send network traffic") }.build()
        AgentWebTools(WebHttpTransport(client)).use { tools ->
            assertEquals("INVALID_ARGUMENT", call(tools, "web_search", JSONObject().put("query", " ")).getString("code"))
        }
    }

    @Test fun successfulSearchReturnsSourcesWithoutOpeningSharedBrowser() {
        val client = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .header("Content-Type", "text/html")
                .body("<div id='links'><div class='web-result'><h2><a class='result__a' href='https://example.test/page'>来源</a></h2><div class='result__snippet'>摘要</div></div></div>".toResponseBody()).build()
        }).build()
        AgentWebTools(WebHttpTransport(client)).use { tools ->
            val result = call(tools, "web_search", JSONObject().put("query", "example"))
            assertTrue(result.toString(), result.getBoolean("ok"))
            assertEquals("duckduckgo_html", result.getString("provider"))
            assertEquals("first_page", result.getString("scope"))
            assertEquals("https://example.test/page", result.getJSONArray("results").getJSONObject(0).getString("url"))
        }
    }

    @Test fun invalidParametersAndClosedToolsHaveStableErrors() {
        val tools = tools()
        assertEquals("INVALID_ARGUMENT", call(tools, "fetch_url", JSONObject().put("url", "https://example.test").put("document_id", "id")).getString("code"))
        tools.close()
        assertEquals("WEB_CANCELLED", call(tools, "fetch_url", JSONObject().put("url", "https://example.test")).getString("code"))
    }

    private fun tools() = AgentWebTools(WebHttpTransport(OkHttpClient()), now = { Instant.parse("2026-10-05T08:00:00Z") })
    private fun call(tools: AgentWebTools, name: String, args: JSONObject): JSONObject = JSONObject(tools.execute(name, args))
}
