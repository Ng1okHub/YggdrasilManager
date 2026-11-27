package ng1ok.yggdrasilmanager.root

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.topjohnwu.superuser.ipc.RootService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import ng1ok.yggdrasilmanager.data.MapsDumpCodec
import ng1ok.yggdrasilmanager.data.MapsOperations
import ng1ok.yggdrasilmanager.data.model.MapsRule
import ng1ok.yggdrasilmanager.data.model.MapsScope

/** App-process proxy for maps policy calls made by a libsu root process. */
internal class RootMapsOperations(context: Context) : MapsOperations {
    private val appContext = context.applicationContext ?: context
    private val mainHandler = Handler(Looper.getMainLooper())
    private val connectionLock = Any()
    private val serviceIntent = Intent(appContext, RootMapsService::class.java)

    @Volatile
    private var remote: IRootMapsService? = null
    private var binding = false
    private var ready = CompletableDeferred<IRootMapsService>()

    override suspend fun addRule(pid: Int?, rule: MapsRule): Int = awaitRemote().addRule(
        if (pid != null) 1 else 0,
        pid ?: 0,
        rule.action,
        rule.matchKind,
        if (rule.matchesPermissions) 1 else 0,
        rule.matchPerms.orEmpty(),
        rule.setPerms.orEmpty(),
        rule.addrStart,
        rule.addrEnd,
        rule.pattern,
    )

    override suspend fun removeRule(pid: Int?, rule: MapsRule): Int = awaitRemote().removeRule(
        if (pid != null) 1 else 0,
        pid ?: 0,
        rule.action,
        rule.matchKind,
        if (rule.matchesPermissions) 1 else 0,
        rule.matchPerms.orEmpty(),
        rule.setPerms.orEmpty(),
        rule.addrStart,
        rule.addrEnd,
        rule.pattern,
    )

    override suspend fun clear(pid: Int?, allScopes: Boolean): Int = awaitRemote().clear(
        if (pid != null) 1 else 0,
        pid ?: 0,
        if (allScopes) 1 else 0,
    )

    override suspend fun get(pid: Int?): MapsScope? = MapsDumpCodec.decode(
        awaitRemote().get(if (pid != null) 1 else 0, pid ?: 0)
    )

    private suspend fun awaitRemote(): IRootMapsService {
        remote?.let { return it }
        ensureBound()
        val pending = synchronized(connectionLock) { remote?.let { return it } ?: ready }
        return withTimeout(ROOT_SERVICE_BIND_TIMEOUT_MILLIS) { pending.await() }
    }

    private fun ensureBound() {
        val shouldBind = synchronized(connectionLock) {
            if (remote != null || binding) false else {
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
        if (Looper.myLooper() == Looper.getMainLooper()) bind.run() else mainHandler.post(bind)
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val connected = service?.let(IRootMapsService.Stub::asInterface)
            if (connected == null) {
                connectionFailed(IllegalStateException("Root Maps 服務未回傳 Binder"))
                return
            }
            synchronized(connectionLock) {
                remote = connected
                binding = false
                if (!ready.isCompleted) ready.complete(connected)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            connectionFailed(IllegalStateException("Root Maps 服務連線中斷"))
        }

        override fun onBindingDied(name: ComponentName?) {
            connectionFailed(IllegalStateException("Root Maps 服務綁定失效"))
        }

        override fun onNullBinding(name: ComponentName?) {
            connectionFailed(IllegalStateException("Root Maps 服務拒絕綁定"))
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
