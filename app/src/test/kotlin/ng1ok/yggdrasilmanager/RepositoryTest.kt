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
import ng1ok.yggdrasilmanager.native.SyscallEventCallback
import ng1ok.yggdrasilmanager.native.YggdrasilNativeApi
import ng1ok.yggdrasilmanager.data.model.HwbpEvent
import ng1ok.yggdrasilmanager.data.model.HwbpObserveParams
import ng1ok.yggdrasilmanager.data.model.SyscallListenerState
import ng1ok.yggdrasilmanager.data.model.SyscallObserveRule
import ng1ok.yggdrasilmanager.data.model.SyscallScope
import ng1ok.yggdrasilmanager.data.model.SyscallTracePhase
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
    fun syscallRulesReflectOnlySuccessfulNativeMutations() = runTest {
        val syscallRepo = YggdrasilRepository(
            native = mockNative,
            observerProcessId = { 7000 },
            observerUid = { 10123 },
            observerThreadIds = { setOf(7000) },
        )
        val rule = SyscallObserveRule(64, SyscallScope.TGID, 1234, SyscallTracePhase.BOTH)
        val replacement = rule.copy(phase = SyscallTracePhase.ENTER)
        every { mockNative.syscallConfigure(any(), any(), any(), any()) } returns 0

        assertTrue(syscallRepo.setSyscallRule(rule).isSuccess)
        assertEquals(listOf(rule), syscallRepo.syscallRules.value)
        assertTrue(syscallRepo.setSyscallRule(replacement).isSuccess)
        assertEquals(listOf(replacement), syscallRepo.syscallRules.value)

        every { mockNative.syscallConfigure(any(), any(), any(), any()) } returns -1
        assertTrue(syscallRepo.deleteSyscallRule(replacement).isFailure)
        assertEquals(listOf(replacement), syscallRepo.syscallRules.value)

        every { mockNative.syscallConfigure(any(), any(), any(), any()) } returns 0
        assertTrue(syscallRepo.deleteSyscallRule(replacement).isSuccess)
        assertTrue(syscallRepo.syscallRules.value.isEmpty())
    }

    @Test
    fun syscallObservationRejectsEveryScopeThatCouldTraceTheManager() = runTest {
        val safetyRepo = YggdrasilRepository(
            native = mockNative,
            observerProcessId = { 4200 },
            observerUid = { 10123 },
            observerThreadIds = { setOf(4200, 4201, 4202) },
        )
        val unsafeRules = listOf(
            SyscallObserveRule(64, SyscallScope.GLOBAL, 0, SyscallTracePhase.ENTER),
            SyscallObserveRule(64, SyscallScope.UID, 0, SyscallTracePhase.ENTER),
            SyscallObserveRule(64, SyscallScope.UID, 10123, SyscallTracePhase.ENTER),
            SyscallObserveRule(64, SyscallScope.TGID, 4200, SyscallTracePhase.ENTER),
            SyscallObserveRule(64, SyscallScope.TID, 4201, SyscallTracePhase.ENTER),
        )

        unsafeRules.forEach { rule ->
            assertTrue(safetyRepo.setSyscallRule(rule).isFailure)
        }

        verify(exactly = 0) { mockNative.syscallConfigure(any(), any(), any(), any()) }
        assertTrue(safetyRepo.syscallRules.value.isEmpty())
    }

    @Test
    fun syscallObservationForwardsTheFullUnsignedUidRange() = runTest {
        val syscallRepo = YggdrasilRepository(
            native = mockNative,
            observerProcessId = { 7000 },
            observerUid = { 10123 },
            observerThreadIds = { setOf(7000) },
        )
        val rule = SyscallObserveRule(
            64,
            SyscallScope.UID,
            SyscallObserveRule.MAX_UID,
            SyscallTracePhase.ENTER,
        )
        every { mockNative.syscallConfigure(any(), any(), any(), any()) } returns 0

        assertTrue(syscallRepo.setSyscallRule(rule).isSuccess)
        verify {
            mockNative.syscallConfigure(
                SyscallObserveRule.OP_SET,
                rule.configureFlags,
                rule.syscallNr,
                SyscallObserveRule.MAX_UID,
            )
        }
    }

    @Test
    fun syscallListenerStartFailureIsPublished() {
        every { mockNative.syscallStartListen(any()) } returns -95

        repo.startSyscallEventStream()

        assertEquals("系統呼叫監聽器已停止（errno 95）", repo.syscallListenerError.value)
        assertEquals(SyscallListenerState.FAILED, repo.syscallListenerStatus.value.state)
    }

    @Test
    fun syscallListenerRuntimeFailureIsPublished() {
        val callback = slot<SyscallEventCallback>()
        every { mockNative.syscallStartListen(capture(callback)) } returns 0

        repo.startSyscallEventStream()
        callback.captured.onListenerFailure(-108)

        assertEquals("系統呼叫監聽器已停止（errno 108）", repo.syscallListenerError.value)
        assertEquals(SyscallListenerState.FAILED, repo.syscallListenerStatus.value.state)
    }

    @Test
    fun syscallListenerStartAndRestartPublishTheirHealth() {
        every { mockNative.syscallStartListen(any()) } returns 0

        repo.startSyscallEventStream()

        assertEquals(SyscallListenerState.RUNNING, repo.syscallListenerStatus.value.state)
        repo.restartSyscallEventStream()
        assertEquals(SyscallListenerState.RUNNING, repo.syscallListenerStatus.value.state)
        verify(exactly = 1) { mockNative.syscallStopListen() }
        verify(exactly = 2) { mockNative.syscallStartListen(any()) }
    }

    @Test
    fun syscallEventsRetainValidatedCapturePayload() = runTest {
        val callback = slot<SyscallEventCallback>()
        every { mockNative.syscallStartListen(capture(callback)) } returns 0
        val payload = byteArrayOf(0x20, 0, 1, 1, 1, 2, 0, 0)
        val expectedPayload = payload.copyOf()

        repo.startSyscallEventStream()
        callback.captured.onEvent(
            seq = 1,
            dropped = 0,
            callId = 2,
            phase = 1,
            tid = 1234,
            tgid = 1234,
            syscallNr = 56,
            eventFlags = 1 shl 3,
            ruleFlags = 1 shl 1,
            targetId = 1234,
            comm = "target",
            userCookie = 0,
            argMask = 0,
            argsBefore = LongArray(6),
            argsAfter = LongArray(6),
            returnBefore = 0,
            returnAfter = 0,
            captureCount = 1,
            capturePayload = payload,
        )
        payload.fill(0)

        val event = withTimeout(1_000) { repo.syscallEvents.take(1).toList().single() }

        assertEquals(1, event.captureCount)
        assertTrue(expectedPayload.contentEquals(event.capturePayload))
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
