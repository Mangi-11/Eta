package fuck.andes.ui.navigation

import androidx.navigation3.runtime.NavKey

sealed interface AppRoute : NavKey {
    data object Home : AppRoute
    data object Chat : AppRoute
    data object Browser : AppRoute
    data object Tools : AppRoute
    data object Skills : AppRoute
    data object Permissions : AppRoute
    data object SystemEnhance : AppRoute
    data object Settings : AppRoute
    data object ModelProviders : AppRoute
    data class ModelProviderDetail(val providerId: String) : AppRoute
    data class ModelProviderNew(val type: NewProviderType) : AppRoute
}

enum class NewProviderType { OpenAiCompatible, Anthropic }
