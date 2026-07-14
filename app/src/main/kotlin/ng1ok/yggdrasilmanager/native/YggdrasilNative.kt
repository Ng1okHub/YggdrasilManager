package ng1ok.yggdrasilmanager.native

interface YggdrasilNativeApi {
    fun checkAvailable(): Boolean
    fun hwbpObserve(pid: Int, targetAddr: Long, mode: Int, len: Int, flags: Int, stackSize: Int): Int
    fun hwbpUnobserve(pid: Int, targetAddr: Long): Int
    fun hwbpStartListen(callback: HwbpEventCallback)
    fun hwbpStopListen()
    fun hideDbg(enable: Boolean, hasUid: Boolean, uid: Int): Int
}

interface HwbpEventCallback {
    fun onEvent(
        seq: Long, dropped: Long, tid: Int, tgid: Int, comm: String,
        mode: Int, len: Int, flags: Int, watchedAddr: Long,
        pc: Long, sp: Long, regs: LongArray
    )
}

object YggdrasilNative : YggdrasilNativeApi {
    init {
        System.loadLibrary("yggdrasilmanager")
    }

    external override fun checkAvailable(): Boolean
    external override fun hwbpObserve(pid: Int, targetAddr: Long, mode: Int, len: Int, flags: Int, stackSize: Int): Int
    external override fun hwbpUnobserve(pid: Int, targetAddr: Long): Int
    external override fun hwbpStartListen(callback: HwbpEventCallback)
    external override fun hwbpStopListen()
    external override fun hideDbg(enable: Boolean, hasUid: Boolean, uid: Int): Int
}
