package ng1ok.yggdrasilmanager.ui.syscall

import android.os.Process
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.concurrent.withLock
import ng1ok.yggdrasilmanager.App
import ng1ok.yggdrasilmanager.data.YggdrasilRepository
import ng1ok.yggdrasilmanager.data.hidedbg.InstalledAppsSource
import ng1ok.yggdrasilmanager.data.model.SyscallListenerState
import ng1ok.yggdrasilmanager.data.model.SyscallEvent
import ng1ok.yggdrasilmanager.data.model.SyscallBacktrace
import ng1ok.yggdrasilmanager.data.model.SyscallBacktraceEnricher
import ng1ok.yggdrasilmanager.data.model.CachedFileProcMapsSource
import ng1ok.yggdrasilmanager.data.model.SyscallObserveRule

data class SyscallUiState(
    val serverRunning: Boolean = false,
    val connectedClients: Int = 0,
    val httpUrl: String? = null,
    val webSocketUrl: String? = null,
    val activeRuleCount: Int = 0,
)

class SyscallViewModel internal constructor(
    private val repo: YggdrasilRepository,
    private val installedAppsSource: InstalledAppsSource,
    serverFactory: SyscallServerFactory,
) : ViewModel() {
    val rules: StateFlow<List<SyscallObserveRule>> = repo.syscallRules

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val serverLock = ReentrantLock()
    private val cleared = AtomicBoolean(false)
    private val _backtraceMode = MutableStateFlow(SyscallBacktrace.MODE_FP)
    private val server: SyscallServer

    val uiState: StateFlow<SyscallUiState>

    init {
        // Backtrace capture is independent from the optional automatic capture
        // schemas, so the selector must expose every Arm64 syscall number.
        val supportedSyscalls = (0 until 460).toList()
        server = serverFactory.create(
            SyscallServerBindings(
                events = repo.syscallEvents.map(::enrichEvent).flowOn(Dispatchers.IO),
                rules = { rules.value },
                listenerStatus = repo.syscallListenerStatus,
                supportedSyscalls = supportedSyscalls,
                installedApps = {
                    installedAppsSource.load().map { apps ->
                        apps.filterNot { app -> app.uid == Process.myUid() }
                    }
                },
                observe = ::observe,
                unobserve = ::unobserve,
                restartListener = ::restartListener,
                backtraceMode = { _backtraceMode.value },
                setBacktraceMode = ::setBacktraceMode,
            ),
        )
        uiState = combine(
            server.running,
            server.connectedClients,
            server.httpUrl,
            server.webSocketUrl,
            rules,
        ) { running, clients, httpUrl, webSocketUrl, rules ->
            SyscallUiState(
                serverRunning = running,
                connectedClients = clients,
                httpUrl = httpUrl,
                webSocketUrl = webSocketUrl,
                activeRuleCount = rules.size,
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, SyscallUiState())

        // Rules remain active in the KPM after this destination is removed.
        // Keep the sole root-side reader draining its destructive FIFO; the
        // repository bounds events while no browser server is attached.
        repo.startSyscallEventStream()

        viewModelScope.launch {
            repo.syscallListenerError.collect { error ->
                if (error != null) publishError(IllegalStateException(error))
            }
        }

        viewModelScope.launch(Dispatchers.IO) {
            serverLock.withLock {
                if (!cleared.get()) startServer()
            }
        }
    }

    fun toggleServer() {
        viewModelScope.launch(Dispatchers.IO) {
            serverLock.withLock {
                if (cleared.get()) return@withLock
                if (server.running.value) {
                    stopServer()
                } else {
                    startServer()
                }
            }
        }
    }

    fun dismissError() {
        _error.value = null
    }

    private fun startServer() {
        if (server.running.value) return
        if (repo.syscallListenerStatus.value.state !in setOf(
                SyscallListenerState.STARTING,
                SyscallListenerState.RUNNING,
            )
        ) {
            repo.startSyscallEventStream()
        }
        server.start()
            .onFailure(::publishError)
    }

    private fun stopServer() {
        server.stop()
    }

    private suspend fun observe(rule: SyscallObserveRule): Result<Unit> =
        repo.setSyscallRule(rule)
            .onFailure(::publishError)

    private suspend fun unobserve(rule: SyscallObserveRule): Result<Unit> =
        repo.deleteSyscallRule(rule)
            .onFailure(::publishError)

    private fun restartListener() {
        repo.restartSyscallEventStream()
    }

    private fun setBacktraceMode(mode: String): Result<Unit> {
        _backtraceMode.value = SyscallBacktrace.normalizeMode(mode)
        return Result.success(Unit)
    }

    private fun enrichEvent(event: SyscallEvent): SyscallEvent {
        val snapshot = event.backtrace ?: return event
        return runCatching {
            event.copy(
                backtrace = SyscallBacktraceEnricher.enrich(
                    snapshot = snapshot,
                    mode = _backtraceMode.value,
                    native = repo.nativeApi,
                    mapsSource = CachedFileProcMapsSource,
                ),
            )
        }.getOrElse { error ->
            event.copy(
                backtrace = snapshot.copy(
                    mode = SyscallBacktrace.normalizeMode(_backtraceMode.value),
                    note = "backtrace failed: ${error.message ?: error.javaClass.simpleName}",
                ),
            )
        }
    }

    private fun publishError(error: Throwable) {
        _error.value = error.message ?: "Syscall操作失敗"
    }

    override fun onCleared() {
        cleared.set(true)
        serverLock.withLock {
            stopServer()
        }
        super.onCleared()
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application = this[APPLICATION_KEY] as App
                SyscallViewModel(
                    repo = YggdrasilRepository.instance,
                    installedAppsSource = application.installedAppsSource,
                    serverFactory = SyscallServerFactory { bindings ->
                        SyscallWebServer(application, bindings)
                    },
                )
            }
        }
    }
}
