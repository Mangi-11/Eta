package io.github.mangi.eta.agent.display

import android.view.KeyEvent
import android.view.MotionEvent
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class VirtualScreenInputInjectorTest {
    @Test
    fun completedInputDoesNotClaimBusinessSuccess() {
        val actions = CopyOnWriteArrayList<Int>()
        val injector = VirtualScreenInputInjector(96, dispatch = { event, wait ->
            assertTrue(wait)
            actions += (event as MotionEvent).action
            true
        })
        val result = injector.execute("tap", "90", "88")
        assertTrue(result.getBoolean("ok"))
        assertTrue(result.getBoolean("input_finished"))
        assertEquals("unconfirmed", result.getString("effect"))
        assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP), actions.toList())
    }

    @Test
    fun unconfirmedDispatchIsReturnedWithoutReplayingTap() {
        val actions = CopyOnWriteArrayList<Int>()
        val injector = VirtualScreenInputInjector(96, dispatch = { event, _ ->
            actions += (event as MotionEvent).action
            false
        })
        val result = injector.execute("tap", "90", "88")
        assertFalse(result.getBoolean("ok"))
        assertEquals("INPUT_DISPATCH_UNCONFIRMED", result.getString("code"))
        assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP), actions.toList())
    }

    @Test
    fun timedOutTouchIsCancelledWithoutWaitingForApplication() {
        val cancelled = CountDownLatch(1)
        val injector = VirtualScreenInputInjector(96, inputTimeoutMs = 1_000, dispatch = { event, wait ->
            if ((event as MotionEvent).action == MotionEvent.ACTION_CANCEL) {
                assertFalse(wait)
                cancelled.countDown()
                true
            } else {
                CountDownLatch(1).await()
                true
            }
        })
        val result = injector.execute("tap", "90", "88")
        assertFalse(result.getBoolean("ok"))
        assertEquals("UI_INPUT_TIMEOUT", result.getString("code"))
        assertTrue(cancelled.await(2, TimeUnit.SECONDS))
    }

    @Test
    fun interruptedKeyStillReleasesThePressedKey() {
        val actions = CopyOnWriteArrayList<Pair<Int, Boolean>>()
        val injector = VirtualScreenInputInjector(96, dispatch = { event, wait ->
            actions += (event as KeyEvent).action to wait
            if (event.action == KeyEvent.ACTION_DOWN) throw InterruptedException("interrupted input")
            true
        })
        assertThrows(ExecutionException::class.java) { injector.execute("keyevent", "4") }
        assertEquals(listOf(KeyEvent.ACTION_DOWN to true, KeyEvent.ACTION_UP to false), actions.toList())
    }

    @Test
    fun primaryDisplayCannotBeUsedByVirtualInjector() {
        assertThrows(IllegalArgumentException::class.java) { VirtualScreenInputInjector(0) }
        assertThrows(IllegalArgumentException::class.java) { VirtualScreenInputInjector(-1) }
    }
}
