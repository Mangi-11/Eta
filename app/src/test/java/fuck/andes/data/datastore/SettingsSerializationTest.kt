package fuck.andes.data.datastore

import fuck.andes.data.model.AnthropicProviderSetting
import fuck.andes.data.model.Settings
import fuck.andes.data.provider.BuiltinProviders
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSerializationTest {
    @Test
    fun settingsRoundTripPreservesSealedProviderTypesAndSelection() {
        val settings = Settings(
            providers = BuiltinProviders.PROVIDERS,
            selectedProviderId = BuiltinProviders.ANTHROPIC_ID,
            selectedModelId = "builtin-anthropic-claude-sonnet-5",
            legacyMigrationCompleted = true
        )

        val decoded = SettingsDataStore.decode(SettingsDataStore.encode(settings))

        assertEquals(settings.selectedProviderId, decoded.selectedProviderId)
        assertEquals(settings.selectedModelId, decoded.selectedModelId)
        assertTrue(decoded.providers.first { it.id == BuiltinProviders.ANTHROPIC_ID } is AnthropicProviderSetting)
        assertEquals(settings.providers.size, decoded.providers.size)
    }
}
