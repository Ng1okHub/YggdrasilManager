package ng1ok.yggdrasilmanager

import ng1ok.yggdrasilmanager.data.hidedbg.InstalledApp
import ng1ok.yggdrasilmanager.ui.hidedbg.HideDbgUiState
import ng1ok.yggdrasilmanager.ui.hidedbg.canToggleUid
import ng1ok.yggdrasilmanager.ui.hidedbg.hideDbgToggleLabel
import ng1ok.yggdrasilmanager.ui.hidedbg.isAppLoadErrorBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HideDbgPresentationTest {
    private val uid = 1000
    private val app = InstalledApp("com.example", "Example", uid, false)

    @Test
    fun canToggleUid_allowsUidWithoutPendingOrGlobalState() {
        assertTrue(HideDbgUiState().canToggleUid(uid))
    }

    @Test
    fun canToggleUid_rejectsUidWhenGlobalRuleIsEnabled() {
        assertFalse(HideDbgUiState(globalEnabled = true).canToggleUid(uid))
    }

    @Test
    fun canToggleUid_rejectsUidWhileGlobalRuleIsPending() {
        assertFalse(HideDbgUiState(globalPending = true).canToggleUid(uid))
    }

    @Test
    fun canToggleUid_rejectsSamePendingUid() {
        assertFalse(HideDbgUiState(pendingUids = setOf(uid)).canToggleUid(uid))
    }

    @Test
    fun canToggleUid_allowsUidWhenOnlyAnotherUidIsPending() {
        assertTrue(HideDbgUiState(pendingUids = setOf(1001)).canToggleUid(uid))
    }

    @Test
    fun appLoadError_isBlockingWithoutCachedApps() {
        assertTrue(HideDbgUiState(appLoadError = "packages").isAppLoadErrorBlocking())
    }

    @Test
    fun appLoadError_isNonBlockingWithCachedApps() {
        val state = HideDbgUiState(
            apps = listOf(app),
            appLoadError = "refresh failed"
        )

        assertFalse(state.isAppLoadErrorBlocking())
    }

    @Test
    fun hideDbgToggleLabel_distinguishesAppsWithTheSameLabel() {
        val first = app.copy(label = "Same", packageName = "com.first")
        val second = app.copy(label = "Same", packageName = "com.second")

        assertEquals("Same (com.first)", first.hideDbgToggleLabel())
        assertEquals("Same (com.second)", second.hideDbgToggleLabel())
    }
}
