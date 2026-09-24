package ng1ok.yggdrasilmanager.ui.maps

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ng1ok.yggdrasilmanager.data.YggdrasilRepository
import ng1ok.yggdrasilmanager.data.model.MapsPolicy
import ng1ok.yggdrasilmanager.data.model.MapsRule

/** Immutable state for the maps policy editor. */
data class MapsUiState(
    val globalScope: Boolean = true,
    val pidText: String = "",
    val action: Int = MapsPolicy.ACTION_HIDE,
    val matchKind: Int = MapsPolicy.MATCH_PATH_SUBSTR,
    val pattern: String = "",
    val startText: String = "",
    val endText: String = "",
    val matchPerms: String = "",
    val setPerms: String = "---",
    val rules: List<MapsRule> = emptyList(),
    val totalRules: Int = 0,
    val loading: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
)

/** Coordinates maps policy editing and keeps the form separate from Compose. */
class MapsViewModel(
    private val repo: YggdrasilRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(MapsUiState())
    val uiState: StateFlow<MapsUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun setGlobalScope(global: Boolean) {
        _uiState.update { it.copy(globalScope = global, error = null) }
        refresh()
    }

    fun setPid(value: String) = _uiState.update { it.copy(pidText = value, error = null) }
    fun setAction(value: Int) = _uiState.update { it.copy(action = value, error = null) }
    fun setMatchKind(value: Int) = _uiState.update { it.copy(matchKind = value, error = null) }
    fun setPattern(value: String) = _uiState.update { it.copy(pattern = value, error = null) }
    fun setStart(value: String) = _uiState.update { it.copy(startText = value, error = null) }
    fun setEnd(value: String) = _uiState.update { it.copy(endText = value, error = null) }
    fun setMatchPerms(value: String) = _uiState.update { it.copy(matchPerms = value, error = null) }
    fun setSetPerms(value: String) = _uiState.update { it.copy(setPerms = value, error = null) }
    fun dismissError() = _uiState.update { it.copy(error = null) }

    fun refresh() {
        if (_uiState.value.loading || _uiState.value.busy) return
        val targetPid = try {
            selectedPid(_uiState.value)
        } catch (failure: IllegalArgumentException) {
            _uiState.update { it.copy(error = failure.message ?: INVALID_PID) }
            return
        }
        _uiState.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                repo.getMaps(targetPid).fold(
                    onSuccess = { scope ->
                        _uiState.update {
                            it.copy(
                                loading = false,
                                rules = scope.rules,
                                totalRules = scope.totalRules,
                            )
                        }
                    },
                    onFailure = { failure ->
                        _uiState.update {
                            it.copy(loading = false, error = failure.message ?: QUERY_ERROR)
                        }
                    },
                )
            } finally {
                _uiState.update { it.copy(loading = false) }
            }
        }
    }

    fun addRule() {
        val state = _uiState.value
        val rule = try {
            buildRule(state)
        } catch (failure: Exception) {
            _uiState.update { it.copy(error = failure.message ?: INVALID_RULE) }
            return
        }
        val targetPid = try { selectedPid(state) } catch (failure: IllegalArgumentException) {
            _uiState.update { it.copy(error = failure.message ?: INVALID_PID) }
            return
        }
        mutate(targetPid) { repo.addMapsRule(targetPid, rule) }
    }

    fun removeRule(rule: MapsRule) {
        val state = _uiState.value
        val targetPid = try { selectedPid(state) } catch (failure: IllegalArgumentException) {
            _uiState.update { it.copy(error = failure.message ?: INVALID_PID) }
            return
        }
        mutate(targetPid) { repo.removeMapsRule(targetPid, rule) }
    }

    fun clearCurrentScope() {
        val state = _uiState.value
        val targetPid = try { selectedPid(state) } catch (failure: IllegalArgumentException) {
            _uiState.update { it.copy(error = failure.message ?: INVALID_PID) }
            return
        }
        mutate(targetPid) { repo.clearMaps(targetPid) }
    }

    fun clearAllScopes() {
        mutate(null) { repo.clearMaps(pid = null, allScopes = true) }
    }

    private fun mutate(targetPid: Int?, operation: suspend () -> Result<Unit>) {
        if (_uiState.value.busy) return
        _uiState.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                operation().fold(
                    onSuccess = { refreshAfterMutation(targetPid) },
                    onFailure = { failure ->
                        _uiState.update { it.copy(error = failure.message ?: OPERATION_ERROR) }
                    },
                )
            } finally {
                _uiState.update { it.copy(busy = false) }
            }
        }
    }

    private suspend fun refreshAfterMutation(targetPid: Int?) {
        repo.getMaps(targetPid).onSuccess { scope ->
            _uiState.update { it.copy(rules = scope.rules, totalRules = scope.totalRules) }
        }
    }

    private fun buildRule(state: MapsUiState): MapsRule {
        val rangeStart = parseAddress(state.startText)
        val rangeEnd = parseAddress(state.endText)
        return MapsRule(
            action = state.action,
            matchKind = state.matchKind,
            matchPerms = state.matchPerms.trim().ifEmpty { null },
            setPerms = if (state.action == MapsPolicy.ACTION_REWRITE_PERMS) {
                state.setPerms.trim().ifEmpty { null }
            } else {
                null
            },
            addrStart = rangeStart,
            addrEnd = rangeEnd,
            pattern = state.pattern,
        )
    }

    private fun parseAddress(text: String): Long {
        if (text.isBlank()) return 0L
        val normalized = text.trim().removePrefix("0x").removePrefix("0X")
        return normalized.toULongOrNull(16)?.toLong()
            ?: throw IllegalArgumentException("位址必須是十六進位")
    }

    private fun selectedPid(state: MapsUiState): Int? {
        if (state.globalScope) return null
        return state.pidText.toIntOrNull()?.takeIf { it > 0 }
            ?: throw IllegalArgumentException("目標 PID 必須是正整數")
    }

    companion object {
        const val QUERY_ERROR = "無法查詢 Maps 規則"
        const val INVALID_RULE = "Maps 規則格式無效"
        const val OPERATION_ERROR = "Maps 操作失敗"
        const val INVALID_PID = "目標 PID 必須是正整數"

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer { MapsViewModel(YggdrasilRepository.instance) }
        }
    }
}
