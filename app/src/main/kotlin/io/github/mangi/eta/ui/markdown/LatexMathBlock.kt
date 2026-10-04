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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.mangi.eta.R

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
    // factory 只執行一次：用 rememberUpdatedState 讓延遲回調永遠寫到當前 composition 的 state，
    // 否則 html 變化後 remeasure 會寫進已廢棄的舊 state，高度永遠卡在 64.dp。
    val heightUpdater = rememberUpdatedState({ h: Int -> webHeightPx = h })
    // scrollHeight（CSS px）在默認縮放下 ≈ dp，直接當 dp 用；實際高度每次渲染後實測，
    // 不依賴 textZoom 假設（不強制覆蓋使用者字體縮放，量到多少用多少）。
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
                // 與 update/onRelease 共用的載體：html 去重 + 延遲任務取消 + 釋放旗標。
                // 用 R.id 鍵控存放，不佔單槽位 tag，避免與其他庫衝突。
                setTag(R.id.latex_webview_payload, WebViewPayload(html = ""))
                webViewClient = object : WebViewClient() {
                    fun remeasure(view: WebView) {
                        val payload = view.getTag(R.id.latex_webview_payload) as? WebViewPayload ?: return
                        if (payload.released) return
                        try {
                            view.evaluateJavascript(
                                "(function(){var m=document.getElementById('math');var h=Math.max(document.body.scrollHeight,m?m.scrollHeight:0);return h;})()",
                            ) { value ->
                                if (payload.released) return@evaluateJavascript
                                value?.trim('"')?.toDoubleOrNull()?.toInt()?.let { heightUpdater.value(it) }
                            }
                        } catch (_: IllegalStateException) {
                            // destroy 後的 evaluateJavascript 同步拋錯，直接吞掉（已有原文兜底）。
                        }
                    }

                    override fun onPageFinished(view: WebView, url: String?) {
                        remeasure(view)
                        // KaTeX 外鏈異步：渲染完成前量到的是空 div，延遲複測一次。
                        // Runnable 引用存起來，onRelease 用 view.removeCallbacks 精確取消，
                        // 不碰共享 Handler（removeCallbacksAndMessages(null) 會誤清全窗口訊息）。
                        val payload = view.getTag(R.id.latex_webview_payload) as? WebViewPayload ?: return
                        payload.pending?.let(view::removeCallbacks)
                        val task = Runnable { remeasure(view) }
                        payload.pending = task
                        view.postDelayed(task, 400)
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
            // 換 html 時先取消舊 pending：否則舊任務在新 onPageFinished 前觸發會寫髒高度一次。
            val payload = (view.getTag(R.id.latex_webview_payload) as? WebViewPayload)
                ?: WebViewPayload(html = "").also { view.setTag(R.id.latex_webview_payload, it) }
            if (payload.html != html) {
                payload.pending?.let(view::removeCallbacks)
                payload.pending = null
                payload.html = html
                view.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
            }
        },
        // 延遲 remeasure（400ms）可能在 dispose 後才觸發：精確取消本 view 的 pending 任務
        // 並立 released 旗標（已發出的 evaluate 回調也靠它短路），再 destroy。
        onRelease = { view ->
            (view.getTag(R.id.latex_webview_payload) as? WebViewPayload)?.let {
                it.released = true
                it.pending?.let(view::removeCallbacks)
                it.pending = null
            }
            view.destroy()
        },
        modifier = modifier
            .fillMaxWidth()
            .height(webHeight),
    )
}

/** AndroidView factory/update/onRelease 之間共用的 WebView 狀態（R.id 鍵控存放）。 */
private class WebViewPayload(
    var html: String,
    var released: Boolean = false,
    var pending: Runnable? = null,
)
