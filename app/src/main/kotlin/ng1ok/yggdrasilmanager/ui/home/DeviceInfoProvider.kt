package ng1ok.yggdrasilmanager.ui.home

import android.os.Build
import ng1ok.yggdrasilmanager.BuildConfig

fun interface DeviceInfoProvider {
    fun getDeviceInfo(): DeviceInfo
}

object AndroidDeviceInfoProvider : DeviceInfoProvider {
    override fun getDeviceInfo() = DeviceInfo(
        manufacturer = Build.MANUFACTURER.orEmpty(),
        model = Build.MODEL.orEmpty(),
        androidVersion = Build.VERSION.RELEASE.orEmpty(),
        apiLevel = Build.VERSION.SDK_INT,
        kernelVersion = System.getProperty("os.version").orEmpty().ifBlank { "未知" },
        managerVersion = BuildConfig.VERSION_NAME
    )
}
