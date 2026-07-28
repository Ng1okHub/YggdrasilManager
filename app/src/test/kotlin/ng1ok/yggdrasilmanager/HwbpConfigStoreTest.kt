package ng1ok.yggdrasilmanager

import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import ng1ok.yggdrasilmanager.data.hwbp.HwbpSavedConfig
import ng1ok.yggdrasilmanager.data.hwbp.SharedPreferencesHwbpConfigStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HwbpConfigStoreTest {
    @Test
    fun validConfigReadsReusableLibraryDefinition() {
        val preferences = mockk<SharedPreferences>()
        every { preferences.getInt("schema_version", 1) } returns 1
        every { preferences.getString("configs", null) } returns """
            [{"id":"system-lib","packageName":"com.example.target","lib":"libfoo.so","offset":"0x20","mode":3,"len":8,"flags":1,"stackSize":256,"note":"watch entry"}]
        """.trimIndent()

        val store = SharedPreferencesHwbpConfigStore(preferences)

        assertEquals(listOf(config()), store.current)
        assertNull(store.loadWarning)
    }

    @Test
    fun malformedRecordsAreSkippedWithoutDiscardingValidConfigs() {
        val preferences = mockk<SharedPreferences>()
        every { preferences.getInt("schema_version", 1) } returns 1
        every { preferences.getString("configs", null) } returns """
            [{"id":"valid","packageName":"com.example.target","addr":"0x1000","mode":1,"len":4,"flags":0,"stackSize":256,"note":""},{"id":"bad","packageName":"com.example.target","mode":1}]
        """.trimIndent()

        val store = SharedPreferencesHwbpConfigStore(preferences)

        assertEquals(1, store.current.size)
        assertEquals("valid", store.current.single().id)
        assertTrue(store.loadWarning.orEmpty().contains("invalid"))
    }

    @Test
    fun saveCommitsVersionedJsonAndUpdatesCurrentAfterSuccess() = runTest {
        val preferences = initialPreferences()
        val editor = writableEditor(preferences)
        every { editor.commit() } returns true
        val store = SharedPreferencesHwbpConfigStore(
            preferences,
            UnconfinedTestDispatcher(testScheduler),
        )

        val result = store.save(listOf(config()))

        assertTrue(result.isSuccess)
        verify(exactly = 1) { editor.putInt("schema_version", 1) }
        verify(exactly = 1) { editor.putString("configs", any()) }
        assertEquals(listOf(config()), store.current)
    }

    @Test
    fun failedCommitKeepsTheLastPersistedSnapshot() = runTest {
        val preferences = initialPreferences()
        val editor = writableEditor(preferences)
        every { editor.commit() } returns false
        val store = SharedPreferencesHwbpConfigStore(
            preferences,
            UnconfinedTestDispatcher(testScheduler),
        )

        val result = store.save(listOf(config()))

        assertTrue(result.isFailure)
        assertTrue(store.current.isEmpty())
    }

    private fun config() = HwbpSavedConfig(
        id = "system-lib",
        packageName = "com.example.target",
        lib = "libfoo.so",
        offset = 0x20,
        mode = 3,
        len = 8,
        flags = 1,
        stackSize = 256,
        note = "watch entry",
    )

    private fun initialPreferences(): SharedPreferences = mockk {
        every { getInt("schema_version", 1) } returns 1
        every { getString("configs", null) } returns null
    }

    private fun writableEditor(preferences: SharedPreferences): SharedPreferences.Editor {
        val editor = mockk<SharedPreferences.Editor>()
        every { preferences.edit() } returns editor
        every { editor.putInt(any(), any()) } returns editor
        every { editor.putString(any(), any()) } returns editor
        return editor
    }
}
