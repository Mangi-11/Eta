package fuck.andes

internal object ModuleConfig {
    const val TAG = "FuckAndes"
    const val ENABLE_VERBOSE_LOGS = false
    const val HOT_PATH_LOG_WINDOW_MS = 60_000L

    const val GOOGLE_PACKAGE = "com.google.android.googlequicksearchbox"
    const val GOOGLE_ASSISTANT_COMPONENT =
        "com.google.android.googlequicksearchbox/com.google.android.voiceinteraction.GsaVoiceInteractionService"
    const val ASSISTANT_ROLE = "android.app.role.ASSISTANT"
    const val SECURE_ASSISTANT = "assistant"
    const val SECURE_VOICE_INTERACTION_SERVICE = "voice_interaction_service"
    const val SYSTEM_UI_PACKAGE = "com.android.systemui"
    const val COLOR_DIRECT_PACKAGE = "com.coloros.colordirectservice"

    const val CONTEXTUAL_SEARCH_ACTION = "android.app.contextualsearch.action.LAUNCH_CONTEXTUAL_SEARCH"
    const val CONTEXTUAL_SEARCH_SERVICE = "contextual_search"
    const val CONTEXTUAL_SEARCH_CLASS =
        "com.android.server.contextualsearch.ContextualSearchManagerService"
    const val TIMINGS_TRACE_AND_SLOG_CLASS = "com.android.server.utils.TimingsTraceAndSlog"
    const val VOICE_INTERACTION_SERVICE = "voiceinteraction"
    const val VOICE_INTERACTION_MANAGER_SERVICE_CLASS =
        "com.android.server.voiceinteraction.VoiceInteractionManagerService"
    const val OCR_BUSINESS_CLASS =
        "com.oplus.systemui.navigationbar.ocrscreen.OplusOcrScreenBusiness"
    const val COLOR_DIRECT_COLLECT_ACTIVITY_CLASS =
        "com.coloros.directui.ui.CollectInfoActivity"
    const val COLOR_DIRECT_START_INFO_CLASS =
        "com.oplus.infocollection.data.CollectionStartInfo"
    const val SYSTEM_SERVER_CLASS = "com.android.server.SystemServer"
    const val PHONE_WINDOW_MANAGER_CLASS = "com.android.server.policy.PhoneWindowManager"
    const val OP_LUS_SPEECH_HANDLER_CLASS =
        "com.android.server.policy.PhoneWindowManagerExtImpl\$OplusSpeechHandler"

    const val CIRCLE_TO_SEARCH_ENTRYPOINT = 2
    const val COLOR_DIRECT_EXTRA_START_INFO = "startInfo"
    const val COLOR_DIRECT_EXTRA_DIRECT_EXT = "directExt"
    const val COLOR_DIRECT_DOUBLE_FINGER_COUNT = 2
    const val OP_LUS_ASSIST_MESSAGE_WHAT = 0x3F3
    const val INTERCEPT_DEDUP_WINDOW_MS = 1_000L
    const val GLOBAL_NEED_DISABLE_ASSISTANT_ALIVE = "need_disable_assistant_alive"

    const val SPOOF_MANUFACTURER = "samsung"
    const val SPOOF_BRAND = "samsung"
    const val SPOOF_MODEL = "SM-S928B"
    const val SPOOF_PRODUCT = "e3s"
    const val SPOOF_DEVICE = "e3s"
}
