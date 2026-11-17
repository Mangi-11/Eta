package io.github.mangi.eta.hook.system

import io.github.mangi.eta.config.PowerAssistantTarget
import io.github.mangi.eta.core.ModuleConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantBindingTest {
    @Test
    fun `OEM target has no managed assistant binding`() {
        assertNull(assistantBindingFor(PowerAssistantTarget.OEM))
        assertFalse(
            shouldConfigureAssistant(
                autoConfigEnabled = true,
                target = PowerAssistantTarget.OEM,
            ),
        )
    }

    @Test
    fun `Gemini and Eta use their own packages and components`() {
        val gemini = requireNotNull(assistantBindingFor(PowerAssistantTarget.GEMINI))
        val eta = requireNotNull(assistantBindingFor(PowerAssistantTarget.ETA))

        assertEquals(ModuleConfig.GOOGLE_PACKAGE, gemini.packageName)
        assertEquals(ModuleConfig.GOOGLE_ASSISTANT_COMPONENT, gemini.componentName)
        assertEquals(ModuleConfig.ETA_PACKAGE, eta.packageName)
        assertEquals(ModuleConfig.ETA_VOICE_INTERACTION_COMPONENT, eta.componentName)
        assertEquals(
            "io.github.mangi.eta/.agent.voice.EtaVoiceInteractionService",
            eta.shortComponentName,
        )
        assertEquals(ModuleConfig.GOOGLE_ASSISTANT_COMPONENT, gemini.shortComponentName)
        assertTrue(
            isSameComponentName(
                eta.componentName,
                "io.github.mangi.eta/.agent.voice.EtaVoiceInteractionService",
            ),
        )
        assertFalse(
            isSameComponentName(
                eta.componentName,
                gemini.componentName,
            ),
        )
    }

    @Test
    fun `automatic configuration requires enabled switch and unchanged target`() {
        assertTrue(
            isAssistantConfigurationCurrent(
                autoConfigEnabled = true,
                expectedTarget = PowerAssistantTarget.GEMINI,
                currentTarget = PowerAssistantTarget.GEMINI,
            ),
        )
        assertFalse(
            isAssistantConfigurationCurrent(
                autoConfigEnabled = false,
                expectedTarget = PowerAssistantTarget.GEMINI,
                currentTarget = PowerAssistantTarget.GEMINI,
            ),
        )
        assertFalse(
            isAssistantConfigurationCurrent(
                autoConfigEnabled = true,
                expectedTarget = PowerAssistantTarget.GEMINI,
                currentTarget = PowerAssistantTarget.ETA,
            ),
        )
    }

    @Test
    fun `preference changes configure managed targets and restore OEM`() {
        assertEquals(
            AssistantSelectionAction.CONFIGURE_MANAGED,
            assistantSelectionAction(true, PowerAssistantTarget.GEMINI),
        )
        assertEquals(
            AssistantSelectionAction.CONFIGURE_MANAGED,
            assistantSelectionAction(true, PowerAssistantTarget.ETA),
        )
        assertEquals(
            AssistantSelectionAction.NONE,
            assistantSelectionAction(false, PowerAssistantTarget.GEMINI),
        )
        assertEquals(
            AssistantSelectionAction.RESTORE_OEM,
            assistantSelectionAction(false, PowerAssistantTarget.OEM),
        )
    }

    @Test
    fun `configuration coordinator coalesces overlapping switches into a trailing resync`() {
        val coordinator = AssistantConfigurationCoordinator()

        assertTrue(coordinator.tryBegin(0))
        // 并发请求同一用户不会并行执行，但会标记结束后需要追平最新选择
        assertFalse(coordinator.tryBegin(0))
        assertFalse(coordinator.tryBegin(0))
        // 其他用户互不阻塞
        assertTrue(coordinator.tryBegin(10))
        assertFalse(coordinator.finish(10))

        assertTrue(coordinator.finish(0))
        assertTrue(coordinator.tryBegin(0))
        assertFalse(coordinator.finish(0))
    }

    @Test
    fun `assistant setting guard rewrites only xiaoai overwrites while auto config is enabled`() {
        assertEquals(
            "io.github.mangi.eta/.agent.voice.EtaVoiceInteractionService",
            rewriteManagedAssistantSettingIfOverwritten(
                autoConfigEnabled = true,
                target = PowerAssistantTarget.ETA,
                key = ModuleConfig.SECURE_VOICE_INTERACTION_SERVICE,
                value = "com.miui.voiceassist/com.xiaomi.voiceassistant.AssistInteractionService",
            ),
        )
        assertNull(
            rewriteManagedAssistantSettingIfOverwritten(
                autoConfigEnabled = false,
                target = PowerAssistantTarget.ETA,
                key = ModuleConfig.SECURE_VOICE_INTERACTION_SERVICE,
                value = "com.miui.voiceassist/com.xiaomi.voiceassistant.AssistInteractionService",
            ),
        )
        assertNull(
            rewriteManagedAssistantSettingIfOverwritten(
                autoConfigEnabled = true,
                target = PowerAssistantTarget.OEM,
                key = ModuleConfig.SECURE_VOICE_INTERACTION_SERVICE,
                value = "com.miui.voiceassist/com.xiaomi.voiceassistant.AssistInteractionService",
            ),
        )
        assertNull(
            rewriteManagedAssistantSettingIfOverwritten(
                autoConfigEnabled = true,
                target = PowerAssistantTarget.ETA,
                key = ModuleConfig.SECURE_VOICE_INTERACTION_SERVICE,
                value = "com.google.android.googlequicksearchbox/com.google.android.voiceinteraction.GsaVoiceInteractionService",
            ),
        )
        assertNull(
            rewriteManagedAssistantSettingIfOverwritten(
                autoConfigEnabled = true,
                target = PowerAssistantTarget.ETA,
                key = ModuleConfig.SECURE_VOICE_INTERACTION_SERVICE,
                value = "io.github.mangi.eta/.agent.voice.EtaVoiceInteractionService",
            ),
        )
    }
}
