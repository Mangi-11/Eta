package fuck.andes.agent.runtime

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRuntimeSessionTest {
    @Test
    fun concurrentCompletionAndCancellationPublishExactlyOneTerminalResult() {
        repeat(100) { iteration ->
            val results = Collections.synchronizedList(mutableListOf<AgentRuntimeWire.RunResult>())
            val session = AgentRuntimeSession(
                runId = "run-$iteration",
                resultSink = results::add,
            )
            val start = CountDownLatch(1)
            val finished = CountDownLatch(2)
            val completed = AtomicBoolean(false)
            val cancelled = AtomicBoolean(false)
            val completeThread = Thread {
                start.await()
                completed.set(
                    session.complete(
                        AgentRuntimeWire.RunResult(
                            runId = "run-$iteration",
                            ok = true,
                            content = "done",
                        )
                    )
                )
                finished.countDown()
            }.apply { isDaemon = true }
            val cancelThread = Thread {
                start.await()
                cancelled.set(session.cancel("stopped"))
                finished.countDown()
            }.apply { isDaemon = true }

            completeThread.start()
            cancelThread.start()
            start.countDown()
            assertTrue(finished.await(2, TimeUnit.SECONDS))
            completeThread.join(2_000)
            cancelThread.join(2_000)

            assertTrue(completed.get() xor cancelled.get())
            assertEquals(1, results.size)
            assertTrue(session.isTerminal)
            if (!results.single().ok) assertTrue(session.controller.isCancelled)
        }
    }

    @Test
    fun terminalResultIsDeliveredExactlyOnceAndStopsLaterEvents() {
        val events = mutableListOf<AgentEvent>()
        val results = mutableListOf<AgentRuntimeWire.RunResult>()
        val session = AgentRuntimeSession(
            runId = "run-1",
            eventSink = events::add,
            resultSink = results::add,
        )

        assertTrue(session.emit(AgentEvent.RoundStarted(round = 1, messageCount = 1)))
        assertTrue(
            session.complete(
                AgentRuntimeWire.RunResult(
                    runId = "run-1",
                    ok = true,
                    content = "done",
                )
            )
        )
        assertFalse(session.cancel("late cancel"))
        assertFalse(session.steer("late steer"))
        assertFalse(session.emit(AgentEvent.RoundStarted(round = 2, messageCount = 2)))

        assertEquals(1, events.size)
        assertEquals(1, results.size)
        assertEquals("done", results.single().content)
    }

    @Test
    fun cancellationCancelsControllerBeforePublishingTerminalResult() {
        val observedCancelled = mutableListOf<Boolean>()
        val results = mutableListOf<AgentRuntimeWire.RunResult>()
        lateinit var session: AgentRuntimeSession
        session = AgentRuntimeSession(
            runId = "run-1",
            resultSink = { result ->
                observedCancelled += session.controller.isCancelled
                results += result
            },
        )

        assertTrue(session.cancel("stopped"))
        assertTrue(session.controller.isCancelled)
        assertEquals("stopped", results.single().error)
        assertTrue(observedCancelled.single())
    }

    @Test
    fun completionClaimOwnsPersistenceAndRejectsConcurrentCancellation() {
        val persistenceStarted = CountDownLatch(1)
        val allowPersistence = CountDownLatch(1)
        val results = Collections.synchronizedList(mutableListOf<AgentRuntimeWire.RunResult>())
        val session = AgentRuntimeSession(runId = "run-1", resultSink = results::add)
        val completionReturned = AtomicBoolean(false)
        val worker = Thread {
            completionReturned.set(
                session.complete(
                    result = AgentRuntimeWire.RunResult(
                        runId = "run-1",
                        ok = true,
                        content = "done",
                    ),
                    beforePublish = {
                        persistenceStarted.countDown()
                        allowPersistence.await(2, TimeUnit.SECONDS)
                    },
                )
            )
        }.apply { isDaemon = true }

        worker.start()
        try {
            assertTrue(persistenceStarted.await(1, TimeUnit.SECONDS))
            assertFalse(session.cancel("too late"))
        } finally {
            allowPersistence.countDown()
            worker.join(2_000)
        }

        assertTrue(completionReturned.get())
        assertEquals(1, results.size)
        assertTrue(results.single().ok)
    }

    @Test
    fun commitCallbackFailureCannotLeaveSessionWithoutTerminalResult() {
        val results = mutableListOf<AgentRuntimeWire.RunResult>()
        val session = AgentRuntimeSession(runId = "run-1", resultSink = results::add)

        assertThrows(IllegalStateException::class.java) {
            session.complete(
                result = AgentRuntimeWire.RunResult(
                    runId = "run-1",
                    ok = false,
                    content = "",
                    error = "persistence failed",
                ),
                beforePublish = { error("disk full") },
            )
        }

        assertTrue(session.isTerminal)
        assertEquals(1, results.size)
        assertFalse(session.cancel("late"))
    }
}
