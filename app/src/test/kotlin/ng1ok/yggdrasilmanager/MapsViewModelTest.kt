package ng1ok.yggdrasilmanager

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import ng1ok.yggdrasilmanager.data.YggdrasilRepository
import ng1ok.yggdrasilmanager.data.model.MapsMutationResult
import ng1ok.yggdrasilmanager.data.model.MapsPolicy
import ng1ok.yggdrasilmanager.data.model.MapsRule
import ng1ok.yggdrasilmanager.data.model.MapsScope
import ng1ok.yggdrasilmanager.ui.maps.MapsViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** Verifies user feedback and recovery after Maps mutations. */
@OptIn(ExperimentalCoroutinesApi::class)
class MapsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val repo = mockk<YggdrasilRepository>()
    private val rule = MapsRule(MapsPolicy.ACTION_HIDE, MapsPolicy.MATCH_PATH_SUBSTR, pattern = "libfoo")
    private val initialScope = MapsScope(global = true, rules = listOf(rule))

    /** Supplies a deterministic main dispatcher for the ViewModel. */
    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
        coEvery { repo.getMaps(null) } returns Result.success(initialScope)
    }

    /** Restores the main dispatcher after each test. */
    @After
    fun teardown() {
        Dispatchers.resetMain()
    }

    /** A readback warning preserves the last list and allows refresh without retrying the change. */
    @Test
    fun failedReadbackShowsCommittedChangeWarningAndAllowsRefresh() = runTest {
        val viewModel = MapsViewModel(repo)
        advanceUntilIdle()
        coEvery { repo.clearMapsAndRead(null) } returns Result.success(
            MapsMutationResult(Result.failure(IllegalStateException("connection lost"))),
        )

        viewModel.clearCurrentScope()
        advanceUntilIdle()

        assertEquals(MapsViewModel.READBACK_ERROR, viewModel.uiState.value.error)
        assertEquals(initialScope.rules, viewModel.uiState.value.rules)
        assertEquals(initialScope.totalRules, viewModel.uiState.value.totalRules)
        assertFalse(viewModel.uiState.value.busy)

        coEvery { repo.getMaps(null) } returns Result.success(MapsScope(true, emptyList()))
        viewModel.refresh()
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.error)
        assertEquals(emptyList<MapsRule>(), viewModel.uiState.value.rules)
        assertEquals(0, viewModel.uiState.value.totalRules)
        coVerify(exactly = 1) { repo.clearMapsAndRead(null) }
        coVerify(exactly = 2) { repo.getMaps(null) }
    }

    /** Failed changes display the mutation error without implying that anything was applied. */
    @Test
    fun mutationFailureDisplaysItsError() = runTest {
        val viewModel = MapsViewModel(repo)
        advanceUntilIdle()
        coEvery { repo.clearMapsAndRead(null) } returns Result.failure(IllegalStateException("prctl errno 22"))

        viewModel.clearCurrentScope()
        advanceUntilIdle()

        assertEquals("prctl errno 22", viewModel.uiState.value.error)
        assertEquals(initialScope.rules, viewModel.uiState.value.rules)
        assertFalse(viewModel.uiState.value.busy)
    }

    /** Successful readback replaces the displayed scope without a second query. */
    @Test
    fun successfulReadbackUpdatesDisplayedRules() = runTest {
        val viewModel = MapsViewModel(repo)
        advanceUntilIdle()
        val scope = MapsScope(global = true, rules = emptyList())
        coEvery { repo.clearMapsAndRead(null) } returns Result.success(MapsMutationResult(Result.success(scope)))

        viewModel.clearCurrentScope()
        advanceUntilIdle()

        assertEquals(scope.rules, viewModel.uiState.value.rules)
        assertEquals(scope.totalRules, viewModel.uiState.value.totalRules)
        assertNull(viewModel.uiState.value.error)
        assertFalse(viewModel.uiState.value.busy)
        coVerify(exactly = 1) { repo.getMaps(null) }
    }
}
