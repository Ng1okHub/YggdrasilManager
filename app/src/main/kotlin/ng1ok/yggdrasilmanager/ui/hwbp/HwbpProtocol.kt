package ng1ok.yggdrasilmanager.ui.hwbp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import ng1ok.yggdrasilmanager.data.model.HwbpEvent
import ng1ok.yggdrasilmanager.data.model.HwbpObserveParams

internal sealed interface HwbpClientCommand {
    data class Observe(val params: HwbpObserveParams) : HwbpClientCommand

    data class Unobserve(
        val pid: Int,
        val targetAddr: Long,
    ) : HwbpClientCommand
}

private val protocolJson = Json { ignoreUnknownKeys = true }
private val modeNames = mapOf("R" to 1, "W" to 2, "RW" to 3, "X" to 4)

internal fun decodeClientCommand(text: String): Result<HwbpClientCommand> = runCatching {
    val root = protocolJson.parseToJsonElement(text).jsonObject
    when (val type = root.requiredString("type")) {
        "observe" -> decodeObserve(root)
        "unobserve" -> HwbpClientCommand.Unobserve(
            pid = root.requiredPositivePid(),
            targetAddr = root.requiredAddress(),
        )
        else -> throw IllegalArgumentException("Unknown message type: $type")
    }
}

private fun decodeObserve(root: JsonObject): HwbpClientCommand.Observe {
    val modeName = root.requiredString("mode")
    val mode = requireNotNull(modeNames[modeName]) { "mode must be R, W, RW, or X" }
    val len = root["len"]?.jsonPrimitive?.intOrNull
        ?: throw IllegalArgumentException("len is required")
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
            len = len,
            flags = if (singleThread) 1 else 0,
            stackSize = stackSize,
        )
    )
}

private fun JsonObject.requiredString(name: String): String =
    this[name]?.jsonPrimitive?.content?.takeIf(String::isNotBlank)
        ?: throw IllegalArgumentException("$name is required")

private fun JsonObject.requiredPositivePid(): Int =
    (this["pid"]?.jsonPrimitive?.intOrNull
        ?: throw IllegalArgumentException("pid is required"))
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
                put(
                    "mode",
                    modeNames.entries.singleOrNull { it.value == point.mode }?.key
                        ?: point.mode.toString()
                )
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

private fun Long.toHexAddress(): String =
    "0x${java.lang.Long.toUnsignedString(this, 16)}"
