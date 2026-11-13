package io.github.mangi.eta.agent.device

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import io.github.mangi.eta.core.AndroidAgentLogger
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull

internal enum class RootAccessStatus { UNKNOWN, UNAVAILABLE, NOT_GRANTED, GRANTED, DENIED, TIMED_OUT }

internal data class RootAccessState(
    val status: RootAccessStatus = RootAccessStatus.UNKNOWN,
    val suPresent: Boolean = false,
    val isChecking: Boolean = false,
) {
    val isGranted: Boolean get() = status == RootAccessStatus.GRANTED
}

/** Root 探测归 App 进程所有；读取能力不会执行 su 或弹出授权。 */
internal object RootAccess {
    private const val PREFERENCES = "eta_root_access"
    private const val AUTOMATIC_REQUEST_ATTEMPTED = "automatic_request_attempted"
    private const val LAST_GRANTED = "last_granted"
    private const val RETRY_COOLDOWN_MS = 5_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow(RootAccessState())
    val state: StateFlow<RootAccessState> = mutableState.asStateFlow()
    val isGranted: Boolean get() = mutableState.value.isGranted
    private var preferences: SharedPreferences? = null
    private var probeJob: Job? = null
    @Volatile
    private var lastAutoRetryAtElapsedMs: Long = 0L

    fun initialize(context: Context) {
        synchronized(this) {
            if (preferences != null) return
            preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        }
        refresh(context)
    }

    /** 首次发现 su 时请求一次；拒绝后只有显式 request 才会再次申请。 */
    fun refresh(context: Context): Job = startProbe(context, explicit = false)

    fun request(context: Context): Job = startProbe(context, explicit = true)

    /**
     * 工具或 Runtime 启动阶段等待探测完成；若此前已授过权（last_granted=true）但本次探测处于临时失败态
     * （例如重启后 KernelSU 临时 Root 尚未就绪或超时），在冷却时间后自动复检一次，避免整次进程周期失灵。
     */
    fun awaitOrRetryIfPreviouslyGranted(context: Context, maxWaitMs: Long = 3_000L) {
        if (isGranted) return
        val prefs = preferences ?: context.applicationContext
            .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .also { preferences = it }
        val activeJob = synchronized(this) {
            val currentJob = probeJob?.takeIf { it.isActive }
            if (currentJob != null) {
                currentJob
            } else {
                val status = mutableState.value.status
                val wasGranted = prefs.getBoolean(LAST_GRANTED, false)
                val now = SystemClock.elapsedRealtime()
                if (shouldAutoRetryOnUse(status, wasGranted, now - lastAutoRetryAtElapsedMs)) {
                    lastAutoRetryAtElapsedMs = now
                    startProbe(context, explicit = false)
                } else {
                    null
                }
            }
        }
        if (activeJob != null) {
            runBlocking {
                withTimeoutOrNull(maxWaitMs.coerceAtLeast(200L)) {
                    activeJob.join()
                }
            }
        }
    }

    /** 仅由执行器确认 su 拒绝后调用；普通命令失败不代表授权被撤销。 */
    fun markDenied() {
        preferences?.edit()?.putBoolean(LAST_GRANTED, false)?.apply()
        mutableState.value = mutableState.value.copy(status = RootAccessStatus.DENIED)
    }

    private fun startProbe(context: Context, explicit: Boolean): Job = synchronized(this) {
        probeJob?.takeIf { it.isActive }?.let { return@synchronized it }
        val prefs = preferences ?: context.applicationContext
            .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .also { preferences = it }
        mutableState.value = mutableState.value.copy(isChecking = true)
        scope.launch {
            try {
                val attempted = prefs.getBoolean(AUTOMATIC_REQUEST_ATTEMPTED, false)
                val wasGranted = prefs.getBoolean(LAST_GRANTED, false)
                val present = suExists()
                // 若此前已授过权（wasGranted=true），即使 KernelSU 隐藏 su 文件或 PATH 不可见，也允许尝试执行 su。
                if (!present && !explicit && !wasGranted) {
                    mutableState.value = RootAccessState(RootAccessStatus.UNAVAILABLE)
                    return@launch
                }
                if (!shouldRequestRoot(explicit, attempted, wasGranted)) {
                    mutableState.value = mutableState.value.copy(
                        status = if (mutableState.value.status == RootAccessStatus.UNKNOWN) {
                            RootAccessStatus.NOT_GRANTED
                        } else {
                            mutableState.value.status
                        },
                        suPresent = present,
                    )
                    return@launch
                }
                // 先记住尝试，进程在授权弹窗期间退出也不会在下次启动再次打扰用户。
                if (!prefs.edit().putBoolean(AUTOMATIC_REQUEST_ATTEMPTED, true).commit()) {
                    mutableState.value = RootAccessState(RootAccessStatus.NOT_GRANTED, suPresent = present)
                    AndroidAgentLogger.warn("Root access probe outcome=preferences_unavailable")
                    return@launch
                }
                val result = runInterruptible {
                    BoundedRootCommandExecutor(AndroidAgentLogger, rootAvailable = { true }).use {
                        it.execute("id -u", timeoutMillis = 30_000L, maxOutputBytes = 256)
                    }
                }
                val (status, nextLastGranted) = classifyRootProbeOutcome(
                    wasGranted = wasGranted,
                    result = result,
                )
                if (nextLastGranted != wasGranted) {
                    prefs.edit().putBoolean(LAST_GRANTED, nextLastGranted).apply()
                }
                if (status != RootAccessStatus.GRANTED) {
                    AndroidAgentLogger.warn(
                        "Root access probe outcome=${status.name.lowercase()} " +
                            "suPresent=$present exit=${result.exitCode} err=${result.errorCode.ifBlank { "none" }}",
                    )
                }
                mutableState.value = RootAccessState(
                    status = status,
                    suPresent = present || status == RootAccessStatus.GRANTED,
                )
            } finally {
                mutableState.value = mutableState.value.copy(isChecking = false)
            }
        }.also { probeJob = it }
    }

    private fun suExists(): Boolean = System.getenv("PATH").orEmpty()
        .split(File.pathSeparatorChar)
        .filter(String::isNotBlank)
        .any { path -> File(path, "su").let { it.isFile && it.canExecute() } }
}

internal fun shouldRequestRoot(explicit: Boolean, attempted: Boolean, wasGranted: Boolean): Boolean =
    explicit || !attempted || wasGranted

internal fun shouldAutoRetryOnUse(
    status: RootAccessStatus,
    wasGranted: Boolean,
    elapsedSinceLastRetryMs: Long,
    cooldownMs: Long = 5_000L,
): Boolean = wasGranted &&
    status != RootAccessStatus.GRANTED &&
    status != RootAccessStatus.DENIED &&
    elapsedSinceLastRetryMs >= cooldownMs

internal fun classifyRootProbeOutcome(
    wasGranted: Boolean,
    result: BoundedRootCommandExecutor.Result,
): Pair<RootAccessStatus, Boolean> {
    val granted = result.ok && result.stdout.trim() == "0"
    if (granted) return RootAccessStatus.GRANTED to true
    val status = when {
        result.errorCode == "ROOT_UNAVAILABLE" -> RootAccessStatus.UNAVAILABLE
        result.timedOut -> RootAccessStatus.TIMED_OUT
        result.errorCode == "ROOT_INPUT_FAILED" || result.errorCode == "ROOT_OUTPUT_FAILED" -> RootAccessStatus.UNAVAILABLE
        else -> RootAccessStatus.DENIED
    }
    // 临时不可用或超时不擦除历史授权，仅在确认被拒绝时清除 last_granted。
    val keepLastGranted = wasGranted && status != RootAccessStatus.DENIED
    return status to keepLastGranted
}
