package ng1ok.yggdrasilmanager.ui.hwbp

import android.content.Context
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.withCharset
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.send
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ng1ok.yggdrasilmanager.data.hwbp.HwbpConfigDraft
import ng1ok.yggdrasilmanager.data.hwbp.HwbpSavedConfig
import ng1ok.yggdrasilmanager.data.model.HwbpEvent
import ng1ok.yggdrasilmanager.data.model.HwbpObserveParams

internal const val HWBP_SERVER_PORT = 8765

internal data class HwbpServerBindings(
    val events: Flow<HwbpEvent>,
    val points: () -> List<HwbpObserveParams>,
    val savedConfigs: () -> List<HwbpSavedConfig> = { emptyList() },
    val backtraceMode: () -> String = { "fp" },
    val observe: suspend (HwbpObserveParams) -> Result<Unit>,
    val unobserve: suspend (Int, Long) -> Result<Unit>,
    val setBacktraceMode: (String) -> Result<Unit> = { Result.success(Unit) },
    val saveConfig: suspend (HwbpConfigDraft) -> Result<Unit> = {
        Result.failure(UnsupportedOperationException("Saved configurations are unavailable"))
    },
    val applyConfig: suspend (String) -> Result<Unit> = {
        Result.failure(UnsupportedOperationException("Saved configurations are unavailable"))
    },
    val deleteConfig: suspend (String) -> Result<Unit> = {
        Result.failure(UnsupportedOperationException("Saved configurations are unavailable"))
    },
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

internal class HwbpClientHub {
    private val sessions = ConcurrentHashMap.newKeySet<DefaultWebSocketServerSession>()
    private val _connectedClients = MutableStateFlow(0)

    val connectedClients: StateFlow<Int> = _connectedClients.asStateFlow()

    fun add(session: DefaultWebSocketServerSession) {
        sessions.add(session)
        _connectedClients.value = sessions.size
    }

    fun remove(session: DefaultWebSocketServerSession) {
        sessions.remove(session)
        _connectedClients.value = sessions.size
    }

    suspend fun broadcast(text: String) {
        val activeSessions = sessions.toList()
        activeSessions.forEach { session ->
            try {
                session.send(text)
            } catch (error: CancellationException) {
                if (!currentCoroutineContext().isActive) throw error
                remove(session)
            } catch (_: Exception) {
                remove(session)
            }
        }
    }

    fun clear() {
        sessions.clear()
        _connectedClients.value = 0
    }
}

internal fun Application.installHwbpWebRoutes(
    bindings: HwbpServerBindings,
    assetLoader: suspend (String) -> ByteArray?,
    hub: HwbpClientHub,
) {
    install(WebSockets)
    environment.monitor.subscribe(ApplicationStopped) { hub.clear() }
    launch {
        bindings.events.collect { event ->
            hub.broadcast(encodeEvent(event))
        }
    }
    routing {
        get("/") {
            call.respondAsset("index.html", assetLoader)
        }
        get("/static/{path...}") {
            val segments = call.parameters.getAll("path").orEmpty()
            if (segments.isEmpty() || segments.any(::isUnsafePathSegment)) {
                call.respond(HttpStatusCode.BadRequest)
            } else {
                call.respondAsset(segments.joinToString("/"), assetLoader)
            }
        }
        webSocket("/ws") {
            hub.add(this)
            try {
                send(
                    Frame.Text(
                        encodeState(
                            bindings.points(),
                            bindings.backtraceMode(),
                            bindings.savedConfigs(),
                        )
                    )
                )
                for (frame in incoming) {
                    if (frame !is Frame.Text) continue
                    val decoded = decodeClientCommand(frame.readText())
                    if (decoded.isFailure) {
                        val failure = Result.failure<Unit>(decoded.exceptionOrNull()!!)
                        send(Frame.Text(encodeAck(failure)))
                        continue
                    }
                    val result = when (val command = decoded.getOrThrow()) {
                        is HwbpClientCommand.Observe -> bindings.observe(command.params)
                        is HwbpClientCommand.Unobserve ->
                            bindings.unobserve(command.pid, command.targetAddr)
                        is HwbpClientCommand.Config -> bindings.setBacktraceMode(command.backtraceMode)
                        is HwbpClientCommand.SaveConfig -> bindings.saveConfig(command.config)
                        is HwbpClientCommand.ApplyConfig -> bindings.applyConfig(command.id)
                        is HwbpClientCommand.DeleteConfig -> bindings.deleteConfig(command.id)
                    }
                    send(Frame.Text(encodeAck(result)))
                    if (result.isSuccess) {
                        hub.broadcast(
                            encodeState(
                                bindings.points(),
                                bindings.backtraceMode(),
                                bindings.savedConfigs(),
                            )
                        )
                    }
                }
            } finally {
                hub.remove(this)
            }
        }
    }
}

private fun isUnsafePathSegment(segment: String): Boolean =
    segment.isBlank() || segment == "." || segment == ".." || '\\' in segment

private suspend fun ApplicationCall.respondAsset(
    path: String,
    assetLoader: suspend (String) -> ByteArray?,
) {
    val bytes = assetLoader(path)
    if (bytes == null) {
        respond(HttpStatusCode.NotFound)
        return
    }
    respondBytes(bytes, contentTypeFor(path))
}

private fun contentTypeFor(path: String): ContentType = when {
    path.endsWith(".html", ignoreCase = true) ->
        ContentType.Text.Html.withCharset(Charsets.UTF_8)
    path.endsWith(".js", ignoreCase = true) ->
        ContentType.Application.JavaScript.withCharset(Charsets.UTF_8)
    path.endsWith(".css", ignoreCase = true) ->
        ContentType.Text.CSS.withCharset(Charsets.UTF_8)
    else -> ContentType.Application.OctetStream
}

internal class HwbpWebServer(
    private val context: Context,
    private val bindings: HwbpServerBindings,
    private val port: Int = HWBP_SERVER_PORT,
    private val addressProvider: () -> String? = ::siteLocalIpv4Address,
    private val engineFactory: (Application.() -> Unit) -> ApplicationEngine = { module ->
        embeddedServer(CIO, host = "0.0.0.0", port = port, module = module)
    },
) : HwbpServer {
    private val hub = HwbpClientHub()
    private val _running = MutableStateFlow(false)
    private val _httpUrl = MutableStateFlow<String?>(null)
    private val _webSocketUrl = MutableStateFlow<String?>(null)
    private var engine: ApplicationEngine? = null

    override val running: StateFlow<Boolean> = _running.asStateFlow()
    override val connectedClients: StateFlow<Int> = hub.connectedClients
    override val httpUrl: StateFlow<String?> = _httpUrl.asStateFlow()
    override val webSocketUrl: StateFlow<String?> = _webSocketUrl.asStateFlow()

    @Synchronized
    override fun start(): Result<Unit> {
        if (_running.value) return Result.success(Unit)
        val nextEngine = engineFactory {
            installHwbpWebRoutes(bindings, ::loadAsset, hub)
        }
        return runCatching {
            nextEngine.start(wait = false)
            engine = nextEngine
            val address = addressProvider()
            _httpUrl.value = address?.let { "http://$it:$port" }
            _webSocketUrl.value = address?.let { "ws://$it:$port/ws" }
            _running.value = true
        }.onFailure {
            runCatching { nextEngine.stop(0, 0) }
            engine = null
            hub.clear()
            _running.value = false
            _httpUrl.value = null
            _webSocketUrl.value = null
        }
    }

    @Synchronized
    override fun stop() {
        val activeEngine = engine ?: return
        runCatching { activeEngine.stop(500, 1_500) }
        engine = null
        hub.clear()
        _running.value = false
    }

    override suspend fun broadcastState() {
        hub.broadcast(
            encodeState(bindings.points(), bindings.backtraceMode(), bindings.savedConfigs())
        )
    }

    private suspend fun loadAsset(path: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            context.assets.open("web/$path").use { stream -> stream.readBytes() }
        } catch (_: IOException) {
            null
        }
    }
}

private fun siteLocalIpv4Address(): String? = runCatching {
    Collections.list(NetworkInterface.getNetworkInterfaces())
        .asSequence()
        .flatMap { network -> Collections.list(network.inetAddresses).asSequence() }
        .filterIsInstance<Inet4Address>()
        .firstOrNull { address ->
            !address.isLoopbackAddress && address.isSiteLocalAddress
        }
        ?.hostAddress
}.getOrNull()
