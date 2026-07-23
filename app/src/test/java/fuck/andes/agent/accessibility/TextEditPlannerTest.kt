package fuck.andes.agent.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextEditPlannerTest {
    @Test
    fun `refuses to reconstruct password or unreadable populated input`() {
        assertFalse(TextEditPlanner.canSafelyReconstruct(true, true, 3, 1, 1))
        assertFalse(TextEditPlanner.canSafelyReconstruct(false, false, 3, 3, 3))
        assertTrue(TextEditPlanner.canSafelyReconstruct(false, true, 3, 3, 3))
        assertTrue(TextEditPlanner.canSafelyReconstruct(false, true, 0, -1, -1))
    }

    @Test
    fun `inserts at cursor instead of always appending`() {
        assertEquals(
            TextEditPlanner.Plan("AXBC", 2),
            TextEditPlanner.insertAtSelection("ABC", "X", 1, 1),
        )
    }

    @Test
    fun `replaces selected range regardless of selection direction`() {
        assertEquals(
            TextEditPlanner.Plan("AXC", 2),
            TextEditPlanner.insertAtSelection("ABC", "X", 2, 1),
        )
    }

    @Test
    fun `uses UTF 16 offsets exposed by accessibility nodes`() {
        assertEquals(
            TextEditPlanner.Plan("😀X好", 3),
            TextEditPlanner.insertAtSelection("😀好", "X", 2, 2),
        )
    }

    @Test
    fun `invalid selection on existing text is rejected instead of appending`() {
        assertNull(TextEditPlanner.insertAtSelection("ABC", "X", -1, -1))
    }

    @Test
    fun `partially invalid selection is rejected`() {
        assertNull(TextEditPlanner.insertAtSelection("ABC", "X", -1, 0))
    }

    @Test
    fun `empty text has one safe insertion point before cursor exists`() {
        assertEquals(
            TextEditPlanner.Plan("X", 1),
            TextEditPlanner.insertAtSelection("", "X", -1, -1),
        )
    }
}
