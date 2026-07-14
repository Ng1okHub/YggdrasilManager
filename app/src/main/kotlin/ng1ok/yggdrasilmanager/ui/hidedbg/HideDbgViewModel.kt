package ng1ok.yggdrasilmanager.ui.hidedbg

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

class HideDbgViewModel(
    private val repo: YggdrasilRepository = YggdrasilRepository.instance
) : ViewModel() {

    private val _globalEnabled = MutableStateFlow(false)
    val globalEnabled: StateFlow<Boolean> = _globalEnabled.asStateFlow()

    private val _uidRules = MutableStateFlow<List<Int>>(emptyList())
    val uidRules: StateFlow<List<Int>> = _uidRules.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun toggleGlobal() {
        val next = !_globalEnabled.value
        _globalEnabled.value = next
        viewModelScope.launch {
            repo.setHideDbg(next, null)
                .onFailure { e ->
                    _globalEnabled.value = !next  // revert
                    _error.value = e.message
                }
        }
    }

    fun addUid(uid: Int) {
        if (_uidRules.value.contains(uid)) return
        viewModelScope.launch {
            repo.setHideDbg(true, uid)
                .onSuccess { _uidRules.value = _uidRules.value + uid }
                .onFailure { e -> _error.value = e.message }
        }
    }

    fun removeUid(uid: Int) {
        viewModelScope.launch {
            repo.setHideDbg(false, uid)
                .onSuccess { _uidRules.value = _uidRules.value - uid }
                .onFailure { e -> _error.value = e.message }
        }
    }

    fun dismissError() { _error.value = null }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer { HideDbgViewModel() }
        }
    }
}
