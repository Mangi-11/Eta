package io.github.mangi.eta.agent.phone

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowProcess

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NativeSystemOperationsTest {
    @Test
    fun mobileDataRequiresRootBeforeReadingOrMutatingTelephony() {
        ShadowProcess.setUid(10_001)
        var mutated = false
        val operations = NativeSystemOperations(RuntimeEnvironment.getApplication(), 0) { mutated = true }
        for (tool in listOf("get_mobile_data", "set_mobile_data")) {
            val failure = org.junit.Assert.assertThrows(PhoneOperationFailure::class.java) {
                operations.execute(tool, JSONObject().put("enabled", false))
            }
            assertEquals("ROOT_REQUIRED", failure.code)
        }
        assertFalse(mutated)
    }
}
