package fuck.andes.ui.pages.providers

import fuck.andes.R
import fuck.andes.data.model.CustomProviderSetting
import fuck.andes.data.model.ProviderSourceTypes
import fuck.andes.data.provider.BuiltinProviders
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProviderComponentsTest {
    @Test
    fun knownSourcesMapToDistinctBrandLogos() {
        val expected = mapOf(
            ProviderSourceTypes.OPENAI to R.drawable.provider_logo_openai,
            ProviderSourceTypes.ANTHROPIC to R.drawable.provider_logo_anthropic,
            ProviderSourceTypes.BAILIAN to R.drawable.provider_logo_bailian,
            ProviderSourceTypes.DEEPSEEK to R.drawable.provider_logo_deepseek,
            ProviderSourceTypes.MOONSHOT to R.drawable.provider_logo_kimi,
            ProviderSourceTypes.MIMO to R.drawable.provider_logo_mimo,
            ProviderSourceTypes.MINIMAX to R.drawable.provider_logo_minimax,
            ProviderSourceTypes.STEPFUN to R.drawable.provider_logo_stepfun,
            ProviderSourceTypes.SILICONFLOW to R.drawable.provider_logo_siliconflow,
            ProviderSourceTypes.OPENROUTER to R.drawable.provider_logo_openrouter,
        )

        expected.forEach { (sourceType, logo) ->
            assertEquals(logo, providerBrandLogoRes(sourceType))
        }
        assertEquals(expected.size, expected.values.toSet().size)
    }

    @Test
    fun everyBuiltInProviderHasABrandLogo() {
        val logos = BuiltinProviders.PROVIDERS.map(::providerBrandLogoRes)

        assertEquals(BuiltinProviders.PROVIDERS.size, logos.filterNotNull().size)
        assertEquals(BuiltinProviders.PROVIDERS.size, logos.filterNotNull().toSet().size)
    }

    @Test
    fun customProviderUsesRecognizedBaseUrlAndUnknownSourceFallsBack() {
        val recognized = CustomProviderSetting(
            id = "custom-deepseek",
            name = "DeepSeek 副本",
            baseUrl = "https://api.deepseek.com/v1",
        )
        val unknown = CustomProviderSetting(
            id = "custom-unknown",
            name = "自定义",
            baseUrl = "https://api.example.com/v1",
        )

        assertEquals(R.drawable.provider_logo_deepseek, providerBrandLogoRes(recognized))
        assertNull(providerBrandLogoRes(unknown))
    }
}
