package io.github.mangi.eta.ui.state

import android.content.Context
import androidx.compose.runtime.Immutable
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.terminal.ConsoleSessionController
import io.github.mangi.eta.agent.terminal.linux.LinuxEnvironmentPaths
import io.github.mangi.eta.agent.terminal.linux.SharedFolderMounts
import io.github.mangi.eta.agent.terminal.TerminalEnvironment
import io.github.mangi.eta.agent.terminal.TerminalKey
import io.github.mangi.eta.agent.terminal.TerminalKeyEncoder
import io.github.mangi.eta.agent.terminal.TerminalModifiers
import io.github.mangi.eta.agent.terminal.TerminalRuntime
import io.github.mangi.eta.agent.terminal.TerminalScreenBuffer
import io.github.mangi.eta.agent.terminal.isLinux
import io.github.mangi.eta.agent.terminal.ptySupported
import io.github.mangi.eta.agent.terminal.terminalEnvironment
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.data.repository.LinuxEnvironmentSettingsRepository
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.mangi.eta.ui.app.TerminalSessionLease

/** 控制台一帧画面：行快照不可变，渲染层按 id 复用条目、按 version 判断是否重绘。 */
@Immutable
internal data class ConsoleFrame(
    val lines: List<TerminalScreenBuffer.RenderLine> = emptyList(),
    val screenRows: Int = 0,
    val cursorRow: Int = 0,
    val cursorCol: Int = 0,
    val cursorVisible: Boolean = true,
)

@Immutable
internal data class ConsoleSessionUi(
    val id: String,
    val environment: TerminalEnvironment,
    val exited: Boolean = false,
)

@Immutable
internal data class ConsoleUiState(
    val sessions: List<ConsoleSessionUi> = emptyList(),
    val activeSessionId: String? = null,
    /** 环境 tab 的选择；与当前会话环境一致，无会话时表示新建会话的目标环境。 */
    val environment: TerminalEnvironment = TerminalEnvironment.DEBIAN,
    val linuxEnvironment: TerminalEnvironment = TerminalEnvironment.DEBIAN,
    val connected: Boolean = false,
    val exited: Boolean = false,
    /** null = 探测中；false 时入口回退到块式终端。 */
    val ptySupported: Boolean? = null,
    val failMessage: String? = null,
    /** 当前会话的画面；每个会话的屏幕缓冲区独立保存。 */
    val frame: ConsoleFrame = ConsoleFrame(),
)

/**
 * 控制台页面的 App 级状态所有者：持有多个 PTY 会话与各自独立的屏幕缓冲区，
 * 字节流在 IO 线程喂入 [TerminalScreenBuffer]，按节流节奏向 UI 发布当前会话的帧。
 * 离开页面会话仍存活；普通会话由前台执行服务持有，用户停止后回收。
 */
internal class ConsoleStore(
    context: Context,
    private val scope: CoroutineScope,
) {
    private companion object {
        const val FLUSH_INTERVAL_MS = 50L
        /** 同步输出期间暂缓发布帧的上限；程序漏发结束标记时不至于让画面一直停住。 */
        const val SYNCHRONIZED_OUTPUT_TIMEOUT_MS = 200L
        const val SCROLLBACK_LINES = 1000
    }

    /** 按键与查询应答必须按产生顺序写入 stdin；单并发调度保证 FIFO，又不占用主线程。 */
    private val inputDispatcher = Dispatchers.IO.limitedParallelism(1)

    private val appContext = context.applicationContext
    private val sessionLeases = ConcurrentHashMap<String, TerminalSessionLease>()
    private val controller = ConsoleSessionController(
        logger = AndroidAgentLogger,
        linuxRootfsPathProvider = { environment ->
            environment.linuxDistribution?.let { distribution ->
                LinuxEnvironmentPaths.rootfsDir(appContext, distribution).absolutePath
            }
        },
        linuxSharedMountsProvider = { SharedFolderMounts.current() },
    )
    private val bufferLock = Any()
    private val initialLinuxEnvironment =
        LinuxEnvironmentSettingsRepository.current(appContext).terminalEnvironment

    /** 每个会话独立的屏幕缓冲区；写入只能在 IO 线程持锁进行，UI 线程持锁读快照。 */
    private val buffers = mutableMapOf<String, TerminalScreenBuffer>()

    /** 界面当前的默认前景/背景色，供 TUI 查询深浅主题；随系统深浅色切换更新到所有会话。 */
    private var themeColors = 0xFF000000L to 0xFFFFFFFFL
    private val sessionSizes = ConcurrentHashMap<String, Pair<Int, Int>>()

    private val _uiState = MutableStateFlow(
        ConsoleUiState(
            environment = if (isReady(initialLinuxEnvironment)) {
                initialLinuxEnvironment
            } else {
                TerminalEnvironment.ANDROID
            },
            linuxEnvironment = initialLinuxEnvironment,
        )
    )
    val uiState: StateFlow<ConsoleUiState> = _uiState.asStateFlow()

    // PTY 读线程与主线程都会触发发布帧。
    /**
     * 会话启动期间（Root 授权与 chroot 准备可能耗时数秒）网格尺寸仍会随软键盘变化；
     * 这期间只记录最新尺寸，就绪后再同步，避免每次尺寸变化都另起一个会话。只在主线程读写。
     */
    private var creatingSession = false

    @Volatile private var lastFlushMs = 0L
    @Volatile private var flushScheduled = false
    private var lastCols = 0
    private var lastRows = 0

    /** 探测 PTY 前提；结果缓存进状态，不支持时入口回退到块式终端。 */
    fun probePtySupport() {
        scope.launch {
            val supported = withContext(Dispatchers.IO) { ptySupported() }
            _uiState.update { it.copy(ptySupported = supported) }
        }
    }

    fun refreshLinuxEnvironment() {
        val selected = LinuxEnvironmentSettingsRepository.current(appContext).terminalEnvironment
        _uiState.update { state ->
            state.copy(
                linuxEnvironment = selected,
                environment = if (state.environment.isLinux) selected else state.environment,
            )
        }
    }

    /**
     * 确保当前有存活会话；网格尺寸变化重入时只同步尺寸，不重建会话。
     * [environment] 仅用于没有可复用会话时的新建。
     */
    fun open(environment: TerminalEnvironment, cols: Int, rows: Int) {
        if (cols <= 0 || rows <= 0) return
        lastCols = cols
        lastRows = rows
        val state = _uiState.value
        val activeId = state.activeSessionId
        if (activeId != null && controller.sessionAlive(activeId)) {
            resizeSession(activeId, cols, rows)
            return
        }
        if (creatingSession) return
        if (activeId == null) {
            val reusable = state.sessions.lastOrNull { !it.exited && it.environment == environment }
            if (reusable != null && controller.sessionAlive(reusable.id)) {
                switchSession(reusable.id)
                return
            }
        }
        createSession(environment, cols, rows)
    }

    /** 以当前环境与最近一次网格尺寸新建会话。 */
    fun newSession() {
        if (lastCols <= 0 || lastRows <= 0) return
        createSession(_uiState.value.environment, lastCols, lastRows)
    }

    fun switchSession(sessionId: String) {
        val session = _uiState.value.sessions.find { it.id == sessionId } ?: return
        _uiState.update { state ->
            state.copy(
                activeSessionId = sessionId,
                environment = session.environment,
                connected = !session.exited,
                exited = session.exited,
                failMessage = null,
            )
        }
        // 会话在后台期间网格可能已变化（横竖屏、软键盘），切回时按当前网格同步。
        if (lastCols > 0 && lastRows > 0) resizeSession(sessionId, lastCols, lastRows)
        flushFrame(sessionId)
    }

    /** 关闭指定会话；关闭当前会话时切换到剩余最近的会话，没有则回到空态。 */
    fun closeSession(sessionId: String) {
        scope.launch(Dispatchers.IO) {
            controller.closeSession(sessionId)
            sessionLeases.remove(sessionId)?.release()
        }
        synchronized(bufferLock) { buffers.remove(sessionId) }
        sessionSizes.remove(sessionId)
        _uiState.update { state ->
            val sessions = state.sessions.filterNot { it.id == sessionId }
            if (state.activeSessionId != sessionId) {
                state.copy(sessions = sessions)
            } else {
                val next = sessions.lastOrNull()
                state.copy(
                    sessions = sessions,
                    activeSessionId = next?.id,
                    environment = next?.environment ?: state.environment,
                    connected = next != null && !next.exited,
                    exited = next?.exited == true,
                    failMessage = null,
                    frame = ConsoleFrame(),
                )
            }
        }
        _uiState.value.activeSessionId?.let { flushFrame(it) }
    }

    /** 重启指定会话：终止后按原环境与原网格尺寸重开。 */
    fun restartSession(sessionId: String) {
        if (creatingSession) return
        val session = _uiState.value.sessions.find { it.id == sessionId } ?: return
        val size = sessionSizes[sessionId] ?: (lastCols to lastRows)
        if (size.first <= 0 || size.second <= 0) return
        scope.launch(Dispatchers.IO) {
            controller.closeSession(sessionId)
            sessionLeases.remove(sessionId)?.release()
        }
        synchronized(bufferLock) { buffers.remove(sessionId) }
        sessionSizes.remove(sessionId)
        _uiState.update { state ->
            state.copy(sessions = state.sessions.filterNot { it.id == sessionId })
        }
        createSession(session.environment, size.first, size.second)
    }

    /** 切换环境 tab：只改目标环境；有该环境的存活会话则切过去，否则由网格回调新建。 */
    fun switchEnvironment(environment: TerminalEnvironment) {
        val state = _uiState.value
        if (state.environment == environment && state.activeSessionId != null) return
        val reusable = state.sessions.lastOrNull { !it.exited && it.environment == environment }
        if (reusable != null) {
            switchSession(reusable.id)
        } else {
            _uiState.update {
                it.copy(
                    environment = environment,
                    activeSessionId = null,
                    connected = false,
                    exited = false,
                    failMessage = null,
                    frame = ConsoleFrame(),
                )
            }
        }
    }

    /** 当前会话断开后以同一环境与网格尺寸重连。 */
    fun reconnect() {
        val activeId = _uiState.value.activeSessionId ?: return
        restartSession(activeId)
    }

    fun write(text: String) {
        if (text.isEmpty()) return
        val sessionId = _uiState.value.activeSessionId ?: return
        scope.launch(inputDispatcher) { controller.write(sessionId, text) }
    }

    /** 功能键按当前会话的光标键模式编码，程序切换 DECCKM 后无需界面感知。 */
    fun sendKey(key: TerminalKey, modifiers: TerminalModifiers = TerminalModifiers.NONE) {
        val applicationCursorKeys = activeBuffer { it.applicationCursorKeys } ?: return
        write(TerminalKeyEncoder.key(key, modifiers, applicationCursorKeys))
    }

    fun sendText(text: String, modifiers: TerminalModifiers = TerminalModifiers.NONE) {
        write(TerminalKeyEncoder.text(text, modifiers))
    }

    fun paste(text: String) {
        val bracketed = activeBuffer { it.bracketedPaste } ?: return
        write(TerminalKeyEncoder.paste(text, bracketed))
    }

    fun setThemeColors(foreground: Long, background: Long) {
        synchronized(bufferLock) {
            themeColors = foreground to background
            buffers.values.forEach { it.applyTheme() }
        }
    }

    private fun TerminalScreenBuffer.applyTheme() {
        defaultForeground = themeColors.first
        defaultBackground = themeColors.second
    }

    private fun <T> activeBuffer(read: (TerminalScreenBuffer) -> T): T? {
        val sessionId = _uiState.value.activeSessionId ?: return null
        return synchronized(bufferLock) { buffers[sessionId]?.let(read) }
    }

    /** 屏幕缓冲区与 PTY 同步改尺寸；内核随后通知程序重绘，缓冲区先按新尺寸承接旧画面。 */
    private fun resizeSession(sessionId: String, cols: Int, rows: Int) {
        if (sessionSizes[sessionId] == (cols to rows)) return
        sessionSizes[sessionId] = cols to rows
        synchronized(bufferLock) { buffers[sessionId]?.resize(cols, rows) }
        scope.launch(inputDispatcher) { controller.resize(sessionId, cols, rows) }
        flushFrame(sessionId)
    }

    fun close() {
        controller.close()
        sessionLeases.values.forEach { it.release() }
        sessionLeases.clear()
    }

    private fun createSession(environment: TerminalEnvironment, cols: Int, rows: Int) {
        if (cols <= 0 || rows <= 0 || creatingSession) return
        lastCols = cols
        lastRows = rows
        creatingSession = true
        _uiState.update { it.copy(connected = false, exited = false, failMessage = null) }
        scope.launch(Dispatchers.IO) {
            try {
                startSession(environment, cols, rows)
            } finally {
                withContext(NonCancellable + Dispatchers.Main) { creatingSession = false }
            }
            withContext(Dispatchers.Main) {
                // 启动期间网格可能已变化，按最新尺寸同步一次。
                _uiState.value.activeSessionId?.let { resizeSession(it, lastCols, lastRows) }
            }
        }
    }

    private fun startSession(environment: TerminalEnvironment, cols: Int, rows: Int) {
        val rootfs = environment.linuxDistribution?.let { LinuxEnvironmentPaths.rootfsDir(appContext, it).absolutePath }
        val identity = TerminalRuntime.defaultIdentity(environment, rootfs)
        val lease = if (identity == "user") {
            TerminalSessionLease.acquire(appContext) { sessionId -> scope.launch { closeSession(sessionId) } } ?: run {
                _uiState.update { it.copy(failMessage = appContext.getString(R.string.capability_background_failed)) }
                return
            }
        } else null
        val result = try {
            controller.open(
                environment = environment,
                identity = identity,
                cols = cols,
                rows = rows,
                onOutput = ::onOutput,
                onExit = { sessionId ->
                    lease?.release()
                    onExit(sessionId)
                },
            )
        } catch (failure: Throwable) {
            lease?.release()
            throw failure
        }
        when (result) {
            is ConsoleSessionController.OpenResult.Ready -> {
                if (lease != null) {
                    sessionLeases[result.sessionId] = lease
                    if (!lease.attach(result.sessionId) || !controller.sessionAlive(result.sessionId)) {
                        controller.closeSession(result.sessionId)
                        sessionLeases.remove(result.sessionId)?.release()
                        _uiState.update { it.copy(failMessage = appContext.getString(R.string.terminal_session_closed)) }
                        return
                    }
                }
                synchronized(bufferLock) {
                    buffers[result.sessionId] = TerminalScreenBuffer(cols, rows, SCROLLBACK_LINES).apply { applyTheme() }
                }
                sessionSizes[result.sessionId] = cols to rows
                _uiState.update { state ->
                    state.copy(
                        sessions = state.sessions + ConsoleSessionUi(result.sessionId, environment),
                        activeSessionId = result.sessionId,
                        environment = environment,
                        connected = true,
                        exited = false,
                        frame = ConsoleFrame(),
                    )
                }
            }
            is ConsoleSessionController.OpenResult.Failed -> {
                lease?.release()
                _uiState.update { it.copy(connected = false, failMessage = result.message) }
            }
        }
    }

    private fun onOutput(sessionId: String, chunk: ByteArray) {
        var responses: String? = null
        var synchronizing = false
        synchronized(bufferLock) {
            val buffer = buffers[sessionId] ?: return
            buffer.feed(chunk)
            responses = buffer.drainResponses()
            synchronizing = buffer.synchronizedOutput
        }
        // 查询应答不经过界面，后台会话也要及时回复，否则程序会阻塞在等待上。
        responses?.let { reply -> scope.launch(inputDispatcher) { controller.write(sessionId, reply) } }
        if (sessionId != _uiState.value.activeSessionId) return
        val now = System.currentTimeMillis()
        when {
            synchronizing -> scheduleFlush(sessionId, SYNCHRONIZED_OUTPUT_TIMEOUT_MS)
            now - lastFlushMs >= FLUSH_INTERVAL_MS -> flushFrame(sessionId)
            else -> scheduleFlush(sessionId, FLUSH_INTERVAL_MS)
        }
    }

    private fun scheduleFlush(sessionId: String, delayMs: Long) {
        if (flushScheduled) return
        flushScheduled = true
        scope.launch {
            delay(delayMs)
            flushScheduled = false
            flushFrame(sessionId)
        }
    }

    private fun flushFrame(sessionId: String) {
        val frame = synchronized(bufferLock) {
            val current = buffers[sessionId] ?: return
            ConsoleFrame(
                lines = current.renderLines(),
                screenRows = current.rows,
                cursorRow = current.cursorRow,
                cursorCol = current.cursorCol,
                cursorVisible = current.cursorVisible,
            )
        }
        if (sessionId != _uiState.value.activeSessionId) return
        lastFlushMs = System.currentTimeMillis()
        _uiState.update { it.copy(frame = frame) }
    }

    private fun onExit(sessionId: String) {
        sessionLeases.remove(sessionId)?.release()
        _uiState.update { state ->
            val sessions = state.sessions.map {
                if (it.id == sessionId) it.copy(exited = true) else it
            }
            if (state.activeSessionId == sessionId) {
                state.copy(sessions = sessions, connected = false, exited = true)
            } else {
                state.copy(sessions = sessions)
            }
        }
        flushFrame(sessionId)
    }

    private fun isReady(environment: TerminalEnvironment): Boolean =
        environment.linuxDistribution?.let { distribution ->
            LinuxEnvironmentPaths.rootfsReady(
                LinuxEnvironmentPaths.rootfsDir(appContext, distribution).absolutePath,
            )
        } ?: false
}
