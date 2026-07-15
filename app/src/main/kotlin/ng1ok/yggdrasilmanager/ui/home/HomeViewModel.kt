package ng1ok.yggdrasilmanager.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ng1ok.yggdrasilmanager.data.YggdrasilRepository

class HomeViewModel(
    private val repo: YggdrasilRepository = YggdrasilRepository.instance,
    private val rootStatusSource: RootStatusSource = LibsuRootStatusSource,
    deviceInfoProvider: DeviceInfoProvider = AndroidDeviceInfoProvider
) : ViewModel() {
    private val _uiState = MutableStateFlow(
        HomeUiState(deviceInfo = deviceInfoProvider.getDeviceInfo())
    )
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private var startupChecksStarted = false

    fun startStartupChecks() {
        startStartupChecks(precheckedRootGranted = null)
    }

    private fun startStartupChecks(precheckedRootGranted: Boolean?) {
        if (startupChecksStarted) return
        startupChecksStarted = true

        viewModelScope.launch {
            launch {
                val rootGranted = precheckedRootGranted ?: performStartupCheck {
                    rootStatusSource.isRootGranted()
                }

                _uiState.update { state ->
                    when {
                        rootGranted -> state.copy(rootStatus = RootStatus.GRANTED)
                        state.hasShownRootDeniedPrompt -> state.copy(rootStatus = RootStatus.DENIED)
                        else -> state.copy(
                            rootStatus = RootStatus.DENIED,
                            showRootDeniedDialog = true,
                            hasShownRootDeniedPrompt = true
                        )
                    }
                }
            }

            launch {
                val available = performStartupCheck {
                    repo.checkAvailable()
                }

                _uiState.update { state ->
                    state.copy(
                        yggdrasilStatus = if (available) {
                            YggdrasilStatus.AVAILABLE
                        } else {
                            YggdrasilStatus.UNAVAILABLE
                        }
                    )
                }
            }
        }
    }

    private suspend fun performStartupCheck(check: suspend () -> Boolean): Boolean = try {
        check()
    } catch (exception: CancellationException) {
        throw exception
    } catch (_: Exception) {
        false
    }

    fun refresh(rootGranted: Boolean) {
        startStartupChecks(precheckedRootGranted = rootGranted)
    }

    fun dismissRootDeniedDialog() {
        _uiState.update { state -> state.copy(showRootDeniedDialog = false) }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer { HomeViewModel() }
        }
    }
}
