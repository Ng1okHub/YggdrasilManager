package ng1ok.yggdrasilmanager.ui.hwbp

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import ng1ok.yggdrasilmanager.data.model.HwbpEvent
import ng1ok.yggdrasilmanager.data.model.HwbpObserveParams

private val MODES = listOf("R" to 1, "W" to 2, "RW" to 3, "X" to 4)
private val LENS = listOf(1, 2, 4, 8)

@Composable
fun HwbpScreen(vm: HwbpViewModel = viewModel(factory = HwbpViewModel.Factory)) {
    val observePoints by vm.observePoints.collectAsState()
    val events by vm.events.collectAsState()
    val error by vm.error.collectAsState()
    var selectedTab by remember { mutableIntStateOf(0) }
    var showAddSheet by remember { mutableStateOf(false) }

    error?.let { msg ->
        AlertDialog(
            onDismissRequest = vm::dismissError,
            confirmButton = { TextButton(onClick = vm::dismissError) { Text("OK") } },
            text = { Text(msg) }
        )
    }

    Scaffold(
        floatingActionButton = {
            if (selectedTab == 0) {
                FloatingActionButton(onClick = { showAddSheet = true }) {
                    Icon(Icons.Default.Add, contentDescription = "Add observe point")
                }
            }
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            TabRow(selectedTabIndex = selectedTab) {
                Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("Observe") })
                Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("Events") })
            }
            when (selectedTab) {
                0 -> ObserveTab(points = observePoints, onRemove = vm::removeObserve)
                1 -> EventsTab(events = events, onClear = vm::clearEvents)
            }
        }
    }

    if (showAddSheet) {
        AddObserveSheet(
            onDismiss = { showAddSheet = false },
            onConfirm = { params -> vm.addObserve(params); showAddSheet = false }
        )
    }
}

@Composable
private fun ObserveTab(points: List<HwbpObserveParams>, onRemove: (HwbpObserveParams) -> Unit) {
    if (points.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No observe points", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(points) { p ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "PID: ${p.pid}  Addr: 0x${p.targetAddr.toString(16).uppercase()}",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            "Mode: ${MODES.firstOrNull { it.second == p.mode }?.first ?: p.mode}  Len: ${p.len}  Stack: ${p.stackSize}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = { onRemove(p) }) {
                        Icon(Icons.Default.Delete, contentDescription = "Remove")
                    }
                }
            }
        }
    }
}

@Composable
private fun EventsTab(events: List<HwbpEvent>, onClear: () -> Unit) {
    val listState = rememberLazyListState()
    LaunchedEffect(events.size) {
        if (events.isNotEmpty()) listState.animateScrollToItem(events.size - 1)
    }
    Column {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("${events.size} events", style = MaterialTheme.typography.labelMedium)
            TextButton(onClick = onClear) { Text("Clear") }
        }
        if (events.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Waiting for events...", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@Column
        }
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(events, key = { it.seq }) { e -> EventCard(e) }
        }
    }
}

@Composable
private fun EventCard(e: HwbpEvent) {
    var expanded by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth(), onClick = { expanded = !expanded }) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("#${e.seq}", style = MaterialTheme.typography.labelSmall)
                if (e.dropped > 0) Text(
                    "dropped:${e.dropped}",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelSmall
                )
            }
            Text("${e.comm} (tid=${e.tid} tgid=${e.tgid})", style = MaterialTheme.typography.bodyMedium)
            Text(
                "watched=0x${e.watchedAddr.toString(16).uppercase()}  pc=0x${e.pc.toString(16).uppercase()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (expanded) {
                Spacer(Modifier.height(4.dp))
                Text("sp=0x${e.sp.toString(16).uppercase()}", style = MaterialTheme.typography.bodySmall)
                e.regs.forEachIndexed { i, v ->
                    Text("x$i=0x${v.toString(16).uppercase()}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddObserveSheet(onDismiss: () -> Unit, onConfirm: (HwbpObserveParams) -> Unit) {
    var addrText by remember { mutableStateOf("") }
    var pidText by remember { mutableStateOf("") }
    var modeIdx by remember { mutableIntStateOf(0) }
    var lenIdx by remember { mutableIntStateOf(2) }
    var singleThread by remember { mutableStateOf(false) }
    var filterSystem by remember { mutableStateOf(false) }
    var stackText by remember { mutableStateOf("4096") }

    val selectedMode = MODES[modeIdx].second
    val effectiveLenIdx = if (selectedMode == 4) LENS.indexOf(4).coerceAtLeast(0) else lenIdx

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Add Observe Point", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                value = addrText, onValueChange = { addrText = it },
                label = { Text("Target address (hex, e.g. 7f1a2b3c)") },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii)
            )
            OutlinedTextField(
                value = pidText, onValueChange = { pidText = it },
                label = { Text("Target PID") },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
            Text("Mode", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MODES.forEachIndexed { i, (label, _) ->
                    FilterChip(selected = modeIdx == i, onClick = { modeIdx = i }, label = { Text(label) })
                }
            }
            Text("Len (bytes)", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LENS.forEachIndexed { i, len ->
                    FilterChip(
                        selected = effectiveLenIdx == i,
                        onClick = { if (selectedMode != 4) lenIdx = i },
                        label = { Text("$len") },
                        enabled = selectedMode != 4
                    )
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Single Thread")
                Switch(checked = singleThread, onCheckedChange = { singleThread = it })
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Filter System")
                Switch(checked = filterSystem, onCheckedChange = { filterSystem = it })
            }
            OutlinedTextField(
                value = stackText, onValueChange = { stackText = it },
                label = { Text("Stack size (max 65536)") },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = {
                    val addr = addrText.trimStart('0', 'x', 'X').toLongOrNull(16) ?: return@Button
                    val pid = pidText.toIntOrNull() ?: return@Button
                    val stack = stackText.toIntOrNull()?.coerceIn(0, 65536) ?: 4096
                    val flags = (if (singleThread) 1 else 0) or (if (filterSystem) 2 else 0)
                    onConfirm(HwbpObserveParams(pid, addr, selectedMode, LENS[effectiveLenIdx], flags, stack))
                }) { Text("Confirm") }
            }
        }
    }
}
