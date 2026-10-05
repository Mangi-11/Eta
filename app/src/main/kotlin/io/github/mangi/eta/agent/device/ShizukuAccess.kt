package io.github.mangi.eta.agent.device

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import io.github.mangi.eta.core.AndroidAgentLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

internal enum class ShizukuStatus {
    UNKNOWN,
    UNAVAILABLE,
    NOT_GRANTED,
    GRANTED,
    DENIED,
}

internal data class ShizukuAccessState(
    val status: ShizukuStatus = ShizukuStatus.UNKNOWN,
    val managerInstalled: Boolean = false,
    val binderAlive: Boolean = false,
    val isChecking: Boolean = false,
) {
    val isGranted: Boolean get() = status == ShizukuStatus.GRANTED
    /** shell 级通道是否可用：binder 存活且已授权。执行 shell 前请使用该闸门，而非 [isGranted]。 */
    val isAvailable: Boolean get() = binderAlive && isGranted
}

/**
 * Shizuku 授权状态归 App 主进程所有；读取状态不会触发授权弹窗。
 *
 * - 主进程（[EtaApp][io.github.mangi.eta.EtaApp] 经 `AppProcessPolicy` 守卫后）调用 [initialize]，
 *   `:voice`/`:recognition` 进程不直接调用 Shizuku；若 Phase 2 需要跨进程执行，
 *   届时再在目标进程补 `ShizukuProvider.enableMultiProcessSupport()`。
 * - `exported=true` 的 `ShizukuProvider` 为官方要求（RikkaApps/Shizuku-API README），
 *   保护靠 `INTERACT_ACROSS_USERS_FULL`（仅 shell 持有），非漏洞。
 */
internal object ShizukuAccess {
    internal const val MANAGER_PACKAGE = "moe.shizuku.privileged.api"
    private const val PERMISSION_API_V23 = "moe.shizuku.manager.permission.API_V23"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow(ShizukuAccessState())
    val state: StateFlow<ShizukuAccessState> = mutableState.asStateFlow()
    val isGranted: Boolean get() = mutableState.value.isGranted
    val isAvailable: Boolean get() = mutableState.value.isAvailable
    private var checkJob: Job? = null
    private var listenersRegistered = false

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        scope.launch { refreshInternal() }
    }
    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        onBinderDead()
    }
    private val permissionResultListener =
        Shizuku.OnRequestPermissionResultListener { _, _ ->
            scope.launch { refreshInternal() }
        }

    @Volatile
    private var appContext: Context? = null

    fun initialize(context: Context) {
        val app = context.applicationContext
        val first = synchronized(this) {
            val wasFirst = appContext == null
            appContext = app
            if (!listenersRegistered) {
                listenersRegistered = true
                true
            } else {
                false
            }
        }
        if (first) {
            runCatching {
                Shizuku.addBinderReceivedListener(binderReceivedListener)
                Shizuku.addBinderDeadListener(binderDeadListener)
                Shizuku.addRequestPermissionResultListener(permissionResultListener)
            }.onFailure {
                AndroidAgentLogger.warn("Shizuku listener registration failed")
            }
        }
        refresh(app)
    }

    fun refresh(context: Context): Job = synchronized(this) {
        checkJob?.takeIf { it.isActive }?.let { return@synchronized it }
        val app = context.applicationContext
        appContext = app
        scope.launch { refreshInternal(app) }.also { checkJob = it }
    }

    private suspend fun refreshInternal(explicit: Context? = null) {
        val context = explicit ?: appContext ?: return
        mutableState.update { it.copy(isChecking = true) }
        try {
            val probed = probe(context)
            mutableState.update { current ->
                // probe 期間 binder 可能死亡；以最新 binder 狀態兜底，避免蓋掉 onBinderDead。
                val binderNow = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
                if (!binderNow && probed.binderAlive) {
                    probed.copy(status = ShizukuStatus.UNAVAILABLE, binderAlive = false)
                } else if (current.status == ShizukuStatus.UNKNOWN && probed.status == ShizukuStatus.UNKNOWN) {
                    probed.copy(status = ShizukuStatus.UNAVAILABLE)
                } else {
                    probed
                }
            }
        } finally {
            mutableState.update { it.copy(isChecking = false) }
        }
    }

    /** binder 死亡后降级，避免沿用过期授权；binder 恢复由 listener 触发 refresh。 */
    fun onBinderDead() {
        mutableState.update { current ->
            current.copy(
                status = when (current.status) {
                    ShizukuStatus.GRANTED, ShizukuStatus.UNKNOWN -> ShizukuStatus.UNAVAILABLE
                    else -> current.status
                },
                binderAlive = false,
            )
        }
    }

    fun isManagerInstalled(context: Context): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(
                MANAGER_PACKAGE,
                PackageManager.PackageInfoFlags.of(0L),
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(MANAGER_PACKAGE, 0)
        }
        true
    }.getOrDefault(false)

    private fun probe(context: Context): ShizukuAccessState {
        val installed = isManagerInstalled(context)
        val alive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        if (!installed || !alive) {
            return ShizukuAccessState(
                status = ShizukuStatus.UNAVAILABLE,
                managerInstalled = installed,
                binderAlive = alive,
            )
        }
        if (runCatching { Shizuku.isPreV11() }.getOrDefault(false)) {
            // 预 v11 走传统权限模型；本分支仅做状态映射，不主动弹授权。
            val granted = context.checkSelfPermission(PERMISSION_API_V23) == PackageManager.PERMISSION_GRANTED
            return ShizukuAccessState(
                status = if (granted) ShizukuStatus.GRANTED else ShizukuStatus.NOT_GRANTED,
                managerInstalled = true,
                binderAlive = true,
            )
        }
        return when (runCatching { Shizuku.checkSelfPermission() }.getOrDefault(PackageManager.PERMISSION_DENIED)) {
            PackageManager.PERMISSION_GRANTED -> ShizukuAccessState(
                status = ShizukuStatus.GRANTED,
                managerInstalled = true,
                binderAlive = true,
            )
            else -> {
                // v11+：用户勾选“不再询问”时 rationale 为 true，映射为 DENIED；否则为可再求的 NOT_GRANTED。
                val neverAskAgain = runCatching {
                    Shizuku.shouldShowRequestPermissionRationale()
                }.getOrDefault(false)
                ShizukuAccessState(
                    status = if (neverAskAgain) ShizukuStatus.DENIED else ShizukuStatus.NOT_GRANTED,
                    managerInstalled = true,
                    binderAlive = true,
                )
            }
        }
    }

    /**
     * 必须在前台 Activity 中调用；结果经
     * [Shizuku.OnRequestPermissionResultListener]（已在 [initialize] 注册）自动刷新，
     * 无需走 `Activity.onRequestPermissionsResult`。
     *
     * @return true 表示已发起请求；false 表示 binder 不存活。
     */
    fun requestPermission(requestCode: Int): Boolean {
        if (runCatching { Shizuku.pingBinder() }.getOrDefault(false).not()) return false
        return runCatching {
            Shizuku.requestPermission(requestCode)
            true
        }.getOrDefault(false)
    }

    /** v11+ 是否处于“拒绝且不再询问”；用于授权引导文案。 */
    fun shouldShowRequestPermissionRationale(): Boolean =
        runCatching { Shizuku.shouldShowRequestPermissionRationale() }.getOrDefault(false)
}
