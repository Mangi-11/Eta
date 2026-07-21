package fuck.andes.agent.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainThreadCallGateTest {
    @Test
    fun `cancelled pending action can never start later`() {
        val gate = MainThreadCallGate()

        assertTrue(gate.cancelIfPending())
        assertFalse(gate.tryStart())
        assertEquals(MainThreadCallGate.State.CANCELLED, gate.currentState())
    }

    @Test
    fun `running action cannot be reported as cancelled`() {
        val gate = MainThreadCallGate()

        assertTrue(gate.tryStart())
        assertFalse(gate.cancelIfPending())
        gate.finish()
        assertEquals(MainThreadCallGate.State.FINISHED, gate.currentState())
    }
}
