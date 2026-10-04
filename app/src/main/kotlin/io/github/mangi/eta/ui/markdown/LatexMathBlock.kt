package io.github.mangi.eta.ui.markdown

import android.annotation.SuppressLint
import android.os.Build
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Issue #101 階段2：塊級公式的 KaTeX WebView。
 *
 * 加載失敗（離線 CDN 不可達等）時回調 [onFallback]，調用方顯示原文代碼塊兜底。
 * 高度取 KaTeX 渲染後的 `scrollHeight`（CSS px ≈ dp，直接用 dp，不經 density 換算）；
 * 渲染是異步外鏈，故在 onPageFinished 後延遲複測，避免量到空 div 被裁剪。
 */
@Composable
internal fun LatexWebView(
    html: String,
    modifier: Modifier = Modifier,
    onFallback: () -> Unit = {},
) {
    var webHeightPx by remember(html) { mutableStateOf<Int?>(null) }
    // CSS px 與 dp 在默認 scale 下 1:1，直接當 dp 用。
    val webHeight = webHeightPx?.dp ?: 64.dp

    @SuppressLint("SetJavaScriptEnabled")
    AndroidView(
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                // S1 硬化：不碰本地文件，不混入 http。
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                }
                setBackgroundColor(0x00000000)
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                webViewClient = object : WebViewClient() {
                    fun remeasure(view: WebView) {
                        view.evaluateJavascript(
                            "(function(){var m=document.getElementById('math');var h=Math.max(document.body.scrollHeight,m?m.scrollHeight:0);return h;})()",
                        ) { value ->
                            value?.trim('"')?.toDoubleOrNull()?.toInt()?.let { webHeightPx = it }
                        }
                    }

                    override fun onPageFinished(view: WebView, url: String?) {
                        remeasure(view)
                        // KaTeX 外鏈異步：渲染完成前量到的是空 div，延遲複測一次。
                        view.postDelayed({ remeasure(view) }, 400)
                    }

                    override fun onReceivedHttpError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        errorResponse: WebResourceResponse?,
                    ) {
                        if (request?.isForMainFrame == true) onFallback()
                    }

                    @Suppress("DEPRECATION")
                    override fun onReceivedError(
                        view: WebView,
                        errorCode: Int,
                        description: String?,
                        failingUrl: String?,
                    ) {
                        onFallback()
                    }

                    override fun onReceivedError(
                        view: WebView,
                        request: WebResourceRequest,
                        error: WebResourceError,
                    ) {
                        if (request.isForMainFrame) onFallback()
                    }

                    // 公式內的 \href 點擊不在 WebView 內導航覆蓋公式，直接攔截。
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true
                }
            }
        },
        update = { view ->
            // S2 B1：重組（滾動/高度回寫/主題）不重複 loadData，只在 html 變化時加載。
            if (view.getTag(TAG_HTML_KEY) != html) {
                view.setTag(TAG_HTML_KEY, html)
                view.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
            }
        },
        onRelease = { it.destroy() },
        modifier = modifier
            .fillMaxWidth()
            .height(webHeight),
    )
}

private const val TAG_HTML_KEY = 0x4C415445
