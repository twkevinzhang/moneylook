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
import tw.kevinzhang.moneylook.ui.transactions.AutoRuleScreen
import tw.kevinzhang.moneylook.ui.transactions.CategoryManagementScreen
import tw.kevinzhang.moneylook.ui.transactions.GlobalTransactionsScreen
import tw.kevinzhang.moneylook.ui.transactions.TagManagementScreen
import tw.kevinzhang.moneylook.ui.transactions.TransactionDetailScreen

private val bottomBarRoutes = setOf(
    Screen.Home.route,
    Screen.GlobalTransactions.route,
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
                    selected = currentRoute == Screen.GlobalTransactions.route,
                    onClick = {
                        navController.navigate(Screen.GlobalTransactions.route) {
                            launchSingleTop = true
                            restoreState = true
                            popUpTo(Screen.Home.route) { saveState = true }
                        }
                    },
                    icon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = "明細") },
                    label = { Text("明細") },
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
        composable(Screen.GlobalTransactions.route) {
            GlobalTransactionsScreen(
                bottomBar = bottomBar,
                onNavigateToTransaction = { transferId ->
                    navController.navigate(Screen.TransactionDetail.route(transferId))
                },
                analysisContent = { state -> GlobalLedgerAnalysisContent(state) },
            )
        }
        composable(Screen.Settings.route) {
            SettingsScreen(
                bottomBar = bottomBar,
                onNavigateToCategories = { navController.navigate(Screen.Categories.route) },
                onNavigateToTags = { navController.navigate(Screen.Tags.route) },
                onNavigateToRules = { navController.navigate(Screen.AutoRules.route) },
            )
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
