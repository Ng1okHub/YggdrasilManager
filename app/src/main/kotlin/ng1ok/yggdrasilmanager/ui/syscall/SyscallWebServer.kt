package ng1ok.yggdrasilmanager.ui.syscall

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
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import java.io.IOException
import java.net.BindException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.security.SecureRandom
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.concurrent.withLock
import ng1ok.yggdrasilmanager.data.model.SyscallEvent
import ng1ok.yggdrasilmanager.data.model.SyscallListenerStatus
import ng1ok.yggdrasilmanager.data.model.SyscallObserveRule

internal const val SYSCALL_SERVER_PORT = 8766

private const val PORT_RELEASE_ATTEMPTS = 20
private const val PORT_RELEASE_RETRY_MILLIS = 100L
private const val SERVER_STOP_TIMEOUT_MILLIS = 5_000L

private val syscallServerLifecycleLock = ReentrantLock()

internal data class SyscallServerBindings(
    val events: Flow<SyscallEvent>,
    val rules: () -> List<SyscallObserveRule>,
    val listenerStatus: StateFlow<SyscallListenerStatus>,
    val observe: suspend (SyscallObserveRule) -> Result<Unit>,
    val unobserve: suspend (SyscallObserveRule) -> Result<Unit>,
    val restartListener: () -> Unit,
)

internal interface SyscallServer {
    val running: StateFlow<Boolean>
    val connectedClients: StateFlow<Int>
    val httpUrl: StateFlow<String?>
    val webSocketUrl: StateFlow<String?>

    fun start(): Result<Unit>
    fun stop()
    suspend fun broadcastState()
}

internal fun interface SyscallServerFactory {
    fun create(bindings: SyscallServerBindings): SyscallServer
}

internal class SyscallClientHub {
    private data class Client(
        val session: DefaultWebSocketServerSession,
        val messages: Channel<String>,
        val sender: Job,
    )

    private val clients = ConcurrentHashMap<DefaultWebSocketServerSession, Client>()
    private val deliveryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _connectedClients = MutableStateFlow(0)

    val connectedClients: StateFlow<Int> = _connectedClients.asStateFlow()

    fun add(session: DefaultWebSocketServerSession, initialState: String) {
        val messages = Channel<String>(CLIENT_MESSAGE_QUEUE_CAPACITY)
        val client = Client(
            session = session,
            messages = messages,
            sender = deliveryScope.launch {
                try {
                    for (message in messages) {
                        session.send(message)
                    }
                } catch (error: CancellationException) {
                    if (currentCoroutineContext().isActive) remove(session)
                } catch (_: Exception) {
                    remove(session)
                }
            },
        )
        val existing = clients.putIfAbsent(session, client)
        if (existing != null) {
            client.messages.close()
            client.sender.cancel()
            return
        }
        _connectedClients.value = clients.size
        enqueue(client, initialState)
    }

    fun remove(session: DefaultWebSocketServerSession) {
        disconnect(session)
    }

    fun send(session: DefaultWebSocketServerSession, text: String) {
        clients[session]?.let { client -> enqueue(client, text) }
    }

    fun broadcast(text: String) {
        clients.values.forEach { client -> enqueue(client, text) }
    }

    fun clear() {
        clients.keys.toList().forEach(::disconnect)
    }

    private fun enqueue(client: Client, text: String) {
        if (client.messages.trySend(text).isSuccess) return
        disconnect(
            session = client.session,
            closeReason = CloseReason(
                CloseReason.Codes.TRY_AGAIN_LATER,
                "用戶端無法及時處理系統呼叫事件",
            ),
        )
    }

    private fun disconnect(
        session: DefaultWebSocketServerSession,
        closeReason: CloseReason? = null,
    ) {
        val client = clients.remove(session) ?: return
        client.messages.close()
        client.sender.cancel()
        _connectedClients.value = clients.size
        if (closeReason != null) {
            deliveryScope.launch {
                runCatching { session.close(closeReason) }
            }
        }
    }

    private companion object {
        const val CLIENT_MESSAGE_QUEUE_CAPACITY = 128
    }
}

internal fun Application.installSyscallWebRoutes(
    bindings: SyscallServerBindings,
    assetLoader: suspend (String) -> ByteArray?,
    hub: SyscallClientHub,
    accessToken: String,
) {
    install(WebSockets)
    environment.monitor.subscribe(ApplicationStopped) { hub.clear() }
    launch {
        bindings.events.collect { event ->
            hub.broadcast(encodeSyscallEvent(event))
        }
    }
    launch {
        // A newly connected client receives the current snapshot below.
        bindings.listenerStatus.drop(1).collect { listenerStatus ->
            hub.broadcast(encodeSyscallState(bindings.rules(), listenerStatus))
        }
    }
    routing {
        get("/") {
            if (!call.hasSyscallAccess(accessToken)) {
                call.respond(HttpStatusCode.Forbidden)
                return@get
            }
            call.respondSyscallAsset("index.html", assetLoader)
        }
        get("/static/{path...}") {
            val segments = call.parameters.getAll("path").orEmpty()
            if (segments.isEmpty() || segments.any(::isUnsafePathSegment)) {
                call.respond(HttpStatusCode.BadRequest)
            } else {
                call.respondSyscallAsset(segments.joinToString("/"), assetLoader)
            }
        }
        webSocket("/ws") {
            if (!call.hasSyscallAccess(accessToken)) {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "存取權杖無效"))
                return@webSocket
            }
            hub.add(this, encodeSyscallState(bindings.rules(), bindings.listenerStatus.value))
            try {
                for (frame in incoming) {
                    if (frame !is Frame.Text) continue
                    val decoded = decodeSyscallClientCommand(frame.readText())
                    if (decoded.isFailure) {
                        hub.send(this, encodeSyscallAck(Result.failure(decoded.exceptionOrNull()!!)))
                        continue
                    }
                    val result = when (val command = decoded.getOrThrow()) {
                        is SyscallClientCommand.Observe -> bindings.observe(command.rule)
                        is SyscallClientCommand.Unobserve -> bindings.unobserve(command.rule)
                        SyscallClientCommand.RestartListener -> runCatching {
                            bindings.restartListener()
                        }
                    }
                    hub.send(this, encodeSyscallAck(result))
                    if (result.isSuccess) {
                        hub.broadcast(
                            encodeSyscallState(bindings.rules(), bindings.listenerStatus.value),
                        )
                    }
                }
            } finally {
                hub.remove(this)
            }
        }
    }
}

private fun ApplicationCall.hasSyscallAccess(accessToken: String): Boolean =
    request.queryParameters["token"] == accessToken

private fun isUnsafePathSegment(segment: String): Boolean =
    segment.isBlank() || segment == "." || segment == ".." || '\\' in segment

private suspend fun ApplicationCall.respondSyscallAsset(
    path: String,
    assetLoader: suspend (String) -> ByteArray?,
) {
    val bytes = assetLoader(path)
    if (bytes == null) {
        respond(HttpStatusCode.NotFound)
        return
    }
    respondBytes(bytes, syscallContentTypeFor(path))
}

private fun syscallContentTypeFor(path: String): ContentType = when {
    path.endsWith(".html", ignoreCase = true) ->
        ContentType.Text.Html.withCharset(Charsets.UTF_8)
    path.endsWith(".js", ignoreCase = true) ->
        ContentType.Application.JavaScript.withCharset(Charsets.UTF_8)
    path.endsWith(".css", ignoreCase = true) ->
        ContentType.Text.CSS.withCharset(Charsets.UTF_8)
    else -> ContentType.Application.OctetStream
}

internal class SyscallWebServer(
    private val context: Context,
    private val bindings: SyscallServerBindings,
    private val port: Int = SYSCALL_SERVER_PORT,
    private val addressProvider: () -> String? = ::siteLocalIpv4Address,
    private val engineFactory: (CoroutineScope, Application.() -> Unit) -> ApplicationEngine = { scope, module ->
        scope.embeddedServer(
            CIO,
            host = "0.0.0.0",
            port = port,
            configure = { reuseAddress = true },
            module = module,
        )
    },
    private val portAvailable: () -> Boolean = { isTcpPortAvailable(port) },
    private val sleeper: (Long) -> Unit = Thread::sleep,
    private val tokenGenerator: () -> String = ::newAccessToken,
) : SyscallServer {
    private val hub = SyscallClientHub()
    private val _running = MutableStateFlow(false)
    private val _httpUrl = MutableStateFlow<String?>(null)
    private val _webSocketUrl = MutableStateFlow<String?>(null)
    private var engine: ApplicationEngine? = null
    private var engineScope: CoroutineScope? = null

    override val running: StateFlow<Boolean> = _running.asStateFlow()
    override val connectedClients: StateFlow<Int> = hub.connectedClients
    override val httpUrl: StateFlow<String?> = _httpUrl.asStateFlow()
    override val webSocketUrl: StateFlow<String?> = _webSocketUrl.asStateFlow()

    override fun start(): Result<Unit> = syscallServerLifecycleLock.withLock {
        if (_running.value) return@withLock Result.success(Unit)

        val portReady = waitForPortRelease()
        if (portReady.isFailure) {
            markStopped()
            return@withLock portReady
        }

        val token = tokenGenerator()
        if (token.isBlank()) {
            markStopped()
            return@withLock Result.failure(IllegalStateException("無法建立存取權杖"))
        }
        val engineFailure = AtomicReference<Throwable?>(null)
        val nextScope = CoroutineScope(
            SupervisorJob() + CoroutineExceptionHandler { _, error ->
                engineFailure.compareAndSet(null, error)
            },
        )
        var nextEngine: ApplicationEngine? = null
        val result = runCatching {
            val created = engineFactory(nextScope) {
                installSyscallWebRoutes(bindings, ::loadAsset, hub, token)
            }
            nextEngine = created
            created.start(wait = false)
            engineFailure.get()?.let { throw it }

            engine = created
            engineScope = nextScope
            val address = addressProvider()
            _httpUrl.value = address?.let { "http://$it:$port?token=$token" }
            _webSocketUrl.value = address?.let { "ws://$it:$port/ws?token=$token" }
            _running.value = true
        }
        if (result.isFailure) {
            nextEngine?.let { failedEngine ->
                runCatching { failedEngine.stop(0, SERVER_STOP_TIMEOUT_MILLIS) }
            }
            nextScope.cancel()
            markStopped()
        }
        result
    }

    override fun stop() = syscallServerLifecycleLock.withLock {
        val activeEngine = engine
        val activeScope = engineScope
        engine = null
        engineScope = null
        activeEngine?.let { runningEngine ->
            runCatching { runningEngine.stop(0, SERVER_STOP_TIMEOUT_MILLIS) }
        }
        activeScope?.cancel()
        markStopped()
    }

    override suspend fun broadcastState() {
        hub.broadcast(encodeSyscallState(bindings.rules(), bindings.listenerStatus.value))
    }

    private suspend fun loadAsset(path: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            context.assets.open("syscall-web/$path").use { stream -> stream.readBytes() }
        } catch (_: IOException) {
            null
        }
    }

    private fun waitForPortRelease(): Result<Unit> = try {
        repeat(PORT_RELEASE_ATTEMPTS) { attempt ->
            if (portAvailable()) return Result.success(Unit)
            if (attempt < PORT_RELEASE_ATTEMPTS - 1) sleeper(PORT_RELEASE_RETRY_MILLIS)
        }
        Result.failure(BindException("連接埠 $port 仍在使用中"))
    } catch (error: Exception) {
        Result.failure(error)
    }

    private fun markStopped() {
        hub.clear()
        _running.value = false
        _httpUrl.value = null
        _webSocketUrl.value = null
    }
}

private fun isTcpPortAvailable(port: Int): Boolean = runCatching {
    ServerSocket().use { socket ->
        socket.reuseAddress = true
        socket.bind(InetSocketAddress("0.0.0.0", port))
    }
}.isSuccess

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

private val accessTokenRandom = SecureRandom()
private const val TOKEN_ALPHABET = "0123456789abcdef"

private fun newAccessToken(): String {
    val bytes = ByteArray(16)
    accessTokenRandom.nextBytes(bytes)
    return buildString(bytes.size * 2) {
        bytes.forEach { byte ->
            val value = byte.toInt() and 0xff
            append(TOKEN_ALPHABET[value ushr 4])
            append(TOKEN_ALPHABET[value and 0x0f])
        }
    }
}
