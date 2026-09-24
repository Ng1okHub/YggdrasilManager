package ng1ok.yggdrasilmanager.ui.maps

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import ng1ok.yggdrasilmanager.data.model.MapsPolicy
import ng1ok.yggdrasilmanager.data.model.MapsRule
import ng1ok.yggdrasilmanager.ui.components.CenteredPage

/** Displays the maps policy editor and the rules in the selected scope. */
@Composable
fun MapsScreen(vm: MapsViewModel = viewModel(factory = MapsViewModel.Factory)) {
    val state by vm.uiState.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.error) {
        state.error?.let {
            snackbar.showSnackbar(it)
            vm.dismissError()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        CenteredPage(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = 20.dp,
                vertical = 16.dp,
            ),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                ScopePanel(state, vm)
                RuleEditor(state, vm)
                RuleList(state, vm)
            }
        }
        SnackbarHost(snackbar, modifier = Modifier.padding(12.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScopePanel(state: MapsUiState, vm: MapsViewModel) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Map, contentDescription = null, modifier = Modifier.size(24.dp))
                Text("Maps 範圍", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).padding(start = 10.dp))
                IconButton(onClick = vm::refresh, enabled = !state.loading && !state.busy) {
                    Icon(Icons.Default.Refresh, contentDescription = "重新整理 Maps 規則")
                }
            }
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                listOf(true to "全域", false to "PID").forEachIndexed { index, (global, label) ->
                    SegmentedButton(
                        selected = state.globalScope == global,
                        onClick = { vm.setGlobalScope(global) },
                        shape = SegmentedButtonDefaults.itemShape(index, 2),
                        label = { Text(label) },
                    )
                }
            }
            if (!state.globalScope) {
                OutlinedTextField(
                    value = state.pidText,
                    onValueChange = vm::setPid,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("目標 PID") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    enabled = !state.busy,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RuleEditor(state: MapsUiState, vm: MapsViewModel) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("新增規則", style = MaterialTheme.typography.titleMedium)
            SegmentedRow(
                values = listOf(MapsPolicy.ACTION_HIDE to "Hide", MapsPolicy.ACTION_REWRITE_PERMS to "Rewrite permissions"),
                selected = state.action,
                onSelect = vm::setAction,
            )
            MatchKindSelector(selected = state.matchKind, onSelect = vm::setMatchKind)
            if (state.matchKind == MapsPolicy.MATCH_ADDR_RANGE) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AddressField(state.startText, vm::setStart, "Start address", Modifier.weight(1f))
                    AddressField(state.endText, vm::setEnd, "End address", Modifier.weight(1f))
                }
            } else if (state.matchKind != MapsPolicy.MATCH_ANY) {
                OutlinedTextField(
                    value = state.pattern,
                    onValueChange = vm::setPattern,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Path pattern") },
                    singleLine = true,
                    enabled = !state.busy,
                )
            }
            OutlinedTextField(
                value = state.matchPerms,
                onValueChange = vm::setMatchPerms,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Match permissions (optional)") },
                placeholder = { Text("r-x") },
                singleLine = true,
                enabled = !state.busy,
            )
            if (state.action == MapsPolicy.ACTION_REWRITE_PERMS) {
                OutlinedTextField(
                    value = state.setPerms,
                    onValueChange = vm::setSetPerms,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Set permissions") },
                    singleLine = true,
                    enabled = !state.busy,
                )
            }
            Button(
                onClick = vm::addRule,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                enabled = !state.busy,
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text("新增規則")
            }
        }
    }
}

@Composable
private fun AddressField(value: String, onValueChange: (String) -> Unit, label: String, modifier: Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        label = { Text(label) },
        placeholder = { Text("0x1000") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MatchKindSelector(selected: Int, onSelect: (Int) -> Unit) {
    val options = listOf(
        MapsPolicy.MATCH_PATH_SUBSTR to "Path contains",
        MapsPolicy.MATCH_PATH_PREFIX to "Path starts with",
        MapsPolicy.MATCH_PATH_EXACT to "Path equals",
        MapsPolicy.MATCH_PATH_BASENAME to "Path basename",
        MapsPolicy.MATCH_ADDR_RANGE to "Address range",
        MapsPolicy.MATCH_ANY to "Any mapping",
    )
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = options.firstOrNull { it.first == selected }?.second ?: "Any mapping"

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
        modifier = Modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            value = selectedLabel,
            onValueChange = {},
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable),
            readOnly = true,
            label = { Text("Match type") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { (value, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        onSelect(value)
                        expanded = false
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SegmentedRow(
    values: List<Pair<Int, String>>,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        values.forEachIndexed { index, (value, label) ->
            SegmentedButton(
                selected = selected == value,
                onClick = { onSelect(value) },
                shape = SegmentedButtonDefaults.itemShape(index, values.size),
                label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
        }
    }
}

@Composable
private fun RuleList(state: MapsUiState, vm: MapsViewModel) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("目前規則（${state.totalRules}）", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = vm::clearCurrentScope, enabled = state.rules.isNotEmpty() && !state.busy) {
                    Icon(Icons.Default.ClearAll, contentDescription = "清除目前範圍")
                }
                if (state.globalScope) {
                    IconButton(onClick = vm::clearAllScopes, enabled = state.rules.isNotEmpty() && !state.busy) {
                        Icon(Icons.Default.Delete, contentDescription = "清除所有範圍")
                    }
                }
            }
            if (state.loading) {
                CircularProgressIndicator(modifier = Modifier.padding(24.dp).align(Alignment.CenterHorizontally))
            } else if (state.rules.isEmpty()) {
                Text(
                    "此範圍沒有規則",
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                state.rules.forEachIndexed { index, rule ->
                    if (index > 0) HorizontalDivider()
                    RuleRow(rule, enabled = !state.busy, onRemove = { vm.removeRule(rule) })
                }
            }
        }
    }
}

@Composable
private fun RuleRow(rule: MapsRule, enabled: Boolean, onRemove: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(ruleLabel(rule), style = MaterialTheme.typography.bodyMedium)
            Text(ruleDetails(rule), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = onRemove, enabled = enabled) {
            Icon(Icons.Default.Delete, contentDescription = "移除規則")
        }
    }
}

private fun ruleLabel(rule: MapsRule): String = when (rule.action) {
    MapsPolicy.ACTION_REWRITE_PERMS -> "改寫權限"
    else -> "隱藏"
} + " / " + when (rule.matchKind) {
    MapsPolicy.MATCH_PATH_SUBSTR -> "包含 ${rule.pattern}"
    MapsPolicy.MATCH_PATH_PREFIX -> "開頭為 ${rule.pattern}"
    MapsPolicy.MATCH_PATH_EXACT -> "完全相等 ${rule.pattern}"
    MapsPolicy.MATCH_PATH_BASENAME -> "檔名為 ${rule.pattern}"
    MapsPolicy.MATCH_ADDR_RANGE -> "範圍 0x${rule.addrStart.toString(16)}-0x${rule.addrEnd.toString(16)}"
    else -> "所有映射"
}

private fun ruleDetails(rule: MapsRule): String = buildString {
    rule.matchPerms?.let { append("匹配=$it") }
    rule.setPerms?.let {
        if (isNotEmpty()) append("; ")
        append("設定=$it")
    }
}
