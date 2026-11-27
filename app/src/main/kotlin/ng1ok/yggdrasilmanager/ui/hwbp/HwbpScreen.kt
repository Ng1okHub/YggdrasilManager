package ng1ok.yggdrasilmanager.ui.hwbp

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import ng1ok.yggdrasilmanager.ui.components.CenteredPage

@Composable
fun HwbpScreen(vm: HwbpViewModel = viewModel(factory = HwbpViewModel.Factory)) {
    val uiState by vm.uiState.collectAsState()
    val error by vm.error.collectAsState()
    val context = LocalContext.current

    error?.let { message ->
        AlertDialog(
            onDismissRequest = vm::dismissError,
            confirmButton = {
                TextButton(onClick = vm::dismissError) {
                    Text("確定")
                }
            },
            title = { Text("無法完成操作") },
            text = { Text(message) },
        )
    }

    CenteredPage(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            ServerStatusPanel(
                serverRunning = uiState.serverRunning,
                onToggleServer = vm::toggleServer,
            )

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle("連線端點")
                EndpointSurface(
                    label = "網頁控制台",
                    endpoint = uiState.httpUrl,
                    icon = Icons.Default.Language,
                    onCopy = {
                        copyEndpoint(context, it, "已複製網頁控制台位址")
                    },
                )
                EndpointSurface(
                    label = "WebSocket 端點",
                    endpoint = uiState.webSocketUrl,
                    icon = Icons.Default.Cable,
                    onCopy = {
                        copyEndpoint(context, it, "已複製 WebSocket 位址")
                    },
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle("服務概況")
                MetricsGrid(uiState)
            }
        }
    }
}

private fun copyEndpoint(context: Context, endpoint: String, toastMessage: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("hwbp-endpoint", endpoint))
    Toast.makeText(context, toastMessage, Toast.LENGTH_SHORT).show()
}

@Composable
private fun ServerStatusPanel(
    serverRunning: Boolean,
    onToggleServer: () -> Unit,
) {
    val status = if (serverRunning) "伺服器執行中" else "伺服器已停止"
    val containerColor = if (serverRunning) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val contentColor = if (serverRunning) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { stateDescription = status },
        shape = MaterialTheme.shapes.medium,
        color = containerColor,
        contentColor = contentColor,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = if (serverRunning) {
                        Icons.Default.CheckCircle
                    } else {
                        Icons.Default.PowerSettingsNew
                    },
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = "HWBP 監看服務",
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        text = status,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (serverRunning) {
                            contentColor
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }

            Button(
                onClick = onToggleServer,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
                colors = if (serverRunning) {
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    )
                } else {
                    ButtonDefaults.buttonColors()
                },
            ) {
                Icon(
                    imageVector = if (serverRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
                    contentDescription = null,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(if (serverRunning) "停止伺服器" else "啟動伺服器")
            }
        }
    }
}

@Composable
private fun EndpointSurface(
    label: String,
    endpoint: String?,
    icon: ImageVector,
    onCopy: (String) -> Unit,
) {
    Surface(
        onClick = { endpoint?.let(onCopy) },
        enabled = endpoint != null,
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {},
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 80.dp)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SelectionContainer {
                    Text(
                        text = endpoint ?: "目前無法取得位址",
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        color = if (endpoint != null) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
            if (endpoint != null) {
                Icon(
                    imageVector = Icons.Default.ContentCopy,
                    contentDescription = "複製${label}位址",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun MetricsGrid(uiState: HwbpUiState) {
    val metrics = listOf(
        "已連線用戶端" to uiState.connectedClients.toString(),
        "作用中監看點" to uiState.observePointCount.toString(),
        "已儲存設定" to uiState.savedConfigCount.toString(),
        "回溯模式" to uiState.backtraceMode,
    )

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val columnCount = if (maxWidth >= 600.dp) 4 else 2

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            metrics.chunked(columnCount).forEach { rowMetrics ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    rowMetrics.forEach { (label, value) ->
                        MetricTile(
                            label = label,
                            value = value,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                        )
                    }
                    repeat(columnCount - rowMetrics.size) {
                        Spacer(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MetricTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.heightIn(min = 88.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
