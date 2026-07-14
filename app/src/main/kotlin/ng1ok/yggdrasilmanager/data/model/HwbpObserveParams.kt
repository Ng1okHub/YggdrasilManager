package ng1ok.yggdrasilmanager.data.model

data class HwbpObserveParams(
    val pid: Int,
    val targetAddr: Long,
    val mode: Int,
    val len: Int,
    val flags: Int,
    val stackSize: Int
)
