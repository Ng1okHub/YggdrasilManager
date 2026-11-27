package ng1ok.yggdrasilmanager

import io.mockk.mockk
import java.nio.ByteBuffer
import java.nio.ByteOrder
import ng1ok.yggdrasilmanager.data.model.ProcMapsSource
import ng1ok.yggdrasilmanager.data.model.SyscallBacktrace
import ng1ok.yggdrasilmanager.data.model.SyscallBacktraceEnricher
import ng1ok.yggdrasilmanager.native.YggdrasilNativeApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyscallBacktraceTest {
    @Test
    fun fpUnwindUsesTheSyscallSnapshotAndSymbols() {
        val stack = ByteBuffer.allocate(0x30).order(ByteOrder.LITTLE_ENDIAN)
            .putLong(0x1010L)
            .putLong(0x2004L)
            .putLong(0L)
            .putLong(0x3004L)
            .array()
        val regs = LongArray(34).also { it[29] = 0x1000L; it[32] = 0x1111L }
        val snapshot = SyscallBacktrace(
            flags = SyscallBacktrace.EVENT_FLAG_USER or SyscallBacktrace.EVENT_FLAG_STACK,
            seq = 1,
            dropped = 0,
            callId = 2,
            phase = 1,
            tid = 42,
            tgid = 42,
            syscallNr = 64,
            stackRequestedSize = stack.size,
            stackCopiedSize = stack.size,
            stackAddr = 0x1000L,
            regs = regs,
            stack = stack,
        )

        val enriched = SyscallBacktraceEnricher.enrich(
            snapshot = snapshot,
            mode = SyscallBacktrace.MODE_FP,
            native = mockk<YggdrasilNativeApi>(relaxed = true),
            mapsSource = ProcMapsSource {
                "1000-4000 r-xp 000000 00:00 0 /system/lib64/libx.so"
            },
        )

        assertNull(enriched.note)
        assertEquals(listOf(0x1111L, 0x2000L, 0x3000L), enriched.frames.map { it.pc })
        assertTrue(enriched.frames.first().label.contains("libx.so"))
    }
}
