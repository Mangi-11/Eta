package io.github.mangi.eta.hook.google

import android.os.Build
import io.github.libxposed.api.XposedModule
import io.github.mangi.eta.core.HookInstallation
import io.github.mangi.eta.core.HookRegistrar
import io.github.mangi.eta.core.ModuleConfig
import io.github.mangi.eta.core.ModuleLogger
import io.github.mangi.eta.core.safeLogType
import java.lang.reflect.Field

internal object GoogleAppHooks {

    fun install(
        module: XposedModule,
        rootLogger: ModuleLogger,
        @Suppress("UNUSED_PARAMETER") classLoader: ClassLoader,
    ): HookInstallation {
        val hooks = HookRegistrar(module, rootLogger, "GoogleApp")
        val logger = hooks.logger
        return hooks.install {
            // 机型伪装：在 Google 进程内伪装为 Samsung S24 Ultra，以放开一圈即搜能力。
            // Build 字段是启动时一次性写入的副作用，作为一圈即搜的底层依赖始终执行。
            setBuildField(logger, Build::class.java, "MANUFACTURER", ModuleConfig.SPOOF_MANUFACTURER)
            setBuildField(logger, Build::class.java, "BRAND", ModuleConfig.SPOOF_BRAND)
            setBuildField(logger, Build::class.java, "MODEL", ModuleConfig.SPOOF_MODEL)
            setBuildField(logger, Build::class.java, "PRODUCT", ModuleConfig.SPOOF_PRODUCT)
            setBuildField(logger, Build::class.java, "DEVICE", ModuleConfig.SPOOF_DEVICE)
        }
    }

    private fun setBuildField(
        logger: ModuleLogger,
        clazz: Class<*>,
        fieldName: String,
        value: String,
    ) {
        val field = runCatching {
            clazz.getDeclaredField(fieldName).apply { isAccessible = true }
        }.getOrElse { throwable ->
            logger.warn("GSA: 找不到 Build.$fieldName，type=${throwable.safeLogType()}")
            return
        }

        try {
            try {
                field.set(null, value)
            } catch (_: IllegalAccessException) {
                // Android 17 禁止反射写入这些静态 final 字段；静态字段 API 位于内部 Unsafe。
                val unsafeClass = Class.forName("jdk.internal.misc.Unsafe")
                val theUnsafe = unsafeClass.getDeclaredField("theUnsafe").apply {
                    isAccessible = true
                }.get(null)
                val base = unsafeClass.getDeclaredMethod("staticFieldBase", Field::class.java)
                    .invoke(theUnsafe, field)
                val offset = unsafeClass.getDeclaredMethod("staticFieldOffset", Field::class.java)
                    .invoke(theUnsafe, field) as Long
                unsafeClass.getDeclaredMethod(
                    "putReferenceVolatile",
                    Any::class.java,
                    Long::class.javaPrimitiveType!!,
                    Any::class.java,
                ).invoke(theUnsafe, base, offset, value)
            }
            check(field.get(null) == value) { "build_field_write_not_applied" }
        } catch (exception: Exception) {
            logger.warn("GSA: 修改 Build.$fieldName 失败，type=${exception.safeLogType()}")
        }
    }
}
