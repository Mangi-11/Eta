package fuck.andes.agent.tool

import org.junit.Assert.assertEquals
import org.junit.Test

class ObservationReferencePolicyTest {
    @Test
    fun `requires an observation and its id`() {
        assertEquals(
            ObservationReferencePolicy.Status.NO_OBSERVATION,
            ObservationReferencePolicy.validate(null, "o1"),
        )
        assertEquals(
            ObservationReferencePolicy.Status.ID_REQUIRED,
            ObservationReferencePolicy.validate("o1", ""),
        )
    }

    @Test
    fun `publishing a newer observation rejects the old id`() {
        assertEquals(
            ObservationReferencePolicy.Status.STALE,
            ObservationReferencePolicy.validate("o2", "o1"),
        )
        assertEquals(
            ObservationReferencePolicy.Status.MATCH,
            ObservationReferencePolicy.validate("o2", "o2"),
        )
    }
}
