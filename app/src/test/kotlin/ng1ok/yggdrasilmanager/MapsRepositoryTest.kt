package ng1ok.yggdrasilmanager

import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import ng1ok.yggdrasilmanager.data.YggdrasilRepository
import ng1ok.yggdrasilmanager.data.model.MapsPolicy
import ng1ok.yggdrasilmanager.data.model.MapsRule
import ng1ok.yggdrasilmanager.data.model.MapsRuleDraft
import ng1ok.yggdrasilmanager.native.YggdrasilNativeApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression coverage for the repository's Maps result contract. */
class MapsRepositoryTest {
    private val native = mockk<YggdrasilNativeApi>()
    private val repo = YggdrasilRepository(native)
    private val rule = MapsRule(MapsPolicy.ACTION_HIDE, MapsPolicy.MATCH_PATH_SUBSTR, pattern = "libfoo")
    private val draft = MapsRuleDraft(MapsPolicy.ACTION_HIDE, MapsPolicy.MATCH_PATH_SUBSTR, pattern = "libfoo")

    /** Every PID-taking API rejects invalid input through Result without reaching JNI. */
    @Test
    fun invalidPidsReturnFailuresWithoutCallingNative() = runTest {
        for (pid in listOf(Int.MIN_VALUE, -1, 0)) {
            val results = listOf(
                repo.addMapsRule(pid, rule),
                repo.removeMapsRule(pid, rule),
                repo.clearMaps(pid),
                repo.getMaps(pid),
                repo.addMapsRuleAndRead(pid, draft),
                repo.removeMapsRuleAndRead(pid, rule),
                repo.clearMapsAndRead(pid),
            )
            results.forEach { result ->
                assertTrue(result.exceptionOrNull() is IllegalArgumentException)
                assertEquals("Maps PID 必須是正整數", result.exceptionOrNull()?.message)
            }
        }

        verify { native wasNot Called }
    }

    /** Clearing all scopes cannot be combined with any explicit PID. */
    @Test
    fun clearAllScopesRejectsPidWithoutCallingNative() = runTest {
        for (pid in listOf(-1, 0, 1234)) {
            assertTrue(repo.clearMaps(pid, allScopes = true).isFailure)
            assertTrue(repo.clearMapsAndRead(pid, allScopes = true).isFailure)
        }

        verify { native wasNot Called }
    }

    /** Legacy Unit APIs report committed changes as successful despite a read error. */
    @Test
    fun unitMutationApisRemainSuccessfulWhenReadbackFails() = runTest {
        stubSuccessfulMutations()
        every { native.mapsGet(any(), any()) } throws IllegalStateException("connection lost")

        for (pid in listOf(null, 1234)) {
            assertEquals(Result.success(Unit), repo.addMapsRule(pid, rule))
            assertEquals(Result.success(Unit), repo.removeMapsRule(pid, rule))
            assertEquals(Result.success(Unit), repo.clearMaps(pid))
        }
        assertEquals(Result.success(Unit), repo.clearMaps(null, allScopes = true))
    }

    /** UI APIs retain the readback failure separately for each successful mutation. */
    @Test
    fun mutationAndReadApisExposeMissingReadback() = runTest {
        stubSuccessfulMutations()
        every { native.mapsGet(any(), any()) } returns null

        val results = listOf(
            repo.addMapsRuleAndRead(null, draft),
            repo.removeMapsRuleAndRead(null, rule),
            repo.clearMapsAndRead(1234),
            repo.clearMapsAndRead(null, allScopes = true),
        )

        results.forEach { assertTrue(it.getOrThrow().readback.isFailure) }
        assertTrue(repo.getMaps(null).isFailure)
    }

    /** A failed mutation remains a failure and never attempts a readback. */
    @Test
    fun mutationFailureDoesNotReadBack() = runTest {
        every { native.mapsClear(false, 0, false) } returns -22

        val result = repo.clearMaps(null)

        assertEquals("prctl errno 22", result.exceptionOrNull()?.message)
        verify(exactly = 0) { native.mapsGet(any(), any()) }
    }

    private fun stubSuccessfulMutations() {
        every { native.mapsAddRule(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns 0
        every { native.mapsRemoveRule(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns 0
        every { native.mapsClear(any(), any(), any()) } returns 0
    }
}
