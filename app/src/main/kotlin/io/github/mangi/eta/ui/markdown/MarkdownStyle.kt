package io.github.mangi.eta.ui.markdown

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import io.github.mangi.eta.ui.components.EtaMonospace
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 正文与思考共用同一套渲染器，只在字号、墨色浓度与留白节奏上区分层级。 */
internal enum class MarkdownTone { Answer, Thinking }

internal val EditorialLineHeightStyle = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.None,
)

/**
 * 一次渲染所需的全部样式。按主题与语气整体记忆，块级组件只读取不再推导，
 * 避免每个段落各自拼一遍 TextStyle。
 */
@Immutable
internal class MarkdownStyle(
    val tone: MarkdownTone,
    val isDark: Boolean,
    val body: TextStyle,
    private val headings: List<TextStyle>,
    val code: TextStyle,
    val table: TextStyle,
    val textColor: Color,
    val strongColor: Color,
    val quoteTextColor: Color,
    val secondaryColor: Color,
    val markerColor: Color,
    val subtleBorderColor: Color,
    val ruleColor: Color,
    val quoteBackground: Color,
    val quoteBarColor: Color,
    val codeBackground: Color,
    val codeHeaderBackground: Color,
    val tableHeaderBackground: Color,
    val inline: MarkdownInlineStyle,
) {
    fun heading(level: Int): TextStyle = headings[(level - 1).coerceIn(0, headings.lastIndex)]

    /** 引用块内的排版样式：保持清晰可读的次主墨色，而不是退化成发虚的说明灰。 */
    fun quoted(): MarkdownStyle = MarkdownStyle(
        tone = tone,
        isDark = isDark,
        body = body.copy(color = quoteTextColor),
        headings = headings.map { it.copy(color = strongColor) },
        code = code,
        table = table.copy(color = quoteTextColor),
        textColor = quoteTextColor,
        strongColor = strongColor,
        quoteTextColor = quoteTextColor,
        secondaryColor = secondaryColor,
        markerColor = markerColor,
        subtleBorderColor = subtleBorderColor,
        ruleColor = ruleColor,
        quoteBackground = quoteBackground,
        quoteBarColor = quoteBarColor,
        codeBackground = codeBackground,
        codeHeaderBackground = codeHeaderBackground,
        tableHeaderBackground = tableHeaderBackground,
        inline = inline,
    )
}

@Composable
internal fun rememberMarkdownStyle(tone: MarkdownTone): MarkdownStyle {
    val colors = MiuixTheme.colorScheme
    val textStyles = MiuixTheme.textStyles
    return remember(tone, colors, textStyles) {
        val answer = tone == MarkdownTone.Answer
        val isDark = colors.surface.luminance() < 0.5f
        // COUI 与 Miuix 的 onSurface 自带不同基础 alpha；统一归一为纯墨色基色后再按层级分配浓度，
        // 避免两套主题下细线、引用与正文的视觉重量发生漂移。
        val ink = colors.onSurface.copy(alpha = 1f)
        val strongColor = if (answer) {
            ink.copy(alpha = if (isDark) 0.96f else 0.95f)
        } else {
            ink.copy(alpha = if (isDark) 0.78f else 0.74f)
        }
        val textColor = if (answer) {
            ink.copy(alpha = if (isDark) 0.88f else 0.86f)
        } else {
            ink.copy(alpha = if (isDark) 0.60f else 0.56f)
        }
        val quoteTextColor = if (answer) {
            ink.copy(alpha = if (isDark) 0.80f else 0.76f)
        } else {
            ink.copy(alpha = if (isDark) 0.56f else 0.52f)
        }
        val secondaryColor = if (answer) {
            ink.copy(alpha = if (isDark) 0.56f else 0.52f)
        } else {
            ink.copy(alpha = if (isDark) 0.48f else 0.44f)
        }
        val markerColor = if (answer) {
            ink.copy(alpha = if (isDark) 0.50f else 0.44f)
        } else {
            ink.copy(alpha = if (isDark) 0.44f else 0.40f)
        }
        val body = (if (answer) textStyles.body1 else textStyles.body2).copy(
            fontSize = if (answer) 16.sp else 13.5.sp,
            lineHeight = if (answer) ANSWER_LINE_HEIGHT_SP.sp else THINKING_LINE_HEIGHT_SP.sp,
            letterSpacing = if (answer) 0.1.sp else 0.sp,
            color = textColor,
            lineHeightStyle = EditorialLineHeightStyle,
        )
        // 中文无衬线体下 SemiBold 与常规字重接近；标题与重点统一使用 Bold + 实墨色拉开层级。
        val headings = if (answer) {
            listOf(
                body.copy(
                    fontSize = 20.sp,
                    lineHeight = 28.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.2).sp,
                    color = strongColor,
                ),
                body.copy(
                    fontSize = 18.sp,
                    lineHeight = 26.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.1).sp,
                    color = strongColor,
                ),
                body.copy(
                    fontSize = 16.5.sp,
                    lineHeight = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = strongColor,
                ),
                body.copy(
                    fontSize = 16.sp,
                    lineHeight = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = strongColor,
                ),
            )
        } else {
            listOf(
                body.copy(
                    fontSize = 14.sp,
                    lineHeight = THINKING_LINE_HEIGHT_SP.sp,
                    fontWeight = FontWeight.Bold,
                    color = strongColor,
                ),
            )
        }
        val codeSurface = if (isDark && colors.surfaceContainer.luminance() < 0.005f) {
            Color(0xFF16171A)
        } else {
            colors.surfaceContainer
        }
        val inlineCodeColor = if (answer) {
            ink.copy(alpha = if (isDark) 0.93f else 0.90f)
        } else {
            strongColor
        }
        MarkdownStyle(
            tone = tone,
            isDark = isDark,
            body = body,
            headings = headings,
            code = TextStyle(
                fontFamily = EtaMonospace,
                fontSize = if (answer) 13.sp else 12.sp,
                lineHeight = if (answer) 20.sp else 18.sp,
                color = inlineCodeColor,
                lineHeightStyle = EditorialLineHeightStyle,
            ),
            table = body.copy(
                fontSize = if (answer) 14.sp else 13.sp,
                lineHeight = if (answer) 21.sp else 19.sp,
            ),
            textColor = textColor,
            strongColor = strongColor,
            quoteTextColor = quoteTextColor,
            secondaryColor = secondaryColor,
            markerColor = markerColor,
            subtleBorderColor = ink.copy(alpha = if (isDark) 0.11f else 0.075f),
            ruleColor = ink.copy(alpha = if (isDark) 0.09f else 0.065f),
            quoteBackground = ink.copy(alpha = if (isDark) 0.055f else 0.034f),
            quoteBarColor = ink.copy(alpha = if (isDark) 0.24f else 0.18f),
            codeBackground = codeSurface,
            codeHeaderBackground = ink.copy(alpha = if (isDark) 0.04f else 0.025f),
            tableHeaderBackground = ink.copy(alpha = if (isDark) 0.065f else 0.038f),
            inline = MarkdownInlineStyle(
                linkColor = colors.primary,
                codeBackground = ink.copy(alpha = if (isDark) 0.11f else 0.06f),
                strongColor = strongColor,
                codeColor = inlineCodeColor,
            ),
        )
    }
}

/**
 * 相邻块之间的留白。源码中的空行只用于切分块，不直接占据高度；可见间距按语义分配：
 * 标题前更宽以开启新章节，标题与引导段后收紧使其贴近所属内容，分割线上下保持均等呼吸感。
 */
internal fun markdownBlockGap(previous: MarkdownBlock?, current: MarkdownBlock, tone: MarkdownTone): TextUnit {
    if (previous == null) return 0.sp
    val gap = when {
        previous is MarkdownRule || current is MarkdownRule -> 16f
        current is MarkdownHeading && previous is MarkdownHeading -> 8f
        current is MarkdownHeading -> when {
            current.level == 1 -> 22f
            current.level == 2 -> 20f
            else -> 16f
        }
        previous is MarkdownHeading -> 6f
        previous is MarkdownParagraph && current is MarkdownList -> 8f
        previous is MarkdownParagraph && current is MarkdownParagraph -> 11f
        else -> 13f
    }
    val scale = if (tone == MarkdownTone.Answer) 1f else THINKING_GAP_SCALE
    return (gap * scale).sp
}

/** 紧凑列表项内部：纯文本与子列表保持紧凑，嵌套引用、代码或表格时留出结构间距。 */
internal fun markdownCompactGap(
    previous: MarkdownBlock?,
    current: MarkdownBlock,
    tone: MarkdownTone,
): TextUnit {
    if (previous == null) return 0.sp
    val structured = previous.isStructuredContainer() || current.isStructuredContainer()
    return when {
        structured -> if (tone == MarkdownTone.Answer) 8.sp else 6.sp
        else -> if (tone == MarkdownTone.Answer) 5.sp else 3.5.sp
    }
}

internal fun MarkdownBlock.isStructuredContainer(): Boolean = when (this) {
    is MarkdownQuote, is MarkdownCode, is MarkdownAlert, is MarkdownTable, is MarkdownRule -> true
    else -> false
}

internal const val ANSWER_LINE_HEIGHT_SP = 25
internal const val THINKING_LINE_HEIGHT_SP = 21
private const val THINKING_GAP_SCALE = 0.75f
