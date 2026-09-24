package ng1ok.yggdrasilmanager

import android.app.Application
import android.content.Context
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import ng1ok.yggdrasilmanager.data.YggdrasilRepository
import ng1ok.yggdrasilmanager.data.hidedbg.HideDbgConfigStore
import ng1ok.yggdrasilmanager.data.hidedbg.HideDbgMutationCoordinator
import ng1ok.yggdrasilmanager.data.hidedbg.InstalledAppsSource
import ng1ok.yggdrasilmanager.data.hidedbg.PackageManagerInstalledAppsSource
import ng1ok.yggdrasilmanager.data.hidedbg.SharedPreferencesHideDbgConfigStore
import ng1ok.yggdrasilmanager.data.hwbp.HwbpConfigStore
import ng1ok.yggdrasilmanager.data.hwbp.SharedPreferencesHwbpConfigStore

class App : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    lateinit var hideDbgConfigStore: HideDbgConfigStore
        private set

    lateinit var hideDbgMutationCoordinator: HideDbgMutationCoordinator
        private set

    lateinit var installedAppsSource: InstalledAppsSource
        private set

    lateinit var hwbpConfigStore: HwbpConfigStore
        private set

    override fun onCreate() {
        super.onCreate()
        // The KPM cannot report its rules, so startup restores Manager state without replaying it.
        hideDbgConfigStore = SharedPreferencesHideDbgConfigStore(
            getSharedPreferences(
                SharedPreferencesHideDbgConfigStore.FILE_NAME,
                Context.MODE_PRIVATE
            )
        )
        hideDbgMutationCoordinator = HideDbgMutationCoordinator(
            repo = YggdrasilRepository.instance,
            configStore = hideDbgConfigStore,
            applicationScope = applicationScope
        )
        installedAppsSource = PackageManagerInstalledAppsSource(packageManager)
        hwbpConfigStore = SharedPreferencesHwbpConfigStore(
            getSharedPreferences(
                SharedPreferencesHwbpConfigStore.FILE_NAME,
                Context.MODE_PRIVATE
            )
        )
        YggdrasilRepository.instance.useRootHwbp(this)
        YggdrasilRepository.instance.useRootSyscall(this)
        YggdrasilRepository.instance.useRootMaps(this)
        Shell.enableVerboseLogging = BuildConfig.DEBUG
        Shell.setDefaultBuilder(
            Shell.Builder.create()
                .setFlags(Shell.FLAG_REDIRECT_STDERR)
                .setTimeout(10)
        )
    }
}
