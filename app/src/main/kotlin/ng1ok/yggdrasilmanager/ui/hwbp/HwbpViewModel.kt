package ng1ok.yggdrasilmanager.ui.hwbp

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ng1ok.yggdrasilmanager.App
import ng1ok.yggdrasilmanager.data.YggdrasilRepository
import ng1ok.yggdrasilmanager.data.model.HwbpObserveParams

data class HwbpUiState(
    val serverRunning: Boolean = false,
    val connectedClients: Int = 0,
    val httpUrl: String? = null,
    val webSocketUrl: String? = null,
    val observePointCount: Int = 0,
)

fun HwbpUiState.serverStatusLabel(): String = if (serverRunning) "Running" else "Stopped"

class HwbpViewModel internal constructor(
    private val repo: YggdrasilRepository,
    serverFactory: HwbpServerFactory,
) : ViewModel() {
    private val _observePoints = MutableStateFlow<List<HwbpObserveParams>>(emptyList())
    val observePoints: StateFlow<List<HwbpObserveParams>> = _observePoints.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val mutationMutex = Mutex()
    private val server: HwbpServer

    val uiState: StateFlow<HwbpUiState>

    init {
        repo.startEventStream()
        server = serverFactory.create(
            HwbpServerBindings(
                events = repo.hwbpEvents,
                points = { _observePoints.value },
                observe = ::observe,
                unobserve = ::unobserve,
            )
        )
        uiState = combine(
            server.running,
            server.connectedClients,
            server.httpUrl,
            server.webSocketUrl,
            observePoints,
        ) { running, clients, httpUrl, webSocketUrl, points ->
            HwbpUiState(
                serverRunning = running,
                connectedClients = clients,
                httpUrl = httpUrl,
                webSocketUrl = webSocketUrl,
                observePointCount = points.size,
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, HwbpUiState())

        viewModelScope.launch(Dispatchers.IO) {
            server.start().onFailure(::publishError)
        }
    }

    fun addObserve(params: HwbpObserveParams) {
        viewModelScope.launch {
            if (observe(params).isSuccess) {
                server.broadcastState()
            }
        }
    }

    fun removeObserve(params: HwbpObserveParams) {
        viewModelScope.launch {
            if (unobserve(params.pid, params.targetAddr).isSuccess) {
                server.broadcastState()
            }
        }
    }

    fun toggleServer() {
        viewModelScope.launch(Dispatchers.IO) {
            if (server.running.value) {
                server.stop()
            } else {
                server.start().onFailure(::publishError)
            }
        }
    }

    fun dismissError() {
        _error.value = null
    }

    private suspend fun observe(params: HwbpObserveParams): Result<Unit> =
        mutationMutex.withLock {
            repo.hwbpObserve(params)
                .onSuccess {
                    _observePoints.value = _observePoints.value
                        .filterNot { it.pid == params.pid && it.targetAddr == params.targetAddr } +
                        params
                }
                .onFailure(::publishError)
        }

    private suspend fun unobserve(pid: Int, targetAddr: Long): Result<Unit> =
        mutationMutex.withLock {
            repo.hwbpUnobserve(pid, targetAddr)
                .onSuccess {
                    _observePoints.value = _observePoints.value.filterNot {
                        it.pid == pid && it.targetAddr == targetAddr
                    }
                }
                .onFailure(::publishError)
        }

    private fun publishError(error: Throwable) {
        _error.value = error.message
    }

    override fun onCleared() {
        server.stop()
        repo.stopEventStream()
        super.onCleared()
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application = this[APPLICATION_KEY] as App
                HwbpViewModel(
                    repo = YggdrasilRepository.instance,
                    serverFactory = HwbpServerFactory { bindings ->
                        HwbpWebServer(application.applicationContext, bindings)
                    },
                )
            }
        }
    }
}
