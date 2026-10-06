package io.github.mangi.eta.agent.display

import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.model.Settings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class VirtualScreenFallbackSettingsTest {
    @Test
    fun fallbackIsOffByDefaultAndPersistsWithoutChangingOtherPreferences() = runBlocking {
        assertFalse(Settings().virtualScreenFallbackEnabled)
        SettingsDataStore.init(RuntimeEnvironment.getApplication())
        val original = SettingsDataStore.settings()
        try {
            SettingsDataStore.updateSettings { it.copy(virtualScreenFallbackEnabled = true) }
            val updated = SettingsDataStore.settings()
            assertTrue(updated.virtualScreenFallbackEnabled)
            assertEquals(
                original,
                updated.copy(virtualScreenFallbackEnabled = original.virtualScreenFallbackEnabled)
            )
        } finally {
            SettingsDataStore.updateSettings { original }
        }
    }
}
