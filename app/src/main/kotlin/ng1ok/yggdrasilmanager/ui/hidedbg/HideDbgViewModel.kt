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
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ng1ok.yggdrasilmanager.App
import ng1ok.yggdrasilmanager.data.YggdrasilRepository
import ng1ok.yggdrasilmanager.data.hidedbg.AppFilter
import ng1ok.yggdrasilmanager.data.hidedbg.HideDbgConfig
import ng1ok.yggdrasilmanager.data.hidedbg.HideDbgConfigStore
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

class HideDbgViewModel(
    private val repo: YggdrasilRepository,
    private val configStore: HideDbgConfigStore,
    private val installedAppsSource: InstalledAppsSource
) : ViewModel() {
    private val mutationMutex = Mutex()
    private val _uiState = MutableStateFlow(
        configStore.current.let { config ->
            HideDbgUiState(
                globalEnabled = config.globalEnabled,
                enabledUids = config.enabledUids.toSet(),
                message = configStore.loadWarning
            )
        }
    )
    val uiState: StateFlow<HideDbgUiState> = _uiState.asStateFlow()

    val globalEnabled: StateFlow<Boolean> = uiState
        .map { state -> state.globalEnabled }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = _uiState.value.globalEnabled
        )

    val uidRules: StateFlow<List<Int>> = uiState
        .map { state -> state.enabledUids.sorted() }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = _uiState.value.enabledUids.sorted()
        )

    val error: StateFlow<String?> = uiState
        .map { state -> state.message }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = _uiState.value.message
        )

    init {
        refreshApps()
    }

    fun refreshApps() {
        if (_uiState.value.loadingApps) return

        _uiState.update { state ->
            state.copy(loadingApps = true, appLoadError = null)
        }
        viewModelScope.launch {
            val result = try {
                installedAppsSource.load()
            } catch (cancellation: CancellationException) {
                _uiState.update { state -> state.copy(loadingApps = false) }
                throw cancellation
            }

            result.fold(
                onSuccess = { apps ->
                    _uiState.update { state ->
                        state.copy(
                            apps = apps.toList(),
                            loadingApps = false,
                            appLoadError = null
                        )
                    }
                },
                onFailure = { failure ->
                    _uiState.update { state ->
                        state.copy(
                            loadingApps = false,
                            appLoadError = failure.message ?: APP_LOAD_ERROR
                        )
                    }
                }
            )
        }
    }

    fun setQuery(query: String) {
        _uiState.update { state -> state.copy(query = query) }
    }

    fun setFilter(filter: AppFilter) {
        _uiState.update { state -> state.copy(filter = filter) }
    }

    fun toggleUid(uid: Int) {
        val state = _uiState.value
        if (state.globalEnabled || state.globalPending || uid in state.pendingUids) return

        val enable = uid !in state.enabledUids
        _uiState.update { current ->
            current.copy(pendingUids = current.pendingUids + uid)
        }

        viewModelScope.launch {
            try {
                mutationMutex.withLock {
                    val kpmResult = invokeKpm(enable, uid)
                    val kpmFailure = kpmResult.exceptionOrNull()
                    if (kpmFailure != null) {
                        _uiState.update { current ->
                            current.copy(message = kpmFailure.message ?: KPM_ERROR)
                        }
                        return@withLock
                    }

                    val changedUids = if (enable) {
                        _uiState.value.enabledUids + uid
                    } else {
                        _uiState.value.enabledUids - uid
                    }
                    _uiState.update { current ->
                        current.copy(enabledUids = changedUids)
                    }

                    val config = HideDbgConfig(
                        globalEnabled = _uiState.value.globalEnabled,
                        enabledUids = changedUids
                    )
                    if (saveConfig(config).isFailure) {
                        _uiState.update { current ->
                            current.copy(message = SAVE_ERROR)
                        }
                    }
                }
            } finally {
                _uiState.update { current ->
                    current.copy(pendingUids = current.pendingUids - uid)
                }
            }
        }
    }

    fun toggleGlobal() {
        val state = _uiState.value
        if (state.globalPending || state.pendingUids.isNotEmpty()) return

        val enable = !state.globalEnabled
        _uiState.update { current -> current.copy(globalPending = true) }

        viewModelScope.launch {
            try {
                mutationMutex.withLock {
                    val kpmResult = invokeKpm(enable, null)
                    val kpmFailure = kpmResult.exceptionOrNull()
                    if (kpmFailure != null) {
                        _uiState.update { current ->
                            current.copy(message = kpmFailure.message ?: KPM_ERROR)
                        }
                        return@withLock
                    }

                    val changedUids = if (enable) {
                        _uiState.value.enabledUids
                    } else {
                        emptySet()
                    }
                    _uiState.update { current ->
                        current.copy(
                            globalEnabled = enable,
                            enabledUids = changedUids
                        )
                    }

                    val config = HideDbgConfig(
                        globalEnabled = enable,
                        enabledUids = changedUids
                    )
                    if (saveConfig(config).isFailure) {
                        _uiState.update { current ->
                            current.copy(message = SAVE_ERROR)
                        }
                    }
                }
            } finally {
                _uiState.update { current -> current.copy(globalPending = false) }
            }
        }
    }

    fun dismissMessage() {
        _uiState.update { state -> state.copy(message = null) }
    }

    fun addUid(uid: Int) {
        if (uid !in _uiState.value.enabledUids) {
            toggleUid(uid)
        }
    }

    fun removeUid(uid: Int) {
        if (uid in _uiState.value.enabledUids) {
            toggleUid(uid)
        }
    }

    fun dismissError() {
        dismissMessage()
    }

    private suspend fun invokeKpm(enable: Boolean, uid: Int?): Result<Unit> = try {
        repo.setHideDbg(enable, uid)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Exception) {
        Result.failure(failure)
    }

    private suspend fun saveConfig(config: HideDbgConfig): Result<Unit> = try {
        configStore.save(config)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Exception) {
        Result.failure(failure)
    }

    companion object {
        private const val APP_LOAD_ERROR = "無法讀取已安裝 App"
        private const val KPM_ERROR = "Hide DBG 操作失敗"
        private const val SAVE_ERROR = "已套用但未保存"

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as App
                HideDbgViewModel(
                    repo = YggdrasilRepository.instance,
                    configStore = app.hideDbgConfigStore,
                    installedAppsSource = app.installedAppsSource
                )
            }
        }
    }
}
