package ng1ok.yggdrasilmanager.data

import android.content.Context
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import ng1ok.yggdrasilmanager.data.model.HwbpEvent
import ng1ok.yggdrasilmanager.data.model.HwbpObserveParams
import ng1ok.yggdrasilmanager.native.HwbpEventCallback
import ng1ok.yggdrasilmanager.native.YggdrasilNative
import ng1ok.yggdrasilmanager.native.YggdrasilNativeApi
import ng1ok.yggdrasilmanager.root.RootHwbpOperations

class YggdrasilRepository(
    private val native: YggdrasilNativeApi = YggdrasilNative
) {
    private val localHwbpOperations = LocalHwbpOperations(native)

    @Volatile
    private var hwbpOperations: HwbpOperations = localHwbpOperations

    /*
     * Native callbacks must return quickly so the next kernel event can be read.
     * A SharedFlow with replay=0 drops values before its collector starts and its
     * DROP_OLDEST policy silently loses bursts while backtraces are being built.
     * The HWBP server has one event consumer, so a queue preserves ordering until
     * that consumer is ready without blocking the native listener thread. Channel
     * receivers are fan-out consumers, therefore collection is serialized so a
     * replacement server cannot split events with the server it is replacing.
     */
    private val hwbpEventQueue = Channel<HwbpEvent>(Channel.UNLIMITED)
    private val hwbpEventConsumerMutex = Mutex()
    val hwbpEvents: Flow<HwbpEvent> = flow {
        hwbpEventConsumerMutex.withLock {
            for (event in hwbpEventQueue) {
                emit(event)
            }
        }
    }

    val nativeApi: YggdrasilNativeApi get() = native

    /**
     * Makes every HWBP control/read call execute in one libsu root process.
     * This must be configured before the first HWBP listener is started.
     */
    internal fun useRootHwbp(context: Context) {
        synchronized(this) {
            if (hwbpOperations === localHwbpOperations) {
                hwbpOperations = RootHwbpOperations(context)
            }
        }
    }

    private val callback = object : HwbpEventCallback {
        override fun onEvent(
            seq: Long,
            dropped: Long,
            tid: Int,
            tgid: Int,
            comm: String,
            mode: Int,
            len: Int,
            flags: Int,
            watchedAddr: Long,
            pc: Long,
            sp: Long,
            pstate: Long,
            stackAddr: Long,
            stackRequestedSize: Int,
            stackCopiedSize: Int,
            regs: LongArray,
            stack: ByteArray,
        ) {
            val event = HwbpEvent(
                seq = seq,
                dropped = dropped,
                tid = tid,
                tgid = tgid,
                comm = comm,
                mode = mode,
                len = len,
                flags = flags,
                watchedAddr = watchedAddr,
                pc = pc,
                sp = sp,
                regs = regs,
                pstate = pstate,
                stackAddr = stackAddr,
                stackRequestedSize = stackRequestedSize,
                stackCopiedSize = stackCopiedSize,
                stack = stack,
            )
            hwbpEventQueue.trySend(event)
        }
    }

    suspend fun checkAvailable(): Boolean = withContext(Dispatchers.IO) {
        native.checkAvailable()
    }

    suspend fun getVersion(): Long = withContext(Dispatchers.IO) {
        native.getVersion()
    }

    suspend fun hwbpObserve(params: HwbpObserveParams): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val ret = hwbpOperations.observe(params)
            if (ret == 0) Result.success(Unit) else Result.failure(RuntimeException("prctl errno ${-ret}"))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            Result.failure(failure)
        }
    }

    suspend fun hwbpUnobserve(pid: Int, targetAddr: Long): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val ret = hwbpOperations.unobserve(pid, targetAddr)
            if (ret == 0) Result.success(Unit) else Result.failure(RuntimeException("prctl errno ${-ret}"))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            Result.failure(failure)
        }
    }

    suspend fun setHideDbg(enable: Boolean, uid: Int?): Result<Unit> = withContext(Dispatchers.IO) {
        val ret = native.hideDbg(enable, uid != null, uid ?: 0)
        if (ret == 0) Result.success(Unit) else Result.failure(RuntimeException("prctl errno ${-ret}"))
    }

    fun startEventStream() {
        hwbpOperations.startListening(callback)
    }

    fun stopEventStream() {
        hwbpOperations.stopListening()
    }

    companion object {
        val instance: YggdrasilRepository by lazy { YggdrasilRepository() }
    }
}
