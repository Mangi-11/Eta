package io.github.mangi.eta.hook.originos

import android.content.Context
import io.github.libxposed.api.XposedModule
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.core.ModuleLogger
import io.github.mangi.eta.hook.support.HookInstallation
import io.github.mangi.eta.hook.support.HookRegistrar
import io.github.mangi.eta.hook.support.HookSupport

internal object OriginOsGestureHooks {
    private const val GROUP = "OriginOsGesture"
    private const val HOOK_ID = "originos.nav-handle-long-press"

    fun install(module: XposedModule, logger: ModuleLogger, loader: ClassLoader): HookInstallation {
        val hooks = HookRegistrar(module, logger, GROUP)
        return hooks.install {
            val dispatcher = HookSupport.findClassOrNull(loader, "com.vivo.upslide.navigation.CopilotTriggerAnimStatusManager")
            val eventProcess = HookSupport.findClassOrNull(loader, "com.vivo.upslide.navigation.sideslide.CopilotEventProcess")
            val dispatch = dispatcher?.let { HookSupport.findMethod(it, "m") }
            val contextField = dispatcher?.let { HookSupport.findField(it, "a") }
            val currentEventProcess = eventProcess?.let { HookSupport.findField(it, "r") }
            val shouldShowGuide = eventProcess?.let { HookSupport.findMethod(it, "y") }
            if (dispatch == null || dispatch.returnType != Void.TYPE ||
                contextField == null || contextField.type != Context::class.java ||
                currentEventProcess == null || currentEventProcess.type != eventProcess ||
                shouldShowGuide == null || shouldShowGuide.returnType != Boolean::class.javaPrimitiveType
            ) {
                missing(HOOK_ID, "CopilotTriggerAnimStatusManager.m", "OriginOS: 手势条长按分发签名不匹配")
                return@install
            }

            // UpSlide 的长按识别已完成。y() 为 true 时该入口显示系统引导。
            intercept(HOOK_ID, dispatch, "CopilotTriggerAnimStatusManager.m") { chain ->
                if (!Prefs.isEnabled(Prefs.Keys.GESTURE_BAR_CIRCLE_TO_SEARCH)) return@intercept chain.proceed()
                val events = currentEventProcess.get(null) ?: return@intercept chain.proceed()
                if (shouldShowGuide.invoke(events) == true) return@intercept chain.proceed()

                val context = contextField.get(chain.getThisObject()) as Context
                if (OriginOsContextualSearch.tryStart(context, OriginOsContextualSearch.Gesture.NAVIGATION_HANDLE, hooks.logger)) {
                    null
                } else {
                    chain.proceed()
                }
            }
        }
    }
}
