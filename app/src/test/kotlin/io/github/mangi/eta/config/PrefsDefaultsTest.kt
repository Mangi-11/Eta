package io.github.mangi.eta.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PrefsDefaultsTest {
    @Test
    fun defaultsMatchRecommendedInitialSettings() {
        assertEquals(
            mapOf(
                Prefs.Keys.POWER_KEY_TAKEOVER to false,
                Prefs.Keys.ASSISTANT_AUTO_CONFIG to false,
                Prefs.Keys.HOTWORD_SELF_HEAL to false,
                Prefs.Keys.GESTURE_BAR_CIRCLE_TO_SEARCH to true,
                Prefs.Keys.DOUBLE_FINGER_CIRCLE_TO_SEARCH to false,
                Prefs.Keys.LOCKSCREEN_VOICE_COMMAND to false,
                Prefs.Keys.SCREEN_ON_VOICE_COMMAND to false,
                Prefs.Keys.AGENT_CUSTOM_MODEL to true,
                Prefs.Keys.AGENT_REQUIRE_PREFIX to false,
                Prefs.Keys.VIVO_CUSTOM_MODEL to true,
                Prefs.Keys.VIVO_REQUIRE_PREFIX to true,
                Prefs.Keys.AGENT_TERMINAL_TOOLS to true,
                Prefs.Keys.AGENT_BROWSER_TOOLS to true,
                Prefs.Keys.AGENT_DEVICE_DIRECT_TOOLS to true,
                Prefs.Keys.AGENT_DEVICE_SENSITIVE_READ_TOOLS to true,
                Prefs.Keys.AGENT_DEVICE_SENSITIVE_ACTION_TOOLS to true,
                Prefs.Keys.AGENT_THINKING_ENABLED to true,
                Prefs.Keys.AGENT_AUTO_COMPACTION_ENABLED to true,
            ),
            Prefs.Keys.BOOLEAN_DEFAULTS,
        )
        assertFalse(
            Prefs.Keys.BOOLEAN_DEFAULTS.containsKey(Prefs.Keys.POWER_KEY_ASSISTANT_TARGET),
        )
    }

    @Test
    fun localAgentKeysMatchRuntimeOwnedSettings() {
        assertEquals(
            setOf(
                Prefs.Keys.AGENT_TERMINAL_TOOLS,
                Prefs.Keys.AGENT_BROWSER_TOOLS,
                Prefs.Keys.AGENT_DEVICE_DIRECT_TOOLS,
                Prefs.Keys.AGENT_DEVICE_SENSITIVE_READ_TOOLS,
                Prefs.Keys.AGENT_DEVICE_SENSITIVE_ACTION_TOOLS,
                Prefs.Keys.AGENT_THINKING_ENABLED,
                Prefs.Keys.AGENT_AUTO_COMPACTION_ENABLED,
            ),
            Prefs.Keys.LOCAL_AGENT_KEYS,
        )
    }
}
