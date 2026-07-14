package ng1ok.yggdrasilmanager.data.model

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
    val regs: LongArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HwbpEvent) return false
        return seq == other.seq && tid == other.tid && tgid == other.tgid
    }
    override fun hashCode(): Int = seq.hashCode()
}
