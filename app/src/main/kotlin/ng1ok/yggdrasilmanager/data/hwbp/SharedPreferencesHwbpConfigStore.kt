package ng1ok.yggdrasilmanager.data.hwbp

import android.content.SharedPreferences
import java.io.IOException
import java.util.Collections
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class SharedPreferencesHwbpConfigStore(
    private val preferences: SharedPreferences,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : HwbpConfigStore {
    private val saveMutex = Mutex()

    @Volatile
    private var currentValue: List<HwbpSavedConfig>

    override val current: List<HwbpSavedConfig>
        get() = currentValue

    override val loadWarning: String?

    init {
        val loaded = readInitialConfigs()
        currentValue = loaded.configs
        loadWarning = loaded.warning
    }

    override suspend fun save(configs: List<HwbpSavedConfig>): Result<Unit> = withContext(ioDispatcher) {
        saveMutex.withLock {
            val snapshot = snapshot(configs)
            try {
                val committed = preferences.edit()
                    .putInt(KEY_SCHEMA_VERSION, SCHEMA_VERSION)
                    .putString(KEY_CONFIGS, encode(snapshot))
                    .commit()
                if (committed) {
                    currentValue = snapshot
                    Result.success(Unit)
                } else {
                    Result.failure(IOException("HWBP configuration commit failed"))
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                Result.failure(failure)
            }
        }
    }

    private fun readInitialConfigs(): LoadedConfigs = try {
        val schemaVersion = preferences.getInt(KEY_SCHEMA_VERSION, SCHEMA_VERSION)
        if (schemaVersion != SCHEMA_VERSION) {
            LoadedConfigs(emptyList(), "Unsupported saved HWBP configuration version")
        } else {
            decode(preferences.getString(KEY_CONFIGS, null))
        }
    } catch (_: RuntimeException) {
        LoadedConfigs(emptyList(), "Saved HWBP configurations could not be loaded")
    }

    private fun decode(raw: String?): LoadedConfigs {
        if (raw.isNullOrBlank()) return LoadedConfigs(emptyList())
        val values = runCatching { json.parseToJsonElement(raw) as JsonArray }
            .getOrElse { return LoadedConfigs(emptyList(), "Saved HWBP configurations are invalid") }
        val decoded = values.mapNotNull(::decodeConfig)
        val unique = LinkedHashMap<String, HwbpSavedConfig>()
        decoded.forEach { unique[it.id] = it }
        val warning = if (decoded.size != values.size || unique.size != decoded.size) {
            "Some saved HWBP configurations were invalid and were skipped"
        } else {
            null
        }
        return LoadedConfigs(snapshot(unique.values), warning)
    }

    private fun decodeConfig(element: kotlinx.serialization.json.JsonElement): HwbpSavedConfig? = runCatching {
        val value = element.jsonObject
        val id = value["id"]?.jsonPrimitive?.contentOrNull ?: return null
        val packageName = value["packageName"]?.jsonPrimitive?.contentOrNull ?: return null
        val lib = value["lib"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf(String::isNotEmpty)
        val offset = value["offset"]?.jsonPrimitive?.contentOrNull?.let(::parseHexAddress)
        val address = value["addr"]?.jsonPrimitive?.contentOrNull?.let(::parseHexAddress)
        HwbpSavedConfig(
            id = id,
            packageName = packageName,
            targetAddr = address,
            lib = lib,
            offset = offset,
            mode = value["mode"]?.jsonPrimitive?.intOrNull ?: return null,
            len = value["len"]?.jsonPrimitive?.intOrNull ?: return null,
            flags = value["flags"]?.jsonPrimitive?.intOrNull ?: return null,
            stackSize = value["stackSize"]?.jsonPrimitive?.intOrNull ?: return null,
            note = value["note"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        )
    }.getOrNull()

    private fun encode(configs: List<HwbpSavedConfig>): String = buildJsonArray {
        configs.forEach { config ->
            add(buildJsonObject {
                put("id", config.id)
                put("packageName", config.packageName)
                put("mode", config.mode)
                put("len", config.len)
                put("flags", config.flags)
                put("stackSize", config.stackSize)
                put("note", config.note)
                if (!config.lib.isNullOrBlank() && config.offset != null) {
                    put("lib", config.lib)
                    put("offset", config.offset.toHexAddress())
                } else {
                    put("addr", requireNotNull(config.targetAddr).toHexAddress())
                }
            })
        }
    }.toString()

    private fun snapshot(configs: Iterable<HwbpSavedConfig>): List<HwbpSavedConfig> {
        val unique = LinkedHashMap<String, HwbpSavedConfig>()
        configs.forEach { config -> unique[config.id] = config }
        return Collections.unmodifiableList(unique.values.toList())
    }

    private fun parseHexAddress(value: String): Long {
        val normalized = value.trim()
        require(normalized.startsWith("0x", ignoreCase = true)) { "address must be hexadecimal" }
        return normalized.drop(2).toULongOrNull(16)?.toLong()
            ?: throw IllegalArgumentException("address is invalid")
    }

    private fun Long.toHexAddress(): String =
        "0x${java.lang.Long.toUnsignedString(this, 16)}"

    private data class LoadedConfigs(
        val configs: List<HwbpSavedConfig>,
        val warning: String? = null,
    )

    companion object {
        const val FILE_NAME = "hwbp_configs"

        private const val SCHEMA_VERSION = 1
        private const val KEY_SCHEMA_VERSION = "schema_version"
        private const val KEY_CONFIGS = "configs"
        private val json = Json { ignoreUnknownKeys = true }
    }
}
