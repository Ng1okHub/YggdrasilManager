package ng1ok.yggdrasilmanager

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import ng1ok.yggdrasilmanager.data.YggdrasilRepository
import ng1ok.yggdrasilmanager.ui.home.HomeUiState
import ng1ok.yggdrasilmanager.ui.home.HomeViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var mockRepo: YggdrasilRepository
    private lateinit var vm: HomeViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        mockRepo = mockk(relaxed = true)
        vm = HomeViewModel(mockRepo)
    }

    @After
    fun teardown() { Dispatchers.resetMain() }

    @Test
    fun refresh_setsRootGranted_whenShellGranted() = runTest {
        coEvery { mockRepo.checkAvailable() } returns true
        vm.refresh(rootGranted = true)
        val state = vm.uiState.value
        assertEquals(true, state.rootGranted)
        assertEquals(true, state.moduleAvailable)
    }

    @Test
    fun refresh_setsModuleUnavailable_whenCheckFails() = runTest {
        coEvery { mockRepo.checkAvailable() } returns false
        vm.refresh(rootGranted = true)
        assertEquals(false, vm.uiState.value.moduleAvailable)
    }

    @Test
    fun initialState_isLoading() {
        assertEquals(HomeUiState(), vm.uiState.value)
    }
}
