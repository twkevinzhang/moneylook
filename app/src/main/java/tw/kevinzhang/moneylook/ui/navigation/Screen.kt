package tw.kevinzhang.moneylook.ui.navigation

sealed class Screen(val route: String) {
    object Home : Screen("home")
    object GlobalTransactions : Screen("global_transactions")
    object Marketplace : Screen("marketplace")
    object Settings : Screen("settings")
    object ManageRepos : Screen("manage_repos")
    object ExtensionLedger : Screen("ledger/{accountId}") {
        fun route(accountId: String) = "ledger/${java.net.URLEncoder.encode(accountId, "UTF-8")}"
    }
    object TransactionDetail : Screen("transaction/{transferId}") {
        fun route(transferId: String) = "transaction/${java.net.URLEncoder.encode(transferId, "UTF-8")}"
    }
    object Categories : Screen("categories")
    object Tags : Screen("tags")
    object AutoRules : Screen("auto_rules")
}
