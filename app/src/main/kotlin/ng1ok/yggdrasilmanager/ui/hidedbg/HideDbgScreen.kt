package ng1ok.yggdrasilmanager.ui.hidedbg

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun HideDbgScreen(vm: HideDbgViewModel = viewModel(factory = HideDbgViewModel.Factory)) {
    val globalEnabled by vm.globalEnabled.collectAsState()
    val uidRules by vm.uidRules.collectAsState()
    val error by vm.error.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }

    error?.let { msg ->
        AlertDialog(
            onDismissRequest = vm::dismissError,
            confirmButton = { TextButton(onClick = vm::dismissError) { Text("OK") } },
            text = { Text(msg) }
        )
    }

    if (showAddDialog) {
        AddUidDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { uid -> vm.addUid(uid); showAddDialog = false }
        )
    }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "Add UID rule")
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                GlobalSwitchCard(enabled = globalEnabled, onToggle = vm::toggleGlobal)
            }
            if (uidRules.isNotEmpty()) {
                item {
                    Text(
                        "Per-UID Rules",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                items(uidRules) { uid ->
                    UidRuleCard(uid = uid, onRemove = { vm.removeUid(uid) })
                }
            }
        }
    }
}

@Composable
private fun GlobalSwitchCard(enabled: Boolean, onToggle: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(Modifier.weight(1f)) {
                Text("Hide Debugger", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Globally hide debugger presence from all processes",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = enabled, onCheckedChange = { onToggle() })
        }
    }
}

@Composable
private fun UidRuleCard(uid: Int, onRemove: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("UID: $uid", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = onRemove) {
                Icon(Icons.Default.Delete, contentDescription = "Remove UID rule")
            }
        }
    }
}

@Composable
private fun AddUidDialog(onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    var uidText by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add UID Rule") },
        text = {
            OutlinedTextField(
                value = uidText,
                onValueChange = { uidText = it },
                label = { Text("UID") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true
            )
        },
        confirmButton = {
            TextButton(onClick = {
                uidText.toIntOrNull()?.let { onConfirm(it) }
            }) { Text("Add") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
