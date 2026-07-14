package ng1ok.yggdrasilmanager.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.ui.graphics.vector.ImageVector

sealed class BottomNavItem(val route: String, val label: String, val icon: ImageVector) {
    object Home : BottomNavItem("home", "主頁", Icons.Default.Home)
    object Features : BottomNavItem("features", "功能", Icons.Default.List)
}
