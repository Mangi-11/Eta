package io.github.mangi.eta.ui.markdown

import androidx.compose.foundation.OverscrollEffect
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text

/**
 * 已完成内容的 Markdown：历史消息、思考全文与流式结束后的静态视图共用。
 *
 * [parsed] 是流式会话的终态快照，可直接复用，切换时不重解析、不经过占位文字。
 * 其余情况在后台解析；命中缓存时首帧即为最终排版，长会话来回滚动不会反复解析。
 */
@Composable
internal fun StaticMarkdown(
    content: String,
    tone: MarkdownTone,
    modifier: Modifier = Modifier,
    parsed: MarkdownDocument? = null,
) {
    val style = rememberMarkdownStyle(tone)
    val reusable = parsed?.takeIf { it.inlineStyle == style.inline }
    val document = reusable ?: produceState(
        initialValue = staticMarkdownDocumentCache.get(content, style.inline),
        content,
        style.inline,
    ) {
        value = staticMarkdownDocumentCache.get(content, style.inline)
            ?: withContext(Dispatchers.Default) { staticMarkdownDocumentCache.parse(content, style.inline) }
    }.value
    if (document == null) {
        // 解析完成前以同字号原文占位，高度与最终排版接近，避免列表越界绘制。
        Text(text = content, style = style.body, modifier = modifier)
    } else {
        MarkdownContent(document = document, style = style, modifier = modifier)
    }
}

/**
 * 独立滚动视口内的长篇静态 Markdown（如 MEMORY.md 预览）。
 *
 * 解析未完成前不渲染整篇原文占位（避免几十 KiB 的单节点 MultiParagraph 在页面进入动画中阻塞主线程），
 * 解析后通过 [LazyMarkdownContent] 按视口虚拟化渲染。
 */
@Composable
internal fun LazyStaticMarkdown(
    content: String,
    tone: MarkdownTone,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    overscrollEffect: OverscrollEffect? = null,
) {
    val style = rememberMarkdownStyle(tone)
    val document = produceState(
        initialValue = staticMarkdownDocumentCache.get(content, style.inline),
        content,
        style.inline,
    ) {
        value = staticMarkdownDocumentCache.get(content, style.inline)
            ?: withContext(Dispatchers.Default) { staticMarkdownDocumentCache.parse(content, style.inline) }
    }.value ?: return
    LazyMarkdownContent(
        document = document,
        style = style,
        modifier = modifier,
        contentPadding = contentPadding,
        overscrollEffect = overscrollEffect,
    )
}

/**
 * 流式内容的 Markdown。解析在后台串行进行，最新目标覆盖尚未开始的旧目标；
 * 正文语气接入逐字显现，思考语气直接显示每次解析结果。
 *
 * [onRevealCompleteChange] 在当前全文的终态快照完成排版、且显现动画排空后回调 true，
 * 调用方据此切换到可选择的静态视图。
 */
@Composable
internal fun StreamingMarkdown(
    state: StreamingMarkdownState,
    content: String,
    isStreaming: Boolean,
    tone: MarkdownTone,
    modifier: Modifier = Modifier,
    onRevealCompleteChange: (Boolean) -> Unit = {},
) {
    val style = rememberMarkdownStyle(tone)
    // 思考紧跟已收到的增量，避免高速思考先排版占位、再受正文逐字速度限制而积压。
    val reveal = state.revealCoordinator.takeIf { tone == MarkdownTone.Answer }
    val snapshot = state.snapshot
    val currentContent by rememberUpdatedState(content)
    val currentIsStreaming by rememberUpdatedState(isStreaming)
    val currentCallback by rememberUpdatedState(onRevealCompleteChange)
    val restoreGeneration = state.restoreState.generation

    LifecycleResumeEffect(state) {
        state.revealCoordinator.pauseAnimationsAndCatchUp()
        state.restoreState.begin(currentContent)
        onPauseOrDispose {
            state.restoreState.pause()
            state.revealCoordinator.pauseAnimationsAndCatchUp()
        }
    }
    LaunchedEffect(reveal) {
        reveal?.runFrameClock()
    }
    LaunchedEffect(state) {
        state.parseUpdates()
    }
    LaunchedEffect(content, isStreaming, style.inline) {
        state.parseTargets.trySend(StreamingMarkdownTarget(content, isStreaming, style.inline))
        if (isStreaming) currentCallback(false)
    }
    LaunchedEffect(content, isStreaming, snapshot, reveal) {
        val target = snapshot
        if (!isStreamingMarkdownTargetComplete(content, isStreaming, target?.originalSource, target?.isComplete == true)) {
            currentCallback(false)
            return@LaunchedEffect
        }
        // 等终态文档完成组合与排版后，再等待尾部字符的显现收口。
        withFrameNanos { }
        if (reveal != null && !reveal.drained.value) reveal.drained.filter { it }.first()
        if (isStreamingMarkdownTargetComplete(
                currentContent,
                currentIsStreaming,
                target?.originalSource,
                target?.isComplete == true,
            )
        ) {
            currentCallback(true)
        }
    }

    val parsed = snapshot ?: return
    if (reveal != null) {
        SideEffect { reveal.retainBlocks(parsed.document.revealKeys) }
    }
    MarkdownContent(
        document = parsed.document,
        style = style,
        reveal = reveal,
        modifier = modifier.onGloballyPositioned {
            // 恢复基线对应的文档真正排版后才开放增量动画，解析耗时不受帧数限制。
            if (state.restoreState.completeLayout(
                    generation = restoreGeneration,
                    renderedContent = parsed.originalSource,
                    currentContent = currentContent,
                )
            ) {
                state.revealCoordinator.resumeAnimationsAfterCatchUp()
            }
        },
    )
}
