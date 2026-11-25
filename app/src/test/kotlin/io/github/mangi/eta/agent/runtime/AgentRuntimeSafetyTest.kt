package io.github.mangi.eta.agent.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import io.github.mangi.eta.agent.runtime.ipc.AgentEvent

class AgentRuntimeSafetyTest {
    @Test
    fun logLinesDoNotContainToolArgumentsOrFailureReason() {
        val secret = "sensitive-user-value"

        val toolLine = AgentEvent.ToolStarted(
            round = 1,
            toolCallId = "call-1",
            name = "shell",
            argsPreview = "{\"command\":\"$secret\"}",
            command = secret,
        ).toLogLine()
        val failureLine = AgentEvent.RunFailed(secret).toLogLine()

        assertFalse(toolLine.contains(secret))
        assertFalse(failureLine.contains(secret))
        assertTrue(toolLine.contains("args_chars="))
        assertTrue(toolLine.contains("command_chars="))
        assertTrue(failureLine.contains("reason_chars="))
    }

    @Test
    fun modelGeneratedToolNamesUseBoundedSafeTokens() {
        val unsafeName = "shell\nforged-log-entry"
        val blockLine = AgentEvent.AssistantBlockStart(
            round = 1,
            kind = AgentEvent.AssistantBlockKind.TOOL_CALL,
            index = 0,
            name = unsafeName,
        ).toLogLine()
        val receivedLine = AgentEvent.AssistantReceived(
            round = 1,
            contentChars = 0,
            reasoningContent = "",
            toolNames = List(10) { index -> if (index == 0) unsafeName else "tool-$index" },
        ).toLogLine()

        assertFalse(blockLine.contains(unsafeName))
        assertFalse(receivedLine.contains(unsafeName))
        assertFalse(blockLine.contains('\n'))
        assertFalse(receivedLine.contains('\n'))
        assertTrue(blockLine.contains("name=unknown"))
        assertTrue(receivedLine.contains("tools=[unknown"))
        assertTrue(receivedLine.contains("tool_count=10"))
        assertFalse(receivedLine.contains("tool-8"))
    }

    @Test
    fun unsafeResultCodesAreNotWrittenToLogLines() {
        val unsafeCodes = listOf(
            "permission_denied\nforged-log-entry",
            "permission_denied\u0000forged-log-entry",
            "x".repeat(200),
        )

        unsafeCodes.forEach { unsafeCode ->
            val line = AgentEvent.ToolFinished(
                round = 1,
                toolCallId = "call-1",
                name = "shell.exec",
                resultSummary = "ok=false, code=$unsafeCode, chars=24",
                imageCount = 0,
                imageBytes = 0,
            ).toLogLine()

            assertFalse(line.contains(unsafeCode))
            assertFalse(line.contains('\n'))
            assertFalse(line.contains('\u0000'))
            assertTrue(line.contains("code=unknown"))
        }
    }

    @Test
    fun safeToolNameAndResultCodeRemainDiagnosable() {
        val line = AgentEvent.ToolFinished(
            round = 1,
            toolCallId = "call-1",
            name = "shell.exec",
            resultSummary = "ok=false, code=permission_denied, chars=24",
            imageCount = 0,
            imageBytes = 0,
        ).toLogLine()

        assertTrue(line.contains("name=shell.exec"))
        assertTrue(line.contains("code=permission_denied"))
    }

    @Test
    fun humanReadableSummaryCodeIsExtractedForLogs() {
        val line = AgentEvent.ToolFinished(
            round = 1,
            toolCallId = "call-1",
            name = "wait_for_text",
            resultSummary = "失败 · 等待文本超时 · code=TIMEOUT",
            imageCount = 0,
            imageBytes = 0,
        ).toLogLine()

        assertTrue(line.contains("code=TIMEOUT"))
        // 摘要正文不进日志，只记录长度与提取出的错误码
        assertFalse(line.contains("等待文本超时"))
    }

    @Test
    fun cancelledControllerExposesCancellationState() {
        val controller = AgentRunController()

        controller.cancel()

        assertTrue(controller.isCancelled)
        assertThrows(AgentRunCancelledException::class.java) {
            controller.throwIfCancelled()
        }
    }
}
