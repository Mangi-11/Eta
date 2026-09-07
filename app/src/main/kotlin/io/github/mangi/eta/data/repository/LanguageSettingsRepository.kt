package io.github.mangi.eta.data.repository

import android.app.LocaleConfig
import android.app.LocaleManager
import android.content.Context
import android.os.LocaleList
import java.util.Locale

class LanguageSettingsRepository(context: Context) {
    private val localeManager = requireNotNull(
        context.applicationContext.getSystemService(LocaleManager::class.java),
    )

    val supportedLocales: List<Locale> = requireNotNull(
        LocaleConfig(context.applicationContext).supportedLocales,
    ).let { locales -> List(locales.size()) { locales[it] } }

    fun selectedLocale(): Locale? = localeManager.applicationLocales
        .takeIf { !it.isEmpty }
        ?.get(0)

    fun selectLocale(locale: Locale?) {
        require(locale == null || locale in supportedLocales)
        val locales = if (locale == null) LocaleList.getEmptyLocaleList() else LocaleList(locale)
        // 系统统一保存应用语言并分发配置变更，避免与系统设置形成两份偏好。
        if (localeManager.applicationLocales != locales) {
            localeManager.applicationLocales = locales
        }
    }
}
