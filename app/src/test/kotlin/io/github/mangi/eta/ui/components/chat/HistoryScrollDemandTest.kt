package io.github.mangi.eta.ui.components.chat

import io.github.mangi.eta.ui.model.ConversationHistoryLoadState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryScrollDemandTest {
    @Test
    fun initialLayoutAndRestoredPositionDoNotReadHistoryWithoutScrolling() {
        val demand = HistoryScrollDemand()
        assertFalse(demand.ready())
        assertFalse(demand.ready(index = 2))
    }

    @Test
    fun approachingTheTopPrefetchesOnlyAfterTheListHasBeenMeasured() {
        val demand = HistoryScrollDemand()
        demand.onScroll(20f)
        assertFalse(demand.ready(measured = false))
        assertFalse(demand.ready(index = 10))
        assertTrue(demand.ready(index = 3))
    }

    @Test
    fun completedPageAndLayoutChangesCannotChainAnotherRead() {
        val demand = HistoryScrollDemand()
        demand.onScroll(20f)
        assertTrue(demand.ready())
        demand.consume()
        assertFalse(demand.ready(state = ConversationHistoryLoadState.Loading))
        assertFalse(demand.ready())
        demand.onScroll(10f)
        assertTrue(demand.ready())
    }

    @Test
    fun failureWaitsForAnExplicitRetryAndBusyReadsAreNotDuplicated() {
        val demand = HistoryScrollDemand()
        demand.onScroll(20f)
        assertFalse(demand.ready(state = ConversationHistoryLoadState.Loading))
        assertFalse(demand.ready(state = ConversationHistoryLoadState.Failed))
        demand.consume()
        assertFalse(demand.ready())
    }

    @Test
    fun reversingDirectionWithdrawsUnusedPrefetchDemand() {
        val demand = HistoryScrollDemand()
        demand.onScroll(20f)
        demand.onScroll(-10f)
        assertFalse(demand.ready())
    }

    @Test
    fun endOfHistoryAndMessageMutationsPreventPrefetch() {
        val demand = HistoryScrollDemand()
        demand.onScroll(20f)
        assertFalse(demand.ready(hasEarlier = false))
        assertFalse(demand.ready(enabled = false))
    }

    private fun HistoryScrollDemand.ready(
        measured: Boolean = true,
        index: Int = 0,
        hasEarlier: Boolean = true,
        enabled: Boolean = true,
        state: ConversationHistoryLoadState = ConversationHistoryLoadState.Idle,
    ): Boolean = canLoad(measured, index, hasEarlier, enabled, state)
}
