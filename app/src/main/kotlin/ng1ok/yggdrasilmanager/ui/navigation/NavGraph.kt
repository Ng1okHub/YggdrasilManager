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
import ng1ok.yggdrasilmanager.ui.maps.MapsScreen
import ng1ok.yggdrasilmanager.ui.selftest.SelfTestScreen
import ng1ok.yggdrasilmanager.ui.syscall.SyscallScreen

@Composable
fun NavGraph(navController: NavHostController, modifier: Modifier = Modifier) {
    NavHost(
        navController = navController,
        startDestination = AppDestination.Home.route,
        modifier = modifier
    ) {
        composable(AppDestination.Home.route) {
            HomeScreen(
                onOpenSelfTest = { navController.navigate(AppDestination.SelfTest.route) }
            )
        }
        composable(AppDestination.Features.route) { FeaturesScreen(navController) }
        composable(AppDestination.Hwbp.route) { HwbpScreen() }
        composable(AppDestination.Syscall.route) { SyscallScreen() }
        composable(AppDestination.HideDbg.route) { HideDbgScreen() }
        composable(AppDestination.Maps.route) { MapsScreen() }
        composable(AppDestination.SelfTest.route) { SelfTestScreen() }
    }
}
