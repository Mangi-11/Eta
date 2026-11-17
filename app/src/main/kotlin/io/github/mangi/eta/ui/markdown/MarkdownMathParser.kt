package io.github.mangi.eta.ui.markdown

import kotlin.math.min
import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.flavours.commonmark.CommonMarkMarkerProcessor
import org.intellij.markdown.flavours.gfm.GFMConstraints
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import org.intellij.markdown.flavours.gfm.HighlightDelimiterParser
import org.intellij.markdown.flavours.gfm.StrikeThroughDelimiterParser
import org.intellij.markdown.flavours.gfm.alert.GitHubAlertMarkerProvider
import org.intellij.markdown.flavours.gfm.table.GitHubTableMarkerProvider
import org.intellij.markdown.parser.LookaheadText
import org.intellij.markdown.parser.MarkerProcessor
import org.intellij.markdown.parser.MarkerProcessorFactory
import org.intellij.markdown.parser.ProductionHolder
import org.intellij.markdown.parser.constraints.MarkdownConstraints
import org.intellij.markdown.parser.constraints.eatItselfFromString
import org.intellij.markdown.parser.constraints.extendsPrev
import org.intellij.markdown.parser.constraints.getCharsEaten
import org.intellij.markdown.parser.markerblocks.MarkerBlock
import org.intellij.markdown.parser.markerblocks.MarkerBlockImpl
import org.intellij.markdown.parser.markerblocks.MarkerBlockProvider
import org.intellij.markdown.parser.sequentialparsers.EmphasisLikeParser
import org.intellij.markdown.parser.sequentialparsers.RangesListBuilder
import org.intellij.markdown.parser.sequentialparsers.SequentialParser
import org.intellij.markdown.parser.sequentialparsers.SequentialParserManager
import org.intellij.markdown.parser.sequentialparsers.TokensCache
import org.intellij.markdown.parser.sequentialparsers.impl.AutolinkParser
import org.intellij.markdown.parser.sequentialparsers.impl.BacktickParser
import org.intellij.markdown.parser.sequentialparsers.impl.EmphStrongDelimiterParser
import org.intellij.markdown.parser.sequentialparsers.impl.ImageParser
import org.intellij.markdown.parser.sequentialparsers.impl.InlineLinkParser
import org.intellij.markdown.parser.sequentialparsers.impl.LinkParserUtil
import org.intellij.markdown.parser.sequentialparsers.impl.ReferenceLinkParser

/**
 * 面向聊天排版的 GFM 扩展：
 *
 * 1. 块级 `$$...$$`（及归一化后的 `\[...\]`）仅在闭合定界符已到达时立为独立 `BLOCK_MATH`，
 *    既能打断未留空行的前导段落，也能保护多行公式内的 `-`、`=`、空行不被误拆为列表或标题；
 *    未闭合时保持普通文本，确保公式闭合后再排版。
 * 2. 行内 `$...$` 与 `$$...$$` 兼容紧贴中文字符与括号，同时排除货币（`$4 和 $20`、`$4-$20`）
 *    并避免未闭合货币跨过后续 `$` 吞并真正的公式。
 */
internal class ChatGfmFlavourDescriptor(
    private val originalSource: String,
) : GFMFlavourDescriptor() {
    override val markerProcessorFactory: MarkerProcessorFactory = object : MarkerProcessorFactory {
        override fun createMarkerProcessor(productionHolder: ProductionHolder): MarkerProcessor<*> =
            ChatGfmMarkerProcessor(productionHolder, originalSource)
    }

    override val sequentialParserManager: SequentialParserManager = object : SequentialParserManager() {
        override fun getParserSequence(): List<SequentialParser> = listOf(
            AutolinkParser(listOf(MarkdownTokenTypes.AUTOLINK, GFMTokenTypes.GFM_AUTOLINK)),
            BacktickParser(),
            ChatMathSequentialParser(),
            ImageParser(),
            InlineLinkParser(),
            ReferenceLinkParser(),
            EmphasisLikeParser(
                EmphStrongDelimiterParser(),
                StrikeThroughDelimiterParser(),
                HighlightDelimiterParser(),
            ),
        )
    }
}

private class ChatGfmMarkerProcessor(
    productionHolder: ProductionHolder,
    originalSource: String,
) : CommonMarkMarkerProcessor(productionHolder, GFMConstraints.BASE) {
    private val providers: List<MarkerBlockProvider<StateInfo>> =
        listOf(GitHubAlertMarkerProvider(), DisplayMathBlockProvider(originalSource)) +
            super.getMarkerBlockProviders() +
            listOf(GitHubTableMarkerProvider())

    override fun getMarkerBlockProviders(): List<MarkerBlockProvider<StateInfo>> = providers

    override fun populateConstraintsTokens(
        pos: LookaheadText.Position,
        constraints: MarkdownConstraints,
        productionHolder: ProductionHolder,
    ) {
        if (constraints !is GFMConstraints || !constraints.hasCheckbox()) {
            super.populateConstraintsTokens(pos, constraints, productionHolder)
            return
        }
        val line = pos.currentLine
        var offset = pos.offsetInCurrentLine
        while (offset < line.length && line[offset] != '[') {
            offset += 1
        }
        if (offset == line.length) {
            super.populateConstraintsTokens(pos, constraints, productionHolder)
            return
        }
        val markerType = when (constraints.types.lastOrNull()) {
            '>' -> MarkdownTokenTypes.BLOCK_QUOTE
            '.', ')' -> MarkdownTokenTypes.LIST_NUMBER
            else -> MarkdownTokenTypes.LIST_BULLET
        }
        val middleOffset = pos.offset - pos.offsetInCurrentLine + offset
        val endOffset = min(
            pos.offset - pos.offsetInCurrentLine + constraints.getCharsEaten(pos.currentLine),
            pos.nextLineOrEofOffset,
        )
        productionHolder.addProduction(
            listOf(
                SequentialParser.Node(pos.offset..middleOffset, markerType),
                SequentialParser.Node(middleOffset..endOffset, GFMTokenTypes.CHECK_BOX),
            )
        )
    }
}

private class DisplayMathBlockProvider(
    private val originalSource: String,
) : MarkerBlockProvider<MarkerProcessor.StateInfo> {

    override fun createMarkerBlocks(
        pos: LookaheadText.Position,
        productionHolder: ProductionHolder,
        stateInfo: MarkerProcessor.StateInfo,
    ): List<MarkerBlock> {
        val opening = obtainOpening(pos, stateInfo.currentConstraints) ?: return emptyList()
        return when (opening) {
            is Opening.SingleLine -> {
                productionHolder.addProduction(
                    buildList {
                        add(SequentialParser.Node(opening.openOffset..opening.openOffset + 2, GFMTokenTypes.DOLLAR))
                        if (opening.openOffset + 2 < opening.closeOffset) {
                            add(
                                SequentialParser.Node(
                                    opening.openOffset + 2..opening.closeOffset,
                                    MarkdownTokenTypes.TEXT,
                                )
                            )
                        }
                        add(SequentialParser.Node(opening.closeOffset..opening.closeOffset + 2, GFMTokenTypes.DOLLAR))
                    }
                )
                listOf(
                    SingleLineDisplayMathMarkerBlock(
                        myConstraints = stateInfo.currentConstraints,
                        productionHolder = productionHolder,
                        lineEndOffset = pos.nextLineOrEofOffset,
                    )
                )
            }
            is Opening.MultiLine -> {
                productionHolder.addProduction(
                    buildList {
                        add(SequentialParser.Node(opening.openOffset..opening.openOffset + 2, GFMTokenTypes.DOLLAR))
                        if (opening.openOffset + 2 < pos.nextLineOrEofOffset) {
                            add(
                                SequentialParser.Node(
                                    opening.openOffset + 2..pos.nextLineOrEofOffset,
                                    MarkdownTokenTypes.TEXT,
                                )
                            )
                        }
                    }
                )
                listOf(
                    MultiLineDisplayMathMarkerBlock(
                        myConstraints = stateInfo.currentConstraints,
                        productionHolder = productionHolder,
                        closingNewlineOffset = opening.closingNewlineOffset,
                        closeIndexInLine = opening.closeIndexInLine,
                    )
                )
            }
        }
    }

    override fun interruptsParagraph(
        pos: LookaheadText.Position,
        constraints: MarkdownConstraints,
    ): Boolean = obtainOpening(pos, constraints) != null

    private fun obtainOpening(
        pos: LookaheadText.Position,
        constraints: MarkdownConstraints,
    ): Opening? {
        if (!MarkerBlockProvider.isStartOfLineWithConstraints(pos, constraints)) return null
        val line = pos.currentLineFromPosition
        var leadSpaces = 0
        while (leadSpaces < line.length && leadSpaces < 4 && line[leadSpaces] == ' ') {
            leadSpaces += 1
        }
        if (leadSpaces > 3) return null
        if (!line.startsWithDoubleDollarAt(leadSpaces)) return null

        val openGlobalOffset = pos.offset + leadSpaces
        // 行首的 `\(...\)` 虽在归一化后变成 `$$...$$`，语义仍是行内公式，不提升为独立居中块。
        if (openGlobalOffset + 2 <= originalSource.length &&
            originalSource.startsWith("\\(", openGlobalOffset)
        ) {
            return null
        }

        val afterOpen = line.subSequence(leadSpaces + 2, line.length)
        val sameLineClose = findTrailingDoubleDollar(afterOpen)
        if (sameLineClose != null) {
            val inner = afterOpen.subSequence(0, sameLineClose)
            if (inner.isBlank() || containsUnescapedDoubleDollar(inner)) return null
            val closeGlobalOffset = openGlobalOffset + 2 + sameLineClose
            return Opening.SingleLine(
                openOffset = openGlobalOffset,
                closeOffset = closeGlobalOffset,
            )
        }

        if (containsUnescapedDoubleDollar(afterOpen)) return null

        var hasNonBlankContent = afterOpen.isNotBlank()
        var scan = pos.nextLinePosition()
        while (scan != null) {
            val nextConstraints = constraints.applyToNextLine(scan)
            if (!nextConstraints.extendsPrev(constraints)) return null
            val nextLine = nextConstraints.eatItselfFromString(scan.currentLine)
            val closeIdx = findTrailingDoubleDollar(nextLine)
            if (closeIdx != null) {
                val beforeClose = nextLine.subSequence(0, closeIdx)
                if (containsUnescapedDoubleDollar(beforeClose)) return null
                if (!hasNonBlankContent && beforeClose.isBlank()) return null
                return Opening.MultiLine(
                    openOffset = openGlobalOffset,
                    closingNewlineOffset = scan.offset,
                    closeIndexInLine = closeIdx,
                )
            }
            if (isCodeFenceLine(nextLine)) return null
            if (containsUnescapedDoubleDollar(nextLine)) return null
            if (nextLine.isNotBlank()) hasNonBlankContent = true
            scan = scan.nextLinePosition()
        }
        return null
    }

    private sealed interface Opening {
        data class SingleLine(
            val openOffset: Int,
            val closeOffset: Int,
        ) : Opening

        data class MultiLine(
            val openOffset: Int,
            val closingNewlineOffset: Int,
            val closeIndexInLine: Int,
        ) : Opening
    }
}

private class SingleLineDisplayMathMarkerBlock(
    myConstraints: MarkdownConstraints,
    productionHolder: ProductionHolder,
    private val lineEndOffset: Int,
) : MarkerBlockImpl(myConstraints, productionHolder.mark()) {
    override fun allowsSubBlocks(): Boolean = false
    override fun isInterestingOffset(pos: LookaheadText.Position): Boolean = true
    override fun calcNextInterestingOffset(pos: LookaheadText.Position): Int = lineEndOffset
    override fun getDefaultAction(): MarkerBlock.ClosingAction = MarkerBlock.ClosingAction.DONE
    override fun getDefaultNodeType(): IElementType = GFMElementTypes.BLOCK_MATH

    override fun doProcessToken(
        pos: LookaheadText.Position,
        currentConstraints: MarkdownConstraints,
    ): MarkerBlock.ProcessingResult =
        if (pos.offsetInCurrentLine == -1 || pos.offset >= lineEndOffset) {
            MarkerBlock.ProcessingResult.DEFAULT
        } else {
            MarkerBlock.ProcessingResult.CANCEL
        }
}

private class MultiLineDisplayMathMarkerBlock(
    myConstraints: MarkdownConstraints,
    private val productionHolder: ProductionHolder,
    private val closingNewlineOffset: Int,
    private val closeIndexInLine: Int,
) : MarkerBlockImpl(myConstraints, productionHolder.mark()) {
    private var realInterestingOffset = -1

    override fun allowsSubBlocks(): Boolean = false
    override fun isInterestingOffset(pos: LookaheadText.Position): Boolean = true
    override fun calcNextInterestingOffset(pos: LookaheadText.Position): Int = pos.nextLineOrEofOffset
    override fun getDefaultAction(): MarkerBlock.ClosingAction = MarkerBlock.ClosingAction.DONE
    override fun getDefaultNodeType(): IElementType = GFMElementTypes.BLOCK_MATH

    override fun doProcessToken(
        pos: LookaheadText.Position,
        currentConstraints: MarkdownConstraints,
    ): MarkerBlock.ProcessingResult {
        if (pos.offset < realInterestingOffset) return MarkerBlock.ProcessingResult.CANCEL
        if (pos.offsetInCurrentLine != -1) return MarkerBlock.ProcessingResult.CANCEL

        val nextLineConstraints = constraints.applyToNextLine(pos)
        if (!nextLineConstraints.extendsPrev(constraints)) {
            return MarkerBlock.ProcessingResult.DEFAULT
        }

        val nextLineOffset = pos.nextLineOrEofOffset
        realInterestingOffset = nextLineOffset
        val contentStart = min(
            pos.offset + 1 + nextLineConstraints.getCharsEaten(pos.currentLine),
            nextLineOffset,
        )

        if (pos.offset == closingNewlineOffset) {
            val closeOffset = min(contentStart + closeIndexInLine, nextLineOffset)
            productionHolder.addProduction(
                buildList {
                    if (contentStart < closeOffset) {
                        add(SequentialParser.Node(contentStart..closeOffset, MarkdownTokenTypes.TEXT))
                    }
                    val endDollar = min(closeOffset + 2, nextLineOffset)
                    if (closeOffset < endDollar) {
                        add(SequentialParser.Node(closeOffset..endDollar, GFMTokenTypes.DOLLAR))
                    }
                }
            )
            scheduleProcessingResult(nextLineOffset, MarkerBlock.ProcessingResult.DEFAULT)
        } else if (contentStart < nextLineOffset) {
            productionHolder.addProduction(
                listOf(SequentialParser.Node(contentStart..nextLineOffset, MarkdownTokenTypes.TEXT))
            )
        }

        return MarkerBlock.ProcessingResult.CANCEL
    }
}

private class ChatMathSequentialParser : SequentialParser {
    override fun parse(
        tokens: TokensCache,
        rangesToGlue: List<IntRange>,
    ): SequentialParser.ParsingResult {
        val result = SequentialParser.ParsingResultBuilder()
        val delegateIndices = RangesListBuilder()
        val linkRanges = collectLinkRanges(tokens, rangesToGlue)

        val dollarTokens = ArrayList<TokensCache.Iterator>()
        var linkIdx = 0
        var scan = tokens.RangesListIterator(rangesToGlue)
        while (scan.type != null) {
            if (scan.type == GFMTokenTypes.DOLLAR) {
                while (linkIdx < linkRanges.size && scan.index > linkRanges[linkIdx].last) {
                    linkIdx += 1
                }
                val insideLink = linkIdx < linkRanges.size && scan.index >= linkRanges[linkIdx].first
                if (!insideLink) {
                    dollarTokens += tokens.Iterator(scan.index)
                }
            }
            scan = scan.advance()
        }

        if (dollarTokens.size < 2) {
            var passthrough = tokens.RangesListIterator(rangesToGlue)
            while (passthrough.type != null) {
                delegateIndices.put(passthrough.index)
                passthrough = passthrough.advance()
            }
            return result.withFurtherProcessing(delegateIndices.get())
        }

        val matched = HashMap<Int, Pair<Int, IElementType>>()
        var dIdx = 0
        while (dIdx < dollarTokens.size) {
            val opener = dollarTokens[dIdx]
            when (opener.length) {
                2 -> {
                    val closerPos = findDoubleDollarCloser(tokens, dollarTokens, dIdx)
                    if (closerPos != -1) {
                        val closer = dollarTokens[closerPos]
                        matched[opener.index] = closer.index to GFMElementTypes.BLOCK_MATH
                        dIdx = closerPos + 1
                        continue
                    }
                }
                1 -> if (canOpenSingleDollar(tokens, opener)) {
                    val closerPos = findSingleDollarCloser(tokens, dollarTokens, dIdx)
                    if (closerPos != -1) {
                        val closer = dollarTokens[closerPos]
                        matched[opener.index] = closer.index to GFMElementTypes.INLINE_MATH
                        dIdx = closerPos + 1
                        continue
                    }
                }
            }
            dIdx += 1
        }

        var skipUntil = -1
        var iterator = tokens.RangesListIterator(rangesToGlue)
        while (iterator.type != null) {
            if (iterator.index <= skipUntil) {
                iterator = iterator.advance()
                continue
            }
            val match = matched[iterator.index]
            if (match != null) {
                val (endTokenIndex, nodeType) = match
                result.withNode(SequentialParser.Node(iterator.index..endTokenIndex + 1, nodeType))
                skipUntil = endTokenIndex
            } else {
                delegateIndices.put(iterator.index)
            }
            iterator = iterator.advance()
        }
        return result.withFurtherProcessing(delegateIndices.get())
    }

    private fun findDoubleDollarCloser(
        tokens: TokensCache,
        dollarTokens: List<TokensCache.Iterator>,
        openerPos: Int,
    ): Int {
        val opener = dollarTokens[openerPos]
        for (candidatePos in (openerPos + 1) until dollarTokens.size) {
            val candidate = dollarTokens[candidatePos]
            if (candidate.length != 2) continue
            val inner = tokens.originalText.subSequence(opener.end, candidate.start)
            return if (inner.isNotBlank()) candidatePos else -1
        }
        return -1
    }

    private fun findSingleDollarCloser(
        tokens: TokensCache,
        dollarTokens: List<TokensCache.Iterator>,
        openerPos: Int,
    ): Int {
        val opener = dollarTokens[openerPos]
        val nextPos = openerPos + 1
        if (nextPos >= dollarTokens.size) return -1
        val candidate = dollarTokens[nextPos]
        // TeX 行内 `$...$` 内部不允许未转义裸 `$`；若下一个 `$` 不能合法闭合（如 `$4 和 $x$`），
        // 则前一个 `$` 只是孤立货币符号，直接放弃并把下一个 `$` 留作后续真正的公式起点。
        if (candidate.length != 1 || !canCloseSingleDollar(tokens, candidate)) return -1
        val inner = tokens.originalText.subSequence(opener.end, candidate.start)
        return if (inner.isNotBlank()) nextPos else -1
    }

    private fun canOpenSingleDollar(tokens: TokensCache, iterator: TokensCache.Iterator): Boolean {
        val prev = tokens.getRawCharAt(iterator.start - 1)
        val next = tokens.getRawCharAt(iterator.end)
        if (next == '\u0000' || next.isWhitespace()) return false
        return !prev.isAsciiWordOrDollar()
    }

    private fun canCloseSingleDollar(tokens: TokensCache, iterator: TokensCache.Iterator): Boolean {
        val prev = tokens.getRawCharAt(iterator.start - 1)
        val next = tokens.getRawCharAt(iterator.end)
        if (prev == '\u0000' || prev.isWhitespace()) return false
        return !next.isAsciiWordOrDollar()
    }

    private fun collectLinkRanges(
        tokens: TokensCache,
        rangesToGlue: List<IntRange>,
    ): List<IntRange> {
        val result = ArrayList<IntRange>()
        val scanIndex by lazy { LinkParserUtil.ScanIndex(tokens, rangesToGlue) }
        var iterator: TokensCache.Iterator = tokens.RangesListIterator(rangesToGlue)
        while (iterator.type != null) {
            if (iterator.type == MarkdownTokenTypes.LBRACKET) {
                val link = InlineLinkParser.parseInlineLink(iterator, scanIndex = scanIndex)
                    ?: ReferenceLinkParser.parseReferenceLink(iterator, scanIndex)
                if (link != null) {
                    result.add(iterator.index..link.iteratorPosition.index)
                    iterator = link.iteratorPosition.advance()
                    continue
                }
            }
            iterator = iterator.advance()
        }
        return result
    }
}

private fun Char.isAsciiWordOrDollar(): Boolean =
    this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9' || this == '_' || this == '$'

private fun CharSequence.startsWithDoubleDollarAt(index: Int): Boolean =
    index + 1 < length &&
        this[index] == '$' &&
        this[index + 1] == '$' &&
        (index + 2 >= length || this[index + 2] != '$')

private fun findTrailingDoubleDollar(text: CharSequence): Int? {
    var trimmedEnd = text.length
    while (trimmedEnd > 0 && (text[trimmedEnd - 1] == ' ' || text[trimmedEnd - 1] == '\t' || text[trimmedEnd - 1] == '\r')) {
        trimmedEnd -= 1
    }
    if (trimmedEnd < 2) return null
    val closeIdx = trimmedEnd - 2
    if (text[closeIdx] != '$' || text[closeIdx + 1] != '$') return null
    if (closeIdx > 0 && text[closeIdx - 1] == '$') return null
    if (text.isEscapedAt(closeIdx)) return null
    return closeIdx
}

private fun containsUnescapedDoubleDollar(text: CharSequence): Boolean {
    var index = 0
    while (index + 1 < text.length) {
        if (text[index] == '\\') {
            index += 2
            continue
        }
        if (text[index] == '$' && text[index + 1] == '$') return true
        index += 1
    }
    return false
}

private fun CharSequence.isEscapedAt(index: Int): Boolean {
    var backslashes = 0
    var cursor = index - 1
    while (cursor >= 0 && this[cursor] == '\\') {
        backslashes += 1
        cursor -= 1
    }
    return backslashes % 2 == 1
}

private fun isCodeFenceLine(line: CharSequence): Boolean {
    var spaces = 0
    while (spaces < line.length && spaces < 4 && line[spaces] == ' ') spaces += 1
    if (spaces > 3 || spaces >= line.length) return false
    val fenceChar = line[spaces]
    if (fenceChar != '`' && fenceChar != '~') return false
    var run = 0
    while (spaces + run < line.length && line[spaces + run] == fenceChar) run += 1
    return run >= 3
}
