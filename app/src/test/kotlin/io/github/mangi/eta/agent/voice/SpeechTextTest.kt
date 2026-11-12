package io.github.mangi.eta.agent.voice

import org.junit.Assert.*
import org.junit.Test

class SpeechTextTest {
    @Test fun spokenTextSkipsCodeAndKeepsLinkLabelAndInlineIdentifiers() {
        val text = SpeechText.readable("""
            # 操作说明

            请查看 **设置** 与 [帮助](https://example.invalid)，参数是 `user_name`。

            ```kotlin
            secretCommand()
            ```

                anotherSecretCommand()

            完成。
        """.trimIndent())
        assertTrue(text.contains("操作说明"))
        assertTrue(text.contains("设置"))
        assertTrue(text.contains("帮助"))
        assertTrue(text.contains("user_name"))
        assertFalse(text.contains("请查看正文"))
        assertFalse(text.contains("https://"))
        assertFalse(text.contains("secretCommand"))
        assertFalse(text.contains("anotherSecretCommand"))
        assertFalse(text.contains("**"))
        assertTrue(text.endsWith("完成。"))
    }

    @Test fun sentenceChunkingPreservesContentAndSurrogatePairs() {
        val text = "第一句话。第二句话。" + "你好😀".repeat(30)
        val chunks = SpeechText.chunks(text, 17)
        assertEquals(text, chunks.joinToString(""))
        assertTrue(chunks.all { it.length <= 17 && !it.last().isHighSurrogate() && !it.first().isLowSurrogate() })
    }

    @Test fun codeOnlyAnswersHaveNoSpeech() {
        for (markdown in listOf("```\nhello\n```", "    hello", "```kotlin\nhello")) {
            assertEquals("", SpeechText.readable(markdown))
        }
    }

    @Test fun tablesAreOmittedWithoutAnnouncementsAndPreserveSurroundingParagraphs() {
        val table = """
            | 产品 | 价格 | 条件 |
            | --- | ---: | --- |
            | 产品甲 | 108元 | 仅授权后可用 |
            | 产品乙 | 209元 | 不支持离线 |
        """.trimIndent()
        val text = SpeechText.readable("先看结论。\n\n$table\n\n继续操作。")

        assertTrue(Regex("先看结论。\\n+继续操作。").matches(text))
    }

    @Test fun tableOnlyAnswersHaveNoSpeechRegardlessOfCellContent() {
        for (table in listOf(
            """
                | 产品 | 价格 |
                | --- | ---: |
                | 产品甲 | 108元 |
                | 产品乙 | 209元 |
            """.trimIndent(),
            """
                | 项目 | | 备注 |
                | --- | --- | --- |
                | A | | 正常 |
                | B |
                | C | 3 | 保留 | 额外单元格 |
            """.trimIndent(),
            """
                | 方案 | 耗时 | 内存 |
                | --- | --- | --- |
                | [**A**](https://example.invalid/a) | 1.5 秒 | 200 MB |
                | `B` | 2 秒 | 100 MB |
            """.trimIndent(),
            "| 项目 | 数量 |\n| --- | --- |",
            """
                | 类型 | 内容 |
                | --- | --- |
                | 图片 | ![示意图](https://example.invalid/diagram.png) |
                | 嵌入图片 | <img src="https://example.invalid/diagram.png"> |
                | 裸网址 | https://example.invalid/download |
            """.trimIndent(),
        )) {
            assertEquals(table, "", SpeechText.readable(table))
        }
    }

    @Test fun urlOnlyAnswersHaveNoSpeech() {
        for (markdown in listOf(
            "https://example.invalid/download?token=abc&lang=zh",
            "http://example.invalid/help",
            "www.example.invalid/help",
            "<https://example.invalid/help>",
            "[https://example.invalid/help](https://example.invalid/help)",
            "[www.example.invalid/help](https://example.invalid/help)",
            "https://例子.测试/文档/安装?语言=中文",
            "[https://example.invalid/help][target]\n\n[target]: https://example.invalid/help",
        )) {
            assertEquals(markdown, "", SpeechText.readable(markdown))
        }
    }

    @Test fun codeAndLinksTogetherDoNotProducePlaceholderSpeech() {
        val text = SpeechText.readable("```sh\nrun_command\n```\n\nhttps://example.invalid/download")

        assertEquals("", text)
    }

    @Test fun imagesAndHtmlBlocksAreSkippedWithoutNoticesOrLostParagraphBoundaries() {
        for (content in listOf(
            "![示意图文字](https://example.invalid/diagram.png)",
            "<div>HTML块内的内容</div>",
            "<img src=\"https://example.invalid/diagram.png\" alt=\"图片说明\">",
        )) {
            assertEquals(content, "", SpeechText.readable(content))
            val text = SpeechText.readable("前面的说明。\n\n$content\n\n后面的说明。")
            assertTrue(content, Regex("前面的说明。\\n+后面的说明。").matches(text))
        }
    }

    @Test fun descriptiveLinkLabelsSurviveInlineAndReferenceLinks() {
        val text = SpeechText.readable("""
            请查看 [帮助中心](https://example.invalid/help)、[安装说明][install]、
            [疑难解答]，然后打开 [**高级设置**](https://example.invalid/settings)。

            [install]: https://example.invalid/install
            [疑难解答]: https://example.invalid/faq
        """.trimIndent())

        for (label in listOf("帮助中心", "安装说明", "疑难解答", "高级设置")) {
            assertTrue(label, text.contains(label))
        }
        assertFalse(text.contains("example.invalid"))
        assertFalse(text.contains("[install]"))
        assertFalse(text.contains("**"))
    }

    @Test fun urlsDoNotConsumeChinesePunctuationOrFollowingInstructions() {
        for ((markdown, continuation) in listOf(
            "打开 https://example.invalid/a?x=1，接下来检查权限。" to "，接下来检查权限。",
            "打开 https://example.invalid/help,然后检查权限。" to ",然后检查权限。",
            "打开 https://example.invalid/help;然后检查权限。" to ";然后检查权限。",
            "下载 http://example.invalid/pkg。完成后重启。" to "。完成后重启。",
            "访问 www.example.invalid/help，随后返回。" to "，随后返回。",
            "地址：https://example.invalid/help，继续下一步。" to "，继续下一步。",
            "查看 <https://example.invalid/help>。然后返回。" to "。然后返回。",
        )) {
            val text = SpeechText.readable(markdown)
            assertFalse(markdown, text.contains("链接见正文"))
            assertTrue(markdown, text.endsWith(continuation))
            assertFalse(markdown, text.contains("example.invalid"))
            assertFalse(markdown, text.contains("<"))
            assertFalse(markdown, text.contains(">"))
        }
    }

    @Test fun skippedLinksAndImagesDoNotJoinNeighboringWords() {
        for (markdown in listOf(
            "alpha<https://example.invalid/help>beta",
            "alpha[https://example.invalid/help](https://example.invalid/help)beta",
            "alpha[](https://example.invalid/help)beta",
            "alpha![图片说明](https://example.invalid/diagram.png)beta",
        )) {
            val text = SpeechText.readable(markdown)
            assertTrue(markdown, Regex("alpha\\s+beta").matches(text))
        }
    }

    @Test fun skippedLinksPreserveParagraphAndListBoundaries() {
        val text = SpeechText.readable("""
            第一段正文

            https://example.invalid/help

            第二段正文

            - 保留第一项
            - <https://example.invalid/download>
            - 保留第二项
        """.trimIndent())

        assertInOrder(text, "第一段正文", "第二段正文", "保留第一项", "保留第二项")
        assertTrue(Regex("第一段正文\\n+").containsMatchIn(text))
        assertTrue(Regex("\\n第二段正文\\n").containsMatchIn(text))
        assertFalse(text.contains("example.invalid"))
        assertFalse(text.contains("链接见正文"))
    }

    @Test fun orderedListsKeepTheirStartAndRenderedSequence() {
        val text = SpeechText.readable("""
            3. 先备份
            3. 再更新
            3. 最后检查
        """.trimIndent())

        var offset = 0
        for ((number, item) in listOf(3 to "先备份", 4 to "再更新", 5 to "最后检查")) {
            val marker = Regex("(?<!\\d)$number(?!\\d)\\s*(?:项|步)?\\s*[:：、.)]?\\s*${Regex.escape(item)}")
            val match = marker.find(text, startIndex = offset)
            assertNotNull("Expected item $number before '$item' in '$text'", match)
            offset = match!!.range.last + 1
        }
    }

    @Test fun taskListsKeepCompletedAndIncompleteStates() {
        val text = SpeechText.readable("""
            - [x] 完成备份
            - [ ] 尚未更新
            - [X] 已核对
            - 不要更改权限
        """.trimIndent())

        assertInOrder(text, "已完成：完成备份", "未完成：尚未更新", "已完成：已核对", "不要更改权限")
        assertFalse(text.contains("[x]"))
        assertFalse(text.contains("[ ]"))
        assertFalse(text.contains("[X]"))
    }

    @Test fun numbersUnitsEnglishAndShortInlineIdentifiersRemainReadable() {
        val text = SpeechText.readable(
            "版本 v1.2.3，温度 -3.5°C，金额 1,234.50 元，长度 10 mm，进度 42%；" +
                "OpenAI API 参数 `user_name`，文件 `config.json`。",
        )

        for (content in listOf("v1.2.3", "-3.5°C", "1,234.50 元", "10 mm", "42%", "OpenAI API", "user_name", "config.json")) {
            assertTrue(content, text.contains(content))
        }
    }

    private fun assertInOrder(text: String, vararg expected: String) {
        fun compact(value: String) = value.replace(Regex("\\s+"), "").replace(':', '：')
        val actual = compact(text)
        var offset = 0
        for (part in expected) {
            val normalized = compact(part)
            val index = actual.indexOf(normalized, startIndex = offset)
            assertTrue("Expected '$part' after offset $offset in '$text'", index >= 0)
            offset = index + normalized.length
        }
    }
}
