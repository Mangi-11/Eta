package fuck.andes.agent.accessibility

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Process
import android.os.SystemClock
import android.view.accessibility.AccessibilityManager
import fuck.andes.core.AndroidAgentLogger
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 审计并按需恢复 Eta 无障碍服务。
 *
 * 开机和升级广播只审计状态；只有用户主动发起 GUI Agent 操作时，才允许使用
 * 已授予的 Root 能力补齐 Secure Settings，并且始终保留其他无障碍服务。
 */
object AgentAccessibilityKeeper {
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "agent-accessibility-audit").apply { isDaemon = true }
    }
    private val enableLock = Any()

    fun auditAsync(
        context: Context,
        reason: String,
        onComplete: (() -> Unit)? = null,
    ) {
        val appContext = context.applicationContext
        executor.execute {
            try {
                audit(appContext, reason)
            } finally {
                onComplete?.invoke()
            }
        }
    }

    internal fun ensureEnabledForGuiOperation(context: Context): AccessibilityEnableResult {
        if (AgentAccessibilityService.isAvailable()) {
            return AccessibilityEnableResult.available(rootAttempted = false)
        }
        val startedAt = SystemClock.elapsedRealtime()
        val targetComponent = ComponentName(context, AgentAccessibilityService::class.java)
        val result = synchronized(enableLock) {
            ensureEnabled(
                targetComponent = targetComponent.flattenToString(),
                shortComponent = targetComponent.flattenToShortString(),
                // Android 把 userId 编码在 uid 的高位；公开 SDK 没有暴露 getUserId。
                userId = Process.myUid() / ANDROID_UIDS_PER_USER,
                serviceAvailable = AgentAccessibilityService::isAvailable,
                runRootCommand = ::runRootCommand,
                awaitServiceBinding = ::awaitServiceBinding,
            )
        }
        val elapsedMs = SystemClock.elapsedRealtime() - startedAt
        if (result.available) {
            AndroidAgentLogger.info(
                "Agent accessibility action=enable_for_gui outcome=completed " +
                    "rootAttempted=${result.rootAttempted} settingChanged=${result.settingChanged} " +
                    "elapsed_ms=$elapsedMs"
            )
        } else {
            AndroidAgentLogger.warn(
                "Agent accessibility action=enable_for_gui outcome=failed " +
                    "code=${result.code} rootAttempted=${result.rootAttempted} elapsed_ms=$elapsedMs"
            )
        }
        return result
    }

    private fun audit(context: Context, reason: String) {
        val targetComponent = ComponentName(context, AgentAccessibilityService::class.java)
        val target = AccessibilityServiceIdentity(
            packageName = targetComponent.packageName,
            className = targetComponent.className,
        )
        val enabledComponents = runCatching {
            val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
            manager
                .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .mapNotNull { info ->
                    info.resolveInfo?.serviceInfo?.let { serviceInfo ->
                        AccessibilityServiceIdentity(serviceInfo.packageName, serviceInfo.name)
                    }
                }
        }.getOrElse { throwable ->
            AndroidAgentLogger.warn(
                "Agent accessibility action=audit outcome=failed " +
                    "reason=${reason.toSafeReason()} error=${throwable.javaClass.simpleName}"
            )
            return
        }
        val state = if (isServiceEnabled(enabledComponents, target)) "enabled" else "disabled"
        AndroidAgentLogger.info(
            "Agent accessibility action=audit outcome=completed state=$state " +
                "reason=${reason.toSafeReason()} enabledServices=${enabledComponents.size}"
        )
    }

    internal fun ensureEnabled(
        targetComponent: String,
        shortComponent: String,
        userId: Int,
        serviceAvailable: () -> Boolean,
        runRootCommand: (String) -> RootCommandResult,
        awaitServiceBinding: () -> Boolean,
    ): AccessibilityEnableResult {
        if (serviceAvailable()) {
            return AccessibilityEnableResult.available(rootAttempted = false)
        }
        val command = buildEnableCommand(targetComponent, shortComponent, userId)
        val rootResult = runRootCommand(command)
        val settingChanged = rootResult.output == ROOT_RESULT_CHANGED
        if (rootResult.exitCode != 0 || rootResult.output !in ROOT_SUCCESS_RESULTS) {
            return AccessibilityEnableResult.failure(
                code = "ACCESSIBILITY_ROOT_ENABLE_FAILED",
                message = "Root 无法启用 Eta 无障碍服务；本次 GUI 操作未执行",
                rootAttempted = true,
            )
        }
        if (!awaitServiceBinding()) {
            return AccessibilityEnableResult.failure(
                code = "ACCESSIBILITY_BIND_TIMEOUT",
                message = "Eta 无障碍服务已写入系统设置，但未在时限内连接；本次 GUI 操作未执行",
                rootAttempted = true,
                settingChanged = settingChanged,
            )
        }
        return AccessibilityEnableResult.available(
            rootAttempted = true,
            settingChanged = settingChanged,
        )
    }

    internal fun buildEnableCommand(
        targetComponent: String,
        shortComponent: String,
        userId: Int,
    ): String =
        """
        user_id=${shellQuote(userId.toString())}
        target=${shellQuote(targetComponent)}
        short_target=${shellQuote(shortComponent)}
        services=${'$'}(settings --user "${'$'}user_id" get secure enabled_accessibility_services 2>/dev/null)
        if [ "${'$'}services" = "null" ]; then services=""; fi
        changed=0
        case ":${'$'}services:" in
          *":${'$'}target:"*|*":${'$'}short_target:"*) new_services="${'$'}services" ;;
          "::") new_services="${'$'}target"; changed=1 ;;
          *) new_services="${'$'}services:${'$'}target"; changed=1 ;;
        esac
        enabled=${'$'}(settings --user "${'$'}user_id" get secure accessibility_enabled 2>/dev/null)
        if [ "${'$'}enabled" != "1" ]; then changed=1; fi
        settings --user "${'$'}user_id" put secure enabled_accessibility_services "${'$'}new_services" || exit 21
        settings --user "${'$'}user_id" put secure accessibility_enabled 1 || exit 22
        echo "ok:${'$'}changed"
        """.trimIndent()

    internal fun isServiceEnabled(
        enabledComponents: List<AccessibilityServiceIdentity>,
        target: AccessibilityServiceIdentity,
    ): Boolean = enabledComponents.any { component -> component == target }

    private fun awaitServiceBinding(): Boolean {
        repeat(SERVICE_BIND_ATTEMPTS) {
            if (AgentAccessibilityService.isAvailable()) return true
            SystemClock.sleep(SERVICE_BIND_POLL_MS)
        }
        return AgentAccessibilityService.isAvailable()
    }

    private fun runRootCommand(command: String): RootCommandResult {
        val process = runCatching {
            ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()
        }.getOrElse {
            return RootCommandResult(exitCode = -1)
        }
        val finished = runCatching {
            process.waitFor(ROOT_COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }.getOrDefault(false)
        if (!finished) {
            process.destroyForcibly()
            return RootCommandResult(exitCode = -2)
        }
        val output = runCatching {
            process.inputStream.bufferedReader().use { reader -> reader.readText().trim().take(64) }
        }.getOrDefault("")
        return RootCommandResult(
            exitCode = process.exitValue(),
            output = output,
        )
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"

    private fun String.toSafeReason(): String = when (this) {
        Intent.ACTION_BOOT_COMPLETED -> "boot"
        Intent.ACTION_MY_PACKAGE_REPLACED -> "package_replaced"
        else -> "unknown"
    }

    private const val ROOT_COMMAND_TIMEOUT_SECONDS = 6L
    private const val ANDROID_UIDS_PER_USER = 100_000
    private const val SERVICE_BIND_ATTEMPTS = 60
    private const val SERVICE_BIND_POLL_MS = 100L
    private const val ROOT_RESULT_UNCHANGED = "ok:0"
    private const val ROOT_RESULT_CHANGED = "ok:1"
    private val ROOT_SUCCESS_RESULTS = setOf(ROOT_RESULT_UNCHANGED, ROOT_RESULT_CHANGED)
}

internal data class AccessibilityEnableResult(
    val available: Boolean,
    val code: String = "",
    val message: String = "",
    val rootAttempted: Boolean,
    val settingChanged: Boolean = false,
) {
    companion object {
        fun available(
            rootAttempted: Boolean,
            settingChanged: Boolean = false,
        ): AccessibilityEnableResult = AccessibilityEnableResult(
            available = true,
            rootAttempted = rootAttempted,
            settingChanged = settingChanged,
        )

        fun failure(
            code: String,
            message: String,
            rootAttempted: Boolean,
            settingChanged: Boolean = false,
        ): AccessibilityEnableResult = AccessibilityEnableResult(
            available = false,
            code = code,
            message = message,
            rootAttempted = rootAttempted,
            settingChanged = settingChanged,
        )
    }
}

internal data class RootCommandResult(
    val exitCode: Int,
    val output: String = "",
)

internal data class AccessibilityServiceIdentity(
    val packageName: String,
    val className: String,
)
