package fuck.andes.config

import android.content.SharedPreferences
import io.github.libxposed.service.XposedService

/**
 * 模块配置中枢。
 *
 * - Hook 进程（system_server / SystemUI / Google / ColorDirect）在模块加载时调用
 *   [attachRemote]，缓存框架提供的只读 [SharedPreferences]；之后所有拦截回调用 [isEnabled]
 *   读取当前进程持有的 remote preferences。
 * - UI 进程（模块自身）通过 [remotePreferencesForUi] 拿到可写的 [SharedPreferences]
 *   （XposedService.getRemotePreferences）。XposedService 未就绪时不提供本地 fallback，
 *   避免 UI 显示已修改但 hook 进程无法看到。
 *
 * 基于 libxposed API 102 的 [io.github.libxposed.api.XposedInterface.getRemotePreferences]
 * 与 service 102 的 [XposedService.getRemotePreferences]，两端共用同一 group。
 */
internal object Prefs {

    /** 远程配置组名，UI 写入与 Hook 读取必须一致。 */
    const val GROUP = "fuck_andes_prefs"

    /** 所有功能开关 key。默认值按功能风险独立定义。 */
    object Keys {
        const val POWER_KEY_TAKEOVER = "power_key_takeover"
        const val ASSISTANT_AUTO_CONFIG = "assistant_auto_config"
        const val HOTWORD_SELF_HEAL = "hotword_self_heal"
        const val GESTURE_BAR_CIRCLE_TO_SEARCH = "gesture_bar_circle_to_search"
        const val DOUBLE_FINGER_CIRCLE_TO_SEARCH = "double_finger_circle_to_search"
        const val LOCKSCREEN_VOICE_COMMAND = "lockscreen_voice_command"
        const val SCREEN_ON_VOICE_COMMAND = "screen_on_voice_command"
        const val AGENT_CUSTOM_MODEL = "agent_custom_model"
        const val AGENT_REQUIRE_PREFIX = "agent_require_prefix"
        const val AGENT_BASE_URL = "agent_base_url"
        const val AGENT_API_KEY = "agent_api_key"
        const val AGENT_MODEL = "agent_model"
        const val AGENT_SYSTEM_PROMPT = "agent_system_prompt"
        const val AGENT_TERMINAL_TOOLS = "agent_terminal_tools"
        const val AGENT_THINKING_ENABLED = "agent_thinking_enabled"
        const val AGENT_EXTRA_BODY_JSON = "agent_extra_body_json"

        /** 全部布尔开关及其默认值。 */
        val BOOLEAN_DEFAULTS: Map<String, Boolean> = mapOf(
            POWER_KEY_TAKEOVER to true,
            ASSISTANT_AUTO_CONFIG to true,
            HOTWORD_SELF_HEAL to true,
            GESTURE_BAR_CIRCLE_TO_SEARCH to true,
            DOUBLE_FINGER_CIRCLE_TO_SEARCH to true,
            LOCKSCREEN_VOICE_COMMAND to true,
            SCREEN_ON_VOICE_COMMAND to true,
            AGENT_CUSTOM_MODEL to false,
            AGENT_REQUIRE_PREFIX to true,
            AGENT_TERMINAL_TOOLS to false,
            AGENT_THINKING_ENABLED to false
        )

        /** 文本配置默认值。 */
        val STRING_DEFAULTS: Map<String, String> = mapOf(
            AGENT_BASE_URL to "https://api.openai.com/v1",
            AGENT_API_KEY to "",
            AGENT_MODEL to "gpt-4.1-mini",
            AGENT_SYSTEM_PROMPT to "你是运行在 Android 设备上的手机 Agent。回答要简洁、直接，并保留必要的操作上下文。",
            AGENT_EXTRA_BODY_JSON to ""
        )
    }

    /** Hook 进程缓存的只读 remote preferences，由 ModuleMain 在 onModuleLoaded 注入。 */
    @Volatile
    private var remote: SharedPreferences? = null

    /** Hook 进程调用：缓存框架提供的只读 SharedPreferences。 */
    fun attachRemote(prefs: SharedPreferences?) {
        remote = prefs
    }

    /**
     * 读取布尔开关。remote 不可用（框架未注入或调用失败）时回退默认值（全开），
     * 与历史行为一致。
     */
    fun isEnabled(key: String): Boolean {
        val default = Keys.BOOLEAN_DEFAULTS[key] ?: true
        return remote?.getBoolean(key, default) ?: default
    }

    fun getString(key: String): String {
        val default = Keys.STRING_DEFAULTS[key].orEmpty()
        return remote?.getString(key, default) ?: default
    }

    /**
     * UI 进程获取可写的 RemotePreferences。
     *
     * [XposedService.getRemotePreferences] 的 commit 会同步等待 binder 提交到 LSPosed
     * 数据库，失败返回 false；service 未就绪时返回 null，让 UI 保持不可写。
     */
    fun remotePreferencesForUi(service: XposedService?): SharedPreferences? =
        runCatching { service?.getRemotePreferences(GROUP) }.getOrNull()
}
