package ng1ok.yggdrasilmanager.data.model

object HwbpBacktrace {
    const val MODE_FP = "fp"
    const val MODE_DWARF = "dwarf"
    private const val MAX_FRAMES = 128

    const val EVENT_FLAG_USER = 1
    const val EVENT_FLAG_COMPAT = 2
    const val EVENT_FLAG_STACK = 4

    fun normalizeMode(mode: String?): String = when (mode?.trim()?.lowercase()) {
        MODE_DWARF, "libunwindstack", "unwindstack", "1" -> MODE_DWARF
        else -> MODE_FP
    }

    fun unwindFp(
        pc: Long,
        fp: Long,
        stackAddr: Long,
        stack: ByteArray,
        flags: Int,
        maps: List<ProcMapEntry> = emptyList(),
    ): Pair<List<HwbpBacktraceFrame>, String?> {
        if (flags and EVENT_FLAG_USER == 0) {
            return emptyList<HwbpBacktraceFrame>() to "skip non-user event"
        }
        if (flags and EVENT_FLAG_COMPAT != 0) {
            return emptyList<HwbpBacktraceFrame>() to
                "skip compat-mode event: 32-bit unwind is not implemented"
        }
        if (stack.isEmpty() || stackAddr == 0L) {
            return listOf(frame(0, pc, maps)) to "stack snapshot unavailable"
        }

        val frames = ArrayList<HwbpBacktraceFrame>(16)
        frames.add(frame(0, pc, maps))

        var currentFp = fp
        var previousFp = 0L
        var index = 1
        while (index < MAX_FRAMES) {
            if (currentFp == 0L ||
                (previousFp != 0L && currentFp.toULong() <= previousFp.toULong())
            ) {
                break
            }
            val nextFp = readU64(stackAddr, stack, currentFp) ?: break
            val lr = readU64(stackAddr, stack, currentFp + 8L) ?: break
            if (lr == 0L) break
            val returnPc = if (lr.toULong() >= 4uL) (lr.toULong() - 4uL).toLong() else lr
            frames.add(frame(index, returnPc, maps))
            previousFp = currentFp
            currentFp = nextFp
            index += 1
        }
        return frames to null
    }

    private fun frame(index: Int, pc: Long, maps: List<ProcMapEntry>): HwbpBacktraceFrame {
        val symbol = ProcMaps.symbolize(maps, pc)
        return HwbpBacktraceFrame(
            index = index,
            pc = pc,
            module = symbol.module,
            offset = symbol.offset,
            label = symbol.label,
        )
    }

    fun readU64(stackAddr: Long, stack: ByteArray, addr: Long): Long? {
        val stackEnd = stackAddr.toULong() + stack.size.toULong()
        val target = addr.toULong()
        if (target < stackAddr.toULong()) return null
        if (target + 8uL < target || target + 8uL > stackEnd) return null
        val offset = (target - stackAddr.toULong()).toInt()
        var value = 0uL
        for (i in 0 until 8) {
            value = value or ((stack[offset + i].toULong() and 0xffuL) shl (8 * i))
        }
        return value.toLong()
    }
}
