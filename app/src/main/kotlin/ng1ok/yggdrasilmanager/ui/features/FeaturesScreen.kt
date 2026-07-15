package ng1ok.yggdrasilmanager.ui.features

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import ng1ok.yggdrasilmanager.ui.navigation.AppDestination

private data class FeatureItem(
    val destination: AppDestination,
    val description: String,
    val icon: ImageVector
)

private val features = listOf(
    FeatureItem(
        destination = AppDestination.Hwbp,
        description = "通過硬件斷點觀察進程內存訪問，實時查看觸發事件",
        icon = Icons.Default.Visibility
    ),
    FeatureItem(
        destination = AppDestination.HideDbg,
        description = "隱藏調試器狀態，支持全局模式和按 App 精確控制",
        icon = Icons.Default.BugReport
    )
)

@Composable
fun FeaturesScreen(navController: NavController) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        features.forEach { feature ->
            FeatureCard(
                feature = feature,
                onClick = { navController.navigate(feature.destination.route) }
            )
        }
    }
}

@Composable
private fun FeatureCard(feature: FeatureItem, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(
                imageVector = feature.icon,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Column {
                Text(feature.destination.title, style = MaterialTheme.typography.titleMedium)
                Text(feature.description, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
