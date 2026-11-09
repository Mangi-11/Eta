package io.github.mangi.eta.agent.accessibility

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityQueryExecutorTest {
    @Test
    fun timedOutBinderReadKeepsWorkerReservedUntilItActuallyReturns() {
        val release = CountDownLatch(1)
        val started = CountDownLatch(1)
        val calls = AtomicInteger()
        AccessibilityQueryExecutor().use { executor ->
            try {
                assertEquals(AccessibilityQueryExecutor.Result.TimedOut, executor.query(50L) {
                    calls.incrementAndGet()
                    started.countDown()
                    release.await()
                })
                assertTrue(started.await(1, TimeUnit.SECONDS))
                repeat(20) {
                    assertEquals(AccessibilityQueryExecutor.Result.Busy, executor.query(50L) {
                        calls.incrementAndGet()
                    })
                }
                assertEquals(1, calls.get())
            } finally {
                release.countDown()
            }
        }
    }

    @Test
    fun queryFailureDoesNotPoisonSubsequentReads() {
        AccessibilityQueryExecutor().use { executor ->
            val result = executor.query(1_000L) { error("test") }
            assertTrue(result is AccessibilityQueryExecutor.Result.Failed)
            assertEquals(AccessibilityQueryExecutor.Result.Completed(42), executor.query(1_000L) { 42 })
        }
    }

    @Test
    fun closedExecutorNeverRunsNewWork() {
        val executor = AccessibilityQueryExecutor()
        executor.close()
        assertEquals(AccessibilityQueryExecutor.Result.Closed, executor.query(50L) {
            error("closed executor ran a query")
        })
    }

    @Test
    fun timeoutStopsTraversalAfterCurrentRemoteReadReturns() {
        val release = CountDownLatch(1)
        val exited = CountDownLatch(1)
        val laterReads = AtomicInteger()
        AccessibilityQueryExecutor().use { executor ->
            try {
                assertEquals(AccessibilityQueryExecutor.Result.TimedOut, executor.query(50L) {
                    try {
                        release.await()
                        executor.ensureActive()
                        laterReads.incrementAndGet()
                    } finally {
                        exited.countDown()
                    }
                })
                release.countDown()
                assertTrue(exited.await(1, TimeUnit.SECONDS))
                assertEquals(0, laterReads.get())
            } finally {
                release.countDown()
            }
        }
    }
}
