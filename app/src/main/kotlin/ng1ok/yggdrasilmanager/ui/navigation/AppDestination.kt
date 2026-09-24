package ng1ok.yggdrasilmanager.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.ui.graphics.vector.ImageVector

enum class AppDestination(
    val route: String,
    val title: String,
    val isTopLevel: Boolean,
    val bottomBarIcon: ImageVector? = null
) {
    Home("home", "主頁", true, Icons.Default.Home),
    Features("features", "功能", true, Icons.AutoMirrored.Filled.List),
    Hwbp("hwbp", "HWBP Observe", false),
    Syscall("syscall", "Arm64 Syscall Observe", false),
    HideDbg("hidedbg", "Hide DBG", false),
    Maps("maps", "Hide Maps", false);

    val showBackButton: Boolean
        get() = !isTopLevel

    val showBottomNavigation: Boolean
        get() = isTopLevel

    companion object {
        val topLevel: List<AppDestination> = entries.filter(AppDestination::isTopLevel)

        fun fromRoute(route: String?): AppDestination =
            entries.firstOrNull { it.route == route } ?: Home
    }
}
