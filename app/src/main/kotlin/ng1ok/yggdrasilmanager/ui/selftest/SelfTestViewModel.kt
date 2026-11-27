package ng1ok.yggdrasilmanager.ui.selftest

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ng1ok.yggdrasilmanager.App
import ng1ok.yggdrasilmanager.data.YggdrasilRepository
import ng1ok.yggdrasilmanager.data.selftest.SelfTestFeature
import ng1ok.yggdrasilmanager.data.selftest.SelfTestLogKind
import ng1ok.yggdrasilmanager.data.selftest.SelfTestLogLine
import ng1ok.yggdrasilmanager.data.selftest.SelfTestRootState
import ng1ok.yggdrasilmanager.data.selftest.SelfTestRunOutcome
import ng1ok.yggdrasilmanager.data.selftest.SelfTestRunner
import ng1ok.yggdrasilmanager.data.selftest.SelfTestStatus
import ng1ok.yggdrasilmanager.data.selftest.YggdrasilSelfTestRunner
import ng1ok.yggdrasilmanager.ui.home.YggdrasilStatus
import ng1ok.yggdrasilmanager.ui.home.YggdrasilVersion

data class SelfTestFeatureEntry(
    val feature: SelfTestFeature,
    val selected: Boolean,
    val status: SelfTestStatus,
)

data class SelfTestUiState(
    val moduleStatus: YggdrasilStatus = YggdrasilStatus.CHECKING,
    val moduleVersion: YggdrasilVersion? = null,
    val rootState: SelfTestRootState = SelfTestRootState.UNKNOWN,
    val supported: Boolean = true,
    val selected: Set<String> = SelfTestFeature.entries.map { it.id }.toSet(),
    val statuses: Map<String, SelfTestStatus> = emptyMap(),
    val log: List<SelfTestLogLine> = emptyList(),
    val running: Boolean = false,
    val stopping: Boolean = false,
    val outcome: SelfTestRunOutcome? = null,
    val message: String? = null,
) {
    val features: List<SelfTestFeatureEntry>
        get() = SelfTestFeature.entries.map { feature ->
            SelfTestFeatureEntry(
                feature = feature,
                selected = feature.id in selected,
                status = statuses[feature.id] ?: SelfTestStatus.NOT_RUN,
            )
        }

    val selectedFeatures: List<SelfTestFeature>
        get() = SelfTestFeature.entries.filter { it.id in selected }

    val canRun: Boolean
        get() = !running && supported && rootState == SelfTestRootState.GRANTED &&
            selected.isNotEmpty()
}

class SelfTestViewModel(
    private val runner: SelfTestRunner,
    private val repository: YggdrasilRepository = YggdrasilRepository.instance,
) : ViewModel() {
    private val _uiState = MutableStateFlow(SelfTestUiState())
    val uiState: StateFlow<SelfTestUiState> = _uiState.asStateFlow()

    private var environmentLoaded = false

    fun refreshEnvironment(force: Boolean = false) {
        if (_uiState.value.running) return
        if (environmentLoaded && !force) return
        environmentLoaded = true

        viewModelScope.launch {
            _uiState.update { it.copy(rootState = SelfTestRootState.CHECKING) }

            val supported = runner.supported
            val available = try {
                repository.checkAvailable()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                false
            }
            val version = if (available) {
                try {
                    repository.getVersion()
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    null
                }
            } else {
                null
            }
            val rootState = runner.rootState()

            _uiState.update { state ->
                state.copy(
                    moduleStatus = if (available) {
                        YggdrasilStatus.AVAILABLE
                    } else {
                        YggdrasilStatus.UNAVAILABLE
                    },
                    moduleVersion = version?.let(YggdrasilVersion::fromPacked),
                    rootState = rootState,
                    supported = supported,
                )
            }
        }
    }

    fun toggleFeature(feature: SelfTestFeature) {
        if (_uiState.value.running) return
        _uiState.update { state ->
            val selected = if (feature.id in state.selected) {
                state.selected - feature.id
            } else {
                state.selected + feature.id
            }
            state.copy(selected = selected)
        }
    }

    fun selectAll() {
        if (_uiState.value.running) return
        _uiState.update { it.copy(selected = SelfTestFeature.entries.map { feature -> feature.id }.toSet()) }
    }

    fun clearSelection() {
        if (_uiState.value.running) return
        _uiState.update { it.copy(selected = emptySet()) }
    }

    fun runAll() = runFeatures(SelfTestFeature.entries)

    fun runSelected() = runFeatures(_uiState.value.selectedFeatures)

    fun stop() {
        if (!_uiState.value.running) return
        _uiState.update { it.copy(stopping = true) }
        runner.requestCancel()
    }

    fun clearOutput() {
        if (_uiState.value.running) return
        _uiState.update {
            it.copy(
                log = emptyList(),
                outcome = null,
                statuses = emptyMap(),
            )
        }
    }

    fun dismissMessage() {
        _uiState.update { it.copy(message = null) }
    }

    private fun runFeatures(features: List<SelfTestFeature>) {
        val state = _uiState.value
        if (state.running) return
        when {
            !state.supported -> {
                _uiState.update { it.copy(message = "此裝置架構不受支援（需要 arm64-v8a）。") }
                return
            }
            state.rootState != SelfTestRootState.GRANTED -> {
                _uiState.update { it.copy(message = "需要 Root 權限才能執行模組自測。") }
                return
            }
            features.isEmpty() -> {
                _uiState.update { it.copy(message = "請先選擇至少一個測試項目。") }
                return
            }
        }

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    running = true,
                    stopping = false,
                    log = emptyList(),
                    outcome = null,
                    message = null,
                    statuses = SelfTestFeature.entries.associate { feature ->
                        feature.id to if (feature in features) {
                            SelfTestStatus.QUEUED
                        } else {
                            SelfTestStatus.NOT_RUN
                        }
                    },
                )
            }

            val outcome = try {
                runner.run(
                    features = features,
                    onLine = ::appendLine,
                    onStatuses = { statuses ->
                        _uiState.update { state -> state.copy(statuses = state.statuses + statuses) }
                    },
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                SelfTestRunOutcome(
                    exitCode = -1,
                    checks = 0,
                    failed = 0,
                    statuses = emptyMap(),
                    error = failure.message ?: "執行模組自測失敗",
                )
            }

            _uiState.update { state ->
                val statuses = state.statuses.toMutableMap()
                features.forEach { feature ->
                    statuses[feature.id] = outcome.statuses[feature.id]
                        ?: if (outcome.error != null) SelfTestStatus.ERROR else SelfTestStatus.NOT_RUN
                }
                state.copy(
                    running = false,
                    stopping = false,
                    statuses = statuses,
                    outcome = outcome,
                    message = outcome.error ?: outcome.fatal ?: if (outcome.cancelled) {
                        "已停止模組自測。"
                    } else {
                        null
                    },
                )
            }
        }
    }

    private fun appendLine(text: String, kind: SelfTestLogKind) {
        _uiState.update { state ->
            val line = SelfTestLogLine(text, kind)
            val log = if (state.log.size >= MAX_LOG_LINES) {
                state.log.drop(state.log.size - MAX_LOG_LINES + 1) + line
            } else {
                state.log + line
            }
            state.copy(log = log)
        }
    }

    override fun onCleared() {
        runner.requestCancel()
        super.onCleared()
    }

    companion object {
        private const val MAX_LOG_LINES = 5_000

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as App
                SelfTestViewModel(YggdrasilSelfTestRunner(app))
            }
        }
    }
}
