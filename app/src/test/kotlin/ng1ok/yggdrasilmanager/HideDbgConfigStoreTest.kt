package ng1ok.yggdrasilmanager

import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import ng1ok.yggdrasilmanager.data.hidedbg.HideDbgConfig
import ng1ok.yggdrasilmanager.data.hidedbg.SharedPreferencesHideDbgConfigStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HideDbgConfigStoreTest {
    @Test
    fun validSchema_readsValidatedConfiguration() = runTest {
        val preferences = mockk<SharedPreferences>()
        every { preferences.getInt("schema_version", 1) } returns 1
        every { preferences.getBoolean("global_enabled", false) } returns true
        every { preferences.getStringSet("enabled_uids", emptySet()) } returns setOf(
            "1000",
            "-1",
            "abc",
            "2147483648",
            "1000"
        )

        val store = SharedPreferencesHideDbgConfigStore(
            preferences,
            UnconfinedTestDispatcher(testScheduler)
        )

        assertEquals(HideDbgConfig(true, setOf(1000)), store.current)
        assertNull(store.loadWarning)
    }

    @Test
    fun unsupportedSchema_usesSafeDefaultAndReportsWarning() = runTest {
        val preferences = mockk<SharedPreferences>(relaxed = true)
        every { preferences.getInt("schema_version", 1) } returns 2

        val store = SharedPreferencesHideDbgConfigStore(
            preferences,
            UnconfinedTestDispatcher(testScheduler)
        )

        assertEquals(HideDbgConfig(), store.current)
        assertEquals("不支援的 Hide DBG 配置版本：2", store.loadWarning)
    }

    @Test
    fun successfulCommit_persistsAndUpdatesCurrentConfiguration() = runTest {
        val preferences = initialPreferences()
        val editor = mockk<SharedPreferences.Editor>()
        every { preferences.edit() } returns editor
        every { editor.putInt(any(), any()) } returns editor
        every { editor.putBoolean(any(), any()) } returns editor
        every { editor.putStringSet(any(), any()) } returns editor
        every { editor.commit() } returns true
        val store = SharedPreferencesHideDbgConfigStore(
            preferences,
            UnconfinedTestDispatcher(testScheduler)
        )
        val changed = HideDbgConfig(true, setOf(1000, 1001))

        val result = store.save(changed)

        assertTrue(result.isSuccess)
        verify(exactly = 1) { editor.putInt("schema_version", 1) }
        verify(exactly = 1) { editor.putBoolean("global_enabled", true) }
        verify(exactly = 1) {
            editor.putStringSet("enabled_uids", setOf("1000", "1001"))
        }
        assertEquals(changed, store.current)
    }

    @Test
    fun failedCommit_keepsUpdatedCurrentConfiguration() = runTest {
        val preferences = initialPreferences()
        val editor = mockk<SharedPreferences.Editor>()
        every { preferences.edit() } returns editor
        every { editor.putInt(any(), any()) } returns editor
        every { editor.putBoolean(any(), any()) } returns editor
        every { editor.putStringSet(any(), any()) } returns editor
        every { editor.commit() } returns false
        val store = SharedPreferencesHideDbgConfigStore(
            preferences,
            UnconfinedTestDispatcher(testScheduler)
        )
        val changed = HideDbgConfig(false, setOf(1000))

        val result = store.save(changed)

        assertTrue(result.isFailure)
        assertEquals(changed, store.current)
    }

    private fun initialPreferences(): SharedPreferences = mockk {
        every { getInt("schema_version", 1) } returns 1
        every { getBoolean("global_enabled", false) } returns false
        every { getStringSet("enabled_uids", emptySet()) } returns emptySet()
    }
}
