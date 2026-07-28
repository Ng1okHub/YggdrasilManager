package ng1ok.yggdrasilmanager.data.model

data class HwbpObserveParams(
    val pid: Int,
    val targetAddr: Long,
    val mode: Int,
    val len: Int,
    val flags: Int,
    val stackSize: Int,
    val lib: String? = null,
    val offset: Long? = null,
    /** The package used to resolve this runtime PID, when one was supplied. */
    val packageName: String? = null,
) {
    val displayTarget: String
        get() = if (!lib.isNullOrBlank() && offset != null) {
            "$lib+0x${java.lang.Long.toUnsignedString(offset, 16)}"
        } else {
            "0x${java.lang.Long.toUnsignedString(targetAddr, 16)}"
        }
}
