package io.github.mangi.eta.agent.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class WindowContentGenerationsTest {
    @Test
    fun changingMainWindowKeepsSecondaryWindowGeneration() {
        val generations = WindowContentGenerations()
        val secondary = generations.current(11)
        generations.advance(1)
        generations.remove(1)
        assertEquals(secondary, generations.current(11))
    }

    @Test
    fun removedAndReusedWindowCannotMatchOldObservation() {
        val generations = WindowContentGenerations()
        val previous = generations.current(11)
        generations.remove(11)
        assertNotEquals(previous, generations.current(11))
    }

    @Test
    fun capacityEvictionAndReconnectInvalidateOldObservations() {
        val generations = WindowContentGenerations(capacity = 2)
        val previous = generations.current(11)
        generations.advance(12)
        generations.advance(13)
        val recreated = generations.current(11)
        assertNotEquals(previous, recreated)
        generations.clear()
        assertNotEquals(recreated, generations.current(11))
    }
}
