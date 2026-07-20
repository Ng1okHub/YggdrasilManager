package ng1ok.yggdrasilmanager.data.model

import ng1ok.yggdrasilmanager.native.YggdrasilNativeApi

object HwbpEventEnricher {
    fun enrich(
        event: HwbpEvent,
        mode: String,
        native: YggdrasilNativeApi,
        mapsSource: ProcMapsSource = CachedFileProcMapsSource,
    ): HwbpEvent {
        val normalized = HwbpBacktrace.normalizeMode(mode)
        if (event.flags and HwbpBacktrace.EVENT_FLAG_USER == 0) {
            return event.copy(
                backtrace = emptyList(),
                backtraceMode = normalized,
                backtraceNote = "skip non-user event",
            )
        }
        if (event.flags and HwbpBacktrace.EVENT_FLAG_COMPAT != 0) {
            return event.copy(
                backtrace = emptyList(),
                backtraceMode = normalized,
                backtraceNote = "skip compat-mode event: 32-bit unwind is not implemented",
            )
        }

        val pid = if (event.tgid != 0) event.tgid else event.tid
        val snapshot = when {
            pid <= 0 -> ProcMapsSnapshot(null, emptyList())
            mapsSource is ProcMapsSnapshotSource -> mapsSource.readSnapshot(pid)
            else -> {
                val text = mapsSource.readMaps(pid)
                ProcMapsSnapshot(text, text?.let(ProcMaps::parse).orEmpty())
            }
        }
        val mapsText = snapshot.text
        val maps = snapshot.entries
        val fp = event.regs.getOrNull(29) ?: 0L

        return if (normalized == HwbpBacktrace.MODE_DWARF) {
            enrichDwarf(event, pid, fp, maps, mapsText, native)
        } else {
            val (frames, note) = HwbpBacktrace.unwindFp(
                pc = event.pc,
                fp = fp,
                stackAddr = event.stackAddr,
                stack = event.stack,
                flags = event.flags,
                maps = maps,
            )
            event.copy(
                backtrace = frames,
                backtraceMode = HwbpBacktrace.MODE_FP,
                backtraceNote = note,
            )
        }
    }

    private fun enrichDwarf(
        event: HwbpEvent,
        pid: Int,
        fp: Long,
        maps: List<ProcMapEntry>,
        mapsText: String?,
        native: YggdrasilNativeApi,
    ): HwbpEvent {
        if (pid <= 0) {
            return event.copy(
                backtrace = emptyList(),
                backtraceMode = HwbpBacktrace.MODE_DWARF,
                backtraceNote = "skip event without pid",
            )
        }
        if (mapsText.isNullOrBlank()) {
            // Still try FP rather than a silent empty dwarf result.
            val (frames, note) = HwbpBacktrace.unwindFp(
                pc = event.pc,
                fp = fp,
                stackAddr = event.stackAddr,
                stack = event.stack,
                flags = event.flags,
                maps = maps,
            )
            return event.copy(
                backtrace = frames,
                backtraceMode = HwbpBacktrace.MODE_DWARF,
                backtraceNote = listOfNotNull(
                    "dwarf unavailable: could not read /proc/$pid/maps (need root)",
                    "fell back to fp",
                    note,
                ).joinToString("; "),
            )
        }

        val dwarf = runCatching {
            native.hwbpUnwindDwarf(
                pid = pid,
                flags = event.flags,
                regs = event.regs,
                pc = event.pc,
                sp = event.sp,
                pstate = event.pstate,
                stackAddr = event.stackAddr,
                stack = event.stack,
                mapsText = mapsText,
            )
        }.getOrElse { error ->
            val (frames, note) = HwbpBacktrace.unwindFp(
                pc = event.pc,
                fp = fp,
                stackAddr = event.stackAddr,
                stack = event.stack,
                flags = event.flags,
                maps = maps,
            )
            return event.copy(
                backtrace = frames,
                backtraceMode = HwbpBacktrace.MODE_DWARF,
                backtraceNote = "dwarf failed (${error.message}); fell back to fp" +
                    (note?.let { "; $it" } ?: ""),
            )
        }

        val dwarfError = dwarf.error?.takeIf { it.isNotBlank() }
        if (dwarf.frames.isEmpty() || dwarfError != null) {
            val (frames, note) = HwbpBacktrace.unwindFp(
                pc = event.pc,
                fp = fp,
                stackAddr = event.stackAddr,
                stack = event.stack,
                flags = event.flags,
                maps = maps,
            )
            if (dwarf.frames.isEmpty() || frames.size > dwarf.frames.size) {
                return event.copy(
                    backtrace = frames,
                    backtraceMode = HwbpBacktrace.MODE_DWARF,
                    backtraceNote = listOfNotNull(
                        dwarfError ?: "libunwindstack produced no frames",
                        "fell back to fp",
                        note,
                    ).joinToString("; "),
                )
            }
        }

        return event.copy(
            backtrace = dwarf.frames,
            backtraceMode = HwbpBacktrace.MODE_DWARF,
            backtraceNote = dwarfError,
        )
    }
}

data class DwarfUnwindResult(
    val frames: List<HwbpBacktraceFrame>,
    val error: String? = null,
)
