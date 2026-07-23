package fuck.andes.agent.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScrollEvidenceContractTest {
    @Test
    fun `undefined accessibility delta is not movement or mismatch`() {
        val delta = ScrollEvidenceContract.normalizeAccessibilityDelta(-1)

        assertNull(delta)
        assertEquals(
            ScrollEvidence.UNVERIFIED,
            ScrollEvidenceContract.classify(
                direction = ScrollDirection.DOWN,
                delta = delta,
                movementSource = ScrollMovementSource.EVENT,
                atBoundary = false,
            ),
        )
    }

    @Test
    fun `classifies event tree boundary and mismatch evidence`() {
        assertEquals(
            ScrollEvidence.MOVED_BY_EVENT,
            classify(delta = 20),
        )
        assertEquals(
            ScrollEvidence.MOVED_BY_ANCHOR_MOTION,
            classify(delta = 20, source = ScrollMovementSource.ANCHOR_MOTION),
        )
        assertEquals(
            ScrollEvidence.AT_BOUNDARY,
            classify(delta = null, boundary = true),
        )
        assertEquals(
            ScrollEvidence.DIRECTION_MISMATCH,
            classify(delta = -20),
        )
    }

    private fun classify(
        delta: Int?,
        source: ScrollMovementSource = ScrollMovementSource.EVENT,
        boundary: Boolean = false,
    ): ScrollEvidence = ScrollEvidenceContract.classify(
        direction = ScrollDirection.DOWN,
        delta = delta,
        movementSource = source,
        atBoundary = boundary,
    )
}
