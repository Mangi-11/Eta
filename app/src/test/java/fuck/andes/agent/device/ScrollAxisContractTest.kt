package fuck.andes.agent.device

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrollAxisContractTest {
    @Test
    fun `horizontal only target rejects vertical request`() {
        assertTrue(
            ScrollAxisContract.exposesOnlyOppositeAxis(
                requestedAxis = ScrollAxis.VERTICAL,
                hasVerticalActions = false,
                hasHorizontalActions = true,
            ),
        )
    }

    @Test
    fun `vertical only target rejects horizontal request`() {
        assertTrue(
            ScrollAxisContract.exposesOnlyOppositeAxis(
                requestedAxis = ScrollAxis.HORIZONTAL,
                hasVerticalActions = true,
                hasHorizontalActions = false,
            ),
        )
    }

    @Test
    fun `same axis both axes and unknown axis do not reject prematurely`() {
        assertFalse(
            ScrollAxisContract.exposesOnlyOppositeAxis(
                requestedAxis = ScrollAxis.VERTICAL,
                hasVerticalActions = true,
                hasHorizontalActions = false,
            ),
        )
        assertFalse(
            ScrollAxisContract.exposesOnlyOppositeAxis(
                requestedAxis = ScrollAxis.VERTICAL,
                hasVerticalActions = true,
                hasHorizontalActions = true,
            ),
        )
        assertFalse(
            ScrollAxisContract.exposesOnlyOppositeAxis(
                requestedAxis = ScrollAxis.VERTICAL,
                hasVerticalActions = false,
                hasHorizontalActions = false,
            ),
        )
    }

    @Test
    fun `legacy forward backward alone is never assumed vertical`() {
        assertFalse(
            ScrollAxisContract.mayTreatLegacyActionsAsVertical(
                requestedAxis = ScrollAxis.VERTICAL,
                hasVerticalActions = false,
                hasHorizontalActions = false,
            ),
        )
        assertFalse(
            ScrollAxisContract.mayTreatLegacyActionsAsVertical(
                requestedAxis = ScrollAxis.HORIZONTAL,
                hasVerticalActions = false,
                hasHorizontalActions = true,
            ),
        )
        assertTrue(
            ScrollAxisContract.mayTreatLegacyActionsAsVertical(
                requestedAxis = ScrollAxis.VERTICAL,
                hasVerticalActions = true,
                hasHorizontalActions = false,
            ),
        )
    }
}
