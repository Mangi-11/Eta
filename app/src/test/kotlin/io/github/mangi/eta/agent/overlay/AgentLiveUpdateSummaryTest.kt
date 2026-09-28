package io.github.mangi.eta.agent.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentLiveUpdateSummaryTest {
    @Test
    fun `strips markdown markers and links`() {
        val raw = """
            # 已完成

            - **结果**：`clipboard` 已写入，见 [文档](https://example.com)

            ```kotlin
            val x = 1
            ```
        """.trimIndent()

        val summary = summarizeAgentResult(raw)

        assertTrue(summary.contains("已完成"))
        assertTrue(summary.contains("结果"))
        assertTrue(summary.contains("clipboard"))
        assertTrue(summary.contains("文档"))
        assertFalse(summary.contains("```"))
        assertFalse(summary.contains("https://"))
        assertFalse(summary.contains("**"))
    }

    @Test
    fun `keeps short plain text`() {
        assertEquals("已经帮你打开了计算器", summarizeAgentResult("已经帮你打开了计算器"))
    }

    @Test
    fun `truncates long text at sentence boundary`() {
        val summary = summarizeAgentResult("第一步已经完成。".repeat(30))

        assertTrue(summary.length <= 161)
        assertTrue(summary.endsWith("…"))
        // 收尾落在句号上，不会把句子劈开
        assertTrue(summary.dropLast(1).endsWith("。"))
    }

    @Test
    fun `truncates text without punctuation`() {
        val summary = summarizeAgentResult("甲".repeat(400))

        assertEquals(161, summary.length)
        assertTrue(summary.endsWith("…"))
    }
}
