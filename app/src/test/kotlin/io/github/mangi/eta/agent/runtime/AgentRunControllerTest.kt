package io.github.mangi.eta.agent.runtime

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRunControllerTest {
    @Test
    fun interjectionCancelsOnlyActiveModelRequestAndLateRegistrationObservesPendingMessage() {
        val controller = AgentRunController()
        val toolCancellations = AtomicInteger()
        val modelCancellations = AtomicInteger()
        controller.register { toolCancellations.incrementAndGet() }
        controller.registerModelRequest(true) { modelCancellations.incrementAndGet() }
        assertTrue(controller.steer("redirect"))
        assertEquals(1, modelCancellations.get())
        controller.registerModelRequest(true) { modelCancellations.incrementAndGet() }
        assertEquals(2, modelCancellations.get())
        controller.registerModelRequest(false) { toolCancellations.incrementAndGet() }
        assertEquals(0, toolCancellations.get())
        assertFalse(controller.admitTool())
        assertEquals("redirect", controller.pollSteeringMessage())
        assertTrue(controller.admitTool())
        assertFalse(controller.isCancelled)
    }

    @Test
    fun interjectionWakesRetryBackoffWithoutCancellingRun() {
        val controller = AgentRunController()
        val finished = CountDownLatch(1)
        val started = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val worker = thread(isDaemon = true) {
            started.countDown()
            try { controller.awaitRetryDelay(60_000, interruptible = true) }
            catch (error: Throwable) { failure.set(error) }
            finally { finished.countDown() }
        }
        try {
            assertTrue(started.await(1, TimeUnit.SECONDS))
            controller.steer("redirect")
            assertTrue(finished.await(1, TimeUnit.SECONDS))
            assertTrue(failure.get() is AgentModelInterruptedException)
            assertFalse(controller.isCancelled)
        } finally { controller.cancel(); worker.join(1_000) }
    }

    @Test
    fun cancellationWakesLongRetryWait() {
        val controller = AgentRunController()
        val started = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val worker = thread {
            started.countDown()
            try {
                controller.awaitRetryDelay(60_000L)
            } catch (error: Throwable) {
                failure.set(error)
            } finally {
                finished.countDown()
            }
        }
        try {
            assertTrue(started.await(1, TimeUnit.SECONDS))
            controller.cancel()
            assertTrue(finished.await(1, TimeUnit.SECONDS))
            assertTrue(failure.get() is AgentRunCancelledException)
        } finally {
            controller.cancel()
            worker.join(1_000)
        }
    }

    @Test
    fun steeringIsQueuedOneAtATimeWithoutCancellingResources() {
        val controller = AgentRunController()
        val cancellations = AtomicInteger(0)
        controller.register { cancellations.incrementAndGet() }

        assertTrue(controller.steer("  first  "))
        assertFalse(controller.steer("   "))
        assertTrue(controller.steer("second"))

        assertEquals(0, cancellations.get())
        assertEquals("first", controller.pollSteeringMessage())
        assertEquals("second", controller.pollSteeringMessage())
        assertNull(controller.pollSteeringMessage())
    }

    @Test
    fun finalPollAtomicallySealsSteering() {
        val controller = AgentRunController()

        assertNull(controller.pollSteeringOrSeal())
        assertFalse(controller.steer("too late"))
    }

    @Test
    fun cancelClearsSteeringAndCancelsEachResourceOnce() {
        val controller = AgentRunController()
        val cancellations = AtomicInteger(0)
        controller.register { cancellations.incrementAndGet() }
        controller.steer("pending")

        controller.cancel()
        controller.cancel()

        assertEquals(1, cancellations.get())
        assertFalse(controller.hasPendingSteering)
        assertFalse(controller.steer("late"))
    }

    @Test
    fun closedBindingIsNotCancelledLater() {
        val controller = AgentRunController()
        val cancellations = AtomicInteger(0)
        val binding = controller.register { cancellations.incrementAndGet() }

        binding.close()
        controller.cancel()

        assertEquals(0, cancellations.get())
    }

    @Test
    fun resourceRegisteredAfterCancellationIsCancelledExactlyOnce() {
        val controller = AgentRunController()
        val cancellations = AtomicInteger(0)
        controller.cancel()

        val binding = controller.register { cancellations.incrementAndGet() }
        controller.cancel()
        binding.close()

        assertEquals(1, cancellations.get())
    }

    @Test
    fun pauseBlocksUntilResume() {
        val controller = AgentRunController()
        val entered = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val failure = AtomicReference<Throwable>()
        controller.pause()
        val worker = thread(name = "controller-pause-test") {
            entered.countDown()
            runCatching(controller::throwIfCancelled).exceptionOrNull()?.let(failure::set)
            finished.countDown()
        }

        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            assertFalse(finished.await(100, TimeUnit.MILLISECONDS))
            controller.resume()
            assertTrue(finished.await(1, TimeUnit.SECONDS))
            assertNull(failure.get())
        } finally {
            controller.cancel()
            worker.join(1_000)
        }
        assertFalse(worker.isAlive)
    }

    @Test
    fun steeringDoesNotResumeAPausedRun() {
        val controller = AgentRunController()
        val entered = CountDownLatch(1)
        val finished = CountDownLatch(1)
        controller.pause()
        val worker = thread(name = "controller-paused-steering-test", isDaemon = true) {
            entered.countDown()
            runCatching(controller::throwIfCancelled)
            finished.countDown()
        }

        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            assertTrue(controller.steer("补充条件"))
            assertFalse(finished.await(100, TimeUnit.MILLISECONDS))
            assertEquals("补充条件", controller.pollSteeringMessage())
            controller.resume()
            assertTrue(finished.await(1, TimeUnit.SECONDS))
        } finally {
            controller.cancel()
            worker.join(1_000)
        }
    }

    @Test
    fun cancelWhilePausedWakesWorkerWithCancellation() {
        val controller = AgentRunController()
        val entered = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val failure = AtomicReference<Throwable>()
        controller.pause()
        val worker = thread(name = "controller-cancel-test") {
            entered.countDown()
            runCatching(controller::throwIfCancelled).exceptionOrNull()?.let(failure::set)
            finished.countDown()
        }

        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            controller.cancel()
            assertTrue(finished.await(1, TimeUnit.SECONDS))
            assertTrue(failure.get() is AgentRunCancelledException)
        } finally {
            controller.cancel()
            worker.join(1_000)
        }
        assertFalse(worker.isAlive)
    }
}
