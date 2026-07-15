package ng1ok.yggdrasilmanager.data.hidedbg

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ng1ok.yggdrasilmanager.data.YggdrasilRepository

data class HideDbgMutationState(
    val globalEnabled: Boolean = false,
    val enabledUids: Set<Int> = emptySet(),
    val globalPending: Boolean = false,
    val pendingUids: Set<Int> = emptySet(),
    val message: String? = null
)

class HideDbgMutationCoordinator(
    private val repo: YggdrasilRepository,
    private val configStore: HideDbgConfigStore,
    private val applicationScope: CoroutineScope
) {
    private val mutationMutex = Mutex()
    private val _state = MutableStateFlow(
        configStore.current.let { config ->
            HideDbgMutationState(
                globalEnabled = config.globalEnabled,
                enabledUids = config.enabledUids.toSet(),
                message = configStore.loadWarning
            )
        }
    )
    val state: StateFlow<HideDbgMutationState> = _state.asStateFlow()

    fun toggleUid(uid: Int) {
        val enable = reserveUid(uid) ?: return

        applicationScope.launch {
            try {
                mutationMutex.withLock {
                    mutateUid(uid, enable)
                }
            } finally {
                _state.update { current ->
                    current.copy(pendingUids = current.pendingUids - uid)
                }
            }
        }
    }

    fun toggleGlobal() {
        val enable = reserveGlobal() ?: return

        applicationScope.launch {
            try {
                mutationMutex.withLock {
                    mutateGlobal(enable)
                }
            } finally {
                _state.update { current -> current.copy(globalPending = false) }
            }
        }
    }

    fun dismissMessage() {
        _state.update { current -> current.copy(message = null) }
    }

    private fun reserveUid(uid: Int): Boolean? {
        while (true) {
            val current = _state.value
            if (current.globalEnabled || current.globalPending || uid in current.pendingUids) {
                return null
            }

            val reserved = current.copy(pendingUids = current.pendingUids + uid)
            if (_state.compareAndSet(current, reserved)) {
                return uid !in current.enabledUids
            }
        }
    }

    private fun reserveGlobal(): Boolean? {
        while (true) {
            val current = _state.value
            if (current.globalPending || current.pendingUids.isNotEmpty()) {
                return null
            }

            val reserved = current.copy(globalPending = true)
            if (_state.compareAndSet(current, reserved)) {
                return !current.globalEnabled
            }
        }
    }

    private suspend fun mutateUid(uid: Int, enable: Boolean) {
        val kpmResult = invokeKpm(enable, uid)
        val kpmFailure = kpmResult.exceptionOrNull()
        if (kpmFailure != null) {
            _state.update { current ->
                current.copy(message = kpmFailure.message ?: KPM_ERROR)
            }
            return
        }

        val changedUids = if (enable) {
            _state.value.enabledUids + uid
        } else {
            _state.value.enabledUids - uid
        }
        _state.update { current -> current.copy(enabledUids = changedUids) }

        val config = HideDbgConfig(
            globalEnabled = _state.value.globalEnabled,
            enabledUids = changedUids
        )
        if (saveConfig(config).isFailure) {
            _state.update { current -> current.copy(message = SAVE_ERROR) }
        }
    }

    private suspend fun mutateGlobal(enable: Boolean) {
        val kpmResult = invokeKpm(enable, null)
        val kpmFailure = kpmResult.exceptionOrNull()
        if (kpmFailure != null) {
            _state.update { current ->
                current.copy(message = kpmFailure.message ?: KPM_ERROR)
            }
            return
        }

        val changedUids = if (enable) _state.value.enabledUids else emptySet()
        _state.update { current ->
            current.copy(globalEnabled = enable, enabledUids = changedUids)
        }

        val config = HideDbgConfig(
            globalEnabled = enable,
            enabledUids = changedUids
        )
        if (saveConfig(config).isFailure) {
            _state.update { current -> current.copy(message = SAVE_ERROR) }
        }
    }

    private suspend fun invokeKpm(enable: Boolean, uid: Int?): Result<Unit> {
        val result = try {
            repo.setHideDbg(enable, uid)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            Result.failure(failure)
        }
        return result.propagateCancellationAndFatal()
    }

    private suspend fun saveConfig(config: HideDbgConfig): Result<Unit> {
        val result = try {
            configStore.save(config)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            Result.failure(failure)
        }
        return result.propagateCancellationAndFatal()
    }

    private fun <T> Result<T>.propagateCancellationAndFatal(): Result<T> = apply {
        when (val failure = exceptionOrNull()) {
            is CancellationException -> throw failure
            is Error -> throw failure
        }
    }

    private companion object {
        const val KPM_ERROR = "Hide DBG 操作失敗"
        const val SAVE_ERROR = "已套用但未保存"
    }
}
