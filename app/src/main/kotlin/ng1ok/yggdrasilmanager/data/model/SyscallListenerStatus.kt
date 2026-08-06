package ng1ok.yggdrasilmanager.data.model

enum class SyscallListenerState(
    val wireName: String,
) {
    STOPPED("stopped"),
    STARTING("starting"),
    RUNNING("running"),
    FAILED("failed"),
}

data class SyscallListenerStatus(
    val state: SyscallListenerState = SyscallListenerState.STOPPED,
    val error: String? = null,
)
