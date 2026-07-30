package ng1ok.yggdrasilmanager.data

import ng1ok.yggdrasilmanager.data.model.HwbpObserveParams
import ng1ok.yggdrasilmanager.native.HwbpEventCallback
import ng1ok.yggdrasilmanager.native.YggdrasilNativeApi

/** Owns the credential domain used for every HWBP control and read operation. */
internal interface HwbpOperations {
    suspend fun observe(params: HwbpObserveParams): Int

    suspend fun unobserve(pid: Int, targetAddr: Long): Int

    fun startListening(callback: HwbpEventCallback)

    fun stopListening()
}

internal class LocalHwbpOperations(
    private val native: YggdrasilNativeApi,
) : HwbpOperations {
    override suspend fun observe(params: HwbpObserveParams): Int = native.hwbpObserve(
        params.pid,
        params.targetAddr,
        params.mode,
        params.len,
        params.flags,
        params.stackSize,
    )

    override suspend fun unobserve(pid: Int, targetAddr: Long): Int = native.hwbpUnobserve(pid, targetAddr)

    override fun startListening(callback: HwbpEventCallback) {
        native.hwbpStartListen(callback)
    }

    override fun stopListening() {
        native.hwbpStopListen()
    }
}
