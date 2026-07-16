package ng1ok.yggdrasilmanager.ui.hwbp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

private val RunningIndicator = Color(0xFF2E7D32)

@Composable
fun HwbpScreen(vm: HwbpViewModel = viewModel(factory = HwbpViewModel.Factory)) {
    val uiState by vm.uiState.collectAsState()
    val error by vm.error.collectAsState()

    error?.let { message ->
        AlertDialog(
            onDismissRequest = vm::dismissError,
            confirmButton = {
                TextButton(onClick = vm::dismissError) {
                    Text("OK")
                }
            },
            text = { Text(message) },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        StatusRow(uiState)
        EndpointRow("Web UI", uiState.httpUrl)
        EndpointRow("WebSocket", uiState.webSocketUrl)

        HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
        MetricRow("Connected PC clients", uiState.connectedClients)
        MetricRow("Active observe points", uiState.observePointCount)

        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = vm::toggleServer,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
        ) {
            Icon(
                imageVector = if (uiState.serverRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
                contentDescription = null,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(if (uiState.serverRunning) "Stop server" else "Start server")
        }
    }
}

@Composable
private fun StatusRow(uiState: HwbpUiState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Server",
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(
                    if (uiState.serverRunning) {
                        RunningIndicator
                    } else {
                        MaterialTheme.colorScheme.outline
                    }
                ),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = uiState.serverStatusLabel(),
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

@Composable
private fun EndpointRow(label: String, endpoint: String?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 68.dp)
            .padding(vertical = 8.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(4.dp))
        SelectionContainer {
            Text(
                text = endpoint ?: "Wi-Fi address unavailable",
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun MetricRow(label: String, value: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value.toString(),
            style = MaterialTheme.typography.titleMedium,
        )
    }
}
