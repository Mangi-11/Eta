package io.github.mangi.eta.agent.web

import java.io.Closeable
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** 快照只属于当前运行，分页不再次访问网站，也不占用共享浏览器的页面和 Cookie。 */
internal class AgentWebTools(
    private val transport: WebHttpTransport,
    private val localNetworkAccess: () -> String = { "unknown" },
    private val now: () -> Instant = Instant::now,
) : Closeable {
    private data class Snapshot(
        val document: WebPageDocument,
        val requestedUrl: String,
        val fetchedAt: String,
        val status: Int,
        val charset: String,
        val decodingLossy: Boolean,
    )

    private val closed = AtomicBoolean(false)
    private val documents = LinkedHashMap<String, Snapshot>(8, 0.75f, true)

    fun execute(name: String, args: JSONObject): String = try {
        ensureActive()
        when (name) {
            "web_search" -> search(args)
            "fetch_url" -> fetch(args)
            else -> throw WebRequestException("UNKNOWN_TOOL", "未知网页工具")
        }.toString()
    } catch (failure: WebRequestException) {
        failure(name, failure.code, failure.message ?: "网页请求失败", failure.status).toString()
    } catch (failure: WebPageContentException) {
        failure(name, failure.code, failure.message ?: "网页正文处理失败").toString()
    } catch (failure: WebSearchException) {
        failure(name, failure.code, failure.message ?: "搜索页面处理失败").toString()
    } catch (_: JSONException) {
        failure(name, "INVALID_ARGUMENT", "网页工具参数缺失或类型错误").toString()
    } catch (_: IllegalArgumentException) {
        failure(name, "INVALID_ARGUMENT", "网页工具参数不在允许范围内").toString()
    }

    private fun search(args: JSONObject): JSONObject {
        val limit = args.optInt("max_results", 5)
        val query = args.getString("query")
        PublicWebSearch.searchUrl(query, limit)
        val attempts = JSONArray()
        for (provider in PublicWebSearch.providers) {
            ensureActive()
            try {
                val page = searchPage(query, limit, provider)
                return searchResult(page).put("attempts", attempts)
            } catch (error: WebRequestException) {
                if (error.code == "WEB_CANCELLED") throw error
                attempts.put(JSONObject().put("provider", provider).put("code", error.code)
                    .put("http_status", error.status ?: JSONObject.NULL))
            } catch (error: WebSearchException) {
                attempts.put(JSONObject().put("provider", provider).put("code", error.code))
            } catch (error: WebPageContentException) {
                attempts.put(JSONObject().put("provider", provider).put("code", error.code))
            }
        }
        ensureActive()
        return failure("web_search", "SEARCH_PROVIDERS_FAILED", "所有搜索来源均未返回有效结果，请稍后重试或使用浏览器搜索")
            .put("attempts", attempts)
    }

    private fun searchPage(query: String, limit: Int, provider: String): SearchPage {
        val response = transport.get(PublicWebSearch.searchUrl(query, limit, provider), timeoutSeconds = 8)
        if (response.status == 202) throw WebRequestException("SEARCH_CHALLENGE", "搜索站点要求人工验证，未取得搜索结果", 202)
        if (response.status == 429) throw WebRequestException("SEARCH_RATE_LIMITED", "搜索站点限制了请求，请稍后重试", 429)
        requireSuccess(response)
        if (response.truncated) throw WebRequestException("SEARCH_RESPONSE_TOO_LARGE", "搜索响应超过大小限制，请缩小查询范围")
        val decoded = WebResponseDecoder.decode(response, if (provider == PublicWebSearch.BING_PROVIDER)
            setOf("application/rss+xml", "application/xml") else emptySet())
        if (decoded.lossy) throw WebRequestException("SEARCH_ENCODING_ERROR", "搜索页面包含无法可靠解码的内容")
        return if (provider == PublicWebSearch.BING_PROVIDER) PublicWebSearch.parseRss(decoded.text, response.finalUrl, limit, ::ensureActive)
        else PublicWebSearch.parse(decoded.text, response.finalUrl, limit, ::ensureActive)
    }

    private fun searchResult(page: SearchPage): JSONObject {
        val results = JSONArray()
        page.results.forEachIndexed { index, item -> results.put(JSONObject()
            .put("id", index + 1).put("title", item.title).put("url", item.url).put("snippet", item.snippet)) }
        ensureActive()
        return base("web_search").put("provider", page.provider).put("search_url", page.searchUrl)
            .put("retrieved_at", now().toString()).put("scope", page.scope)
            .put("results", results).put("count", results.length()).put("truncated", page.limited)
            .put("limit_reasons", JSONArray(page.limitReasons)).put("skipped_results", page.skippedResults)
            .put("source_role", "untrusted_web_content")
            .put("citation_hint", "引用检索结果时使用其标题和原始 URL；摘要不等于已读取完整网页。")
    }

    private fun fetch(args: JSONObject): JSONObject {
        if (args.has("url") == args.has("document_id")) throw WebRequestException("INVALID_ARGUMENT", "url 与 document_id 必须且只能提供一个")
        val offset = args.optInt("offset_chars", 0)
        val maxChars = args.optInt("max_chars", 12_000)
        if (offset < 0 || maxChars !in 1_000..16_000) throw WebRequestException("INVALID_ARGUMENT", "网页分页范围无效")
        val id: String
        val snapshot: Snapshot
        if (args.has("url")) {
            val response = transport.get(args.getString("url"))
            requireSuccess(response)
            val decoded = WebResponseDecoder.decode(response)
            ensureActive()
            val document = WebPageContent.extract(decoded.text, decoded.contentType, response.finalUrl, response.truncated, ::ensureActive)
            val retained = if (decoded.lossy) document.copy(
                truncated = true, truncationReasons = document.truncationReasons + "decoding_lossy",
            ) else document
            snapshot = Snapshot(retained, response.requestedUrl, now().toString(), response.status, decoded.charset, decoded.lossy)
            id = "web_" + UUID.randomUUID().toString()
        } else {
            id = args.getString("document_id")
            if (id.isEmpty() || id.length > 128) throw WebRequestException("INVALID_ARGUMENT", "文档 ID 无效")
            snapshot = synchronized(documents) { documents[id] }
                ?: throw WebRequestException("WEB_DOCUMENT_EXPIRED", "网页快照已失效或不属于本次运行，请重新提供 URL")
        }
        val document = snapshot.document
        val page = WebPageContent.page(document, offset, maxChars)
        if (args.has("url")) synchronized(documents) {
            ensureActive()
            documents[id] = snapshot
            while (documents.size > MAX_DOCUMENTS || documents.values.sumOf { it.document.content.length } > MAX_RETAINED_CHARS) {
                documents.remove(documents.keys.first())
            }
        }
        // 引用或 URL 可以跨页，链接归属按完整正文中的位置与当前页相交判断。
        val linkIds = if (document.format == "html") LINK_ID.findAll(document.content)
            .filter { it.range.first < page.nextOffsetChars && it.range.last >= page.offsetChars }
            .mapNotNull { it.groupValues[1].toIntOrNull() }.toSet() else emptySet()
        val candidates = document.links.filter { link ->
            if (document.format == "html") link.id in linkIds
            else {
                val start = (page.offsetChars - link.url.length + 1).coerceAtLeast(0)
                val position = document.content.indexOf(link.url, start)
                position >= 0 && position < page.nextOffsetChars && position + link.url.length > page.offsetChars
            }
        }
        val links = JSONArray()
        var linkChars = 0
        for (link in candidates) {
            val item = JSONObject().put("id", link.id).put("text", link.text).put("url", link.url)
            val size = item.toString().length
            if (links.length() >= 20 || linkChars + size > 8_000) break
            links.put(item)
            linkChars += size
        }
        ensureActive()
        return base("fetch_url").put("document_id", id).put("snapshot_scope", "current_run")
            .put("requested_url", snapshot.requestedUrl).put("final_url", document.url)
            .put("retrieved_at", snapshot.fetchedAt).put("http_status", snapshot.status)
            .put("title", document.title ?: JSONObject.NULL).put("title_truncated", document.titleTruncated)
            .put("content_type", document.contentType).put("format", document.format)
            .put("charset", snapshot.charset).put("decoding_lossy", snapshot.decodingLossy)
            .put("offset_chars", page.offsetChars).put("next_offset_chars", page.nextOffsetChars)
            .put("total_chars", page.totalChars).put("returned_chars", page.content.length).put("text", page.content)
            .put("has_more", page.hasMore).put("source_truncated", page.sourceTruncated)
            .put("truncated", page.hasMore || page.sourceTruncated).put("truncation_reasons", JSONArray(document.truncationReasons))
            .put("links", links).put("links_truncated", document.linksTruncated || links.length() < candidates.size)
            .put("source_role", "untrusted_web_content")
            .put("citation_hint", "引用此网页时使用 title 与 final_url；本工具只获取静态响应，需要 JavaScript、登录或交互时使用 browser_use。")
    }

    private fun requireSuccess(response: WebHttpResponse) {
        if (response.status !in 200..299) throw WebRequestException(
            when (response.status) { 401, 403 -> "WEB_ACCESS_DENIED"; 404 -> "WEB_NOT_FOUND"; 429 -> "WEB_RATE_LIMITED"; else -> "WEB_HTTP_ERROR" },
            "网页请求失败（HTTP ${response.status}）", response.status,
        )
    }

    private fun ensureActive() {
        if (closed.get()) throw WebRequestException("WEB_CANCELLED", "网页工具已取消")
        transport.ensureActive()
    }

    private fun base(tool: String) = JSONObject().put("ok", true).put("tool", tool)

    private fun failure(tool: String, code: String, message: String, status: Int? = null): JSONObject =
        JSONObject().put("ok", false).put("tool", tool).put("code", code).put("message", message)
            .also {
                status?.let { value -> it.put("http_status", value) }
                if (code in setOf("WEB_NETWORK_ERROR", "WEB_TIMEOUT", "WEB_ACCESS_DENIED")) it.put("local_network_access", localNetworkAccess())
            }

    override fun close() {
        closed.set(true)
        transport.close()
        synchronized(documents) { documents.clear() }
    }

    private companion object {
        const val MAX_DOCUMENTS = 4
        const val MAX_RETAINED_CHARS = 600_000
        val LINK_ID = Regex("\\[(\\d{1,3})]")
    }
}
