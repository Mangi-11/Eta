package io.github.mangi.eta.hook.originos

import android.content.Context
import io.github.libxposed.api.XposedModule
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.core.ModuleLogger
import io.github.mangi.eta.hook.support.HookInstallation
import io.github.mangi.eta.hook.support.HookRegistrar
import io.github.mangi.eta.hook.support.HookSupport

internal object OriginOsDoubleFingerHooks {
    private const val STARTER_CLASS = "com.vivo.accessibilityenhance.dc.uj0"
    private const val ACTIVE_DOUBLE_FINGER_EVENT = 8
    private const val HOOK_ID = "originos.double-finger-long-press"
    private const val DESCRIPTION = "StarterCircleSearchDoubleFinger dispatch"

    fun install(
        module: XposedModule,
        logger: ModuleLogger,
        loader: ClassLoader,
    ): HookInstallation =
        HookRegistrar(module, logger, "OriginOsDoubleFinger").install {
            val dispatcher = HookSupport.findClassOrNull(loader, "$STARTER_CLASS\$a")
            val runDispatch = dispatcher?.let { HookSupport.findMethod(it, "run") }
            val eventType = dispatcher?.let { HookSupport.findField(it, "c") }
            val gestureState = dispatcher?.let { HookSupport.findField(it, "b") }
            val isGestureActive = gestureState?.let { HookSupport.findMethod(it.type, "a") }
            val starter = dispatcher?.let { HookSupport.findField(it, "m") }
            val context = starter?.let { HookSupport.findField(it.type, "c") }
            if (runDispatch == null || runDispatch.returnType != Void.TYPE ||
                eventType == null || eventType.type != Int::class.javaPrimitiveType ||
                gestureState == null || isGestureActive == null || isGestureActive.returnType != Boolean::class.javaPrimitiveType ||
                starter == null || starter.type.name != STARTER_CLASS ||
                context == null || context.type != Context::class.java
            ) {
                missing(HOOK_ID, DESCRIPTION, "OriginOS: 双指长按分发签名不匹配")
                return@install
            }

            val searchGesture = OriginOsContextualSearch.Gesture.DOUBLE_FINGER
            // 系统在排程前已完成前台窗口和场景校验。引导和取消事件继续走原分发。
            intercept(HOOK_ID, runDispatch, DESCRIPTION) { chain ->
                if (!Prefs.isEnabled(searchGesture.preferenceKey)) return@intercept chain.proceed()
                val dispatch = chain.getThisObject()
                if (eventType.getInt(dispatch) != ACTIVE_DOUBLE_FINGER_EVENT ||
                    isGestureActive.invoke(gestureState.get(dispatch)) != true
                ) {
                    return@intercept chain.proceed()
                }

                val dispatchContext = context.get(starter.get(dispatch)) as Context
                if (OriginOsContextualSearch.tryStart(dispatchContext, searchGesture, this.logger)) null else chain.proceed()
            }
        }
}
