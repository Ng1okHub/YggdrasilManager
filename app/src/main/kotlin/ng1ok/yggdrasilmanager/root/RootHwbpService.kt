package ng1ok.yggdrasilmanager.root

import android.content.Intent
import android.os.IBinder
import android.os.Process
import android.os.RemoteException
import com.topjohnwu.superuser.ipc.RootService
import java.util.concurrent.atomic.AtomicBoolean
import ng1ok.yggdrasilmanager.native.HwbpEventCallback
import ng1ok.yggdrasilmanager.native.YggdrasilNative

/**
 * Runs every HWBP prctl in the libsu root process.
 *
 * KPM scopes HWBP event handling to the caller's credentials. Keeping observe, unobserve,
 * and read in this one process prevents an app-UID reader from being mixed with
 * a root-installed observation.
 */
class RootHwbpService : RootService() {
    @Volatile
    private var eventListener: IRootHwbpEventCallback? = null

    private val listening = AtomicBoolean(false)

    private val nativeCallback = object : HwbpEventCallback {
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
            val listener = eventListener ?: return
            try {
                listener.onEvent(
                    RootHwbpEvent(
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
                        pstate = pstate,
                        stackAddr = stackAddr,
                        stackRequestedSize = stackRequestedSize,
                        stackCopiedSize = stackCopiedSize,
                        regs = regs,
                        stack = stack,
                    ),
                )
            } catch (_: RemoteException) {
                eventListener = null
            }
        }
    }

    private val binder = object : IRootHwbpService.Stub() {
        override fun observe(
            pid: Int,
            targetAddr: Long,
            mode: Int,
            len: Int,
            flags: Int,
            stackSize: Int,
        ): Int {
            requireRootProcess()
            return YggdrasilNative.hwbpObserve(pid, targetAddr, mode, len, flags, stackSize)
        }

        override fun unobserve(pid: Int, targetAddr: Long): Int {
            requireRootProcess()
            return YggdrasilNative.hwbpUnobserve(pid, targetAddr)
        }

        override fun startListening(callback: IRootHwbpEventCallback?) {
            requireRootProcess()
            requireNotNull(callback) { "HWBP event callback is required" }
            eventListener = callback
            if (listening.compareAndSet(false, true)) {
                YggdrasilNative.hwbpStartListen(nativeCallback)
            }
        }

        override fun stopListening() {
            eventListener = null
            if (listening.compareAndSet(true, false)) {
                YggdrasilNative.hwbpStopListen()
            }
        }
    }

    override fun onBind(intent: Intent): IBinder {
        requireRootProcess()
        return binder
    }

    override fun onDestroy() {
        binder.stopListening()
        super.onDestroy()
    }

    private fun requireRootProcess() {
        check(Process.myUid() == Process.ROOT_UID) {
            "RootHwbpService was not launched as root"
        }
    }
}
