package ng1ok.yggdrasilmanager

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import io.mockk.mockk
import java.util.Locale
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
                        packageName = "com.zeta.updated",
                        label = "Same",
                        uid = 1000,
                        flags = ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
                    ),
                    PackageRecord(
                        packageName = "com.beta.user",
                        label = "Alpha",
                        uid = 1001,
                        flags = 0
                    ),
                    PackageRecord(
                        packageName = "com.zeta.system",
                        label = "Same",
                        uid = 1002,
                        flags = ApplicationInfo.FLAG_SYSTEM
                    ),
                    PackageRecord(
                        packageName = "com.alpha.user",
                        label = "Same",
                        uid = 1003,
                        flags = 0
                    )
                )
            }
        )

        val result = source.load()

        assertTrue(result.isSuccess)
        val loadedApps = result.getOrThrow()
        assertEquals(
            listOf(
                "com.beta.user",
                "com.alpha.user",
                "com.zeta.system",
                "com.zeta.updated"
            ),
            loadedApps.map { it.packageName }
        )
        assertEquals(
            listOf(false, false, true, true),
            loadedApps.map { it.isSystem }
        )
    }

    @Test
    fun packageManagerSource_usesLocaleAwareLabelOrdering() = runTest {
        val previousLocale = Locale.getDefault()

        try {
            Locale.setDefault(Locale.US)
            val source = PackageManagerInstalledAppsSource(
                packageManager = mockk<PackageManager>(),
                ioDispatcher = UnconfinedTestDispatcher(testScheduler),
                queryRecords = {
                    listOf(
                        PackageRecord("com.zulu", "Zulu", 1000, 0),
                        PackageRecord("com.angstrom", "\u00c5ngstr\u00f6m", 1001, 0)
                    )
                }
            )

            val loadedApps = source.load().getOrThrow()

            assertEquals(
                listOf("com.angstrom", "com.zulu"),
                loadedApps.map { it.packageName }
            )
        } finally {
            Locale.setDefault(previousLocale)
        }
    }

    @Test
    fun packageManagerSource_replacesBlankLabelWithPackageName() = runTest {
        val source = PackageManagerInstalledAppsSource(
            packageManager = mockk<PackageManager>(),
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
            queryRecords = {
                listOf(PackageRecord("com.blank", " \t", 1000, 0))
            }
        )

        val loadedApp = source.load().getOrThrow().single()

        assertEquals("com.blank", loadedApp.label)
    }

    @Test
    fun packageManagerSource_returnsOrdinaryQueryFailure() = runTest {
        val failure = SecurityException("package visibility denied")
        val source = PackageManagerInstalledAppsSource(
            packageManager = mockk<PackageManager>(),
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
            queryRecords = { throw failure }
        )

        val result = source.load()

        assertTrue(result.isFailure)
        assertSame(failure, result.exceptionOrNull())
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
