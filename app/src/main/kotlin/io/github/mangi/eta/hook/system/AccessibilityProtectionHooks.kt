package io.github.mangi.eta.hook.system

import android.content.Context
import android.os.Handler
import io.github.mangi.eta.hook.support.HookInstallation
import io.github.mangi.eta.hook.support.HookRegistrar
import io.github.mangi.eta.hook.support.HookSupport
import io.github.mangi.eta.core.ModuleConfig
import io.github.mangi.eta.core.ModuleLogger
import io.github.libxposed.api.XposedModule

/**
 * 在 SystemServer 完成其他系统服务启动后接入无障碍保护。
 *
 * 当前 ColorOS 16 目标已由 contextual_search 启动链路验证
 * SystemServer.startOtherServices(TimingsTraceAndSlog) 的类名、签名与调用时机。
 */
internal object AccessibilityProtectionHooks {
    @Volatile
    private var enforcer: AccessibilityServiceEnforcer? = null

    fun install(
        module: XposedModule,
        rootLogger: ModuleLogger,
        classLoader: ClassLoader,
    ): HookInstallation {
        val hooks = HookRegistrar(module, rootLogger, "AccessibilityProtection")
        val logger = hooks.logger
        return hooks.install {
            val systemServerClass = HookSupport.findClassOrNull(
                classLoader,
                ModuleConfig.SYSTEM_SERVER_CLASS,
            )
            val timingsClass = HookSupport.findClassOrNull(
                classLoader,
                ModuleConfig.TIMINGS_TRACE_AND_SLOG_CLASS,
            )
            val startOtherServices = if (systemServerClass != null && timingsClass != null) {
                HookSupport.findMethod(systemServerClass, "startOtherServices", timingsClass)
            } else {
                null
            }
            if (startOtherServices == null) {
                hooks.missing(
                    id = "system.accessibility-protection",
                    description = "SystemServer.startOtherServices",
                    detail = "未找到 SystemServer.startOtherServices(TimingsTraceAndSlog)",
                )
                return@install
            }

            hooks.intercept(
                id = "system.accessibility-protection",
                executable = startOtherServices,
                description = "SystemServer.startOtherServices",
            ) { chain ->
                val result = chain.proceed()
                startEnforcer(
                    systemServer = chain.getThisObject(),
                    classLoader = classLoader,
                    logger = logger,
                )
                result
            }
        }
    }

    @Synchronized
    private fun startEnforcer(
        systemServer: Any,
        classLoader: ClassLoader,
        logger: ModuleLogger,
    ) {
        if (enforcer != null) return
        val context = SystemServerContextResolver.resolve(systemServer)
        if (context == null) {
            logger.warn("SystemServer 已启动，但无法取得 system context")
            return
        }
        val handler = resolveSystemBackgroundHandler(classLoader)
        if (handler == null) {
            logger.warn("无法取得 Android BackgroundThread，跳过无障碍保护")
            return
        }
        AccessibilityServiceEnforcer(
            handler = handler,
            logger = logger,
        ).also {
            enforcer = it
            it.start(context)
        }
    }

    /**
     * Android 37 源码中的 com.android.internal.os.BackgroundThread 是每进程共享线程；
     * 复用它可以让组件校验和 Settings I/O 离开 system_server 主线程，同时不创建模块线程。
     */
    private fun resolveSystemBackgroundHandler(classLoader: ClassLoader): Handler? =
        runCatching {
            val backgroundThreadClass = HookSupport.findClassOrNull(
                classLoader,
                "com.android.internal.os.BackgroundThread",
            ) ?: return@runCatching null
            val getHandler = HookSupport.findMethod(backgroundThreadClass, "getHandler")
                ?: return@runCatching null
            getHandler.invoke(null) as? Handler
        }.getOrNull()
}

internal object SystemServerContextResolver {
    private val contextFieldNames = arrayOf("mSystemContext", "mContext")
    private val contextMethodNames = arrayOf("getSystemContext", "getContext")

    fun resolve(owner: Any?): Context? {
        contextFromOwner(owner)?.let { return it }
        return runCatching {
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val currentThread = activityThreadClass
                .getDeclaredMethod("currentActivityThread")
                .apply { isAccessible = true }
                .invoke(null)
                ?: return@runCatching null
            activityThreadClass
                .getDeclaredMethod("getSystemContext")
                .apply { isAccessible = true }
                .invoke(currentThread) as? Context
        }.getOrNull()
    }

    private fun contextFromOwner(owner: Any?): Context? {
        if (owner == null) return null
        contextFieldNames.forEach { name ->
            findField(owner.javaClass, name)?.let { field ->
                runCatching {
                    field.isAccessible = true
                    field.get(owner) as? Context
                }.getOrNull()?.let { return it }
            }
        }
        contextMethodNames.forEach { name ->
            findMethod(owner.javaClass, name)?.let { method ->
                runCatching {
                    method.isAccessible = true
                    method.invoke(owner) as? Context
                }.getOrNull()?.let { return it }
            }
        }
        return null
    }

    private fun findField(type: Class<*>, name: String): java.lang.reflect.Field? {
        var current: Class<*>? = type
        while (current != null) {
            val candidate = current
            runCatching { candidate.getDeclaredField(name) }.getOrNull()?.let { return it }
            current = candidate.superclass
        }
        return null
    }

    private fun findMethod(type: Class<*>, name: String): java.lang.reflect.Method? {
        var current: Class<*>? = type
        while (current != null) {
            val candidate = current
            runCatching { candidate.getDeclaredMethod(name) }.getOrNull()?.let { return it }
            current = candidate.superclass
        }
        return null
    }
}
