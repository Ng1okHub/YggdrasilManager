package ng1ok.yggdrasilmanager.native

import ng1ok.yggdrasilmanager.data.model.DwarfUnwindResult
import ng1ok.yggdrasilmanager.data.model.HwbpBacktraceFrame

interface YggdrasilNativeApi {
    fun checkAvailable(): Boolean
    fun getVersion(): Long
    fun hwbpObserve(pid: Int, targetAddr: Long, mode: Int, len: Int, flags: Int, stackSize: Int): Int
    fun hwbpUnobserve(pid: Int, targetAddr: Long): Int
    fun hwbpStartListen(callback: HwbpEventCallback)
    fun hwbpStopListen()
    fun hideDbg(enable: Boolean, hasUid: Boolean, uid: Int): Int
    fun hwbpUnwindDwarf(
        pid: Int,
        flags: Int,
        regs: LongArray,
        pc: Long,
        sp: Long,
        pstate: Long,
        stackAddr: Long,
        stack: ByteArray,
        mapsText: String?,
    ): DwarfUnwindResult
    fun hwbpResolveLibraryTarget(
        pid: Int,
        mapsText: String,
        library: String,
        offset: Long,
    ): Result<Long>
}

interface HwbpEventCallback {
    fun onEvent(
        seq: Long,
        dropped: Long,
        tid: Int,
        tgid: Int,
        comm: String,
        mode: Int,
        len: Int,
        flags: Int,
        watchedAddr: Long,
        pc: Long,
        sp: Long,
        pstate: Long,
        stackAddr: Long,
        stackRequestedSize: Int,
        stackCopiedSize: Int,
        regs: LongArray,
        stack: ByteArray,
    )
}

object YggdrasilNative : YggdrasilNativeApi {
    init {
        System.loadLibrary("yggdrasilmanager")
    }

    external override fun checkAvailable(): Boolean
    external override fun getVersion(): Long
    external override fun hwbpObserve(pid: Int, targetAddr: Long, mode: Int, len: Int, flags: Int, stackSize: Int): Int
    external override fun hwbpUnobserve(pid: Int, targetAddr: Long): Int
    external override fun hwbpStartListen(callback: HwbpEventCallback)
    external override fun hwbpStopListen()
    external override fun hideDbg(enable: Boolean, hasUid: Boolean, uid: Int): Int

    override fun hwbpUnwindDwarf(
        pid: Int,
        flags: Int,
        regs: LongArray,
        pc: Long,
        sp: Long,
        pstate: Long,
        stackAddr: Long,
        stack: ByteArray,
        mapsText: String?,
    ): DwarfUnwindResult {
        val packed = nativeHwbpUnwindDwarf(
            pid, flags, regs, pc, sp, pstate, stackAddr, stack, mapsText
        ) ?: return DwarfUnwindResult(emptyList(), "native dwarf unwind returned null")
        if (packed.isEmpty()) {
            return DwarfUnwindResult(emptyList(), "native dwarf unwind returned empty")
        }
        val note = packed[0]
        val frames = ArrayList<HwbpBacktraceFrame>((packed.size - 1).coerceAtLeast(0))
        for (i in 1 until packed.size) {
            val parts = packed[i].split('\u0001')
            if (parts.size < 2) continue
            val framePc = parts[0].toULongOrNull(16)?.toLong() ?: continue
            val label = parts[1]
            val module = parts.getOrNull(2)?.ifBlank { null }
            val offset = parts.getOrNull(3)?.takeIf { it.isNotBlank() }?.toULongOrNull(16)?.toLong()
            frames.add(
                HwbpBacktraceFrame(
                    index = frames.size,
                    pc = framePc,
                    module = module,
                    offset = offset,
                    label = label,
                )
            )
        }
        return DwarfUnwindResult(frames, note.ifBlank { null })
    }

    override fun hwbpResolveLibraryTarget(
        pid: Int,
        mapsText: String,
        library: String,
        offset: Long,
    ): Result<Long> = runCatching {
        nativeHwbpResolveLibraryTarget(pid, mapsText, library, offset)
    }

    private external fun nativeHwbpUnwindDwarf(
        pid: Int,
        flags: Int,
        regs: LongArray,
        pc: Long,
        sp: Long,
        pstate: Long,
        stackAddr: Long,
        stack: ByteArray,
        mapsText: String?,
    ): Array<String>?

    private external fun nativeHwbpResolveLibraryTarget(
        pid: Int,
        mapsText: String,
        library: String,
        offset: Long,
    ): Long
}
