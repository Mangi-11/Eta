package io.github.mangi.eta.ui.markdown

/**
 * Issue #101 階段2：直接渲染 LaTeX 內容。
 *
 * 策略：塊級公式（[MarkdownCode.isMath]）用系統 WebView + KaTeX（CDN）排版，
 * 行內公式仍按行內代碼樣式顯示原文（基線對齊在 WebView 內難以做好，且每公式一 WebView 開銷大）。
 *
 * 降級保證（階段1不變式延續）：CDN 離線 / KaTeX 解析失敗 / WebView 不可用時，
 * 顯示原文代碼塊（可複製），永不消失。HTML 模板內的公式只經 JS 字符串轉義後注入，
 * 不做字符串拼接進 `<script>` 執行上下文之外的 HTML，避免 `</script>` 突破。
 */
internal object LatexRenderer {
    const val KATEX_VERSION = "0.16.11"
    const val KATEX_CSS_URL = "https://cdn.jsdelivr.net/npm/katex@$KATEX_VERSION/dist/katex.min.css"
    const val KATEX_JS_URL = "https://cdn.jsdelivr.net/npm/katex@$KATEX_VERSION/dist/katex.min.js"

    fun buildKatexHtml(latex: String, displayMode: Boolean, dark: Boolean): String {
        val textColor = if (dark) "#e8e8e8" else "#1a1a1a"
        val jsLatex = escapeForJsString(latex)
        val htmlFallback = escapeHtml(latex)
        val mode = if (displayMode) "true" else "false"
        return """
            <!DOCTYPE html>
            <html><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <link rel="stylesheet" href="$KATEX_CSS_URL">
            <style>
              html,body{margin:0;padding:8px 12px;background:transparent;color:$textColor;}
              #math{overflow-x:auto;overflow-y:hidden;}
              #fallback{display:none;white-space:pre-wrap;word-break:break-word;font-family:monospace;font-size:13px;}
            </style>
            </head><body>
            <div id="math"></div>
            <pre id="fallback">$htmlFallback</pre>
            <script src="$KATEX_JS_URL"></script>
            <script>
              function showFallback(){document.getElementById('fallback').style.display='block';}
              try {
                if (window.katex) {
                  // S1 安全：trust:false 禁止 \href{javascript:}、\htmlStyle 等可點擊/覆蓋載體。
                  window.katex.render("$jsLatex", document.getElementById('math'), {displayMode:$mode, throwOnError:true, trust:false});
                } else { showFallback(); }
              } catch (e) { showFallback(); }
            </script>
            </body></html>
        """.trimIndent()
    }

    /** 注入雙引號 JS 字符串字面量：處理 `\`、`"`、換行與 `</script>` 突破。 */
    internal fun escapeForJsString(raw: String): String = buildString(raw.length + 16) {
        for (ch in raw) {
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\u2028' -> append("\\u2028")
                '\u2029' -> append("\\u2029")
                '<' -> append("\\u003c")
                '>' -> append("\\u003e")
                else -> append(ch)
            }
        }
    }

    internal fun escapeHtml(raw: String): String = buildString(raw.length) {
        for (ch in raw) {
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                else -> append(ch)
            }
        }
    }
}
