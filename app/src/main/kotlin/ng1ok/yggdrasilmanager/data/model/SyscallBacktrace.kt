package ng1ok.yggdrasilmanager.data.model

import ng1ok.yggdrasilmanager.native.YggdrasilNativeApi

data class SyscallBacktraceFrame(
    val index: Int,
    val pc: Long,
    val module: String? = null,
    val offset: Long? = null,
    val label: String,
)

/** Raw syscall backtrace record drained from Yggdrasil's dedicated ring. */
data class SyscallBacktrace(
    val flags: Int,
    val seq: Long,
    val dropped: Long,
    val callId: Long,
    val phase: Int,
    val tid: Int,
    val tgid: Int,
    val syscallNr: Int,
    val stackRequestedSize: Int,
    val stackCopiedSize: Int,
    val stackAddr: Long,
    val regs: LongArray,
    val stack: ByteArray,
    val frames: List<SyscallBacktraceFrame> = emptyList(),
    val mode: String = MODE_FP,
    val note: String? = null,
) {
    val pc: Long get() = regs.getOrNull(REG_PC) ?: 0L
    val sp: Long get() = regs.getOrNull(REG_SP) ?: 0L
    val pstate: Long get() = regs.getOrNull(REG_PSTATE) ?: 0L

    companion object {
        const val MODE_FP = "fp"
        const val MODE_DWARF = "dwarf"
        const val EVENT_FLAG_USER = 1
        const val EVENT_FLAG_COMPAT = 2
        const val EVENT_FLAG_STACK = 4
        const val EVENT_FLAG_PARTIAL = 8
        const val REG_SP = 31
        const val REG_PC = 32
        const val REG_PSTATE = 33
        const val MAX_STACK_SIZE = 65_536
        const val DEFAULT_STACK_SIZE = 4_096
        const val MAX_FRAMES = 128

        fun normalizeMode(mode: String?): String = when (mode?.trim()?.lowercase()) {
            MODE_DWARF, "libunwindstack", "unwindstack", "1" -> MODE_DWARF
            else -> MODE_FP
        }
    }
}

object SyscallBacktraceEnricher {
    fun enrich(
        snapshot: SyscallBacktrace,
        mode: String,
        native: YggdrasilNativeApi,
        mapsSource: ProcMapsSource = CachedFileProcMapsSource,
    ): SyscallBacktrace {
        val normalized = SyscallBacktrace.normalizeMode(mode)
        if (snapshot.flags and SyscallBacktrace.EVENT_FLAG_USER == 0) {
            return snapshot.copy(mode = normalized, frames = emptyList(), note = "skip non-user event")
        }
        if (snapshot.flags and SyscallBacktrace.EVENT_FLAG_COMPAT != 0) {
            return snapshot.copy(
                mode = normalized,
                frames = emptyList(),
                note = "skip compat-mode event: 32-bit unwind is not implemented",
            )
        }

        val pid = if (snapshot.tgid != 0) snapshot.tgid else snapshot.tid
        val mapsSnapshot = when {
            pid <= 0 -> ProcMapsSnapshot(null, emptyList())
            mapsSource is ProcMapsSnapshotSource -> mapsSource.readSnapshot(pid)
            else -> {
                val text = mapsSource.readMaps(pid)
                ProcMapsSnapshot(text, text?.let(ProcMaps::parse).orEmpty())
            }
        }
        val mapsText = mapsSnapshot.text
        val maps = mapsSnapshot.entries

        return if (normalized == SyscallBacktrace.MODE_DWARF) {
            enrichDwarf(snapshot, pid, maps, mapsText, native)
        } else {
            val (frames, note) = unwindFp(snapshot, maps)
            snapshot.copy(mode = SyscallBacktrace.MODE_FP, frames = frames, note = note)
        }
    }

    private fun unwindFp(
        snapshot: SyscallBacktrace,
        maps: List<ProcMapEntry>,
    ): Pair<List<SyscallBacktraceFrame>, String?> {
        if (snapshot.stack.isEmpty() || snapshot.stackAddr == 0L) {
            return listOf(frame(0, snapshot.pc, maps)) to "stack snapshot unavailable"
        }
        val frames = ArrayList<SyscallBacktraceFrame>(16)
        frames += frame(0, snapshot.pc, maps)
        var currentFp = snapshot.regs.getOrNull(29) ?: 0L
        var previousFp = 0L
        var index = 1
        while (index < SyscallBacktrace.MAX_FRAMES && currentFp != 0L &&
            (previousFp == 0L || currentFp.toULong() > previousFp.toULong())
        ) {
            val nextFp = readU64(snapshot.stackAddr, snapshot.stack, currentFp) ?: break
            val lr = readU64(snapshot.stackAddr, snapshot.stack, currentFp + 8L) ?: break
            if (lr == 0L) break
            val returnPc = if (lr.toULong() >= 4uL) (lr.toULong() - 4uL).toLong() else lr
            frames += frame(index, returnPc, maps)
            previousFp = currentFp
            currentFp = nextFp
            index++
        }
        return frames to null
    }

    private fun enrichDwarf(
        snapshot: SyscallBacktrace,
        pid: Int,
        maps: List<ProcMapEntry>,
        mapsText: String?,
        native: YggdrasilNativeApi,
    ): SyscallBacktrace {
        if (pid <= 0) {
            return snapshot.copy(mode = SyscallBacktrace.MODE_DWARF, frames = emptyList(), note = "skip event without pid")
        }
        if (mapsText.isNullOrBlank()) {
            val (frames, note) = unwindFp(snapshot, maps)
            return snapshot.copy(
                mode = SyscallBacktrace.MODE_DWARF,
                frames = frames,
                note = listOfNotNull(
                    "dwarf unavailable: could not read /proc/$pid/maps (need root)",
                    "fell back to fp",
                    note,
                ).joinToString("; "),
            )
        }

        val dwarf = runCatching {
            native.hwbpUnwindDwarf(
                pid = pid,
                flags = snapshot.flags,
                regs = snapshot.regs,
                pc = snapshot.pc,
                sp = snapshot.sp,
                pstate = snapshot.pstate,
                stackAddr = snapshot.stackAddr,
                stack = snapshot.stack,
                mapsText = mapsText,
            )
        }.getOrElse { error ->
            val (frames, note) = unwindFp(snapshot, maps)
            return snapshot.copy(
                mode = SyscallBacktrace.MODE_DWARF,
                frames = frames,
                note = "dwarf failed (${error.message ?: error.javaClass.simpleName}); fell back to fp" +
                    (note?.let { "; $it" } ?: ""),
            )
        }
        val dwarfError = dwarf.error?.takeIf(String::isNotBlank)
        if (dwarf.frames.isEmpty() || dwarfError != null) {
            val (frames, note) = unwindFp(snapshot, maps)
            return snapshot.copy(
                mode = SyscallBacktrace.MODE_DWARF,
                frames = frames,
                note = listOfNotNull(
                    dwarfError ?: "libunwindstack produced no frames",
                    "fell back to fp",
                    note,
                ).joinToString("; "),
            )
        }
        return snapshot.copy(
            mode = SyscallBacktrace.MODE_DWARF,
            frames = dwarf.frames.mapIndexed { index, frame ->
                SyscallBacktraceFrame(index, frame.pc, frame.module, frame.offset, frame.label)
            },
            note = null,
        )
    }

    private fun frame(index: Int, pc: Long, maps: List<ProcMapEntry>): SyscallBacktraceFrame {
        val symbol = ProcMaps.symbolize(maps, pc)
        return SyscallBacktraceFrame(index, pc, symbol.module, symbol.offset, symbol.label)
    }

    fun readU64(stackAddr: Long, stack: ByteArray, addr: Long): Long? {
        val stackEnd = stackAddr.toULong() + stack.size.toULong()
        val target = addr.toULong()
        if (target < stackAddr.toULong() || target + 8uL < target || target + 8uL > stackEnd) {
            return null
        }
        val offset = (target - stackAddr.toULong()).toInt()
        var value = 0uL
        for (i in 0 until 8) value = value or ((stack[offset + i].toULong() and 0xffuL) shl (8 * i))
        return value.toLong()
    }
}
