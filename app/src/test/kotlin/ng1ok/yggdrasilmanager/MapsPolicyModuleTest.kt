package ng1ok.yggdrasilmanager

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import ng1ok.yggdrasilmanager.data.MapsOperations
import ng1ok.yggdrasilmanager.data.MapsPolicyModule
import ng1ok.yggdrasilmanager.data.model.MapsPolicy
import ng1ok.yggdrasilmanager.data.model.MapsRule
import ng1ok.yggdrasilmanager.data.model.MapsRuleDraft
import ng1ok.yggdrasilmanager.data.model.MapsScope
import ng1ok.yggdrasilmanager.data.model.MapsScopeTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MapsPolicyModuleTest {
    @Test
    fun addBuildsAndValidatesAPathRuleThenReturnsReadback() = runTest {
        val expected = MapsScope(
            global = true,
            rules = listOf(
                MapsRule(
                    action = MapsPolicy.ACTION_HIDE,
                    matchKind = MapsPolicy.MATCH_PATH_SUBSTR,
                    pattern = "libfoo",
                ),
            ),
        )
        val operations = FakeMapsOperations(expected)
        val module = MapsPolicyModule(
            operationsProvider = { operations },
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
        )

        val result = module.add(
            MapsScopeTarget.Global,
            draft = MapsRuleDraft(
                action = MapsPolicy.ACTION_HIDE,
                matchKind = MapsPolicy.MATCH_PATH_SUBSTR,
                pattern = "libfoo",
                startAddress = "not an address",
                endAddress = "also ignored",
            ),
        )

        assertEquals(Result.success(expected), result.getOrThrow().readback)
        assertEquals(null, operations.lastPid)
        assertEquals("libfoo", operations.lastRule?.pattern)
        assertEquals(0L, operations.lastRule?.addrStart)
        assertEquals(1, operations.addCalls)
    }

    @Test
    fun addressRangeRequiresAnOrderedHexRange() = runTest {
        val operations = FakeMapsOperations(MapsScope(true, emptyList()))
        val module = MapsPolicyModule(
            operationsProvider = { operations },
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
        )

        val result = module.add(
            MapsScopeTarget.Global,
            draft = MapsRuleDraft(
                action = MapsPolicy.ACTION_HIDE,
                matchKind = MapsPolicy.MATCH_ADDR_RANGE,
                startAddress = "0x2000",
                endAddress = "0x1000",
            ),
        )

        assertTrue(result.isFailure)
        assertEquals(0, operations.addCalls)
    }

    @Test
    fun successfulMutationReadsBackTheSelectedProcessScope() = runTest {
        val expected = MapsScope(global = false, rules = emptyList())
        val operations = FakeMapsOperations(expected)
        val module = MapsPolicyModule(
            operationsProvider = { operations },
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
        )

        val result = module.clear(MapsScopeTarget.Process(1234))

        assertEquals(Result.success(expected), result.getOrThrow().readback)
        assertEquals(1234, operations.lastPid)
        assertEquals(1234, operations.lastReadPid)
        assertEquals(false, operations.lastAllScopes)
        assertEquals(1, operations.getCalls)
    }

    @Test
    fun clearAllScopesRejectsAdapterFailureWithoutReadingBack() = runTest {
        val operations = FakeMapsOperations(MapsScope(global = true, rules = emptyList()))
        val module = MapsPolicyModule(
            operationsProvider = { operations },
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
        )

        operations.clearResult = -22
        val result = module.clearAllScopes()

        assertTrue(result.isFailure)
        assertEquals(null, operations.lastPid)
        assertTrue(operations.lastAllScopes)
        assertEquals(0, operations.getCalls)
        assertFalse(result.isSuccess)
    }

    /** A missing scope after a successful change must not report mutation failure. */
    @Test
    fun successfulMutationWithMissingReadbackRemainsSuccessful() = runTest {
        val operations = FakeMapsOperations(readback = null)
        val module = MapsPolicyModule({ operations }, UnconfinedTestDispatcher(testScheduler))

        val result = module.clear(MapsScopeTarget.Global)

        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow().readback.isFailure)
        assertEquals(1, operations.getCalls)
    }

    /** Adapter read errors retain their cause without undoing a successful mutation. */
    @Test
    fun successfulMutationPreservesReadbackException() = runTest {
        val failure = IllegalStateException("connection lost")
        val operations = FakeMapsOperations(readback = null).apply { readFailure = failure }
        val module = MapsPolicyModule({ operations }, UnconfinedTestDispatcher(testScheduler))

        val result = module.clear(MapsScopeTarget.Global)

        assertSame(failure, result.getOrThrow().readback.exceptionOrNull())
        assertSame(failure, module.read(MapsScopeTarget.Global).exceptionOrNull())
    }

    /** A scope mismatch is a readback failure, even after a committed change. */
    @Test
    fun successfulMutationRejectsWrongScopeInReadback() = runTest {
        val operations = FakeMapsOperations(MapsScope(global = true, rules = emptyList()))
        val module = MapsPolicyModule({ operations }, UnconfinedTestDispatcher(testScheduler))

        val result = module.clear(MapsScopeTarget.Process(1234))

        assertTrue(result.getOrThrow().readback.isFailure)
        assertEquals(1234, operations.lastReadPid)
    }

    /** Clearing every scope queries the global policy after the change. */
    @Test
    fun clearAllScopesReadsBackGlobalScope() = runTest {
        val expected = MapsScope(global = true, rules = emptyList())
        val operations = FakeMapsOperations(expected)
        val module = MapsPolicyModule({ operations }, UnconfinedTestDispatcher(testScheduler))

        val result = module.clearAllScopes()

        assertEquals(Result.success(expected), result.getOrThrow().readback)
        assertTrue(operations.lastAllScopes)
        assertEquals(null, operations.lastReadPid)
        assertEquals(1, operations.getCalls)
    }

    /** Readback cancellation must propagate rather than becoming an ordinary result. */
    @Test
    fun readbackCancellationPropagates() = runTest {
        val cancellation = CancellationException("cancelled")
        val operations = FakeMapsOperations(readback = null).apply { readFailure = cancellation }
        val module = MapsPolicyModule({ operations }, UnconfinedTestDispatcher(testScheduler))

        try {
            module.clear(MapsScopeTarget.Global)
            fail("Expected cancellation to propagate")
        } catch (actual: CancellationException) {
            assertSame(cancellation, actual)
        }
    }

    private class FakeMapsOperations(
        private val readback: MapsScope?,
    ) : MapsOperations {
        var addCalls = 0
        var getCalls = 0
        var lastPid: Int? = null
        var lastReadPid: Int? = null
        var lastAllScopes = false
        var lastRule: MapsRule? = null
        var clearResult = 0
        var readFailure: Exception? = null

        override suspend fun addRule(pid: Int?, rule: MapsRule): Int {
            addCalls++
            lastPid = pid
            lastRule = rule
            return 0
        }

        override suspend fun removeRule(pid: Int?, rule: MapsRule): Int {
            lastPid = pid
            lastRule = rule
            return 0
        }

        override suspend fun clear(pid: Int?, allScopes: Boolean): Int {
            lastPid = pid
            lastAllScopes = allScopes
            return clearResult
        }

        override suspend fun get(pid: Int?): MapsScope? {
            getCalls++
            lastReadPid = pid
            readFailure?.let { throw it }
            return readback
        }
    }
}
