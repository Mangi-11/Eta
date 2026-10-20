package io.github.mangi.eta.hook.xiaoai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class XiaoAiHookStateTest {
    @Test
    fun versionGateOnlyAcceptsTheAnalyzedApks() {
        assertTrue(XiaoAiTakeoverPolicy.isSupportedVersion(507013032L))
        assertTrue(XiaoAiTakeoverPolicy.isSupportedVersion(507013034L))
        assertFalse(XiaoAiTakeoverPolicy.isSupportedVersion(507013031L))
        assertFalse(XiaoAiTakeoverPolicy.isSupportedVersion(507013033L))
        assertFalse(XiaoAiTakeoverPolicy.isSupportedVersion(-1L))
    }

    @Test
    fun outboundMatchUsesTheEventTypeBecauseXiaoAiRegeneratesTheEventId() {
        assertTrue(XiaoAiTakeoverPolicy.matchesOutboundEvent("Nlp.Request"))
        assertFalse(XiaoAiTakeoverPolicy.matchesOutboundEvent("SpeechRecognizer.Recognize"))
    }

    @Test
    fun takeoverRequiresEnabledConfigAndHonorsBothPrefixForms() {
        assertNull(
            XiaoAiTakeoverPolicy.decide(
                query = "/agent 测试",
                hasImage = false,
                customModelEnabled = false,
                requirePrefix = true,
            )
        )
        assertEquals(
            "测试",
            XiaoAiTakeoverPolicy.decide(
                query = "/agent 测试",
                hasImage = false,
                customModelEnabled = true,
                requirePrefix = true,
            )?.prompt,
        )
        assertEquals(
            "测试",
            XiaoAiTakeoverPolicy.decide(
                query = "/agent%20测试",
                hasImage = false,
                customModelEnabled = true,
                requirePrefix = true,
            )?.prompt,
        )
        assertNull(
            XiaoAiTakeoverPolicy.decide(
                query = "测试",
                hasImage = false,
                customModelEnabled = true,
                requirePrefix = true,
            )
        )
    }

    @Test
    fun imageOnlyRequestGetsDefaultPromptWhenPrefixIsNotRequired() {
        assertEquals(
            "请分析这张图片",
            XiaoAiTakeoverPolicy.decide(
                query = "blank",
                hasImage = true,
                customModelEnabled = true,
                requirePrefix = false,
            )?.prompt,
        )
        assertNull(
            XiaoAiTakeoverPolicy.decide(
                query = "blank",
                hasImage = true,
                customModelEnabled = true,
                requirePrefix = true,
            )
        )
    }

    @Test
    fun queryCacheIsBoundedConsumedOnceAndExpires() {
        var now = 0L
        val cache = XiaoAiQueryCache(
            capacity = 2,
            ttlMillis = 100L,
            clock = { now },
        )
        cache.put("one", "1", null)
        cache.put("two", "2", "image")
        cache.put("three", "3", null)

        assertNull(cache.take("one"))
        assertEquals("image", cache.take("two")?.imageFileId)
        assertNull(cache.take("two"))
        now = 101L
        assertNull(cache.take("three"))
        assertEquals(0, cache.size())
    }

    @Test
    fun queryCacheCanCorrelateARegeneratedEventIdByQueryText() {
        val cache = XiaoAiQueryCache()
        cache.put("query-dialog", "测试问题", "image")

        val correlated = cache.takeMatching(
            eventId = "new-event-id",
            eventQuery = "测试问题",
        )

        assertEquals("query-dialog", correlated?.dialogId)
        assertEquals("image", correlated?.imageFileId)
        assertNull(cache.takeMatching("another-event", "测试问题"))
    }

    @Test
    fun queryCacheStillPrefersAnExactDialogId() {
        val cache = XiaoAiQueryCache()
        cache.put("exact", "第一个问题", null)
        cache.put("other", "第二个问题", null)

        assertEquals(
            "第一个问题",
            cache.takeMatching("exact", "第二个问题")?.query,
        )
        assertEquals("第二个问题", cache.take("other")?.query)
    }

    @Test
    fun turnTrackerKeepsOnlyAFreshLatestTurn() {
        var now = 0L
        val tracker = XiaoAiTurnTracker(
            ttlMillis = 100L,
            clock = { now },
        )
        tracker.capture(
            dialogId = "asr-dialog",
            query = "测试",
            hasImage = true,
            documentInput = false,
        )

        assertEquals("测试", tracker.latest()?.query)
        assertTrue(tracker.latest()?.hasImage == true)
        now = 101L
        assertNull(tracker.latest())
    }

    @Test
    fun recentIdsSuppressDuplicatesWithinTtl() {
        var now = 0L
        val recent = XiaoAiRecentIds(
            capacity = 2,
            ttlMillis = 100L,
            clock = { now },
        )
        recent.add("dialog")
        assertTrue(recent.contains("dialog"))
        now = 101L
        assertFalse(recent.contains("dialog"))
    }

    @Test
    fun runSlotReplacesAndConditionallyClearsTheActiveRun() {
        val slot = XiaoAiRunSlot<Any>()
        val first = Any()
        val second = Any()

        assertNull(slot.replace(first))
        assertSame(first, slot.replace(second))
        assertFalse(slot.clear(first))
        assertSame(second, slot.get())
        assertTrue(slot.clear(second))
        assertNull(slot.get())
    }

    @Test
    fun sessionClearDetachesRendererWithoutTakingTheActiveRun() {
        val activeRuns = XiaoAiRunSlot<Any>()
        val renderers = XiaoAiRendererSlot<Any>()
        val run = Any()
        val renderer = Any()
        activeRuns.replace(run)
        renderers.attach(renderer)

        assertSame(renderer, renderers.detach())
        assertNull(renderers.detach())
        assertSame(run, activeRuns.get())
    }
}
