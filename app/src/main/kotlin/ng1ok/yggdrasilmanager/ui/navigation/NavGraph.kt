package ng1ok.yggdrasilmanager.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import ng1ok.yggdrasilmanager.ui.features.FeaturesScreen
import ng1ok.yggdrasilmanager.ui.hidedbg.HideDbgScreen
import ng1ok.yggdrasilmanager.ui.home.HomeScreen
import ng1ok.yggdrasilmanager.ui.hwbp.HwbpScreen

@Composable
fun NavGraph(navController: NavHostController, modifier: Modifier = Modifier) {
    NavHost(navController = navController, startDestination = BottomNavItem.Home.route, modifier = modifier) {
        composable(BottomNavItem.Home.route) { HomeScreen() }
        composable(BottomNavItem.Features.route) { FeaturesScreen(navController) }
        composable("hwbp") { HwbpScreen() }
        composable("hidedbg") { HideDbgScreen() }
    }
}
