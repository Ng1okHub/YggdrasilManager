package ng1ok.yggdrasilmanager.ui.hwbp

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ng1ok.yggdrasilmanager.data.YggdrasilRepository
import ng1ok.yggdrasilmanager.data.model.HwbpEvent
import ng1ok.yggdrasilmanager.data.model.HwbpObserveParams

private const val MAX_EVENTS = 200

class HwbpViewModel(
    private val repo: YggdrasilRepository = YggdrasilRepository.instance
) : ViewModel() {

    private val _observePoints = MutableStateFlow<List<HwbpObserveParams>>(emptyList())
    val observePoints: StateFlow<List<HwbpObserveParams>> = _observePoints.asStateFlow()

    private val _events = MutableStateFlow<List<HwbpEvent>>(emptyList())
    val events: StateFlow<List<HwbpEvent>> = _events.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        repo.startEventStream()
        viewModelScope.launch {
            repo.hwbpEvents.collect { event -> appendEvent(event) }
        }
    }

    fun addObserve(params: HwbpObserveParams) {
        viewModelScope.launch {
            repo.hwbpObserve(params)
                .onSuccess { _observePoints.value = _observePoints.value + params }
                .onFailure { e -> _error.value = e.message }
        }
    }

    fun removeObserve(params: HwbpObserveParams) {
        viewModelScope.launch {
            repo.hwbpUnobserve(params.pid, params.targetAddr)
                .onSuccess { _observePoints.value = _observePoints.value - params }
                .onFailure { e -> _error.value = e.message }
        }
    }

    fun clearEvents() { _events.value = emptyList() }
    fun dismissError() { _error.value = null }

    internal fun appendEvent(event: HwbpEvent) {
        val current = _events.value
        _events.value = if (current.size >= MAX_EVENTS) current.drop(1) + event else current + event
    }

    override fun onCleared() {
        super.onCleared()
        repo.stopEventStream()
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer { HwbpViewModel() }
        }
    }
}
