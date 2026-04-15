package tw.kevinzhang.moneylook.ui.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import tw.kevinzhang.moneylook.ui.home.HomeScreen
import tw.kevinzhang.moneylook.ui.marketplace.ManageReposScreen
import tw.kevinzhang.moneylook.ui.marketplace.MarketplaceScreen

@Composable
fun AppNavHost(navController: NavHostController) {
    NavHost(
        navController = navController,
        startDestination = Screen.Home.route,
        enterTransition = { EnterTransition.None },
        exitTransition = { ExitTransition.None },
        popEnterTransition = { EnterTransition.None },
        popExitTransition = { ExitTransition.None },
    ) {
        composable(Screen.Home.route) {
            HomeScreen(onNavigateToMarketplace = { navController.navigate(Screen.Marketplace.route) })
        }
        composable(Screen.Marketplace.route) {
            MarketplaceScreen(
                onNavigateUp = { navController.popBackStack() },
                onNavigateToManageRepos = { navController.navigate(Screen.ManageRepos.route) },
            )
        }
        composable(Screen.ManageRepos.route) {
            ManageReposScreen(onNavigateUp = { navController.popBackStack() })
        }
    }
}
