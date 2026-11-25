package io.github.mangi.eta.agent.runtime

import android.app.Application
import io.github.mangi.eta.agent.model.provider.AgentModelClient
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import io.github.mangi.eta.agent.runtime.archive.AgentExternalArchivePayload
import io.github.mangi.eta.agent.runtime.ipc.AgentRuntimeWire
import io.github.mangi.eta.agent.runtime.ipc.AgentUiHandoffPayload

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class RuntimeRunRegistryTest {
    @Test
    fun pendingRequestsCountTowardCapacityAndDuplicatesDoNotCreateAnotherRun() {
        val registry = RuntimeRunRegistry(capacity = 2)
        val a = registry.accept("a", "conversation-a")
        registry.accept("b", "conversation-b")
        val duplicate = registry.admit(request("a", "conversation-a")) { error("不能重复创建") }
        assertSame(a, (duplicate as RuntimeRunRegistry.Admission.Duplicate).run)
        val rejected = registry.admit(request("c", "conversation-c")) { error("不能超额创建") }
        assertEquals("RUN_CAPACITY_REACHED", (rejected as RuntimeRunRegistry.Admission.Rejected).code)
        assertEquals(setOf("a", "b"), registry.snapshots().map { it.runId }.toSet())
        assertTrue(registry.snapshots().all { it.phase == AgentRuntimeWire.RunPhase.Preparing })
    }

    @Test
    fun simultaneousRequestsForOneConversationAdmitExactlyOneWriter() {
        val registry = RuntimeRunRegistry()
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val accepted = AtomicInteger()
        val workers = (1..2).map { index -> thread {
            ready.countDown()
            assertTrue(start.await(5, TimeUnit.SECONDS))
            val request = request("run-$index", "same-conversation")
            val admission = registry.admit(request) {
                RuntimeRunRegistry.Run(request, AgentRuntimeSession(request.runId))
            }
            if (admission is RuntimeRunRegistry.Admission.Accepted) accepted.incrementAndGet()
        } }
        assertTrue(ready.await(5, TimeUnit.SECONDS))
        start.countDown()
        workers.forEach { it.join(5_000) }
        assertTrue(workers.none(Thread::isAlive))
        assertEquals(1, accepted.get())
    }

    @Test
    fun committingHoldsConversationUntilBeforeTerminalDelivery() {
        val registry = RuntimeRunRegistry()
        val request = request("first", "conversation")
        var followUpAccepted = false
        lateinit var session: AgentRuntimeSession
        session = AgentRuntimeSession(
            request.runId,
            beforeTerminalPublish = { registry.finish(request.runId, session) },
            resultSink = {
                followUpAccepted = registry.admit(request("next", "conversation")) {
                    val next = request("next", "conversation")
                    RuntimeRunRegistry.Run(next, AgentRuntimeSession(next.runId))
                } is RuntimeRunRegistry.Admission.Accepted
            },
        )
        registry.admit(request) { RuntimeRunRegistry.Run(request, session) }
        session.complete(AgentRuntimeWire.RunResult("first", true, "完成")) {
            assertEquals(AgentRuntimeWire.RunPhase.Committing, registry.snapshots().single().phase)
            val busy = registry.admit(request("too-early", "conversation")) { error("提交期间不能创建") }
            assertEquals("CONVERSATION_BUSY", (busy as RuntimeRunRegistry.Admission.Rejected).code)
        }
        assertTrue(followUpAccepted)
        assertEquals("next", registry.snapshots().single().runId)
    }

    @Test
    fun supplementsAndCancellationBelongToTheirOwnRun() {
        val registry = RuntimeRunRegistry()
        val a = registry.accept("a", "conversation-a")
        val b = registry.accept("b", "conversation-b")
        assertNotNull(a.supplement("补充 A"))
        assertNotNull(b.supplement("补充 B"))
        assertEquals("补充 A", AgentUiHandoffPayload.from(a.snapshotRequest().handoff!!.payload).supplements.single().text)
        assertEquals("补充 B", AgentUiHandoffPayload.from(b.snapshotRequest().handoff!!.payload).supplements.single().text)
        a.session.controller.cancel()
        assertTrue(a.session.controller.isCancelled)
        assertFalse(b.session.controller.isCancelled)
        assertEquals("补充 B", b.session.controller.pollSteeringMessage())
    }

    @Test
    fun pendingIngestionIsReleasedOnceAndPreparingSnapshotKeepsOwnership() {
        val registry = RuntimeRunRegistry()
        val request = request("run", "conversation").copy(operation = AgentRuntimeWire.OP_COMPACT)
        var releases = 0
        val run = (registry.admit(request) {
            RuntimeRunRegistry.Run(request, AgentRuntimeSession(request.runId), onRequestIngested = { releases++ })
        } as RuntimeRunRegistry.Admission.Accepted).run
        val snapshot = registry.snapshots().single()
        assertEquals("conversation", snapshot.conversationId)
        assertEquals(AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE, snapshot.handoffSource)
        assertEquals(AgentRuntimeWire.OP_COMPACT, snapshot.operation)
        run.releaseIncoming()
        run.prepared(request)
        run.releaseIncoming()
        assertEquals(1, releases)
        run.guiStatus = GuiControlStatus.Waiting
        assertEquals(AgentRuntimeWire.RunPhase.WaitingForGui, registry.snapshots().single().phase)
    }

    @Test
    fun externalReplacementWaitsUntilCancelledPredecessorHasDrained() {
        val registry = RuntimeRunRegistry()
        fun external(id: String) = request(id, "unused").copy(handoff = AgentRuntimeWire.EntryHandoff(
            id, "eta_voice", AgentExternalArchivePayload(userText = "测试", conversationKey = "voice-conversation", title = "测试会话").toJson(),
        ))
        val firstRequest = external("first")
        val first = (registry.admit(firstRequest) {
            RuntimeRunRegistry.Run(firstRequest, AgentRuntimeSession(firstRequest.runId))
        } as RuntimeRunRegistry.Admission.Accepted).run
        val secondRequest = external("second")
        val admission = registry.admit(secondRequest) {
            RuntimeRunRegistry.Run(secondRequest, AgentRuntimeSession(secondRequest.runId))
        } as RuntimeRunRegistry.Admission.Accepted
        assertEquals(listOf(first), admission.replacedRuns)
        first.session.controller.cancel()
        val entered = CountDownLatch(1)
        val ready = CountDownLatch(1)
        val worker = thread {
            entered.countDown()
            admission.run.awaitPredecessors()
            ready.countDown()
        }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        assertFalse(ready.await(50, TimeUnit.MILLISECONDS))
        registry.finish(first.runId, first.session)
        assertTrue(ready.await(5, TimeUnit.SECONDS))
        worker.join(5_000)
        assertEquals("second", registry.snapshots().single().runId)
    }

    @Test
    fun replacingAPendingReplacementStillWaitsForTheOriginalExecution() {
        val registry = RuntimeRunRegistry()
        fun external(id: String): RuntimeRunRegistry.Admission.Accepted {
            val request = request(id, "unused").copy(handoff = AgentRuntimeWire.EntryHandoff(
                id, "eta_voice", AgentExternalArchivePayload("测试", "same-voice-conversation", "测试会话").toJson(),
            ))
            return registry.admit(request) { RuntimeRunRegistry.Run(request, AgentRuntimeSession(id)) }
                as RuntimeRunRegistry.Admission.Accepted
        }
        val a = external("a").run
        val b = external("b").run
        a.session.controller.cancel()
        val bWaiting = CountDownLatch(1)
        val bCancelled = CountDownLatch(1)
        val bExecuted = AtomicBoolean(false)
        val bWorker = thread {
            bWaiting.countDown()
            try {
                b.awaitPredecessors()
                bExecuted.set(true)
            } catch (_: AgentRunCancelledException) {
                bCancelled.countDown()
            }
        }
        assertTrue(bWaiting.await(5, TimeUnit.SECONDS))
        val c = external("c")
        assertEquals(listOf(a, b), c.replacedRuns)
        b.session.controller.cancel()
        registry.finish(b.runId, b.session)
        assertTrue(bCancelled.await(5, TimeUnit.SECONDS))
        val cReady = CountDownLatch(1)
        val cWorker = thread { c.run.awaitPredecessors(); cReady.countDown() }
        assertFalse(cReady.await(50, TimeUnit.MILLISECONDS))
        registry.finish(a.runId, a.session)
        assertTrue(cReady.await(5, TimeUnit.SECONDS))
        bWorker.join(5_000)
        cWorker.join(5_000)
        assertFalse(bExecuted.get())
        assertEquals("c", registry.snapshots().single().runId)
    }

    private fun RuntimeRunRegistry.accept(runId: String, conversationId: String): RuntimeRunRegistry.Run {
        val request = request(runId, conversationId)
        return (admit(request) { RuntimeRunRegistry.Run(request, AgentRuntimeSession(runId)) }
            as RuntimeRunRegistry.Admission.Accepted).run
    }

    private fun request(runId: String, conversationId: String) = AgentRuntimeWire.RunRequest(
        runId = runId,
        prompt = "测试请求",
        config = AgentModelClient.ModelConfig(baseUrl = "https://example.invalid", apiKey = "test-key", model = "test-model", systemPrompt = ""),
        images = emptyList(),
        handoff = AgentRuntimeWire.EntryHandoff(runId, AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE, conversationId),
    )
}
