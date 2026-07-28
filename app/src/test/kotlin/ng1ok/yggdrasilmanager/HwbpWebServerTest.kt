package ng1ok.yggdrasilmanager

import android.content.Context
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.testing.testApplication
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import ng1ok.yggdrasilmanager.data.hwbp.HwbpSavedConfig
import ng1ok.yggdrasilmanager.data.model.HwbpEvent
import ng1ok.yggdrasilmanager.data.model.HwbpObserveParams
import ng1ok.yggdrasilmanager.ui.hwbp.HwbpClientHub
import ng1ok.yggdrasilmanager.ui.hwbp.HwbpServerBindings
import ng1ok.yggdrasilmanager.ui.hwbp.HwbpWebServer
import ng1ok.yggdrasilmanager.ui.hwbp.installHwbpWebRoutes
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HwbpWebServerTest {
    private val json = Json

    @Test
    fun rootAndStaticAssetsComeFromTheAssetLoader() = testApplication {
        val assets = mapOf(
            "index.html" to "<main>HWBP</main>".encodeToByteArray(),
            "app.js" to "export {};".encodeToByteArray(),
        )
        application {
            installHwbpWebRoutes(
                bindings = emptyBindings(),
                assetLoader = assets::get,
                hub = HwbpClientHub(),
            )
        }

        val root = client.get("/")
        val script = client.get("/static/app.js")

        assertEquals(HttpStatusCode.OK, root.status)
        assertEquals("<main>HWBP</main>", root.bodyAsText())
        assertEquals("text/html; charset=UTF-8", root.headers["Content-Type"])
        assertEquals(HttpStatusCode.OK, script.status)
        assertEquals("application/javascript; charset=UTF-8", script.headers["Content-Type"])
        assertEquals(HttpStatusCode.NotFound, client.get("/static/missing.js").status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/static/%2e%2e").status)
    }

    @Test
    fun newWebSocketReceivesStateAndUpdatesClientCount() = testApplication {
        val hub = HwbpClientHub()
        val point = HwbpObserveParams(12, 0x1000, 3, 8, 0, 256)
        application {
            installHwbpWebRoutes(
                bindings = emptyBindings(points = { listOf(point) }),
                assetLoader = { null },
                hub = hub,
            )
        }
        val socketClient = createClient { install(ClientWebSockets) }

        val session = socketClient.webSocketSession("/ws")
        val state = receiveJson(session.incoming.receive())

        assertEquals(1, hub.connectedClients.value)
        assertEquals("state", state.getValue("type").jsonPrimitive.content)
        assertEquals(12, state.getValue("points").jsonArray.single().jsonObject
            .getValue("pid").jsonPrimitive.int)

        session.close(CloseReason(CloseReason.Codes.NORMAL, "done"))
        withTimeout(1_000) { hub.connectedClients.first { it == 0 } }
    }

    @Test
    fun observeReturnsAckAndBroadcastsUpdatedState() = testApplication {
        val hub = HwbpClientHub()
        var points = emptyList<HwbpObserveParams>()
        val bindings = emptyBindings(
            points = { points },
            observe = { params ->
                points = points + params
                Result.success(Unit)
            },
        )
        application { installHwbpWebRoutes(bindings, { null }, hub) }
        val socketClient = createClient { install(ClientWebSockets) }
        val session = socketClient.webSocketSession("/ws")
        receiveJson(session.incoming.receive())

        session.send(
            Frame.Text(
                """{"type":"observe","pid":44,"addr":"0x2000","mode":"W","len":4}"""
            )
        )
        val ack = receiveJson(session.incoming.receive())
        val state = receiveJson(session.incoming.receive())

        assertEquals("ack", ack.getValue("type").jsonPrimitive.content)
        assertTrue(ack.getValue("ok").jsonPrimitive.boolean)
        assertEquals("state", state.getValue("type").jsonPrimitive.content)
        assertEquals(44, state.getValue("points").jsonArray.single().jsonObject
            .getValue("pid").jsonPrimitive.int)
        assertEquals(1, points.size)

        session.close()
    }

    @Test
    fun unobserveFailureReturnsErrorAckWithoutStateBroadcast() = testApplication {
        val hub = HwbpClientHub()
        val bindings = emptyBindings(
            unobserve = { _, _ -> Result.failure(IllegalStateException("native rejected")) },
        )
        application { installHwbpWebRoutes(bindings, { null }, hub) }
        val socketClient = createClient { install(ClientWebSockets) }
        val session = socketClient.webSocketSession("/ws")
        receiveJson(session.incoming.receive())

        session.send(Frame.Text("""{"type":"unobserve","pid":44,"addr":"0x2000"}"""))
        val ack = receiveJson(session.incoming.receive())

        assertFalse(ack.getValue("ok").jsonPrimitive.boolean)
        assertEquals("native rejected", ack.getValue("error").jsonPrimitive.content)
        assertNull(withTimeoutOrNull(150) { session.incoming.receive() })

        session.close()
    }

    @Test
    fun savedConfigurationCommandsReturnStateWithPersistedDefinitions() = testApplication {
        val hub = HwbpClientHub()
        var configs = emptyList<HwbpSavedConfig>()
        var appliedId: String? = null
        val bindings = emptyBindings(
            savedConfigs = { configs },
            saveConfig = { draft ->
                configs = listOf(draft.toSavedConfig("system-lib"))
                Result.success(Unit)
            },
            applyConfig = { id ->
                appliedId = id
                Result.success(Unit)
            },
            deleteConfig = { id ->
                configs = configs.filterNot { it.id == id }
                Result.success(Unit)
            },
        )
        application { installHwbpWebRoutes(bindings, { null }, hub) }
        val socketClient = createClient { install(ClientWebSockets) }
        val session = socketClient.webSocketSession("/ws")
        receiveJson(session.incoming.receive())

        session.send(
            Frame.Text(
                """{"type":"saveConfig","packageName":"com.example.target","lib":"libfoo.so","offset":"0x20","mode":"RW","len":8,"stackSize":256,"note":"watch entry"}"""
            )
        )
        assertTrue(receiveJson(session.incoming.receive()).getValue("ok").jsonPrimitive.boolean)
        val savedState = receiveJson(session.incoming.receive())
        val saved = savedState.getValue("savedConfigs").jsonArray.single().jsonObject
        assertEquals("system-lib", saved.getValue("id").jsonPrimitive.content)
        assertEquals("watch entry", saved.getValue("note").jsonPrimitive.content)

        session.send(Frame.Text("""{"type":"applyConfig","id":"system-lib"}"""))
        assertTrue(receiveJson(session.incoming.receive()).getValue("ok").jsonPrimitive.boolean)
        receiveJson(session.incoming.receive())
        assertEquals("system-lib", appliedId)

        session.send(Frame.Text("""{"type":"deleteConfig","id":"system-lib"}"""))
        assertTrue(receiveJson(session.incoming.receive()).getValue("ok").jsonPrimitive.boolean)
        val deletedState = receiveJson(session.incoming.receive())
        assertTrue(deletedState.getValue("savedConfigs").jsonArray.isEmpty())
        session.close()
    }

    @Test
    fun repositoryEventsAreBroadcastToEveryConnectedSession() = testApplication {
        val events = MutableSharedFlow<HwbpEvent>(extraBufferCapacity = 1)
        val hub = HwbpClientHub()
        application {
            installHwbpWebRoutes(
                bindings = emptyBindings(events = events),
                assetLoader = { null },
                hub = hub,
            )
        }
        val socketClient = createClient { install(ClientWebSockets) }
        val first = socketClient.webSocketSession("/ws")
        val second = socketClient.webSocketSession("/ws")
        receiveJson(first.incoming.receive())
        receiveJson(second.incoming.receive())

        events.emit(
            HwbpEvent(9, 0, 1, 2, "target", 1, 4, 0, 0x1000, 0x2000, 0x3000, longArrayOf(7))
        )
        val firstEvent = withTimeout(1_000) { receiveJson(first.incoming.receive()) }
        val secondEvent = withTimeout(1_000) { receiveJson(second.incoming.receive()) }

        assertEquals(2, hub.connectedClients.value)
        assertEquals(9L, firstEvent.getValue("seq").jsonPrimitive.content.toLong())
        assertEquals(9L, secondEvent.getValue("seq").jsonPrimitive.content.toLong())

        first.close()
        second.close()
    }

    @Test
    fun lifecycleIsIdempotentAndPublishesReachableUrls() {
        val engine = mockk<ApplicationEngine>(relaxed = true)
        every { engine.start(wait = false) } returns engine
        val server = HwbpWebServer(
            context = mockk<Context>(relaxed = true),
            bindings = emptyBindings(),
            addressProvider = { "192.168.1.9" },
            engineFactory = { engine },
        )

        assertTrue(server.start().isSuccess)
        assertTrue(server.start().isSuccess)
        assertTrue(server.running.value)
        assertEquals("http://192.168.1.9:8765", server.httpUrl.value)
        assertEquals("ws://192.168.1.9:8765/ws", server.webSocketUrl.value)
        verify(exactly = 1) { engine.start(wait = false) }

        server.stop()
        server.stop()

        assertFalse(server.running.value)
        verify(exactly = 1) { engine.stop(500, 1_500) }
    }

    @Test
    fun bindFailureLeavesServerStopped() {
        val engine = mockk<ApplicationEngine>()
        every { engine.start(wait = false) } throws IllegalStateException("address in use")
        every { engine.stop(any(), any()) } returns Unit
        val server = HwbpWebServer(
            context = mockk<Context>(relaxed = true),
            bindings = emptyBindings(),
            addressProvider = { "192.168.1.9" },
            engineFactory = { engine },
        )

        val result = server.start()

        assertTrue(result.isFailure)
        assertFalse(server.running.value)
        assertNull(server.httpUrl.value)
        assertNull(server.webSocketUrl.value)
    }

    private fun emptyBindings(
        events: MutableSharedFlow<HwbpEvent>? = null,
        points: () -> List<HwbpObserveParams> = { emptyList() },
        savedConfigs: () -> List<HwbpSavedConfig> = { emptyList() },
        observe: suspend (HwbpObserveParams) -> Result<Unit> = { Result.success(Unit) },
        unobserve: suspend (Int, Long) -> Result<Unit> = { _, _ -> Result.success(Unit) },
        saveConfig: suspend (ng1ok.yggdrasilmanager.data.hwbp.HwbpConfigDraft) -> Result<Unit> = {
            Result.success(Unit)
        },
        applyConfig: suspend (String) -> Result<Unit> = { Result.success(Unit) },
        deleteConfig: suspend (String) -> Result<Unit> = { Result.success(Unit) },
    ) = HwbpServerBindings(
        events = events ?: emptyFlow(),
        points = points,
        savedConfigs = savedConfigs,
        observe = observe,
        unobserve = unobserve,
        saveConfig = saveConfig,
        applyConfig = applyConfig,
        deleteConfig = deleteConfig,
    )

    private fun receiveJson(frame: Frame) =
        json.parseToJsonElement((frame as Frame.Text).readText()).jsonObject
}
