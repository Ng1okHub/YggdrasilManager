package ng1ok.yggdrasilmanager

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import ng1ok.yggdrasilmanager.data.YggdrasilRepository
import ng1ok.yggdrasilmanager.native.HwbpEventCallback
import ng1ok.yggdrasilmanager.native.YggdrasilNativeApi
import ng1ok.yggdrasilmanager.data.model.HwbpEvent
import ng1ok.yggdrasilmanager.data.model.HwbpObserveParams
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class RepositoryTest {
    private lateinit var mockNative: YggdrasilNativeApi
    private lateinit var repo: YggdrasilRepository

    @Before
    fun setup() {
        mockNative = mockk(relaxed = true)
        repo = YggdrasilRepository(mockNative)
    }

    @Test
    fun checkAvailable_returnsTrue_whenNativeReturnsTrue() = runTest {
        every { mockNative.checkAvailable() } returns true
        assertTrue(repo.checkAvailable())
    }

    @Test
    fun checkAvailable_returnsFalse_whenNativeReturnsFalse() = runTest {
        every { mockNative.checkAvailable() } returns false
        assertFalse(repo.checkAvailable())
    }

    @Test
    fun getVersion_returnsPackedNativeVersion() = runTest {
        every { mockNative.getVersion() } returns 0x00010100L

        assertEquals(0x00010100L, repo.getVersion())
    }

    @Test
    fun hwbpObserve_returnsSuccess_whenNativeReturns0() = runTest {
        every { mockNative.hwbpObserve(any(), any(), any(), any(), any(), any()) } returns 0
        val params = HwbpObserveParams(pid = 1234, targetAddr = 0x1000L, mode = 1, len = 4, flags = 0, stackSize = 4096)
        val result = repo.hwbpObserve(params)
        assertTrue(result.isSuccess)
    }

    @Test
    fun hwbpObserve_returnsFailure_whenNativeReturnsNegative() = runTest {
        every { mockNative.hwbpObserve(any(), any(), any(), any(), any(), any()) } returns -13
        val params = HwbpObserveParams(pid = 1234, targetAddr = 0x1000L, mode = 1, len = 4, flags = 0, stackSize = 4096)
        val result = repo.hwbpObserve(params)
        assertTrue(result.isFailure)
    }

    @Test
    fun setHideDbg_global_callsNativeWithoutUid() = runTest {
        every { mockNative.hideDbg(any(), any(), any()) } returns 0
        repo.setHideDbg(enable = true, uid = null)
        verify { mockNative.hideDbg(enable = true, hasUid = false, uid = 0) }
    }

    @Test
    fun setHideDbg_perUid_callsNativeWithUid() = runTest {
        every { mockNative.hideDbg(any(), any(), any()) } returns 0
        repo.setHideDbg(enable = true, uid = 10086)
        verify { mockNative.hideDbg(enable = true, hasUid = true, uid = 10086) }
    }

    @Test
    fun hwbpEventsQueuedBeforeCollectionAreDeliveredInOrder() = runTest {
        val callback = slot<HwbpEventCallback>()
        every { mockNative.hwbpStartListen(capture(callback)) } returns Unit

        repo.startEventStream()
        (1L..5L).forEach { seq ->
            callback.captured.onEvent(
                seq = seq,
                dropped = 0,
                tid = 1234,
                tgid = 1234,
                comm = "target",
                mode = 1,
                len = 4,
                flags = 0,
                watchedAddr = 0x1000,
                pc = 0x2000,
                sp = 0x3000,
                pstate = 0,
                stackAddr = 0,
                stackRequestedSize = 0,
                stackCopiedSize = 0,
                regs = longArrayOf(),
                stack = byteArrayOf(),
            )
        }

        val events = withTimeout(1_000) {
            repo.hwbpEvents.take(5).toList()
        }

        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), events.map(HwbpEvent::seq))
    }

    @Test
    fun concurrentCollectorsDoNotSplitQueuedEvents() = runTest {
        val callback = slot<HwbpEventCallback>()
        every { mockNative.hwbpStartListen(capture(callback)) } returns Unit
        repo.startEventStream()

        val firstReceived = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            repo.hwbpEvents.take(1).onEach {
                firstReceived.complete(Unit)
                releaseFirst.await()
            }.toList()
        }

        callback.captured.onEvent(
            seq = 1,
            dropped = 0,
            tid = 1234,
            tgid = 1234,
            comm = "target",
            mode = 1,
            len = 4,
            flags = 0,
            watchedAddr = 0x1000,
            pc = 0x2000,
            sp = 0x3000,
            pstate = 0,
            stackAddr = 0,
            stackRequestedSize = 0,
            stackCopiedSize = 0,
            regs = longArrayOf(),
            stack = byteArrayOf(),
        )
        firstReceived.await()

        val second = async(start = CoroutineStart.UNDISPATCHED) {
            repo.hwbpEvents.take(1).toList()
        }
        callback.captured.onEvent(
            seq = 2,
            dropped = 0,
            tid = 1234,
            tgid = 1234,
            comm = "target",
            mode = 1,
            len = 4,
            flags = 0,
            watchedAddr = 0x1000,
            pc = 0x2000,
            sp = 0x3000,
            pstate = 0,
            stackAddr = 0,
            stackRequestedSize = 0,
            stackCopiedSize = 0,
            regs = longArrayOf(),
            stack = byteArrayOf(),
        )

        assertFalse(second.isCompleted)
        releaseFirst.complete(Unit)
        assertEquals(listOf(1L), first.await().map(HwbpEvent::seq))
        assertEquals(listOf(2L), second.await().map(HwbpEvent::seq))
    }
}
