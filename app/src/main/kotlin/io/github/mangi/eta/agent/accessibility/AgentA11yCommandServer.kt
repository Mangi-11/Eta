package io.github.mangi.eta.agent.accessibility

import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONArray
import org.json.JSONObject

/**
 * 影子屏无障碍注入服务端：把 [AgentAccessibilityService] 的语义化 UI 操作暴露给本机脚本。
 *
 * 设计目标（安全优先）：
 *  - 只监听 127.0.0.1，不暴露给局域网；
 *  - 随机 token 鉴权（写入 files/a11y/token，0600），命令白名单，杜绝任意代码执行；
 *  - 语义化注入（performAction / SET_TEXT）优先，不产生合成触摸事件，
 *    从源头消除"抢焦点 / 手势条串扰 / 中文输入"问题；
 *  - 每条命令写审计日志。
 *
 * 协议：一行 JSON 请求 → 一行 JSON 响应 → 关闭连接。
 * 请求：{"token":"...","cmd":"dump|click|text|...","display":<可选 displayId>, ...}
 * 响应：{"ok":true,"data":...} 或 {"ok":false,"error":{"code":"...","message":"..."}}
 */
internal class AgentA11yCommandServer(
    private val service: AgentAccessibilityService,
    private val port: Int = DEFAULT_PORT,
) {
    private data class NodeEntry(
        val node: AccessibilityNodeInfo,
        val displayId: Int,
        val windowId: Int,
        val bounds: android.graphics.Rect,
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val nodeTable = LinkedHashMap<String, NodeEntry>()
    private val nodeSeq = AtomicLong(0)
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null

    @Volatile
    private var running = false

    val token: String = java.util.UUID.randomUUID().toString().replace("-", "")

    fun start() {
        if (running) return
        runCatching {
            serverSocket = ServerSocket(port, 8, InetAddress.getByName("127.0.0.1"))
        }.onFailure {
            Log.w(TAG, "注入服务端监听失败: ${it.message}")
            return
        }
        writeTokenFile()
        ensureWindowFlags()
        running = true
        acceptThread = Thread(::acceptLoop, "agent-a11y-inject").apply {
            isDaemon = true
            start()
        }
        Log.i(TAG, "注入服务端已启动 127.0.0.1:$port")
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        synchronized(nodeTable) { nodeTable.clear() }
    }

    private fun acceptLoop() {
        while (running) {
            val socket = runCatching { serverSocket?.accept() }.getOrNull() ?: break
            Thread({ handle(socket) }, "agent-a11y-inject-conn").apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun handle(socket: Socket) {
        socket.use { s ->
            runCatching {
                val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
                val writer = BufferedWriter(OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8))
                val line = reader.readLine() ?: return
                val response = runCatching { dispatch(line) }
                    .getOrElse { errorJson("internal_error", it.message ?: "未知错误") }
                writer.write(response.toString())
                writer.write("\n")
                writer.flush()
            }
        }
    }

    private fun dispatch(line: String): JSONObject {
        val request = JSONObject(line)
        if (request.optString("token") != token) {
            audit("reject", "token 不匹配")
            return errorJson("unauthorized", "token 不匹配")
        }
        val cmd = request.optString("cmd")
        audit(cmd, request.toString())
        return when (cmd) {
            "ping" -> ok(JSONObject().put("pong", true))
            "status" -> ok(service.statusJson().put("displayCount", service.windowsOrNull()?.size ?: 0))
            "dump" -> ok(dump(request.optInt("display", -1)))
            "click" -> actOnNode(request) { node -> node.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
            "long_click" -> actOnNode(request) { node -> node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK) }
            "focus" -> actOnNode(request) { node -> node.performAction(AccessibilityNodeInfo.ACTION_FOCUS) }
            "clear" -> actOnNode(request) {
                node.performAction(AccessibilityNodeInfo.ACTION_FOCUS) &&
                    node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, android.os.Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
                    })
            }
            "text" -> actOnNode(request) { node ->
                node.performAction(AccessibilityNodeInfo.ACTION_FOCUS) &&
                    node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, android.os.Bundle().apply {
                        putCharSequence(
                            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                            request.optString("value"),
                        )
                    })
            }
            "scroll" -> actOnNode(request) { node ->
                when (request.optString("dir")) {
                    "down", "forward" -> node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                    "up", "backward" -> node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                    "left" -> node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.id)
                    "right" -> node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id)
                    else -> false
                }
            }
            "back", "home", "recents", "notifications", "quick_settings" -> {
                if (service.globalAction(cmd)) ok(JSONObject()) else errorJson("action_failed", "全局动作 $cmd 失败")
            }
            "tap" -> gesture(
                display = request.optInt("display", -1),
                x1 = request.optDouble("x").toFloat(), y1 = request.optDouble("y").toFloat(),
                x2 = request.optDouble("x").toFloat(), y2 = request.optDouble("y").toFloat(),
                ms = 50L,
            )
            "gesture" -> gesture(
                display = request.optInt("display", -1),
                x1 = request.optDouble("x1").toFloat(), y1 = request.optDouble("y1").toFloat(),
                x2 = request.optDouble("x2").toFloat(), y2 = request.optDouble("y2").toFloat(),
                ms = request.optLong("ms", 300L),
            )
            else -> errorJson("unknown_cmd", "命令不在白名单: $cmd")
        }
    }

    /** 节点树（可按 displayId 过滤），并刷新节点表供后续语义动作使用。 */
    private fun dump(displayId: Int): JSONObject {
        val windows = service.windowsOrNull().orEmpty()
            .filter { displayId < 0 || it.displayId == displayId }
        val out = JSONArray()
        synchronized(nodeTable) { nodeTable.clear() }
        for (window in windows) {
            val root = runCatching { window.root }.getOrNull() ?: continue
            val windowJson = JSONObject()
                .put("display", window.displayId)
                .put("windowId", window.id)
                .put("focused", window.isFocused)
            val nodes = JSONArray()
            visit(root, window.displayId, window.id, nodes, depth = 0)
            windowJson.put("nodes", nodes)
            out.put(windowJson)
        }
        return JSONObject()
            .put("displays", out.map { (it as JSONObject).optInt("display") }.distinct().sorted())
            .put("windows", out)
    }

    private fun visit(
        node: AccessibilityNodeInfo,
        displayId: Int,
        windowId: Int,
        out: JSONArray,
        depth: Int,
    ) {
        if (depth > MAX_DEPTH || out.length() >= MAX_NODES) return
        val bounds = android.graphics.Rect().also(node::getBoundsInScreen)
        val id = "n${nodeSeq.incrementAndGet()}"
        synchronized(nodeTable) {
            nodeTable[id] = NodeEntry(node, displayId, windowId, bounds)
        }
        out.put(
            JSONObject()
                .put("id", id)
                .put("text", node.text?.toString().orEmpty())
                .put("desc", node.contentDescription?.toString().orEmpty())
                .put("class", node.className?.toString().orEmpty())
                .put("pkg", node.packageName?.toString().orEmpty())
                .put("bounds", "${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}")
                .put("clickable", node.isClickable)
                .put("editable", node.isEditable)
                .put("scrollable", node.isScrollable)
                .put("focused", node.isFocused),
        )
        for (i in 0 until node.childCount) {
            runCatching { node.getChild(i) }.getOrNull()?.let { child ->
                visit(child, displayId, windowId, out, depth + 1)
                child.recycle()
            }
        }
    }

    private fun actOnNode(request: JSONObject, action: (AccessibilityNodeInfo) -> Boolean): JSONObject {
        val id = request.optString("id")
        val entry = synchronized(nodeTable) { nodeTable[id] }
            ?: return errorJson("stale_node", "节点 $id 不在当前表中，请先 dump")
        val ok = runOnMain {
            if (!entry.node.refresh()) return@runOnMain false
            val fresh = android.graphics.Rect().also(entry.node::getBoundsInScreen)
            if (fresh != entry.bounds && request.optBoolean("verifyBounds", true)) {
                return@runOnMain false
            }
            action(entry.node)
        }
        return if (ok) ok(JSONObject().put("id", id))
        else errorJson("action_failed", "节点动作未生效（可能界面已刷新），请重新 dump")
    }

    private fun gesture(display: Int, x1: Float, y1: Float, x2: Float, y2: Float, ms: Long): JSONObject {
        val result = if (x1 == x2 && y1 == y2) {
            service.gestureTap(x1, y1, ms.coerceAtLeast(50L))
        } else {
            service.gestureSwipe(x1, y1, x2, y2, ms)
        }
        return if (result.ok) ok(JSONObject().put("gesture", true))
        else errorJson("action_failed", result.message.ifBlank { "手势失败（该 Android 版本可能不支持跨屏手势，改用节点动作）" })
    }

    private fun runOnMain(block: () -> Boolean): Boolean {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val result = java.util.concurrent.atomic.AtomicBoolean(false)
        val latch = java.util.concurrent.CountDownLatch(1)
        mainHandler.post {
            runCatching { result.set(block()) }
            latch.countDown()
        }
        latch.await(3, java.util.concurrent.TimeUnit.SECONDS)
        return result.get()
    }

    private fun ensureWindowFlags() {
        runCatching {
            val info = service.serviceInfo ?: return
            val flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            if (info.flags != flags) {
                info.flags = flags
                service.serviceInfo = info
            }
        }
    }

    private fun windowsOrNull() = runCatching { service.windows }.getOrNull()

    private fun writeTokenFile() {
        runCatching {
            val dir = File(service.filesDir, "a11y").apply { mkdirs() }
            val file = File(dir, "token")
            file.writeText(token)
            runCatching {
                Runtime.getRuntime().exec(arrayOf("chmod", "600", file.absolutePath)).waitFor()
            }
        }
    }

    private fun audit(cmd: String, detail: String) {
        Log.i(TAG, "cmd=$cmd ${detail.take(200)}")
    }

    private fun ok(data: JSONObject) = JSONObject().put("ok", true).put("data", data)

    private fun errorJson(code: String, message: String) =
        JSONObject().put("ok", false).put("error", JSONObject().put("code", code).put("message", message))

    private companion object {
        const val TAG = "AgentA11yInject"
        const val DEFAULT_PORT = 38391
        const val MAX_DEPTH = 24
        const val MAX_NODES = 1200
    }
}

private fun AgentAccessibilityService.windowsOrNull(): List<android.view.accessibility.AccessibilityWindowInfo>? =
    runCatching { windows }.getOrNull()
