package tw.kevinzhang.moneylook.ui.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.navArgument
import tw.kevinzhang.moneylook.ui.analysis.GlobalLedgerAnalysisContent
import tw.kevinzhang.moneylook.ui.home.ExtensionLedgerScreen
import tw.kevinzhang.moneylook.ui.home.HomeScreen
import tw.kevinzhang.moneylook.ui.home.SyncLogScreen
import tw.kevinzhang.moneylook.ui.marketplace.ManageReposScreen
import tw.kevinzhang.moneylook.ui.marketplace.MarketplaceScreen
import tw.kevinzhang.moneylook.ui.settings.SettingsScreen
import tw.kevinzhang.moneylook.ui.settings.DataTransferRoute
import tw.kevinzhang.moneylook.ui.transactions.AutoRuleScreen
import tw.kevinzhang.moneylook.ui.transactions.CategoryManagementScreen
import tw.kevinzhang.moneylook.ui.transactions.CategoryTransactionsScreen
import tw.kevinzhang.moneylook.ui.transactions.ExcludedTransactionsScreen
import tw.kevinzhang.moneylook.ui.transactions.GlobalLedgerScreen
import tw.kevinzhang.moneylook.ui.transactions.GlobalLedgerViewModel
import tw.kevinzhang.moneylook.ui.transactions.TagManagementScreen
import tw.kevinzhang.moneylook.ui.transactions.TransactionDetailScreen

private val bottomBarRoutes = setOf(
    Screen.Home.route,
    Screen.GlobalLedger.route,
    Screen.Settings.route,
)

@Composable
fun AppNavHost(navController: NavHostController) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val bottomBar: @Composable () -> Unit = {
        if (currentRoute in bottomBarRoutes) {
            NavigationBar {
                NavigationBarItem(
                    selected = currentRoute == Screen.Home.route,
                    onClick = {
                        navController.navigate(Screen.Home.route) {
                            launchSingleTop = true
                            restoreState = true
                            popUpTo(Screen.Home.route) { saveState = true }
                        }
                    },
                    icon = { Icon(Icons.Default.Home, contentDescription = "首頁") },
                    label = { Text("首頁") },
                )
                NavigationBarItem(
                    selected = currentRoute == Screen.GlobalLedger.route,
                    onClick = {
                        navController.navigate(Screen.GlobalLedger.route) {
                            launchSingleTop = true
                            restoreState = true
                            popUpTo(Screen.Home.route) { saveState = true }
                        }
                    },
                    icon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = "帳本") },
                    label = { Text("帳本") },
                )
                NavigationBarItem(
                    selected = currentRoute == Screen.Settings.route,
                    onClick = {
                        navController.navigate(Screen.Settings.route) {
                            launchSingleTop = true
                            restoreState = true
                            popUpTo(Screen.Home.route) { saveState = true }
                        }
                    },
                    icon = { Icon(Icons.Default.Settings, contentDescription = "設定") },
                    label = { Text("設定") },
                )
            }
        }
    }

    NavHost(
        navController = navController,
        startDestination = Screen.Home.route,
        enterTransition = { EnterTransition.None },
        exitTransition = { ExitTransition.None },
        popEnterTransition = { EnterTransition.None },
        popExitTransition = { ExitTransition.None },
    ) {
        composable(Screen.Home.route) {
            HomeScreen(
                bottomBar = bottomBar,
                onNavigateToMarketplace = {
                    navController.navigate(Screen.Marketplace.route) {
                        launchSingleTop = true
                    }
                },
                onNavigateToLedger = { accountId ->
                    navController.navigate(Screen.ExtensionLedger.route(accountId))
                },
                onNavigateToSyncLog = { extensionId -> navController.navigate(Screen.SyncLog.route(extensionId)) },
            )
        }
        composable(Screen.Marketplace.route) {
            MarketplaceScreen(
                onNavigateUp = { navController.popBackStack() },
                onNavigateToManageRepos = { navController.navigate(Screen.ManageRepos.route) },
            )
        }
        composable(Screen.GlobalLedger.route) {
            GlobalLedgerScreen(
                bottomBar = bottomBar,
                onNavigateToTransaction = { transferId ->
                    navController.navigate(Screen.TransactionDetail.route(transferId))
                },
                onNavigateToCategoryTransactions = { categoryId ->
                    navController.navigate(Screen.CategoryTransactions.route(categoryId))
                },
                onNavigateToExcludedTransactions = {
                    navController.navigate(Screen.ExcludedTransactions.route)
                },
                analysisContent = { state -> GlobalLedgerAnalysisContent(state) },
            )
        }
        composable(Screen.ExcludedTransactions.route) { backStackEntry ->
            val parentEntry = remember(backStackEntry) {
                navController.getBackStackEntry(Screen.GlobalLedger.route)
            }
            val viewModel: GlobalLedgerViewModel = hiltViewModel(parentEntry)
            ExcludedTransactionsScreen(
                viewModel = viewModel,
                onNavigateUp = { navController.popBackStack() },
                onNavigateToTransaction = { transferId ->
                    navController.navigate(Screen.TransactionDetail.route(transferId))
                },
            )
        }
        composable(
            route = Screen.CategoryTransactions.route,
            arguments = listOf(
                navArgument("categoryId") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { backStackEntry ->
            val parentEntry = remember(backStackEntry) {
                navController.getBackStackEntry(Screen.GlobalLedger.route)
            }
            val viewModel: GlobalLedgerViewModel = hiltViewModel(parentEntry)
            val categoryId = backStackEntry.arguments?.getString("categoryId")
                ?.let { java.net.URLDecoder.decode(it, "UTF-8") }
            CategoryTransactionsScreen(
                categoryId = categoryId,
                viewModel = viewModel,
                onNavigateUp = { navController.popBackStack() },
                onNavigateToTransaction = { transferId ->
                    navController.navigate(Screen.TransactionDetail.route(transferId))
                },
            )
        }
        composable(Screen.Settings.route) {
            SettingsScreen(
                bottomBar = bottomBar,
                onNavigateToCategories = { navController.navigate(Screen.Categories.route) },
                onNavigateToTags = { navController.navigate(Screen.Tags.route) },
                onNavigateToRules = { navController.navigate(Screen.AutoRules.route) },
                onNavigateToDataTransfer = { navController.navigate(Screen.DataTransfer.route) },
            )
        }
        composable(Screen.DataTransfer.route) {
            DataTransferRoute(onNavigateUp = { navController.popBackStack() })
        }
        composable(Screen.ManageRepos.route) {
            ManageReposScreen(onNavigateUp = { navController.popBackStack() })
        }
        composable(
            route = Screen.ExtensionLedger.route,
            arguments = listOf(navArgument("accountId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val accountId = java.net.URLDecoder.decode(
                backStackEntry.arguments?.getString("accountId") ?: "",
                "UTF-8",
            )
            ExtensionLedgerScreen(
                accountId = accountId,
                onNavigateUp = { navController.popBackStack() },
                onNavigateToTransaction = { transferId -> navController.navigate(Screen.TransactionDetail.route(transferId)) },
            )
        }
        composable(
            route = Screen.TransactionDetail.route,
            arguments = listOf(navArgument("transferId") { type = NavType.StringType }),
        ) {
            TransactionDetailScreen(onNavigateUp = { navController.popBackStack() })
        }
        composable(Screen.Categories.route) {
            CategoryManagementScreen(onNavigateUp = { navController.popBackStack() })
        }
        composable(Screen.Tags.route) {
            TagManagementScreen(onNavigateUp = { navController.popBackStack() })
        }
        composable(Screen.AutoRules.route) {
            AutoRuleScreen(onNavigateUp = { navController.popBackStack() })
        }
        composable(
            route = Screen.SyncLog.route,
            arguments = listOf(navArgument("extensionId") { type = NavType.StringType }),
        ) { entry ->
            SyncLogScreen(
                extensionId = java.net.URLDecoder.decode(entry.arguments?.getString("extensionId") ?: "", "UTF-8"),
                onNavigateUp = { navController.popBackStack() },
            )
        }
    }
}
