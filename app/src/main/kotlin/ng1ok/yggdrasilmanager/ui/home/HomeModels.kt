package ng1ok.yggdrasilmanager.ui.home

enum class RootStatus {
    CHECKING,
    GRANTED,
    DENIED
}

val RootStatus.label: String
    get() = when (this) {
        RootStatus.CHECKING -> "檢查中"
        RootStatus.GRANTED -> "已授權"
        RootStatus.DENIED -> "未授權"
    }

enum class YggdrasilStatus {
    CHECKING,
    AVAILABLE,
    UNAVAILABLE
}

val YggdrasilStatus.label: String
    get() = when (this) {
        YggdrasilStatus.CHECKING -> "檢查中"
        YggdrasilStatus.AVAILABLE -> "可用"
        YggdrasilStatus.UNAVAILABLE -> "不可用"
    }

data class YggdrasilVersion(
    val major: Int,
    val minor: Int,
    val patch: Int
) {
    val label: String
        get() = "$major.$minor.$patch"

    companion object {
        fun fromPacked(value: Long): YggdrasilVersion? {
            if (value < 0) return null
            return YggdrasilVersion(
                major = ((value shr 16) and 0xff).toInt(),
                minor = ((value shr 8) and 0xff).toInt(),
                patch = (value and 0xff).toInt()
            )
        }
    }
}

data class DeviceInfo(
    val manufacturer: String,
    val model: String,
    val androidVersion: String,
    val apiLevel: Int,
    val kernelVersion: String,
    val managerVersion: String
)

val DeviceInfo.displayName: String
    get() {
        val trimmedManufacturer = manufacturer.trim()
        val trimmedModel = model.trim()

        return when {
            trimmedModel.isBlank() -> trimmedManufacturer.ifBlank { "未知設備" }
            trimmedManufacturer.isBlank() -> trimmedModel
            trimmedModel.startsWith(trimmedManufacturer, ignoreCase = true) -> trimmedModel
            else -> "$trimmedManufacturer $trimmedModel"
        }
    }

val DeviceInfo.androidLabel: String
    get() = "Android ${androidVersion.ifBlank { "未知" }} (API $apiLevel)"

data class HomeUiState(
    val deviceInfo: DeviceInfo,
    val rootStatus: RootStatus = RootStatus.CHECKING,
    val yggdrasilStatus: YggdrasilStatus = YggdrasilStatus.CHECKING,
    val yggdrasilVersion: YggdrasilVersion? = null,
    val showRootDeniedDialog: Boolean = false,
    val hasShownRootDeniedPrompt: Boolean = false
)
