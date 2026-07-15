package ng1ok.yggdrasilmanager

import android.app.Application
import android.content.Context
import com.topjohnwu.superuser.Shell
import ng1ok.yggdrasilmanager.data.hidedbg.HideDbgConfigStore
import ng1ok.yggdrasilmanager.data.hidedbg.SharedPreferencesHideDbgConfigStore

class App : Application() {
    lateinit var hideDbgConfigStore: HideDbgConfigStore
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
        Shell.enableVerboseLogging = BuildConfig.DEBUG
        Shell.setDefaultBuilder(
            Shell.Builder.create()
                .setFlags(Shell.FLAG_REDIRECT_STDERR)
                .setTimeout(10)
        )
    }
}
