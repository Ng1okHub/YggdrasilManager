package ng1ok.yggdrasilmanager.data.model

data class SyscallEvent(
    val seq: Long,
    val dropped: Long,
    val callId: Long,
    val phase: Int,
    val tid: Int,
    val tgid: Int,
    val syscallNr: Int,
    val eventFlags: Int,
    val ruleFlags: Int,
    val targetId: Long,
    val comm: String,
    val userCookie: Long,
    val argMask: Long,
    val argsBefore: List<Long>,
    val argsAfter: List<Long>,
    val returnBefore: Long,
    val returnAfter: Long,
    /** Validated Yggdrasil capture TLVs, retained for typed userspace decoding. */
    val captureCount: Int = 0,
    val capturePayload: ByteArray = ByteArray(0),
    /** Events lost by the root/App forwarding queues before this record. */
    val relayDropped: Long = 0,
    val backtrace: SyscallBacktrace? = null,
) {
    val captures: List<SyscallCapture> by lazy(LazyThreadSafetyMode.NONE) {
        SyscallCaptureDecoder.decode(captureCount, capturePayload)
    }

    val syscallName: String
        get() = Arm64SyscallCatalog.nameFor(syscallNr)

    val phaseName: String
        get() = when (phase) {
            PHASE_ENTER -> "enter"
            PHASE_EXIT -> "exit"
            else -> "unknown"
        }

    companion object {
        const val PHASE_ENTER = 1
        const val PHASE_EXIT = 2
        const val FLAG_AFTER_DROPPED = 1 shl 2
        const val FLAG_HAS_BACKTRACE = 1 shl 5
    }
}
