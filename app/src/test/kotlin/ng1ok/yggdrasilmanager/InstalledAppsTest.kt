package ng1ok.yggdrasilmanager

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import ng1ok.yggdrasilmanager.data.hidedbg.AppFilter
import ng1ok.yggdrasilmanager.data.hidedbg.InstalledApp
import ng1ok.yggdrasilmanager.data.hidedbg.PackageManagerInstalledAppsSource
import ng1ok.yggdrasilmanager.data.hidedbg.PackageRecord
import ng1ok.yggdrasilmanager.data.hidedbg.filterInstalledApps
import ng1ok.yggdrasilmanager.data.hidedbg.findSharedUids
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class InstalledAppsTest {
    private val apps = listOf(
        InstalledApp("com.beta", "Beta", 1001, false),
        InstalledApp("com.alpha.system", "Alpha System", 1000, true),
        InstalledApp("com.alpha.shared", "Alpha Shared", 1000, false)
    )

    @Test
    fun filterInstalledApps_matchesQueryAndApplicationType() {
        assertEquals(
            listOf(apps[1], apps[2]),
            filterInstalledApps(apps, " ALPHA ", AppFilter.ALL)
        )
        assertEquals(
            listOf(apps[0], apps[2]),
            filterInstalledApps(apps, "", AppFilter.USER)
        )
        assertEquals(
            listOf(apps[1]),
            filterInstalledApps(apps, "system", AppFilter.SYSTEM)
        )
        assertEquals(
            listOf(apps[0]),
            filterInstalledApps(apps, "COM.BETA", AppFilter.ALL)
        )
    }

    @Test
    fun findSharedUids_returnsOnlyUidsUsedByMultipleApplications() {
        assertEquals(setOf(1000), findSharedUids(apps))
    }

    @Test
    fun packageManagerSource_mapsSystemFlagsAndSortsByLabelThenPackage() = runTest {
        val source = PackageManagerInstalledAppsSource(
            packageManager = mockk<PackageManager>(),
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
            queryRecords = {
                listOf(
                    PackageRecord(
                        packageName = "com.zeta",
                        label = "Zeta",
                        uid = 1000,
                        flags = ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
                    ),
                    PackageRecord(
                        packageName = "com.alpha",
                        label = "Alpha",
                        uid = 1001,
                        flags = 0
                    )
                )
            }
        )

        val result = source.load()

        assertTrue(result.isSuccess)
        val loadedApps = result.getOrThrow()
        assertEquals(listOf("com.alpha", "com.zeta"), loadedApps.map { it.packageName })
        assertFalse(loadedApps.first().isSystem)
        assertTrue(loadedApps.last().isSystem)
    }

    @Test
    fun packageManagerSource_propagatesCancellationWithoutWrappingIt() = runTest {
        val cancellation = CancellationException("query cancelled")
        val source = PackageManagerInstalledAppsSource(
            packageManager = mockk<PackageManager>(),
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
            queryRecords = { throw cancellation }
        )

        try {
            source.load()
            fail("Expected package query cancellation to propagate")
        } catch (caught: CancellationException) {
            assertSame(cancellation, caught)
        }
    }
}
