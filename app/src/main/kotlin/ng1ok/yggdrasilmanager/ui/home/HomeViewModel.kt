package ng1ok.yggdrasilmanager.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ng1ok.yggdrasilmanager.data.YggdrasilRepository

data class HomeUiState(
    val rootGranted: Boolean = false,
    val moduleAvailable: Boolean = false,
    val loading: Boolean = false
)

class HomeViewModel(
    private val repo: YggdrasilRepository = YggdrasilRepository.instance
) : ViewModel() {
    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    fun refresh(rootGranted: Boolean) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(loading = true)
            val available = if (rootGranted) repo.checkAvailable() else false
            _uiState.value = HomeUiState(rootGranted = rootGranted, moduleAvailable = available)
        }
    }
}
