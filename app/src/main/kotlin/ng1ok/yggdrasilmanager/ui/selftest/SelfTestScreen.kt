package ng1ok.yggdrasilmanager.ui.selftest

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import java.util.Locale
import ng1ok.yggdrasilmanager.data.selftest.SelfTestFeature
import ng1ok.yggdrasilmanager.data.selftest.SelfTestLogKind
import ng1ok.yggdrasilmanager.data.selftest.SelfTestLogLine
import ng1ok.yggdrasilmanager.data.selftest.SelfTestRootState
import ng1ok.yggdrasilmanager.data.selftest.SelfTestStatus
import ng1ok.yggdrasilmanager.ui.home.YggdrasilStatus

private const val PassColorLight = 0xFF2E7D32
private const val PassColorDark = 0xFF81C784

@Composable
private fun passColor(): Color =
    if (isSystemInDarkTheme()) Color(PassColorDark) else Color(PassColorLight)

@Composable
fun SelfTestScreen(vm: SelfTestViewModel = viewModel(factory = SelfTestViewModel.Factory)) {
    val state by vm.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(vm) {
        vm.refreshEnvironment()
    }
    LaunchedEffect(state.message) {
        state.message?.let { message ->
            snackbarHostState.showSnackbar(message)
            vm.dismissMessage()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.TopCenter,
        ) {
            // Short (landscape) windows cannot fit the panels and a weighted
            // console at once, so the page scrolls and the console gets a
            // fixed height instead.
            val compact = maxHeight < 620.dp
            val scrollState = rememberScrollState()
            Column(
                modifier = Modifier
                    .widthIn(max = 840.dp)
                    .fillMaxSize()
                    .then(
                        if (compact) Modifier.verticalScroll(scrollState) else Modifier
                    )
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                EnvironmentPanel(
                    state = state,
                    onRefresh = { vm.refreshEnvironment(force = true) },
                )
                Spacer(modifier = Modifier.size(10.dp))
                FeatureSelector(
                    state = state,
                    onToggle = vm::toggleFeature,
                )
                Spacer(modifier = Modifier.size(10.dp))
                ActionBar(
                    state = state,
                    onRunAll = vm::runAll,
                    onRunSelected = vm::runSelected,
                    onStop = vm::stop,
                    onSelectAll = vm::selectAll,
                    onClearSelection = vm::clearSelection,
                )
                Spacer(modifier = Modifier.size(8.dp))
                WarningNote()
                Spacer(modifier = Modifier.size(8.dp))
                ConsolePanel(
                    state = state,
                    onClear = vm::clearOutput,
                    modifier = if (compact) {
                        Modifier.fillMaxWidth().height(320.dp)
                    } else {
                        Modifier.weight(1f)
                    },
                )
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .widthIn(max = 840.dp)
                .fillMaxWidth()
                .padding(16.dp),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EnvironmentPanel(
    state: SelfTestUiState,
    onRefresh: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    modifier = Modifier.size(44.dp),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Science,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Yggdrasil 模組自測",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = "在裝置上執行 KPM 內建自測程式",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onRefresh, enabled = !state.running) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "重新檢查",
                    )
                }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when (state.moduleStatus) {
                    YggdrasilStatus.CHECKING -> StatusPill(
                        label = "模組檢查中",
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    YggdrasilStatus.UNAVAILABLE -> StatusPill(
                        label = "模組不可用",
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    )

                    YggdrasilStatus.AVAILABLE -> StatusPill(
                        label = "模組可用" + (state.moduleVersion?.let { " v${it.label}" } ?: ""),
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                when (state.rootState) {
                    SelfTestRootState.GRANTED -> StatusPill(
                        label = "Root 已授權",
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    )

                    SelfTestRootState.DENIED -> StatusPill(
                        label = "Root 未授權",
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    )

                    SelfTestRootState.CHECKING,
                    SelfTestRootState.UNKNOWN -> StatusPill(
                        label = "Root 檢查中",
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                StatusPill(
                    label = if (state.supported) "arm64-v8a" else "架構不支援",
                    containerColor = if (state.supported) {
                        MaterialTheme.colorScheme.surfaceVariant
                    } else {
                        MaterialTheme.colorScheme.errorContainer
                    },
                    contentColor = if (state.supported) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onErrorContainer
                    },
                )
            }
        }
    }
}

@Composable
private fun StatusPill(
    label: String,
    containerColor: Color,
    contentColor: Color,
) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = containerColor,
        contentColor = contentColor,
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FeatureSelector(
    state: SelfTestUiState,
    onToggle: (SelfTestFeature) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "測試項目",
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = "${state.selected.size} / ${state.features.size} 已選",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            state.features.forEach { entry ->
                FilterChip(
                    selected = entry.selected,
                    onClick = { onToggle(entry.feature) },
                    enabled = !state.running,
                    modifier = Modifier
                        .heightIn(min = 40.dp)
                        .semantics {
                            contentDescription = "${entry.feature.label}：${entry.feature.description}"
                        },
                    label = {
                        Text(
                            text = entry.feature.label,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    leadingIcon = { FeatureStatusIcon(entry.status) },
                    trailingIcon = if (entry.feature.needsRoot) {
                        {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = "需要 Root",
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    } else {
                        null
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        selectedLeadingIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        selectedTrailingIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    ),
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = null,
                modifier = Modifier.size(12.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "需要 Root",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FeatureStatusIcon(status: SelfTestStatus) {
    when (status) {
        SelfTestStatus.RUNNING -> CircularProgressIndicator(
            modifier = Modifier.size(16.dp),
            strokeWidth = 2.dp,
        )

        SelfTestStatus.QUEUED -> Icon(
            imageVector = Icons.Default.Schedule,
            contentDescription = "等待執行",
            modifier = Modifier.size(16.dp),
        )

        SelfTestStatus.PASSED -> Icon(
            imageVector = Icons.Default.CheckCircle,
            contentDescription = "通過",
            modifier = Modifier.size(16.dp),
            tint = passColor(),
        )

        SelfTestStatus.FAILED,
        SelfTestStatus.ERROR -> Icon(
            imageVector = Icons.Default.Error,
            contentDescription = "失敗",
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.error,
        )

        SelfTestStatus.SKIPPED -> Icon(
            imageVector = Icons.Default.SkipNext,
            contentDescription = "略過",
            modifier = Modifier.size(16.dp),
        )

        SelfTestStatus.NOT_RUN -> Icon(
            imageVector = Icons.Default.RadioButtonUnchecked,
            contentDescription = "未執行",
            modifier = Modifier.size(16.dp),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActionBar(
    state: SelfTestUiState,
    onRunAll: () -> Unit,
    onRunSelected: () -> Unit,
    onStop: () -> Unit,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(
            onClick = onRunAll,
            enabled = state.canRun && !state.running,
            modifier = Modifier.heightIn(min = 40.dp),
        ) {
            Text("執行全部")
        }
        FilledTonalButton(
            onClick = onRunSelected,
            enabled = state.canRun && state.selected.isNotEmpty(),
            modifier = Modifier.heightIn(min = 40.dp),
        ) {
            Text("執行所選（${state.selected.size}）")
        }
        if (state.running) {
            OutlinedButton(
                onClick = onStop,
                enabled = !state.stopping,
                modifier = Modifier.heightIn(min = 40.dp),
            ) {
                if (state.stopping) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(modifier = Modifier.size(6.dp))
                    Text("停止中")
                } else {
                    Icon(
                        imageVector = Icons.Default.Stop,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.size(6.dp))
                    Text("停止")
                }
            }
        }
        TextButton(onClick = onSelectAll, enabled = !state.running) {
            Text("全選")
        }
        TextButton(onClick = onClearSelection, enabled = !state.running) {
            Text("全不選")
        }
    }
}

@Composable
private fun WarningNote() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = Icons.Default.WarningAmber,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.tertiary,
        )
        Text(
            text = "測試會直接接管模組狀態，並在每個小節前重設 maps、syscall、trampoline、freeze 等規則；正在進行的觀察可能被中斷。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ConsolePanel(
    state: SelfTestUiState,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val clipboard = remember(context) {
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "即時輸出",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = summaryText(state),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium,
                    color = summaryColor(state),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                IconButton(
                    onClick = {
                        val text = state.log.joinToString("\n") { it.text }
                        clipboard.setPrimaryClip(
                            ClipData.newPlainText("Yggdrasil self-test", text),
                        )
                    },
                    enabled = state.log.isNotEmpty(),
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = "複製輸出",
                    )
                }
                IconButton(onClick = onClear, enabled = !state.running && state.log.isNotEmpty()) {
                    Icon(
                        imageVector = Icons.Default.DeleteOutline,
                        contentDescription = "清除輸出",
                    )
                }
            }
            HorizontalDivider()
            if (state.log.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(20.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (state.running) {
                            "正在部署並啟動自測程式…"
                        } else {
                            "尚未執行。選擇項目後按「執行全部」或「執行所選」。"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LogList(
                    log = state.log,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
private fun LogList(
    log: List<SelfTestLogLine>,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val autoScroll by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            layoutInfo.totalItemsCount == 0 || lastVisible >= layoutInfo.totalItemsCount - 3
        }
    }

    LaunchedEffect(log.size) {
        if (log.isNotEmpty() && autoScroll) {
            listState.scrollToItem(log.lastIndex)
        }
    }

    SelectionContainer(modifier = modifier) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            itemsIndexed(log) { _, line ->
                LogLine(line)
            }
        }
    }
}

@Composable
private fun LogLine(line: SelfTestLogLine) {
    val color = when (line.kind) {
        SelfTestLogKind.PASS -> passColor()
        SelfTestLogKind.FAIL,
        SelfTestLogKind.ERROR -> MaterialTheme.colorScheme.error

        SelfTestLogKind.SECTION -> MaterialTheme.colorScheme.primary
        SelfTestLogKind.SUMMARY -> MaterialTheme.colorScheme.onSurface
        SelfTestLogKind.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
        SelfTestLogKind.PLAIN -> MaterialTheme.colorScheme.onSurface
    }
    val weight = when (line.kind) {
        SelfTestLogKind.SECTION,
        SelfTestLogKind.SUMMARY,
        SelfTestLogKind.FAIL,
        SelfTestLogKind.ERROR -> FontWeight.SemiBold

        else -> FontWeight.Normal
    }

    Text(
        text = line.text.ifEmpty { " " },
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        lineHeight = 17.sp,
        fontWeight = weight,
        color = color,
    )
}

private fun summaryText(state: SelfTestUiState): String {
    if (state.running) {
        return if (state.stopping) "正在停止…" else "執行中…"
    }
    val outcome = state.outcome ?: return "未執行"
    if (outcome.error != null) return "錯誤：${outcome.error}"
    if (outcome.cancelled) {
        return "已停止（${outcome.checks} 項檢查，${outcome.failed} 項失敗）"
    }
    if (outcome.fatal != null) return "模組不可用"
    val duration = String.format(Locale.ROOT, "%.1f 秒", outcome.durationMs / 1000.0)
    return when {
        outcome.failed > 0 -> "${outcome.checks} 項檢查，${outcome.failed} 項失敗 · $duration"
        outcome.checks == 0 -> "沒有執行任何檢查 · $duration"
        else -> "${outcome.checks} 項檢查，全數通過 · $duration"
    }
}

@Composable
private fun summaryColor(state: SelfTestUiState): Color {
    if (state.running) return MaterialTheme.colorScheme.onSurfaceVariant
    val outcome = state.outcome ?: return MaterialTheme.colorScheme.onSurfaceVariant
    return when {
        outcome.error != null || outcome.cancelled || outcome.fatal != null ->
            MaterialTheme.colorScheme.error

        outcome.failed > 0 -> MaterialTheme.colorScheme.error
        outcome.success -> passColor()
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}
