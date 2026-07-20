package ng1ok.yggdrasilmanager.ui.hwbp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import ng1ok.yggdrasilmanager.data.model.FileProcMapsSource
import ng1ok.yggdrasilmanager.data.model.HwbpBacktrace
import ng1ok.yggdrasilmanager.data.model.HwbpEvent
import ng1ok.yggdrasilmanager.data.model.HwbpObserveParams
import ng1ok.yggdrasilmanager.data.model.ProcMapsSource
import ng1ok.yggdrasilmanager.native.YggdrasilNative

internal sealed interface HwbpClientCommand {
    data class Observe(val params: HwbpObserveParams) : HwbpClientCommand

    data class Unobserve(
        val pid: Int,
        val targetAddr: Long,
    ) : HwbpClientCommand

    data class Config(
        val backtraceMode: String,
    ) : HwbpClientCommand
}

private val protocolJson = Json { ignoreUnknownKeys = true }
private val modeNames = mapOf("R" to 1, "W" to 2, "RW" to 3, "X" to 4)
internal const val DEFAULT_STACK_SIZE = 4096

internal fun interface HwbpLibraryTargetResolver {
    fun resolve(pid: Int, library: String, offset: Long, mapsSource: ProcMapsSource): Long
}

private val nativeLibraryTargetResolver = HwbpLibraryTargetResolver { pid, library, offset, source ->
    val mapsText = source.readMaps(pid)
        ?: throw IllegalArgumentException(
            "failed to read maps for pid=$pid " +
                "(process missing, no root, or SELinux blocked /proc/$pid/maps)"
        )
    YggdrasilNative.hwbpResolveLibraryTarget(pid, mapsText, library, offset).getOrThrow()
}

internal fun decodeClientCommand(
    text: String,
    mapsSource: ProcMapsSource = FileProcMapsSource,
    libraryTargetResolver: HwbpLibraryTargetResolver = nativeLibraryTargetResolver,
): Result<HwbpClientCommand> = runCatching {
    val root = protocolJson.parseToJsonElement(text).jsonObject
    when (val type = root.requiredString("type")) {
        "observe" -> decodeObserve(root, mapsSource, libraryTargetResolver)
        "unobserve" -> HwbpClientCommand.Unobserve(
            pid = root.requiredPositivePid(),
            targetAddr = root.requiredAddress("addr"),
        )
        "config" -> HwbpClientCommand.Config(
            backtraceMode = HwbpBacktrace.normalizeMode(root.requiredString("backtraceMode")),
        )
        else -> throw IllegalArgumentException("Unknown message type: $type")
    }
}

private fun decodeObserve(
    root: JsonObject,
    mapsSource: ProcMapsSource,
    libraryTargetResolver: HwbpLibraryTargetResolver,
): HwbpClientCommand.Observe {
    val modeName = root.requiredString("mode")
    val mode = requireNotNull(modeNames[modeName]) { "mode must be R, W, RW, or X" }
    val len = root["len"]?.jsonPrimitive?.intOrNull
        ?: throw IllegalArgumentException("len is required")
    require(len in setOf(1, 2, 4, 8)) { "len must be 1, 2, 4, or 8" }
    require(mode != 4 || len == 4) { "X mode requires len 4" }
    val stackSize = root["stackSize"]?.jsonPrimitive?.intOrNull ?: DEFAULT_STACK_SIZE
    require(stackSize in 0..65536) { "stackSize must be between 0 and 65536" }
    val singleThread = root["singleThread"]?.jsonPrimitive?.booleanOrNull ?: false
    val pid = root.requiredPositivePid()

    val lib = root["lib"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
    val offset = root.parseOptionalOffset("offset")
    val (targetAddr, resolvedLib, resolvedOffset) = when {
        lib != null -> {
            val off = offset ?: throw IllegalArgumentException("offset is required when lib is set")
            val resolved = libraryTargetResolver.resolve(pid, lib, off, mapsSource)
            Triple(resolved, lib, off)
        }
        else -> Triple(root.requiredAddress("addr"), null, null)
    }

    return HwbpClientCommand.Observe(
        HwbpObserveParams(
            pid = pid,
            targetAddr = targetAddr,
            mode = mode,
            len = len,
            flags = if (singleThread) 1 else 0,
            stackSize = stackSize,
            lib = resolvedLib,
            offset = resolvedOffset,
        )
    )
}

private fun JsonObject.parseOptionalOffset(name: String): Long? {
    val element = this[name] ?: return null
    val primitive = element.jsonPrimitive
    val raw = primitive.contentOrNull?.trim().orEmpty()
    if (raw.isEmpty()) return null
    require(!raw.startsWith('-')) { "$name must be non-negative" }
    primitive.longOrNull?.let { return it }
    return parseHexOrDecimal(raw, name)
}

private fun parseHexOrDecimal(raw: String, field: String): Long {
    val text = raw.trim()
    return if (text.startsWith("0x", ignoreCase = true) || text.any { it in 'a'..'f' || it in 'A'..'F' }) {
        val hex = text.removePrefix("0x").removePrefix("0X")
        hex.toULongOrNull(16)?.toLong()
            ?: throw IllegalArgumentException("$field must be a 64-bit hexadecimal value")
    } else {
        text.toULongOrNull()?.toLong()
            ?: throw IllegalArgumentException("$field must be a non-negative integer")
    }
}

private fun JsonObject.requiredString(name: String): String =
    this[name]?.jsonPrimitive?.content?.takeIf(String::isNotBlank)
        ?: throw IllegalArgumentException("$name is required")

private fun JsonObject.requiredPositivePid(): Int =
    (this["pid"]?.jsonPrimitive?.intOrNull
        ?: throw IllegalArgumentException("pid is required"))
        .also { require(it > 0) { "pid must be positive" } }

private fun JsonObject.requiredAddress(name: String): Long {
    val raw = requiredString(name)
    require(raw.startsWith("0x", ignoreCase = true)) { "$name must start with 0x" }
    return raw.drop(2).toULongOrNull(16)?.toLong()
        ?: throw IllegalArgumentException("$name must be a 64-bit hexadecimal value")
}

internal fun encodeState(
    points: List<HwbpObserveParams>,
    backtraceMode: String = HwbpBacktrace.MODE_FP,
): String = buildJsonObject {
    put("type", "state")
    put("backtraceMode", HwbpBacktrace.normalizeMode(backtraceMode))
    put("points", buildJsonArray {
        points.forEach { point ->
            add(buildJsonObject {
                put("pid", point.pid)
                put("addr", point.targetAddr.toHexAddress())
                put(
                    "mode",
                    modeNames.entries.singleOrNull { it.value == point.mode }?.key
                        ?: point.mode.toString()
                )
                put("len", point.len)
                put("flags", point.flags)
                put("stackSize", point.stackSize)
                if (!point.lib.isNullOrBlank() && point.offset != null) {
                    put("lib", point.lib)
                    put("offset", point.offset.toHexAddress())
                    put("display", point.displayTarget)
                }
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
    put("pstate", event.pstate.toHexAddress())
    put(
        "stack",
        buildJsonObject {
            put("addr", event.stackAddr.toHexAddress())
            put("requested", event.stackRequestedSize)
            put("copied", event.stackCopiedSize)
        }
    )
    put("backtraceMode", event.backtraceMode)
    if (event.backtraceNote != null) {
        put("backtraceNote", event.backtraceNote)
    } else {
        put("backtraceNote", JsonNull)
    }
    put(
        "backtrace",
        buildJsonArray {
            event.backtrace.forEach { frame ->
                add(buildJsonObject {
                    put("index", frame.index)
                    put("pc", frame.pc.toHexAddress())
                    put("label", frame.label)
                    if (frame.module != null) put("module", frame.module) else put("module", JsonNull)
                    if (frame.offset != null) {
                        put("offset", frame.offset.toHexAddress())
                    } else {
                        put("offset", JsonNull)
                    }
                })
            }
        }
    )
    put("regs", buildJsonArray {
        event.regs.forEach { add(JsonPrimitive(it.toHexAddress())) }
    })
}.toString()

internal fun encodeAck(result: Result<Unit>): String = buildJsonObject {
    put("type", "ack")
    put("ok", result.isSuccess)
    put("error", result.exceptionOrNull()?.message?.let(::JsonPrimitive) ?: JsonNull)
}.toString()

private fun Long.toHexAddress(): String =
    "0x${java.lang.Long.toUnsignedString(this, 16)}"
