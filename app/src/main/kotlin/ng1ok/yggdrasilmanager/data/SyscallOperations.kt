package ng1ok.yggdrasilmanager.data

import ng1ok.yggdrasilmanager.data.model.SyscallObserveRule
import ng1ok.yggdrasilmanager.native.SyscallEventCallback
import ng1ok.yggdrasilmanager.native.YggdrasilNativeApi

/** Owns the credential domain used for syscall configuration and event reads. */
internal interface SyscallOperations {
    suspend fun setRule(rule: SyscallObserveRule): Int

    suspend fun deleteRule(rule: SyscallObserveRule): Int

    fun startListening(callback: SyscallEventCallback)

    fun stopListening()
}

internal class LocalSyscallOperations(
    private val native: YggdrasilNativeApi,
) : SyscallOperations {
    override suspend fun setRule(rule: SyscallObserveRule): Int = if (rule.captureBacktrace) {
        native.syscallConfigureWithBacktrace(
            SyscallObserveRule.OP_SET,
            rule.configureFlags,
            rule.syscallNr,
            rule.targetId,
            rule.backtraceStackSize,
        )
    } else {
        native.syscallConfigure(
            SyscallObserveRule.OP_SET,
            rule.configureFlags,
            rule.syscallNr,
            rule.targetId,
        )
    }

    override suspend fun deleteRule(rule: SyscallObserveRule): Int = native.syscallConfigure(
        SyscallObserveRule.OP_DELETE,
        rule.deleteFlags,
        rule.syscallNr,
        rule.targetId,
    )

    override fun startListening(callback: SyscallEventCallback) {
        val result = native.syscallStartListen(callback)
        if (result != 0) callback.onListenerFailure(result) else callback.onListenerStarted()
    }

    override fun stopListening() {
        native.syscallStopListen()
    }
}
