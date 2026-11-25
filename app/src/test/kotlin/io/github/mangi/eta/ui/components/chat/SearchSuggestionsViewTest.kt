package io.github.mangi.eta.ui.components.chat

import android.app.Activity
import android.app.Application
import android.graphics.Canvas
import android.net.Uri
import android.os.Looper
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class SearchSuggestionsViewTest {
    @Test
    fun viewDisablesActiveContentStorageAndNetworkAndReleasesIdempotently() {
        val view = SearchSuggestionsWebView(RuntimeEnvironment.getApplication()) {}
        assertFalse(view.settings.javaScriptEnabled)
        assertFalse(view.settings.javaScriptCanOpenWindowsAutomatically)
        assertFalse(view.settings.allowFileAccess)
        assertFalse(view.settings.allowContentAccess)
        assertFalse(view.settings.domStorageEnabled)
        assertFalse(view.settings.databaseEnabled)
        assertTrue(view.settings.blockNetworkLoads)
        assertEquals(WebSettings.LOAD_NO_CACHE, view.settings.cacheMode)
        assertEquals(WebSettings.MIXED_CONTENT_NEVER_ALLOW, view.settings.mixedContentMode)
        view.release()
        view.release()
        assertTrue(shadowOf(view).wasDestroyCalled())
    }

    @Test
    fun emptyContentReportsMinimumHeightOnceWithoutSchedulingFurtherDrawWork() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val heights = mutableListOf<Int>()
        val view = SearchSuggestionsWebView(controller.get(), heights::add)
        val looper = shadowOf(Looper.getMainLooper())
        try {
            controller.get().setContentView(view)
            looper.idle()
            assertTrue(view.isAttachedToWindow)
            assertEquals(0, view.contentHeight)

            drawContent(view)
            assertTrue(heights.isEmpty())
            assertFalse(looper.isIdle)
            looper.idle()
            assertEquals(listOf(1), heights)

            drawContent(view)
            assertTrue(looper.isIdle)
            assertEquals(listOf(1), heights)
        } finally {
            (view.parent as? ViewGroup)?.removeView(view)
            view.release()
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun releaseCancelsPendingHeightReportBeforeDestroyingView() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val heights = mutableListOf<Int>()
        val view = SearchSuggestionsWebView(controller.get(), heights::add)
        val looper = shadowOf(Looper.getMainLooper())
        try {
            controller.get().setContentView(view)
            looper.idle()
            drawContent(view)
            assertFalse(looper.isIdle)

            (view.parent as? ViewGroup)?.removeView(view)
            view.release()
            looper.idle()
            assertTrue(heights.isEmpty())
            assertTrue(shadowOf(view).wasDestroyCalled())
        } finally {
            view.release()
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun dataImagesRemainAvailableButExternalResourcesAreBlocked() {
        val view = SearchSuggestionsWebView(RuntimeEnvironment.getApplication()) {}
        try {
            val client = view.webViewClient
            assertNull(client.shouldInterceptRequest(view, request("data:image/svg+xml;base64,PHN2Zy8+")))
            assertNotNull(client.shouldInterceptRequest(view, request("https://example.invalid/tracker.png")))
            assertNotNull(client.shouldInterceptRequest(view, request("file:///private/file")))
            assertNotNull(client.shouldInterceptRequest(view, request("content://private/item")))
            assertTrue(client.shouldOverrideUrlLoading(view, request("https://example.invalid/frame", mainFrame = false)))
        } finally {
            view.release()
        }
    }

    @Test
    fun onlyAbsoluteHttpLinksCanLeaveTheViewAndOriginalMarkupRemainsIntact() {
        assertTrue(isSearchSuggestionLink(Uri.parse("https://www.google.com/search?q=test")))
        assertTrue(isSearchSuggestionLink(Uri.parse("http://example.invalid/")))
        listOf("javascript:alert(1)", "file:///tmp/local", "content://authority/item", "intent://example", "/relative")
            .forEach { assertFalse(isSearchSuggestionLink(Uri.parse(it))) }
        val original = "<style>.suggestion{padding:8px}</style><a href='https://google.com'>搜索</a>"
        val document = searchSuggestionsDocument(original)
        assertTrue(document.contains(original))
        assertTrue(document.contains("frame-src 'none'"))
        assertTrue(document.contains("form-action 'none'"))
    }

    private fun request(url: String, mainFrame: Boolean = false): WebResourceRequest = object : WebResourceRequest {
        override fun getUrl(): Uri = Uri.parse(url)
        override fun isForMainFrame(): Boolean = mainFrame
        override fun isRedirect(): Boolean = false
        override fun hasGesture(): Boolean = false
        override fun getMethod(): String = "GET"
        override fun getRequestHeaders(): Map<String, String> = emptyMap()
    }

    private fun drawContent(view: SearchSuggestionsWebView) {
        // Robolectric 的 View.draw 不调用子类 onDraw，直接驱动实际绘制回调。
        SearchSuggestionsWebView::class.java.getDeclaredMethod("onDraw", Canvas::class.java)
            .apply { isAccessible = true }.invoke(view, Canvas())
    }
}
