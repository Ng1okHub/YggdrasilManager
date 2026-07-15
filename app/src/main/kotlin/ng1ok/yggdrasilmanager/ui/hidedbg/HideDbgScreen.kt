package ng1ok.yggdrasilmanager.ui.hidedbg

import android.graphics.drawable.Drawable
import android.widget.ImageView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ng1ok.yggdrasilmanager.data.hidedbg.AppFilter
import ng1ok.yggdrasilmanager.data.hidedbg.InstalledApp

@Composable
fun HideDbgScreen(vm: HideDbgViewModel = viewModel(factory = HideDbgViewModel.Factory)) {
    val state by vm.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        state.message?.let { message ->
            snackbarHostState.showSnackbar(message)
            vm.dismissMessage()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            GlobalSetting(
                state = state,
                onToggle = vm::toggleGlobal
            )
            HorizontalDivider()
            AppControls(
                state = state,
                onQueryChange = vm::setQuery,
                onFilterChange = vm::setFilter,
                onRefresh = vm::refreshApps
            )
            HorizontalDivider()
            AppsPanel(
                state = state,
                onToggleUid = vm::toggleUid,
                onRetry = vm::refreshApps,
                modifier = Modifier.weight(1f)
            )
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp)
        )
    }
}

@Composable
private fun GlobalSetting(state: HideDbgUiState, onToggle: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().height(84.dp).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "全局 Hide DBG",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = "為所有 App 隱藏調試器狀態",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        ToggleSlot(
            checked = state.globalEnabled,
            enabled = state.pendingUids.isEmpty(),
            pending = state.globalPending,
            onToggle = onToggle
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppControls(
    state: HideDbgUiState,
    onQueryChange: (String) -> Unit,
    onFilterChange: (AppFilter) -> Unit,
    onRefresh: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = state.query,
                onValueChange = onQueryChange,
                modifier = Modifier.weight(1f),
                label = { Text("搜尋 App") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null
                    )
                },
                singleLine = true
            )
            IconButton(
                onClick = onRefresh,
                enabled = !state.loadingApps,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "重新整理 App"
                )
            }
        }

        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            AppFilter.entries.forEachIndexed { index, filter ->
                SegmentedButton(
                    selected = state.filter == filter,
                    onClick = { onFilterChange(filter) },
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = AppFilter.entries.size
                    ),
                    label = {
                        Text(
                            text = filter.label,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                )
            }
        }
    }
}

@Composable
private fun AppsPanel(
    state: HideDbgUiState,
    onToggleUid: (Int) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    val loadError = state.appLoadError
    val visibleApps = state.visibleApps
    val sharedUids = state.sharedUids

    when {
        state.loadingApps && state.apps.isEmpty() -> {
            Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }

        loadError != null -> {
            LoadFailure(
                message = loadError,
                onRetry = onRetry,
                modifier = modifier
            )
        }

        visibleApps.isEmpty() -> {
            EmptyApps(
                hasInstalledApps = state.apps.isNotEmpty(),
                modifier = modifier
            )
        }

        else -> {
            LazyColumn(
                modifier = modifier.fillMaxWidth(),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                items(visibleApps, key = InstalledApp::packageName) { app ->
                    Column {
                        AppRow(
                            app = app,
                            sharedUid = app.uid in sharedUids,
                            checked = app.uid in state.enabledUids,
                            enabled = state.canToggleUid(app.uid),
                            pending = app.uid in state.pendingUids,
                            onToggle = { onToggleUid(app.uid) }
                        )
                        HorizontalDivider(modifier = Modifier.padding(start = 72.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun LoadFailure(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Default.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(32.dp)
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
            Button(onClick = onRetry) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("重試")
            }
        }
    }
}

@Composable
private fun EmptyApps(hasInstalledApps: Boolean, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Apps,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(32.dp)
            )
            Text(
                text = if (hasInstalledApps) "找不到符合條件的 App" else "沒有可顯示的 App",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun AppRow(
    app: InstalledApp,
    sharedUid: Boolean,
    checked: Boolean,
    enabled: Boolean,
    pending: Boolean,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(76.dp).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AppIcon(packageName = app.packageName)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = app.label,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = app.packageName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "UID ${app.uid}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (sharedUid) {
                    Text(
                        text = "共享 UID",
                        modifier = Modifier.padding(start = 8.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        ToggleSlot(
            checked = checked,
            enabled = enabled,
            pending = pending,
            onToggle = onToggle
        )
    }
}

@Composable
private fun AppIcon(packageName: String) {
    val packageManager = LocalContext.current.packageManager
    val drawable by produceState<Drawable?>(initialValue = null, key1 = packageName) {
        value = null
        value = withContext(Dispatchers.IO) {
            try {
                packageManager.getApplicationIcon(packageName)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                null
            }
        }
    }

    Box(modifier = Modifier.size(44.dp), contentAlignment = Alignment.Center) {
        if (drawable == null) {
            Icon(
                imageVector = Icons.Default.Apps,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(28.dp)
            )
        } else {
            AndroidView(
                factory = { imageContext ->
                    ImageView(imageContext).apply {
                        scaleType = ImageView.ScaleType.FIT_CENTER
                    }
                },
                update = { imageView -> imageView.setImageDrawable(drawable) },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
private fun ToggleSlot(
    checked: Boolean,
    enabled: Boolean,
    pending: Boolean,
    onToggle: () -> Unit
) {
    Box(
        modifier = Modifier.size(width = 52.dp, height = 48.dp),
        contentAlignment = Alignment.Center
    ) {
        if (pending) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                strokeWidth = 2.dp
            )
        } else {
            Switch(
                checked = checked,
                onCheckedChange = { onToggle() },
                enabled = enabled
            )
        }
    }
}

private val AppFilter.label: String
    get() = when (this) {
        AppFilter.ALL -> "全部"
        AppFilter.USER -> "使用者"
        AppFilter.SYSTEM -> "系統"
    }
