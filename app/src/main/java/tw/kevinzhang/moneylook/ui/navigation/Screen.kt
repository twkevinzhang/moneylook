package tw.kevinzhang.moneylook.ui.navigation

sealed class Screen(val route: String) {
    object Home : Screen("home")
    object Marketplace : Screen("marketplace")
    object ManageRepos : Screen("manage_repos")
    object ExtensionLedger : Screen("ledger/{accountId}") {
        fun route(accountId: String) = "ledger/${java.net.URLEncoder.encode(accountId, "UTF-8")}"
    }
}
