package ng1ok.yggdrasilmanager

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import ng1ok.yggdrasilmanager.data.YggdrasilRepository
import ng1ok.yggdrasilmanager.data.hidedbg.HideDbgConfig
import ng1ok.yggdrasilmanager.data.hidedbg.HideDbgConfigStore
import ng1ok.yggdrasilmanager.data.hidedbg.InstalledApp
import ng1ok.yggdrasilmanager.data.hidedbg.InstalledAppsSource
import ng1ok.yggdrasilmanager.ui.hidedbg.HideDbgViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HideDbgViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private val apps = listOf(
        InstalledApp("com.one", "One", 1000, false),
        InstalledApp("com.shared", "Shared", 1000, false),
        InstalledApp("com.system", "System", 1001, true)
    )

    private lateinit var repo: YggdrasilRepository
    private lateinit var configStore: HideDbgConfigStore
    private lateinit var installedAppsSource: InstalledAppsSource

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        repo = mockk()
        configStore = mockk()
        installedAppsSource = mockk()

        coEvery { installedAppsSource.load() } returns Result.success(apps)
        coEvery { configStore.save(any()) } returns Result.success(Unit)
        coEvery { repo.setHideDbg(any(), any()) } returns Result.success(Unit)
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
    }

    @Test
    fun initialization_restoresCachedStateAndLoadsAppsWithoutKpmWrites() = runTest {
        val viewModel = createViewModel(
            config = HideDbgConfig(globalEnabled = true, enabledUids = setOf(1000)),
            loadWarning = "cached warning"
        )

        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.globalEnabled)
        assertEquals(setOf(1000), state.enabledUids)
        assertEquals(apps, state.apps)
        assertEquals("cached warning", state.message)
        coVerify(exactly = 1) { installedAppsSource.load() }
        coVerify(exactly = 0) { repo.setHideDbg(any(), any()) }
        coVerify(exactly = 0) { configStore.save(any()) }
    }

    @Test
    fun toggleUid_enablesEveryRowWithSharedUidThenSavesSnapshot() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.toggleUid(1000)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        val sharedRows = state.apps.filter { it.uid == 1000 }
        assertEquals(setOf(1000), state.enabledUids)
        assertEquals(setOf(1000), state.sharedUids)
        assertEquals(2, sharedRows.size)
        assertTrue(sharedRows.all { it.uid in state.enabledUids })
        coVerifyOrder {
            repo.setHideDbg(true, 1000)
            configStore.save(HideDbgConfig(false, setOf(1000)))
        }
    }

    @Test
    fun toggleUid_kpmFailurePreservesStateAndDoesNotSave() = runTest {
        coEvery { repo.setHideDbg(true, 1000) } returns
            Result.failure(IllegalStateException("denied"))
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.toggleUid(1000)
        advanceUntilIdle()

        assertEquals(emptySet<Int>(), viewModel.uiState.value.enabledUids)
        assertTrue(viewModel.uiState.value.message.orEmpty().contains("denied"))
        coVerify(exactly = 0) { configStore.save(any()) }
    }

    @Test
    fun toggleUid_saveFailureKeepsAppliedStateAndShowsExactMessage() = runTest {
        coEvery { configStore.save(any()) } returns
            Result.failure(IllegalStateException("disk"))
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.toggleUid(1000)
        advanceUntilIdle()

        assertEquals(setOf(1000), viewModel.uiState.value.enabledUids)
        assertEquals("已套用但未保存", viewModel.uiState.value.message)
    }

    @Test
    fun appLoadFailure_preservesConfiguredUidsAndNeverWritesKpm() = runTest {
        coEvery { installedAppsSource.load() } returns
            Result.failure(IllegalStateException("packages"))
        val viewModel = createViewModel(
            HideDbgConfig(enabledUids = setOf(1000))
        )

        advanceUntilIdle()

        assertEquals("packages", viewModel.uiState.value.appLoadError)
        assertEquals(setOf(1000), viewModel.uiState.value.enabledUids)
        coVerify(exactly = 0) { repo.setHideDbg(any(), any()) }
    }

    @Test
    fun refreshFailure_retainsLoadedAppsAndNeverMutatesHideDbgState() = runTest {
        var loadCount = 0
        coEvery { installedAppsSource.load() } coAnswers {
            loadCount += 1
            if (loadCount == 1) {
                Result.success(apps)
            } else {
                Result.failure(IllegalStateException("refresh failed"))
            }
        }
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.refreshApps()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(2, loadCount)
        assertEquals(apps, state.apps)
        assertEquals("refresh failed", state.appLoadError)
        assertFalse(state.loadingApps)
        coVerify(exactly = 0) { repo.setHideDbg(any(), any()) }
        coVerify(exactly = 0) { configStore.save(any()) }
    }

    @Test
    fun appLoadThrownException_setsRetryableErrorAndClearsLoading() = runTest {
        var loadCount = 0
        coEvery { installedAppsSource.load() } coAnswers {
            loadCount += 1
            if (loadCount == 1) {
                throw IllegalStateException("packages")
            }
            Result.success(apps)
        }
        val viewModel = createViewModel(
            HideDbgConfig(enabledUids = setOf(1000))
        )

        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.loadingApps)
        assertEquals("packages", viewModel.uiState.value.appLoadError)
        assertEquals(setOf(1000), viewModel.uiState.value.enabledUids)

        viewModel.refreshApps()
        advanceUntilIdle()

        assertEquals(2, loadCount)
        assertEquals(apps, viewModel.uiState.value.apps)
        assertNull(viewModel.uiState.value.appLoadError)
        coVerify(exactly = 0) { repo.setHideDbg(any(), any()) }
    }

    @Test
    fun appLoadResultCancellation_clearsLoadingAndPropagates() = runTest {
        var operationJob: Job? = null
        coEvery { installedAppsSource.load() } coAnswers {
            operationJob = currentCoroutineContext()[Job]
            Result.failure(CancellationException("cancelled"))
        }
        val viewModel = createViewModel()

        advanceUntilIdle()

        assertTrue(operationJob?.isCancelled == true)
        assertFalse(viewModel.uiState.value.loadingApps)
        assertNull(viewModel.uiState.value.appLoadError)
        coVerify(exactly = 0) { repo.setHideDbg(any(), any()) }
    }

    @Test
    fun appLoadResultFatal_clearsLoadingAndPropagates() {
        lateinit var viewModel: HideDbgViewModel
        val fatal = FatalTestError("fatal app result")

        val thrown = assertThrows(FatalTestError::class.java) {
            runTest {
                coEvery { installedAppsSource.load() } returns Result.failure(fatal)
                viewModel = createViewModel()

                advanceUntilIdle()
            }
        }

        assertSame(fatal, thrown)
        assertFalse(viewModel.uiState.value.loadingApps)
        assertNull(viewModel.uiState.value.appLoadError)
        coVerify(exactly = 0) { repo.setHideDbg(any(), any()) }
    }

    @Test
    fun appLoadThrownFatal_clearsLoadingAndPropagates() {
        lateinit var viewModel: HideDbgViewModel
        val fatal = FatalTestError("fatal app throw")

        val thrown = assertThrows(FatalTestError::class.java) {
            runTest {
                coEvery { installedAppsSource.load() } throws fatal
                viewModel = createViewModel()

                advanceUntilIdle()
            }
        }

        assertSame(fatal, thrown)
        assertFalse(viewModel.uiState.value.loadingApps)
        assertNull(viewModel.uiState.value.appLoadError)
        coVerify(exactly = 0) { repo.setHideDbg(any(), any()) }
    }

    @Test
    fun toggleGlobal_disablingClearsEveryUidThenSavesSnapshot() = runTest {
        val viewModel = createViewModel(
            HideDbgConfig(globalEnabled = true, enabledUids = setOf(1000, 1001))
        )
        advanceUntilIdle()

        viewModel.toggleGlobal()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.globalEnabled)
        assertEquals(emptySet<Int>(), viewModel.uiState.value.enabledUids)
        coVerifyOrder {
            repo.setHideDbg(false, null)
            configStore.save(HideDbgConfig(false, emptySet()))
        }
    }

    @Test
    fun toggleGlobal_kpmFailurePreservesGlobalAndUidStateWithoutSaving() = runTest {
        coEvery { repo.setHideDbg(false, null) } returns
            Result.failure(IllegalStateException("denied"))
        val initialConfig = HideDbgConfig(
            globalEnabled = true,
            enabledUids = setOf(1000, 1001)
        )
        val viewModel = createViewModel(initialConfig)
        advanceUntilIdle()

        viewModel.toggleGlobal()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.globalEnabled)
        assertEquals(initialConfig.enabledUids, viewModel.uiState.value.enabledUids)
        coVerify(exactly = 0) { configStore.save(any()) }
    }

    @Test
    fun toggleGlobal_enablingPreservesUidsAndBlocksSubsequentUidWrites() = runTest {
        val viewModel = createViewModel(
            HideDbgConfig(globalEnabled = false, enabledUids = setOf(1000))
        )
        advanceUntilIdle()

        viewModel.toggleGlobal()
        advanceUntilIdle()
        viewModel.toggleUid(1001)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.globalEnabled)
        assertEquals(setOf(1000), viewModel.uiState.value.enabledUids)
        coVerifyOrder {
            repo.setHideDbg(true, null)
            configStore.save(HideDbgConfig(true, setOf(1000)))
        }
        coVerify(exactly = 0) { repo.setHideDbg(any(), 1001) }
    }

    @Test
    fun toggleUid_repeatedTapWhilePendingWritesKpmExactlyOnce() = runTest {
        val kpmResult = CompletableDeferred<Result<Unit>>()
        coEvery { repo.setHideDbg(true, 1000) } coAnswers { kpmResult.await() }
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.toggleUid(1000)
        runCurrent()
        assertEquals(setOf(1000), viewModel.uiState.value.pendingUids)

        viewModel.toggleUid(1000)
        runCurrent()

        coVerify(exactly = 1) { repo.setHideDbg(true, 1000) }
        kpmResult.complete(Result.success(Unit))
        advanceUntilIdle()

        assertEquals(setOf(1000), viewModel.uiState.value.enabledUids)
        assertEquals(emptySet<Int>(), viewModel.uiState.value.pendingUids)
    }

    @Test
    fun toggleUid_distinctRapidTogglesSaveCumulativeSnapshotsInOrder() = runTest {
        val firstKpmResult = CompletableDeferred<Result<Unit>>()
        coEvery { repo.setHideDbg(true, 1000) } coAnswers { firstKpmResult.await() }
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.toggleUid(1000)
        runCurrent()
        coVerify(exactly = 1) { repo.setHideDbg(true, 1000) }

        viewModel.toggleUid(1001)
        runCurrent()

        try {
            coVerify(exactly = 0) { repo.setHideDbg(true, 1001) }
            coVerify(exactly = 0) { configStore.save(any()) }
            assertEquals(setOf(1000, 1001), viewModel.uiState.value.pendingUids)
        } finally {
            firstKpmResult.complete(Result.success(Unit))
            advanceUntilIdle()
        }

        assertEquals(setOf(1000, 1001), viewModel.uiState.value.enabledUids)
        assertEquals(emptySet<Int>(), viewModel.uiState.value.pendingUids)
        coVerifyOrder {
            repo.setHideDbg(true, 1000)
            configStore.save(HideDbgConfig(false, setOf(1000)))
            repo.setHideDbg(true, 1001)
            configStore.save(HideDbgConfig(false, setOf(1000, 1001)))
        }
        coVerify(exactly = 1) { repo.setHideDbg(true, 1001) }
        coVerify(exactly = 1) {
            configStore.save(HideDbgConfig(false, setOf(1000)))
        }
        coVerify(exactly = 1) {
            configStore.save(HideDbgConfig(false, setOf(1000, 1001)))
        }
    }

    @Test
    fun toggleGlobal_uidPendingIgnoresGlobalWithoutQueuingIt() = runTest {
        val uidKpmResult = CompletableDeferred<Result<Unit>>()
        coEvery { repo.setHideDbg(true, 1000) } coAnswers { uidKpmResult.await() }
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.toggleUid(1000)
        runCurrent()
        assertEquals(setOf(1000), viewModel.uiState.value.pendingUids)

        viewModel.toggleGlobal()
        runCurrent()

        try {
            assertFalse(viewModel.uiState.value.globalPending)
            coVerify(exactly = 0) { repo.setHideDbg(any(), null) }
            coVerify(exactly = 0) { configStore.save(any()) }
        } finally {
            uidKpmResult.complete(Result.success(Unit))
            advanceUntilIdle()
        }

        assertFalse(viewModel.uiState.value.globalEnabled)
        assertFalse(viewModel.uiState.value.globalPending)
        assertEquals(setOf(1000), viewModel.uiState.value.enabledUids)
        assertEquals(emptySet<Int>(), viewModel.uiState.value.pendingUids)
        coVerify(exactly = 0) { repo.setHideDbg(any(), null) }
        coVerify(exactly = 1) {
            configStore.save(HideDbgConfig(false, setOf(1000)))
        }
    }

    @Test
    fun toggleUid_globalPendingIgnoresUidWithoutQueuingIt() = runTest {
        val globalKpmResult = CompletableDeferred<Result<Unit>>()
        coEvery { repo.setHideDbg(true, null) } coAnswers { globalKpmResult.await() }
        val viewModel = createViewModel(
            HideDbgConfig(globalEnabled = false, enabledUids = setOf(1000))
        )
        advanceUntilIdle()

        viewModel.toggleGlobal()
        runCurrent()
        assertTrue(viewModel.uiState.value.globalPending)

        viewModel.toggleUid(1001)
        runCurrent()

        try {
            assertEquals(emptySet<Int>(), viewModel.uiState.value.pendingUids)
            coVerify(exactly = 0) { repo.setHideDbg(any(), 1001) }
            coVerify(exactly = 0) { configStore.save(any()) }
        } finally {
            globalKpmResult.complete(Result.success(Unit))
            advanceUntilIdle()
        }

        assertTrue(viewModel.uiState.value.globalEnabled)
        assertFalse(viewModel.uiState.value.globalPending)
        assertEquals(setOf(1000), viewModel.uiState.value.enabledUids)
        assertEquals(emptySet<Int>(), viewModel.uiState.value.pendingUids)
        coVerify(exactly = 0) { repo.setHideDbg(any(), 1001) }
        coVerify(exactly = 1) {
            configStore.save(HideDbgConfig(true, setOf(1000)))
        }
    }

    @Test
    fun toggleUid_cancellationClearsPendingAndPropagatesWithoutSaving() = runTest {
        var operationJob: Job? = null
        coEvery { repo.setHideDbg(true, 1000) } coAnswers {
            operationJob = currentCoroutineContext()[Job]
            throw CancellationException("cancelled")
        }
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.toggleUid(1000)
        advanceUntilIdle()

        assertTrue(operationJob?.isCancelled == true)
        assertEquals(emptySet<Int>(), viewModel.uiState.value.pendingUids)
        assertEquals(emptySet<Int>(), viewModel.uiState.value.enabledUids)
        assertNull(viewModel.uiState.value.message)
        coVerify(exactly = 0) { configStore.save(any()) }
    }

    @Test
    fun toggleGlobal_cancellationClearsPendingAndPropagatesWithoutSaving() = runTest {
        var operationJob: Job? = null
        coEvery { repo.setHideDbg(false, null) } coAnswers {
            operationJob = currentCoroutineContext()[Job]
            throw CancellationException("cancelled")
        }
        val initialConfig = HideDbgConfig(
            globalEnabled = true,
            enabledUids = setOf(1000)
        )
        val viewModel = createViewModel(initialConfig)
        advanceUntilIdle()

        viewModel.toggleGlobal()
        advanceUntilIdle()

        assertTrue(operationJob?.isCancelled == true)
        assertFalse(viewModel.uiState.value.globalPending)
        assertTrue(viewModel.uiState.value.globalEnabled)
        assertEquals(setOf(1000), viewModel.uiState.value.enabledUids)
        assertNull(viewModel.uiState.value.message)
        coVerify(exactly = 0) { configStore.save(any()) }
    }

    @Test
    fun toggleUid_resultCancellationClearsPendingAndPropagatesWithoutSaving() = runTest {
        var operationJob: Job? = null
        coEvery { repo.setHideDbg(true, 1000) } coAnswers {
            operationJob = currentCoroutineContext()[Job]
            Result.failure(CancellationException("cancelled result"))
        }
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.toggleUid(1000)
        advanceUntilIdle()

        assertTrue(operationJob?.isCancelled == true)
        assertEquals(emptySet<Int>(), viewModel.uiState.value.pendingUids)
        assertEquals(emptySet<Int>(), viewModel.uiState.value.enabledUids)
        assertNull(viewModel.uiState.value.message)
        coVerify(exactly = 0) { configStore.save(any()) }
    }

    @Test
    fun toggleGlobal_resultFatalClearsPendingAndPropagatesWithoutSaving() {
        lateinit var viewModel: HideDbgViewModel
        val fatal = FatalTestError("fatal global KPM result")
        val initialConfig = HideDbgConfig(
            globalEnabled = true,
            enabledUids = setOf(1000, 1001)
        )

        val thrown = assertThrows(FatalTestError::class.java) {
            runTest {
                coEvery { repo.setHideDbg(false, null) } returns Result.failure(fatal)
                viewModel = createViewModel(initialConfig)
                advanceUntilIdle()

                viewModel.toggleGlobal()
                advanceUntilIdle()
            }
        }

        assertSame(fatal, thrown)
        assertFalse(viewModel.uiState.value.globalPending)
        assertTrue(viewModel.uiState.value.globalEnabled)
        assertEquals(initialConfig.enabledUids, viewModel.uiState.value.enabledUids)
        assertNull(viewModel.uiState.value.message)
        coVerify(exactly = 0) { configStore.save(any()) }
    }

    @Test
    fun toggleUid_saveResultCancellationRetainsAppliedStateAndPropagates() = runTest {
        var operationJob: Job? = null
        coEvery { configStore.save(HideDbgConfig(false, setOf(1000))) } coAnswers {
            operationJob = currentCoroutineContext()[Job]
            Result.failure(CancellationException("cancelled save result"))
        }
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.toggleUid(1000)
        advanceUntilIdle()

        assertTrue(operationJob?.isCancelled == true)
        assertEquals(emptySet<Int>(), viewModel.uiState.value.pendingUids)
        assertEquals(setOf(1000), viewModel.uiState.value.enabledUids)
        assertNull(viewModel.uiState.value.message)
        coVerifyOrder {
            repo.setHideDbg(true, 1000)
            configStore.save(HideDbgConfig(false, setOf(1000)))
        }
    }

    @Test
    fun toggleGlobal_saveResultFatalRetainsAppliedStateAndPropagates() {
        lateinit var viewModel: HideDbgViewModel
        val fatal = FatalTestError("fatal global save result")
        val initialConfig = HideDbgConfig(
            globalEnabled = true,
            enabledUids = setOf(1000, 1001)
        )

        val thrown = assertThrows(FatalTestError::class.java) {
            runTest {
                coEvery {
                    configStore.save(HideDbgConfig(false, emptySet()))
                } returns Result.failure(fatal)
                viewModel = createViewModel(initialConfig)
                advanceUntilIdle()

                viewModel.toggleGlobal()
                advanceUntilIdle()
            }
        }

        assertSame(fatal, thrown)
        assertFalse(viewModel.uiState.value.globalPending)
        assertFalse(viewModel.uiState.value.globalEnabled)
        assertEquals(emptySet<Int>(), viewModel.uiState.value.enabledUids)
        assertNull(viewModel.uiState.value.message)
        coVerifyOrder {
            repo.setHideDbg(false, null)
            configStore.save(HideDbgConfig(false, emptySet()))
        }
    }

    private fun createViewModel(
        config: HideDbgConfig = HideDbgConfig(),
        loadWarning: String? = null
    ): HideDbgViewModel {
        every { configStore.current } returns config
        every { configStore.loadWarning } returns loadWarning
        return HideDbgViewModel(repo, configStore, installedAppsSource)
    }

    private class FatalTestError(message: String) : Error(message)
}
