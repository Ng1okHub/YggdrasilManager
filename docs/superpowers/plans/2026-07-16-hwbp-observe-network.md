# HWBP Observe Network Refactor Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move HWBP observe-point control and event viewing to a PC browser served by an embedded Android HTTP/WebSocket server, leaving the Android route as a compact server-status page.

**Architecture:** `HwbpWebServer` owns Ktor CIO lifecycle, Android asset delivery, WebSocket sessions, and event fan-out. A small package-internal protocol codec keeps JSON validation testable on the JVM, while `HwbpViewModel` remains the single owner of observe-point mutations so Android and WebSocket callers cannot diverge.

**Tech Stack:** Kotlin 2.x, Android/Compose Material 3, Ktor 2.3.12 CIO + WebSockets, kotlinx.serialization JSON, JUnit 4/MockK/coroutines-test, browser ES modules, Node 24 built-in test runner.

---

## File Structure

- Modify `gradle/libs.versions.toml`: pin Ktor 2.3.12 and expose production/test aliases.
- Modify `app/build.gradle`: add Ktor runtime and JVM server-test dependencies.
- Modify `app/src/main/AndroidManifest.xml`: grant `INTERNET` for the listening socket.
- Create `app/src/main/kotlin/ng1ok/yggdrasilmanager/ui/hwbp/HwbpProtocol.kt`: validate client commands and encode state/event/ack JSON.
- Create `app/src/main/kotlin/ng1ok/yggdrasilmanager/ui/hwbp/HwbpWebServer.kt`: own server lifecycle, assets, sessions, and event broadcasts.
- Modify `app/src/main/kotlin/ng1ok/yggdrasilmanager/ui/hwbp/HwbpViewModel.kt`: own point mutations, server bindings, status state, and lifecycle.
- Replace `app/src/main/kotlin/ng1ok/yggdrasilmanager/ui/hwbp/HwbpScreen.kt`: render only server status and start/stop controls.
- Create `app/src/main/assets/web/index.html`: responsive two-column operator UI and restrained tool styling.
- Create `app/src/main/assets/web/app.js`: WebSocket/reconnect logic, form commands, state rendering, and capped events.
- Create `app/src/test/kotlin/ng1ok/yggdrasilmanager/HwbpProtocolTest.kt`: protocol validation and serialization tests.
- Create `app/src/test/kotlin/ng1ok/yggdrasilmanager/HwbpWebServerTest.kt`: Ktor HTTP/WebSocket integration tests.
- Replace `app/src/test/kotlin/ng1ok/yggdrasilmanager/HwbpViewModelTest.kt`: server lifecycle and shared-mutation tests.
- Create `app/src/test/kotlin/ng1ok/yggdrasilmanager/HwbpPresentationTest.kt`: deterministic endpoint/status presentation tests.
- Create `app/src/test/js/hwbp-web-client.test.mjs`: reducer, command, cap, and reconnect-policy tests without npm packages.

### Task 1: Add the Network Runtime and Permission

**Files:**

- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle`
- Modify: `app/src/main/AndroidManifest.xml`

- [ ] **Step 1: Add the pinned Ktor catalog entries**

Add under `[versions]`:

```toml
ktor = "2.3.12"
```

Add under `[libraries]`:

```toml
ktor-server-core = { group = "io.ktor", name = "ktor-server-core", version.ref = "ktor" }
ktor-server-cio = { group = "io.ktor", name = "ktor-server-cio", version.ref = "ktor" }
ktor-server-websockets = { group = "io.ktor", name = "ktor-server-websockets", version.ref = "ktor" }
ktor-serialization-kotlinx-json = { group = "io.ktor", name = "ktor-serialization-kotlinx-json", version.ref = "ktor" }
ktor-server-test-host = { group = "io.ktor", name = "ktor-server-test-host", version.ref = "ktor" }
ktor-client-websockets = { group = "io.ktor", name = "ktor-client-websockets", version.ref = "ktor" }
```

- [ ] **Step 2: Add runtime and test dependencies**

Add to `dependencies` in `app/build.gradle`:

```groovy
implementation(libs.ktor.server.core)
implementation(libs.ktor.server.cio)
implementation(libs.ktor.server.websockets)
implementation(libs.ktor.serialization.kotlinx.json)

testImplementation(libs.ktor.server.test.host)
testImplementation(libs.ktor.client.websockets)
```

- [ ] **Step 3: Grant socket access**

Add directly inside `<manifest>` in `app/src/main/AndroidManifest.xml`:

```xml
<uses-permission android:name="android.permission.INTERNET" />
```

- [ ] **Step 4: Resolve the exact dependency graph**

Run:

```powershell
.\gradlew.bat :app:dependencies --configuration debugRuntimeClasspath --no-problems-report
.\gradlew.bat :app:dependencies --configuration debugUnitTestRuntimeClasspath --no-problems-report
```

Expected: both commands exit 0 and show every Ktor module at `2.3.12`. If this Gradle build does not accept `--no-problems-report`, stop the process holding `build/reports/problems/problems-report.html` and rerun without the flag; do not change dependency versions to work around an output-file lock.

- [ ] **Step 5: Commit the dependency boundary**

```powershell
git add gradle/libs.versions.toml app/build.gradle app/src/main/AndroidManifest.xml
git commit -m "build: add HWBP web server runtime"
```

### Task 2: Define and Test the WebSocket Protocol

**Files:**

- Create: `app/src/main/kotlin/ng1ok/yggdrasilmanager/ui/hwbp/HwbpProtocol.kt`
- Create: `app/src/test/kotlin/ng1ok/yggdrasilmanager/HwbpProtocolTest.kt`

- [ ] **Step 1: Write failing tests for commands and outbound messages**

Create `HwbpProtocolTest.kt` with tests that assert all of the following concrete behavior:

```kotlin
package ng1ok.yggdrasilmanager

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import ng1ok.yggdrasilmanager.data.model.HwbpEvent
import ng1ok.yggdrasilmanager.data.model.HwbpObserveParams
import ng1ok.yggdrasilmanager.ui.hwbp.HwbpClientCommand
import ng1ok.yggdrasilmanager.ui.hwbp.decodeClientCommand
import ng1ok.yggdrasilmanager.ui.hwbp.encodeAck
import ng1ok.yggdrasilmanager.ui.hwbp.encodeEvent
import ng1ok.yggdrasilmanager.ui.hwbp.encodeState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HwbpProtocolTest {
    private val json = Json

    @Test
    fun observeCommandMapsModeDefaultsAndUnsignedAddress() {
        val result = decodeClientCommand(
            """{"type":"observe","pid":1234,"addr":"0xffff000000001000","mode":"RW","len":8}"""
        )
        val command = result.getOrThrow() as HwbpClientCommand.Observe
        assertEquals(1234, command.params.pid)
        assertEquals("ffff000000001000", java.lang.Long.toUnsignedString(command.params.targetAddr, 16))
        assertEquals(3, command.params.mode)
        assertEquals(8, command.params.len)
        assertEquals(0, command.params.flags)
        assertEquals(256, command.params.stackSize)
    }

    @Test
    fun unobserveCommandAcceptsCanonicalHexAddress() {
        val result = decodeClientCommand(
            """{"type":"unobserve","pid":99,"addr":"0x7ff0001000"}"""
        )
        val command = result.getOrThrow() as HwbpClientCommand.Unobserve
        assertEquals(99, command.pid)
        assertEquals(0x7ff0001000L, command.targetAddr)
    }

    @Test
    fun malformedAndUnsupportedCommandsFailWithStableMessages() {
        assertEquals("Unknown message type: ping", decodeClientCommand("""{"type":"ping"}""").exceptionOrNull()?.message)
        assertEquals("pid must be positive", decodeClientCommand("""{"type":"observe","pid":0,"addr":"0x1000","mode":"R","len":4}""").exceptionOrNull()?.message)
        assertEquals("X mode requires len 4", decodeClientCommand("""{"type":"observe","pid":1,"addr":"0x1000","mode":"X","len":8}""").exceptionOrNull()?.message)
    }

    @Test
    fun stateContainsEveryRequiredPointField() {
        val root = json.parseToJsonElement(
            encodeState(listOf(HwbpObserveParams(12, 0x1000, 3, 8, 1, 256)))
        ).jsonObject
        val point = root.getValue("points").jsonArray.single().jsonObject
        assertEquals("state", root.getValue("type").jsonPrimitive.content)
        assertEquals(12, point.getValue("pid").jsonPrimitive.int)
        assertEquals("0x1000", point.getValue("addr").jsonPrimitive.content)
        assertEquals("RW", point.getValue("mode").jsonPrimitive.content)
        assertEquals(8, point.getValue("len").jsonPrimitive.int)
        assertEquals(1, point.getValue("flags").jsonPrimitive.int)
        assertEquals(256, point.getValue("stackSize").jsonPrimitive.int)
    }

    @Test
    fun eventAndAckMatchThePublishedWireShape() {
        val event = HwbpEvent(42, 2, 7, 8, "target", 3, 8, 0, 0x1000, 0x2000, 0x3000, longArrayOf(4, 5))
        val encodedEvent = json.parseToJsonElement(encodeEvent(event)).jsonObject
        assertEquals("event", encodedEvent.getValue("type").jsonPrimitive.content)
        assertEquals(42L, encodedEvent.getValue("seq").jsonPrimitive.content.toLong())
        assertEquals(listOf("4", "5"), encodedEvent.getValue("regs").jsonArray.map { it.jsonPrimitive.content })

        val success = json.parseToJsonElement(encodeAck(Result.success(Unit))).jsonObject
        assertTrue(success.getValue("ok").jsonPrimitive.boolean)
        assertTrue(success.getValue("error").toString() == "null")
        val failure = json.parseToJsonElement(encodeAck(Result.failure(IllegalArgumentException("bad input")))).jsonObject
        assertEquals("bad input", failure.getValue("error").jsonPrimitive.content)
    }
}
```

- [ ] **Step 2: Run the protocol test and verify RED**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "ng1ok.yggdrasilmanager.HwbpProtocolTest"
```

Expected: compilation fails because the protocol types/functions do not exist.

- [ ] **Step 3: Implement the minimal protocol codec**

Create `HwbpProtocol.kt` with this public package-internal API and validation policy:

```kotlin
package ng1ok.yggdrasilmanager.ui.hwbp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import ng1ok.yggdrasilmanager.data.model.HwbpEvent
import ng1ok.yggdrasilmanager.data.model.HwbpObserveParams

internal sealed interface HwbpClientCommand {
    data class Observe(val params: HwbpObserveParams) : HwbpClientCommand
    data class Unobserve(val pid: Int, val targetAddr: Long) : HwbpClientCommand
}

private val protocolJson = Json { ignoreUnknownKeys = true }
private val modeNames = mapOf("R" to 1, "W" to 2, "RW" to 3, "X" to 4)

internal fun decodeClientCommand(text: String): Result<HwbpClientCommand> = runCatching {
    val root = protocolJson.parseToJsonElement(text).jsonObject
    when (root.requiredString("type")) {
        "observe" -> decodeObserve(root)
        "unobserve" -> HwbpClientCommand.Unobserve(
            pid = root.requiredPositivePid(),
            targetAddr = root.requiredAddress(),
        )
        else -> error("Unknown message type: ${root.requiredString("type")}")
    }
}

private fun decodeObserve(root: JsonObject): HwbpClientCommand.Observe {
    val modeName = root.requiredString("mode")
    val mode = requireNotNull(modeNames[modeName]) { "mode must be R, W, RW, or X" }
    val len = root["len"]?.jsonPrimitive?.intOrNull
    require(len in setOf(1, 2, 4, 8)) { "len must be 1, 2, 4, or 8" }
    require(mode != 4 || len == 4) { "X mode requires len 4" }
    val stackSize = root["stackSize"]?.jsonPrimitive?.intOrNull ?: 256
    require(stackSize in 0..65536) { "stackSize must be between 0 and 65536" }
    val singleThread = root["singleThread"]?.jsonPrimitive?.booleanOrNull ?: false
    return HwbpClientCommand.Observe(
        HwbpObserveParams(
            pid = root.requiredPositivePid(),
            targetAddr = root.requiredAddress(),
            mode = mode,
            len = len!!,
            flags = if (singleThread) 1 else 0,
            stackSize = stackSize,
        )
    )
}

private fun JsonObject.requiredString(name: String): String =
    this[name]?.jsonPrimitive?.content?.takeIf(String::isNotBlank)
        ?: throw IllegalArgumentException("$name is required")

private fun JsonObject.requiredPositivePid(): Int =
    (this["pid"]?.jsonPrimitive?.intOrNull ?: throw IllegalArgumentException("pid is required"))
        .also { require(it > 0) { "pid must be positive" } }

private fun JsonObject.requiredAddress(): Long {
    val raw = requiredString("addr")
    require(raw.startsWith("0x", ignoreCase = true)) { "addr must start with 0x" }
    return raw.drop(2).toULongOrNull(16)?.toLong()
        ?: throw IllegalArgumentException("addr must be a 64-bit hexadecimal value")
}

internal fun encodeState(points: List<HwbpObserveParams>): String = buildJsonObject {
    put("type", "state")
    put("points", buildJsonArray {
        points.forEach { point ->
            add(buildJsonObject {
                put("pid", point.pid)
                put("addr", point.targetAddr.toHexAddress())
                put("mode", modeNames.entries.singleOrNull { it.value == point.mode }?.key ?: point.mode.toString())
                put("len", point.len)
                put("flags", point.flags)
                put("stackSize", point.stackSize)
            })
        }
    })
}.toString()

internal fun encodeEvent(event: HwbpEvent): String = buildJsonObject {
    put("type", "event")
    put("seq", event.seq)
    put("dropped", event.dropped)
    put("comm", event.comm)
    put("tid", event.tid)
    put("tgid", event.tgid)
    put("watchedAddr", event.watchedAddr.toHexAddress())
    put("pc", event.pc.toHexAddress())
    put("sp", event.sp.toHexAddress())
    put("regs", buildJsonArray { event.regs.forEach { add(JsonPrimitive(it)) } })
}.toString()

internal fun encodeAck(result: Result<Unit>): String = buildJsonObject {
    put("type", "ack")
    put("ok", result.isSuccess)
    put("error", result.exceptionOrNull()?.message?.let(::JsonPrimitive) ?: JsonNull)
}.toString()

private fun Long.toHexAddress(): String = "0x${java.lang.Long.toUnsignedString(this, 16)}"
```

- [ ] **Step 4: Verify GREEN and commit the protocol**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "ng1ok.yggdrasilmanager.HwbpProtocolTest"
git add app/src/main/kotlin/ng1ok/yggdrasilmanager/ui/hwbp/HwbpProtocol.kt app/src/test/kotlin/ng1ok/yggdrasilmanager/HwbpProtocolTest.kt
git commit -m "feat: define HWBP websocket protocol"
```

Expected: the focused test exits 0.

### Task 3: Serve Assets and WebSocket Sessions

**Files:**

- Create: `app/src/main/kotlin/ng1ok/yggdrasilmanager/ui/hwbp/HwbpWebServer.kt`
- Create: `app/src/test/kotlin/ng1ok/yggdrasilmanager/HwbpWebServerTest.kt`

- [ ] **Step 1: Write failing Ktor integration tests**

Use `testApplication` and a `MutableSharedFlow<HwbpEvent>` to cover these behaviors in `HwbpWebServerTest.kt`:

```kotlin
@Test fun rootAndStaticAssetsComeFromTheAssetLoader()
@Test fun newWebSocketReceivesStateAndUpdatesClientCount()
@Test fun observeReturnsAckAndBroadcastsUpdatedState()
@Test fun unobserveFailureReturnsErrorAckWithoutStateBroadcast()
@Test fun repositoryEventsAreBroadcastToEveryConnectedSession()
```

The test fixture must create real test-host WebSocket clients, a mutable point list, and bindings whose observe/unobserve lambdas update that list only on success. Assert exact message `type` values and exact HTTP statuses (`200`, `404`, and `400` for a traversal segment). Do not mock a WebSocket session.

- [ ] **Step 2: Run the server test and verify RED**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "ng1ok.yggdrasilmanager.HwbpWebServerTest"
```

Expected: compilation fails because `HwbpWebServer`, `HwbpServer`, `HwbpServerBindings`, and `installHwbpWebRoutes` do not exist.

- [ ] **Step 3: Implement the server boundary**

Create these exact types in `HwbpWebServer.kt`:

```kotlin
internal const val HWBP_SERVER_PORT = 8765

internal data class HwbpServerBindings(
    val events: Flow<HwbpEvent>,
    val points: () -> List<HwbpObserveParams>,
    val observe: suspend (HwbpObserveParams) -> Result<Unit>,
    val unobserve: suspend (Int, Long) -> Result<Unit>,
)

internal interface HwbpServer {
    val running: StateFlow<Boolean>
    val connectedClients: StateFlow<Int>
    val httpUrl: StateFlow<String?>
    val webSocketUrl: StateFlow<String?>
    fun start(): Result<Unit>
    fun stop()
    suspend fun broadcastState()
}

internal fun interface HwbpServerFactory {
    fun create(bindings: HwbpServerBindings): HwbpServer
}
```

Implement a package-internal `HwbpClientHub` with a concurrent session set. `add`/`remove` must update `connectedClients`; `broadcast` must remove failed sessions and continue broadcasting to the rest. `installHwbpWebRoutes` must:

```kotlin
internal fun Application.installHwbpWebRoutes(
    bindings: HwbpServerBindings,
    assetLoader: suspend (String) -> ByteArray?,
    hub: HwbpClientHub,
) {
    install(WebSockets)
    launch { bindings.events.collect { hub.broadcast(encodeEvent(it)) } }
    routing {
        get("/") { call.respondAsset("index.html", assetLoader) }
        get("/static/{path...}") {
            val segments = call.parameters.getAll("path").orEmpty()
            if (segments.isEmpty() || segments.any { it.isBlank() || it == "." || it == ".." || '\\' in it }) {
                call.respond(HttpStatusCode.BadRequest)
            } else {
                call.respondAsset(segments.joinToString("/"), assetLoader)
            }
        }
        webSocket("/ws") {
            hub.add(this)
            try {
                send(Frame.Text(encodeState(bindings.points())))
                for (frame in incoming) {
                    if (frame !is Frame.Text) continue
                    val command = decodeClientCommand(frame.readText())
                    if (command.isFailure) {
                        send(Frame.Text(encodeAck(Result.failure(command.exceptionOrNull()!!))))
                        continue
                    }
                    val result = when (val value = command.getOrThrow()) {
                        is HwbpClientCommand.Observe -> bindings.observe(value.params)
                        is HwbpClientCommand.Unobserve -> bindings.unobserve(value.pid, value.targetAddr)
                    }
                    send(Frame.Text(encodeAck(result)))
                    if (result.isSuccess) hub.broadcast(encodeState(bindings.points()))
                }
            } finally {
                hub.remove(this)
            }
        }
    }
}
```

`respondAsset` must return `404` for a missing asset and use `text/html; charset=UTF-8`, `application/javascript; charset=UTF-8`, or `application/octet-stream` based on extension. The Android loader must read only `context.assets.open("web/$path")`.

Implement `HwbpWebServer` with `embeddedServer(CIO, host = "0.0.0.0", port = 8765)`, rebuilding the engine on every transition from stopped to started. `start()` is idempotent, refreshes the first non-loopback site-local IPv4 address, updates both URLs, and reports bind failures as `Result.failure`. `stop()` is idempotent, calls `engine.stop(500, 1_500)`, clears sessions, and sets `running` false. URL values must be `null` when no site-local IPv4 address exists; the server still listens so USB/forwarding remains possible.

- [ ] **Step 4: Verify GREEN, then verify protocol compatibility**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "ng1ok.yggdrasilmanager.HwbpWebServerTest"
.\gradlew.bat :app:testDebugUnitTest --tests "ng1ok.yggdrasilmanager.HwbpProtocolTest"
```

Expected: both focused suites exit 0, including two simultaneously connected test-host WebSocket clients receiving the same event.

- [ ] **Step 5: Commit the server**

```powershell
git add app/src/main/kotlin/ng1ok/yggdrasilmanager/ui/hwbp/HwbpWebServer.kt app/src/test/kotlin/ng1ok/yggdrasilmanager/HwbpWebServerTest.kt
git commit -m "feat: serve HWBP controls over websocket"
```

### Task 4: Make the ViewModel Own Shared Mutations and Lifecycle

**Files:**

- Modify: `app/src/main/kotlin/ng1ok/yggdrasilmanager/ui/hwbp/HwbpViewModel.kt`
- Replace: `app/src/test/kotlin/ng1ok/yggdrasilmanager/HwbpViewModelTest.kt`

- [ ] **Step 1: Replace event-list tests with failing server lifecycle tests**

Create a `FakeHwbpServer` in the test file with mutable `running`, `connectedClients`, `httpUrl`, and `webSocketUrl` flows plus counters for `start`, `stop`, and `broadcastState`. Capture the `HwbpServerBindings` supplied to `HwbpServerFactory` and test:

```kotlin
@Test fun initStartsNativeStreamAndWebServer()
@Test fun websocketObserveUpdatesTheSameObservePointState()
@Test fun duplicatePidAndAddressReplaceTheExistingPoint()
@Test fun failedObserveLeavesPointsUnchangedAndPublishesError()
@Test fun androidMutationBroadcastsStateAfterSuccess()
@Test fun toggleStopsAndRestartsTheServer()
@Test fun clearingViewModelStopsServerAndNativeStream()
```

Use `ViewModelStore` and `store.clear()` for the final test so `onCleared()` is exercised through AndroidX rather than exposing a test-only production method.

- [ ] **Step 2: Run the ViewModel test and verify RED**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "ng1ok.yggdrasilmanager.HwbpViewModelTest"
```

Expected: compilation fails because the constructor does not accept `HwbpServerFactory`, the event list still exists, and server status is absent.

- [ ] **Step 3: Implement a single mutation path**

Replace `HwbpViewModel.kt`. Keep `observePoints` and `error`; remove `events`, `appendEvent`, `clearEvents`, and `MAX_EVENTS`. Add:

```kotlin
data class HwbpUiState(
    val serverRunning: Boolean = false,
    val connectedClients: Int = 0,
    val httpUrl: String? = null,
    val webSocketUrl: String? = null,
    val observePointCount: Int = 0,
)
```

The constructor must be:

```kotlin
class HwbpViewModel(
    private val repo: YggdrasilRepository,
    serverFactory: HwbpServerFactory,
) : ViewModel()
```

In `init`, call `repo.startEventStream()`, create the server with bindings to `repo.hwbpEvents`, the current point list, and private suspend mutation functions, then start the server on `Dispatchers.IO`. Use a `Mutex` around both mutations. A successful observe replaces any existing point with the same `(pid, targetAddr)` key; a successful unobserve removes by that key. A failure leaves state unchanged and sets `_error` to the exception message.

Expose `uiState` by combining the server's four flows with `observePoints` and mapping them to `HwbpUiState`. Keep public `addObserve`/`removeObserve`; after their shared mutation returns success, call `server.broadcastState()`. `toggleServer()` must perform `start` or `stop` on `Dispatchers.IO` and surface a start failure through `_error`. `onCleared()` must stop the server before `repo.stopEventStream()`.

Factory construction must use `APPLICATION_KEY`:

```kotlin
val Factory: ViewModelProvider.Factory = viewModelFactory {
    initializer {
        val application = this[APPLICATION_KEY] as App
        HwbpViewModel(
            repo = YggdrasilRepository.instance,
            serverFactory = HwbpServerFactory { bindings ->
                HwbpWebServer(application.applicationContext, bindings)
            },
        )
    }
}
```

- [ ] **Step 4: Verify GREEN and repository non-regression**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "ng1ok.yggdrasilmanager.HwbpViewModelTest"
.\gradlew.bat :app:testDebugUnitTest --tests "ng1ok.yggdrasilmanager.RepositoryTest"
```

Expected: both suites exit 0 and the repository production file remains unchanged.

- [ ] **Step 5: Commit the lifecycle integration**

```powershell
git add app/src/main/kotlin/ng1ok/yggdrasilmanager/ui/hwbp/HwbpViewModel.kt app/src/test/kotlin/ng1ok/yggdrasilmanager/HwbpViewModelTest.kt
git commit -m "feat: manage HWBP server lifecycle"
```

### Task 5: Replace the Android Operator UI with Status

**Files:**

- Replace: `app/src/main/kotlin/ng1ok/yggdrasilmanager/ui/hwbp/HwbpScreen.kt`
- Create: `app/src/test/kotlin/ng1ok/yggdrasilmanager/HwbpPresentationTest.kt`

- [ ] **Step 1: Write the failing status presentation test**

```kotlin
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
```

- [ ] **Step 2: Run the presentation test and verify RED**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "ng1ok.yggdrasilmanager.HwbpPresentationTest"
```

Expected: compilation fails because `serverStatusLabel` does not exist.

- [ ] **Step 3: Add the tested presentation decision**

Add beside `HwbpUiState`:

```kotlin
fun HwbpUiState.serverStatusLabel(): String = if (serverRunning) "Running" else "Stopped"
```

- [ ] **Step 4: Replace the two-tab screen**

Implement a single unframed `Column` that collects `uiState` and `error`. It must render, in this order:

1. A compact status row with a green running indicator or neutral stopped indicator and `serverStatusLabel()`.
2. A `Web UI` row showing `httpUrl ?: "Wi-Fi address unavailable"`.
3. A `WebSocket` row showing `webSocketUrl ?: "Wi-Fi address unavailable"`.
4. A divider and two fixed-height metric rows: connected PC clients and active observe points.
5. A full-width `Button` with `PlayArrow` + `Start server` when stopped, or `Stop` + `Stop server` when running.

Keep the existing dismissible error dialog. Remove all form, tab, event-card, lazy-list, and floating-action-button code from Android. Use `SelectionContainer` around endpoint text so operators can copy it, `maxLines = 2` with ellipsis for narrow screens, and no nested cards.

- [ ] **Step 5: Verify presentation, compile, and commit**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "ng1ok.yggdrasilmanager.HwbpPresentationTest"
.\gradlew.bat :app:assembleDebug
git add app/src/main/kotlin/ng1ok/yggdrasilmanager/ui/hwbp/HwbpViewModel.kt app/src/main/kotlin/ng1ok/yggdrasilmanager/ui/hwbp/HwbpScreen.kt app/src/test/kotlin/ng1ok/yggdrasilmanager/HwbpPresentationTest.kt
git commit -m "feat: show HWBP server status on device"
```

Expected: focused test and APK build both exit 0.

### Task 6: Build the PC Web Client Test-First

**Files:**

- Create: `app/src/main/assets/web/index.html`
- Create: `app/src/main/assets/web/app.js`
- Create: `app/src/test/js/hwbp-web-client.test.mjs`

- [ ] **Step 1: Write failing pure-client tests**

Create `hwbp-web-client.test.mjs`:

```javascript
import assert from "node:assert/strict";
import test from "node:test";
import {
  EVENT_LIMIT,
  RECONNECT_DELAY_MS,
  buildObserveCommand,
  buildUnobserveCommand,
  initialModel,
  reduceServerMessage,
} from "../../main/assets/web/app.js";

test("state replaces observe points", () => {
  const next = reduceServerMessage(initialModel(), {
    type: "state",
    points: [{ pid: 12, addr: "0x1000", mode: "RW", len: 8 }],
  });
  assert.equal(next.points.length, 1);
  assert.equal(next.points[0].addr, "0x1000");
});

test("events prepend and cap at 500", () => {
  let model = initialModel();
  for (let seq = 0; seq < EVENT_LIMIT + 25; seq += 1) {
    model = reduceServerMessage(model, { type: "event", seq, regs: [] });
  }
  assert.equal(model.events.length, 500);
  assert.equal(model.events[0].seq, 524);
  assert.equal(model.events.at(-1).seq, 25);
});

test("commands normalize values to the wire protocol", () => {
  assert.deepEqual(
    buildObserveCommand({ pid: "1234", addr: "7FF0001000", mode: "RW", len: "8" }),
    { type: "observe", pid: 1234, addr: "0x7ff0001000", mode: "RW", len: 8, singleThread: false, stackSize: 256 },
  );
  assert.deepEqual(buildUnobserveCommand({ pid: 1234, addr: "0x1000" }), {
    type: "unobserve", pid: 1234, addr: "0x1000",
  });
});

test("reconnect policy is three seconds", () => {
  assert.equal(RECONNECT_DELAY_MS, 3000);
});
```

- [ ] **Step 2: Run the Node suite and verify RED**

```powershell
node --experimental-default-type=module --test app/src/test/js/hwbp-web-client.test.mjs
```

Expected: the import fails because `app.js` does not exist.

- [ ] **Step 3: Implement the reducer and commands, then verify GREEN**

Create `app.js` as an ES module. Export exactly the six names imported by the test. The reducer must return new arrays, replace points on `state`, prepend one event on `event`, and leave the model unchanged for `ack` or unknown types. Address normalization must trim, lowercase, accept an optional `0x`, reject non-hex input, and return canonical `0x...` text. PID must be a positive integer; mode must be one of `R/W/RW/X`; len must be `1/2/4/8`; X must force len 4.

Run the same Node command and confirm all four tests pass before adding DOM code.

- [ ] **Step 4: Add browser rendering and WebSocket behavior**

Keep exports side-effect free under Node by guarding browser startup with:

```javascript
if (typeof document !== "undefined" && typeof WebSocket !== "undefined") {
  startBrowserClient(document, window);
}
```

The browser client must connect to `${location.protocol === "https:" ? "wss" : "ws"}://${location.host}/ws`, mark connection states as connecting/connected/disconnected, and retry exactly once per close after `RECONNECT_DELAY_MS`. It must parse JSON defensively, render state and events through `textContent`, send observe/unobserve JSON only while the socket is open, and show ack errors beside the form. Event rows use `<details>` for registers and display dropped counts only when non-zero. Clear empties only the browser event array.

- [ ] **Step 5: Create the responsive operator page**

Create `index.html` with no external assets or dependencies. Use a semantic header containing the `HWBP Observe` product name and connection state; a two-column `main` with a fixed `minmax(280px, 360px)` control rail and flexible events area; and these stable IDs used by `app.js`:

```text
connection-state, observe-form, pid, addr, mode-group, len-group,
form-error, points, point-count, events, event-count, clear-events
```

Use segmented radio controls for mode/length, numeric/text inputs for PID/address, a clear text button for event clearing, and clear Remove commands on point rows. At widths below `760px`, stack controls above events. Use neutral surfaces, green for connected/primary actions, amber for reconnecting/dropped warnings, and red only for errors/removal; keep radii at `6px` or less, fixed control heights, letter spacing `0`, and no gradients, decorative blobs, marketing copy, or nested cards.

- [ ] **Step 6: Verify client logic and packaged assets**

```powershell
node --experimental-default-type=module --test app/src/test/js/hwbp-web-client.test.mjs
.\gradlew.bat :app:testDebugUnitTest --tests "ng1ok.yggdrasilmanager.HwbpWebServerTest"
.\gradlew.bat :app:assembleDebug
```

Expected: Node reports 4 passing tests, the server asset tests pass, and the Debug APK includes `assets/web/index.html` and `assets/web/app.js`.

- [ ] **Step 7: Commit the web client**

```powershell
git add app/src/main/assets/web/index.html app/src/main/assets/web/app.js app/src/test/js/hwbp-web-client.test.mjs
git commit -m "feat: add HWBP browser console"
```

### Task 7: Run Full Verification and Device Acceptance

**Files:**

- Verify only. If a failure reveals a defect, first add a focused failing regression test, then make the smallest production correction.

- [ ] **Step 1: Run every JVM and browser test from fresh invocations**

```powershell
.\gradlew.bat :app:testDebugUnitTest
node --experimental-default-type=module --test app/src/test/js/hwbp-web-client.test.mjs
```

Expected: Gradle reports `BUILD SUCCESSFUL` with zero failed tests; Node reports 4 passing tests.

- [ ] **Step 2: Build and lint the full Android application**

```powershell
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:lintDebug
```

Expected: both commands exit 0; `app/build/outputs/apk/debug/app-debug.apk` exists.

- [ ] **Step 3: Check repository hygiene and protected boundaries**

```powershell
git diff --check
git status --short
git diff 2b264e4..HEAD -- app/src/main/cpp app/src/main/kotlin/ng1ok/yggdrasilmanager/native app/src/main/kotlin/ng1ok/yggdrasilmanager/data/YggdrasilRepository.kt
```

Expected: whitespace check is silent; status is clean except pre-existing `.idea/`; protected JNI, ABI, native declarations, and repository diff is empty.

- [ ] **Step 4: Inspect requirement coverage**

```powershell
git diff --stat 2b264e4..HEAD
git log --oneline 2b264e4..HEAD
```

Confirm: auto-start and manual restart both work; `/`, `/static/*`, and `/ws` exist; every new connection gets state; successful commands ack then broadcast state; failures ack without mutation; events reach all clients; Android retains no event list; browser caps at 500 and reconnects after 3 seconds.

- [ ] **Step 5: Run device/LAN acceptance when a rooted target is available**

```powershell
adb devices
.\gradlew.bat :app:connectedDebugAndroidTest
```

On a rooted Android device with Yggdrasil loaded, open HWBP Observe and verify auto-start, Wi-Fi URLs, client count, stop/start, and point count. From a PC on the same LAN, open the displayed HTTP URL in narrow and desktop viewports; add R/W/RW/X points; remove a point; trigger events; expand registers; clear browser events; disable/re-enable Wi-Fi and confirm reconnect after 3 seconds; open a second browser and confirm both receive events.

Expected: device instrumentation exits 0 and every manual case succeeds. If no rooted target or LAN peer is available, report this step as unavailable rather than passed.
