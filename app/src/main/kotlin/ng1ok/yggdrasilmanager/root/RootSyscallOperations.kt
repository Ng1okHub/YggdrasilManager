package ng1ok.yggdrasilmanager.root

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import android.util.Log
import com.topjohnwu.superuser.ipc.RootService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import ng1ok.yggdrasilmanager.data.SyscallOperations
import ng1ok.yggdrasilmanager.data.model.SyscallObserveRule
import ng1ok.yggdrasilmanager.native.SyscallEventCallback

/** App-process proxy for the root process that owns syscall operations. */
internal class RootSyscallOperations(context: Context) : SyscallOperations {
    private val appContext = context.applicationContext ?: context
    private val mainHandler = Handler(Looper.getMainLooper())
    private val connectionLock = Any()
    private val listenerLock = Any()
    private val serviceIntent = Intent(appContext, RootSyscallService::class.java)

    @Volatile
    private var callback: SyscallEventCallback? = null

    @Volatile
    private var remote: IRootSyscallService? = null

    private var binding = false
    private var ready = CompletableDeferred<IRootSyscallService>()

    private val eventTransport = object : IRootSyscallEventCallback.Stub() {
        override fun onEvent(event: RootSyscallEvent?) {
            val target = callback ?: return
            val value = event ?: return
            try {
                if (value.relayDropped > 0) target.onRelayDropped(value.relayDropped)
                target.onEvent(
                    seq = value.seq,
                    dropped = value.dropped,
                    callId = value.callId,
                    phase = value.phase,
                    tid = value.tid,
                    tgid = value.tgid,
                    syscallNr = value.syscallNr,
                    eventFlags = value.eventFlags,
                    ruleFlags = value.ruleFlags,
                    targetId = value.targetId,
                    comm = value.comm,
                    userCookie = value.userCookie,
                    argMask = value.argMask,
                    argsBefore = value.argsBefore,
                    argsAfter = value.argsAfter,
                    returnBefore = value.returnBefore,
                    returnAfter = value.returnAfter,
                )
            } catch (_: RuntimeException) {
            }
        }

        override fun onListenerFailure(error: Int) {
            callback?.onListenerFailure(error)
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val connected = service?.let(IRootSyscallService.Stub::asInterface)
            if (connected == null) {
                connectionFailed(IllegalStateException("Root 系統呼叫服務未回傳 Binder"))
                return
            }
            synchronized(connectionLock) {
                remote = connected
                binding = false
                if (!ready.isCompleted) ready.complete(connected)
            }
            synchronized(listenerLock) {
                if (callback != null && remote === connected) {
                    attachListenerLocked(connected)
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            connectionFailed(IllegalStateException("Root 系統呼叫服務連線已中斷"))
            ensureBound()
        }

        override fun onBindingDied(name: ComponentName?) {
            connectionFailed(IllegalStateException("Root 系統呼叫服務綁定失效"))
            ensureBound()
        }

        override fun onNullBinding(name: ComponentName?) {
            connectionFailed(IllegalStateException("Root 系統呼叫服務拒絕綁定"))
        }
    }

    override suspend fun setRule(rule: SyscallObserveRule): Int = awaitRemote().setRule(
        rule.syscallNr,
        rule.configureFlags,
        rule.targetId,
    )

    override suspend fun deleteRule(rule: SyscallObserveRule): Int = awaitRemote().deleteRule(
        rule.syscallNr,
        rule.deleteFlags,
        rule.targetId,
    )

    override fun startListening(callback: SyscallEventCallback) {
        synchronized(listenerLock) {
            this.callback = callback
        }
        val service = remote
        if (service == null) {
            ensureBound()
        } else {
            synchronized(listenerLock) {
                if (this.callback === callback && remote === service) {
                    attachListenerLocked(service)
                }
            }
        }
    }

    override fun stopListening() {
        synchronized(listenerLock) {
            callback = null
            val service = remote ?: return
            try {
                service.stopListening()
            } catch (error: RemoteException) {
                connectionFailed(error)
            }
        }
    }

    private suspend fun awaitRemote(): IRootSyscallService {
        remote?.let { return it }
        ensureBound()
        val pending = synchronized(connectionLock) { remote?.let { return it } ?: ready }
        return withTimeoutOrNull(ROOT_SERVICE_BIND_TIMEOUT_MILLIS) { pending.await() }
            ?: throw IllegalStateException("連線至 Root 系統呼叫服務逾時")
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

    /** Must be called while [listenerLock] is held. */
    private fun attachListenerLocked(service: IRootSyscallService) {
        try {
            // Binder call into the root process; result is the JNI/root errno.
            val result = service.startListening(eventTransport)
            Log.i(SYSCALL_LOG_TAG,
                "[app] startListening (Binder) returned $result " +
                    "(errno=${if (result < 0) -result else result})")
            val target = callback
            if (result != 0) target?.onListenerFailure(result)
            else target?.onListenerStarted()
        } catch (error: RemoteException) {
            // Transport failure -> reported as EPIPE(32), NOT the EIO(5) case.
            Log.e(SYSCALL_LOG_TAG, "[app] startListening Binder RemoteException", error)
            connectionFailed(error)
        }
    }

    private fun connectionFailed(error: Throwable) {
        synchronized(connectionLock) {
            remote = null
            binding = false
            if (!ready.isCompleted) ready.completeExceptionally(error)
        }
        Log.e(SYSCALL_LOG_TAG,
            "[app] connectionFailed -> onListenerFailure(errno=$ERRNO_EPIPE)", error)
        // The listener binds asynchronously, so route a failed bind back to
        // the repository instead of leaving the browser connected but idle.
        callback?.onListenerFailure(-ERRNO_EPIPE)
    }

    private companion object {
        const val ROOT_SERVICE_BIND_TIMEOUT_MILLIS = 10_000L
        const val ERRNO_EPIPE = 32
        // Shared tag with the JNI layer and RootSyscallService.
        const val SYSCALL_LOG_TAG = "YggSyscall"
    }
}
