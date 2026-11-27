package ng1ok.yggdrasilmanager.data.model

/** Selects the global maps policy or the policy owned by one process. */
sealed interface MapsScopeTarget {
    /** The policy shared by all processes. */
    data object Global : MapsScopeTarget

    /** The policy scoped to one positive process identifier. */
    data class Process(val pid: Int) : MapsScopeTarget {
        init {
            require(pid > 0) { "Maps PID 必須是正整數" }
        }
    }

    /** Returns the nullable PID shape required by the process adapter seam. */
    fun pidOrNull(): Int? = when (this) {
        Global -> null
        is Process -> pid
    }
}
