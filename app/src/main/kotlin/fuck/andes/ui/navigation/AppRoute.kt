package fuck.andes.ui.navigation

import androidx.navigation3.runtime.NavKey

sealed interface AppRoute : NavKey {
    data object Home : AppRoute
    data object Chat : AppRoute
    data object Runs : AppRoute
    data class RunDetail(val runId: String) : AppRoute
    data object Tools : AppRoute
    data object Permissions : AppRoute
    data object SystemEnhance : AppRoute
    data object Settings : AppRoute
}
