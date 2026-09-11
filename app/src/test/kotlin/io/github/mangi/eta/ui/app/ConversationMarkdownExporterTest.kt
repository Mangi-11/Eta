package io.github.mangi.eta.ui.app

import io.github.mangi.eta.agent.model.AgentFileReference
import io.github.mangi.eta.agent.model.AgentFileReferenceKind
import io.github.mangi.eta.agent.model.AgentFileReferencePromptCodec
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.RunTraceMessageUi
import io.github.mangi.eta.ui.model.SuggestionChipsMessageUi
import io.github.mangi.eta.ui.model.SystemNoticeCode
import io.github.mangi.eta.ui.model.SystemNoticeMessageUi
import io.github.mangi.eta.ui.model.ThinkingMessageUi
import io.github.mangi.eta.ui.model.ToolActivityMessageUi
import io.github.mangi.eta.ui.model.ToolActivityStatusUi
import io.github.mangi.eta.ui.model.ToolSummaryMessageUi
import io.github.mangi.eta.ui.model.UserMessageUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationMarkdownExporterTest {

    private val labels = ConversationMarkdownExporter.Labels(
        user = "User",
        assistant = "Assistant",
        thinking = "Thinking",
        toolLineFormat = "Tool: %1\$s (%2\$s)",
        toolsLineFormat = "Tools: %1\$s",
        argumentsFormat = "Arguments: %1\$s",
        resultFormat = "Result: %1\$s",
        imagesFormat = "Images: %1\$d",
        toolStatusRunning = "Running",
        toolStatusSuccess = "Completed",
        toolStatusFailed = "Failed",
        toolStatusUnknown = "Result unknown",
        noticeStopped = "Stopped",
        noticeEmptyResult = "Empty result",
        noticeModelRetry = "Retrying",
        noticeRuntimeFailed = "Runtime failed",
        noticeInterrupted = "Interrupted",
    )

    @Test
    fun exportRendersFullConversationInOrder() {
        val messages = listOf(
            UserMessageUi(id = "u1", content = "你好"),
            AgentMessageUi(id = "a1", content = "你好！**有什么可以帮你？**"),
            ThinkingMessageUi(id = "t1", content = "先思考", isStreaming = false),
            ToolActivityMessageUi(
                id = "tool1",
                toolName = "run_terminal_command",
                status = ToolActivityStatusUi.Success,
                argumentsSummary = "ls -la",
                command = "ls -la",
                resultSummary = "ok",
            ),
            ToolSummaryMessageUi(id = "ts1", tools = listOf("observe_screen", "tap_element")),
            SystemNoticeMessageUi(id = "n1", code = SystemNoticeCode.Stopped, detail = "用户取消"),
            AgentMessageUi(id = "a2", content = "结束"),
        )

        val result = ConversationMarkdownExporter.export("周会纪要", messages, labels)

        val expected = """
            # 周会纪要

            ## User

            你好

            ## Assistant

            你好！**有什么可以帮你？**

            <details>
            <summary>Thinking</summary>

            先思考

            </details>

            > Tool: run_terminal_command (Completed)
            >
            > ls -la
            >
            > Arguments: ls -la
            >
            > Result: ok

            > Tools: observe_screen, tap_element

            > Stopped
            >
            > 用户取消

            ## Assistant

            结束
        """.trimIndent()
        assertEquals(expected + "\n", result)
    }

    @Test
    fun exportParsesFileReferencesInUserContent() {
        val content = AgentFileReferencePromptCodec.format(
            request = "总结一下这个文件",
            references = listOf(
                AgentFileReference("notes.txt", "/sdcard/notes.txt", AgentFileReferenceKind.File),
            ),
        )

        val result = ConversationMarkdownExporter.export(
            "t",
            listOf(UserMessageUi(id = "u", content = content)),
            labels,
        )

        assertTrue(result.contains("总结一下这个文件"))
        assertTrue(result.contains("- /sdcard/notes.txt"))
        assertFalse(result.contains("# Files mentioned by the user:"))
    }

    @Test
    fun exportAnnotatesImagesWithoutEmbeddingDataUrls() {
        val message = UserMessageUi(
            id = "u",
            content = "看这张图",
            images = listOf("data:image/png;base64,AAAA", "data:image/jpeg;base64,BBBB"),
        )

        val result = ConversationMarkdownExporter.export("t", listOf(message), labels)

        assertTrue(result.contains("_Images: 2_"))
        assertFalse(result.contains("base64"))
    }

    @Test
    fun exportSkipsBlankStreamingAndNonContentMessages() {
        val messages = listOf(
            AgentMessageUi(id = "a", content = "", isStreaming = true),
            RunTraceMessageUi(id = "r", capabilities = emptyList()),
            SuggestionChipsMessageUi(id = "s", prompts = listOf("试试这个")),
        )

        val result = ConversationMarkdownExporter.export("t", messages, labels)

        assertEquals("# t\n", result)
    }

    @Test
    fun exportRendersToolStatusVariants() {
        val messages = listOf(
            ToolActivityMessageUi(
                id = "t1",
                toolName = "observe_screen",
                status = ToolActivityStatusUi.Failed,
                argumentsSummary = "",
                resultSummary = null,
            ),
            ToolActivityMessageUi(
                id = "t2",
                toolName = "tap_element",
                status = ToolActivityStatusUi.Unknown,
                argumentsSummary = "确定",
            ),
        )

        val result = ConversationMarkdownExporter.export("t", messages, labels)

        assertTrue(result.contains("> Tool: observe_screen (Failed)"))
        assertTrue(result.contains("> Tool: tap_element (Result unknown)"))
        assertTrue(result.contains("> Arguments: 确定"))
    }

    @Test
    fun exportSanitizesMultilineTitle() {
        val result = ConversationMarkdownExporter.export(
            "第一行\n第二行",
            listOf(UserMessageUi(id = "u", content = "hi")),
            labels,
        )

        assertTrue(result.startsWith("# 第一行 第二行\n"))
    }

    @Test
    fun defaultFileNameSanitizesTitle() {
        val name = ConversationMarkdownExporter.defaultFileName(
            title = "a/b\\c:d*e?f\"g<h>i|j",
            fallback = "Conversation",
            nowMillis = 0L,
        )

        assertTrue(name.startsWith("Eta-abcdefghij-"))
        assertTrue(name.endsWith(".md"))
        assertFalse(name.contains("/"))
    }

    @Test
    fun defaultFileNameFallsBackWhenBlankAndCollapsesWhitespace() {
        val fallback = ConversationMarkdownExporter.defaultFileName(
            title = "   ",
            fallback = "Conversation",
            nowMillis = 0L,
        )
        val spaced = ConversationMarkdownExporter.defaultFileName(
            title = "hello   world\nagain",
            fallback = "Conversation",
            nowMillis = 0L,
        )

        assertTrue(fallback.startsWith("Eta-Conversation-"))
        assertTrue(spaced.startsWith("Eta-hello world again-"))
    }

    @Test
    fun defaultFileNameTruncatesLongTitles() {
        val name = ConversationMarkdownExporter.defaultFileName(
            title = "长".repeat(100),
            fallback = "Conversation",
            nowMillis = 0L,
        )

        val match = Regex("^Eta-(.+)-\\d{8}-\\d{4}\\.md$").matchEntire(name)
        assertTrue(match != null)
        assertEquals(40, match!!.groupValues[1].length)
    }
}
