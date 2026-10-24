package io.github.mangi.eta.ui.markdown

/**
 * Issue #101（PR-A 修復分支）：`$...$` / `$$...$$` 區間計算。
 *
 * 設計約束（相對 PR #122 的差異）：
 * - 輸入是 [TexMathDelimiters.normalize] 之後的源碼（`\(...\)` 已等長替換為 `$$`），
 *   偏移與原文 1:1，可直接用於流式投影跳過判斷。
 * - 保持等長不變式：未閉合的單 `$` 按普通字符處理，不延伸；未閉合的 `$$`
 *   同樣不延伸（[TexMathDelimitersTest.streamingUnclosedDisplayMathConvertsOnceClosed]
 *   要求未閉合不成 MATH），避免整段尾巴被染成 math。
 * - 行內代碼遵循 CommonMark：找不到等長閉合時，開頭反引號只是普通字符
 *  （[TexMathDelimitersTest.unmatchedBacktickDoesNotHideLaterFormula]），不延至文末。
 * - `\[...\]` 必須獨占一行由 [TexMathDelimiters] 保證，此處不重判，只處理 `$` 區間。
 */
internal fun mathRanges(source: String): List<IntRange> {
    if (!source.contains('$')) return emptyList()
    val protected = mathProtectedRanges(source)
    fun isProtected(index: Int): Boolean = protected.containsSorted(index)
    val ranges = mutableListOf<IntRange>()
    var i = 0
    while (i < source.length) {
        if (isProtected(i)) {
            i += 1
            continue
        }
        if (source[i] == '\\') {
            i += 2
            continue
        }
        if (source[i] == '`') {
            var j = i
            while (j < source.length && source[j] == '`') j += 1
            i = j
            continue
        }
        if (source[i] != '$') {
            i += 1
            continue
        }
        if (isEscapedAt(source, i)) {
            i += 1
            continue
        }
        val isDouble = source.getOrNull(i + 1) == '$' && !isEscapedAt(source, i + 1)
        val markerLength = if (isDouble) 2 else 1
        // 單 `$` 後緊跟空白或數字（如 "$ 5"、貨幣 "$5"）不成公式。
        // `$$` 塊級允許後跟換行，不做此限制。
        if (!isDouble) {
            val next = source.getOrNull(i + 1)
            if (next == null || next.isWhitespace() || next.isDigit()) {
                i += 1
                continue
            }
        }
        var j = i + markerLength
        var closed: Int? = null
        while (j < source.length) {
            if (isProtected(j)) {
                j += 1
                continue
            }
            if (source[j] == '\\') {
                j += 2
                continue
            }
            if (isDouble) {
                if (source[j] == '\n' && source.getOrNull(j + 1) == '\n') break
                if (source[j] == '$' && source.getOrNull(j + 1) == '$' &&
                    !isEscapedAt(source, j)
                ) {
                    closed = j + 2
                    break
                }
                j += 1
            } else {
                if (source[j] == '$' && !isEscapedAt(source, j)) {
                    // Closer 只要求前一字符非空白（"$x$ 是" 合法閉合）；
                    // opener 側已拒絕 "$ a$"，此處不再檢查 next。
                    val prev = source.getOrNull(j - 1)
                    if (prev?.isWhitespace() == true) {
                        j += 1
                        continue
                    }
                    closed = j + 1
                    break
                }
                if (source[j] == '\n' && source.getOrNull(j + 1) == '\n') break
                j += 1
            }
        }
        if (closed != null) {
            ranges += i..(closed - 1)
            i = closed
        } else {
            // 未閉合：單 `$`（貨幣 $5）與未閉合 `$$` 都按普通字符處理，不延伸。
            // 流式未閉合保持原文（等長方案有意為之），避免尾段誤染與閃爍。
            i += markerLength
            continue
        }
    }
    return ranges
}

private fun isEscapedAt(source: String, index: Int): Boolean {
    var backslashes = 0
    var i = index - 1
    while (i >= 0 && source[i] == '\\') {
        backslashes += 1
        i -= 1
    }
    return backslashes % 2 == 1
}

/** 已排序區間的二分包含判斷，避免流式逐字符 `any { in }` 的 O(n·m)。 */
internal fun List<IntRange>.containsSorted(index: Int): Boolean {
    var lo = 0
    var hi = size - 1
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        val r = this[mid]
        if (index < r.first) hi = mid - 1 else if (index > r.last) lo = mid + 1 else return true
    }
    return false
}

internal fun mathProtectedRanges(source: String): List<IntRange> {
    val fences = mathFenceRanges(source)
    val sortedFences = fences.sortedBy { it.first }
    val ranges = mutableListOf<IntRange>()
    ranges += fences
    ranges += mathInlineCodeRanges(source, sortedFences)
    return ranges.sortedBy { it.first }
}

private fun mathFenceRanges(source: String): List<IntRange> {
    val ranges = mutableListOf<IntRange>()
    var openStart: Int? = null
    var openMarker = ' '
    var openLength = 0
    val lines = mutableListOf<Pair<Int, String>>()
    var lineStart = 0
    for (pos in source.indices) {
        if (source[pos] == '\n') {
            lines += lineStart to source.substring(lineStart, pos)
            lineStart = pos + 1
        }
    }
    lines += lineStart to source.substring(lineStart)
    for ((start, text) in lines) {
        val marker = mathFenceMarkerOf(text) ?: continue
        if (openStart == null) {
            openStart = start
            openMarker = marker.marker
            openLength = marker.length
        } else if (marker.marker == openMarker && marker.length >= openLength && marker.isClosing) {
            ranges += openStart..(start + text.length - 1)
            openStart = null
        }
    }
    if (openStart != null) {
        ranges += openStart..(source.length - 1)
    }
    return ranges
}

private data class MathFenceMarker(val marker: Char, val length: Int, val isClosing: Boolean)

private fun mathFenceMarkerOf(line: String): MathFenceMarker? {
    var i = 0
    while (i < line.length && i < 4 && line[i] == ' ') i += 1
    if (i > 3) return null
    val marker = line.getOrNull(i)?.takeIf { it == '`' || it == '~' } ?: return null
    var end = i
    while (end < line.length && line[end] == marker) end += 1
    val length = end - i
    if (length < 3) return null
    val suffix = line.substring(end)
    return MathFenceMarker(marker, length, suffix.isBlank())
}

/**
 * 行內代碼 span 保護（CommonMark 語義）：只有找到等長閉合才算 span；
 * 找不到時開頭反引號只是普通字符，繼續向後掃描，不延至文末。
 */
private fun mathInlineCodeRanges(source: String, fenceRanges: List<IntRange>): List<IntRange> {
    val sortedFences = fenceRanges.sortedBy { it.first }
    val ranges = mutableListOf<IntRange>()
    var i = 0
    while (i < source.length) {
        if (sortedFences.containsSorted(i)) {
            i += 1
            continue
        }
        if (source[i] != '`') {
            i += 1
            continue
        }
        var runEnd = i
        while (runEnd < source.length && source[runEnd] == '`') runEnd += 1
        val runLength = runEnd - i
        var j = runEnd
        var found: Int? = null
        while (j < source.length) {
            if (sortedFences.containsSorted(j)) {
                j += 1
                continue
            }
            if (source[j] == '`') {
                var k = j
                while (k < source.length && source[k] == '`') k += 1
                if (k - j == runLength) {
                    found = j
                    break
                }
                j = k
                continue
            }
            if (source[j] == '\n' && source.getOrNull(j + 1) == '\n') break
            j += 1
        }
        if (found != null) {
            ranges += i..(found + runLength - 1)
            i = found + runLength
        } else {
            // 未閉合反引號是普通字符，只跳過開頭 run，後續公式照常識別。
            i = runEnd
        }
    }
    return ranges
}
