package ng1ok.yggdrasilmanager.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Smartphone
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

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

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxHeight()
                .widthIn(max = 840.dp)
                .fillMaxWidth(),
            contentPadding = PaddingValues(
                start = 20.dp,
                top = 12.dp,
                end = 20.dp,
                bottom = 32.dp
            ),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            item {
                YggdrasilSummary(
                    status = state.yggdrasilStatus,
                    version = state.yggdrasilVersion
                )
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionTitle(
                        title = "系統狀態",
                        supportingText = "執行環境與權限"
                    )
                    RootSummary(
                        status = state.rootStatus
                    )
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionTitle(
                        title = "設備資訊",
                        supportingText = state.deviceInfo.displayName
                    )
                    DeviceInfoPanel(deviceInfo = state.deviceInfo)
                }
            }
        }
    }
}

@Composable
private fun YggdrasilSummary(
    status: YggdrasilStatus,
    version: YggdrasilVersion?
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    modifier = Modifier.size(52.dp),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.AccountTree,
                            contentDescription = null,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Yggdrasil",
                        style = MaterialTheme.typography.headlineSmall
                    )
                    Text(
                        text = "核心服務",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f)
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                StatusBadge(
                    icon = status.icon,
                    label = status.label,
                    containerColor = status.containerColor,
                    contentColor = status.contentColor
                )
                Text(
                    text = "v${version?.label ?: "--"}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f)
                )
            }
        }
    }
}

@Composable
private fun RootSummary(
    status: RootStatus
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(40.dp),
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Security,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Root 權限",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = "特權操作通道",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            StatusBadge(
                icon = status.icon,
                label = status.label,
                containerColor = status.containerColor,
                contentColor = status.contentColor
            )
        }
    }
}

@Composable
private fun StatusBadge(
    icon: ImageVector,
    label: String,
    containerColor: Color,
    contentColor: Color
) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = containerColor,
        contentColor = contentColor,
        modifier = Modifier.semantics(mergeDescendants = true) {}
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
            Text(text = label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun SectionTitle(title: String, supportingText: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom
    ) {
        Text(text = title, style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = supportingText,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun DeviceInfoPanel(deviceInfo: DeviceInfo) {
    val rows = listOf(
        DeviceInfoRow(Icons.Default.Smartphone, "設備", deviceInfo.displayName),
        DeviceInfoRow(Icons.Default.Android, "系統", deviceInfo.androidLabel),
        DeviceInfoRow(Icons.Default.Memory, "核心", deviceInfo.kernelVersion),
        DeviceInfoRow(Icons.Default.Info, "Manager", deviceInfo.managerVersion)
    )

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)
        )
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            rows.forEachIndexed { index, row ->
                InfoRow(row = row)
                if (index != rows.lastIndex) {
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 44.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
                    )
                }
            }
        }
    }
}

private data class DeviceInfoRow(
    val icon: ImageVector,
    val label: String,
    val value: String
)

@Composable
private fun InfoRow(row: DeviceInfoRow) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            imageVector = row.icon,
            contentDescription = null,
            modifier = Modifier
                .padding(top = 1.dp)
                .size(20.dp),
            tint = MaterialTheme.colorScheme.tertiary
        )
        Text(
            text = row.label,
            modifier = Modifier.width(72.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = row.value,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

private val YggdrasilStatus.icon: ImageVector
    get() = when (this) {
        YggdrasilStatus.CHECKING -> Icons.Default.HourglassTop
        YggdrasilStatus.AVAILABLE -> Icons.Default.CheckCircle
        YggdrasilStatus.UNAVAILABLE -> Icons.Default.Warning
    }

private val RootStatus.icon: ImageVector
    get() = when (this) {
        RootStatus.CHECKING -> Icons.Default.HourglassTop
        RootStatus.GRANTED -> Icons.Default.CheckCircle
        RootStatus.DENIED -> Icons.Default.Warning
    }

private val YggdrasilStatus.containerColor: Color
    @Composable get() = when (this) {
        YggdrasilStatus.CHECKING -> MaterialTheme.colorScheme.surfaceContainerHighest
        YggdrasilStatus.AVAILABLE -> MaterialTheme.colorScheme.primary
        YggdrasilStatus.UNAVAILABLE -> MaterialTheme.colorScheme.error
    }

private val YggdrasilStatus.contentColor: Color
    @Composable get() = when (this) {
        YggdrasilStatus.CHECKING -> MaterialTheme.colorScheme.onSurface
        YggdrasilStatus.AVAILABLE -> MaterialTheme.colorScheme.onPrimary
        YggdrasilStatus.UNAVAILABLE -> MaterialTheme.colorScheme.onError
    }

private val RootStatus.containerColor: Color
    @Composable get() = when (this) {
        RootStatus.CHECKING -> MaterialTheme.colorScheme.surfaceContainerHighest
        RootStatus.GRANTED -> MaterialTheme.colorScheme.primaryContainer
        RootStatus.DENIED -> MaterialTheme.colorScheme.errorContainer
    }

private val RootStatus.contentColor: Color
    @Composable get() = when (this) {
        RootStatus.CHECKING -> MaterialTheme.colorScheme.onSurface
        RootStatus.GRANTED -> MaterialTheme.colorScheme.onPrimaryContainer
        RootStatus.DENIED -> MaterialTheme.colorScheme.onErrorContainer
    }
