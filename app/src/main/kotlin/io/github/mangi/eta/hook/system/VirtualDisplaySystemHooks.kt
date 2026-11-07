package io.github.mangi.eta.hook.system

import android.hardware.display.VirtualDisplayConfig
import android.os.Binder
import android.os.Build
import android.view.Display
import androidx.annotation.RequiresApi
import io.github.libxposed.api.XposedModule
import io.github.mangi.eta.agent.virtualdisplay.CanvasRoutingPolicy
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.core.HookInstallation
import io.github.mangi.eta.core.HookRegistrar
import io.github.mangi.eta.core.HookSupport
import io.github.mangi.eta.core.ModuleConfig
import io.github.mangi.eta.core.ModuleLogger
import java.util.concurrent.ConcurrentHashMap

/**
 * 在 system_server 为 Eta 自身的后台虚拟屏授予 SYSTEM_UID 所有权、受信任显示标志、
 * 隐藏软键盘策略（DISPLAY_IME_POLICY_HIDE）与跨应用 Activity 启动放行。
 *
 * 严格限制调用方包名与虚拟屏名称（EtaAgentVirtualDisplay），不对其他应用开放权限。
 */
internal object VirtualDisplaySystemHooks {

    private const val DMS_CLASS = "com.android.server.display.DisplayManagerService"
    private const val DMS_BINDER_CLASS = "com.android.server.display.DisplayManagerService\$BinderService"
    private const val ATMS_CLASS = "com.android.server.wm.ActivityTaskManagerService"
    private const val ATS_CLASS = "com.android.server.wm.ActivityTaskSupervisor"

    private val activeEtaDisplayCreation = ThreadLocal.withInitial { false }
    private val activeEtaDisplayIds = ConcurrentHashMap.newKeySet<Int>()

    fun install(
        module: XposedModule,
        rootLogger: ModuleLogger,
        classLoader: ClassLoader,
    ): HookInstallation {
        val hooks = HookRegistrar(module, rootLogger, "VirtualDisplay")
        return hooks.install {
            installDisplayManagerHooks(hooks, classLoader)
            installCrossAppLaunchHooks(hooks, classLoader)
            installTaskMoveHooks(hooks, classLoader)
        }
    }

    private fun installDisplayManagerHooks(hooks: HookRegistrar, classLoader: ClassLoader) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return
        }
        val dmsClass = HookSupport.findClassOrNull(classLoader, DMS_CLASS)
        val binderClass = HookSupport.findClassOrNull(classLoader, DMS_BINDER_CLASS)
        val createMethod = binderClass?.let { clazz ->
            HookSupport.findDeclaredMethods(clazz, makeAccessible = true) { method ->
                method.name == "createVirtualDisplay" &&
                    method.parameterCount >= 4 &&
                    method.parameterTypes[0] == VirtualDisplayConfig::class.java &&
                    method.parameterTypes[3] == String::class.java
            }.firstOrNull()
        }
        if (dmsClass == null || createMethod == null) {
            hooks.missing(
                id = "system.virtual-display-create",
                description = "DisplayManagerService.createVirtualDisplay",
                detail = "未找到 DisplayManagerService\$BinderService.createVirtualDisplay",
            )
            return
        }

        hooks.intercept(
            id = "system.virtual-display-create",
            executable = createMethod,
            description = "DisplayManagerService\$BinderService.createVirtualDisplay",
        ) { chain ->
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
                !Prefs.isEnabled(Prefs.Keys.AGENT_VIRTUAL_DISPLAY_ENABLED)
            ) {
                return@intercept chain.proceed()
            }
            val args = chain.args.toMutableList()
            val config = args.getOrNull(0) as? VirtualDisplayConfig
            val packageName = args.getOrNull(3) as? String
            val isEta = (packageName == ModuleConfig.ETA_PACKAGE || packageName == "android") &&
                config != null &&
                config.name == CanvasRoutingPolicy.VIRTUAL_DISPLAY_NAME
            if (!isEta) {
                return@intercept chain.proceed()
            }
            val upgraded = upgradeVirtualDisplayConfig(config) ?: config
            args[0] = upgraded
            args[3] = "android"
            activeEtaDisplayCreation.set(true)
            val identityToken = Binder.clearCallingIdentity()
            try {
                val result = chain.proceed(args.toTypedArray())
                if (result is Int && CanvasRoutingPolicy.isVirtualDisplay(result)) {
                    activeEtaDisplayIds.add(result)
                    applyDisplayImePolicyHide(result)
                }
                result
            } finally {
                Binder.restoreCallingIdentity(identityToken)
                activeEtaDisplayCreation.set(false)
            }
        }

        val checkPermMethod = HookSupport.findDeclaredMethods(dmsClass, makeAccessible = true) { method ->
            method.name == "checkCallingPermission" &&
                method.parameterCount == 2 &&
                method.parameterTypes[0] == String::class.java &&
                method.parameterTypes[1] == String::class.java &&
                method.returnType == Boolean::class.javaPrimitiveType
        }.firstOrNull()

        if (checkPermMethod != null) {
            hooks.intercept(
                id = "system.virtual-display-permission",
                executable = checkPermMethod,
                description = "DisplayManagerService.checkCallingPermission",
            ) { chain ->
                if (activeEtaDisplayCreation.get() == true) {
                    true
                } else {
                    chain.proceed()
                }
            }
        }
    }

    /**
     * 放行虚拟屏内部的跨应用 Activity 启动（App A -> App B），避免 ActivityTaskSupervisor 因跨 UID 拦截导致崩溃。
     */
    private fun installCrossAppLaunchHooks(hooks: HookRegistrar, classLoader: ClassLoader) {
        val atsClass = HookSupport.findClassOrNull(classLoader, ATS_CLASS) ?: return
        val canPlaceMethods = HookSupport.findDeclaredMethods(atsClass, makeAccessible = true) { method ->
            method.name == "canPlaceEntityOnDisplay" &&
                method.parameterCount >= 3 &&
                method.parameterTypes[0] == Int::class.javaPrimitiveType &&
                method.returnType == Boolean::class.javaPrimitiveType
        }
        canPlaceMethods.forEachIndexed { index, method ->
            hooks.intercept(
                id = if (index == 0) "system.virtual-display-can-place" else "system.virtual-display-can-place-$index",
                executable = method,
                description = "ActivityTaskSupervisor.canPlaceEntityOnDisplay#$index",
            ) { chain ->
                val displayId = chain.args.getOrNull(0) as? Int ?: Display.DEFAULT_DISPLAY
                if (displayId in activeEtaDisplayIds) {
                    true
                } else {
                    chain.proceed()
                }
            }
        }
    }

    private fun applyDisplayImePolicyHide(displayId: Int) {
        runCatching {
            val wmgClass = Class.forName("android.view.WindowManagerGlobal")
            val wms = wmgClass.getMethod("getWindowManagerService").invoke(null) ?: return
            val setPolicy = wms.javaClass.methods.firstOrNull {
                it.name == "setDisplayImePolicy" && it.parameterCount == 2
            } ?: return
            setPolicy.invoke(wms, displayId, CanvasRoutingPolicy.DISPLAY_IME_POLICY_HIDE)
        }
    }

    @android.annotation.SuppressLint("WrongConstant")
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun upgradeVirtualDisplayConfig(config: VirtualDisplayConfig): VirtualDisplayConfig? {
        if (config.name != CanvasRoutingPolicy.VIRTUAL_DISPLAY_NAME) return null
        return runCatching {
            VirtualDisplayConfig.Builder(
                config.name,
                config.width,
                config.height,
                config.densityDpi,
            ).apply {
                config.surface?.let(::setSurface)
                setFlags(config.flags or CanvasRoutingPolicy.TRUSTED_INTERACTIVE_FLAGS)
                if (config.requestedRefreshRate > 0f) {
                    setRequestedRefreshRate(config.requestedRefreshRate)
                }
            }.build()
        }.getOrDefault(config)
    }

    private fun installTaskMoveHooks(hooks: HookRegistrar, classLoader: ClassLoader) {
        val atmsClass = HookSupport.findClassOrNull(classLoader, ATMS_CLASS) ?: return
        val moveMethod = HookSupport.findMethod(
            atmsClass,
            "moveRootTaskToDisplay",
            Int::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!,
        ) ?: return

        hooks.intercept(
            id = "system.virtual-display-move-task",
            executable = moveMethod,
            description = "ActivityTaskManagerService.moveRootTaskToDisplay",
        ) { chain ->
            val target = chain.thisObject
            val context = SystemServerContextResolver.resolve(target)
            val callingUid = Binder.getCallingUid()
            val isEtaCaller = context != null && runCatching {
                context.packageManager.getPackagesForUid(callingUid)
                    ?.any { it == ModuleConfig.ETA_PACKAGE } == true
            }.getOrDefault(false)
            if (!isEtaCaller) {
                return@intercept chain.proceed()
            }
            val token = Binder.clearCallingIdentity()
            try {
                chain.proceed()
            } finally {
                Binder.restoreCallingIdentity(token)
            }
        }
    }
}
