package fuck.andes.agent.accessibility

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.accessibility.AccessibilityManager
import fuck.andes.core.AndroidAgentLogger
import java.util.concurrent.Executors

/**
 * 开机和升级后只通过公开 API 审计用户授权状态。
 * 服务绑定由系统负责；这里不得修改 Secure Settings 或替用户重新启用无障碍。
 */
object AgentAccessibilityKeeper {
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "agent-accessibility-audit").apply { isDaemon = true }
    }

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

    internal fun isServiceEnabled(
        enabledComponents: List<AccessibilityServiceIdentity>,
        target: AccessibilityServiceIdentity,
    ): Boolean = enabledComponents.any { component -> component == target }

    private fun String.toSafeReason(): String = when (this) {
        Intent.ACTION_BOOT_COMPLETED -> "boot"
        Intent.ACTION_MY_PACKAGE_REPLACED -> "package_replaced"
        else -> "unknown"
    }
}

internal data class AccessibilityServiceIdentity(
    val packageName: String,
    val className: String,
)
