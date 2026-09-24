package ng1ok.yggdrasilmanager.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppDestinationTest {
    @Test
    fun entriesExposeRouteMetadataInNavigationOrder() {
        assertEquals(
            listOf(
                Triple("home", "主頁", true),
                Triple("features", "功能", true),
                Triple("hwbp", "HWBP Observe", false),
                Triple("syscall", "Arm64 Syscall Observe", false),
                Triple("hidedbg", "Hide DBG", false),
                Triple("maps", "Hide Maps", false)
            ),
            AppDestination.entries.map { Triple(it.route, it.title, it.isTopLevel) }
        )
    }

    @Test
    fun topLevelDestinationsShowBottomNavigationWithoutBackButton() {
        assertEquals(
            listOf(AppDestination.Home, AppDestination.Features),
            AppDestination.topLevel
        )
        AppDestination.topLevel.forEach { destination ->
            assertTrue(destination.showBottomNavigation)
            assertFalse(destination.showBackButton)
        }
    }

    @Test
    fun childDestinationsShowBackButtonWithoutBottomNavigation() {
        listOf(AppDestination.Hwbp, AppDestination.Syscall, AppDestination.HideDbg, AppDestination.Maps).forEach { destination ->
            assertTrue(destination.showBackButton)
            assertFalse(destination.showBottomNavigation)
        }
    }

    @Test
    fun routeLookupFallsBackToHome() {
        assertEquals(AppDestination.Hwbp, AppDestination.fromRoute("hwbp"))
        assertEquals(AppDestination.Syscall, AppDestination.fromRoute("syscall"))
        assertEquals(AppDestination.Home, AppDestination.fromRoute(null))
        assertEquals(AppDestination.Home, AppDestination.fromRoute("unknown"))
    }
}
