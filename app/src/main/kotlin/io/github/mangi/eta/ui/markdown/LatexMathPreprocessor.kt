package io.github.mangi.eta.ui.markdown

/**
 * Issue #101：AI 输出的 LaTeX 直接消失。
 *
 * 底層 `intellij-markdown` 的 GFM 解析器只識別 `$...$` / `$$...$$` 為
 * `INLINE_MATH` / `BLOCK_MATH`，國產模型常用的 `\(...\)` / `\[...\]`
 * 會走普通 TEXT 路徑，被 [decodeMarkdownText] 把反斜杠當 CommonMark
 * 轉義剝掉（`\(` → `(`），再被寬鬆強調配對吃掉 `_` / `*`。
 *
 * 本預處理在解析前把 `\(...\)` → `$...$`、`\[...\]` → `$$...$$`，
 * 讓它們進入既有的 MATH 節點兜底（行內代碼 / LaTeX 代碼塊樣式顯示原文），
 * 保證「永不消失，只允許變樣式」。圍欄代碼塊與行內代碼 span 內的不轉換。
 */
internal object LatexMathPreprocessor {
    fun normalize(source: String, isComplete: Boolean): String {
        if (source.isEmpty()) return source
        if (!source.contains('\\') && !source.contains('$')) return source
        val protectedRanges = protectedRanges(source)
        fun isProtected(index: Int): Boolean = protectedRanges.containsSorted(index)

        val out = StringBuilder(source.length + 8)
        var i = 0
        while (i < source.length) {
            if (isProtected(i)) {
                out.append(source[i])
                i += 1
                continue
            }
            // 已轉義的反斜杠（前面有奇數個 `\`）本身不是分隔符，照原樣輸出一個字符。
            if (source[i] == '\\' && isEscaped(source, i)) {
                out.append(source[i])
                i += 1
                continue
            }
            if (source[i] == '\\' && i + 1 < source.length &&
                (source[i + 1] == '(' || source[i + 1] == '[')
            ) {
                val isBlock = source[i + 1] == '['
                val closer = if (isBlock) "\\]" else "\\)"
                val closeIndex = findCloser(source, from = i + 2, closer = closer, protectedRanges = protectedRanges)
                if (closeIndex != null) {
                    val inner = source.substring(i + 2, closeIndex)
                    if (isBlock) {
                        out.append("$$\n")
                        out.append(inner.trim('\n'))
                        out.append("\n$$")
                    } else {
                        out.append('$')
                        out.append(inner)
                        out.append('$')
                    }
                    i = closeIndex + 2
                    continue
                }
                // 未閉合：終態按原文保留（交給 decode 路徑顯示）；流式期間虛擬閉合，
                // 讓它提前成為 MATH 節點，避免反斜杠被剝掉而「直接消失」。
                // 虛擬閉合只延伸到段末（遇空行停），餘下段落繼續掃描歸一化，避免後段真公式被跳過。
                if (!isComplete) {
                    val tail = source.substring(i + 2)
                    val paraEnd = tail.indexOf("\n\n").let { if (it == -1) tail.length else it }
                    val inner = tail.substring(0, paraEnd)
                    if (isBlock) {
                        out.append("$$\n")
                        out.append(inner.trim('\n'))
                        out.append("\n$$")
                    } else {
                        out.append('$')
                        out.append(inner)
                        out.append('$')
                    }
                    i = i + 2 + paraEnd
                    continue
                }
                out.append(source[i])
                out.append(source[i + 1])
                i += 2
                continue
            }
            out.append(source[i])
            i += 1
        }
        return out.toString()
    }

    private fun findCloser(
        source: String,
        from: Int,
        closer: String,
        protectedRanges: List<IntRange>,
    ): Int? {
        var i = from
        while (i + 1 < source.length) {
            // 行內 \(...\) 與塊 \[...\] 都不跨段落空行：避免兩個遠端公式被錯配成一對。
            if (source[i] == '\n' && source.getOrNull(i + 1) == '\n') return null
            if (protectedRanges.containsSorted(i)) {
                i += 1
                continue
            }
            if (source[i] == '\\' && isEscaped(source, i)) {
                i += 1
                continue
            }
            if (source[i] == '\\' && source[i + 1] == closer[1] &&
                (closer[1] == ')' || closer[1] == ']')
            ) {
                // 確認是 closer（\( 配 \)，\[ 配 \]）， opener 已限定所以只比第二字符即可。
                return i
            }
            i += 1
        }
        return null
    }

    private fun isEscaped(source: String, index: Int): Boolean {
        var backslashes = 0
        var i = index - 1
        while (i >= 0 && source[i] == '\\') {
            backslashes += 1
            i -= 1
        }
        return backslashes % 2 == 1
    }

    /**
     * 圍欄代碼塊與行內代碼 span 內的 LaTeX 分隔符不轉換：
     * 用戶貼的源碼示例必須保持原樣。
     */
    internal fun protectedRanges(source: String): List<IntRange> {
        val ranges = mutableListOf<IntRange>()
        ranges += fenceRanges(source)
        ranges += inlineCodeRanges(source, fenceRanges = ranges)
        return ranges.sortedBy { it.first }
    }

    private fun fenceRanges(source: String): List<IntRange> {
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
            val marker = fenceMarkerOf(text) ?: continue
            if (openStart == null) {
                // 與 StreamingGfmParser.findOpenFence 對齊：任何圍欄行都可打開
                // （含無語言的裸 ```），裸圍欄的 suffix.isBlank() 為 true 也不例外。
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

    private data class FenceMarker(val marker: Char, val length: Int, val isClosing: Boolean)

    private fun fenceMarkerOf(line: String): FenceMarker? {
        var i = 0
        while (i < line.length && i < 4 && line[i] == ' ') i += 1
        if (i > 3) return null
        val marker = line.getOrNull(i)?.takeIf { it == '`' || it == '~' } ?: return null
        var end = i
        while (end < line.length && line[end] == marker) end += 1
        val length = end - i
        if (length < 3) return null
        // ``` 語言行不是 closing；``` 空行才是。
        val suffix = line.substring(end)
        return FenceMarker(marker, length, suffix.isBlank())
    }

    private fun inlineCodeRanges(source: String, fenceRanges: List<IntRange>): List<IntRange> {
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
                // 行內代碼不能跨段落空行。
                if (source[j] == '\n' && source.getOrNull(j + 1) == '\n') break
                j += 1
            }
            if (found != null) {
                ranges += i..(found + runLength - 1)
                i = found + runLength
            } else {
                // 未閉合的行內代碼延到文末（與圍欄未閉合一致）：其內的 `$` / `\(` 不當數學，
                // 避免 "執行 `adb $5" 把 $5 誤判為公式。
                ranges += i..(source.length - 1)
                break
            }
        }
        return ranges
    }
}

/**
 * `$...$` / `$$...$$` 區間：流式投影（表格緩衝、連結截斷、強調虛擬閉合）
 * 必須跳過這些區間，否則公式裡的 `|` / `[` / `*` 會讓整段公式在流式期間消失或抖動。
 */
internal fun mathRanges(source: String): List<IntRange> {
    if (!source.contains('$')) return emptyList()
    val protected = LatexMathPreprocessor.protectedRanges(source)
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
        // Issue #101 BLOCKER-1：單 `$` 後緊跟空白（如 "$ 5"）不成公式，避免貨幣/普通文本污染。
        // `$$` 塊級允許後跟換行，不做此限制。
        if (!isDouble) {
            val next = source.getOrNull(i + 1)
            if (next == null || next.isWhitespace()) {
                i += 1
                continue
            }
        }
        var j = i + markerLength
        var closed: Int? = null
        while (j < source.length) {
            if (source[j] == '\\') {
                j += 2
                continue
            }
            if (isDouble) {
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
        } else if (isDouble) {
            // 未閉合的 `$$` 視為流式中的塊公式候選，延伸到段末（遇空行停），後段繼續掃描。
            var end = i + markerLength
            while (end + 1 < source.length &&
                !(source[end] == '\n' && source[end + 1] == '\n')
            ) end += 1
            ranges += i..end
            i = end
            // end 停在段末或文末：若停在 "\n\n" 的首個 \n，留給後段繼續掃描。
            if (end + 1 < source.length) continue else break
        } else {
            // 未閉合的單 `$`（如貨幣 $5）按普通字符處理，不延伸，避免整段尾巴變 math。
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
