package ng1ok.yggdrasilmanager.root

import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Process
import android.os.RemoteException
import android.util.Log
import com.topjohnwu.superuser.ipc.RootService
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import ng1ok.yggdrasilmanager.data.model.SyscallObserveRule
import ng1ok.yggdrasilmanager.native.SyscallEventCallback
import ng1ok.yggdrasilmanager.native.YggdrasilNative

/** Runs all syscall prctl operations inside one libsu root process. */
class RootSyscallService : RootService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val eventQueue = Channel<RootSyscallEvent>(
        capacity = ROOT_EVENT_BUFFER_CAPACITY,
    )
    private val relayDropped = AtomicLong(0)
    private val listenerLock = Any()

    @Volatile
    private var eventListener: IRootSyscallEventCallback? = null

    private var listenerDeathRecipient: IBinder.DeathRecipient? = null
    /** Guarded by [listenerLock], together with the native reader lifecycle. */
    private var listening = false
    private val nativeListenerFailure = AtomicInteger(0)

    init {
        serviceScope.launch {
            for (event in eventQueue) {
                val listener = eventListener ?: continue
                try {
                    listener.onEvent(event)
                } catch (_: RemoteException) {
                    requestStopAfterListenerLoss(listener)
                }
            }
        }
    }

    private val nativeCallback = object : SyscallEventCallback {
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
            captureCount: Int,
            capturePayload: ByteArray,
            backtraceFlags: Int,
            backtraceSeq: Long,
            backtraceDropped: Long,
            backtraceCallId: Long,
            backtracePhase: Int,
            backtraceTid: Int,
            backtraceTgid: Int,
            backtraceSyscallNr: Int,
            backtraceStackRequestedSize: Int,
            backtraceStackCopiedSize: Int,
            backtraceStackAddr: Long,
            backtraceRegs: LongArray,
            backtraceStack: ByteArray,
        ) {
            val pendingRelayDrops = relayDropped.getAndSet(0)
            val event = RootSyscallEvent(
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
                    argsBefore = argsBefore,
                    argsAfter = argsAfter,
                    returnBefore = returnBefore,
                    returnAfter = returnAfter,
                    captureCount = captureCount,
                    capturePayload = capturePayload,
                    relayDropped = pendingRelayDrops,
                    backtraceFlags = backtraceFlags,
                    backtraceSeq = backtraceSeq,
                    backtraceDropped = backtraceDropped,
                    backtraceCallId = backtraceCallId,
                    backtracePhase = backtracePhase,
                    backtraceTid = backtraceTid,
                    backtraceTgid = backtraceTgid,
                    backtraceSyscallNr = backtraceSyscallNr,
                    backtraceStackRequestedSize = backtraceStackRequestedSize,
                    backtraceStackCopiedSize = backtraceStackCopiedSize,
                    backtraceStackAddr = backtraceStackAddr,
                    backtraceRegs = backtraceRegs,
                    backtraceStack = backtraceStack,
                )
            if (eventQueue.trySend(event).isFailure) {
                // Preserve event order for the consumer and report this gap
                // with the next record that can enter the queue.
                relayDropped.addAndGet(pendingRelayDrops + 1)
            }
        }

        override fun onListenerFailure(error: Int) {
            if (error == 0) return
            // Runtime failure from the JNI reader thread (root process). errno is
            // the magnitude; error is negative when it came from -errno.
            Log.e(SYSCALL_LOG_TAG,
                "[root] native reader onListenerFailure error=$error errno=" +
                    "${if (error < 0) -error else error} (listening=$listening)")
            nativeListenerFailure.compareAndSet(0, error)
            requestStopAfterNativeFailure()
        }
    }

    private val binder = object : IRootSyscallService.Stub() {
        override fun setRule(
            syscallNr: Int,
            flags: Int,
            targetId: Long,
            backtraceStackSize: Int,
        ): Int {
            requireRootProcess()
            if (!isValidSetTarget(flags, targetId)) return -ERRNO_EINVAL
            if (wouldTraceObserver(flags, targetId)) return -ERRNO_EPERM
            return YggdrasilNative.syscallConfigureWithBacktrace(
                op = 1,
                flags = flags,
                syscallNr = syscallNr,
                targetId = targetId,
                backtraceStackSize = backtraceStackSize,
            )
        }

        override fun deleteRule(syscallNr: Int, selectorFlags: Int, targetId: Long): Int {
            requireRootProcess()
            if (!isValidDeleteTarget(selectorFlags, targetId)) return -ERRNO_EINVAL
            return YggdrasilNative.syscallConfigure(
                op = 2,
                flags = selectorFlags,
                syscallNr = syscallNr,
                targetId = targetId,
            )
        }

        override fun startListening(callback: IRootSyscallEventCallback?): Int {
            requireRootProcess()
            val listener = requireNotNull(callback) { "必須提供系統呼叫事件回呼" }
            return synchronized(listenerLock) {
                clearFailedNativeListenerLocked()
                if (!replaceListenerLocked(listener)) return@synchronized -ERRNO_EPIPE
                if (listening) return@synchronized 0

                Log.i(SYSCALL_LOG_TAG,
                    "[root] startListening: calling syscallStartListen " +
                        "(uid=${Process.myUid()} pid=${Process.myPid()})")
                val result = YggdrasilNative.syscallStartListen(nativeCallback)
                if (result != 0) {
                    // errno born here == JNI handshake failure (candidate A:
                    // AttachCurrentThread / null method id -> -EIO/-5).
                    Log.e(SYSCALL_LOG_TAG,
                        "[root] syscallStartListen returned $result " +
                            "(errno=${if (result < 0) -result else result})")
                    return@synchronized result
                }

                val startupFailure = nativeListenerFailure.getAndSet(0)
                if (startupFailure != 0) {
                    Log.e(SYSCALL_LOG_TAG,
                        "[root] startup-race failure=$startupFailure " +
                            "(errno=${if (startupFailure < 0) -startupFailure else startupFailure})")
                    clearListenerLocked()
                    // The reader failed after the JNI handshake but before this
                    // Binder call returned, so it has not set [listening] yet.
                    YggdrasilNative.syscallStopListen()
                    return@synchronized startupFailure
                }
                Log.i(SYSCALL_LOG_TAG, "[root] startListening OK, reader running")
                listening = true
                0
            }
        }

        override fun stopListening() {
            stopListeningInternal()
        }
    }

    override fun onBind(intent: Intent): IBinder {
        requireRootProcess()
        return binder
    }

    override fun onDestroy() {
        stopListeningInternal()
        eventQueue.close()
        serviceScope.cancel()
        super.onDestroy()
    }

    /** Must be called while [listenerLock] is held. */
    private fun replaceListenerLocked(listener: IRootSyscallEventCallback): Boolean {
        val callbackBinder = listener.asBinder()
        val deathRecipient = object : IBinder.DeathRecipient {
            override fun binderDied() {
                requestStopAfterListenerLoss(listener)
            }
        }
        try {
            callbackBinder.linkToDeath(deathRecipient, 0)
        } catch (_: RemoteException) {
            return false
        }

        // Keep the previous callback alive until the new callback is known to be linkable.
        clearListenerLocked()
        eventListener = listener
        listenerDeathRecipient = deathRecipient
        return true
    }

    private fun requestStopAfterListenerLoss(lostListener: IRootSyscallEventCallback) {
        serviceScope.launch {
            synchronized(listenerLock) {
                if (eventListener !== lostListener) return@synchronized
                clearListenerLocked()
                stopNativeListenerLocked()
            }
        }
    }

    private fun requestStopAfterNativeFailure() {
        serviceScope.launch {
            var failure = 0
            var listener: IRootSyscallEventCallback? = null
            synchronized(listenerLock) {
                // Keep a failure reported during JNI startup pending until
                // startListening() consumes it. Otherwise an old reader can
                // race a replacement and stop the replacement's reader.
                if (!listening) return@synchronized
                failure = nativeListenerFailure.getAndSet(0)
                if (failure == 0) return@synchronized
                listener = eventListener
                clearListenerLocked()
                // This coroutine is never the JNI reader thread, so joining is safe.
                stopNativeListenerLocked()
            }
            if (failure == 0) return@launch
            try {
                listener?.onListenerFailure(failure)
            } catch (_: RemoteException) {
            }
        }
    }

    /** Must be called while [listenerLock] is held. */
    private fun clearFailedNativeListenerLocked() {
        if (nativeListenerFailure.getAndSet(0) == 0) return
        clearListenerLocked()
        if (listening) {
            stopNativeListenerLocked()
        } else {
            YggdrasilNative.syscallStopListen()
        }
    }

    private fun stopListeningInternal() {
        synchronized(listenerLock) {
            clearListenerLocked()
            stopNativeListenerLocked()
        }
    }

    /** Must be called while [listenerLock] is held. */
    private fun stopNativeListenerLocked() {
        if (!listening) return
        listening = false
        YggdrasilNative.syscallStopListen()
    }

    /** Must be called while [listenerLock] is held. */
    private fun clearListenerLocked() {
        val listener = eventListener
        val deathRecipient = listenerDeathRecipient
        if (listener != null && deathRecipient != null) {
            runCatching { listener.asBinder().unlinkToDeath(deathRecipient, 0) }
        }
        eventListener = null
        listenerDeathRecipient = null
    }

    private fun requireRootProcess() {
        check(Process.myUid() == Process.ROOT_UID) {
            "RootSyscallService 未以 root 身分啟動"
        }
    }

    /*
     * KPM only exempts the native reader TID. Tracing either side of this
     * Binder bridge can feed its own forwarding syscalls back into the FIFO.
     * Delete requests intentionally bypass this policy so legacy rules remain
     * recoverable.
     */
    private fun wouldTraceObserver(flags: Int, targetId: Long): Boolean {
        val callerPid = Binder.getCallingPid()
        val callerUid = Binder.getCallingUid()
        return when {
            flags and SyscallObserveRule.FLAG_TARGET_UID != 0 ->
                targetId == Process.ROOT_UID.toLong() || targetId == callerUid.toLong()
            flags and SyscallObserveRule.FLAG_SINGLE_THREAD != 0 ->
                isRootServiceThread(targetId.toInt()) ||
                    targetId == callerPid.toLong() ||
                    isThreadInProcess(callerPid, targetId.toInt())
            else ->
                targetId == 0L ||
                    targetId == Process.myPid().toLong() ||
                    targetId == callerPid.toLong()
        }
    }

    private fun isValidSetTarget(flags: Int, targetId: Long): Boolean = when (
        flags and SYSCALL_SELECTOR_FLAGS
    ) {
        SyscallObserveRule.FLAG_TARGET_UID -> targetId in 1L..SyscallObserveRule.MAX_UID
        SyscallObserveRule.FLAG_SINGLE_THREAD,
        0 -> targetId in 1L..SyscallObserveRule.MAX_PROCESS_ID
        else -> false
    }

    private fun isValidDeleteTarget(selectorFlags: Int, targetId: Long): Boolean = when (
        selectorFlags and SYSCALL_SELECTOR_FLAGS
    ) {
        SyscallObserveRule.FLAG_TARGET_UID -> targetId in 0L..SyscallObserveRule.MAX_UID
        SyscallObserveRule.FLAG_SINGLE_THREAD ->
            targetId in 1L..SyscallObserveRule.MAX_PROCESS_ID
        0 -> targetId in 0L..SyscallObserveRule.MAX_PROCESS_ID
        else -> false
    }

    private fun isRootServiceThread(tid: Int): Boolean =
        tid == Process.myPid() || File("/proc/self/task/$tid").isDirectory

    private fun isThreadInProcess(pid: Int, tid: Int): Boolean =
        pid > 0 && tid > 0 && File("/proc/$pid/task/$tid").isDirectory

    private companion object {
        const val ERRNO_EPERM = 1
        const val ERRNO_EINVAL = 22
        const val ERRNO_EPIPE = 32
        const val ROOT_EVENT_BUFFER_CAPACITY = 256
        const val SYSCALL_SELECTOR_FLAGS =
            SyscallObserveRule.FLAG_SINGLE_THREAD or SyscallObserveRule.FLAG_TARGET_UID
        // Shared with the JNI layer (YGG_SYSCALL_LOG_TAG) so a single
        // `adb logcat -s YggSyscall` shows the whole listener lifecycle.
        const val SYSCALL_LOG_TAG = "YggSyscall"
    }
}
