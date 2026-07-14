package ng1ok.yggdrasilmanager.ui.home

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.topjohnwu.superuser.Shell

@Composable
fun HomeScreen(vm: HomeViewModel = viewModel()) {
    val state by vm.uiState.collectAsState()

    LaunchedEffect(Unit) {
        Shell.getShell { shell ->
            vm.refresh(rootGranted = shell.isRoot)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("YggdrasilManager", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        StatusCard(
            title = "Root 權限",
            ok = state.rootGranted,
            okText = "已獲取",
            failText = "未獲取"
        )
        StatusCard(
            title = "Yggdrasil 模塊",
            ok = state.moduleAvailable,
            okText = "可用",
            failText = if (!state.rootGranted) "需要Root" else "不可用"
        )
    }
}

@Composable
private fun StatusCard(title: String, ok: Boolean, okText: String, failText: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                    imageVector = if (ok) Icons.Default.CheckCircle else Icons.Default.Warning,
                    contentDescription = null,
                    tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
                Text(
                    if (ok) okText else failText,
                    color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
