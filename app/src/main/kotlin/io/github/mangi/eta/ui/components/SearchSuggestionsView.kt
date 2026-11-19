package io.github.mangi.eta.ui.components

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.runtime.SearchSuggestionsPayload
import java.io.ByteArrayInputStream

/** 官方建议保留原始 HTML/CSS，仅在独立、无脚本的展示视图中加载。 */
@Composable
internal fun SearchSuggestionsView(html: String, modifier: Modifier = Modifier) {
    if (!SearchSuggestionsPayload.isValidHtml(html)) return
    val context = LocalContext.current
    val density = LocalDensity.current
    key(context, html) {
        var contentHeightPx by remember { mutableIntStateOf(with(density) { 96.dp.roundToPx() }) }
        AndroidView(
            factory = { viewContext ->
                SearchSuggestionsWebView(viewContext) { contentHeightPx = it }.apply {
                    loadDataWithBaseURL(null, searchSuggestionsDocument(html), "text/html", "UTF-8", null)
                }
            },
            onRelease = SearchSuggestionsWebView::release,
            modifier = modifier.fillMaxWidth().height(with(density) { contentHeightPx.toDp() }.coerceIn(48.dp, 480.dp)),
        )
    }
}

internal class SearchSuggestionsWebView(
    context: Context,
    private val onContentHeightChanged: (Int) -> Unit,
) : WebView(context) {
    private var released = false
    private var reportedHeight = 0
    private var heightReportPending = false
    private val contentHeightPx: Int
        get() = (contentHeight * resources.displayMetrics.density).toInt().coerceAtLeast(1)

    private val reportHeight = Runnable {
        heightReportPending = false
        if (!released) {
            val next = contentHeightPx
            if (next != reportedHeight) {
                reportedHeight = next
                onContentHeightChanged(next)
            }
        }
    }

    init {
        setBackgroundColor(Color.TRANSPARENT)
        isHorizontalScrollBarEnabled = false
        settings.apply {
            javaScriptEnabled = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            allowFileAccess = false
            allowContentAccess = false
            domStorageEnabled = false
            databaseEnabled = false
            cacheMode = WebSettings.LOAD_NO_CACHE
            blockNetworkLoads = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportZoom(false)
        }
        setDownloadListener { _, _, _, _, _ -> }
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                if (!released && request.isForMainFrame && request.hasGesture() && isSearchSuggestionLink(uri)) {
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, uri).apply {
                            addCategory(Intent.CATEGORY_BROWSABLE)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        })
                    } catch (_: ActivityNotFoundException) {
                        Toast.makeText(context, context.getString(R.string.ui_misc_no_browser_for_link), Toast.LENGTH_SHORT).show()
                    } catch (_: SecurityException) {
                        Toast.makeText(context, context.getString(R.string.ui_misc_cannot_open_link), Toast.LENGTH_SHORT).show()
                    }
                }
                return true
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                if (request.url.scheme.equals("data", ignoreCase = true)) null
                else WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // WebView 的正文高度在布局后才能确定；只在实际改变时异步通知 Compose，避免绘制时写状态。
        if (!released && !heightReportPending && contentHeightPx != reportedHeight) {
            heightReportPending = true
            post(reportHeight)
        }
    }

    fun release() {
        if (released) return
        released = true
        removeCallbacks(reportHeight)
        stopLoading()
        webViewClient = WebViewClient()
        removeAllViews()
        destroy()
    }
}

internal fun isSearchSuggestionLink(uri: Uri): Boolean =
    (uri.scheme.equals("https", ignoreCase = true) || uri.scheme.equals("http", ignoreCase = true)) &&
        !uri.host.isNullOrBlank()

internal fun searchSuggestionsDocument(html: String): String = """
    <!doctype html><html><head>
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <meta name="color-scheme" content="light dark">
    <meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; img-src data:; font-src data:; frame-src 'none'; form-action 'none'; base-uri 'none'">
    </head><body>$html</body></html>
""".trimIndent()
