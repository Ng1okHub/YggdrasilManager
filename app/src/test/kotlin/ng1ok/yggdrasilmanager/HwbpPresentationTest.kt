package ng1ok.yggdrasilmanager

import ng1ok.yggdrasilmanager.ui.hwbp.HwbpUiState
import ng1ok.yggdrasilmanager.ui.hwbp.serverStatusLabel
import org.junit.Assert.assertEquals
import org.junit.Test

class HwbpPresentationTest {
    @Test
    fun statusLabelsAreDeterministic() {
        assertEquals("Stopped", HwbpUiState().serverStatusLabel())
        assertEquals("Running", HwbpUiState(serverRunning = true).serverStatusLabel())
    }
}
