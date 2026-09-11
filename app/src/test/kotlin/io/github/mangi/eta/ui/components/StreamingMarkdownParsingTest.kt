package io.github.mangi.eta.ui.components

import io.github.mangi.eta.ui.markdown.StreamingGfmParserSession
import io.github.mangi.eta.ui.markdown.StreamingGfmSnapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingMarkdownParsingTest {
    @Test
    fun continuousAppendsPublishProgressBeforeTheStreamEnds() = runBlocking {
        val targets = Channel<StreamingMarkdownTarget>(Channel.CONFLATED)
        val parser = StreamingGfmParserSession()
        val published = mutableListOf<StreamingGfmSnapshot>()
        val chunk = "继续分析这一段内容。\n\n"
        targets.send(StreamingMarkdownTarget(chunk, isStreaming = true))
        var parseCount = 0

        consumeStreamingMarkdownTargets(
            targets = targets,
            parse = { target ->
                parseCount += 1
                // 每次解析尚未返回时就收到下一批内容，模拟生产速度持续超过解析速度。
                if (parseCount < 20) {
                    targets.send(target.copy(content = target.content + chunk))
                } else {
                    targets.close()
                }
                parser.parse(target.content, isComplete = !target.isStreaming)
            },
            publish = published::add,
        )

        assertEquals(20, published.size)
        assertTrue(published.all { !it.isComplete })
        assertEquals((1..20).map { chunk.repeat(it) }, published.map { it.originalSource })
    }

    @Test
    fun pendingTargetsAreCoalescedWithoutParsingEveryNetworkChunk() = runBlocking {
        val targets = Channel<StreamingMarkdownTarget>(Channel.CONFLATED)
        val parser = StreamingGfmParserSession()
        val parsedSources = mutableListOf<String>()
        repeat(100) { targets.send(StreamingMarkdownTarget("内容".repeat(it + 1), true)) }
        targets.close()

        consumeStreamingMarkdownTargets(
            targets = targets,
            parse = { target ->
                parsedSources += target.content
                parser.parse(target.content, isComplete = !target.isStreaming)
            },
            publish = {},
        )

        assertEquals(listOf("内容".repeat(100)), parsedSources)
    }

    @Test
    fun correctedSourceDoesNotPublishAnObsoleteSnapshot() = runBlocking {
        val targets = Channel<StreamingMarkdownTarget>(Channel.CONFLATED)
        val parser = StreamingGfmParserSession()
        val published = mutableListOf<StreamingGfmSnapshot>()
        targets.send(StreamingMarkdownTarget("旧内容", isStreaming = true))

        consumeStreamingMarkdownTargets(
            targets = targets,
            parse = { target ->
                if (target.content == "旧内容") {
                    targets.send(StreamingMarkdownTarget("修正后的内容", isStreaming = false))
                    targets.close()
                }
                parser.parse(target.content, isComplete = !target.isStreaming)
            },
            publish = published::add,
        )

        assertEquals(listOf("修正后的内容"), published.map { it.originalSource })
        assertTrue(published.single().isComplete)
    }

    @Test
    fun completionWithUnchangedTextStillPublishesTheFinalSnapshot() = runBlocking {
        val targets = Channel<StreamingMarkdownTarget>(Channel.CONFLATED)
        val parser = StreamingGfmParserSession()
        val published = mutableListOf<StreamingGfmSnapshot>()
        targets.send(StreamingMarkdownTarget("**最终内容**", isStreaming = true))

        consumeStreamingMarkdownTargets(
            targets = targets,
            parse = { target ->
                if (target.isStreaming) {
                    targets.send(target.copy(isStreaming = false))
                    targets.close()
                }
                parser.parse(target.content, isComplete = !target.isStreaming)
            },
            publish = published::add,
        )

        assertEquals(listOf(false, true), published.map { it.isComplete })
        assertTrue(published.last().state.linksLookedUp)
    }

    @Test
    fun resumedStreamDoesNotPublishAnObsoleteCompletion() = runBlocking {
        val targets = Channel<StreamingMarkdownTarget>(Channel.CONFLATED)
        val parser = StreamingGfmParserSession()
        val published = mutableListOf<StreamingGfmSnapshot>()
        targets.send(StreamingMarkdownTarget("同一份内容", isStreaming = false))

        consumeStreamingMarkdownTargets(
            targets = targets,
            parse = { target ->
                if (!target.isStreaming) {
                    targets.send(target.copy(isStreaming = true))
                    targets.close()
                }
                parser.parse(target.content, isComplete = !target.isStreaming)
            },
            publish = published::add,
        )

        assertEquals(1, published.size)
        assertFalse(published.single().isComplete)
    }

    @Test
    fun disposingConsumerCancelsParsingWithoutPublishingLateResults() = runBlocking {
        val targets = Channel<StreamingMarkdownTarget>(Channel.CONFLATED)
        val started = CompletableDeferred<Unit>()
        val published = mutableListOf<StreamingGfmSnapshot>()
        targets.send(StreamingMarkdownTarget("思考内容", isStreaming = true))
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            consumeStreamingMarkdownTargets(
                targets = targets,
                parse = {
                    started.complete(Unit)
                    awaitCancellation()
                },
                publish = published::add,
            )
        }

        started.await()
        job.cancelAndJoin()

        assertTrue(published.isEmpty())
        assertTrue(job.isCancelled)
    }
}
