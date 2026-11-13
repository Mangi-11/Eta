package io.github.mangi.eta.agent.browser

import android.annotation.SuppressLint
import android.content.Context
import android.content.MutableContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.graphics.createBitmap
import io.github.mangi.eta.agent.media.AgentImageCodec
import io.github.mangi.eta.agent.tool.requiredText
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

internal data class BrowserSessionSnapshot(
    val available: Boolean = false,
    val url: String = "",
    val displayUrl: String = "",
    val host: String = "",
    val title: String = "",
    val isLoading: Boolean = false,
    val isPageVisible: Boolean = false,
    val hasCommittedPage: Boolean = false,
    val progress: Int = 0,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val error: String? = null,
    val isUserControlling: Boolean = false,
    val lastAgentRunId: String? = null,
    val lastAgentToolCallId: String? = null,
)

internal data class BrowserImage(
    val dataUrl: String,
    val mimeType: String,
    val bytes: Int,
    val width: Int,
    val height: Int,
)

internal data class BrowserToolResult(
    val content: String,
    val images: List<BrowserImage> = emptyList(),
)

/**
 * 同一会话内由 Agent 与用户共享的浏览器页面。
 *
 * WebView 可以离屏工作，也可以临时挂到 App 的浏览器页面供用户接管。
 */
// 共享 WebView 必须跨工具调用存活；Activity 容器只在浏览器页面可见时持有，并在 dispose 时解绑。
@SuppressLint("StaticFieldLeak")
internal class AgentBrowserSession(
    private val onIdle: () -> Unit = {},
    private val isDataClearing: () -> Boolean = { false },
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val operationLock = ReentrantLock()
    private val interrupted = AtomicBoolean(false)
    private val operationEpoch = AtomicLong(0L)
    private val navigationGeneration = AtomicLong(0L)

    private val mutableSnapshots = MutableStateFlow(BrowserSessionSnapshot())
    val snapshots: StateFlow<BrowserSessionSnapshot> = mutableSnapshots.asStateFlow()

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var contextWrapper: MutableContextWrapper? = null

    @Volatile
    private var webView: WebView? = null

    @Volatile
    private var attachedContainer: ViewGroup? = null

    @Volatile
    private var currentLoadWaiter: LoadWaiter? = null

    @Volatile
    private var currentUrl: String = ""

    @Volatile
    private var currentHost: String = ""

    @Volatile
    private var currentTitle: String = ""

    @Volatile
    private var currentError: String? = null

    @Volatile
    private var currentHttpStatus: Int? = null

    @Volatile
    private var currentProgress: Int = 0

    @Volatile
    private var currentLoading: Boolean = false

    @Volatile
    private var currentPageVisible: Boolean = false

    @Volatile
    private var committedMainFrameUrl: String = ""

    @Volatile
    private var userControlActive: Boolean = false

    @Volatile
    private var activeActionIsUserInitiated: Boolean = false

    @Volatile
    private var activeOperationEpoch: Long = 0L

    @Volatile
    private var lastAgentToolCallId: String? = null

    @Volatile
    private var lastAgentRunId: String? = null

    @Volatile
    private var activeAgentRunId: String? = null

    @Volatile
    private var closed = false

    fun initialize(context: Context) {
        if (appContext == null) {
            synchronized(this) {
                if (appContext == null) appContext = context.applicationContext
            }
        }
    }

    fun execute(
        context: Context,
        args: JSONObject,
        runId: String,
        toolCallId: String,
        checkCancellation: () -> Unit = {},
    ): BrowserToolResult {
        val result = executeInternal(
            context = context,
            args = args,
            userInitiated = false,
            agentRunId = runId,
            checkCancellation = checkCancellation,
        )
        val succeeded = runCatching { JSONObject(result.content).optBoolean("ok", false) }
            .getOrDefault(false)
        if (succeeded && toolCallId.isNotBlank()) {
            runCatching {
                callOnMain {
                    lastAgentRunId = runId.takeIf(String::isNotBlank)
                    lastAgentToolCallId = toolCallId
                    publishSnapshotOnMain()
                }
            }
        }
        return result
    }

    fun navigateFromUser(context: Context, url: String): BrowserToolResult {
        val target = url.trim().let { value ->
            if (value.isNotBlank() && "://" !in value) "https://$value" else value
        }
        return executeInternal(
            context = context,
            args = JSONObject().put("action", "navigate").put("url", target),
            userInitiated = true,
        )
    }

    fun goBackFromUser(): BrowserToolResult =
        executeFromExistingContext("go_back", userInitiated = true)

    fun goForwardFromUser(): BrowserToolResult =
        executeFromExistingContext("go_forward", userInitiated = true)

    fun reloadFromUser(): BrowserToolResult =
        executeFromExistingContext("reload", userInitiated = true)

    /** 停止必须能越过串行操作锁，才能立刻唤醒正在等待导航的工具调用。 */
    fun stopFromUser(): BrowserToolResult {
        interruptCurrentAction(force = true)
        return toolResult(baseEnvelope("stop", ok = true, status = "ok"))
    }

    fun resetFromUser(): BrowserToolResult {
        val context = appContext
            ?: return errorResult("reset", "BROWSER_NOT_INITIALIZED", "浏览器尚未初始化")
        return try {
            operationLock.withLock {
                if (closed) return@withLock errorResult("reset", "BROWSER_SESSION_CLOSED", "浏览器会话已关闭")
                interrupted.set(true)
                currentLoadWaiter?.complete(LoadOutcome(false, "CANCELLED", "操作已取消"))
                callOnMain {
                    destroyWebViewOnMain()
                    clearSessionStateOnMain()
                }
                initialize(context)
                toolResult(baseEnvelope("reset", ok = true, status = "ok"))
            }
        } finally {
            onIdle()
        }
    }

    fun interruptAgentAction(runId: String? = null) {
        if (userControlActive) return
        if (!runId.isNullOrBlank() && activeAgentRunId != runId) return
        interruptCurrentAction(force = false)
    }

    private fun interruptCurrentAction(force: Boolean) {
        if (!force && activeActionIsUserInitiated) return
        interrupted.set(true)
        operationEpoch.incrementAndGet()
        activeOperationEpoch = 0L
        navigationGeneration.incrementAndGet()
        currentLoadWaiter?.complete(LoadOutcome(false, "CANCELLED", "操作已取消"))
        mainHandler.post {
            runCatching { webView?.stopLoading() }
            currentLoading = false
            currentPageVisible = committedMainFrameUrl.isNotBlank()
            publishSnapshotOnMain()
        }
    }

    fun attachTo(container: ViewGroup, hostContext: Context) {
        if (closed) return
        initialize(hostContext)
        val wasAlreadyControlling = userControlActive
        userControlActive = true
        if (!wasAlreadyControlling) interruptCurrentAction(force = true)
        runOnMain {
            attachedContainer?.takeIf { it !== container }?.removeAllViews()
            attachedContainer = container
            contextWrapper?.baseContext = hostContext
            webView?.let { attachWebViewOnMain(it, container) }
            publishSnapshotOnMain()
        }
    }

    fun detachFrom(container: ViewGroup) {
        runOnMain {
            if (attachedContainer === container) {
                val wasControlling = userControlActive
                userControlActive = false
                if (wasControlling) interruptCurrentAction(force = true)
                webView?.takeIf { it.parent === container }?.let(container::removeView)
                attachedContainer = null
                appContext?.let { contextWrapper?.baseContext = it }
                publishSnapshotOnMain()
                onIdle()
            }
        }
    }

    /** 主线程只尝试回收空闲页面，不等待正在执行的导航或脚本。 */
    internal fun releaseIdlePage(dispose: Boolean = false): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (userControlActive || !operationLock.tryLock()) return false
        return try {
            if (dispose) closed = true
            destroyWebViewOnMain()
            clearSessionStateOnMain()
            true
        } finally {
            operationLock.unlock()
        }
    }

    /**
     * 聊天页工具卡片的实时预览截图。
     *
     * 只在主线程绘制当前视口，不占用串行操作锁、不中断 Agent 或用户操作；
     * 页面不存在或绘制失败时返回 null，由调用方显示占位。
     */
    fun capturePreview(): BrowserImage? {
        if (Looper.myLooper() == Looper.getMainLooper()) return null
        val view = webView ?: return null
        if (currentUrl.isBlank()) return null
        return runCatching {
            captureViewport(view, preview = true)
        }.getOrNull()
    }

    private fun executeFromExistingContext(action: String, userInitiated: Boolean): BrowserToolResult {
        val context = appContext
            ?: return errorResult(action, "BROWSER_NOT_INITIALIZED", "浏览器尚未初始化")
        return executeInternal(
            context = context,
            args = JSONObject().put("action", action),
            userInitiated = userInitiated,
        )
    }

    private fun executeInternal(
        context: Context,
        args: JSONObject,
        userInitiated: Boolean,
        agentRunId: String? = null,
        checkCancellation: () -> Unit = {},
    ): BrowserToolResult {
        initialize(context)
        val action = args.optString("action").trim().lowercase(Locale.ROOT)
        if (action !in SUPPORTED_ACTIONS) {
            return errorResult(action.ifBlank { "unknown" }, "INVALID_ACTION", "浏览器 action 无效或缺失")
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return errorResult(action, "MAIN_THREAD_CALL", "浏览器操作不能阻塞主线程")
        }
        checkCancellation()
        if (isDataClearing()) return errorResult(action, "BROWSER_DATA_CLEARING", "正在清除浏览器数据，请稍后再试", "blocked")
        // 等待另一动作时也响应本 run 的取消，不能获得锁后清掉已经到达的取消信号。
        while (!operationLock.tryLock(100L, TimeUnit.MILLISECONDS)) checkCancellation()
        return try {
            checkCancellation()
            if (closed) return errorResult(action, "BROWSER_SESSION_CLOSED", "浏览器会话已关闭")
            if (isDataClearing()) return errorResult(action, "BROWSER_DATA_CLEARING", "正在清除浏览器数据，请稍后再试", "blocked")
            if (!userInitiated && userControlActive) {
                return errorResult(
                    action = action,
                    code = "USER_CONTROL_ACTIVE",
                    message = "用户正在接管浏览器，请等待用户离开浏览器页面后再继续",
                    status = "blocked",
                )
            }
            interrupted.set(false)
            val epoch = operationEpoch.incrementAndGet()
            activeOperationEpoch = epoch
            activeActionIsUserInitiated = userInitiated
            activeAgentRunId = agentRunId.takeUnless { userInitiated }
            try {
                checkCancellation()
                callOnMain {
                    currentError = null
                    publishSnapshotOnMain()
                }
                runCatching {
                    when (action) {
                        "navigate" -> navigate(args)
                        "get_readable" -> readPage(args, readable = true)
                        "get_text" -> readPage(args, readable = false)
                        "find_elements" -> findElements(args)
                        "click" -> click(args)
                        "type" -> type(args)
                        "scroll" -> scroll(args)
                        "screenshot" -> screenshot(args)
                        "get_page_info" -> pageInfo()
                        "go_back" -> historyNavigation(action, backwards = true)
                        "go_forward" -> historyNavigation(action, backwards = false)
                        "reload" -> reload()
                        "wait_for_selector" -> waitForSelector(args)
                        else -> throw BrowserFailure("INVALID_ACTION", "浏览器 action 无效")
                    }
                }.getOrElse { throwable -> failureResult(action, throwable) }
            } finally {
                if (activeOperationEpoch == epoch) activeOperationEpoch = 0L
                activeActionIsUserInitiated = false
                if (activeAgentRunId == agentRunId) activeAgentRunId = null
            }
        } finally {
            operationLock.unlock()
            onIdle()
        }
    }

    private fun navigate(args: JSONObject): BrowserToolResult {
        val rawUrl = args.optString("url").trim()
        if (rawUrl.isBlank()) throw BrowserFailure("INVALID_ARGUMENT", "navigate 缺少 url")
        val view = ensureWebView()
        val epoch = activeOperationEpoch
        val waiter = LoadWaiter()
        currentLoadWaiter = waiter
        val timeout = args.optLong("timeout_ms", NAVIGATION_TIMEOUT_MS)
            .coerceIn(500L, NAVIGATION_TIMEOUT_MS)
        val generation = navigationGeneration.incrementAndGet()
        callOnMain {
            requireActiveOperation(epoch)
            if (navigationGeneration.get() != generation) {
                throw BrowserFailure("NAVIGATION_SUPERSEDED", "页面导航已被新的操作替代", "cancelled")
            }
            currentError = null
            currentHttpStatus = null
            currentUrl = rawUrl
            currentHost = hostOf(rawUrl)
            currentLoading = true
            currentPageVisible = false
            currentProgress = 0
            publishSnapshotOnMain()
            view.loadUrl(rawUrl)
        }

        val outcome = try {
            waiter.await(timeout) ?: run {
                navigationGeneration.incrementAndGet()
                callOnMain {
                    view.stopLoading()
                    currentLoading = false
                    currentPageVisible = committedMainFrameUrl.isNotBlank()
                    currentError = "页面加载超时"
                    publishSnapshotOnMain()
                }
                throw BrowserFailure("NAVIGATION_TIMEOUT", "页面加载超时", status = "timeout")
            }
        } finally {
            if (currentLoadWaiter === waiter) currentLoadWaiter = null
        }
        if (!outcome.ok) throw BrowserFailure(outcome.code, outcome.message)
        throwIfInterrupted()

        currentHttpStatus?.takeIf { it >= 400 }?.let { code ->
            throw BrowserFailure("HTTP_$code", "网页返回 HTTP $code")
        }
        return toolResult(
            baseEnvelope("navigate", ok = true, status = "ok")
                .put("redirected", rawUrl != currentUrl)
        )
    }

    private fun readPage(args: JSONObject, readable: Boolean): BrowserToolResult {
        val view = requirePage()
        val offset = args.optInt("offset", 0).coerceIn(0, 200_000)
        val maxChars = args.optInt("max_chars", DEFAULT_TEXT_CHARS)
            .coerceIn(256, MAX_TEXT_CHARS)
        val selector = if (readable) null else validatedSelector(args, required = false)
        val value = evaluateObject(
            view,
            if (readable) {
                BrowserDomScripts.readable(offset, maxChars)
            } else {
                BrowserDomScripts.text(selector, offset, maxChars)
            }
        )
        val action = if (readable) "get_readable" else "get_text"
        return toolResult(
            mergeValue(baseEnvelope(action, true, "ok"), value)
                .put("content_format", if (readable) "markdown" else "text")
        )
    }

    private fun findElements(args: JSONObject): BrowserToolResult {
        val view = requirePage()
        val selector = validatedSelector(args, required = false)
        val value = evaluateObject(view, BrowserDomScripts.findElements(selector))
        return toolResult(
            mergeValue(baseEnvelope("find_elements", true, "ok"), value)
        )
    }

    private fun click(args: JSONObject): BrowserToolResult {
        val target = targetFrom(args)
        val view = requirePage()
        val value = evaluateObject(view, BrowserDomScripts.click(target.selector, target.x, target.y))
        waitForPostAction()
        return toolResult(
            mergeValue(baseEnvelope("click", true, "ok"), value)
                .put("side_effect", "possible")
        )
    }

    private fun type(args: JSONObject): BrowserToolResult {
        val inputText = args.requiredText("text")
        val submit = args.optBoolean("submit", false)
        val target = targetFrom(args)
        val view = requirePage()
        val value = evaluateObject(
            view,
            BrowserDomScripts.type(
                selector = target.selector,
                x = target.x,
                y = target.y,
                text = inputText,
                submit = submit,
            )
        )
        waitForPostAction()
        return toolResult(
            mergeValue(baseEnvelope("type", true, "ok"), value)
                .put("side_effect", if (submit) "possible" else "local_input")
        )
    }

    private fun scroll(args: JSONObject): BrowserToolResult {
        val view = requirePage()
        val direction = args.optString("direction", "down").lowercase(Locale.ROOT)
            .takeIf { it == "up" || it == "down" }
            ?: throw BrowserFailure("INVALID_ARGUMENT", "direction 仅支持 up 或 down")
        val amount = args.optInt("amount", 600).coerceIn(1, 5_000)
        val selector = validatedSelector(args, required = false)
        val value = evaluateObject(view, BrowserDomScripts.scroll(selector, direction, amount))
        Thread.sleep(200)
        return toolResult(mergeValue(baseEnvelope("scroll", true, "ok"), value))
    }

    private fun screenshot(args: JSONObject): BrowserToolResult {
        val view = requirePage()
        val captured = captureViewport(view)
        val includeImage = args.optBoolean("read_image", true)
        val envelope = baseEnvelope("screenshot", true, "ok")
            .put("image_width", captured.width)
            .put("image_height", captured.height)
            .put("image_bytes", captured.bytes)
        val image = captured.takeIf { includeImage }
        return toolResult(envelope, listOfNotNull(image))
    }

    private fun pageInfo(): BrowserToolResult {
        val view = requirePage()
        val value = evaluateObject(view, BrowserDomScripts.pageInfo())
        return toolResult(mergeValue(baseEnvelope("get_page_info", true, "ok"), value))
    }

    private fun historyNavigation(action: String, backwards: Boolean): BrowserToolResult {
        val view = requirePage()
        val hasTarget = callOnMain {
            val history = view.copyBackForwardList()
            val targetIndex = history.currentIndex + if (backwards) -1 else 1
            targetIndex in 0 until history.size
        }
        if (!hasTarget) throw BrowserFailure("HISTORY_UNAVAILABLE", "当前没有可用的浏览记录")
        val epoch = activeOperationEpoch
        val generation = navigationGeneration.incrementAndGet()
        callOnMain {
            requireActiveOperation(epoch)
            if (navigationGeneration.get() != generation) return@callOnMain
            currentLoading = true
            currentPageVisible = false
            currentProgress = 0
            publishSnapshotOnMain()
            if (backwards) view.goBack() else view.goForward()
        }
        waitForPostAction()
        return toolResult(baseEnvelope(action, true, "ok"))
    }

    private fun reload(): BrowserToolResult {
        val view = requirePage()
        val epoch = activeOperationEpoch
        val generation = navigationGeneration.incrementAndGet()
        callOnMain {
            requireActiveOperation(epoch)
            if (navigationGeneration.get() != generation) return@callOnMain
            currentLoading = true
            currentPageVisible = false
            currentProgress = 0
            publishSnapshotOnMain()
            view.reload()
        }
        waitForPostAction()
        return toolResult(baseEnvelope("reload", true, "ok"))
    }

    private fun waitForSelector(args: JSONObject): BrowserToolResult {
        val view = requirePage()
        val selector = validatedSelector(args, required = true)!!
        val timeout = args.optLong("timeout_ms", 5_000L).coerceIn(500L, 30_000L)
        val deadline = System.currentTimeMillis() + timeout
        var state = JSONObject().put("found", false).put("visible", false)
        while (System.currentTimeMillis() < deadline) {
            throwIfInterrupted()
            state = evaluateObject(view, BrowserDomScripts.selectorState(selector))
            if (state.optBoolean("found")) {
                return toolResult(
                    mergeValue(baseEnvelope("wait_for_selector", true, "ok"), state)
                        .put("selector", selector.take(240))
                )
            }
            Thread.sleep(250L)
        }
        return toolResult(
            mergeValue(baseEnvelope("wait_for_selector", false, "not_found"), state)
                .put("code", "ELEMENT_NOT_FOUND")
                .put("message", "等待的网页元素未出现")
        )
    }

    private fun targetFrom(args: JSONObject): BrowserTarget {
        val selector = validatedSelector(args, required = false)
        val hasX = args.has("coordinate_x") && !args.isNull("coordinate_x")
        val hasY = args.has("coordinate_y") && !args.isNull("coordinate_y")
        if (hasX != hasY) {
            throw BrowserFailure("INVALID_ARGUMENT", "coordinate_x 与 coordinate_y 必须同时提供")
        }
        if (selector == null && !hasX) {
            throw BrowserFailure("INVALID_ARGUMENT", "需要 selector 或 coordinate_x/coordinate_y")
        }
        val x = if (hasX) coordinateFrom(args, "coordinate_x") else null
        val y = if (hasY) coordinateFrom(args, "coordinate_y") else null
        return BrowserTarget(
            selector = selector,
            x = x,
            y = y,
        )
    }

    private fun coordinateFrom(args: JSONObject, name: String): Int {
        val value = (args.opt(name) as? Number)?.toDouble()
        if (value == null || value != value.toInt().toDouble()) {
            throw JSONException("$name 必须是整数坐标")
        }
        return value.toInt()
    }

    private fun validatedSelector(args: JSONObject, required: Boolean): String? {
        val selector = args.optString("selector").trim()
        if (selector.isBlank()) {
            if (required) throw BrowserFailure("INVALID_ARGUMENT", "缺少 CSS selector")
            return null
        }
        return selector
    }

    private fun requirePage(): WebView {
        val view = ensureWebView()
        if (!snapshots.value.available || currentUrl.isBlank()) {
            throw BrowserFailure("NO_PAGE", "当前没有网页，请先调用 navigate")
        }
        throwIfInterrupted()
        return view
    }

    private fun waitForPostAction() {
        Thread.sleep(250L)
        val deadline = System.currentTimeMillis() + POST_ACTION_TIMEOUT_MS
        while (snapshots.value.isLoading && System.currentTimeMillis() < deadline) {
            throwIfInterrupted()
            Thread.sleep(100L)
        }
        if (snapshots.value.isLoading) {
            navigationGeneration.incrementAndGet()
            mainHandler.post { runCatching { webView?.stopLoading() } }
            throw BrowserFailure("ACTION_TIMEOUT", "网页操作后的页面加载超时", "timeout")
        }
    }

    private fun throwIfInterrupted() {
        if (isDataClearing() || interrupted.get() || activeOperationEpoch == 0L) {
            throw BrowserFailure("CANCELLED", "操作已取消", "cancelled")
        }
    }

    private fun requireActiveOperation(epoch: Long) {
        if (isDataClearing() || epoch == 0L || activeOperationEpoch != epoch || interrupted.get()) {
            throw BrowserFailure("CANCELLED", "操作已取消", "cancelled")
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun ensureWebView(): WebView {
        webView?.let { return it }
        return callOnMain {
            webView ?: run {
                val base = appContext ?: error("browser context unavailable")
                val wrapper = MutableContextWrapper(attachedContainer?.context ?: base)
                val view = WebView(wrapper).apply {
                    setBackgroundColor(Color.WHITE)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = true
                    settings.allowContentAccess = true
                    settings.javaScriptCanOpenWindowsAutomatically = true
                    settings.mediaPlaybackRequiresUserGesture = false
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    settings.safeBrowsingEnabled = false
                    settings.setSupportZoom(true)
                    settings.builtInZoomControls = true
                    settings.displayZoomControls = false
                    webViewClient = BrowserClient()
                    webChromeClient = BrowserChrome()
                }
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)
                contextWrapper = wrapper
                webView = view
                layoutOffscreenOnMain(view)
                attachedContainer?.let { attachWebViewOnMain(view, it) }
                publishSnapshotOnMain()
                view
            }
        }
    }

    private fun attachWebViewOnMain(view: WebView, container: ViewGroup) {
        // 前台跟随窗口的硬件加速，不能继承离屏绘制遗留的软件图层。
        view.setLayerType(View.LAYER_TYPE_NONE, null)
        (view.parent as? ViewGroup)?.takeIf { it !== container }?.removeView(view)
        if (view.parent == null) {
            container.removeAllViews()
            container.addView(
                view,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
            )
        }
    }

    private fun layoutOffscreenOnMain(view: WebView) {
        if (view.width > 0 && view.height > 0) return
        val metrics = (appContext ?: return).resources.displayMetrics
        val width = metrics.widthPixels.coerceIn(720, OFFSCREEN_VIEWPORT_MAX_WIDTH)
        val height = metrics.heightPixels.coerceIn(1_280, OFFSCREEN_VIEWPORT_MAX_HEIGHT)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, width, height)
    }

    private fun destroyWebViewOnMain() {
        val view = webView ?: return
        (view.parent as? ViewGroup)?.removeView(view)
        runCatching { view.stopLoading() }
        runCatching { view.clearHistory() }
        runCatching { view.clearFormData() }
        runCatching { view.destroy() }
        webView = null
        contextWrapper = null
    }

    private fun clearSessionStateOnMain() {
        currentUrl = ""
        currentHost = ""
        currentTitle = ""
        currentError = null
        currentHttpStatus = null
        currentProgress = 0
        currentLoading = false
        currentPageVisible = false
        committedMainFrameUrl = ""
        lastAgentRunId = null
        lastAgentToolCallId = null
        navigationGeneration.incrementAndGet()
        publishSnapshotOnMain()
    }

    private fun evaluateObject(view: WebView, body: String): JSONObject {
        throwIfInterrupted()
        val epoch = activeOperationEpoch
        val future = CompletableFuture<String>()
        mainHandler.post {
            if (isDataClearing() || webView !== view || interrupted.get() || activeOperationEpoch != epoch || epoch == 0L) {
                future.completeExceptionally(BrowserFailure("CANCELLED", "操作已取消", "cancelled"))
            } else {
                runCatching {
                    view.evaluateJavascript(BrowserDomScripts.wrap(body)) { raw ->
                        if (!isDataClearing() && !interrupted.get() && activeOperationEpoch == epoch) {
                            future.complete(raw ?: "null")
                        } else {
                            future.completeExceptionally(BrowserFailure("CANCELLED", "操作已取消", "cancelled"))
                        }
                    }
                }.onFailure(future::completeExceptionally)
            }
        }
        val raw = try {
            future.get(JAVASCRIPT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            future.cancel(true)
            if (activeOperationEpoch == epoch) {
                interrupted.set(true)
                operationEpoch.incrementAndGet()
                activeOperationEpoch = 0L
            }
            mainHandler.post { runCatching { view.stopLoading() } }
            throw BrowserFailure("SCRIPT_TIMEOUT", "网页响应超时", "timeout")
        } catch (error: ExecutionException) {
            throw error.cause ?: error
        }
        throwIfInterrupted()
        return decodeEvaluation(raw)
    }

    private fun decodeEvaluation(raw: String): JSONObject {
        val outer = runCatching { JSONTokener(raw).nextValue() }.getOrNull()
        val decoded = when (outer) {
            is String -> outer
            null, JSONObject.NULL -> throw BrowserFailure("SCRIPT_FAILED", "网页没有返回可读结果")
            else -> outer.toString()
        }
        val envelope = runCatching { JSONObject(decoded) }
            .getOrElse { throw BrowserFailure("SCRIPT_FAILED", "网页结果格式无效") }
        if (!envelope.optBoolean("ok", false)) {
            val code = envelope.optString("error")
            val message = when {
                code.contains("TARGET_NOT_FOUND") ||
                    code.contains("TARGET_NOT_VISIBLE") ||
                    code.contains("TARGET_OCCLUDED") -> "目标网页元素不可见或被其他内容遮挡"
                code.contains("TARGET_DISABLED") -> "目标网页元素当前不可操作"
                code.contains("TARGET_NOT_EDITABLE") -> "目标网页元素不可输入"
                code.contains("not a valid selector", ignoreCase = true) -> "CSS selector 无效"
                else -> "网页元素操作失败"
            }
            throw BrowserFailure("SCRIPT_FAILED", message)
        }
        val value = envelope.opt("value")
        if (value == null || value === JSONObject.NULL) return JSONObject()
        if (value is JSONObject) return value
        if (value is JSONArray) return JSONObject().put("items", value)
        return JSONObject().put("value", value)
    }

    // callOnMain 将 WebView 绘制切到主线程，随后的位图编码留在浏览器执行线程；Lint ThreadConstraint 无法识别跨线程同步桥接。
    @SuppressLint("ThreadConstraint")
    private fun captureViewport(view: WebView, preview: Boolean = false): BrowserImage {
        val bitmap = captureViewportBitmap(view, preview)
        return encodeViewportBitmap(bitmap, preview)
    }

    private fun captureViewportBitmap(view: WebView, preview: Boolean): Bitmap = callOnMain {
        layoutOffscreenOnMain(view)
        val sourceWidth = view.width.coerceAtLeast(1)
        val sourceHeight = view.height.coerceAtLeast(1)
        val scale = if (preview) minOf(
            1f,
            PREVIEW_MAX_WIDTH.toFloat() / sourceWidth,
            PREVIEW_MAX_HEIGHT.toFloat() / sourceHeight,
        ) else 1f
        val width = (sourceWidth * scale).roundToInt().coerceAtLeast(1)
        val height = (sourceHeight * scale).roundToInt().coerceAtLeast(1)
        // Bitmap Canvas 已决定截图采用软件绘制，不能修改共享 WebView 的持久图层类型。
        val captured = createBitmap(width, height)
        try {
            Canvas(captured).also { canvas ->
                if (preview) {
                    canvas.drawColor(Color.WHITE)
                    canvas.scale(scale, scale)
                }
                view.draw(canvas)
            }
            captured
        } catch (failure: Throwable) {
            captured.recycle()
            throw failure
        }
    }

    private fun encodeViewportBitmap(bitmap: Bitmap, preview: Boolean): BrowserImage = try {
        if (preview) {
            val bytes = ByteArrayOutputStream().use { stream ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, PREVIEW_QUALITY, stream))
                stream.toByteArray()
            }
            BrowserImage(
                dataUrl = "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP),
                mimeType = "image/jpeg", bytes = bytes.size,
                width = bitmap.width, height = bitmap.height,
            )
        } else {
            val image = AgentImageCodec.fromScreenBitmap(bitmap, source = "agent_browser")
            BrowserImage(
                dataUrl = image.reference, mimeType = image.mimeType, bytes = image.bytes,
                width = bitmap.width, height = bitmap.height,
            )
        }
    } finally { bitmap.recycle() }

    private fun baseEnvelope(action: String, ok: Boolean, status: String): JSONObject {
        val snapshot = snapshots.value
        return JSONObject()
            .put("ok", ok)
            .put("tool", TOOL_NAME)
            .put("action", action)
            .put("status", status)
            .put("url", currentUrl)
            .put("display_url", currentUrl)
            .put("host", snapshot.host)
            .put("title", snapshot.title)
            .put("is_loading", snapshot.isLoading)
            .put("can_go_back", snapshot.canGoBack)
            .put("can_go_forward", snapshot.canGoForward)
            .also { json ->
                currentHttpStatus?.let { json.put("http_status", it) }
            }
    }

    private fun mergeValue(target: JSONObject, value: JSONObject): JSONObject = target.apply {
        value.keys().forEach { key -> put(key, value.opt(key)) }
    }

    private fun toolResult(
        envelope: JSONObject,
        images: List<BrowserImage> = emptyList(),
    ): BrowserToolResult = BrowserToolResult(
        content = BrowserPayloadLimiter.serialize(envelope),
        images = images,
    )

    private fun failureResult(action: String, throwable: Throwable): BrowserToolResult {
        val failure = throwable as? BrowserFailure
        val message = failure?.message ?: if (throwable is JSONException) throwable.message.orEmpty() else "浏览器操作失败"
        if (failure?.code !in setOf("CANCELLED", "USER_CONTROL_ACTIVE")) {
            runCatching {
                callOnMain {
                    currentLoading = false
                    currentPageVisible = committedMainFrameUrl.isNotBlank()
                    currentError = message
                    publishSnapshotOnMain()
                }
            }
        }
        return errorResult(
            action = action,
            code = failure?.code ?: if (throwable is JSONException) "INVALID_ARGUMENT" else "BROWSER_ERROR",
            message = message,
            status = failure?.status ?: "error",
        )
    }

    private fun errorResult(
        action: String,
        code: String,
        message: String,
        status: String = "error",
    ): BrowserToolResult = toolResult(
        baseEnvelope(action, ok = false, status = status)
            .put("code", code)
            .put("message", message)
    )

    private fun publishSnapshotOnMain() {
        val view = webView
        val pageAvailable = view != null && currentUrl.isNotBlank()
        mutableSnapshots.value = BrowserSessionSnapshot(
            available = pageAvailable,
            url = if (pageAvailable) currentUrl else "",
            displayUrl = if (pageAvailable) currentUrl else "",
            host = if (pageAvailable) currentHost else "",
            title = safeTitle(currentTitle),
            isLoading = currentLoading,
            isPageVisible = currentPageVisible,
            hasCommittedPage = committedMainFrameUrl.isNotBlank(),
            progress = currentProgress.coerceIn(0, 100),
            canGoBack = runCatching { view?.canGoBack() == true }.getOrDefault(false),
            canGoForward = runCatching { view?.canGoForward() == true }.getOrDefault(false),
            error = currentError,
            isUserControlling = userControlActive,
            lastAgentRunId = lastAgentRunId,
            lastAgentToolCallId = lastAgentToolCallId,
        )
    }

    private fun safeTitle(value: String): String =
        value.filterNot(Char::isISOControl)
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(160)

    private fun hostOf(url: String): String =
        runCatching { Uri.parse(url).host.orEmpty() }.getOrDefault("")

    private fun setNavigationErrorOnMain(code: String, message: String) {
        navigationGeneration.incrementAndGet()
        currentLoading = false
        currentPageVisible = committedMainFrameUrl.isNotBlank()
        currentError = message
        currentLoadWaiter?.complete(LoadOutcome(false, code, message))
        publishSnapshotOnMain()
    }

    private fun <T> callOnMain(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val future = CompletableFuture<T>()
        mainHandler.post {
            runCatching(block)
                .onSuccess(future::complete)
                .onFailure(future::completeExceptionally)
        }
        return try {
            future.get(JAVASCRIPT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            future.cancel(true)
            throw BrowserFailure("MAIN_THREAD_TIMEOUT", "浏览器主线程响应超时", "timeout")
        } catch (error: ExecutionException) {
            throw error.cause ?: error
        }
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    private inner class BrowserClient : WebViewClient() {
        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            if (webView !== view) return
            currentUrl = url.orEmpty()
            currentHost = hostOf(currentUrl)
            currentTitle = view.title.orEmpty()
            currentError = null
            currentHttpStatus = null
            currentLoading = true
            currentPageVisible = false
            currentProgress = 0
            publishSnapshotOnMain()
        }

        override fun onPageFinished(view: WebView, url: String?) {
            if (webView !== view) return
            currentUrl = url.orEmpty()
            currentHost = hostOf(currentUrl)
            committedMainFrameUrl = currentUrl
            currentPageVisible = true
            currentTitle = view.title.orEmpty()
            currentLoading = false
            currentProgress = 100
            publishSnapshotOnMain()
            currentLoadWaiter?.complete(LoadOutcome(true, "OK", ""))
        }

        override fun onPageCommitVisible(view: WebView, url: String?) {
            if (webView !== view) return
            committedMainFrameUrl = url.orEmpty()
            currentPageVisible = true
            publishSnapshotOnMain()
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
            if (webView !== view) return
            currentUrl = url.orEmpty()
            currentHost = hostOf(currentUrl)
            currentTitle = view.title.orEmpty()
            publishSnapshotOnMain()
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (webView !== view || !request.isForMainFrame) return
            setNavigationErrorOnMain("NETWORK_ERROR", "页面加载失败，请检查网络连接")
        }

        override fun onReceivedHttpError(
            view: WebView,
            request: WebResourceRequest,
            errorResponse: WebResourceResponse,
        ) {
            if (webView !== view || !request.isForMainFrame) return
            currentHttpStatus = errorResponse.statusCode
            if (errorResponse.statusCode >= 400) {
                currentError = "网页返回 HTTP ${errorResponse.statusCode}"
            }
            publishSnapshotOnMain()
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            if (webView !== view) return true
            mainHandler.post {
                if (webView === view) {
                    destroyWebViewOnMain()
                    currentError = "网页渲染进程已退出，请重新打开页面"
                    currentLoading = false
                    currentPageVisible = false
                    committedMainFrameUrl = ""
                    publishSnapshotOnMain()
                }
            }
            currentLoadWaiter?.complete(LoadOutcome(false, "RENDERER_GONE", "网页渲染进程已退出"))
            return true
        }
    }

    private inner class BrowserChrome : WebChromeClient() {
        override fun onReceivedTitle(view: WebView, title: String?) {
            if (webView !== view) return
            currentTitle = title.orEmpty()
            publishSnapshotOnMain()
        }

        override fun onProgressChanged(view: WebView, newProgress: Int) {
            if (webView !== view) return
            currentProgress = newProgress.coerceIn(0, 100)
            // 进度回调可能在主页面结束后继续到达，不能反复切换导航的加载状态。
            publishSnapshotOnMain()
        }
    }

    private data class BrowserTarget(
        val selector: String?,
        val x: Int?,
        val y: Int?,
    )

    private data class LoadOutcome(
        val ok: Boolean,
        val code: String,
        val message: String,
    )

    private class LoadWaiter {
        private val latch = CountDownLatch(1)

        @Volatile
        private var outcome: LoadOutcome? = null

        fun complete(value: LoadOutcome) {
            if (outcome != null) return
            synchronized(this) {
                if (outcome == null) {
                    outcome = value
                    latch.countDown()
                }
            }
        }

        fun await(timeoutMs: Long): LoadOutcome? =
            if (latch.await(timeoutMs, TimeUnit.MILLISECONDS)) outcome else null
    }

    private class BrowserFailure(
        val code: String,
        override val message: String,
        val status: String = "error",
    ) : RuntimeException(message)

    private companion object {
        private const val TOOL_NAME = "browser_use"
        private const val DEFAULT_TEXT_CHARS = 8_000
        private const val MAX_TEXT_CHARS = 12_000
        private const val NAVIGATION_TIMEOUT_MS = 25_000L
        private const val JAVASCRIPT_TIMEOUT_MS = 8_000L
        private const val POST_ACTION_TIMEOUT_MS = 10_000L
        private const val OFFSCREEN_VIEWPORT_MAX_WIDTH = 1_280
        private const val OFFSCREEN_VIEWPORT_MAX_HEIGHT = 2_400
        private const val PREVIEW_MAX_WIDTH = 480
        private const val PREVIEW_MAX_HEIGHT = 900
        private const val PREVIEW_QUALITY = 60
        private val SUPPORTED_ACTIONS = setOf(
            "navigate",
            "get_readable",
            "get_text",
            "find_elements",
            "click",
            "type",
            "scroll",
            "screenshot",
            "get_page_info",
            "go_back",
            "go_forward",
            "reload",
            "wait_for_selector",
        )
    }

}
