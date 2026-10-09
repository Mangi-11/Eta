package io.github.mangi.eta.ui

import io.github.mangi.eta.ui.voice.ACTION_SPEECH_SETTINGS
import android.app.UiModeManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import io.github.mangi.eta.agent.voice.EtaAssistantOverlayService
import io.github.mangi.eta.agent.display.VirtualScreenSession
import io.github.mangi.eta.agent.display.VirtualScreenViewerActivity
import io.github.mangi.eta.agent.runtime.AgentRuntimeWire
import io.github.mangi.eta.hook.vivo.VivoHandoff
import io.github.mangi.eta.data.model.AppearanceThemeMode
import io.github.mangi.eta.data.repository.AppearanceSettingsRepository
import io.github.mangi.eta.ui.app.AgentAppRoot
import io.github.mangi.eta.ui.app.AgentAppTheme
import io.github.mangi.eta.ui.app.PredictiveBackController
import io.github.mangi.eta.ui.app.installStartupSplash
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var assistantConversationKey by mutableStateOf<String?>(null)
    private var assistantConversationSource by mutableStateOf(AgentRuntimeWire.ETA_VOICE_HANDOFF_SOURCE)
    private var requestedConversationId by mutableStateOf<String?>(null)
    private var appliedPredictiveBackEnabled = true
    private var speechSettingsRequested by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        var contentReady = false
        installStartupSplash { contentReady }
        enableEdgeToEdge()
        updateAssistantHandoff(intent)
        lifecycleScope.launch {
            val initialAppearance = AppearanceSettingsRepository.settings()
            appliedPredictiveBackEnabled = initialAppearance.predictiveBackEnabled
            setContent {
                val appearance by AppearanceSettingsRepository.settingsFlow()
                    .collectAsState(initial = initialAppearance)

                LaunchedEffect(appearance.themeMode) {
                    updateApplicationNightMode(appearance.themeMode)
                }

                LaunchedEffect(appearance.predictiveBackEnabled) {
                    val enabled = appearance.predictiveBackEnabled
                    if (enabled != appliedPredictiveBackEnabled &&
                        PredictiveBackController.apply(applicationInfo, enabled)
                    ) {
                        appliedPredictiveBackEnabled = enabled
                        recreateWithoutTransition()
                    }
                }

                AgentAppTheme(
                    appearance = appearance,
                    applyInterfaceScale = true,
                    onResolvedDarkModeChange = ::updateSystemBars,
                ) {
                    AgentAppRoot(
                        assistantConversationKey = assistantConversationKey,
                        assistantConversationSource = assistantConversationSource,
                        requestedConversationId = requestedConversationId,
                        onRequestedConversationOpened = { requestedConversationId = null; intent?.action = null },
                        openSpeechSettings = speechSettingsRequested,
                        onSpeechSettingsOpened = { speechSettingsRequested = false; intent?.action = null },
                        onAssistantConversationOpened = { opened ->
                            assistantConversationKey = null
                            if (opened && assistantConversationSource == AgentRuntimeWire.ETA_VOICE_HANDOFF_SOURCE) {
                                EtaAssistantOverlayService.notifyHandoffReady(this@MainActivity)
                            }
                        },
                    )
                    io.github.mangi.eta.agent.display.VirtualScreenAppConflictDialog()
                }
            }
            contentReady = true
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        updateAssistantHandoff(intent)
    }

    private fun updateAssistantHandoff(intent: Intent?) {
        if (intent?.action == ACTION_VIEW_EXECUTION) {
            assistantConversationSource = intent.getStringExtra(EXTRA_EXECUTION_SOURCE)
                ?.takeIf { it in setOf(
                    VivoHandoff.SOURCE, AgentRuntimeWire.ETA_VOICE_HANDOFF_SOURCE,
                    AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE, "automation",
                ) } ?: AgentRuntimeWire.ETA_VOICE_HANDOFF_SOURCE
            assistantConversationKey = intent.getStringExtra(
                EtaAssistantOverlayService.EXTRA_CONVERSATION_KEY,
            )?.takeIf(String::isNotBlank)
            intent.action = null
            if (VirtualScreenSession.isActive()) {
                startActivity(Intent(this, VirtualScreenViewerActivity::class.java))
            }
            return
        }
        if (intent?.action == ACTION_SPEECH_SETTINGS) {
            speechSettingsRequested = true
            return
        }
        if (intent?.action == ACTION_OPEN_CONVERSATION_ID) {
            requestedConversationId = intent.getStringExtra(EXTRA_CONVERSATION_ID)?.takeIf(String::isNotBlank)
            return
        }
        if (intent?.action != EtaAssistantOverlayService.ACTION_OPEN_CONVERSATION) return
        assistantConversationSource = AgentRuntimeWire.ETA_VOICE_HANDOFF_SOURCE
        assistantConversationKey = intent.getStringExtra(
            EtaAssistantOverlayService.EXTRA_CONVERSATION_KEY,
        )?.takeIf(String::isNotBlank)
    }

    private fun updateApplicationNightMode(themeMode: AppearanceThemeMode) {
        val mode = when (themeMode) {
            AppearanceThemeMode.LIGHT -> UiModeManager.MODE_NIGHT_NO
            AppearanceThemeMode.DARK -> UiModeManager.MODE_NIGHT_YES
            // 应用级 AUTO 清除夜间模式覆盖，恢复跟随系统。
            AppearanceThemeMode.SYSTEM -> UiModeManager.MODE_NIGHT_AUTO
        }
        getSystemService(UiModeManager::class.java).setApplicationNightMode(mode)
    }

    companion object {
        const val ACTION_VIEW_EXECUTION = "io.github.mangi.eta.action.VIEW_EXECUTION"
        const val EXTRA_EXECUTION_SOURCE = "io.github.mangi.eta.extra.EXECUTION_SOURCE"
        /** 运行浮层的结果卡片回到本体：App 发起的 run 带会话 ID，直接打开该会话。 */
        const val ACTION_OPEN_CONVERSATION_ID = "io.github.mangi.eta.action.OPEN_CONVERSATION_ID"
        const val EXTRA_CONVERSATION_ID = "io.github.mangi.eta.extra.CONVERSATION_ID"
    }

    private fun updateSystemBars(isDark: Boolean) {
        val style = if (isDark) {
            SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        } else {
            SystemBarStyle.light(
                scrim = android.graphics.Color.TRANSPARENT,
                darkScrim = android.graphics.Color.TRANSPARENT,
            )
        }
        enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = !isDark
            isAppearanceLightNavigationBars = !isDark
        }
        window.decorView.post {
            WindowInsetsControllerCompat(window, window.decorView).apply {
                isAppearanceLightStatusBars = !isDark
                isAppearanceLightNavigationBars = !isDark
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun recreateWithoutTransition() {
        overridePendingTransition(0, 0)
        recreate()
        overridePendingTransition(0, 0)
    }

}
