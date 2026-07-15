package ng1ok.yggdrasilmanager.ui.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import ng1ok.yggdrasilmanager.R

@Composable
fun HomeScreen(vm: HomeViewModel = viewModel(factory = HomeViewModel.Factory)) {
    val state by vm.uiState.collectAsState()

    LaunchedEffect(vm) {
        vm.startStartupChecks()
    }

    if (state.showRootDeniedDialog) {
        AlertDialog(
            onDismissRequest = vm::dismissRootDeniedDialog,
            title = { Text("Root 權限未授權") },
            text = {
                Text("YggdrasilManager 尚未取得 Root 權限；此提示不會限制任何功能。")
            },
            confirmButton = {
                TextButton(onClick = vm::dismissRootDeniedDialog) {
                    Text("知道了")
                }
            }
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            YggdrasilSummary(status = state.yggdrasilStatus)
        }
        item {
            RootSummary(status = state.rootStatus)
        }
        item {
            DeviceInfoPanel(deviceInfo = state.deviceInfo)
        }
    }
}

@Composable
private fun YggdrasilSummary(status: YggdrasilStatus) {
    val containerColor = when (status) {
        YggdrasilStatus.CHECKING -> MaterialTheme.colorScheme.surfaceVariant
        YggdrasilStatus.AVAILABLE -> MaterialTheme.colorScheme.primaryContainer
        YggdrasilStatus.UNAVAILABLE -> MaterialTheme.colorScheme.errorContainer
    }
    val contentColor = when (status) {
        YggdrasilStatus.CHECKING -> MaterialTheme.colorScheme.onSurfaceVariant
        YggdrasilStatus.AVAILABLE -> MaterialTheme.colorScheme.onPrimaryContainer
        YggdrasilStatus.UNAVAILABLE -> MaterialTheme.colorScheme.onErrorContainer
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = containerColor,
        contentColor = contentColor
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(
                painter = painterResource(R.drawable.ic_launcher_foreground),
                contentDescription = null,
                modifier = Modifier.size(56.dp)
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Yggdrasil", style = MaterialTheme.typography.titleLarge)
                StatusLine(
                    icon = when (status) {
                        YggdrasilStatus.CHECKING -> Icons.Default.HourglassTop
                        YggdrasilStatus.AVAILABLE -> Icons.Default.CheckCircle
                        YggdrasilStatus.UNAVAILABLE -> Icons.Default.Warning
                    },
                    label = status.label,
                    color = contentColor
                )
            }
        }
    }
}

@Composable
private fun RootSummary(status: RootStatus) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Root 權限", style = MaterialTheme.typography.titleMedium)
            StatusLine(
                icon = when (status) {
                    RootStatus.CHECKING -> Icons.Default.HourglassTop
                    RootStatus.GRANTED -> Icons.Default.CheckCircle
                    RootStatus.DENIED -> Icons.Default.Warning
                },
                label = status.label,
                color = when (status) {
                    RootStatus.CHECKING -> MaterialTheme.colorScheme.onSurfaceVariant
                    RootStatus.GRANTED -> MaterialTheme.colorScheme.primary
                    RootStatus.DENIED -> MaterialTheme.colorScheme.error
                }
            )
        }
    }
}

@Composable
private fun StatusLine(icon: ImageVector, label: String, color: Color) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(20.dp)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = color
        )
    }
}

@Composable
private fun DeviceInfoPanel(deviceInfo: DeviceInfo) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 1.dp
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("設備資訊", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            InfoRow(label = "設備", value = deviceInfo.displayName)
            HorizontalDivider()
            InfoRow(label = "系統", value = deviceInfo.androidLabel)
            HorizontalDivider()
            InfoRow(label = "核心", value = deviceInfo.kernelVersion)
            HorizontalDivider()
            InfoRow(label = "Manager", value = deviceInfo.managerVersion)
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(0.32f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            modifier = Modifier.weight(0.68f),
            style = MaterialTheme.typography.bodyMedium
        )
    }
}
