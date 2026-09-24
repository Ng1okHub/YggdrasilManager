package ng1ok.yggdrasilmanager.root

import android.content.Intent
import android.os.IBinder
import android.os.Process
import com.topjohnwu.superuser.ipc.RootService
import ng1ok.yggdrasilmanager.native.YggdrasilNative

/** Executes maps policy prctl calls in the libsu root process. */
class RootMapsService : RootService() {
    private val binder = object : IRootMapsService.Stub() {
        override fun addRule(
            hasPid: Int,
            pid: Int,
            action: Int,
            matchKind: Int,
            ruleFlags: Int,
            matchPerms: String?,
            setPerms: String?,
            addrStart: Long,
            addrEnd: Long,
            pattern: String?,
        ): Int {
            requireRootProcess()
            return YggdrasilNative.mapsAddRule(
                hasPid != 0, pid, action, matchKind, ruleFlags,
                matchPerms.orEmpty(), setPerms.orEmpty(), addrStart, addrEnd, pattern.orEmpty()
            )
        }

        override fun removeRule(
            hasPid: Int,
            pid: Int,
            action: Int,
            matchKind: Int,
            ruleFlags: Int,
            matchPerms: String?,
            setPerms: String?,
            addrStart: Long,
            addrEnd: Long,
            pattern: String?,
        ): Int {
            requireRootProcess()
            return YggdrasilNative.mapsRemoveRule(
                hasPid != 0, pid, action, matchKind, ruleFlags,
                matchPerms.orEmpty(), setPerms.orEmpty(), addrStart, addrEnd, pattern.orEmpty()
            )
        }

        override fun clear(hasPid: Int, pid: Int, allScopes: Int): Int {
            requireRootProcess()
            return YggdrasilNative.mapsClear(hasPid != 0, pid, allScopes != 0)
        }

        override fun get(hasPid: Int, pid: Int): ByteArray? {
            requireRootProcess()
            return YggdrasilNative.mapsGet(hasPid != 0, pid)
        }
    }

    override fun onBind(intent: Intent): IBinder {
        requireRootProcess()
        return binder
    }

    private fun requireRootProcess() {
        check(Process.myUid() == Process.ROOT_UID) {
            "RootMapsService was not launched as root"
        }
    }
}
