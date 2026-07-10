package fuck.andes.agent.runtime

import android.os.SystemClock
import fuck.andes.agent.accessibility.AgentAccessibilityService
import fuck.andes.core.AgentLogger
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 把外部入口从前台工具的真实操作对象中隔离开。
 *
 * 关闭动作、窗口稳定确认和首张截图排除共享同一份入口描述，避免各层分别猜测入口状态。
 */
internal class EntrySurfaceGuard private constructor(
    internal val targetPackageName: String?,
    private val logger: AgentLogger,
) {
    private val triggered = AtomicBoolean(false)
    private val screenshotExclusionPending = AtomicBoolean(targetPackageName != null)

    val wasTriggered: Boolean
        get() = triggered.get()

    fun dismissOnce(): Boolean {
        if (!triggered.compareAndSet(false, true)) return false
        val service = AgentAccessibilityService.current()
        if (service == null) {
            logger.warn("Agent runtime entry surface dismiss skipped: accessibility service unavailable")
            return false
        }

        val packageName = targetPackageName
        val wasVisible = packageName?.let(service::isPackageWindowVisible)
        val startedAt = SystemClock.elapsedRealtime()
        val actionAccepted = service.globalAction("BACK")
        val windowGone = packageName?.let(service::awaitPackageWindowGone) ?: actionAccepted
        val waitedMillis = SystemClock.elapsedRealtime() - startedAt
        val completed = actionAccepted && windowGone

        if (completed) {
            logger.debug {
                "Agent runtime entry surface dismissed before foreground operation " +
                    "package=$packageName visibleBefore=$wasVisible waitedMs=$waitedMillis"
            }
        } else {
            logger.warn(
                "Agent runtime entry surface dismiss incomplete before foreground operation: " +
                    "actionAccepted=$actionAccepted windowGone=$windowGone package=$packageName " +
                    "visibleBefore=$wasVisible waitedMs=$waitedMillis"
            )
        }
        return completed
    }

    fun consumeScreenshotExcludedPackages(): Set<String> {
        val packageName = targetPackageName ?: return emptySet()
        return if (screenshotExclusionPending.compareAndSet(true, false)) {
            setOf(packageName)
        } else {
            emptySet()
        }
    }

    companion object {
        fun from(
            handoff: AgentRuntimeWire.EntryHandoff?,
            logger: AgentLogger,
        ): EntrySurfaceGuard? {
            if (handoff?.dismissEntrySurfaceOnForegroundOperation != true) return null
            val packageName = when (handoff.source) {
                BREENO_HANDOFF_SOURCE -> BREENO_PACKAGE_NAME
                else -> null
            }
            return EntrySurfaceGuard(packageName, logger)
        }

        private const val BREENO_HANDOFF_SOURCE = "breeno"
        private const val BREENO_PACKAGE_NAME = "com.heytap.speechassist"
    }
}
