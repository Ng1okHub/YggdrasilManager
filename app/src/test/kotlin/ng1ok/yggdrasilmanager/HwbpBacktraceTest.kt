package ng1ok.yggdrasilmanager

import io.mockk.every
import io.mockk.mockk
import ng1ok.yggdrasilmanager.data.model.HwbpBacktrace
import ng1ok.yggdrasilmanager.data.model.CachingProcMapsSource
import ng1ok.yggdrasilmanager.data.model.DwarfUnwindResult
import ng1ok.yggdrasilmanager.data.model.HwbpBacktraceFrame
import ng1ok.yggdrasilmanager.data.model.HwbpEvent
import ng1ok.yggdrasilmanager.data.model.HwbpEventEnricher
import ng1ok.yggdrasilmanager.data.model.ProcMaps
import ng1ok.yggdrasilmanager.data.model.ProcMapsSource
import ng1ok.yggdrasilmanager.native.YggdrasilNativeApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class HwbpBacktraceTest {
    @Test
    fun procMapsCacheReusesTextAndParsedEntriesUntilExpiry() {
        var reads = 0
        var now = 0L
        val source = CachingProcMapsSource(
            delegate = ProcMapsSource {
                reads += 1
                "1000-2000 r-xp 000000 00:00 0 /system/lib64/libx.so"
            },
            ttlNanos = 100,
            clockNanos = { now },
        )

        assertEquals(1, source.readSnapshot(42).entries.size)
        assertEquals(1, source.readSnapshot(42).entries.size)
        assertEquals(1, reads)

        now = 100
        assertEquals(1, source.readSnapshot(42).entries.size)
        assertEquals(2, reads)
    }

    @Test
    fun resolveLibraryTargetUsesLoadBias() {
        val maps = ProcMaps.parse(
            """
            1000-2000 r-xp 001000 00:00 0 /data/app/libdemo.so
            2000-3000 r--p 002000 00:00 0 /data/app/libdemo.so
            """.trimIndent()
        )
        // base = 0x1000 - 0x1000 = 0; target = 0 + 0x40
        assertEquals(0x40L, ProcMaps.resolveLibraryTarget(maps, "libdemo.so", 0x40))
    }

    @Test
    fun fpUnwindWalksFrameChainFromSnapshot() {
        val stackAddr = 0x1000L
        val fp0 = 0x1000L
        val fp1 = 0x1010L
        val buffer = ByteBuffer.allocate(0x30).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putLong(fp1)
        buffer.putLong(0x2004L)
        buffer.putLong(0)
        buffer.putLong(0x3004L)
        val stack = buffer.array()

        val maps = ProcMaps.parse(
            """
            1000-4000 r-xp 000000 00:00 0 /system/lib64/libx.so
            """.trimIndent()
        )

        val (frames, note) = HwbpBacktrace.unwindFp(
            pc = 0x1111,
            fp = fp0,
            stackAddr = stackAddr,
            stack = stack,
            flags = HwbpBacktrace.EVENT_FLAG_USER or HwbpBacktrace.EVENT_FLAG_STACK,
            maps = maps,
        )

        assertEquals(null, note)
        assertEquals(3, frames.size)
        assertEquals(0x1111L, frames[0].pc)
        assertEquals(0x2000L, frames[1].pc) // lr-4
        assertEquals(0x3000L, frames[2].pc)
        assertTrue(frames[0].label.contains("libx.so"))
    }

    @Test
    fun partialDwarfUsesFpWhenFpProducesMoreFrames() {
        val native = nativeReturning(
            DwarfUnwindResult(dwarfFrames(2), "libunwindstack stopped: invalid memory")
        )

        val enriched = HwbpEventEnricher.enrich(
            event = eventWithThreeFpFrames(),
            mode = HwbpBacktrace.MODE_DWARF,
            native = native,
            mapsSource = ProcMapsSource { MAPS_TEXT },
        )

        assertEquals(listOf(0x1111L, 0x2000L, 0x3000L), enriched.backtrace.map { it.pc })
        assertTrue(enriched.backtraceNote.orEmpty().contains("invalid memory"))
        assertTrue(enriched.backtraceNote.orEmpty().contains("fell back to fp"))
    }

    @Test
    fun partialDwarfKeepsDwarfWhenFpIsNotLonger() {
        val dwarfFrames = dwarfFrames(3)
        val native = nativeReturning(
            DwarfUnwindResult(dwarfFrames, "libunwindstack stopped: invalid memory")
        )

        val enriched = HwbpEventEnricher.enrich(
            event = eventWithThreeFpFrames(),
            mode = HwbpBacktrace.MODE_DWARF,
            native = native,
            mapsSource = ProcMapsSource { MAPS_TEXT },
        )

        assertEquals(dwarfFrames, enriched.backtrace)
        assertEquals("libunwindstack stopped: invalid memory", enriched.backtraceNote)
    }

    private fun nativeReturning(result: DwarfUnwindResult): YggdrasilNativeApi =
        mockk<YggdrasilNativeApi>(relaxed = true) {
            every {
                hwbpUnwindDwarf(
                    any(), any(), any(), any(), any(), any(), any(), any(), any()
                )
            } returns result
        }

    private fun eventWithThreeFpFrames(): HwbpEvent {
        val regs = LongArray(31)
        regs[29] = 0x1000L
        val stack = ByteBuffer.allocate(0x30).order(ByteOrder.LITTLE_ENDIAN)
            .putLong(0x1010L)
            .putLong(0x2004L)
            .putLong(0L)
            .putLong(0x3004L)
            .array()
        return HwbpEvent(
            seq = 1,
            dropped = 0,
            tid = 42,
            tgid = 42,
            comm = "test",
            mode = 1,
            len = 8,
            flags = HwbpBacktrace.EVENT_FLAG_USER or HwbpBacktrace.EVENT_FLAG_STACK,
            watchedAddr = 0x1800L,
            pc = 0x1111L,
            sp = 0x1000L,
            regs = regs,
            stackAddr = 0x1000L,
            stackRequestedSize = stack.size,
            stackCopiedSize = stack.size,
            stack = stack,
        )
    }

    private fun dwarfFrames(count: Int): List<HwbpBacktraceFrame> =
        List(count) { index ->
            HwbpBacktraceFrame(
                index = index,
                pc = 0x5000L + index * 4L,
                label = "dwarf-$index",
            )
        }

    private companion object {
        const val MAPS_TEXT = "1000-6000 r-xp 000000 00:00 0 /system/lib64/libx.so"
    }
}
