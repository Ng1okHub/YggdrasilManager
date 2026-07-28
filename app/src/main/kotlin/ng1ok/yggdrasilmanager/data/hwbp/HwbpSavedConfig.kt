package ng1ok.yggdrasilmanager.data.hwbp

import java.util.Collections

/**
 * A reusable HWBP definition. Runtime PIDs deliberately do not belong here:
 * a package is resolved to its current PID each time the configuration is applied.
 */
data class HwbpSavedConfig(
    val id: String,
    val packageName: String,
    val targetAddr: Long? = null,
    val lib: String? = null,
    val offset: Long? = null,
    val mode: Int,
    val len: Int,
    val flags: Int,
    val stackSize: Int,
    val note: String = "",
) {
    init {
        require(id.isNotBlank() && id.length <= MAX_ID_LENGTH) { "config id is invalid" }
        require(packageName.isNotBlank() && packageName.length <= MAX_PACKAGE_NAME_LENGTH) {
            "packageName is invalid"
        }
        require(mode in VALID_MODES) { "mode must be R, W, RW, or X" }
        require(len in VALID_LENGTHS) { "len must be 1, 2, 4, or 8" }
        require(mode != MODE_EXECUTE || len == 4) { "X mode requires len 4" }
        require(flags >= 0) { "flags must be non-negative" }
        require(stackSize in 0..MAX_STACK_SIZE) { "stackSize must be between 0 and $MAX_STACK_SIZE" }
        require(note.length <= MAX_NOTE_LENGTH) { "note is too long" }

        val hasLibrary = !lib.isNullOrBlank()
        if (hasLibrary) {
            require(offset != null) { "offset is required when lib is set" }
            require(targetAddr == null) { "addr cannot be set with lib" }
        } else {
            require(targetAddr != null) { "addr is required when lib is not set" }
            require(offset == null) { "offset requires lib" }
        }
    }

    val displayTarget: String
        get() = if (!lib.isNullOrBlank() && offset != null) {
            "$lib+0x${java.lang.Long.toUnsignedString(offset, 16)}"
        } else {
            "0x${java.lang.Long.toUnsignedString(requireNotNull(targetAddr), 16)}"
        }

    companion object {
        const val MODE_EXECUTE = 4
        const val MAX_STACK_SIZE = 65_536
        const val MAX_ID_LENGTH = 128
        const val MAX_PACKAGE_NAME_LENGTH = 255
        const val MAX_NOTE_LENGTH = 2_000

        private val VALID_MODES = setOf(1, 2, 3, 4)
        private val VALID_LENGTHS = setOf(1, 2, 4, 8)
    }
}

data class HwbpConfigDraft(
    val id: String? = null,
    val packageName: String,
    val targetAddr: Long? = null,
    val lib: String? = null,
    val offset: Long? = null,
    val mode: Int,
    val len: Int,
    val flags: Int,
    val stackSize: Int,
    val note: String = "",
) {
    fun toSavedConfig(newId: String): HwbpSavedConfig = HwbpSavedConfig(
        id = id?.trim()?.takeIf(String::isNotEmpty) ?: newId,
        packageName = packageName.trim(),
        targetAddr = targetAddr,
        lib = lib?.trim()?.takeIf(String::isNotEmpty),
        offset = offset,
        mode = mode,
        len = len,
        flags = flags,
        stackSize = stackSize,
        note = note.trim(),
    )
}

interface HwbpConfigStore {
    val current: List<HwbpSavedConfig>
    val loadWarning: String?

    suspend fun save(configs: List<HwbpSavedConfig>): Result<Unit>
}

object EmptyHwbpConfigStore : HwbpConfigStore {
    override val current: List<HwbpSavedConfig> = Collections.emptyList()
    override val loadWarning: String? = null

    override suspend fun save(configs: List<HwbpSavedConfig>): Result<Unit> = Result.success(Unit)
}
