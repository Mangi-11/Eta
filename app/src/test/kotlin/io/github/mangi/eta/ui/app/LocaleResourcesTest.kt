package io.github.mangi.eta.ui.app

import android.content.Context
import android.content.res.Configuration
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.subagent.SubagentProfile
import io.github.mangi.eta.core.AppStrings
import io.github.mangi.eta.data.provider.BuiltinProviders
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import io.github.mangi.eta.ui.state.description

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LocaleResourcesTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @After
    fun tearDown() {
        AppStrings.resetForTest()
    }

    @Test
    fun supportedLocalesSelectExpectedResourcesAndOthersFallBackToEnglish() {
        assertEquals("Settings", localizedString("en-US", R.string.route_settings))
        assertEquals("设置", localizedString("zh-CN", R.string.route_settings))
        assertEquals("设置", localizedString("zh-SG", R.string.route_settings))
        assertEquals("設定", localizedString("zh-TW", R.string.route_settings))
        assertEquals("設定", localizedString("zh-HK", R.string.route_settings))
        assertEquals("昨日", localizedString("zh-TW", R.string.time_yesterday))
        assertEquals("Settings", localizedString("fr-FR", R.string.route_settings))
        assertEquals("Appearance & Theme", localizedString("en-US", R.string.appearance_title))
        assertEquals("外观与主题", localizedString("zh-CN", R.string.appearance_title))
        assertEquals("外觀與主題", localizedString("zh-TW", R.string.appearance_title))
        assertEquals("Appearance & Theme", localizedString("fr-FR", R.string.appearance_title))
        assertEquals("1 model", localizedQuantity("en-US", R.plurals.provider_models_count, 1))
        assertEquals("2 models", localizedQuantity("en-US", R.plurals.provider_models_count, 2))
    }

    @Test
    fun supplementalUiResourcesFollowLocaleWhileTechnicalTermsStayEnglish() {
        assertEquals("Current device", localizedString("en-US", R.string.capability_current_device))
        assertEquals("当前设备", localizedString("zh-CN", R.string.capability_current_device))
        assertEquals("目前裝置", localizedString("zh-TW", R.string.capability_current_device))
        assertEquals("Current device", localizedString("fr-FR", R.string.capability_current_device))
        assertEquals("Eta is running", localizedString("en-US", R.string.execution_title))
        assertEquals("Eta 正在运行", localizedString("zh-CN", R.string.execution_title))
        assertEquals("Eta 正在執行", localizedString("zh-TW", R.string.execution_title))
        assertEquals("Environment configuration", localizedString("en-US", R.string.linux_environment_configuration))
        assertEquals("环境配置", localizedString("zh-CN", R.string.linux_environment_configuration))
        assertEquals("環境設定", localizedString("zh-TW", R.string.linux_environment_configuration))
        assertEquals("View description", localizedString("en-US", R.string.ui_view_description))
        assertEquals("查看说明", localizedString("zh-CN", R.string.ui_view_description))
        assertEquals("查看說明", localizedString("zh-TW", R.string.ui_view_description))
        assertEquals("Retrying model request", localizedString("en-US", R.string.system_notice_model_retry))
        assertEquals("模型请求重试", localizedString("zh-CN", R.string.system_notice_model_retry))
        assertEquals("模型請求重試", localizedString("zh-TW", R.string.system_notice_model_retry))
        assertEquals("Auto-generate titles", localizedString("en-US", R.string.conversation_title_settings_title))
        assertEquals("自动生成对话标题", localizedString("zh-CN", R.string.conversation_title_settings_title))
        assertEquals("自動產生對話標題", localizedString("zh-TW", R.string.conversation_title_settings_title))
        assertEquals("Floating assistant", localizedString("en-US", R.string.assistant_overlay_settings_title))
        assertEquals("全局浮窗助手", localizedString("zh-CN", R.string.assistant_overlay_settings_title))
        assertEquals("全域浮窗助手", localizedString("zh-TW", R.string.assistant_overlay_settings_title))
        assertEquals(
            "1 task is running. Return to Eta to check progress.",
            localizedQuantity("en-US", R.plurals.execution_summary, 1),
        )
        assertEquals(
            "2 tasks are running. Return to Eta to check progress.",
            localizedQuantity("en-US", R.plurals.execution_summary, 2),
        )
        assertEquals(
            "已导入 1 个文件，其余文件无法读取或超过 32 MiB",
            localizedQuantity("zh-CN", R.plurals.capability_workspace_partial_import, 1),
        )
        assertEquals(
            "已匯入 2 個檔案，其餘檔案無法讀取或超過 32 MiB",
            localizedQuantity("zh-TW", R.plurals.capability_workspace_partial_import, 2),
        )
        for (locale in listOf("en-US", "zh-CN", "zh-TW", "fr-FR")) {
            assertEquals("Skills", localizedString(locale, R.string.route_skills))
            assertEquals("API Key", localizedString(locale, R.string.speech_api_key))
        }
    }

    @Test
    fun externalizedModuleResourcesAndDynamicResolversFollowLocale() {
        AppStrings.init(context)

        assertEquals("Quick start", localizedString("en-US", R.string.scheduled_section_quick_start))
        assertEquals("快速开始", localizedString("zh-CN", R.string.scheduled_section_quick_start))
        assertEquals("快速開始", localizedString("zh-TW", R.string.scheduled_section_quick_start))

        assertEquals("Characters", localizedString("en-US", R.string.character_library_title))
        assertEquals("角色", localizedString("zh-CN", R.string.character_library_title))
        assertEquals("角色", localizedString("zh-TW", R.string.character_library_title))

        assertEquals("Add from models.dev", localizedString("en-US", R.string.catalog_i18n_title))
        assertEquals("从 models.dev 添加", localizedString("zh-CN", R.string.catalog_i18n_title))
        assertEquals("從 models.dev 新增", localizedString("zh-TW", R.string.catalog_i18n_title))

        assertEquals("Search the web", localizedString("en-US", R.string.trace_arg_web_search))
        assertEquals("搜索网页", localizedString("zh-CN", R.string.trace_arg_web_search))
        assertEquals("搜尋網頁", localizedString("zh-TW", R.string.trace_arg_web_search))

        assertEquals("Eta is listening", localizedString("en-US", R.string.voice_i18n_fg_listening))
        assertEquals("Eta 正在聆听", localizedString("zh-CN", R.string.voice_i18n_fg_listening))
        assertEquals("Eta 正在聆聽", localizedString("zh-TW", R.string.voice_i18n_fg_listening))

        assertEquals("Background command completed", localizedString("en-US", R.string.execution_bg_cmd_completed))
        assertEquals("后台命令已完成", localizedString("zh-CN", R.string.execution_bg_cmd_completed))
        assertEquals("背景指令已完成", localizedString("zh-TW", R.string.execution_bg_cmd_completed))

        // 验证动态解析器随语言切换实时更新，不在伴生对象或默认列表中静态固化。
        applyLocale("en-US")
        assertEquals(
            context.getString(R.string.subagent_i18n_explore_summary),
            SubagentProfile.EXPLORE.localizedSummary(),
        )
        assertEquals("Alibaba Bailian", BuiltinProviders.localizedDisplayName(BuiltinProviders.BAILIAN_ID, "阿里百炼"))
        assertEquals("Custom Bailian", BuiltinProviders.localizedDisplayName(BuiltinProviders.BAILIAN_ID, "Custom Bailian"))

        applyLocale("zh-CN")
        assertEquals("只读探索与多源信息检索", SubagentProfile.EXPLORE.localizedSummary())
        assertEquals("阿里百炼", BuiltinProviders.localizedDisplayName(BuiltinProviders.BAILIAN_ID, "阿里百炼"))

        applyLocale("zh-TW")
        assertEquals(
            context.getString(R.string.subagent_i18n_explore_summary),
            SubagentProfile.EXPLORE.localizedSummary(),
        )
        assertEquals(
            context.getString(R.string.provider_i18n_builtin_bailian),
            BuiltinProviders.localizedDisplayName(BuiltinProviders.BAILIAN_ID, "阿里百炼"),
        )
    }

    @Suppress("DEPRECATION")
    private fun applyLocale(languageTag: String) {
        val resources = context.resources
        val configuration = Configuration(resources.configuration).apply {
            setLocale(Locale.forLanguageTag(languageTag))
        }
        resources.updateConfiguration(configuration, resources.displayMetrics)
    }

    private fun localizedString(languageTag: String, resourceId: Int): String {
        applyLocale(languageTag)
        return context.resources.getString(resourceId)
    }

    private fun localizedQuantity(languageTag: String, resourceId: Int, quantity: Int): String {
        applyLocale(languageTag)
        return context.resources.getQuantityString(resourceId, quantity, quantity)
    }
}
