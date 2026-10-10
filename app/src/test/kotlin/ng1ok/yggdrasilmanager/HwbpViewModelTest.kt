package ng1ok.yggdrasilmanager

import androidx.lifecycle.ViewModelStore
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import ng1ok.yggdrasilmanager.data.YggdrasilRepository
import ng1ok.yggdrasilmanager.data.hidedbg.InstalledApp
import ng1ok.yggdrasilmanager.data.hidedbg.InstalledAppsSource
import ng1ok.yggdrasilmanager.data.hwbp.HwbpConfigDraft
import ng1ok.yggdrasilmanager.data.hwbp.HwbpConfigStore
import ng1ok.yggdrasilmanager.data.hwbp.HwbpSavedConfig
import ng1ok.yggdrasilmanager.data.model.HwbpEvent
import ng1ok.yggdrasilmanager.data.model.HwbpObserveParams
import ng1ok.yggdrasilmanager.data.model.ProcMapsSource
import ng1ok.yggdrasilmanager.ui.hwbp.HwbpServer
import ng1ok.yggdrasilmanager.ui.hwbp.HwbpServerBindings
import ng1ok.yggdrasilmanager.ui.hwbp.HwbpServerFactory
import ng1ok.yggdrasilmanager.ui.hwbp.HwbpViewModel
import ng1ok.yggdrasilmanager.ui.hwbp.HwbpLibraryTargetResolver
import ng1ok.yggdrasilmanager.ui.hwbp.HwbpPackagePidResolver
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HwbpViewModelTest {
    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var repo: YggdrasilRepository
    private lateinit var server: FakeHwbpServer
    private lateinit var bindings: HwbpServerBindings
    private lateinit var viewModel: HwbpViewModel
    private lateinit var configStore: FakeHwbpConfigStore
    private val apps = listOf(InstalledApp("com.example.target", "Target", 1234, false))
    private val mapsRequests = mutableListOf<Int>()
    private var libraryMaps: String? = """
        1000-2000 r-xp 00000000 00:00 0 /system/lib64/libalpha.so
        2000-3000 r--p 00001000 00:00 0 /system/lib64/libalpha.so
    """.trimIndent()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        repo = mockk(relaxed = true)
        every { repo.hwbpEvents } returns MutableSharedFlow<HwbpEvent>()
        server = FakeHwbpServer()
        configStore = FakeHwbpConfigStore()
        viewModel = HwbpViewModel(
            repo = repo,
            serverFactory = HwbpServerFactory { captured ->
                bindings = captured
                server
            },
            configStore = configStore,
            installedAppsSource = InstalledAppsSource { Result.success(apps) },
            packagePidResolver = HwbpPackagePidResolver { 4321 },
            mapsSource = ProcMapsSource { pid -> mapsRequests += pid; libraryMaps },
            libraryTargetResolver = HwbpLibraryTargetResolver { _, _, offset, _ -> 0x7a00000000L + offset },
            libraryNamesResolver = { _, _ -> listOf("libembedded.so", "libalpha.so") },
        )
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
    }

    @Test
    fun initStartsNativeStreamAndWebServer() = runTest {
        waitUntil { server.startCount == 1 }

        verify(exactly = 1) { repo.startEventStream() }
        assertTrue(server.running.value)
    }

    @Test
    fun serverOptionsUseInstalledAppsAndMapsForTheSelectedProcess() = runTest {
        assertEquals(apps, bindings.installedApps().getOrThrow())
        assertEquals(listOf("libalpha.so", "libembedded.so"),
            bindings.libraryNames("com.example.target", null).getOrThrow())
        assertEquals(listOf("libalpha.so", "libembedded.so"), bindings.libraryNames(null, 7654).getOrThrow())
        assertEquals(listOf(4321, 7654), mapsRequests)
    }

    @Test
    fun unreadableMapsAreReportedAsAnOptionLoadingFailure() = runTest {
        libraryMaps = null

        val result = bindings.libraryNames("com.example.target", null)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("/proc/4321/maps"))
    }

    @Test
    fun websocketObserveUpdatesTheSameObservePointState() = runTest {
        val params = point(pid = 1234, address = 0x1000)
        coEvery { repo.hwbpObserve(params) } returns Result.success(Unit)

        val result = bindings.observe(params)

        assertTrue(result.isSuccess)
        assertEquals(listOf(params), viewModel.observePoints.value)
        assertEquals(0, server.broadcastCount)
    }

    @Test
    fun duplicatePidAndAddressReplaceTheExistingPoint() = runTest {
        val first = point(pid = 1234, address = 0x1000, mode = 1)
        val replacement = point(pid = 1234, address = 0x1000, mode = 3)
        coEvery { repo.hwbpObserve(any()) } returns Result.success(Unit)

        bindings.observe(first)
        bindings.observe(replacement)

        assertEquals(listOf(replacement), viewModel.observePoints.value)
    }

    @Test
    fun failedObserveLeavesPointsUnchangedAndPublishesError() = runTest {
        val params = point(pid = 1234, address = 0x1000)
        coEvery { repo.hwbpObserve(params) } returns
            Result.failure(IllegalStateException("native rejected"))

        val result = bindings.observe(params)

        assertTrue(result.isFailure)
        assertTrue(viewModel.observePoints.value.isEmpty())
        assertEquals("native rejected", viewModel.error.value)
    }

    @Test
    fun websocketUnobserveRemovesByPidAndAddress() = runTest {
        val first = point(pid = 1234, address = 0x1000)
        val second = point(pid = 1234, address = 0x2000)
        coEvery { repo.hwbpObserve(any()) } returns Result.success(Unit)
        coEvery { repo.hwbpUnobserve(1234, 0x1000) } returns Result.success(Unit)
        bindings.observe(first)
        bindings.observe(second)

        val result = bindings.unobserve(1234, 0x1000)

        assertTrue(result.isSuccess)
        assertEquals(listOf(second), viewModel.observePoints.value)
    }

    @Test
    fun androidMutationBroadcastsStateAfterSuccess() = runTest {
        val params = point(pid = 55, address = 0x3000)
        coEvery { repo.hwbpObserve(params) } returns Result.success(Unit)

        viewModel.addObserve(params)

        waitUntil { server.broadcastCount == 1 }
        assertEquals(listOf(params), viewModel.observePoints.value)
    }

    @Test
    fun savedConfigCanBeCreatedUpdatedAndDeletedThroughServerBindings() = runTest {
        val first = savedConfigDraft(note = "first")

        assertTrue(bindings.saveConfig(first).isSuccess)
        assertEquals("first", viewModel.savedConfigs.value.single().note)
        assertEquals(viewModel.savedConfigs.value, configStore.current)

        assertTrue(bindings.saveConfig(first.copy(note = "updated")).isSuccess)
        assertEquals("updated", viewModel.savedConfigs.value.single().note)

        assertTrue(bindings.deleteConfig("system-lib").isSuccess)
        assertTrue(viewModel.savedConfigs.value.isEmpty())
    }

    @Test
    fun applyingSavedConfigResolvesFreshPidAndLibraryAddress() = runTest {
        val draft = savedConfigDraft()
        assertTrue(bindings.saveConfig(draft).isSuccess)
        val expected = HwbpObserveParams(
            pid = 4321,
            targetAddr = 0x7a00000020L,
            mode = 3,
            len = 8,
            flags = 1,
            stackSize = 256,
            lib = "libfoo.so",
            offset = 0x20,
            packageName = "com.example.target",
        )
        coEvery { repo.hwbpObserve(expected) } returns Result.success(Unit)

        val result = bindings.applyConfig("system-lib")

        assertTrue(result.isSuccess)
        assertEquals(listOf(expected), viewModel.observePoints.value)
    }

    @Test
    fun toggleStopsAndRestartsTheServer() = runTest {
        waitUntil { server.startCount == 1 }

        viewModel.toggleServer()
        waitUntil { server.stopCount == 1 }
        assertFalse(server.running.value)
        verify(exactly = 0) { repo.stopEventStream() }

        viewModel.toggleServer()
        waitUntil { server.startCount == 2 }
        assertTrue(server.running.value)
        verify(exactly = 1) { repo.startEventStream() }
    }

    @Test
    fun serverFlowsFeedUiState() = runTest {
        server.connected.value = 2
        server.http.value = "http://192.168.1.2:8765"
        server.webSocket.value = "ws://192.168.1.2:8765/ws"
        val params = point(pid = 9, address = 0x4000)
        coEvery { repo.hwbpObserve(params) } returns Result.success(Unit)
        bindings.observe(params)

        waitUntil {
            viewModel.uiState.value.connectedClients == 2 &&
                viewModel.uiState.value.observePointCount == 1
        }

        assertEquals("http://192.168.1.2:8765", viewModel.uiState.value.httpUrl)
        assertEquals("ws://192.168.1.2:8765/ws", viewModel.uiState.value.webSocketUrl)
    }

    @Test
    fun clearingViewModelStopsServerButKeepsNativeStreamAlive() = runTest {
        waitUntil { server.startCount == 1 }
        val store = ViewModelStore()
        store.put("hwbp", viewModel)

        store.clear()

        assertEquals(1, server.stopCount)
        verify(exactly = 0) { repo.stopEventStream() }
    }

    private suspend fun waitUntil(predicate: () -> Boolean) {
        withTimeout(1_000) {
            while (!predicate()) yield()
        }
    }

    private fun point(
        pid: Int,
        address: Long,
        mode: Int = 1,
    ) = HwbpObserveParams(pid, address, mode, 4, 0, 256)

    private fun savedConfigDraft(note: String = "watch entry") = HwbpConfigDraft(
        id = "system-lib",
        packageName = "com.example.target",
        lib = "libfoo.so",
        offset = 0x20,
        mode = 3,
        len = 8,
        flags = 1,
        stackSize = 256,
        note = note,
    )
}

private class FakeHwbpConfigStore(
    initial: List<HwbpSavedConfig> = emptyList(),
) : HwbpConfigStore {
    override var current: List<HwbpSavedConfig> = initial
        private set
    override val loadWarning: String? = null

    override suspend fun save(configs: List<HwbpSavedConfig>): Result<Unit> {
        current = configs
        return Result.success(Unit)
    }
}

private class FakeHwbpServer : HwbpServer {
    override val running = MutableStateFlow(false)
    val connected = MutableStateFlow(0)
    val http = MutableStateFlow<String?>(null)
    val webSocket = MutableStateFlow<String?>(null)
    override val connectedClients: StateFlow<Int> = connected
    override val httpUrl: StateFlow<String?> = http
    override val webSocketUrl: StateFlow<String?> = webSocket

    var startCount = 0
    var stopCount = 0
    var broadcastCount = 0

    override fun start(): Result<Unit> {
        startCount += 1
        running.value = true
        return Result.success(Unit)
    }

    override fun stop() {
        stopCount += 1
        running.value = false
    }

    override suspend fun broadcastState() {
        broadcastCount += 1
    }
}
