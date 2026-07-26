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
    /** `categoryId` is omitted for the uncategorized report bucket. */
    object CategoryTransactions : Screen("category_transactions?categoryId={categoryId}") {
        fun route(categoryId: String?): String = categoryId
            ?.let { "category_transactions?categoryId=${java.net.URLEncoder.encode(it, "UTF-8")}" }
            ?: "category_transactions"
    }
    object Categories : Screen("categories")
    object Tags : Screen("tags")
    object AutoRules : Screen("auto_rules")
    object SyncLog : Screen("sync_log/{extensionId}") {
        fun route(extensionId: String) = "sync_log/${java.net.URLEncoder.encode(extensionId, "UTF-8")}" // Encoded route argument.
    }
}
