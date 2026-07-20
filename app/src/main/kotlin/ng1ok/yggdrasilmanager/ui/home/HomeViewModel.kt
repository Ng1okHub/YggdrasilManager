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
        if (startupChecksStarted) return
        startupChecksStarted = true

        viewModelScope.launch {
            launch {
                val granted = performStartupCheck {
                    rootStatusSource.isRootGranted()
                }

                _uiState.update { state ->
                    when {
                        granted -> state.copy(rootStatus = RootStatus.GRANTED)
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
                val version = if (available) {
                    performStartupQuery { repo.getVersion() }
                        ?.let(YggdrasilVersion::fromPacked)
                } else {
                    null
                }

                _uiState.update { state ->
                    state.copy(
                        yggdrasilStatus = if (available) {
                            YggdrasilStatus.AVAILABLE
                        } else {
                            YggdrasilStatus.UNAVAILABLE
                        },
                        yggdrasilVersion = version
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

    private suspend fun <T> performStartupQuery(query: suspend () -> T): T? = try {
        query()
    } catch (exception: CancellationException) {
        throw exception
    } catch (_: Exception) {
        null
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
