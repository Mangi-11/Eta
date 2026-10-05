package io.github.mangi.eta.ui.markdown

import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.LinkMap
import org.intellij.markdown.parser.MarkdownParser

/**
 * 面向追加式模型输出的 GFM 解析会话。
 *
 * 每次提交都在调用线程完成一次完整 GFM 解析，再转换为 [MarkdownDocument]；调用方应把
 * 会话限制在后台串行 dispatcher。完整解析让块级语法遵循同一套 CommonMark/GFM 规则，
 * 而不是由 UI 猜测节点类型。流尚未结束时，仅在虚拟 EOF 上补齐仍在等待闭合的结构，
 * 虚拟字符不会写回消息，也不会进入最终快照。
 */
internal class StreamingGfmParserSession {
    private val parser = MarkdownParser(GFMFlavourDescriptor())
    private val documentBuilder = MarkdownDocumentBuilder()

    fun parse(
        source: String,
        isComplete: Boolean,
        style: MarkdownInlineStyle = MarkdownInlineStyle.Default,
    ): StreamingGfmSnapshot {
        val renderedSource = StreamingGfmProjection.project(
            source = TexMathDelimiters.normalize(source),
            isComplete = isComplete,
        )
        val root = parser.buildMarkdownTreeFromString(renderedSource)
        // 引用式链接的定义可能出现在文末，只在终态整体解析一次；流式期间引用暂按原文显示。
        val links = if (isComplete) LinkMap.buildLinkMap(root, renderedSource) else null
        return StreamingGfmSnapshot(
            originalSource = source,
            renderedSource = renderedSource,
            isComplete = isComplete,
            document = documentBuilder.build(root, renderedSource, links, style),
        )
    }
}

internal data class StreamingGfmSnapshot(
    val originalSource: String,
    val renderedSource: String,
    val isComplete: Boolean,
    val document: MarkdownDocument,
) {
    fun completedDocumentFor(content: String): MarkdownDocument? =
        document.takeIf { isComplete && originalSource == content }
}

/**
 * 为真实 EOF 和“暂时没有更多字符”的流式 EOF 建立不同语义。
 *
 * - 表格在分隔行确认前不发布候选表头，避免先按普通段落显示竖线。
 * - 未闭合链接保留在解析器缓冲区，避免把半截目标地址暴露给 UI。
 * - 已确认开始的围栏代码、代码 span 和强调结构使用只存在于解析快照中的
 *   虚拟闭合符，使其从第一次可判定时就保持同一种节点类型。
 */
internal object StreamingGfmProjection {
    fun project(source: String, isComplete: Boolean): String {
        if (isComplete || source.isEmpty()) return source

        // Issue #101：數學區間只算一次後傳參，避免每幀 3×protectedRanges 全掃描。
        val needMath = source.contains('$') &&
            (('|' in source) || ('[' in source) || source.any { it == '*' || it == '_' || it == '~' || it == '`' })
        val math = if (needMath) mathRanges(source) else emptyList()

        val tableSafeEnd = ambiguousTableStart(source, math) ?: source.length
        var projected = source.substring(0, tableSafeEnd)

        val openFence = findOpenFence(projected)
        if (openFence != null) {
            if (!projected.endsWith('\n')) projected += "\n"
            return projected + openFence.marker.toString().repeat(openFence.length)
        }

        val pendingLinkStart = findPendingLinkStart(projected, mathFor(projected, source, math))
        if (pendingLinkStart != null) {
            projected = projected.substring(0, pendingLinkStart)
        }

        val inlineClosures = findInlineClosures(projected, mathFor(projected, source, math))
        return projected + inlineClosures
    }

    /**
     * projected 是 source 的前綴子串（表格截斷後），其上的 math 區間可過濾重用；
     * 若被截斷導致尾部 math 區間越界則截斷，pend/link 階段 source 變化時回退重算由調用方保證。
     * 簡化起見：若 projected 與 source 等長直接重用，否則按 projected 重算（仍只算一次/分支）。
     */
    private fun mathFor(projected: String, source: String, math: List<IntRange>): List<IntRange> {
        if (projected.length == source.length) return math
        if (!projected.contains('$')) return emptyList()
        return mathRanges(projected)
    }

    private fun ambiguousTableStart(source: String, math: List<IntRange> = mathRanges(source)): Int? {
        if ('|' !in source) return null
        val lines = source.toLineSlices()
        if (lines.isEmpty()) return null
        // Issue #101：公式裡的 `|`（如 $\mid$、$\lvert x\rvert$）不能觸發表格緩衝，
        // 否則流式期間整行公式被截斷而「直接消失」。mathRanges 基於等長歸一化源碼，偏移 1:1。
        fun hasPipeOutsideMath(slice: LineSlice): Boolean =
            containsUnescapedPipeOutsideMath(slice.text, slice.start, math)

        val blockStart = lines.indexOfLast { it.text.isBlank() }
            .let { blankIndex -> if (blankIndex == -1) 0 else blankIndex + 1 }
        val blockLines = lines.subList(blockStart, lines.size)
        if (blockLines.isEmpty()) return null

        val confirmedTable = (1 until blockLines.size).any { index ->
            hasPipeOutsideMath(blockLines[index - 1]) &&
                isValidTableDelimiter(blockLines[index].text)
        }
        if (confirmedTable) return null

        val current = blockLines.last()
        val previous = blockLines.getOrNull(blockLines.lastIndex - 1)

        if (previous != null && hasPipeOutsideMath(previous)) {
            if (current.text.isEmpty()) {
                return if (source.endsWith("\n\n")) null else previous.start
            }
            if (isTableDelimiterCandidate(current.text)) {
                return if (isValidTableDelimiter(current.text)) null else previous.start
            }
        }

        return current.start.takeIf {
            current.text.isNotBlank() && hasPipeOutsideMath(current)
        }
    }

    private fun findOpenFence(source: String): Fence? {
        var openFence: Fence? = null
        source.toLineSlices().forEach { line ->
            val marker = line.fenceMarker() ?: return@forEach
            val current = openFence
            if (current == null) {
                openFence = marker
            } else if (
                marker.marker == current.marker &&
                marker.length >= current.length &&
                marker.isClosing
            ) {
                openFence = null
            }
        }
        return openFence
    }

    private fun findPendingLinkStart(source: String, math: List<IntRange> = mathRanges(source)): Int? {
        if ('[' !in source) return null
        // Issue #101：公式裡的 `[0,1]`、`a[0]` 不能當未閉合連結截斷，否則流式公式後半消失。
        fun inMath(index: Int): Boolean = math.containsSorted(index)
        val bracketStack = ArrayDeque<Int>()
        var inlineCodeTicks = 0
        var openLinkStart: Int? = null
        var linkParenthesisDepth = 0
        var lastClosedBracketStart: Int? = null
        var lastClosedBracketEnd = -1
        var index = 0

        while (index < source.length) {
            if (inMath(index)) {
                index += 1
                continue
            }
            if (source[index] == '\\') {
                index += 2
                continue
            }
            if (source[index] == '`') {
                val runLength = source.runLengthAt(index, '`')
                inlineCodeTicks = when {
                    inlineCodeTicks == 0 -> runLength
                    inlineCodeTicks == runLength -> 0
                    else -> inlineCodeTicks
                }
                index += runLength
                continue
            }
            if (inlineCodeTicks != 0) {
                index += 1
                continue
            }

            val char = source[index]
            if (openLinkStart != null) {
                when (char) {
                    '(' -> linkParenthesisDepth += 1
                    ')' -> {
                        linkParenthesisDepth -= 1
                        if (linkParenthesisDepth == 0) {
                            openLinkStart = null
                        }
                    }
                }
                index += 1
                continue
            }

            when (char) {
                '[' -> bracketStack.addLast(index)
                ']' -> if (bracketStack.isNotEmpty()) {
                    lastClosedBracketStart = bracketStack.removeLast()
                    lastClosedBracketEnd = index
                }
                '(' -> if (lastClosedBracketEnd == index - 1) {
                    openLinkStart = lastClosedBracketStart
                    linkParenthesisDepth = 1
                }
            }
            index += 1
        }

        val pendingStart = openLinkStart ?: bracketStack.lastOrNull()
        return pendingStart?.let { start ->
            if (start > 0 && source[start - 1] == '!') start - 1 else start
        }
    }

    private fun findInlineClosures(source: String, math: List<IntRange> = mathRanges(source)): String {
        if (!source.any { it == '*' || it == '_' || it == '~' || it == '`' }) return ""
        var inlineCodeTicks = 0
        val delimiterStack = ArrayDeque<String>()
        // Issue #101：公式內的 `*` / `_` 不參與寬鬆配對，否則流式會虛擬閉合出假斜體。
        var index = 0

        while (index < source.length) {
            if (math.containsSorted(index)) {
                index += 1
                continue
            }
            if (source[index] == '\\') {
                index += 2
                continue
            }

            if (source[index] == '`') {
                val runLength = source.runLengthAt(index, '`')
                inlineCodeTicks = when {
                    inlineCodeTicks == 0 -> runLength
                    inlineCodeTicks == runLength -> 0
                    else -> inlineCodeTicks
                }
                index += runLength
                continue
            }
            if (inlineCodeTicks != 0) {
                index += 1
                continue
            }

            val delimiter = source.delimiterAt(index)
            if (delimiter == null) {
                index += 1
                continue
            }

            val previous = source.getOrNull(index - 1)
            val next = source.getOrNull(index + delimiter.length)
            val canOpen = next != null && !next.isWhitespace()
            val canClose = previous != null && !previous.isWhitespace()
            val intrawordUnderscore = delimiter.contains('_') &&
                previous?.isLetterOrDigit() == true &&
                next?.isLetterOrDigit() == true

            when {
                canClose && delimiterStack.lastOrNull() == delimiter -> delimiterStack.removeLast()
                canOpen && !intrawordUnderscore -> delimiterStack.addLast(delimiter)
            }
            index += delimiter.length
        }

        return buildString {
            if (inlineCodeTicks != 0) {
                append("`".repeat(inlineCodeTicks))
            }
            delimiterStack.reversed().forEach(::append)
        }
    }

    private fun String.delimiterAt(index: Int): String? = when {
        startsWith("**", index) -> "**"
        startsWith("__", index) -> "__"
        startsWith("~~", index) -> "~~"
        this[index] == '*' -> "*"
        this[index] == '_' -> "_"
        else -> null
    }

    private fun String.runLengthAt(start: Int, char: Char): Int {
        var end = start
        while (end < length && this[end] == char) end += 1
        return end - start
    }

    private fun containsUnescapedPipeOutsideMath(line: String, lineStart: Int, math: List<IntRange>): Boolean {
        var escaped = false
        line.forEachIndexed { offset, char ->
            when {
                escaped -> escaped = false
                char == '\\' -> escaped = true
                char == '|' -> {
                    val global = lineStart + offset
                    if (!math.containsSorted(global)) return true
                }
            }
        }
        return false
    }

    private fun isTableDelimiterCandidate(line: String): Boolean {
        val trimmed = line.trim()
        return trimmed.isNotEmpty() &&
            trimmed.all { char -> char == '|' || char == ':' || char == '-' || char.isWhitespace() }
    }

    private fun isValidTableDelimiter(line: String): Boolean {
        val trimmed = line.trim().removePrefix("|").removeSuffix("|")
        val cells = trimmed.split('|').map(String::trim)
        return cells.isNotEmpty() && cells.all { cell ->
            val withoutColons = cell.removePrefix(":").removeSuffix(":")
            withoutColons.length >= 3 && withoutColons.all { it == '-' }
        }
    }

    private fun String.toLineSlices(): List<LineSlice> {
        val result = mutableListOf<LineSlice>()
        var start = 0
        for (index in indices) {
            if (this[index] != '\n') continue
            val end = if (index > start && this[index - 1] == '\r') index - 1 else index
            result += LineSlice(start = start, text = substring(start, end))
            start = index + 1
        }
        result += LineSlice(start = start, text = substring(start))
        return result
    }

    private fun LineSlice.fenceMarker(): Fence? {
        var index = 0
        while (index < text.length && index < 4 && text[index] == ' ') index += 1
        if (index > 3) return null
        val marker = text.getOrNull(index)?.takeIf { it == '`' || it == '~' } ?: return null
        val runLength = text.runLengthAt(index, marker)
        if (runLength < 3) return null
        val suffix = text.substring(index + runLength)
        return Fence(
            marker = marker,
            length = runLength,
            isClosing = suffix.isBlank(),
        )
    }

    private data class LineSlice(
        val start: Int,
        val text: String,
    )

    private data class Fence(
        val marker: Char,
        val length: Int,
        val isClosing: Boolean,
    )
}
