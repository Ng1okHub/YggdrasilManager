package ng1ok.yggdrasilmanager.data

import android.content.Context
import android.os.Process
import android.util.Log
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import ng1ok.yggdrasilmanager.data.model.HwbpEvent
import ng1ok.yggdrasilmanager.data.model.HwbpObserveParams
import ng1ok.yggdrasilmanager.data.model.SyscallEvent
import ng1ok.yggdrasilmanager.data.model.SyscallListenerState
import ng1ok.yggdrasilmanager.data.model.SyscallListenerStatus
import ng1ok.yggdrasilmanager.data.model.SyscallObserveRule
import ng1ok.yggdrasilmanager.data.model.SyscallScope
import ng1ok.yggdrasilmanager.native.HwbpEventCallback
import ng1ok.yggdrasilmanager.native.SyscallEventCallback
import ng1ok.yggdrasilmanager.native.YggdrasilNative
import ng1ok.yggdrasilmanager.native.YggdrasilNativeApi
import ng1ok.yggdrasilmanager.root.RootHwbpOperations
import ng1ok.yggdrasilmanager.root.RootSyscallOperations

class YggdrasilRepository(
    private val native: YggdrasilNativeApi = YggdrasilNative,
    private val observerProcessId: () -> Int = Process::myPid,
    private val observerUid: () -> Int = Process::myUid,
    private val observerThreadIds: () -> Set<Int> = ::currentProcessThreadIds,
) {
    private val localHwbpOperations = LocalHwbpOperations(native)
    private val localSyscallOperations = LocalSyscallOperations(native)

    @Volatile
    private var hwbpOperations: HwbpOperations = localHwbpOperations

    @Volatile
    private var syscallOperations: SyscallOperations = localSyscallOperations

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

    /*
     * Syscall tracing can produce events much faster than a browser can render.
     * Keep the root callback non-blocking and bound retained events when the web
     * server is stopped or a client is slow.
     */
    private val syscallEventQueue = Channel<SyscallEvent>(
        capacity = 256,
    )
    private val syscallRelayDropped = AtomicLong(0)
    private val syscallEventConsumerMutex = Mutex()
    val syscallEvents: Flow<SyscallEvent> = flow {
        syscallEventConsumerMutex.withLock {
            for (event in syscallEventQueue) {
                emit(event)
            }
        }
    }

    /*
     * The KPM exposes no syscall-rule enumeration. Keep the selectors created
     * by this Manager process centrally so a recreated web server can still
     * remove them. They are deliberately not replayed after an app restart.
     */
    private val syscallRuleMutex = Mutex()
    private val _syscallRules = MutableStateFlow<List<SyscallObserveRule>>(emptyList())
    val syscallRules: StateFlow<List<SyscallObserveRule>> = _syscallRules.asStateFlow()
    private val _syscallListenerError = MutableStateFlow<String?>(null)
    val syscallListenerError: StateFlow<String?> = _syscallListenerError.asStateFlow()
    private val _syscallListenerStatus = MutableStateFlow(SyscallListenerStatus())
    val syscallListenerStatus: StateFlow<SyscallListenerStatus> =
        _syscallListenerStatus.asStateFlow()
    private val syscallStreamLock = Any()

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

    /** Makes every syscall control/read call execute in one libsu root process. */
    internal fun useRootSyscall(context: Context) {
        synchronized(this) {
            if (syscallOperations === localSyscallOperations) {
                syscallOperations = RootSyscallOperations(context)
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

    private val syscallCallback = object : SyscallEventCallback {
        override fun onListenerStarted() {
            _syscallListenerError.value = null
            _syscallListenerStatus.value = SyscallListenerStatus(SyscallListenerState.RUNNING)
        }

        override fun onRelayDropped(count: Long) {
            if (count > 0) syscallRelayDropped.addAndGet(count)
        }

        override fun onEvent(
            seq: Long,
            dropped: Long,
            callId: Long,
            phase: Int,
            tid: Int,
            tgid: Int,
            syscallNr: Int,
            eventFlags: Int,
            ruleFlags: Int,
            targetId: Long,
            comm: String,
            userCookie: Long,
            argMask: Long,
            argsBefore: LongArray,
            argsAfter: LongArray,
            returnBefore: Long,
            returnAfter: Long,
        ) {
            val pendingRelayDrops = syscallRelayDropped.getAndSet(0)
            val event = SyscallEvent(
                    seq = seq,
                    dropped = dropped,
                    callId = callId,
                    phase = phase,
                    tid = tid,
                    tgid = tgid,
                    syscallNr = syscallNr,
                    eventFlags = eventFlags,
                    ruleFlags = ruleFlags,
                    targetId = targetId,
                    comm = comm,
                    userCookie = userCookie,
                    argMask = argMask,
                    argsBefore = argsBefore.toList(),
                    argsAfter = argsAfter.toList(),
                    returnBefore = returnBefore,
                    returnAfter = returnAfter,
                    relayDropped = pendingRelayDrops,
                )
            if (syscallEventQueue.trySend(event).isFailure) {
                syscallRelayDropped.addAndGet(pendingRelayDrops + 1)
            }
        }

        override fun onListenerFailure(error: Int) {
            val errno = if (error < 0) -error else error
            // Terminal sink for the "監聽器已停止" message (app process). The
            // Throwable captures the delivering call stack so we can tell the
            // startup path from the runtime path in one logcat line.
            // Diagnostics must never prevent publishing the terminal state.
            runCatching {
                Log.e(
                    SYSCALL_LOG_TAG,
                    "[app] onListenerFailure raw=$error -> errno=$errno " +
                        "(process=${observerProcessId()})",
                    Throwable("listener-failure trace"),
                )
            }
            val message = "系統呼叫監聽器已停止（errno $errno）"
            _syscallListenerError.value = message
            _syscallListenerStatus.value = SyscallListenerStatus(
                state = SyscallListenerState.FAILED,
                error = message,
            )
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

    suspend fun setSyscallRule(rule: SyscallObserveRule): Result<Unit> =
        withContext(Dispatchers.IO) {
            syscallRuleMutex.withLock {
                try {
                    validateSyscallObserveTarget(rule)
                    val ret = syscallOperations.setRule(rule)
                    if (ret == 0) {
                        _syscallRules.value = _syscallRules.value
                            .filterNot { it.hasSameSelector(rule) } + rule
                        Result.success(Unit)
                    } else {
                        Result.failure(RuntimeException("prctl 失敗（errno ${-ret}）"))
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Exception) {
                    Result.failure(failure)
                }
            }
        }

    suspend fun deleteSyscallRule(rule: SyscallObserveRule): Result<Unit> =
        withContext(Dispatchers.IO) {
            syscallRuleMutex.withLock {
                try {
                    val ret = syscallOperations.deleteRule(rule)
                    if (ret == 0) {
                        _syscallRules.value = _syscallRules.value
                            .filterNot { it.hasSameSelector(rule) }
                        Result.success(Unit)
                    } else {
                        Result.failure(RuntimeException("prctl 失敗（errno ${-ret}）"))
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Exception) {
                    Result.failure(failure)
                }
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

    fun startSyscallEventStream() {
        synchronized(syscallStreamLock) {
            _syscallListenerError.value = null
            _syscallListenerStatus.value = SyscallListenerStatus(SyscallListenerState.STARTING)
            syscallOperations.startListening(syscallCallback)
        }
    }

    fun stopSyscallEventStream() {
        synchronized(syscallStreamLock) {
            syscallOperations.stopListening()
            _syscallListenerError.value = null
            _syscallListenerStatus.value = SyscallListenerStatus(SyscallListenerState.STOPPED)
        }
    }

    fun restartSyscallEventStream() {
        synchronized(syscallStreamLock) {
            syscallOperations.stopListening()
            _syscallListenerError.value = null
            _syscallListenerStatus.value = SyscallListenerStatus(SyscallListenerState.STARTING)
            syscallOperations.startListening(syscallCallback)
        }
    }

    private fun SyscallObserveRule.hasSameSelector(other: SyscallObserveRule): Boolean =
        syscallNr == other.syscallNr && scope == other.scope && targetId == other.targetId

    /*
     * The KPM only excludes the root reader thread. A broad rule, or a rule
     * targeting this app, would also trace Binder/WebSocket work that forwards
     * events and can continually refill the global event FIFO.
     */
    private fun validateSyscallObserveTarget(rule: SyscallObserveRule) {
        val ownPid = observerProcessId()
        when (rule.scope) {
            SyscallScope.GLOBAL -> throw IllegalArgumentException(
                "無法使用全域範圍，否則會觀察到觀察器本身",
            )
            SyscallScope.UID -> require(
                rule.targetId != Process.ROOT_UID.toLong() &&
                    rule.targetId != observerUid().toLong()
            ) {
                "無法觀察觀察器的 UID"
            }
            SyscallScope.TGID -> require(rule.targetId != ownPid.toLong()) {
                "無法觀察觀察器的處理程序"
            }
            SyscallScope.TID -> require(
                rule.targetId != ownPid.toLong() && rule.targetId.toInt() !in observerThreadIds()
            ) {
                "無法觀察觀察器的執行緒"
            }
        }
    }

    companion object {
        val instance: YggdrasilRepository by lazy { YggdrasilRepository() }
        // Shared tag with the JNI layer and root service; one logcat filter
        // (`adb logcat -s YggSyscall`) covers the whole listener lifecycle.
        private const val SYSCALL_LOG_TAG = "YggSyscall"
    }
}

private fun currentProcessThreadIds(): Set<Int> = buildSet {
    add(Process.myPid())
    File("/proc/self/task").list()
        ?.mapNotNull(String::toIntOrNull)
        ?.let(::addAll)
}
