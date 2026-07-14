package ng1ok.yggdrasilmanager

import ng1ok.yggdrasilmanager.data.model.HwbpEvent
import ng1ok.yggdrasilmanager.data.model.HwbpObserveParams
import org.junit.Assert.assertEquals
import org.junit.Test

class ModelTest {
    @Test
    fun hwbpObserveParams_equality() {
        val a = HwbpObserveParams(pid = 1234, targetAddr = 0xDEADBEEFL, mode = 1, len = 4, flags = 0, stackSize = 4096)
        val b = HwbpObserveParams(pid = 1234, targetAddr = 0xDEADBEEFL, mode = 1, len = 4, flags = 0, stackSize = 4096)
        assertEquals(a, b)
    }

    @Test
    fun hwbpEvent_modeLabel() {
        val event = HwbpEvent(seq = 1L, dropped = 0L, tid = 100, tgid = 100,
            comm = "test", mode = 1, len = 4, flags = 0,
            watchedAddr = 0xABCDL, pc = 0x1000L, sp = 0x2000L, regs = LongArray(31))
        assertEquals(1, event.mode)
        assertEquals("test", event.comm)
    }
}
