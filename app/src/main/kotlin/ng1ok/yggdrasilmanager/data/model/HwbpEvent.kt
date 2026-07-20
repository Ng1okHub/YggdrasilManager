package ng1ok.yggdrasilmanager.data.model

data class HwbpBacktraceFrame(
    val index: Int,
    val pc: Long,
    val module: String? = null,
    val offset: Long? = null,
    val label: String,
)

data class HwbpEvent(
    val seq: Long,
    val dropped: Long,
    val tid: Int,
    val tgid: Int,
    val comm: String,
    val mode: Int,
    val len: Int,
    val flags: Int,
    val watchedAddr: Long,
    val pc: Long,
    val sp: Long,
    val regs: LongArray,
    val pstate: Long = 0L,
    val stackAddr: Long = 0L,
    val stackRequestedSize: Int = 0,
    val stackCopiedSize: Int = 0,
    val stack: ByteArray = ByteArray(0),
    val backtrace: List<HwbpBacktraceFrame> = emptyList(),
    val backtraceMode: String = HwbpBacktrace.MODE_FP,
    val backtraceNote: String? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HwbpEvent) return false
        return seq == other.seq && tid == other.tid && tgid == other.tgid
    }

    override fun hashCode(): Int = seq.hashCode()
}