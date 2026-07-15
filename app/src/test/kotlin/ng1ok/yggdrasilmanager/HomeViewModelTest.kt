package ng1ok.yggdrasilmanager

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import ng1ok.yggdrasilmanager.data.YggdrasilRepository
import ng1ok.yggdrasilmanager.ui.home.DeviceInfo
import ng1ok.yggdrasilmanager.ui.home.DeviceInfoProvider
import ng1ok.yggdrasilmanager.ui.home.HomeUiState
import ng1ok.yggdrasilmanager.ui.home.HomeViewModel
import ng1ok.yggdrasilmanager.ui.home.RootStatus
import ng1ok.yggdrasilmanager.ui.home.RootStatusSource
import ng1ok.yggdrasilmanager.ui.home.YggdrasilStatus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private val deviceInfo = DeviceInfo(
        manufacturer = "Google",
        model = "Pixel 8",
        androidVersion = "15",
        apiLevel = 35,
        kernelVersion = "6.1.0-test",
        managerVersion = "1.0"
    )

    private lateinit var repo: YggdrasilRepository
    private lateinit var rootStatusSource: RootStatusSource
    private lateinit var deviceInfoProvider: DeviceInfoProvider

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        repo = mockk()
        rootStatusSource = mockk()
        deviceInfoProvider = mockk()
        every { deviceInfoProvider.getDeviceInfo() } returns deviceInfo
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
    }

    @Test
    fun initialState_containsDeviceInfoAndCheckingStatuses() {
        val viewModel = createViewModel()

        assertEquals(HomeUiState(deviceInfo = deviceInfo), viewModel.uiState.value)
    }

    @Test
    fun startupChecks_completeIndependently() = runTest {
        val rootResult = CompletableDeferred<Boolean>()
        coEvery { rootStatusSource.isRootGranted() } coAnswers { rootResult.await() }
        coEvery { repo.checkAvailable() } returns true
        val viewModel = createViewModel()

        viewModel.startStartupChecks()
        runCurrent()

        assertEquals(RootStatus.CHECKING, viewModel.uiState.value.rootStatus)
        assertEquals(YggdrasilStatus.AVAILABLE, viewModel.uiState.value.yggdrasilStatus)

        rootResult.complete(true)
        runCurrent()

        assertEquals(RootStatus.GRANTED, viewModel.uiState.value.rootStatus)
    }

    @Test
    fun deniedRoot_showsPromptOnlyOnceAndDoesNotGateYggdrasil() = runTest {
        coEvery { rootStatusSource.isRootGranted() } returns false
        coEvery { repo.checkAvailable() } returns true
        val viewModel = createViewModel()

        viewModel.startStartupChecks()
        advanceUntilIdle()

        assertEquals(RootStatus.DENIED, viewModel.uiState.value.rootStatus)
        assertEquals(YggdrasilStatus.AVAILABLE, viewModel.uiState.value.yggdrasilStatus)
        assertTrue(viewModel.uiState.value.showRootDeniedDialog)

        viewModel.dismissRootDeniedDialog()
        viewModel.startStartupChecks()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.showRootDeniedDialog)
        assertTrue(viewModel.uiState.value.hasShownRootDeniedPrompt)
        coVerify(exactly = 1) { rootStatusSource.isRootGranted() }
        coVerify(exactly = 1) { repo.checkAvailable() }
    }

    @Test
    fun grantedRoot_doesNotPromptWhenYggdrasilIsUnavailable() = runTest {
        coEvery { rootStatusSource.isRootGranted() } returns true
        coEvery { repo.checkAvailable() } returns false
        val viewModel = createViewModel()

        viewModel.startStartupChecks()
        advanceUntilIdle()

        assertEquals(RootStatus.GRANTED, viewModel.uiState.value.rootStatus)
        assertEquals(YggdrasilStatus.UNAVAILABLE, viewModel.uiState.value.yggdrasilStatus)
        assertFalse(viewModel.uiState.value.showRootDeniedDialog)
    }

    @Test
    fun startupCheckExceptions_areMappedIndependently() = runTest {
        coEvery { rootStatusSource.isRootGranted() } throws IllegalStateException("no su")
        coEvery { repo.checkAvailable() } throws IllegalStateException("native unavailable")
        val viewModel = createViewModel()

        viewModel.startStartupChecks()
        advanceUntilIdle()

        assertEquals(RootStatus.DENIED, viewModel.uiState.value.rootStatus)
        assertEquals(YggdrasilStatus.UNAVAILABLE, viewModel.uiState.value.yggdrasilStatus)
        assertTrue(viewModel.uiState.value.showRootDeniedDialog)
    }

    @Test
    fun startupCheckCancellation_doesNotPublishFailureStates() = runTest {
        coEvery { rootStatusSource.isRootGranted() } throws CancellationException("cancelled")
        coEvery { repo.checkAvailable() } throws CancellationException("cancelled")
        val viewModel = createViewModel()

        viewModel.startStartupChecks()
        advanceUntilIdle()

        assertEquals(RootStatus.CHECKING, viewModel.uiState.value.rootStatus)
        assertEquals(YggdrasilStatus.CHECKING, viewModel.uiState.value.yggdrasilStatus)
        assertFalse(viewModel.uiState.value.showRootDeniedDialog)
    }

    private fun createViewModel() = HomeViewModel(
        repo = repo,
        rootStatusSource = rootStatusSource,
        deviceInfoProvider = deviceInfoProvider
    )
}
