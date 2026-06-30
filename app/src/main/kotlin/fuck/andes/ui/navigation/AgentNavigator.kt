package fuck.andes.ui.navigation

import androidx.navigation3.runtime.NavKey

class AgentNavigator(
    val backStack: MutableList<NavKey>,
) {
    fun push(route: AppRoute) {
        backStack.add(route)
    }

    fun replace(route: AppRoute) {
        if (backStack.isNotEmpty()) {
            backStack[backStack.lastIndex] = route
        } else {
            backStack.add(route)
        }
    }

    fun pop() {
        if (backStack.size > 1) {
            backStack.removeLastOrNull()
        }
    }

    fun popToHome() {
        while (backStack.size > 1) {
            backStack.removeLastOrNull()
        }
        backStack.firstOrNull()?.let { first ->
            if (first !is AppRoute.Home) {
                backStack.clear()
                backStack.add(AppRoute.Home)
            }
        }
    }

    fun current(): NavKey? = backStack.lastOrNull()
}
