package fuck.andes.agent.device

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GestureFallbackPolicyTest {
    @Test
    fun `only a definitely undispatched gesture may be replayed`() {
        assertTrue(GestureFallbackPolicy.mayFallbackToRoot("GESTURE_NOT_DISPATCHED"))
        assertFalse(GestureFallbackPolicy.mayFallbackToRoot("GESTURE_CANCELLED"))
        assertFalse(GestureFallbackPolicy.mayFallbackToRoot("ACTION_OUTCOME_UNKNOWN"))
    }
}
