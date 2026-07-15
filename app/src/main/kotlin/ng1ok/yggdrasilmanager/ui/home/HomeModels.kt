package ng1ok.yggdrasilmanager.ui.home

enum class RootStatus {
    CHECKING,
    GRANTED,
    DENIED
}

enum class YggdrasilStatus {
    CHECKING,
    AVAILABLE,
    UNAVAILABLE
}

data class DeviceInfo(
    val manufacturer: String,
    val model: String,
    val androidVersion: String,
    val apiLevel: Int,
    val kernelVersion: String,
    val managerVersion: String
)

data class HomeUiState(
    val deviceInfo: DeviceInfo,
    val rootStatus: RootStatus = RootStatus.CHECKING,
    val yggdrasilStatus: YggdrasilStatus = YggdrasilStatus.CHECKING,
    val showRootDeniedDialog: Boolean = false,
    val hasShownRootDeniedPrompt: Boolean = false
) {
    val rootGranted: Boolean
        get() = rootStatus == RootStatus.GRANTED

    val moduleAvailable: Boolean
        get() = yggdrasilStatus == YggdrasilStatus.AVAILABLE
}
