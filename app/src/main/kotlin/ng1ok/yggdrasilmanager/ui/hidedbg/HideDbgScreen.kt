package ng1ok.yggdrasilmanager.ui.hidedbg

import android.graphics.drawable.Drawable
import android.widget.ImageView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
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
import androidx.compose.material3.Surface
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
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
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            contentAlignment = Alignment.TopCenter
        ) {
            val useCompactLandscape = maxHeight < 480.dp && maxWidth >= 480.dp
            val useSideBySideHeader = maxWidth >= 700.dp

            Column(
                modifier = Modifier
                    .widthIn(max = 840.dp)
                    .fillMaxSize()
                    .padding(vertical = if (useCompactLandscape) 8.dp else 16.dp)
            ) {
                if (useCompactLandscape) {
                    if (useSideBySideHeader) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            GlobalSetting(
                                state = state,
                                onToggle = vm::toggleGlobal,
                                compact = true,
                                modifier = Modifier.weight(5f)
                            )
                            Column(modifier = Modifier.weight(7f)) {
                                AppsSectionHeader(
                                    state = state,
                                    onRefresh = vm::refreshApps
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                AppControls(
                                    state = state,
                                    onQueryChange = vm::setQuery,
                                    onFilterChange = vm::setFilter,
                                    onRefresh = vm::refreshApps,
                                    compact = true
                                )
                            }
                        }
                    } else {
                        GlobalSetting(
                            state = state,
                            onToggle = vm::toggleGlobal,
                            compact = true
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        AppsSectionHeader(
                            state = state,
                            onRefresh = vm::refreshApps
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        AppControls(
                            state = state,
                            onQueryChange = vm::setQuery,
                            onFilterChange = vm::setFilter,
                            onRefresh = vm::refreshApps,
                            compact = true
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                } else {
                    GlobalSetting(
                        state = state,
                        onToggle = vm::toggleGlobal
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    AppsSectionHeader(state = state)
                    Spacer(modifier = Modifier.height(12.dp))
                    AppControls(
                        state = state,
                        onQueryChange = vm::setQuery,
                        onFilterChange = vm::setFilter,
                        onRefresh = vm::refreshApps
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }
                AppsPanel(
                    state = state,
                    onToggleUid = vm::toggleUid,
                    onRetry = vm::refreshApps,
                    compact = useCompactLandscape,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .widthIn(max = 840.dp)
                .fillMaxWidth()
                .padding(16.dp)
        )
    }
}

@Composable
private fun GlobalSetting(
    state: HideDbgUiState,
    onToggle: () -> Unit,
    compact: Boolean = false,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier.padding(if (compact) 12.dp else 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                horizontalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(if (compact) 40.dp else 48.dp)
                        .background(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = MaterialTheme.shapes.medium
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Security,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "全局 Hide DBG",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = if (compact && state.globalEnabled) {
                            "已啟用，單項設定已被覆蓋"
                        } else {
                            "為所有 App 隱藏調試器狀態"
                        },
                        style = if (compact) {
                            MaterialTheme.typography.bodySmall
                        } else {
                            MaterialTheme.typography.bodyMedium
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                ToggleSlot(
                    accessibilityLabel = "全局 Hide DBG",
                    checked = state.globalEnabled,
                    enabled = state.pendingUids.isEmpty(),
                    pending = state.globalPending,
                    onToggle = onToggle
                )
            }

            if (state.globalEnabled && !compact) {
                Text(
                    text = "全局模式已啟用，單項設定已被覆蓋",
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = MaterialTheme.shapes.small
                        )
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    }
}

@Composable
private fun AppsSectionHeader(
    state: HideDbgUiState,
    onRefresh: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "App 設定",
            style = MaterialTheme.typography.titleMedium
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (state.loadingApps && state.apps.isEmpty()) {
                    "正在載入"
                } else {
                    "${state.visibleApps.size} 個結果"
                },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            onRefresh?.let {
                RefreshAppsButton(
                    loading = state.loadingApps,
                    onRefresh = it
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppControls(
    state: HideDbgUiState,
    onQueryChange: (String) -> Unit,
    onFilterChange: (AppFilter) -> Unit,
    onRefresh: () -> Unit,
    compact: Boolean = false
) {
    if (compact) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SearchControls(
                state = state,
                onQueryChange = onQueryChange,
                onRefresh = onRefresh,
                compact = true,
                showRefresh = false,
                modifier = Modifier.weight(5f)
            )
            FilterControls(
                selectedFilter = state.filter,
                onFilterChange = onFilterChange,
                compact = true,
                modifier = Modifier.weight(6f)
            )
        }
    } else {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SearchControls(
                state = state,
                onQueryChange = onQueryChange,
                onRefresh = onRefresh
            )
            FilterControls(
                selectedFilter = state.filter,
                onFilterChange = onFilterChange
            )
        }
    }
}

@Composable
private fun SearchControls(
    state: HideDbgUiState,
    onQueryChange: (String) -> Unit,
    onRefresh: () -> Unit,
    compact: Boolean = false,
    showRefresh: Boolean = true,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = state.query,
            onValueChange = onQueryChange,
            modifier = Modifier.weight(1f),
            label = {
                Text(
                    text = if (compact) "搜尋" else "搜尋 App",
                    maxLines = 1
                )
            },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = null
                )
            },
            trailingIcon = if (state.query.isNotEmpty()) {
                {
                    IconButton(
                        onClick = { onQueryChange("") },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "清除搜尋"
                        )
                    }
                }
            } else {
                null
            },
            singleLine = true
        )
        if (showRefresh) {
            RefreshAppsButton(
                loading = state.loadingApps,
                onRefresh = onRefresh
            )
        }
    }
}

@Composable
private fun RefreshAppsButton(loading: Boolean, onRefresh: () -> Unit) {
    IconButton(
        onClick = onRefresh,
        enabled = !loading,
        modifier = Modifier.size(48.dp)
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(22.dp).semantics {
                    contentDescription = "正在重新整理 App"
                },
                strokeWidth = 2.dp
            )
        } else {
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = "重新整理 App"
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterControls(
    selectedFilter: AppFilter,
    onFilterChange: (AppFilter) -> Unit,
    compact: Boolean = false,
    modifier: Modifier = Modifier
) {
    SingleChoiceSegmentedButtonRow(modifier = modifier.fillMaxWidth()) {
        AppFilter.entries.forEachIndexed { index, filter ->
            SegmentedButton(
                selected = selectedFilter == filter,
                onClick = { onFilterChange(filter) },
                modifier = Modifier.heightIn(min = 48.dp),
                shape = SegmentedButtonDefaults.itemShape(
                    index = index,
                    count = AppFilter.entries.size
                ),
                label = {
                    Text(
                        text = filter.label,
                        style = if (compact) {
                            MaterialTheme.typography.labelMedium
                        } else {
                            MaterialTheme.typography.labelLarge
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            )
        }
    }
}

@Composable
private fun AppsPanel(
    state: HideDbgUiState,
    onToggleUid: (Int) -> Unit,
    onRetry: () -> Unit,
    compact: Boolean = false,
    modifier: Modifier = Modifier
) {
    val loadError = state.appLoadError
    val visibleApps = state.visibleApps
    val sharedUids = state.sharedUids

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        when {
            state.loadingApps && state.apps.isEmpty() -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }

            state.isAppLoadErrorBlocking() -> {
                LoadFailure(
                    message = requireNotNull(loadError),
                    onRetry = onRetry,
                    compact = compact,
                    modifier = Modifier.fillMaxSize()
                )
            }

            else -> {
                Column(modifier = Modifier.fillMaxSize()) {
                    loadError?.let { message ->
                        InlineLoadFailure(message = message, onRetry = onRetry)
                    }

                    if (visibleApps.isEmpty()) {
                        EmptyApps(
                            hasInstalledApps = state.apps.isNotEmpty(),
                            modifier = Modifier.weight(1f)
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            contentPadding = PaddingValues(vertical = 4.dp)
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
        }
    }
}

@Composable
private fun InlineLoadFailure(message: String, onRetry: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.ErrorOutline,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.size(20.dp)
        )
        Text(
            text = message,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        IconButton(
            onClick = onRetry,
            modifier = Modifier.size(48.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = "重試載入 App",
                tint = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}

@Composable
private fun LoadFailure(
    message: String,
    onRetry: () -> Unit,
    compact: Boolean = false,
    modifier: Modifier = Modifier
) {
    if (compact) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(24.dp)
            )
            Text(
                text = message,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Button(
                onClick = onRetry,
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("重試")
            }
        }
        return
    }

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
            Button(
                onClick = onRetry,
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
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
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 76.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
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
            accessibilityLabel = app.hideDbgToggleLabel(),
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
    accessibilityLabel: String,
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
                modifier = Modifier.size(24.dp).semantics {
                    contentDescription = accessibilityLabel
                    stateDescription = "處理中"
                },
                strokeWidth = 2.dp
            )
        } else {
            Switch(
                checked = checked,
                onCheckedChange = { onToggle() },
                enabled = enabled,
                modifier = Modifier.semantics {
                    contentDescription = accessibilityLabel
                }
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
