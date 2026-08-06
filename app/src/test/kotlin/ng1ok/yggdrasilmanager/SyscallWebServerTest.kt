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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import ng1ok.yggdrasilmanager.data.model.SyscallEvent
import ng1ok.yggdrasilmanager.data.model.SyscallListenerStatus
import ng1ok.yggdrasilmanager.data.model.SyscallObserveRule
import ng1ok.yggdrasilmanager.data.model.SyscallScope
import ng1ok.yggdrasilmanager.data.model.SyscallTracePhase
import ng1ok.yggdrasilmanager.ui.syscall.SyscallClientHub
import ng1ok.yggdrasilmanager.ui.syscall.SyscallServerBindings
import ng1ok.yggdrasilmanager.ui.syscall.SyscallWebServer
import ng1ok.yggdrasilmanager.ui.syscall.installSyscallWebRoutes
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyscallWebServerTest {
    private val json = Json
    private val accessToken = "test-access-token"

    @Test
    fun rootRequiresTokenAndValidTokenServesTheWebAssets() = testApplication {
        val assets = mapOf(
            "index.html" to "<main>Arm64 Syscall Observe</main>".encodeToByteArray(),
            "app.js" to "export {};".encodeToByteArray(),
        )
        application {
            installSyscallWebRoutes(
                bindings = emptyBindings(),
                assetLoader = assets::get,
                hub = SyscallClientHub(),
                accessToken = accessToken,
            )
        }

        val denied = client.get("/")
        val root = client.get("/?token=$accessToken")
        val script = client.get("/static/app.js?token=$accessToken")

        assertEquals(HttpStatusCode.Forbidden, denied.status)
        assertEquals(HttpStatusCode.OK, root.status)
        assertEquals("<main>Arm64 Syscall Observe</main>", root.bodyAsText())
        assertEquals("text/html; charset=UTF-8", root.headers["Content-Type"])
        assertEquals(HttpStatusCode.OK, script.status)
        assertEquals("export {};", script.bodyAsText())
        assertEquals("application/javascript; charset=UTF-8", script.headers["Content-Type"])
    }

    @Test
    fun validTokenWebSocketReceivesTheInitialRuleState() = testApplication {
        val rule = SyscallObserveRule(
            63,
            SyscallScope.UID,
            SyscallObserveRule.MAX_UID,
            SyscallTracePhase.BOTH,
        )
        application {
            installSyscallWebRoutes(
                bindings = emptyBindings(rules = { listOf(rule) }),
                assetLoader = { null },
                hub = SyscallClientHub(),
                accessToken = accessToken,
            )
        }
        val socketClient = createClient { install(ClientWebSockets) }
        val session = socketClient.webSocketSession("/ws?token=$accessToken")
        val state = receiveJson(session.incoming.receive())

        assertEquals("state", state.getValue("type").jsonPrimitive.content)
        val encodedRule = state.getValue("rules").jsonArray.single().jsonObject
        assertEquals(63, encodedRule.getValue("syscallNr").jsonPrimitive.int)
        assertEquals("uid", encodedRule.getValue("scope").jsonPrimitive.content)
        assertEquals(SyscallObserveRule.MAX_UID, encodedRule.getValue("targetId").jsonPrimitive.long)
        assertEquals("both", encodedRule.getValue("phase").jsonPrimitive.content)

        session.close(CloseReason(CloseReason.Codes.NORMAL, "done"))
    }

    @Test
    fun successfulObserveReturnsAckThenBroadcastsUpdatedState() = testApplication {
        var rules = emptyList<SyscallObserveRule>()
        application {
            installSyscallWebRoutes(
                bindings = emptyBindings(
                    rules = { rules },
                    observe = { rule ->
                        rules = rules + rule
                        Result.success(Unit)
                    },
                ),
                assetLoader = { null },
                hub = SyscallClientHub(),
                accessToken = accessToken,
            )
        }
        val socketClient = createClient { install(ClientWebSockets) }
        val session = socketClient.webSocketSession("/ws?token=$accessToken")
        receiveJson(session.incoming.receive())

        session.send(
            Frame.Text(
                """{"type":"observe","syscallNr":64,"scope":"tgid","targetId":4321,"phase":"both"}""",
            ),
        )
        val ack = receiveJson(session.incoming.receive())
        val state = receiveJson(session.incoming.receive())

        assertEquals("ack", ack.getValue("type").jsonPrimitive.content)
        assertTrue(ack.getValue("ok").jsonPrimitive.boolean)
        assertEquals("state", state.getValue("type").jsonPrimitive.content)
        val rule = state.getValue("rules").jsonArray.single().jsonObject
        assertEquals("tgid", rule.getValue("scope").jsonPrimitive.content)
        assertEquals(4321, rule.getValue("targetId").jsonPrimitive.int)
        assertEquals("both", rule.getValue("phase").jsonPrimitive.content)
        assertEquals(listOf(SyscallObserveRule(64, SyscallScope.TGID, 4321, SyscallTracePhase.BOTH)), rules)

        session.close()
    }

    @Test
    fun legacyGlobalUnobserveStillReachesTheMutationBinding() = testApplication {
        application {
            installSyscallWebRoutes(
                bindings = emptyBindings(
                    unobserve = { Result.failure(IllegalStateException("native rejected")) },
                ),
                assetLoader = { null },
                hub = SyscallClientHub(),
                accessToken = accessToken,
            )
        }
        val socketClient = createClient { install(ClientWebSockets) }
        val session = socketClient.webSocketSession("/ws?token=$accessToken")
        receiveJson(session.incoming.receive())

        session.send(
            Frame.Text("""{"type":"unobserve","syscallNr":64,"scope":"global"}"""),
        )
        val ack = receiveJson(session.incoming.receive())

        assertFalse(ack.getValue("ok").jsonPrimitive.boolean)
        assertEquals("native rejected", ack.getValue("error").jsonPrimitive.content)
        assertNull(withTimeoutOrNull(150) { session.incoming.receive() })

        session.close()
    }

    @Test
    fun restartListenerCommandReachesTheBinding() = testApplication {
        var restartCount = 0
        application {
            installSyscallWebRoutes(
                bindings = emptyBindings(restartListener = { restartCount += 1 }),
                assetLoader = { null },
                hub = SyscallClientHub(),
                accessToken = accessToken,
            )
        }
        val socketClient = createClient { install(ClientWebSockets) }
        val session = socketClient.webSocketSession("/ws?token=$accessToken")
        receiveJson(session.incoming.receive())

        session.send(Frame.Text("""{"type":"restartListener"}"""))
        val ack = receiveJson(session.incoming.receive())

        assertTrue(ack.getValue("ok").jsonPrimitive.boolean)
        assertEquals(1, restartCount)
        session.close()
    }

    @Test
    fun lifecyclePublishesUrlsWithTheGeneratedToken() {
        val engine = mockk<ApplicationEngine>(relaxed = true)
        every { engine.start(wait = false) } returns engine
        val server = SyscallWebServer(
            context = mockk<Context>(relaxed = true),
            bindings = emptyBindings(),
            addressProvider = { "192.168.1.9" },
            engineFactory = { _, _ -> engine },
            portAvailable = { true },
            tokenGenerator = { "fixed-token" },
        )

        assertTrue(server.start().isSuccess)
        assertTrue(server.running.value)
        assertEquals("http://192.168.1.9:8766?token=fixed-token", server.httpUrl.value)
        assertEquals("ws://192.168.1.9:8766/ws?token=fixed-token", server.webSocketUrl.value)
        verify(exactly = 1) { engine.start(wait = false) }

        server.stop()

        assertFalse(server.running.value)
        assertNull(server.httpUrl.value)
        assertNull(server.webSocketUrl.value)
        verify(exactly = 1) { engine.stop(0, 5_000) }
    }

    private fun emptyBindings(
        events: Flow<SyscallEvent> = emptyFlow(),
        rules: () -> List<SyscallObserveRule> = { emptyList() },
        listenerStatus: StateFlow<SyscallListenerStatus> = MutableStateFlow(SyscallListenerStatus()),
        observe: suspend (SyscallObserveRule) -> Result<Unit> = { Result.success(Unit) },
        unobserve: suspend (SyscallObserveRule) -> Result<Unit> = { Result.success(Unit) },
        restartListener: () -> Unit = {},
    ) = SyscallServerBindings(
        events = events,
        rules = rules,
        listenerStatus = listenerStatus,
        observe = observe,
        unobserve = unobserve,
        restartListener = restartListener,
    )

    private fun receiveJson(frame: Frame) =
        json.parseToJsonElement((frame as Frame.Text).readText()).jsonObject
}
