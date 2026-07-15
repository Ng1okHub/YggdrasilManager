package ng1ok.yggdrasilmanager.data.hidedbg

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import java.text.Collator
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun interface InstalledAppsSource {
    suspend fun load(): Result<List<InstalledApp>>
}

internal data class PackageRecord(
    val packageName: String,
    val label: String,
    val uid: Int,
    val flags: Int
)

class PackageManagerInstalledAppsSource internal constructor(
    packageManager: PackageManager,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val queryRecords: () -> List<PackageRecord> = {
        packageManager.queryInstalledAppRecords()
    }
) : InstalledAppsSource {
    override suspend fun load(): Result<List<InstalledApp>> = withContext(ioDispatcher) {
        try {
            val labelCollator = Collator.getInstance(Locale.getDefault())
            val appComparator = Comparator<InstalledApp> { first, second ->
                val labelComparison = labelCollator.compare(first.label, second.label)
                if (labelComparison != 0) {
                    labelComparison
                } else {
                    first.packageName.compareTo(second.packageName)
                }
            }

            Result.success(
                queryRecords()
                    .map(PackageRecord::toInstalledApp)
                    .sortedWith(appComparator)
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            Result.failure(failure)
        }
    }
}

private fun PackageRecord.toInstalledApp(): InstalledApp = InstalledApp(
    packageName = packageName,
    label = label.takeUnless(String::isBlank) ?: packageName,
    uid = uid,
    isSystem = flags and ApplicationInfo.FLAG_SYSTEM != 0 ||
        flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0
)

private fun PackageManager.queryInstalledAppRecords(): List<PackageRecord> =
    getInstalledApplicationsCompat().map { info ->
        PackageRecord(
            packageName = info.packageName,
            label = try {
                getApplicationLabel(info).toString()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                info.packageName
            },
            uid = info.uid,
            flags = info.flags
        )
    }

private fun PackageManager.getInstalledApplicationsCompat(): List<ApplicationInfo> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
    } else {
        getInstalledApplicationsLegacy()
    }

@Suppress("DEPRECATION")
private fun PackageManager.getInstalledApplicationsLegacy(): List<ApplicationInfo> =
    getInstalledApplications(0)
