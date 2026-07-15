package ng1ok.yggdrasilmanager

import ng1ok.yggdrasilmanager.ui.home.DeviceInfo
import ng1ok.yggdrasilmanager.ui.home.RootStatus
import ng1ok.yggdrasilmanager.ui.home.YggdrasilStatus
import ng1ok.yggdrasilmanager.ui.home.androidLabel
import ng1ok.yggdrasilmanager.ui.home.displayName
import ng1ok.yggdrasilmanager.ui.home.label
import org.junit.Assert.assertEquals
import org.junit.Test

class HomePresentationTest {
    @Test
    fun statusesHaveStableVisibleLabels() {
        assertEquals("檢查中", RootStatus.CHECKING.label)
        assertEquals("已授權", RootStatus.GRANTED.label)
        assertEquals("未授權", RootStatus.DENIED.label)
        assertEquals("可用", YggdrasilStatus.AVAILABLE.label)
        assertEquals("不可用", YggdrasilStatus.UNAVAILABLE.label)
    }

    @Test
    fun deviceLabelsAvoidDuplicatingManufacturer() {
        val device = DeviceInfo("Google", "Google Pixel 8", "15", 35, "6.1", "1.0")
        assertEquals("Google Pixel 8", device.displayName)
        assertEquals("Android 15 (API 35)", device.androidLabel)
    }
}
