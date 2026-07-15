package ng1ok.yggdrasilmanager.data.hidedbg

import android.content.SharedPreferences
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class SharedPreferencesHideDbgConfigStore(
    private val preferences: SharedPreferences,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : HideDbgConfigStore {
    private val saveMutex = Mutex()

    @Volatile
    private var currentValue: HideDbgConfig

    override val current: HideDbgConfig
        get() = currentValue

    override val loadWarning: String?

    init {
        val loadedConfig = readInitialConfig()
        currentValue = loadedConfig.config
        loadWarning = loadedConfig.warning
    }

    override suspend fun save(config: HideDbgConfig): Result<Unit> = withContext(ioDispatcher) {
        saveMutex.withLock {
            val stableConfig = config.copy(enabledUids = config.enabledUids.toSet())
            currentValue = stableConfig

            try {
                val committed = preferences.edit()
                    .putInt(KEY_SCHEMA_VERSION, SCHEMA_VERSION)
                    .putBoolean(KEY_GLOBAL_ENABLED, stableConfig.globalEnabled)
                    .putStringSet(
                        KEY_ENABLED_UIDS,
                        stableConfig.enabledUids.mapTo(mutableSetOf(), Int::toString)
                    )
                    .commit()

                if (committed) {
                    Result.success(Unit)
                } else {
                    Result.failure(IOException("Hide DBG configuration commit failed"))
                }
            } catch (failure: Throwable) {
                Result.failure(failure)
            }
        }
    }

    private fun readInitialConfig(): LoadedConfig = try {
        val schemaVersion = preferences.getInt(KEY_SCHEMA_VERSION, SCHEMA_VERSION)
        if (schemaVersion != SCHEMA_VERSION) {
            LoadedConfig(
                config = HideDbgConfig(),
                warning = "不支援的 Hide DBG 配置版本：$schemaVersion"
            )
        } else {
            LoadedConfig(
                config = HideDbgConfig(
                    globalEnabled = preferences.getBoolean(KEY_GLOBAL_ENABLED, false),
                    enabledUids = preferences.getStringSet(KEY_ENABLED_UIDS, emptySet())
                        .orEmpty()
                        .mapNotNullTo(mutableSetOf()) { it.toValidUidOrNull() }
                        .toSet()
                )
            )
        }
    } catch (_: RuntimeException) {
        LoadedConfig(
            config = HideDbgConfig(),
            warning = "Hide DBG 配置無法讀取，已使用預設值"
        )
    }

    private fun String.toValidUidOrNull(): Int? {
        if (isEmpty() || any { it !in '0'..'9' }) return null
        return toIntOrNull()?.takeIf { it >= 0 }
    }

    private data class LoadedConfig(
        val config: HideDbgConfig,
        val warning: String? = null
    )

    companion object {
        const val FILE_NAME = "hide_dbg_config"

        private const val SCHEMA_VERSION = 1
        private const val KEY_SCHEMA_VERSION = "schema_version"
        private const val KEY_GLOBAL_ENABLED = "global_enabled"
        private const val KEY_ENABLED_UIDS = "enabled_uids"
    }
}
