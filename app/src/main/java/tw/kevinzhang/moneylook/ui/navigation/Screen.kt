package tw.kevinzhang.moneylook.ui.navigation

sealed class Screen(val route: String) {
    object Home : Screen("home")
    object Marketplace : Screen("marketplace")
}
