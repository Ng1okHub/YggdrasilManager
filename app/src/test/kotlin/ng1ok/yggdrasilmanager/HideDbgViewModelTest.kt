package ng1ok.yggdrasilmanager

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import ng1ok.yggdrasilmanager.data.YggdrasilRepository
import ng1ok.yggdrasilmanager.ui.hidedbg.HideDbgViewModel
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HideDbgViewModelTest {
    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var mockRepo: YggdrasilRepository
    private lateinit var vm: HideDbgViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        mockRepo = mockk(relaxed = true)
        vm = HideDbgViewModel(mockRepo)
    }

    @After
    fun teardown() { Dispatchers.resetMain() }

    @Test
    fun toggleGlobal_enablesAndCallsRepo() = runTest {
        coEvery { mockRepo.setHideDbg(any(), any()) } returns Result.success(Unit)
        assertFalse(vm.globalEnabled.value)
        vm.toggleGlobal()
        assertTrue(vm.globalEnabled.value)
        coVerify { mockRepo.setHideDbg(true, null) }
    }

    @Test
    fun toggleGlobal_disablesAndCallsRepo() = runTest {
        coEvery { mockRepo.setHideDbg(any(), any()) } returns Result.success(Unit)
        vm.toggleGlobal()  // enable first
        vm.toggleGlobal()  // now disable
        assertFalse(vm.globalEnabled.value)
        coVerify { mockRepo.setHideDbg(false, null) }
    }

    @Test
    fun toggleGlobal_revertsOnFailure() = runTest {
        coEvery { mockRepo.setHideDbg(any(), any()) } returns Result.failure(RuntimeException("denied"))
        vm.toggleGlobal()
        assertFalse(vm.globalEnabled.value)  // reverted back to false
        assertNotNull(vm.error.value)
    }

    @Test
    fun addUid_addsToListOnSuccess() = runTest {
        coEvery { mockRepo.setHideDbg(any(), any()) } returns Result.success(Unit)
        vm.addUid(1000)
        assertEquals(listOf(1000), vm.uidRules.value)
        coVerify { mockRepo.setHideDbg(true, 1000) }
    }

    @Test
    fun addUid_doesNotAddDuplicate() = runTest {
        coEvery { mockRepo.setHideDbg(any(), any()) } returns Result.success(Unit)
        vm.addUid(1000)
        vm.addUid(1000)
        assertEquals(1, vm.uidRules.value.size)
    }

    @Test
    fun addUid_doesNotAddOnFailure() = runTest {
        coEvery { mockRepo.setHideDbg(any(), any()) } returns Result.failure(RuntimeException("err"))
        vm.addUid(1000)
        assertEquals(0, vm.uidRules.value.size)
    }

    @Test
    fun removeUid_removesFromListOnSuccess() = runTest {
        coEvery { mockRepo.setHideDbg(any(), any()) } returns Result.success(Unit)
        vm.addUid(1000)
        vm.removeUid(1000)
        assertEquals(0, vm.uidRules.value.size)
        coVerify { mockRepo.setHideDbg(false, 1000) }
    }
}
