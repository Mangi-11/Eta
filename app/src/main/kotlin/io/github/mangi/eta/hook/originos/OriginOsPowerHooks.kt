package io.github.mangi.eta.hook.originos

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import io.github.libxposed.api.XposedModule
import io.github.mangi.eta.config.PowerAssistantTarget
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.core.ModuleLogger
import io.github.mangi.eta.hook.support.HookInstallation
import io.github.mangi.eta.hook.support.HookRegistrar
import io.github.mangi.eta.hook.support.HookSupport
import io.github.mangi.eta.hook.system.AssistantManager

internal object OriginOsPowerHooks {
    private const val GROUP = "OriginOsPower"
    private const val HOOK_ID = "originos.power-assistant"
    private const val WAKE_ACTION = "com.vivo.intent.action.WAKEUP_AGENT_BY_POWER"
    private val assistantPackages = setOf(
        "com.vivo.ai.copilot", "com.vivo.ai.base.copilot", "com.vivo.agent", "com.vivo.base.agent",
    )
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    fun install(module: XposedModule, logger: ModuleLogger, loader: ClassLoader): HookInstallation {
        val hooks = HookRegistrar(module, logger, GROUP)
        return hooks.install {
            val policyClass = HookSupport.findClassOrNull(loader, "com.android.server.policy.VivoInputPolicy")
            if (policyClass == null) {
                skipped(HOOK_ID, "VivoInputPolicy", "当前系统未提供 vivo 输入策略")
                return@install
            }
            val wakeMethod = HookSupport.findMethod(policyClass, "startJoviVoice", Int::class.javaPrimitiveType!!)
            val contextClass = HookSupport.findClassOrNull(loader, "android.app.ContextImpl")
            val startService = contextClass?.let {
                HookSupport.findMethod(it, "startServiceAsUser", Intent::class.java, UserHandle::class.java)
            }
            if (wakeMethod == null || startService == null || startService.returnType != ComponentName::class.java) {
                missing(HOOK_ID, "ContextImpl.startServiceAsUser", "OriginOS: 电源键助理分发签名不匹配")
                return@install
            }

            HookSupport.deoptimize(module, hooks.logger, wakeMethod, "VivoInputPolicy.startJoviVoice")
            // VivoInputPolicy 已判断系统开关和场景，并在启动服务前标记按键已处理。
            intercept(HOOK_ID, startService, "ContextImpl.startServiceAsUser (WAKEUP_AGENT_BY_POWER)") { chain ->
                val intent = chain.getArg(0) as? Intent
                if (intent?.action != WAKE_ACTION || intent.`package` !in assistantPackages) {
                    return@intercept chain.proceed()
                }
                val context = chain.getThisObject() as Context
                if (showSelectedAssistant(context, hooks.logger)) null else chain.proceed()
            }
        }
    }

    private fun showSelectedAssistant(context: Context, logger: ModuleLogger): Boolean {
        val target = Prefs.powerAssistantTarget()
        if (target == PowerAssistantTarget.OEM) return false
        if (AssistantManager.showAssistantSession(context, target, logger, "VivoInputPolicy", logFailures = true)) return true
        if (Prefs.isEnabled(Prefs.Keys.ASSISTANT_AUTO_CONFIG)) {
            AssistantManager.scheduleAssistantRecovery(context, logger, mainHandler, forceRefresh = true)
        }
        return false
    }
}
