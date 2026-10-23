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
        require(!(syscallNr in EXIT_ONLY_UNSUPPORTED && phase.tracesExit)) {
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

        private val EXIT_ONLY_UNSUPPORTED = setOf(93, 94)
    }
}

object Arm64SyscallCatalog {
    private val names = mapOf(
        20 to "epoll_create1",
        21 to "epoll_ctl",
        22 to "epoll_pwait",
        23 to "dup",
        24 to "dup3",
        25 to "fcntl",
        29 to "ioctl",
        34 to "mkdirat",
        35 to "unlinkat",
        36 to "symlinkat",
        37 to "linkat",
        38 to "renameat",
        40 to "mount",
        41 to "pivot_root",
        43 to "statfs",
        46 to "ftruncate",
        48 to "faccessat",
        49 to "chdir",
        50 to "fchdir",
        52 to "fchmod",
        53 to "fchmodat",
        54 to "fchownat",
        55 to "fchown",
        56 to "openat",
        57 to "close",
        59 to "pipe2",
        61 to "getdents64",
        62 to "lseek",
        63 to "read",
        64 to "write",
        65 to "readv",
        66 to "writev",
        67 to "pread64",
        68 to "pwrite64",
        69 to "preadv",
        70 to "pwritev",
        71 to "sendfile",
        72 to "pselect6",
        73 to "ppoll",
        78 to "readlinkat",
        79 to "newfstatat",
        80 to "fstat",
        88 to "utimensat",
        93 to "exit",
        94 to "exit_group",
        98 to "futex",
        101 to "nanosleep",
        113 to "clock_gettime",
        129 to "kill",
        131 to "tgkill",
        132 to "sigaltstack",
        134 to "rt_sigaction",
        135 to "rt_sigprocmask",
        139 to "rt_sigreturn",
        160 to "uname",
        167 to "prctl",
        169 to "gettimeofday",
        172 to "getpid",
        173 to "getppid",
        174 to "getuid",
        175 to "geteuid",
        176 to "getgid",
        177 to "getegid",
        178 to "gettid",
        198 to "socket",
        200 to "bind",
        201 to "listen",
        202 to "accept",
        203 to "connect",
        204 to "getsockname",
        205 to "getpeername",
        206 to "sendto",
        207 to "recvfrom",
        208 to "setsockopt",
        209 to "getsockopt",
        210 to "shutdown",
        211 to "sendmsg",
        212 to "recvmsg",
        214 to "brk",
        215 to "munmap",
        220 to "clone",
        221 to "execve",
        222 to "mmap",
        226 to "mprotect",
        242 to "accept4",
        260 to "wait4",
        261 to "prlimit64",
        278 to "getrandom",
        281 to "execveat",
        291 to "statx",
        437 to "openat2",
    )

    fun nameFor(syscallNr: Int): String = names[syscallNr] ?: "syscall_$syscallNr"
}
