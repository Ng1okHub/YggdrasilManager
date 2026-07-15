package ng1ok.yggdrasilmanager

import ng1ok.yggdrasilmanager.ui.hidedbg.HideDbgUiState
import ng1ok.yggdrasilmanager.ui.hidedbg.canToggleUid
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HideDbgPresentationTest {
    private val uid = 1000

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
}
