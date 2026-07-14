package ng1ok.yggdrasilmanager.data

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import ng1ok.yggdrasilmanager.data.model.HwbpEvent
import ng1ok.yggdrasilmanager.data.model.HwbpObserveParams
import ng1ok.yggdrasilmanager.native.HwbpEventCallback
import ng1ok.yggdrasilmanager.native.YggdrasilNative
import ng1ok.yggdrasilmanager.native.YggdrasilNativeApi

class YggdrasilRepository(
    private val native: YggdrasilNativeApi = YggdrasilNative
) {
    private val _hwbpEvents = MutableSharedFlow<HwbpEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val hwbpEvents: SharedFlow<HwbpEvent> = _hwbpEvents.asSharedFlow()

    private val callback = object : HwbpEventCallback {
        override fun onEvent(
            seq: Long, dropped: Long, tid: Int, tgid: Int, comm: String,
            mode: Int, len: Int, flags: Int, watchedAddr: Long,
            pc: Long, sp: Long, regs: LongArray
        ) {
            _hwbpEvents.tryEmit(
                HwbpEvent(seq, dropped, tid, tgid, comm, mode, len, flags, watchedAddr, pc, sp, regs)
            )
        }
    }

    suspend fun checkAvailable(): Boolean = withContext(Dispatchers.IO) {
        native.checkAvailable()
    }

    suspend fun hwbpObserve(params: HwbpObserveParams): Result<Unit> = withContext(Dispatchers.IO) {
        val ret = native.hwbpObserve(params.pid, params.targetAddr, params.mode, params.len, params.flags, params.stackSize)
        if (ret == 0) Result.success(Unit) else Result.failure(RuntimeException("prctl errno ${-ret}"))
    }

    suspend fun hwbpUnobserve(pid: Int, targetAddr: Long): Result<Unit> = withContext(Dispatchers.IO) {
        val ret = native.hwbpUnobserve(pid, targetAddr)
        if (ret == 0) Result.success(Unit) else Result.failure(RuntimeException("prctl errno ${-ret}"))
    }

    suspend fun setHideDbg(enable: Boolean, uid: Int?): Result<Unit> = withContext(Dispatchers.IO) {
        val ret = native.hideDbg(enable, uid != null, uid ?: 0)
        if (ret == 0) Result.success(Unit) else Result.failure(RuntimeException("prctl errno ${-ret}"))
    }

    fun startEventStream() {
        native.hwbpStartListen(callback)
    }

    fun stopEventStream() {
        native.hwbpStopListen()
    }

    companion object {
        val instance: YggdrasilRepository by lazy { YggdrasilRepository() }
    }
}
