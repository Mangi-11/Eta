package io.github.mangi.eta.hook.hyperos

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HyperOsPowerPolicyTest {
    @Test
    fun `只接管电源键助手动作`() {
        assertTrue(HyperOsPowerPolicy.isAssistantShortcut("launch_voice_assistant", "long_press_power_key"))
        assertTrue(HyperOsPowerPolicy.isAssistantShortcut("launch_voice_assistant", "imperceptible_press_power_key"))
        for (action in listOf("show_power_menu", "launch_camera", "sos", "launch_smarthome", null)) {
            assertFalse(HyperOsPowerPolicy.isAssistantShortcut(action, "long_press_power_key"))
        }
        for (source in listOf("long_press_home_key", "double_click_power_key", "", null)) {
            assertFalse(HyperOsPowerPolicy.isAssistantShortcut("launch_voice_assistant", source))
        }
    }

    @Test
    fun `去重闸门在窗口内抑制重复触发且失败不锁死`() {
        val gate = HyperOsPowerDispatchGate(dedupWindowMs = 1_000L)

        // 首次触发即使开机 uptime 小于窗口也必须放行。
        assertFalse(gate.shouldSuppressDuplicate(200L))
        // 首次唤起尚未成功记录时，不允许抑制后续重试或回落触发。
        assertFalse(gate.shouldSuppressDuplicate(400L))

        gate.markLaunched(400L)
        assertTrue(gate.shouldSuppressDuplicate(400L))
        assertTrue(gate.shouldSuppressDuplicate(900L))
        assertTrue(gate.shouldSuppressDuplicate(1_400L))

        // 超过去重窗口后允许新一轮按压唤起。
        assertFalse(gate.shouldSuppressDuplicate(1_401L))
        gate.markLaunched(1_401L)
        assertTrue(gate.shouldSuppressDuplicate(2_000L))

        gate.reset()
        assertFalse(gate.shouldSuppressDuplicate(2_000L))
    }
}
