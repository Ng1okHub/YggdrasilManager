package ng1ok.yggdrasilmanager.ui.hidedbg

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ng1ok.yggdrasilmanager.App
import ng1ok.yggdrasilmanager.data.hidedbg.AppFilter
import ng1ok.yggdrasilmanager.data.hidedbg.HideDbgMutationCoordinator
import ng1ok.yggdrasilmanager.data.hidedbg.HideDbgMutationState
import ng1ok.yggdrasilmanager.data.hidedbg.InstalledApp
import ng1ok.yggdrasilmanager.data.hidedbg.InstalledAppsSource
import ng1ok.yggdrasilmanager.data.hidedbg.filterInstalledApps
import ng1ok.yggdrasilmanager.data.hidedbg.findSharedUids

data class HideDbgUiState(
    val globalEnabled: Boolean = false,
    val apps: List<InstalledApp> = emptyList(),
    val enabledUids: Set<Int> = emptySet(),
    val query: String = "",
    val filter: AppFilter = AppFilter.ALL,
    val loadingApps: Boolean = false,
    val appLoadError: String? = null,
    val globalPending: Boolean = false,
    val pendingUids: Set<Int> = emptySet(),
    val message: String? = null
) {
    val visibleApps: List<InstalledApp>
        get() = filterInstalledApps(apps, query, filter)

    val sharedUids: Set<Int>
        get() = findSharedUids(apps)
}

fun HideDbgUiState.canToggleUid(uid: Int): Boolean =
    !globalEnabled && !globalPending && uid !in pendingUids

fun HideDbgUiState.isAppLoadErrorBlocking(): Boolean =
    appLoadError != null && apps.isEmpty()

fun InstalledApp.hideDbgToggleLabel(): String = "$label ($packageName)"

private data class HideDbgLocalState(
    val apps: List<InstalledApp> = emptyList(),
    val query: String = "",
    val filter: AppFilter = AppFilter.ALL,
    val loadingApps: Boolean = false,
    val appLoadError: String? = null
)

private fun HideDbgLocalState.toUiState(
    mutationState: HideDbgMutationState
): HideDbgUiState = HideDbgUiState(
    globalEnabled = mutationState.globalEnabled,
    apps = apps,
    enabledUids = mutationState.enabledUids,
    query = query,
    filter = filter,
    loadingApps = loadingApps,
    appLoadError = appLoadError,
    globalPending = mutationState.globalPending,
    pendingUids = mutationState.pendingUids,
    message = mutationState.message
)

class HideDbgViewModel(
    private val mutationCoordinator: HideDbgMutationCoordinator,
    private val installedAppsSource: InstalledAppsSource
) : ViewModel() {
    private val _localState = MutableStateFlow(HideDbgLocalState())
    val uiState: StateFlow<HideDbgUiState> = combine(
        mutationCoordinator.state,
        _localState
    ) { mutationState, localState ->
        localState.toUiState(mutationState)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = _localState.value.toUiState(mutationCoordinator.state.value)
    )

    init {
        refreshApps()
    }

    fun refreshApps() {
        if (_localState.value.loadingApps) return

        _localState.update { state ->
            state.copy(loadingApps = true, appLoadError = null)
        }
        viewModelScope.launch {
            try {
                loadInstalledApps().fold(
                    onSuccess = { apps ->
                        _localState.update { state ->
                            state.copy(
                                apps = apps.toList(),
                                loadingApps = false,
                                appLoadError = null
                            )
                        }
                    },
                    onFailure = { failure ->
                        _localState.update { state ->
                            state.copy(
                                loadingApps = false,
                                appLoadError = failure.message ?: APP_LOAD_ERROR
                            )
                        }
                    }
                )
            } finally {
                _localState.update { state -> state.copy(loadingApps = false) }
            }
        }
    }

    fun setQuery(query: String) {
        _localState.update { state -> state.copy(query = query) }
    }

    fun setFilter(filter: AppFilter) {
        _localState.update { state -> state.copy(filter = filter) }
    }

    fun toggleUid(uid: Int) {
        mutationCoordinator.toggleUid(uid)
    }

    fun toggleGlobal() {
        mutationCoordinator.toggleGlobal()
    }

    fun dismissMessage() {
        mutationCoordinator.dismissMessage()
    }

    private suspend fun loadInstalledApps(): Result<List<InstalledApp>> {
        val result = try {
            installedAppsSource.load()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            Result.failure(failure)
        }
        return result.propagateCancellationAndFatal()
    }

    private fun <T> Result<T>.propagateCancellationAndFatal(): Result<T> = apply {
        when (val failure = exceptionOrNull()) {
            is CancellationException -> throw failure
            is Error -> throw failure
        }
    }

    companion object {
        private const val APP_LOAD_ERROR = "無法讀取已安裝 App"

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as App
                HideDbgViewModel(
                    mutationCoordinator = app.hideDbgMutationCoordinator,
                    installedAppsSource = app.installedAppsSource
                )
            }
        }
    }
}
