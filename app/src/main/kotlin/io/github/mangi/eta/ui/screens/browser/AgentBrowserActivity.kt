package io.github.mangi.eta.ui.screens.browser

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import io.github.mangi.eta.agent.browser.BrowserSessionRegistry
import io.github.mangi.eta.data.repository.AppearanceSettingsRepository
import io.github.mangi.eta.ui.app.AgentAppTheme
import kotlinx.coroutines.launch

/** WebView 保持在固定窗口内，进出动画由系统合成窗口，避免整页绘制裁剪介入网页渲染。 */
class AgentBrowserActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sessionKey = intent.getStringExtra(EXTRA_SESSION_KEY)?.takeIf { it.isNotBlank() }
        if (sessionKey == null) {
            finish()
            return
        }
        enableEdgeToEdge()
        val session = BrowserSessionRegistry.get(sessionKey)
        lifecycleScope.launch {
            val initialAppearance = AppearanceSettingsRepository.settings()
            setContent {
                val appearance by AppearanceSettingsRepository.settingsFlow().collectAsStateWithLifecycle(initialValue = initialAppearance)
                AgentAppTheme(
                    appearance = appearance,
                    provideGlassHost = true,
                    onResolvedDarkModeChange = { isDark ->
                        val style = if (isDark) {
                            SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                        } else {
                            SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                        }
                        enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                    },
                ) {
                    AgentBrowserScreen(session = session, onBack = ::finish)
                }
            }
        }
    }

    companion object {
        private const val EXTRA_SESSION_KEY = "io.github.mangi.eta.extra.BROWSER_SESSION_KEY"

        fun createIntent(context: Context, sessionKey: String): Intent {
            require(sessionKey.isNotBlank())
            return Intent(context, AgentBrowserActivity::class.java).putExtra(EXTRA_SESSION_KEY, sessionKey)
        }
    }
}
