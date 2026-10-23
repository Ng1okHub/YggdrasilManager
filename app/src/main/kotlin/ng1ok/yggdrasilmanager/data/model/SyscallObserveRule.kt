package ng1ok.yggdrasilmanager.data.model

private const val ARM64_SYSCALL_COUNT = 460

enum class SyscallScope(
    val wireName: String,
) {
    GLOBAL("global"),
    TGID("tgid"),
    TID("tid"),
    UID("uid"),
}

enum class SyscallTracePhase(
    val wireName: String,
) {
    ENTER("enter"),
    EXIT("exit"),
    BOTH("both");

    val tracesEnter: Boolean
        get() = this != EXIT

    val tracesExit: Boolean
        get() = this != ENTER
}

/** A read-only syscall tracing rule owned by the web control surface. */
data class SyscallObserveRule(
    val syscallNr: Int,
    val scope: SyscallScope,
    val targetId: Long,
    val phase: SyscallTracePhase,
    val captureBacktrace: Boolean = false,
    val backtraceStackSize: Int = 0,
) {
    init {
        require(syscallNr in 0 until ARM64_SYSCALL_COUNT) {
            "Syscall編號必須介於 0 至 ${ARM64_SYSCALL_COUNT - 1} 之間"
        }
        when (scope) {
            SyscallScope.GLOBAL -> require(targetId == 0L) {
                "全域範圍的目標 ID 必須為 0"
            }
            // UID 0 remains representable so existing root-UID selectors can be removed.
            // New observation requests reject it before reaching the native bridge.
            SyscallScope.UID -> require(targetId in 0L..MAX_UID) {
                "UID 必須介於 0 至 $MAX_UID 之間"
            }
            SyscallScope.TGID, SyscallScope.TID -> require(targetId in 1L..MAX_PROCESS_ID) {
                "${scope.displayName}必須介於 1 至 $MAX_PROCESS_ID 之間"
            }
        }
        require(!(Arm64SyscallCatalog.isEnterOnly(syscallNr) && phase.tracesExit)) {
            "exit 與 exit_group 僅支援進入階段的觀察"
        }
        require(backtraceStackSize in 0..SyscallBacktrace.MAX_STACK_SIZE) {
            "backtrace stack size must be between 0 and ${SyscallBacktrace.MAX_STACK_SIZE}"
        }
        require(captureBacktrace || backtraceStackSize == 0) {
            "backtraceStackSize requires backtrace"
        }
    }

    val configureFlags: Int
        get() {
            var flags = 0
            if (phase.tracesEnter) flags = flags or FLAG_TRACE_ENTER
            if (phase.tracesExit) flags = flags or FLAG_TRACE_EXIT
            if (captureBacktrace) flags = flags or FLAG_CAPTURE_BACKTRACE
            return flags or selectorFlags
        }

    val deleteFlags: Int
        get() = selectorFlags

    private val selectorFlags: Int
        get() = when (scope) {
            SyscallScope.GLOBAL, SyscallScope.TGID -> 0
            SyscallScope.TID -> FLAG_SINGLE_THREAD
            SyscallScope.UID -> FLAG_TARGET_UID
        }

    private val SyscallScope.displayName: String
        get() = when (this) {
            SyscallScope.TGID -> "Process（TGID）"
            SyscallScope.TID -> "Thread（TID）"
            SyscallScope.GLOBAL -> "全域範圍"
            SyscallScope.UID -> "UID"
        }

    companion object {
        const val FLAG_SINGLE_THREAD = 1 shl 0
        const val FLAG_TRACE_ENTER = 1 shl 1
        const val FLAG_TRACE_EXIT = 1 shl 2
        const val FLAG_TARGET_UID = 1 shl 4
        const val FLAG_CAPTURE_BACKTRACE = 1 shl 5
        const val OP_SET = 1
        const val OP_DELETE = 2
        const val MAX_UID = 0xffff_ffffL
        const val MAX_PROCESS_ID = 2_147_483_647L
    }
}
