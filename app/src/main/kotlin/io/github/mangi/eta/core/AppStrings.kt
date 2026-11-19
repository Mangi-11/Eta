package io.github.mangi.eta.core

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import java.util.Locale

/**
 * 供非 Compose 层（Runtime、Formatter、Repository、Store 等）按当前应用语言解析字符串资源。
 *
 * - Android 运行时与 Robolectric 测试通过 [init] 绑定的 Application Context 实时查询 Resources，
 *   跟随 Per-App Language 变更，不缓存已解析文案。
 * - 纯 JVM 单元测试未初始化 Android Context 时回退到传入的 [fallback] 模板，
 *   以保持既有纯 JVM 单元测试的行为契约不变。
 * - Compose 界面层应直接使用 `stringResource` / `pluralStringResource`，不要绕过组合期配置依赖调用本对象。
 */
internal object AppStrings {
    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext ?: context
    }

    internal fun resetForTest() {
        appContext = null
    }

    fun get(@StringRes resId: Int, fallback: String, vararg args: Any): String {
        val ctx = appContext
        if (ctx != null) {
            return runCatching {
                if (args.isEmpty()) ctx.getString(resId) else ctx.getString(resId, *args)
            }.getOrElse { formatFallback(fallback, *args) }
        }
        return formatFallback(fallback, *args)
    }

    fun quantity(
        @PluralsRes resId: Int,
        quantity: Int,
        fallbackOne: String,
        fallbackOther: String,
        vararg args: Any,
    ): String {
        val ctx = appContext
        if (ctx != null) {
            return runCatching {
                if (args.isEmpty()) {
                    ctx.resources.getQuantityString(resId, quantity)
                } else {
                    ctx.resources.getQuantityString(resId, quantity, *args)
                }
            }.getOrElse {
                val template = if (quantity == 1) fallbackOne else fallbackOther
                formatFallback(template, *args)
            }
        }
        val template = if (quantity == 1) fallbackOne else fallbackOther
        return formatFallback(template, *args)
    }

    private fun formatFallback(template: String, vararg args: Any): String {
        if (args.isEmpty()) return template
        return runCatching { String.format(Locale.US, template, *args) }.getOrDefault(template)
    }
}
