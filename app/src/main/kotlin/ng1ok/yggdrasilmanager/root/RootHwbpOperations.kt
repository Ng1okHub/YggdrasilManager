package ng1ok.yggdrasilmanager.root

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import com.topjohnwu.superuser.ipc.RootService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import ng1ok.yggdrasilmanager.data.HwbpOperations
import ng1ok.yggdrasilmanager.data.model.HwbpObserveParams
import ng1ok.yggdrasilmanager.native.HwbpEventCallback

/** App-process proxy for the root process that owns HWBP operations. */
internal class RootHwbpOperations(context: Context) : HwbpOperations {
    private val appContext = context.applicationContext ?: context
    private val mainHandler = Handler(Looper.getMainLooper())
    private val connectionLock = Any()
    private val serviceIntent = Intent(appContext, RootHwbpService::class.java)

    @Volatile
    private var callback: HwbpEventCallback? = null

    @Volatile
    private var remote: IRootHwbpService? = null

    private var binding = false
    private var ready = CompletableDeferred<IRootHwbpService>()

    private val eventTransport = object : IRootHwbpEventCallback.Stub() {
        override fun onEvent(event: RootHwbpEvent?) {
            val target = callback ?: return
            val value = event ?: return
            try {
                target.onEvent(
                    seq = value.seq,
                    dropped = value.dropped,
                    tid = value.tid,
                    tgid = value.tgid,
                    comm = value.comm,
                    mode = value.mode,
                    len = value.len,
                    flags = value.flags,
                    watchedAddr = value.watchedAddr,
                    pc = value.pc,
                    sp = value.sp,
                    pstate = value.pstate,
                    stackAddr = value.stackAddr,
                    stackRequestedSize = value.stackRequestedSize,
                    stackCopiedSize = value.stackCopiedSize,
                    regs = value.regs,
                    stack = value.stack,
                )
            } catch (_: RuntimeException) {
            }
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val connected = service?.let(IRootHwbpService.Stub::asInterface)
            if (connected == null) {
                connectionFailed(IllegalStateException("Root HWBP service returned no binder"))
                return
            }
            synchronized(connectionLock) {
                remote = connected
                binding = false
                if (!ready.isCompleted) ready.complete(connected)
            }
            if (callback != null) attachListener(connected)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            connectionFailed(IllegalStateException("Root HWBP service disconnected"))
            ensureBound()
        }

        override fun onBindingDied(name: ComponentName?) {
            connectionFailed(IllegalStateException("Root HWBP service binding died"))
            ensureBound()
        }

        override fun onNullBinding(name: ComponentName?) {
            connectionFailed(IllegalStateException("Root HWBP service rejected binding"))
        }
    }

    override suspend fun observe(params: HwbpObserveParams): Int = awaitRemote().observe(
        params.pid,
        params.targetAddr,
        params.mode,
        params.len,
        params.flags,
        params.stackSize,
    )

    override suspend fun unobserve(pid: Int, targetAddr: Long): Int =
        awaitRemote().unobserve(pid, targetAddr)

    override fun startListening(callback: HwbpEventCallback) {
        this.callback = callback
        ensureBound()
        remote?.let(::attachListener)
    }

    override fun stopListening() {
        callback = null
        val service = remote ?: return
        try {
            service.stopListening()
        } catch (error: RemoteException) {
            connectionFailed(error)
        }
    }

    private suspend fun awaitRemote(): IRootHwbpService {
        remote?.let { return it }
        ensureBound()
        val pending = synchronized(connectionLock) { remote?.let { return it } ?: ready }
        return withTimeout(ROOT_SERVICE_BIND_TIMEOUT_MILLIS) { pending.await() }
    }

    private fun ensureBound() {
        val shouldBind = synchronized(connectionLock) {
            if (remote != null || binding) {
                false
            } else {
                if (ready.isCompleted) ready = CompletableDeferred()
                binding = true
                true
            }
        }
        if (!shouldBind) return

        val bind = Runnable {
            try {
                RootService.bind(serviceIntent, connection)
            } catch (error: RuntimeException) {
                connectionFailed(error)
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            bind.run()
        } else {
            mainHandler.post(bind)
        }
    }

    private fun attachListener(service: IRootHwbpService) {
        try {
            service.startListening(eventTransport)
        } catch (error: RemoteException) {
            connectionFailed(error)
        }
    }

    private fun connectionFailed(error: Throwable) {
        synchronized(connectionLock) {
            remote = null
            binding = false
            if (!ready.isCompleted) ready.completeExceptionally(error)
        }
    }

    private companion object {
        const val ROOT_SERVICE_BIND_TIMEOUT_MILLIS = 10_000L
    }
}
