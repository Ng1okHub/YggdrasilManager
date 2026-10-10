package ng1ok.yggdrasilmanager.ui.hwbp

import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.concurrent.withLock
import ng1ok.yggdrasilmanager.App
import ng1ok.yggdrasilmanager.data.YggdrasilRepository
import ng1ok.yggdrasilmanager.data.hidedbg.InstalledAppsSource
import ng1ok.yggdrasilmanager.data.hwbp.EmptyHwbpConfigStore
import ng1ok.yggdrasilmanager.data.hwbp.HwbpConfigDraft
import ng1ok.yggdrasilmanager.data.hwbp.HwbpConfigStore
import ng1ok.yggdrasilmanager.data.hwbp.HwbpSavedConfig
import ng1ok.yggdrasilmanager.data.model.HwbpBacktrace
import ng1ok.yggdrasilmanager.data.model.HwbpEvent
import ng1ok.yggdrasilmanager.data.model.HwbpEventEnricher
import ng1ok.yggdrasilmanager.data.model.HwbpObserveParams
import ng1ok.yggdrasilmanager.data.model.FileProcMapsSource
import ng1ok.yggdrasilmanager.data.model.ProcMapsSource
import ng1ok.yggdrasilmanager.native.YggdrasilNative

data class HwbpUiState(
    val serverRunning: Boolean = false,
    val connectedClients: Int = 0,
    val httpUrl: String? = null,
    val webSocketUrl: String? = null,
    val observePointCount: Int = 0,
    val savedConfigCount: Int = 0,
    val backtraceMode: String = HwbpBacktrace.MODE_FP,
)

fun HwbpUiState.serverStatusLabel(): String = if (serverRunning) "Running" else "Stopped"

class HwbpViewModel internal constructor(
    private val repo: YggdrasilRepository,
    serverFactory: HwbpServerFactory,
    private val configStore: HwbpConfigStore = EmptyHwbpConfigStore,
    private val installedAppsSource: InstalledAppsSource = InstalledAppsSource {
        Result.success(emptyList())
    },
    private val packagePidResolver: HwbpPackagePidResolver = RootHwbpPackagePidResolver,
    private val mapsSource: ProcMapsSource = FileProcMapsSource,
    private val libraryTargetResolver: HwbpLibraryTargetResolver = nativeLibraryTargetResolver,
    private val libraryNamesResolver: (Int, String) -> List<String> = { pid, mapsText ->
        YggdrasilNative.hwbpListLibraries(pid, mapsText).getOrThrow()
    },
) : ViewModel() {
    private val _observePoints = MutableStateFlow<List<HwbpObserveParams>>(emptyList())
    val observePoints: StateFlow<List<HwbpObserveParams>> = _observePoints.asStateFlow()

    private val _savedConfigs = MutableStateFlow(configStore.current)
    val savedConfigs: StateFlow<List<HwbpSavedConfig>> = _savedConfigs.asStateFlow()

    private val _backtraceMode = MutableStateFlow(HwbpBacktrace.MODE_FP)
    val backtraceMode: StateFlow<String> = _backtraceMode.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val mutationMutex = Mutex()
    private val serverLock = ReentrantLock()
    private val cleared = AtomicBoolean(false)
    private val server: HwbpServer

    val uiState: StateFlow<HwbpUiState>

    init {
        val enrichedEvents = repo.hwbpEvents.map { event ->
            enrichEvent(event)
        }.flowOn(Dispatchers.IO)
        server = serverFactory.create(
            HwbpServerBindings(
                events = enrichedEvents,
                points = { _observePoints.value },
                savedConfigs = { _savedConfigs.value },
                backtraceMode = { _backtraceMode.value },
                installedApps = { installedAppsSource.load() },
                libraryNames = ::loadLibraryNames,
                observe = ::observe,
                unobserve = ::unobserve,
                setBacktraceMode = ::setBacktraceMode,
                saveConfig = ::saveConfig,
                applyConfig = ::applyConfig,
                deleteConfig = ::deleteConfig,
            )
        )
        uiState = combine(
            server.running,
            server.connectedClients,
            server.httpUrl,
            server.webSocketUrl,
            combine(observePoints, backtraceMode, savedConfigs) { points, mode, configs ->
                Triple(points, mode, configs)
            },
        ) { running, clients, httpUrl, webSocketUrl, state ->
            HwbpUiState(
                serverRunning = running,
                connectedClients = clients,
                httpUrl = httpUrl,
                webSocketUrl = webSocketUrl,
                observePointCount = state.first.size,
                savedConfigCount = state.third.size,
                backtraceMode = state.second,
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, HwbpUiState())

        configStore.loadWarning?.let { _error.value = it }

        // HWBPs remain installed after this destination is removed.  Keep the
        // process-wide reader alive as well, otherwise a hit that arrives after
        // navigation away is left unread in the kernel event queue.
        repo.startEventStream()

        viewModelScope.launch(Dispatchers.IO) {
            serverLock.withLock {
                if (!cleared.get()) {
                    startServer()
                }
            }
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

    private fun setBacktraceMode(mode: String): Result<Unit> {
        _backtraceMode.value = HwbpBacktrace.normalizeMode(mode)
        return Result.success(Unit)
    }

    private fun enrichEvent(event: HwbpEvent): HwbpEvent {
        return try {
            HwbpEventEnricher.enrich(
                event = event,
                mode = _backtraceMode.value,
                native = repo.nativeApi,
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            event.copy(
                backtraceMode = HwbpBacktrace.normalizeMode(_backtraceMode.value),
                backtraceNote = "backtrace failed: ${error.message ?: error.javaClass.simpleName}",
            )
        }
    }

    private fun startServer() {
        if (server.running.value) return
        server.start()
            .onFailure(::publishError)
    }

    private fun stopServer() {
        server.stop()
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

    private suspend fun saveConfig(draft: HwbpConfigDraft): Result<Unit> {
        val config = try {
            draft.toSavedConfig(UUID.randomUUID().toString())
        } catch (failure: IllegalArgumentException) {
            return Result.failure<Unit>(failure).also { publishError(failure) }
        }
        return mutationMutex.withLock {
            val updated = _savedConfigs.value.filterNot { it.id == config.id } + config
            persistConfigs(updated)
        }
    }

    private suspend fun deleteConfig(id: String): Result<Unit> = mutationMutex.withLock {
        val existing = _savedConfigs.value
        if (existing.none { it.id == id }) {
            val failure = IllegalArgumentException("Saved configuration was not found")
            return@withLock Result.failure<Unit>(failure).also { publishError(failure) }
        }
        persistConfigs(existing.filterNot { it.id == id })
    }

    private suspend fun applyConfig(id: String): Result<Unit> {
        val config = _savedConfigs.value.firstOrNull { it.id == id }
            ?: return Result.failure<Unit>(IllegalArgumentException("Saved configuration was not found"))
                .also { publishError(it.exceptionOrNull()!!) }
        val params = resolveConfig(config).getOrElse { failure ->
            publishError(failure)
            return Result.failure(failure)
        }
        return observe(params)
    }

    private suspend fun resolveConfig(config: HwbpSavedConfig): Result<HwbpObserveParams> =
        withContext(Dispatchers.IO) {
            try {
                Result.success(config.resolveCurrentParams())
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                Result.failure(failure)
            }
        }

    private suspend fun loadLibraryNames(
        packageName: String?,
        pid: Int?,
    ): Result<List<String>> = withContext(Dispatchers.IO) {
        try {
            val resolvedPid = when {
                packageName != null && pid == null ->
                    packagePidResolver.resolve(normalizePackageName(packageName))
                packageName == null && pid != null && pid > 0 -> pid
                else -> throw IllegalArgumentException("Specify either packageName or pid")
            }
            val mapsText = mapsSource.readMaps(resolvedPid)
                ?: throw IllegalArgumentException(
                    "Failed to read libraries for pid=$resolvedPid " +
                        "(process missing, no root, or SELinux blocked /proc/$resolvedPid/maps)"
                )
            // Keep named mappings even when ELF metadata cannot be read under the app UID.
            val names = libraryNamesResolver(resolvedPid, mapsText) + extractHwbpLibraryNames(mapsText)
            Result.success(names.distinct().sortedWith(String.CASE_INSENSITIVE_ORDER.thenBy { it }))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            Result.failure(failure)
        }
    }

    private suspend fun persistConfigs(configs: List<HwbpSavedConfig>): Result<Unit> {
        val result = try {
            configStore.save(configs)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            Result.failure(failure)
        }
        return result
            .onSuccess { _savedConfigs.value = configs }
            .onFailure(::publishError)
    }

    private fun HwbpSavedConfig.resolveCurrentParams(): HwbpObserveParams {
        val pid = packagePidResolver.resolve(packageName)
        val targetAddress = if (!lib.isNullOrBlank() && offset != null) {
            libraryTargetResolver.resolve(pid, lib, offset, mapsSource)
        } else {
            requireNotNull(targetAddr)
        }
        return HwbpObserveParams(
            pid = pid,
            targetAddr = targetAddress,
            mode = mode,
            len = len,
            flags = flags,
            stackSize = stackSize,
            lib = lib,
            offset = offset,
            packageName = packageName,
        )
    }

    private fun publishError(error: Throwable) {
        _error.value = error.message ?: "HWBP operation failed"
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
                HwbpViewModel(
                    repo = YggdrasilRepository.instance,
                    serverFactory = HwbpServerFactory { bindings ->
                        HwbpWebServer(application, bindings)
                    },
                    configStore = application.hwbpConfigStore,
                    installedAppsSource = application.installedAppsSource,
                )
            }
        }
    }
}
