package ng1ok.yggdrasilmanager

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.*
import ng1ok.yggdrasilmanager.data.YggdrasilRepository
import ng1ok.yggdrasilmanager.data.model.HwbpEvent
import ng1ok.yggdrasilmanager.data.model.HwbpObserveParams
import ng1ok.yggdrasilmanager.ui.hwbp.HwbpViewModel
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HwbpViewModelTest {
    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var mockRepo: YggdrasilRepository
    private lateinit var vm: HwbpViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        mockRepo = mockk(relaxed = true)
        every { mockRepo.hwbpEvents } returns MutableSharedFlow<HwbpEvent>()
        vm = HwbpViewModel(mockRepo)
    }

    @After
    fun teardown() { Dispatchers.resetMain() }

    @Test
    fun addObserve_addsToList_onSuccess() = runTest {
        coEvery { mockRepo.hwbpObserve(any()) } returns Result.success(Unit)
        val params = HwbpObserveParams(1234, 0x1000L, 1, 4, 0, 4096)
        vm.addObserve(params)
        assertEquals(1, vm.observePoints.value.size)
        assertEquals(params, vm.observePoints.value.first())
    }

    @Test
    fun addObserve_doesNotAdd_onFailure() = runTest {
        coEvery { mockRepo.hwbpObserve(any()) } returns Result.failure(RuntimeException("err"))
        val params = HwbpObserveParams(1234, 0x1000L, 1, 4, 0, 4096)
        vm.addObserve(params)
        assertEquals(0, vm.observePoints.value.size)
    }

    @Test
    fun removeObserve_removesFromList_onSuccess() = runTest {
        coEvery { mockRepo.hwbpObserve(any()) } returns Result.success(Unit)
        coEvery { mockRepo.hwbpUnobserve(any(), any()) } returns Result.success(Unit)
        val params = HwbpObserveParams(1234, 0x1000L, 1, 4, 0, 4096)
        vm.addObserve(params)
        vm.removeObserve(params)
        assertEquals(0, vm.observePoints.value.size)
    }

    @Test
    fun clearEvents_emptiesEventList() = runTest {
        vm.clearEvents()
        assertEquals(0, vm.events.value.size)
    }

    @Test
    fun events_cappedAt200() = runTest {
        repeat(250) { i ->
            vm.appendEvent(HwbpEvent(i.toLong(), 0, 0, 0, "t", 1, 4, 0, 0L, 0L, 0L, LongArray(31)))
        }
        assertEquals(200, vm.events.value.size)
        assertEquals(249L, vm.events.value.last().seq)
    }
}
