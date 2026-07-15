package ng1ok.yggdrasilmanager.data.hidedbg

import java.util.Locale

data class InstalledApp(
    val packageName: String,
    val label: String,
    val uid: Int,
    val isSystem: Boolean
)

enum class AppFilter {
    ALL,
    USER,
    SYSTEM
}

fun filterInstalledApps(
    apps: List<InstalledApp>,
    query: String,
    filter: AppFilter
): List<InstalledApp> {
    val normalizedQuery = query.trim().lowercase(Locale.ROOT)

    return apps.filter { app ->
        val matchesType = when (filter) {
            AppFilter.ALL -> true
            AppFilter.USER -> !app.isSystem
            AppFilter.SYSTEM -> app.isSystem
        }
        val matchesQuery = normalizedQuery.isEmpty() ||
            app.label.lowercase(Locale.ROOT).contains(normalizedQuery) ||
            app.packageName.lowercase(Locale.ROOT).contains(normalizedQuery)

        matchesType && matchesQuery
    }
}

fun findSharedUids(apps: List<InstalledApp>): Set<Int> = apps
    .groupingBy(InstalledApp::uid)
    .eachCount()
    .filterValues { count -> count > 1 }
    .keys
