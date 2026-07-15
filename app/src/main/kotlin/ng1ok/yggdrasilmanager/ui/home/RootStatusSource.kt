package ng1ok.yggdrasilmanager.ui.home

import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

fun interface RootStatusSource {
    suspend fun isRootGranted(): Boolean
}

object LibsuRootStatusSource : RootStatusSource {
    override suspend fun isRootGranted(): Boolean = suspendCancellableCoroutine { continuation ->
        Shell.getShell { shell ->
            if (continuation.isActive) {
                continuation.resume(shell.isRoot)
            }
        }
    }
}
