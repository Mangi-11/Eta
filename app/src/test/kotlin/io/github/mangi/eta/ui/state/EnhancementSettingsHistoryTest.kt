package io.github.mangi.eta.ui.state

import android.content.Context
import io.github.mangi.eta.config.PowerAssistantTarget
import io.github.mangi.eta.config.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class EnhancementSettingsHistoryTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Before
    fun clearHistory() {
        context.getSharedPreferences("eta_enhancement_ui_history", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun historyPreservesDisplayedValuesWithoutWritingBackToTheRemoteSource() {
        val source = context.getSharedPreferences("test_remote_snapshot", Context.MODE_PRIVATE)
        source.edit().clear()
            .putBoolean(Prefs.Keys.ASSISTANT_AUTO_CONFIG, true)
            .putString(Prefs.Keys.POWER_KEY_ASSISTANT_TARGET, PowerAssistantTarget.GEMINI.persistedValue)
            .commit()
        val history = EnhancementSettingsHistory(context)
        assertFalse(history.hasConnected)
        history.captureConnected(source)
        history.recordCommittedBoolean(Prefs.Keys.ASSISTANT_AUTO_CONFIG, false)
        val restored = EnhancementSettingsHistory(context)
        assertTrue(restored.hasConnected)
        assertFalse(restored.checked(Prefs.Keys.ASSISTANT_AUTO_CONFIG, true))
        assertEquals(PowerAssistantTarget.GEMINI, restored.powerTarget())
        assertTrue(source.getBoolean(Prefs.Keys.ASSISTANT_AUTO_CONFIG, false))
    }
}
